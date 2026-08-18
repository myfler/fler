package com.ai.fler.core.mcp

import android.annotation.SuppressLint
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.ai.fler.core.analysis.DartCallGraphBuilder
import com.ai.fler.core.analysis.DartEntryResolver
import com.ai.fler.core.analysis.ClosureSlotResolver
import com.ai.fler.core.analysis.DartFieldSlots
import com.ai.fler.core.analysis.DartNameDisplay
import com.ai.fler.core.analysis.FunctionIndex
import com.ai.fler.core.analysis.IndirectCallScanner
import com.ai.fler.core.analysis.MethodCfg
import com.ai.fler.core.analysis.MethodStringLabels
import com.ai.fler.core.analysis.StringXrefScanner
import com.ai.fler.core.jni.CapstoneBindings
import com.ai.fler.core.jni.ElfParserBindings
import com.ai.fler.core.jni.KeystoneBindings
import com.ai.fler.core.service.AddressTranslator
import com.ai.fler.core.service.WorkDirectory
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.AsmBlockDao
import com.ai.fler.data.entity.Analysis
import com.ai.fler.data.dao.DartCallGraphDao
import com.ai.fler.data.dao.DartClassDao
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.LibraryDao
import com.ai.fler.data.dao.PpEntryDao
import com.ai.fler.data.dao.ProjectDao
import com.ai.fler.data.dao.DartObjectDao
import com.ai.fler.data.dao.EnumMapDao
import com.ai.fler.features.mcp.McpPatchService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MCP 工具处理器：把 fler 现有服务映射为 MCP 工具。
 * 所有只读工具在 Dispatchers.IO 上运行（由调用方调度）。
 */
@Singleton
class McpToolHandlers @Inject constructor(
    private val analysisDao: AnalysisDao,
    private val libraryDao: LibraryDao,
    private val dartClassDao: DartClassDao,
    private val dartMethodDao: DartMethodDao,
    private val ppEntryDao: PpEntryDao,
    private val asmBlockDao: AsmBlockDao,
    private val dartObjectDao: DartObjectDao,
    private val enumMapDao: EnumMapDao,
    private val projectDao: ProjectDao,
    private val addressTranslator: AddressTranslator,
    private val config: McpConfig,
    private val patchService: McpPatchService,
    private val engineMcp: EngineMcpToolRegistry,
    private val emulationMcp: EmulationMcpToolRegistry,
    private val axisResolver: AddressAxisResolver,
    private val dartCallGraphDao: DartCallGraphDao,
    private val callGraphBuilder: DartCallGraphBuilder,
    private val functionIndex: FunctionIndex,
    private val stringXrefScanner: StringXrefScanner,
    private val methodStringLabels: MethodStringLabels,
    private val indirectCallScanner: IndirectCallScanner,
    private val fridaTools: FridaMcpToolRegistry,
    private val workDirectory: WorkDirectory,
    @SuppressLint("StaticFieldLeak")
    @ApplicationContext private val context: Context,
) : McpResourceProvider {

    class McpTool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject,
        val handler: suspend (JsonObject) -> JsonElement,
    )

    // ========== 坐标换算辅助 ==========

    /** 请求级 DB 缓存：避免单个工具内重复 getById（Analysis 行数据无热点变化）。 */
    private class AnalysisCache(private val dao: AnalysisDao) {
        private val map = java.util.concurrent.ConcurrentHashMap<Long, Cached>()
        private class Cached(val analysis: Analysis, val ts: Long)

        suspend fun get(id: Long): Analysis? {
            map[id]?.let { c -> if (System.currentTimeMillis() - c.ts < TTL_MS) return c.analysis }
            val a = dao.getById(id)
            if (a != null) map[id] = Cached(a, System.currentTimeMillis())
            trim()
            return a
        }

        private fun trim() {
            val now = System.currentTimeMillis()
            val it = map.entries.iterator()
            while (it.hasNext()) if (now - it.next().value.ts >= TTL_MS) it.remove()
            while (map.size > MAX_SIZE) {
                val oldest = map.entries.minByOrNull { it.value.ts }?.key ?: break
                map.remove(oldest)
            }
        }

        companion object {
            private const val TTL_MS = 60_000L
            private const val MAX_SIZE = 64
        }
    }

    private val analysisCache = AnalysisCache(analysisDao)

    /** 缓存版 getById。 */
    private suspend fun cachedAnalysis(id: Long): Analysis? = analysisCache.get(id)

    /** 分析对应的 libapp.so 路径（坐标换算用）。 */
    private suspend fun libAppPath(analysisId: Long): String? =
        cachedAnalysis(analysisId)?.libappPath

    /** functionOffset(vaddr) → 文件偏移；so 不可用或地址无效时返回 null。
     *  歧义（同节偏差≠0，数字同时是文件偏移与 vaddr）时按 vaddr 解读取 altFileOffset，
     *  因为方法工具的 functionOffset 恒为 Blutter 给的 vaddr。 */
    private fun fileOffsetOf(soPath: String?, functionOffset: Long): Long? {
        if (soPath == null || functionOffset <= 0) return null
        val res = axisResolver.resolve(soPath, functionOffset) ?: return null
        return if (res.ambiguous) (res.altFileOffset ?: res.fileOffset) else res.fileOffset
    }

    /** 触发建图但不阻塞请求；建图完成后查询自动读到新边。 */
    private suspend fun ensureGraph(analysisId: Long) {
        callGraphBuilder.ensureAsync(analysisId)
    }

    // ========== 会话级「当前分析」（use_analysis 设定的默认 analysisId） ==========

    /** 每个 MCP 会话记住其当前分析；无会话头（原子 HTTP POST）用 _default。 */
    private val sessionDefaultAnalysis = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun sessionKey(): String =
        McpRequestContext.current()?.sessionId ?: "_default"

    /** 当前会话设定的默认 analysisId（未设置返回 null）。 */
    private suspend fun currentDefaultAnalysis(): Long? {
        val id = sessionDefaultAnalysis[sessionKey()] ?: return null
        // 行可能已被删除：失效兜底
        return if (cachedAnalysis(id) != null) id else null
    }

    /**
     * 统一解析 analysisId：显式参数优先，其次回退会话当前分析（use_analysis 设定）。
     * 都没有则抛清晰错误，引导先 use_analysis。
     */
    private suspend fun resolveAnalysisId(p: JsonObject, toolName: String): Long {
        p.long("analysisId")?.let { return it }
        currentDefaultAnalysis()?.let { return it }
        throw McpToolException(
            "$toolName: 缺少 analysisId。请先调用 use_analysis(analysisId) 设置当前会话的分析，" +
                "或在参数里显式传 analysisId"
        )
    }

    val tools: Map<String, McpTool> = buildMap {
        buildList {
            addAll(buildAnalysisTools())
            addAll(buildBrowseTools())
            addAll(buildDisasmTools())
            addAll(buildPatchTools())
            addAll(buildDeobfTools())
        }.forEach { this[it.name] = it }
        // Engine 能力自动暴露的工具（带 engine_ 前缀）
        engineMcp.buildTools().forEach { (k, v) -> this[k] = v }
        // 仿真工具（带 emu_ 前缀，Unicorn 会话/调用/寄存器/内存/断点）
        emulationMcp.buildTools().forEach { (k, v) -> this[k] = v }
        // Frida 动态调试工具（带 frida_ 前缀，root 方案 App 内闭环）
        fridaTools.buildTools().forEach { this[it.name] = it }
    }

    /** 工具是否对外暴露：emu_* 仿真工具默认隐藏，由 [McpConfig.emuToolsEnabled] 实时控制。 */
    fun isToolExposed(name: String): Boolean {
        if (name.startsWith("emu_")) return config.emuToolsEnabled.value
        return true
    }

    // ========== 参数读取辅助 ==========

    private fun JsonObject.long(key: String): Long? {
        val v = (this[key] as? JsonPrimitive)?.contentOrNull ?: return null
        return if (v.startsWith("0x", ignoreCase = true)) {
            v.substring(2).toLongOrNull(16)
        } else {
            v.toLongOrNull()
        }
    }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    // ========== 分析工具 ==========

    private fun buildAnalysisTools(): List<McpTool> = listOf(
        McpTool(
            name = "list_analyses",
            description = "列出 App 内所有 Blutter 分析记录（近 200 条）：含 id（analysisId，供后续 list_methods/get_method 等用）、类/方法/PP 计数、libapp.so 路径。分析对应一次 libapp.so 的 Blutter 恢复结果",
            inputSchema = buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) }
        ) { _ ->
            val rows = analysisDao.getRecentList(200)
            buildJsonArray {
                rows.forEach { a ->
                    addJsonObject {
                        put("id", a.id)
                        put("projectId", a.projectId)
                        put("libappPath", a.libappPath ?: "")
                        put("resultCode", a.resultCode)
                        put("classesCount", a.classesCount)
                        put("methodsCount", a.methodsCount)
                        put("ppEntriesCount", a.ppEntriesCount)
                        put("startedAt", a.startedAt)
                    }
                }
            }
        },
        McpTool(
            name = "get_analysis",
            description = "获取某次分析（analysisId 必填）的详情：libapp/libflutter 路径、类/方法/PP 计数、errorMessage，以及该分析涉及的 libraries 列表。分析 id 来自 list_analyses",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID，来自 list_analyses") }
                }
                putJsonArray("required") { add("analysisId") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失或非法")
            val a = cachedAnalysis(id) ?: return@McpTool buildJsonObject { put("found", false) }
            val libs = libraryDao.getByAnalysisIdList(id)
            buildJsonObject {
                put("found", true)
                put("id", a.id)
                put("projectId", a.projectId)
                put("libappPath", a.libappPath ?: "")
                put("libflutterPath", a.libflutterPath ?: "")
                put("resultCode", a.resultCode)
                put("errorMessage", a.errorMessage ?: "")
                put("classesCount", a.classesCount)
                put("methodsCount", a.methodsCount)
                put("ppEntriesCount", a.ppEntriesCount)
                putJsonArray("libraries") {
                    libs.forEach { l ->
                        addJsonObject {
                            put("name", l.libraryName)
                            put("path", l.path)
                            put("isDartSnapshot", l.isDartSnapshot)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "use_analysis",
            description = "把某次分析设为当前会话的默认分析（use_analysis(analysisId)）。设置后，后续 list_classes/list_methods/get_method/get_pp_entry/search_strings/get_method_callers/callees/get_pp_references 等工具可省略 analysisId，自动回退到当前分析。反复调用可切换。返回已选分析概要",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID，来自 list_analyses") }
                }
                putJsonArray("required") { add("analysisId") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失或非法")
            val a = cachedAnalysis(id) ?: return@McpTool buildJsonObject {
                put("ok", false)
                put("analysisId", id)
                put("reason", "分析不存在，请用 list_analyses 获取有效 id")
            }
            sessionDefaultAnalysis[sessionKey()] = id
            buildJsonObject {
                put("ok", true)
                put("analysisId", id)
                put("libappPath", a.libappPath ?: "")
                put("classesCount", a.classesCount)
                put("methodsCount", a.methodsCount)
                put("ppEntriesCount", a.ppEntriesCount)
            }
        },
        McpTool(
            name = "list_projects",
            description = "列出所有被分析的项目：id/名称/apk 路径/dartVersion/引擎版本/状态/更新时间。项目是分析的容器，每次 Blutter 分析属于某个项目",
            inputSchema = buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) }
        ) { _ ->
            val projects = projectDao.getAll().first()
            buildJsonObject {
                put("count", projects.size)
                putJsonArray("projects") {
                    projects.forEach { pr ->
                        addJsonObject {
                            put("id", pr.id)
                            put("name", pr.name)
                            put("apkPath", pr.apkPath)
                            put("dartVersion", pr.dartVersion ?: "")
                            put("engineVersion", pr.engineVersion ?: "")
                            put("status", pr.status)
                            put("updatedAt", pr.updatedAt)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "get_project",
            description = "获取项目详情与该项目下的全部分析记录（含各分析 id/计数/状态）。项目 id 来自 list_projects",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("projectId") { put("type", "integer"); put("description", "项目 ID，来自 list_projects") } }
                putJsonArray("required") { add("projectId") }
            }
        ) { p ->
            val projectId = p.long("projectId") ?: throw McpToolException("projectId 缺失或非法")
            val pr = projectDao.getById(projectId) ?: return@McpTool buildJsonObject { put("found", false) }
            val analyses = analysisDao.getByProjectIdList(projectId)
            buildJsonObject {
                put("found", true)
                put("id", pr.id)
                put("name", pr.name)
                put("apkPath", pr.apkPath)
                put("dartVersion", pr.dartVersion ?: "")
                put("engineVersion", pr.engineVersion ?: "")
                put("status", pr.status)
                put("updatedAt", pr.updatedAt)
                put("analysisCount", analyses.size)
                putJsonArray("analyses") {
                    analyses.forEach { a ->
                        addJsonObject {
                            put("id", a.id)
                            put("libappPath", a.libappPath ?: "")
                            put("resultCode", a.resultCode)
                            put("errorMessage", a.errorMessage ?: "")
                            put("classesCount", a.classesCount)
                            put("methodsCount", a.methodsCount)
                            put("ppEntriesCount", a.ppEntriesCount)
                            put("startedAt", a.startedAt)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "search_objects",
            description = "对象池对象搜索（引擎 objs.txt 轻量索引）：在对象类名与字段摘要中按子串搜索（不区分大小写），如搜「至尊」命中 Obj!qCb@b42021【至尊永久VIP】。返回对象地址/类名/字段摘要。analysisId 可省略（缺省用 use_analysis 设定的当前分析）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") }
                    putJsonObject("query") { put("type", "string"); put("description", "搜索子串（匹配类名或字段摘要），不区分大小写") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "返回上限（默认 100）") }
                }
                putJsonArray("required") { add("query") }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "search_objects")
            val query = p.str("query") ?: throw McpToolException("search_objects: 缺少 query")
            val limit = (p.int("limit") ?: 100).coerceIn(1, 1000)
            val rows = dartObjectDao.searchObjectsAnywhere(id, query, limit)
            buildJsonObject {
                put("count", rows.size)
                putJsonArray("objects") {
                    rows.forEach { o ->
                        addJsonObject {
                            put("objAddress", o.objAddress)
                            put("className", o.className ?: "")
                            put("fieldHint", o.fieldHint ?: "")
                        }
                    }
                }
            }
        },
        McpTool(
            name = "get_object",
            description = "按对象地址精确查对象池对象（引擎 objs.txt 轻量索引）：返回对象地址/类名/字段摘要。对象地址来自 search_objects 或 objs.txt。analysisId 可省略（缺省用 use_analysis 设定的当前分析）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") }
                    putJsonObject("objAddress") { put("type", "integer"); put("description", "对象地址（十进制或 0x 十六进制）") }
                }
                putJsonArray("required") { add("objAddress") }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "get_object")
            val addr = p.str("objAddress")?.toLongOrNull()
                ?: throw McpToolException("get_object: 缺少 objAddress")
            val rows = dartObjectDao.getByAddress(id, addr)
            buildJsonObject {
                put("found", rows.isNotEmpty())
                putJsonArray("objects") {
                    rows.forEach { o ->
                        addJsonObject {
                            put("objAddress", o.objAddress)
                            put("className", o.className ?: "")
                            put("fieldHint", o.fieldHint ?: "")
                        }
                    }
                }
            }
        },
        McpTool(
            name = "enum_lookup",
            description = "枚举索引映射查询（引擎 enum_map 表）：枚举对象地址不在 pp.txt 中，Blutter objs.txt 里每个枚举值对应一个常量对象（如 Obj!qCb@b42021【至尊永久VIP】level 5，其中 5 是枚举序号，【至尊永久VIP】是枚举值名）。本工具按枚举名/类名反查。analysisId 可省略（缺省用 use_analysis 设定的当前分析）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") }
                    putJsonObject("className") { put("type", "string"); put("description", "枚举类名精确匹配（可选）") }
                    putJsonObject("query") { put("type", "string"); put("description", "枚举值名子串（可选，如「至尊」）") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "返回上限（默认 100）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "enum_lookup")
            val className = p.str("className")
            val query = p.str("query")
            val limit = (p.int("limit") ?: 100).coerceIn(1, 1000)
            val rows = when {
                className != null -> enumMapDao.getByClass(id, className)
                query != null -> enumMapDao.searchByName(id, query, limit)
                else -> enumMapDao.getByAnalysisIdList(id).take(limit)
            }
            buildJsonObject {
                put("count", rows.size)
                putJsonArray("enums") {
                    rows.forEach { e ->
                        addJsonObject {
                            put("className", e.className)
                            put("enumIndex", e.enumIndex)
                            put("enumName", e.enumName)
                        }
                    }
                }
            }
        },
    )

    // ========== 浏览工具 ==========

    private fun buildBrowseTools(): List<McpTool> = listOf(
        McpTool(
            name = "list_classes",
            description = "列出某次分析的全部 Dart 类：id/className/superClass/方法数。analysisId 可省略（缺省用 use_analysis 设定的当前分析）。Dart 类层级是 Blutter 恢复的 Dart 语义结构（区别于 ELF 符号）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") } }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "list_classes")
            val classes = dartClassDao.getByAnalysisIdList(id)
            // SQL 下推：方法数在 DB 侧 GROUP BY 统计，避免全量载入方法表
            val methodCounts = dartMethodDao.countMethodsGroupedByClass(id)
                .associate { it.classId to it.methodCount }
            buildJsonArray {
                classes.forEach { c ->
                    addJsonObject {
                        put("id", c.id)
                        put("className", c.className)
                        put("superClass", c.superClass ?: "")
                        put("methodCount", methodCounts[c.id] ?: 0)
                    }
                }
            }
        },
        McpTool(
            name = "list_methods",
            description = "分页列出某次分析的 Dart 方法；可按 classId/名称过滤。analysisId 可省略（缺省用 use_analysis 设定的当前分析）。返回方法 id/className/methodName/functionOffset（vaddr）/fileOffset（若 vaddr≠文件偏移则换算）/functionSize。functionOffset 恒为 vaddr，作反汇编偏移时需经 translate_address 或 fileOffset 字段。分页默认 page=1 / pageSize=200（上限 1000）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") }
                    putJsonObject("classId") { put("type", "integer"); put("description", "类 ID（可选），只列出该类的方法") }
                    putJsonObject("name") { put("type", "string"); put("description", "方法名子串（可选），模糊过滤") }
                    putJsonObject("page") { put("type", "integer"); put("description", "页码，从 1 开始（默认 1）") }
                    putJsonObject("pageSize") { put("type", "integer"); put("description", "每页条数 1..1000（默认 200）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "list_methods")
            val classId = p.long("classId")
            val name = p.str("name")
            val page = (p.int("page") ?: 1).coerceAtLeast(1)
            val pageSize = (p.int("pageSize") ?: 200).coerceIn(1, 1000)

            // sub_<vaddr> 显示名反查：name 命中 sub_ 形态时按 function_offset 精确定位单方法
            val subVaddr = DartNameDisplay.parseSubName(name)
            if (subVaddr != null) {
                val hit = dartMethodDao.getMethodWithClassByOffset(id, subVaddr)
                val soPath = libAppPath(id)
                return@McpTool buildJsonObject {
                    put("total", if (hit != null) 1 else 0)
                    put("page", page)
                    put("pageSize", pageSize)
                    put("truncated", false)
                    put("resolvedSubName", name)
                    putJsonArray("methods") {
                        if (hit != null) {
                            addJsonObject {
                                put("id", hit.method.id)
                                put("classId", hit.method.classId)
                                put("className", hit._className)
                                put("methodName", DartNameDisplay.displayMethodName(hit.method.methodName, hit.method.functionOffset))
                                put("functionOffset", hit.method.functionOffset ?: 0)
                                fileOffsetOf(soPath, hit.method.functionOffset ?: 0)?.let { put("fileOffset", it) }
                                put("functionSize", hit.method.functionSize ?: 0)
                            }
                        }
                    }
                }
            }

            // SQL 下推：DB 侧过滤 + 分页，避免全表载入内存
            val total = dartMethodDao.countMethodsWithClass(id, name, classId)
            val offset = ((page - 1) * pageSize).coerceAtMost(total)
            val rows = dartMethodDao.searchMethodsWithClass(id, name, classId, pageSize, offset)
            val soPath = libAppPath(id)

            buildJsonObject {
                put("total", total)
                put("page", page)
                put("pageSize", pageSize)
                put("truncated", offset + rows.size < total)
                putJsonArray("methods") {
                    rows.forEach { r ->
                        addJsonObject {
                            put("id", r.method.id)
                            put("classId", r.method.classId)
                            put("className", r._className)
                            put("methodName", DartNameDisplay.displayMethodName(r.method.methodName, r.method.functionOffset))
                            put("functionOffset", r.method.functionOffset ?: 0)
                            fileOffsetOf(soPath, r.method.functionOffset ?: 0)?.let { put("fileOffset", it) }
                            put("functionSize", r.method.functionSize ?: 0)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "get_method",
            description = "获取单个 Dart 方法详情与 Blutter 反汇编伪代码（src_code 大字段默认截断，includeSrc=true 返回完整）。用 methodId 或 name 定位；methodId 来自 list_methods。analysisId 可省略（缺省用 use_analysis 设定的当前分析）。src_code 为 Blutter 恢复的反汇编，适合读业务逻辑/关键算法",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") }
                    putJsonObject("methodId") { put("type", "integer"); put("description", "方法 ID（来自 list_methods）；与 name 二选一") }
                    putJsonObject("name") { put("type", "string"); put("description", "方法名精确匹配（如 UserHome._fetchList）；与 methodId 二选一") }
                    putJsonObject("includeSrc") { put("type", "boolean"); put("description", "true=返回完整 src_code（默认截断）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "get_method")
            val methodId = p.long("methodId")
            val name = p.str("name")
            val full = p.str("includeSrc") == "true"
            // SQL 命中：methodId 精确 / name 精确（sub_<vaddr> 显示名反查 vaddr）/ LIKE 兜底
            val match = when {
                methodId != null -> dartMethodDao.getMethodWithClassById(methodId)
                name != null -> {
                    val subVaddr = DartNameDisplay.parseSubName(name)
                    if (subVaddr != null) dartMethodDao.getMethodWithClassByOffset(id, subVaddr)
                    else dartMethodDao.getMethodWithClassByName(id, name)
                        ?: dartMethodDao.searchMethodsWithClass(id, name, null, 1, 0).firstOrNull()
                }
                else -> null
            } ?: return@McpTool buildJsonObject { put("found", false) }

            val m = match.method
            val src = m.srcCode ?: ""
            val capped = if (!full && src.length > MAX_SRC) src.take(MAX_SRC) else src
            val soPath = libAppPath(id)
            // asm_blocks 存档（标准 DumpCode 格式）：src_code 为空壳占位（等于方法名）
            // 时回退取 asm 完整反汇编，保证空壳方法也能读到语义注释版
            val block = asmBlockDao.getByMethodId(id, m.id)
            val asmCode = block?.body?.takeIf { it.isNotBlank() }
            buildJsonObject {
                put("found", true)
                put("id", m.id)
                put("classId", m.classId)
                put("className", match._className)
                put("methodName", DartNameDisplay.displayMethodName(m.methodName, m.functionOffset))
                put("rawMethodName", m.methodName)
                put("functionOffset", m.functionOffset ?: 0)
                fileOffsetOf(soPath, m.functionOffset ?: 0)?.let { put("fileOffset", it) }
                put("functionSize", m.functionSize ?: 0)
                put("srcTruncated", !full && src.length > MAX_SRC)
                put("srcCode", capped)
put("asmCode", if (full) asmCode else asmCode?.take(MAX_SRC))
                put("asmUrl", block?.url)
                put("asmSize", block?.size ?: 0)
            }
        },
        McpTool(
            name = "get_asm_code",
            description = "获取某方法的 asm_blocks 完整反汇编存档（引擎直写表，标准 DumpCode 格式，含 IL 语义注释 + pp 解引用）。与 get_method 的 src_code（裸指令）互补；src_code 为空壳时 asm_blocks 是唯一可读反汇编。定位方式同 get_method：methodId 或 name（精确，支持 sub_<vaddr>）。返回 JSON：vaddr/size/url/body + 关联方法元数据；无块时返回 found=false。analysisId 可省略（缺省 use_analysis）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") }
                    putJsonObject("methodId") { put("type", "integer"); put("description", "方法 ID（来自 list_methods）；与 name 二选一") }
                    putJsonObject("name") { put("type", "string"); put("description", "方法名精确匹配（如 EZa::_anon_closure_13141c4）；与 methodId 二选一") }
                    putJsonObject("includeBody") { put("type", "boolean"); put("description", "true=返回完整 body（默认截断）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "get_asm_code")
            val methodId = p.long("methodId")
            val name = p.str("name")
            val full = p.str("includeBody") == "true"
            val match = when {
                methodId != null -> dartMethodDao.getMethodWithClassById(methodId)
                name != null -> {
                    val subVaddr = DartNameDisplay.parseSubName(name)
                    if (subVaddr != null) dartMethodDao.getMethodWithClassByOffset(id, subVaddr)
                    else dartMethodDao.getMethodWithClassByName(id, name)
                        ?: dartMethodDao.searchMethodsWithClass(id, name, null, 1, 0).firstOrNull()
                }
                else -> null
            }
            val block = if (match != null) {
                asmBlockDao.getByMethodId(id, match.method.id)
                    ?: asmBlockDao.getByVaddr(id, match.method.functionOffset ?: 0)
            } else null
            if (block == null) {
                return@McpTool buildJsonObject {
                    put("found", false)
                    put("reason", if (match == null) "method not found" else "no asm_block for this method (empty shell / engine had no disassembly)")
                }
            }
            val body = block.body
            val cappedBody = if (!full && body.length > MAX_SRC) body.take(MAX_SRC) else body
            buildJsonObject {
                put("found", true)
                put("vaddr", block.vaddr)
                put("size", block.size)
                put("url", block.url)
                put("bodyTruncated", !full && body.length > MAX_SRC)
                put("body", cappedBody)
                put("methodId", block.methodId)
                put("className", match?._className)
                put("methodName", match?.method?.methodName)
                put("functionOffset", match?.method?.functionOffset ?: 0)
            }
        },
        McpTool(
            name = "get_pp_entry",
            description = "按 pp 偏移（vmOffset）查 Dart 对象池条目：type/description（可读描述）/fileOffset/引用它的方法数。用途：确认 search_strings / find_bool_getters 拿到的槽是否为目标（如字符串/字段/stub），并作为 scan_pool_refs 的目标槽输入。混淆包的槽描述常为 Field/Stub 形态（如 'static late'），字符串槽 type 为 String。支持两种定位：1) ppOffset 精确查槽；2) query 按 description/type 内容子串反查（不区分大小写）——用于表无记录但代码有引用（如 .rodata 业务串）时先按内容找到槽。analysisId 可省略（缺省 use_analysis）。ppOffset 支持十进制或 0x 十六进制",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") }
                    putJsonObject("ppOffset") { put("type", "integer"); put("description", "对象池偏移 vmOffset，hex（0x..）或十进制（与 query 二选一）") }
                    putJsonObject("query") { put("type", "string"); put("description", "按描述内容子串反查槽（与 ppOffset 二选一，不区分大小写）") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "query 反查上限 1..200（默认 20）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "get_pp_entry")
            val off = p.long("ppOffset")
            val query = p.str("query")
            val limit = (p.int("limit") ?: 20).coerceIn(1, 200)
            val rows: List<com.ai.fler.data.entity.PpEntry> = when {
                off != null -> ppEntryDao.getPpByVmOffset(id, off)
                query != null -> searchStringSlots(id, query, limit)
                else -> throw McpToolException("ppOffset 或 query 至少提供其一")
            }
            val soPath = libAppPath(id)
            buildJsonObject {
                put("count", rows.size)
                put("mode", if (off != null) "by_offset" else "by_content")
                put("truncated", query != null && rows.size == limit)
                putJsonArray("entries") {
                    rows.forEach { e ->
                        addJsonObject {
                            put("ppOffset", e.vmOffset)
                            put("type", e.type ?: "")
                            put("description", e.description ?: "")
                            put("fileOffset", e.fileOffset)
                            put("callerCount", e.callerCount)
                            fileOffsetOf(soPath, e.vmOffset)?.let { put("soFileOffset", it) }
                        }
                    }
                }
            }
        },
        McpTool(
            name = "search_strings",
            description = "在某次分析的 Dart 字符串常量中搜索子串（query 必填，不区分大小写）。返回 ppOffset/description/fileOffset。用途：1) 反混淆入口——用业务关键词（如 'VIP'/'剩余时长'/'登录'）定位字符串，拿到 vmOffset 后交给 scan_pool_refs（ppOffsets 直传）或 string_xrefs 做真实机器码交叉引用；2) 定位渠道/URL/错误提示。注意：对无 type='String' 条目的混淆包自动回退到「description/type 含引号字符串」的槽做内存过滤（与 scan_pool_refs 的 query 语义一致），不再恒 0。analysisId 可省略（缺省 use_analysis）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") }
                    putJsonObject("query") { put("type", "string"); put("description", "搜索关键词，不区分大小写") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "返回上限 1..500（默认 100）") }
                }
                putJsonArray("required") { add("query") }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "search_strings")
            val q = p.str("query") ?: throw McpToolException("query 缺失")
            val limit = (p.int("limit") ?: 100).coerceIn(1, 500)
            val rows = searchStringSlots(id, q, limit)
            val total = if (ppEntryDao.countStringsByAnalysisId(id) > 0) rows.size else 0
            buildJsonObject {
                put("count", rows.size)
                put("fallback", total == 0 && rows.isNotEmpty())
                put("truncated", rows.size == limit)
                putJsonArray("strings") {
                    rows.forEach { e ->
                        addJsonObject {
                            put("ppOffset", e.vmOffset)
                            put("description", e.description ?: "")
                            put("type", e.type ?: "")
                            put("fileOffset", e.fileOffset)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "get_class",
            description = "获取某个 Dart 类详情（classId 或 className 二选一）：superClass + 该类全部方法（methodName/functionOffset/fileOffset/size）。analysisId 可省略（缺省用 use_analysis 设定的当前分析）。用于按类梳理业务方法面",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") }
                    putJsonObject("classId") { put("type", "integer"); put("description", "类 ID（与 className 二选一）") }
                    putJsonObject("className") { put("type", "string"); put("description", "类名（与 classId 二选一，如 UserHome）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "get_class")
            val classId = p.long("classId")
            val className = p.str("className")
            val cls = dartClassDao.getByAnalysisIdList(id).firstOrNull {
                (classId != null && it.id == classId) ||
                    (className != null && it.className.equals(className, ignoreCase = true))
            } ?: return@McpTool buildJsonObject { put("found", false) }
            val methods = dartMethodDao.getMethodsByClassIdWithClass(id, cls.id)
            val soPath = libAppPath(id)
            buildJsonObject {
                put("found", true)
                put("id", cls.id)
                put("className", cls.className)
                put("superClass", cls.superClass ?: "")
                put("methodCount", methods.size)
                putJsonArray("methods") {
                    methods.forEach { m ->
                        addJsonObject {
                            put("id", m.method.id)
                            put("methodName", DartNameDisplay.displayMethodName(m.method.methodName, m.method.functionOffset))
                            put("functionOffset", m.method.functionOffset ?: 0)
                            fileOffsetOf(soPath, m.method.functionOffset ?: 0)?.let { put("fileOffset", it) }
                            put("functionSize", m.method.functionSize ?: 0)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "list_strings",
            description = "分页列出某次分析的全部字符串常量（SQL 下推）：ppOffset/description/fileOffset。反混淆工作流：分页浏览发现业务关键词槽后，用 get_pp_entry 确认、用 string_xrefs（按串子串查引用）或 scan_pool_refs（按槽偏移查引用）定位使用方。数据量大（数万条）请优先 search_strings 按关键词定位。分页默认 page=1 / pageSize=200（上限 1000）。注意：部分大 Flutter 包无 String 类型条目时 total=0 属正常",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") }
                    putJsonObject("page") { put("type", "integer"); put("description", "页码，从 1 开始（默认 1）") }
                    putJsonObject("pageSize") { put("type", "integer"); put("description", "每页条数 1..1000（默认 200）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "list_strings")
            val page = (p.int("page") ?: 1).coerceAtLeast(1)
            val pageSize = (p.int("pageSize") ?: 200).coerceIn(1, 1000)
            // 优先 type='String' 的 SQL 下推；若无（Blutter 未建 strings 表）则全量 pp 里挑引号字符串分页
            val typedTotal = ppEntryDao.countStringsByAnalysisId(id)
            val rows: List<com.ai.fler.data.entity.PpEntry>
            val total: Int
            if (typedTotal > 0) {
                val offset = ((page - 1) * pageSize).coerceAtMost(typedTotal)
                rows = ppEntryDao.getStringsByAnalysisIdPaged(id, pageSize, offset)
                total = typedTotal
            } else {
                val quoted = ppEntryDao.getByAnalysisIdList(id).filter {
                    it.description?.contains('"') == true || it.type?.contains('"') == true
                }
                total = quoted.size
                val offset = ((page - 1) * pageSize).coerceAtMost(total)
                rows = quoted.subList(offset, minOf(offset + pageSize, total))
            }
            buildJsonObject {
                put("total", total)
                put("page", page)
                put("pageSize", pageSize)
                put("truncated", (page - 1) * pageSize + rows.size < total)
                putJsonArray("strings") {
                    rows.forEach { e ->
                        addJsonObject {
                            put("ppOffset", e.vmOffset)
                            put("description", e.description ?: "")
                            put("fileOffset", e.fileOffset)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "get_method_callers",
            description = "反查哪些 Dart 方法调用了目标方法（methodName 为被调方法名子串，不区分大小写，基于真实调用图 dart_call_edges）。analysisId 可省略（缺省用 use_analysis 设定的当前分析）。返回调用方 name=类.方法 / callerVaddr / callTargetVaddr / siteVaddr，附 graphBuilt/edgeCount/isBuilding。适合从被调方法向上找真实调用链。analysisId 无效时返回 analysisExists=false；图未就绪时返回 graphBuilt=false + isBuilding，请稍后重查",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") }
                    putJsonObject("methodName") { put("type", "string"); put("description", "被调方法名子串，不区分大小写（如 _fetchList）") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "返回上限 1..500（默认 100）") }
                }
                putJsonArray("required") { add("methodName") }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "get_method_callers")
            val name = p.str("methodName") ?: throw McpToolException("methodName 缺失")
            val limit = (p.int("limit") ?: 100).coerceIn(1, 500)
            ensureGraph(id)
            val progress = currentProgress()
            progress.report(0.1f, "开始查询调用方: $name")
            val analysisExists = cachedAnalysis(id) != null
            val res = callGraphBuilder.findCallersByName(id, name, limit)
            if (!analysisExists) {
                return@McpTool buildJsonObject {
                    put("count", 0)
                    put("graphBuilt", false)
                    put("analysisExists", false)
                    put("message", "分析不存在（analysisId=$id），请用 list_analyses 获取有效 id")
                }
            }
            if (res == null) {
                return@McpTool buildJsonObject {
                    put("count", 0)
                    put("graphBuilt", false)
                    put("analysisExists", true)
                    put("isBuilding", callGraphBuilder.isBuilding(id))
                    put("message", "调用图未就绪（analysisId=$id），请稍后重查")
                }
            }
            val infos = res.callers
            buildJsonObject {
                put("count", infos.size)
                put("truncated", infos.size == limit)
                put("graphSource", "DART_CALL_GRAPH")
                put("graphBuilt", true)
                put("edgeCount", res.edgeCount)
                put("isBuilding", callGraphBuilder.isBuilding(id))
                putJsonArray("callers") {
                    infos.forEach { c ->
                        addJsonObject {
                            put("caller", c.name)
                            put("callerVaddr", c.vaddr)
                            put("callTargetVaddr", c.targetVaddr)
                            put("siteVaddr", c.siteVaddr)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "get_method_callees",
            description = "列出某 Dart 方法内部真实调用的子方法（基于调用图 dart_call_edges）。methodId 或 methodName 二选一（methodName 为精确匹配，如 UserHome._fetchList）。analysisId 可省略（缺省用 use_analysis 设定的当前分析）。返回被调 name/类名/calleeVaddr/调用类型（DIRECT_CALL/DIRECT_BRANCH），附 graphBuilt/edgeCount/isBuilding。analysisId 无效时返回 analysisExists=false；图未就绪时返回 graphBuilt=false + isBuilding，请稍后重查",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") }
                    putJsonObject("methodId") { put("type", "integer"); put("description", "方法 ID（来自 list_methods）；与 methodName 二选一") }
                    putJsonObject("methodName") { put("type", "string"); put("description", "方法名精确匹配（如 UserHome._fetchList）；与 methodId 二选一") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "返回上限 1..500（默认 100）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "get_method_callees")
            val methodId = p.long("methodId")
            val methodName = p.str("methodName")
            val limit = (p.int("limit") ?: 100).coerceIn(1, 500)
            val callerId = when {
                methodId != null -> methodId
                methodName != null -> {
                    val subVaddr = DartNameDisplay.parseSubName(methodName)
                    val found = if (subVaddr != null) dartMethodDao.getMethodWithClassByOffset(id, subVaddr)
                    else dartMethodDao.getMethodWithClassByName(id, methodName)
                    found?.method?.id ?: throw McpToolException("未找到方法: $methodName")
                }
                else -> throw McpToolException("需提供 methodId 或 methodName")
            }
            ensureGraph(id)
            currentProgress().report(0.1f, "开始查询被调方法")
            if (cachedAnalysis(id) == null) {
                return@McpTool buildJsonObject {
                    put("count", 0)
                    put("graphBuilt", false)
                    put("analysisExists", false)
                    put("message", "分析不存在（analysisId=$id），请用 list_analyses 获取有效 id")
                }
            }
            if (!callGraphBuilder.isBuilt(id)) {
                return@McpTool buildJsonObject {
                    put("count", 0)
                    put("graphBuilt", false)
                    put("analysisExists", true)
                    put("isBuilding", callGraphBuilder.isBuilding(id))
                    put("message", "调用图未就绪（analysisId=$id），请稍后重查")
                }
            }
            val callees = dartCallGraphDao.calleesOf(id, callerId, limit)
            val edgeCount = callGraphBuilder.edgeCount(id)
            buildJsonObject {
                put("count", callees.size)
                put("truncated", callees.size == limit)
                put("graphSource", "DART_CALL_GRAPH")
                put("graphBuilt", true)
                put("edgeCount", edgeCount)
                put("isBuilding", callGraphBuilder.isBuilding(id))
                putJsonArray("callees") {
                    callees.forEach { c ->
                        addJsonObject {
                            put("callee", c.name)
                            put("calleeVaddr", c.vaddr)
                            put("kind", c.kind)
                            put("siteVaddr", c.siteVaddr)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "dart_rebuild_call_graph",
            description = "强制全量重建某次分析的 Dart 调用图（先清空旧边再重建，供修复/升级建图逻辑后刷新边表）。同步等待完成返回边数。analysisId 可省略（缺省用 use_analysis 设定的当前分析）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "dart_rebuild_call_graph")
            currentProgress().report(0.2f, "重建调用图 analysis=$id")
            val n = callGraphBuilder.build(id)
            buildJsonObject {
                put("analysisId", id)
                put("edgeCount", n)
                put("built", true)
            }
        },
        McpTool(
            name = "dart_call_graph_status",
            description = "查询某次分析 Dart 调用图（真实交叉引用）的构建状态：built=是否已建完（含 0 边情形）、edgeCount=边数、building=是否正在后台构建。analysisId 可省略（缺省用 use_analysis 设定的当前分析）。用于确认建图是否完成 / 何时可查 get_method_callers/get_method_callees",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "dart_call_graph_status")
            currentProgress().report(0.2f, "查询调用图构建状态")
            val count = dartCallGraphDao.countByAnalysisId(id)
            buildJsonObject {
                put("analysisId", id)
                put("analysisExists", cachedAnalysis(id) != null)
                put("built", callGraphBuilder.hasCompleted(id) || count > 0)
                put("edgeCount", count)
                put("building", callGraphBuilder.isBuilding(id))
            }
        },
        McpTool(
            name = "get_pp_references",
            description = "反查哪些 Dart 方法在 Blutter src_code 文本中引用了指定 pp 偏移（自动拼 [pp+0x..] 匹配）。注意：这是对 Blutter 反汇编文本的 SQL 子串搜索，仅适用未混淆包（src_code 可读、槽引用形态规整）；混淆包 src_code 大字段常为空或形态错配，应改用 string_xrefs / scan_pool_refs（真实 Capstone 机器码扫描，支持大槽双指令）。analysisId 可省略（缺省 use_analysis）。返回引用方法 id/className/methodName/functionOffset/fileOffset + target 串",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") }
                    putJsonObject("ppOffset") { put("type", "integer"); put("description", "对象池偏移 vmOffset，hex（0x..）或十进制") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "返回上限 1..500（默认 100）") }
                }
                putJsonArray("required") { add("ppOffset") }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "get_pp_references")
            val off = p.long("ppOffset") ?: throw McpToolException("ppOffset 缺失")
            val limit = (p.int("limit") ?: 100).coerceIn(1, 500)
            val target = "[pp+0x" + off.toString(16) + "]"
            val rows = dartMethodDao.searchSrcWithClass(id, target, limit)
            val soPath = libAppPath(id)
            buildJsonObject {
                put("target", target)
                put("count", rows.size)
                put("truncated", rows.size == limit)
                putJsonArray("references") {
                    rows.forEach { r ->
                        addJsonObject {
                            put("id", r.method.id)
                            put("className", r._className)
                            put("methodName", DartNameDisplay.displayMethodName(r.method.methodName, r.method.functionOffset))
                            put("functionOffset", r.method.functionOffset ?: 0)
                            fileOffsetOf(soPath, r.method.functionOffset ?: 0)?.let { put("fileOffset", it) }
                        }
                    }
                }
            }
        },
        McpTool(
            name = "analyze_method",
            description = "一站式分析单个 Dart 方法：一次返回方法详情（src_code 截断）+ 调用方 callers + 被调 callees + PP 引用 + 关键字符串，替代 get_method + get_method_callers + get_method_callees + get_pp_references 多连调用。用 methodId 或 methodName 定位（methodName 为精确匹配，如 UserHome._fetchList）。analysisId 可省略（缺省用 use_analysis 设定的当前分析）。图未就绪时 graphBuilt=false 且 callers/callees 为空数组，请稍后重查",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选，缺省用 use_analysis 设定的当前分析）") }
                    putJsonObject("methodId") { put("type", "integer"); put("description", "方法 ID（来自 list_methods）；与 methodName 二选一") }
                    putJsonObject("methodName") { put("type", "string"); put("description", "方法名精确匹配（如 UserHome._fetchList）；与 methodId 二选一") }
                    putJsonObject("includeSrc") { put("type", "boolean"); put("description", "true=返回完整 src_code（默认截断）") }
                    putJsonObject("callerLimit") { put("type", "integer"); put("description", "callers/callees 返回上限 1..50（默认 10）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "analyze_method")
            val methodId = p.long("methodId")
            val name = p.str("methodName")
            val full = p.str("includeSrc") == "true"
            val edgeLimit = (p.int("callerLimit") ?: 10).coerceIn(1, 50)

            val match = when {
                methodId != null -> dartMethodDao.getMethodWithClassById(methodId)
                name != null -> {
                    val subVaddr = DartNameDisplay.parseSubName(name)
                    if (subVaddr != null) dartMethodDao.getMethodWithClassByOffset(id, subVaddr)
                    else dartMethodDao.getMethodWithClassByName(id, name)
                        ?: dartMethodDao.searchMethodsWithClass(id, name, null, 1, 0).firstOrNull()
                }
                else -> null
            } ?: return@McpTool buildJsonObject { put("found", false) }

            val m = match.method
            val src = m.srcCode ?: ""
            val capped = if (!full && src.length > MAX_SRC) src.take(MAX_SRC) else src
            val soPath = libAppPath(id)
            val graphBuilt = callGraphBuilder.isBuilt(id)
            val edgeCount = callGraphBuilder.edgeCount(id)
            val isBuilding = callGraphBuilder.isBuilding(id)

            // 图未就绪时静默触发建图，但本次不阻塞等待
            if (!graphBuilt) ensureGraph(id)

            // asm_blocks 存档（标准 DumpCode 格式）：src_code 空壳时回退补充
            val block = asmBlockDao.getByMethodId(id, m.id)
            val asmCode = block?.body?.takeIf { it.isNotBlank() }

            // 只取 src 中确实出现的 [pp+0x..] 偏移，避免全库模糊匹配
            val ppOffsets = Regex("\\[pp\\+0x([0-9a-fA-F]+)\\]")
                .findAll(capped)
                .mapNotNull { it.groupValues[1].toLongOrNull(16) }
                .distinct()
                .take(10)
                .toList()
            val ppRefs = ArrayList<Pair<String, String>>(ppOffsets.size)
            for (off in ppOffsets) {
                val e = ppEntryDao.getPpByVmOffset(id, off).firstOrNull()
                ppRefs.add(off.toString(16) to (e?.description?.take(80) ?: ""))
            }

            // 真实机器码扫描方法 body 区间，提取引用的字符串槽作为业务标签
            val labels = if (soPath != null) {
                val text = textSection(soPath)
                if (text != null) {
                    val start = m.functionOffset ?: 0
                    val end = if ((m.functionSize ?: 0) > 0) start + (m.functionSize ?: 0) else 0
                    if (start > 0) methodStringLabels.scanLabels(id, soPath, text, start, end) else emptyList()
                } else emptyList()
            } else emptyList()

            // callers/callees 在挂起点计算，避免在 lambda 内调 suspend。
            // 用显示名（sub_<vaddr>）查询：匿名方法的原始 <anonymous closure> 与重建后的边名不匹配
            val callerQuery = DartNameDisplay.displayMethodName(m.methodName, m.functionOffset)
            val callerList = if (graphBuilt) {
                callGraphBuilder.findCallersByName(id, callerQuery, edgeLimit)
                    ?.callers?.take(edgeLimit).orEmpty()
            } else emptyList()
            val calleeList = if (graphBuilt) dartCallGraphDao.calleesOf(id, m.id, edgeLimit) else emptyList()

            buildJsonObject {
                put("found", true)
                put("id", m.id)
                put("classId", m.classId)
                put("className", match._className)
                put("methodName", DartNameDisplay.displayMethodName(m.methodName, m.functionOffset))
                put("rawMethodName", m.methodName)
                put("functionOffset", m.functionOffset ?: 0)
                fileOffsetOf(soPath, m.functionOffset ?: 0)?.let { put("fileOffset", it) }
                put("functionSize", m.functionSize ?: 0)
                put("srcTruncated", !full && src.length > MAX_SRC)
                put("srcCode", capped)
                put("asmCode", if (full) asmCode else asmCode?.take(MAX_SRC))
                put("asmUrl", block?.url)
                put("asmSize", block?.size ?: 0)
                put("graphSource", "DART_CALL_GRAPH")
                put("graphBuilt", graphBuilt)
                put("edgeCount", edgeCount)
                put("isBuilding", isBuilding)
                if (graphBuilt) {
                    putJsonArray("callers") {
                        callerList.forEach { c ->
                            addJsonObject {
                                put("caller", c.name)
                                put("callerVaddr", c.vaddr)
                                put("siteVaddr", c.siteVaddr)
                            }
                        }
                    }
                    putJsonArray("callees") {
                        calleeList.forEach { c ->
                            addJsonObject {
                                put("callee", c.name)
                                put("calleeVaddr", c.vaddr)
                                put("kind", c.kind)
                            }
                        }
                    }
                }
                putJsonArray("ppRefs") {
                    ppRefs.forEach { (off, desc) ->
                        addJsonObject {
                            put("ppOffset", "0x$off")
                            put("description", desc)
                        }
                    }
                }
                putJsonArray("stringLabels") {
                    labels.forEach { l ->
                        addJsonObject {
                            put("ppOffset", "0x${l.ppOffset.toString(16)}")
                            put("text", l.text)
                        }
                    }
                }
                closureSlotFor(id, m.functionOffset)?.let { cs ->
                    put("closure", buildJsonObject {
                        put("owner", cs.owner)
                        if (cs.library != null) put("library", cs.library)
                        put("parentVaddr", cs.parentVaddr)
                        put("isStatic", cs.isStatic)
                        put("displayName", cs.displayName)
                        put("rawValue", cs.rawValue.take(160))
                    })
                }
            }
        },
    )

    // ========== 反汇编 / ELF / 地址工具 ==========

    private fun buildDisasmTools(): List<McpTool> = listOf(
        McpTool(
            name = "disassemble_range",
            description = "用 Capstone 反汇编 so 文件指定【文件偏移】范围的 ARM64 代码（独立于分析会话，给定 soPath 即可）。offset 为文件偏移（非 vaddr）；不可解码字显示为 .word。默认 size=512（防上下文爆炸）；compact=true 时省略 bytes 只回 address/mnemonic/opStr。返回 baseAddress/count/instructions（address/size/mnemonic/opStr/bytes）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string"); put("description", "so 文件绝对路径") }
                    putJsonObject("offset") { put("type", "integer"); put("description", "文件偏移（非 vaddr）") }
                    putJsonObject("size") { put("type", "integer"); put("description", "反汇编字节数 4..65536（默认 512）") }
                    putJsonObject("compact") { put("type", "boolean"); put("description", "true=省略 bytes 只回 address/mnemonic/opStr（省 token）") }
                }
                putJsonArray("required") { add("soPath"); add("offset") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val offset = p.long("offset") ?: throw McpToolException("offset 缺失")
            val size = (p.long("size") ?: 512L).coerceIn(4, 65536)
            val compact = p.str("compact") == "true"
            val bytes = readFileBytes(so, offset, size)
            if (bytes.isEmpty()) return@McpTool buildJsonObject { put("empty", true); put("reason", "偏移越界或文件不可读") }
            val insns = CapstoneBindings.disassembleWithCapstone(bytes, offset)
                ?: return@McpTool buildJsonObject { put("empty", true); put("reason", "Capstone 反汇编不可用") }
            buildJsonObject {
                put("baseAddress", offset)
                put("count", insns.size)
                putJsonArray("instructions") {
                    insns.forEach { it ->
                        addJsonObject {
                            put("address", it.address)
                            put("size", it.size)
                            put("mnemonic", it.mnemonic)
                            put("opStr", it.opStr)
                            if (!compact) {
                                put("bytes", it.bytes.joinToString(" ") { b -> b.toUByte().toString(16).uppercase().padStart(2, '0') })
                            }
                        }
                    }
                }
            }
        },
        McpTool(
            name = "list_elf_sections",
            description = "列出 so 文件的 ELF 节头（独立于会话，给定 soPath 即可）：name/type/address(vaddr)/offset(文件偏移)/size/flags。address 与 offset 的差即该节的 vaddr 偏差",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("soPath") { put("type", "string"); put("description", "so 文件绝对路径") } }
                putJsonArray("required") { add("soPath") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val sections = ElfParserBindings().use { parser ->
                if (!parser.open(so)) throw McpToolException("无法打开 $so")
                parser.getSections()
            }
            buildJsonArray {
                sections.forEach { s ->
                    addJsonObject {
                        put("name", s.name)
                        put("type", s.type)
                        put("address", s.address)
                        put("offset", s.offset)
                        put("size", s.size)
                        put("flags", s.flags)
                    }
                }
            }
        },
        McpTool(
            name = "list_elf_symbols",
            description = "列出 so 文件符号（独立于会话）：默认动态符号表（dynamic 默认 true；传 false 取 .symtab）。返回 name/address(vaddr)/size/type/binding",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string"); put("description", "so 文件绝对路径") }
                    putJsonObject("dynamic") { put("type", "boolean"); put("description", "true=动态符号表（默认）；false=取 .symtab") }
                }
                putJsonArray("required") { add("soPath") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val dynamic = p.str("dynamic") != "false"
            val symbols = ElfParserBindings().use { parser ->
                if (!parser.open(so)) throw McpToolException("无法打开 $so")
                if (dynamic) parser.getDynamicSymbols() else parser.getSymbols()
            }
            buildJsonArray {
                symbols.forEach { s ->
                    addJsonObject {
                        put("name", s.name)
                        put("address", s.address)
                        put("size", s.size)
                        put("type", s.type)
                        put("binding", s.binding)
                    }
                }
            }
        },
        McpTool(
            name = "find_symbol_offset",
            description = "按符号名（精确匹配）解析 ELF 动态符号的地址。返回 found/address(vaddr)/size。用于定位 JNI/导出函数入口后再反汇编",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string"); put("description", "so 文件绝对路径") }
                    putJsonObject("name") { put("type", "string"); put("description", "符号名（精确匹配）") }
                }
                putJsonArray("required") { add("soPath"); add("name") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val name = p.str("name") ?: throw McpToolException("name 缺失")
            val sym = ElfParserBindings().use { parser ->
                if (!parser.open(so)) throw McpToolException("无法打开 $so")
                parser.getDynamicSymbols().firstOrNull { it.name == name }
            }
            buildJsonObject {
                put("found", sym != null)
                put("address", sym?.address ?: 0)
                put("size", sym?.size ?: 0)
            }
        },
        McpTool(
            name = "translate_address",
            description = "地址坐标换算：判断一个数值是文件偏移还是 vaddr，并给出另一个坐标与所在节、偏差 bias。当数值同时是某段 vaddr 与另一段文件偏移（带偏差的 so）时 ambiguous=true 并返回 altVaddr/altFileOffset——此时 read_bytes/write_bytes 会报歧义，需用本工具确认后再把明确的 fileOffset 传入。address 传十进制或 0x 十六进制字符串均可",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string"); put("description", "so 文件绝对路径") }
                    putJsonObject("address") { put("type", "integer"); put("description", "十进制或 0x 十六进制地址值") }
                }
                putJsonArray("required") { add("soPath"); add("address") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val addr = p.long("address") ?: throw McpToolException("address 缺失")
            val res = axisResolver.resolve(so, addr)
            val ctx = addressTranslator.getContext(addr)
            buildJsonObject {
                put("input", addr)
                put("axis", res?.inputAxis?.name ?: "NONE")
                put("interpretedAsFileOffset", res?.inputAxis == AddressAxis.FILE_OFFSET)
                put("ambiguous", res?.ambiguous == true)
                put("fileOffset", res?.fileOffset ?: addr)
                put("vaddr", res?.vaddr ?: addr)
                put("bias", res?.bias ?: 0)
                put("section", res?.section ?: "")
                if (res?.altVaddr != null) put("altVaddr", res.altVaddr)
                if (res?.altFileOffset != null) put("altFileOffset", res.altFileOffset)
                put("symbol", ctx.symbol ?: "")
                put("mappingFound", ctx.found)
            }
        },
        McpTool(
            name = "assemble_instruction",
            description = "用 Keystone 汇编一条 ARM64 指令并返回机器码（预览，不写文件）。assembly 如 'MOV W0, #1' / 'BL #0x4000'；address 为指令所在地址（PC 相对分支需要）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("assembly") { put("type", "string"); put("description", "ARM64 指令，如 MOV W0, #1 / BL #0x4000") }
                    putJsonObject("address") { put("type", "integer"); put("description", "指令所在地址（PC 相对分支需要，可选）") }
                }
                putJsonArray("required") { add("assembly") }
            }
        ) { p ->
            val asm = p.str("assembly") ?: throw McpToolException("assembly 缺失")
            val address = p.long("address") ?: 0L
            val bytes = KeystoneBindings.asm(asm, address)
            if (bytes == null || bytes.isEmpty()) {
                buildJsonObject {
                    put("ok", false)
                    put("message", "Keystone 无法汇编: $asm")
                }
            } else {
                buildJsonObject {
                    put("ok", true)
                    put("size", bytes.size)
                    put("bytes", bytes.joinToString(" ") { b -> b.toUByte().toString(16).uppercase().padStart(2, '0') })
                }
            }
        },
        McpTool(
            name = "read_so_bytes",
            description = "读取 so 文件指定【文件偏移】的原始字节（hex dump，独立于会话）。offset 为文件偏移（非 vaddr）；返回 baseOffset/size/bytes",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string"); put("description", "so 文件绝对路径") }
                    putJsonObject("offset") { put("type", "integer"); put("description", "文件偏移（非 vaddr）") }
                    putJsonObject("size") { put("type", "integer"); put("description", "字节数 1..65536（默认 256）") }
                }
                putJsonArray("required") { add("soPath"); add("offset") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val offset = p.long("offset") ?: throw McpToolException("offset 缺失")
            val size = (p.long("size") ?: 256L).coerceIn(1, 65536)
            val bytes = readFileBytes(so, offset, size)
            buildJsonObject {
                put("empty", bytes.isEmpty())
                put("baseOffset", offset)
                put("size", bytes.size)
                if (bytes.isNotEmpty()) {
                    put("bytes", bytes.joinToString(" ") { b -> b.toUByte().toString(16).uppercase().padStart(2, '0') })
                }
            }
        },
        McpTool(
            name = "search_elf_symbols",
            description = "在 so 动态符号表中按名称子串（不区分大小写）搜索符号。返回 count/truncated/symbols（name/address(vaddr)/size）。用于模糊定位符号",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string"); put("description", "so 文件绝对路径") }
                    putJsonObject("query") { put("type", "string"); put("description", "符号名子串，不区分大小写") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "返回上限 1..1000（默认 100）") }
                }
                putJsonArray("required") { add("soPath"); add("query") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val query = p.str("query") ?: throw McpToolException("query 缺失")
            val limit = (p.int("limit") ?: 100).coerceIn(1, 1000)
            val symbols = ElfParserBindings().use { parser ->
                if (!parser.open(so)) throw McpToolException("无法打开 $so")
                parser.getDynamicSymbols().filter { it.name.contains(query, ignoreCase = true) }
            }.take(limit)
            buildJsonObject {
                put("count", symbols.size)
                put("truncated", symbols.size == limit)
                putJsonArray("symbols") {
                    symbols.forEach { s ->
                        addJsonObject {
                            put("name", s.name)
                            put("address", s.address)
                            put("size", s.size)
                        }
                    }
                }
            }
        },
    )

    // ========== 反混淆工具（结构定位：不依赖符号名） ==========

    private fun buildDeobfTools(): List<McpTool> = listOf(
        McpTool(
            name = "calibrate_pool_sig",
            description = "校准 Dart pool 常量加载签名：反汇编 so 指定 vaddr（推荐用未混淆包的非混淆方法，如 User.isVIP），返回实际命中的 ldr/ldur 池基址寄存器与立即数形态。用于确认 scan_pool_refs 的 poolRegs 参数（默认 x27）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string"); put("description", "so 文件绝对路径（必须具类）") }
                    putJsonObject("vaddr") { put("type", "integer"); put("description", "方法 vaddr（如 get_method 返回的 functionOffset）") }
                    putJsonObject("size") { put("type", "integer"); put("description", "反汇编字节数 32..4096（默认 256）") }
                }
                putJsonArray("required") { add("soPath"); add("vaddr") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val vaddr = p.long("vaddr") ?: throw McpToolException("vaddr 缺失")
            val size = (p.long("size") ?: 256L).coerceIn(32, 4096)
            val fileOffset = fileOffsetOf(so, vaddr)
                ?: return@McpTool buildJsonObject { put("empty", true); put("reason", "无法把 vaddr 换算成文件偏移（so 不可用或地址无效）") }
            val bytes = readFileBytes(so, fileOffset, size)
            if (bytes.isEmpty()) return@McpTool buildJsonObject { put("empty", true); put("reason", "偏移越界或文件不可读") }
            val insns = CapstoneBindings.disassembleWithCapstone(bytes, vaddr)
                ?: return@McpTool buildJsonObject { put("empty", true); put("reason", "Capstone 反汇编不可用") }
            // 提取 pool 池基址形态：ldr/ldur {reg}, [x.., #imm]（imm≈pool 槽偏移）。
            // 只统计「段内多次出现」或「Dart 固定寄存器（x26/x27/x28）」的 base，
            // 排除 `ldr x0, [x0, #8]` 这类单次局部变量访问。
            val dartFixed = listOf("x26", "x27", "x28")
            val baseCount = HashMap<String, Int>()
            val baseSamples = HashMap<String, MutableList<PoolLoadRecord>>()
            for (ins in insns) {
                if (ins.mnemonic != "ldr" && ins.mnemonic != "ldur") continue
                val mem = ins.opStr.substringAfter('[').substringBefore(']')
                val comma = mem.indexOf(',')
                if (comma <= 0) continue
                val base = mem.substring(0, comma).trim()
                if (!base.startsWith("x")) continue
                val immPart = mem.substring(comma + 1).trim()
                if (!immPart.startsWith("#")) continue
                val immStr = immPart.substring(1).trim().removePrefix("0x")
                val imm = immStr.toLongOrNull(16) ?: continue
                if (imm < 0 || imm >= 0x200000) continue
                baseCount[base] = (baseCount[base] ?: 0) + 1
                baseSamples.getOrPut(base) { ArrayList() }.add(PoolLoadRecord(base, imm, ins.address))
            }
            val poolRegs = LinkedHashSet<String>()
            val sampleLdrs = ArrayList<PoolLoadRecord>()
            val fixedHit = baseCount.keys.any { it in dartFixed }
            for ((base, cnt) in baseCount) {
                // 固定池寄存器（x26/x27/x28）出现即有效；非固定需段内 >=2 且无固定时才考虑
                if (base in dartFixed || (cnt >= 2 && !fixedHit)) {
                    poolRegs.add(base)
                    val samples = baseSamples[base] ?: continue
                    if (sampleLdrs.size + samples.size <= 48) sampleLdrs.addAll(samples)
                }
            }
            // 双步池访问：add xN, x27, #off; ldr xN, [xN, #imm] → 溯源到 x27
            for (i in 0 until insns.size - 1) {
                val a = insns[i]
                val b = insns[i + 1]
                if (a.mnemonic != "add" || b.mnemonic != "ldr") continue
                val dst = a.opStr.substringBefore(',').trim()
                val rest = a.opStr.substringAfter(',').trim()
                val src = rest.substringBefore(',').trim()
                if (src !in dartFixed) continue
                if (!b.opStr.startsWith("[$dst")) continue
                if (poolRegs.add("$src(+$dst)")) {
                    sampleLdrs.add(PoolLoadRecord("$src(+$dst)", 0L, a.address))
                }
            }
            buildJsonObject {
                put("found", poolRegs.isNotEmpty())
                put("count", insns.size)
                put("vaddr", vaddr)
                put("fileOffset", fileOffset)
                putJsonArray("poolBaseRegisters") {
                    poolRegs.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("poolLoads") {
                    sampleLdrs.forEach { r ->
                        addJsonObject {
                            put("reg", r.base)
                            put("imm", r.imm)
                            put("address", r.address)
                        }
                    }
                }
                put("hint", if (poolRegs.isNotEmpty()) "scan_pool_refs 的 poolRegs 参数可传 ${poolRegs.joinToString(",")}" else "该函数无 pool 常量加载（可能是普通字段/直接调用）")
            }
        },
        McpTool(
            name = "scan_pool_refs",
            description = "精确 pool 槽交叉引用：全 .text 扫描哪些 Dart 方法引用了指定 pool 槽（Dart 字符串/Stub/字段常量）。基于真实 Capstone 反汇编，同时匹配小槽单指令 `ldr [poolReg,#imm]` 与大槽双指令 `add xN, poolReg, #hi, lsl #12 + ldr xN, [xN, #lo]`（pp=(hi<<12)+lo）。两个应用场景：1) string_xrefs 对中文串 0 命中时，先用 search_strings 拿到字符串的 vmOffset，再 ppOffsets 直传该槽做精确验证（注意目标槽若是 >4095 的大槽会自动走双指令匹配，这是旧版扫不到的盲区）；2) 已知槽偏移（如 find_bool_getters 返回的）反查所有引用方。返回 methodId/className/methodName/siteVaddr（补丁坐标）。analysisId 可省略（缺省 use_analysis）。poolRegs 可覆盖默认 x27,x26（校准实证的池基址）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选）") }
                    putJsonObject("soPath") { put("type", "string"); put("description", "so 文件绝对路径（可选，缺省用分析自带的 libappPath）") }
                    putJsonObject("query") { put("type", "string"); put("description", "字符串子串过滤（可选）：只扫 description 含 query 的 String pp 槽") }
                    putJsonObject("ppOffsets") { put("type", "string"); put("description", "直接指定 pool 槽偏移（逗号分隔 hex/dec，如 0x2328,8992），绕过 String 槽限制——无 String 条目时用此参数；支持 >4095 大槽（自动走 add+ldr 双指令匹配）") }
                    putJsonObject("poolRegs") { put("type", "string"); put("description", "池基址寄存器候选（逗号分隔，如 x27,x26；缺省 x27,x26）") }
                    putJsonObject("minRefCount") { put("type", "integer"); put("description", "只返回引用数 >= 该值的方法（可选）") }
                    putJsonObject("maxResults") { put("type", "integer"); put("description", "返回上限 1..5000（默认 500）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "scan_pool_refs")
            val so = p.str("soPath")
                ?: libappPathOf(id)
                ?: throw McpToolException("soPath 缺失且分析无 libappPath")
            val query = p.str("query")
            val ppOffsetsParam = p.str("ppOffsets")
            val poolRegsParam = p.str("poolRegs")
            val minRef = p.int("minRefCount") ?: 0
            val limit = (p.int("maxResults") ?: 500).coerceIn(1, 5000)
            val poolRegs: List<String> = poolRegsParam
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?: StringXrefScanner.DEFAULT_POOL_REGS
            val directOffsets: Set<Long> = ppOffsetsParam?.split(',')
                ?.mapNotNull { it.trim().removePrefix("0x").toLongOrNull(16) ?: it.trim().toLongOrNull() }
                ?.toSet()
                ?: emptySet()
            // 目标 String pp 槽：优先 ppOffsets，否则按 query 过滤 String 条目
            val allStrings = stringPoolTargets(id)
            val targets: Map<Long, Any?> = when {
                directOffsets.isNotEmpty() -> directOffsets.associateWith { null }
                query.isNullOrBlank() -> allStrings.associateBy { it.vmOffset }
                else -> allStrings.filter { stringSlotContains(it, query) }.associateBy { it.vmOffset }
            }
            if (targets.isEmpty()) return@McpTool buildJsonObject {
                put("count", 0); put("reason", "该分析 pp_entries 无 String 类型条目（或查询无命中），可改查 engine_scan_strings；或用 ppOffsets 直传已知槽偏移")
            }
            val resolvedTargets = ppEntryDao.getPpByVmOffsets(id, targets.keys.toList())
            // 构建 .text 区间
            val text = textSection(so) ?: return@McpTool buildJsonObject {
                put("count", 0); put("reason", "未找到 .text 节")
            }
            val progress = currentProgress()
            val hits = stringXrefScanner.scan(
                soPath = so, text = text, analysisId = id,
                targetPpOffsets = targets.keys.toSet(),
                poolRegs = poolRegs,
                sink = { f, m -> progress.report(f * 0.9f, m) },
            )
            // 聚合 per method
            val byMethod = HashMap<Long, MutableList<StringXrefScanner.Hit>>()
            val byMethodMeta = HashMap<Long, Triple<String, String, Long>>()
            for (h in hits) {
                if (h.methodId == 0L) continue
                byMethod.getOrPut(h.methodId) { ArrayList() }.add(h)
                if (!byMethodMeta.containsKey(h.methodId)) byMethodMeta[h.methodId] = Triple(h.className, h.methodName, h.methodVaddr)
            }
            val filtered = byMethod.filter { (_, list) -> list.size >= minRef }
            val sorted = filtered.entries.sortedByDescending { it.value.size }
                .filter { it.value.size >= minRef }
            val top = if (sorted.size > limit) sorted.subList(0, limit) else sorted
            buildJsonObject {
                put("count", hits.size)
                put("methodCount", filtered.size)
                putJsonArray("poolRegs") {
                    poolRegs.forEach { add(JsonPrimitive(it)) }
                }
                put("truncated", sorted.size > limit)
                putJsonArray("methods") {
                    top.forEach { (methodId, list) ->
                        addJsonObject {
                            val meta = byMethodMeta[methodId] ?: Triple("", "", 0L)
                            put("methodId", methodId)
                            put("className", meta.first)
                            put("methodName", DartNameDisplay.displayMethodName(meta.second, meta.third))
                            put("refCount", list.size)
                            putJsonArray("sites") {
                                list.forEach { hit ->
                                    addJsonObject {
                                        put("siteVaddr", hit.siteVaddr)
                                        put("ppOffset", hit.ppOffset)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        McpTool(
            name = "string_xrefs",
            description = "字符串交叉引用（反混淆核心入口）：定位哪些 Dart 方法引用了含指定子串的 Dart 字符串（pool 字符串 xref）。基于真实 Capstone 反汇编全 .text 扫描（非 Blutter 文本），同时匹配小槽单指令 `ldr [x27,#imm]` 与大槽双指令 `add xN, x27/x26, #hi, lsl #12 + ldr xN, [xN, #lo]`（pp=(hi<<12)+lo，覆盖 >4095 的槽）。用法：给 query（如 'VIP'/'登录'/'剩余时长'，中英文均可，不区分大小写），返回引用方法/class + siteVaddr（即补丁坐标，functionOffset 同系可直接转文件偏移）。混淆包建议用较独特的中文字符串片段缩小噪音。analysisId 可省略（缺省 use_analysis）。query 无匹配或对象池无 String 条目时返回 count=0，可改 engine_scan_strings 或 scan_pool_refs 用 ppOffsets 直传",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选）") }
                    putJsonObject("query") { put("type", "string"); put("description", "字符串子串，不区分大小写，如 VIP / 登录 / is_premium") }
                    putJsonObject("maxResults") { put("type", "integer"); put("description", "返回上限 1..5000（默认 300）") }
                }
                putJsonArray("required") { add("query") }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "string_xrefs")
            val q = p.str("query") ?: throw McpToolException("query 缺失")
            val limit = (p.int("maxResults") ?: 300).coerceIn(1, 5000)
            val so = libappPathOf(id) ?: throw McpToolException("分析无 libappPath")
            val targets = stringPoolTargets(id)
                .filter { stringSlotContains(it, q) }
            if (targets.isEmpty()) return@McpTool buildJsonObject {
                put("count", 0); put("reason", "该分析 pp_entries 无匹配 String（或对象池未含 String 类型条目，可改查 engine_scan_strings）")
            }
            val text = textSection(so) ?: return@McpTool buildJsonObject {
                put("count", 0); put("reason", "未找到 .text 节")
            }
            val progress = currentProgress()
            val hits = stringXrefScanner.scan(
                soPath = so, text = text, analysisId = id,
                targetPpOffsets = targets.map { it.vmOffset }.toSet(),
                sink = { f, m -> progress.report(f * 0.9f, m) },
            )
            val descByOffset = targets.associateBy { it.vmOffset }
            val top = if (hits.size > limit) hits.subList(0, limit) else hits
            buildJsonObject {
                put("count", hits.size)
                put("query", q)
                put("stringCount", targets.size)
                put("truncated", hits.size > limit)
                putJsonArray("targetSample") {
                    targets.take(3).forEach { t ->
                        addJsonObject {
                            put("vmOffset", t.vmOffset)
                            put("desc", t.description ?: "")
                        }
                    }
                }
                put("note", "targetSample 仅为确认目标槽的取样；若 query 命中了字符串但 xrefs 为空：该串可能只被间接加载（无直接 ldr 引用），或槽为 >4095 大槽但加载形态特殊（理论上 add+ldr 双指令已覆盖）。可用 scan_pool_refs 的 ppOffsets 直传 targetSample 里的 vmOffset 做单槽精确验证")
                putJsonArray("xrefs") {
                    top.forEach { h ->
                        addJsonObject {
                            put("siteVaddr", h.siteVaddr)
                            put("ppOffset", h.ppOffset)
                            put("string", descByOffset[h.ppOffset]?.description ?: "")
                            put("methodId", h.methodId)
                            put("className", h.className)
                            put("methodName", DartNameDisplay.displayMethodName(h.methodName, h.methodVaddr))
                        }
                    }
                }
            }
        },
        McpTool(
            name = "infer_class_fields",
            description = "类级字段聚合（反混淆）：1) 对某类全部方法用 .text 结构扫描（真实 Capstone，支持小槽单指令 + 大槽 add+ldr 双指令），收集这些方法引用的 Dart 字符串/Stub pool 槽，恢复该类的字段面（如 is_premium/premiumUser/premiumExpiresIn）；2) 聚合 Blutter pp_entries 的 type=Field 结构化字段槽（含字段名+对象内偏移+owner 类+修饰符，如 Ofa._bvf → late @0x120），比字符串推断更直接。对混淆类（方法名不可读）尤其有效：不依赖符号名。用法：className 传乱码类名（如从 string_xrefs 命中里拿到的引用类），query 过滤（如 'premium'），返回字符串字段槽 fields + 结构化字段槽 fieldSlots。analysisId 可省略（缺省 use_analysis 当前分析）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选）") }
                    putJsonObject("classId") { put("type", "integer"); put("description", "类 ID）") }
                    putJsonObject("className") { put("type", "string"); put("description", "类名（与 classId 二选一）") }
                    putJsonObject("query") { put("type", "string"); put("description", "只返回字符串含该子串的字段（可选，如 premium）") }
                    putJsonObject("minHits") { put("type", "integer"); put("description", "只返回被引用 >= 该次数的字符串（默认 1）") }
                    putJsonObject("maxResults") { put("type", "integer"); put("description", "返回上限 1..2000（默认 300）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "infer_class_fields")
            val classId = p.long("classId")
            val className = p.str("className")
            val q = p.str("query")
            val minHits = (p.int("minHits") ?: 1).coerceAtLeast(0)
            val limit = (p.int("maxResults") ?: 300).coerceIn(1, 2000)
            // 解析类
            val cls = dartClassDao.getByAnalysisIdList(id).firstOrNull {
                (classId != null && it.id == classId) ||
                    (className != null && it.className.equals(className, ignoreCase = true))
            } ?: return@McpTool buildJsonObject { put("found", false); put("reason", "类不存在（检查 classId/className 或 use_analysis）") }
            val so = libappPathOf(id) ?: throw McpToolException("分析无 libappPath")
            val text = textSection(so) ?: return@McpTool buildJsonObject {
                put("found", false); put("reason", "未找到 .text 节")
            }
            // 该类方法索引
            val funcs = functionIndex.build(id)
            val methods = funcs.methodsOf(cls.id)
            if (methods.isEmpty()) return@McpTool buildJsonObject {
                put("found", true); put("className", cls.className); put("methodCount", 0); put("fieldCount", 0)
            }
            // 扫描全部 String 槽（本类方法）——限定扫描他处太多，直接全扫再过滤 methodId
            val allStrings = stringPoolTargets(id)
            if (allStrings.isEmpty()) return@McpTool buildJsonObject {
                put("found", true); put("className", cls.className); put("reason", "对象池无 String 类型条目，无法字段恢复")
            }
            val progress = currentProgress()
            val methodIds = methods.map { it.id }.toSet()
            val hits = stringXrefScanner.scan(
                soPath = so, text = text, analysisId = id,
                targetPpOffsets = allStrings.map { it.vmOffset }.toSet(),
                sink = { f, m -> progress.report(f * 0.85f, m) },
            ).filter { it.methodId in methodIds }
            val descByOffset = allStrings.associateBy { it.vmOffset }
            // 字段聚合：pp 槽 → 方法集合
            val fields = HashMap<Long, MutableList<StringXrefScanner.Hit>>()
            for (h in hits) fields.getOrPut(h.ppOffset) { ArrayList() }.add(h)
            val fieldList = fields.entries
                .filter { (pp, list) -> list.size >= minHits && (q.isNullOrBlank() || (descByOffset[pp]?.description ?: "").contains(q, ignoreCase = true)) }
                .map { (pp, list) -> pp to list }
                .sortedByDescending { (_, list) -> list.size }
            val top = if (fieldList.size > limit) fieldList.subList(0, limit) else fieldList
            // ========== Field 槽聚合（Blutter pp_entries 的 type=Field 结构化字段，含字段名+偏移+owner 类） ==========
            val fieldSlots = DartFieldSlots.parseAll(ppEntryDao.getByAnalysisIdList(id))
                .filter { DartFieldSlots.ownerMatches(it, cls.className) }
                .filter { q.isNullOrBlank() || it.displayName.contains(q, ignoreCase = true) || (it.description ?: "").contains(q, ignoreCase = true) }
                .sortedBy { it.offset ?: Long.MAX_VALUE }
            val fieldSlotList = if (fieldSlots.size > limit) fieldSlots.subList(0, limit) else fieldSlots
            buildJsonObject {
                put("found", true)
                put("classId", cls.id)
                put("className", cls.className)
                put("methodCount", methods.size)
                put("fieldCount", top.size)
                putJsonArray("fields") {
                    top.forEach { (pp, list) ->
                        addJsonObject {
                            put("ppOffset", pp)
                            put("description", descByOffset[pp]?.description ?: "")
                            put("hitCount", list.size)
                            put("methods", list.map { it.methodId }.distinct().joinToString(",", transform = { it.toString() }))
                            put("methodNames", list.map { DartNameDisplay.displayFullName(it.className, it.methodName, it.methodVaddr) }.distinct().take(5).joinToString(";"))
                        }
                    }
                }
                put("fieldSlotCount", fieldSlotList.size)
                putJsonArray("fieldSlots") {
                    fieldSlotList.forEach { fs ->
                        addJsonObject {
                            put("vmOffset", fs.vmOffset)
                            put("name", fs.displayName)
                            put("fieldName", fs.fieldName)
                            put("owner", fs.ownerPlain)
                            put("modifier", fs.modifierText)
                            put("isStatic", fs.isStatic)
                            put("isFinal", fs.isFinal)
                            put("isLate", fs.isLate)
                            if (fs.offset != null) put("offset", fs.offset)
                            put("description", fs.description ?: "")
                        }
                    }
                }
            }
        },
        McpTool(
            name = "closure_map",
            description = "匿名闭包槽映射（反混淆）：解析 Blutter pp_entries 的 AnonymousClosure 槽，建立「闭包 vaddr → 归属类/方法名/父方法」表。混淆包中方法名是 sub_<vaddr>，用本工具能把 vaddr 映射回业务语义（如 0x90f900 → of [Ofa]，即 Ofa 类里的闭包）。支持三种查询：1) vaddr 查单个（如 0x12be2c0）；2) owner 查某类的全部闭包（如 Ofa/_Kya）；3) query 子串过滤。analysisId 可省略（缺省 use_analysis 当前分析）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选）") }
                    putJsonObject("vaddr") { put("type", "integer"); put("description", "闭包 vaddr（hex 或十进制），查单个（可选）") }
                    putJsonObject("owner") { put("type", "string"); put("description", "归属类名子串，查该类闭包（可选）") }
                    putJsonObject("query") { put("type", "string"); put("description", "value 内容子串过滤（可选）") }
                    putJsonObject("maxResults") { put("type", "integer"); put("description", "返回上限 1..500（默认 100）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "closure_map")
            val vaddr = p.long("vaddr")
            val owner = p.str("owner")
            val q = p.str("query")
            val limit = (p.int("maxResults") ?: 100).coerceIn(1, 500)
            val slots = ClosureSlotResolver.parseAll(ppEntryDao.getByAnalysisIdList(id))
            val matched = when {
                vaddr != null -> slots.filter { it.closureVaddr == vaddr }
                owner != null -> slots.filter {
                    it.owner.contains(owner, ignoreCase = true) ||
                        (it.library?.contains(owner, ignoreCase = true) == true)
                }
                else -> slots
            }.filter { q.isNullOrBlank() || it.rawValue.contains(q, ignoreCase = true) }
            val top = if (matched.size > limit) matched.subList(0, limit) else matched
            buildJsonObject {
                put("found", true)
                put("closureSlotCount", slots.size)
                put("matchCount", matched.size)
                put("truncated", matched.size > top.size)
                putJsonArray("closures") {
                    top.forEach { cs ->
                        addJsonObject {
                            put("vmOffset", cs.vmOffset)
                            put("vaddr", cs.closureVaddr)
                            put("owner", cs.owner)
                            if (cs.library != null) put("library", cs.library)
                            if (cs.parentVaddr != null) put("parentVaddr", cs.parentVaddr)
                            put("isStatic", cs.isStatic)
                            put("displayName", cs.displayName)
                            put("rawValue", cs.rawValue.take(200))
                        }
                    }
                }
            }
        },
        McpTool(
            name = "resolve_entry",
            description = "Dart 函数入口探测器（通用，不依赖固定偏移）：Blutter 对匿名闭包恢复的 functionOffset 往往是对象池槽 vaddr（stub/trampoline 区），真实代码入口与其距离因 app/版本而异。本工具用「验证 + 序言扫描」把任意疑似函数地址解析为真实入口：直接验证（stp x29,x30,[x15,#-0x10]! 等标准序言）→ 向高地址扫描第一个序言 → leaf 兜底。输入 vaddr（如 get_method 的 functionOffset），返回 entryVaddr/offset/confidence/原因/入口头指令。analysisId 可省略（缺省 use_analysis）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选）") }
                    putJsonObject("vaddr") { put("type", "integer"); put("description", "疑似函数地址（hex/十进制，必填）") }
                    putJsonObject("scanBytes") { put("type", "integer"); put("description", "向高地址扫描上限 256..16384（默认 4096）") }
                }
                putJsonArray("required") { add("vaddr") }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "resolve_entry")
            val vaddr = p.long("vaddr") ?: throw McpToolException("vaddr 缺失或非法")
            val scanBytes = (p.int("scanBytes") ?: 4096).coerceIn(256, 16384)
            val so = libappPathOf(id) ?: throw McpToolException("分析无 libappPath")
            val text = textSection(so)
            val entry = withContext(kotlinx.coroutines.Dispatchers.IO) { DartEntryResolver.resolve(so, vaddr, text, scanBytes) }
            if (entry == null) {
                buildJsonObject {
                    put("found", false)
                    put("vaddr", vaddr)
                    put("reason", "无法解析入口（反汇编失败或 4KB 内无标准序言）")
                }
            } else {
                buildJsonObject {
                    put("found", true)
                    put("sourceVaddr", vaddr)
                    put("entryVaddr", entry.entryVaddr)
                    put("offset", entry.offset)
                    put("confidence", entry.confidence)
                    put("reason", entry.reason)
                    putJsonArray("firstInsns") {
                        entry.firstInsns.forEach { ins ->
                            addJsonObject {
                                put("address", ins.address)
                                put("mnemonic", ins.mnemonic)
                                put("opStr", ins.opStr)
                            }
                        }
                    }
                }
            }
        },
        McpTool(
            name = "find_bool_getters",
            description = "候选布尔 getter 定位（反混淆）：扫描某类（或全 .text 的 vaddr 区间）中引用 pool 槽的形式，识别「短体 + 返回 bool/枚举槽 + 单一字段加载」形状的方法，产出可直接打补丁的候选地址表。analysisId 可省略（缺省使用）。classId/className 或 vaddrRange（起/止可选）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选）") }
                    putJsonObject("classId") { put("type", "integer"); put("description", "限定类（可选）") }
                    putJsonObject("className") { put("type", "string"); put("description", "类名（与 classId 二选一）") }
                    putJsonObject("query") { put("type", "string"); put("description", "只看引用字符串含该子串的方法（如 premium/vip/bool 字样）") }
                    putJsonObject("maxResults") { put("type", "integer"); put("description", "返回上限 1..2000（默认 300）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "find_bool_getters")
            val clsId = p.long("classId")
            val clsName = p.str("className")
            val q = p.str("query")
            val limit = (p.int("maxResults") ?: 300).coerceIn(1, 2000)
            val so = libappPathOf(id) ?: throw McpToolException("分析无 libappPath")
            val text = textSection(so) ?: return@McpTool buildJsonObject {
                put("found", false); put("reason", "未找到 .text 节")
            }
            val funcs = functionIndex.build(id)
            val resolvedCls = run {
                val cid = clsId
                val cn = clsName
                funcs.all.firstOrNull { m ->
                    (cid != null && m.classId == cid) || (cn != null && m._className.equals(cn, ignoreCase = true))
                }?.let { it.classId }
            }
            val methods = if (resolvedCls != null) funcs.methodsOf(resolvedCls) else funcs.all
            val allStrings = stringPoolTargets(id)
            if (allStrings.isEmpty()) return@McpTool buildJsonObject {
                put("found", false); put("reason", "对象池无 String 类型条目，无法扫描")
            }
            val descByOffset = allStrings.associateBy { it.vmOffset }
            val progress = currentProgress()
            val hits = stringXrefScanner.scan(
                soPath = so, text = text, analysisId = id,
                targetPpOffsets = allStrings.map { it.vmOffset }.toSet(),
                sink = { f, m -> progress.report(f * 0.8f, m) },
            )
            val methodIds = methods.map { it.id }.toSet()
            val byMethod = HashMap<Long, MutableList<StringXrefScanner.Hit>>()
            for (h in hits) {
                if (h.methodId in methodIds) byMethod.getOrPut(h.methodId) { ArrayList() }.add(h)
            }
            val metaById = funcs.all.associateBy { it.id }
            data class Cand(
                val methodId: Long,
                val className: String,
                val methodName: String,
                val functionVaddr: Long,
                val functionSize: Long,
                val refCount: Int,
                val firstString: String,
            )
            val cands = ArrayList<Cand>()
            for ((methodId, list) in byMethod) {
                if (!q.isNullOrBlank() && list.none { (descByOffset[it.ppOffset]?.description ?: "").contains(q, ignoreCase = true) }) continue
                val m = metaById[methodId]
                val strings = list.mapNotNull { descByOffset[it.ppOffset]?.description }.distinct()
                val firstStr = strings.firstOrNull() ?: ""
                val size = m?.functionSize ?: 0L
                cands.add(Cand(methodId, m?._className ?: "", m?.methodName ?: "", m?.functionOffset ?: 0L, size, list.size, firstStr))
            }
            cands.sortWith(
                compareByDescending<Cand> { it.functionSize in 1..512 }
                    .thenByDescending { it.refCount }
            )
            val top = if (cands.size > limit) cands.subList(0, limit) else cands
            buildJsonObject {
                put("found", true)
                put("className", resolvedCls?.toString() ?: "*")
                put("methodCount", byMethod.size)
                put("candidateCount", top.size)
                putJsonArray("candidates") {
                    top.forEach { c ->
                        addJsonObject {
                            put("methodId", c.methodId)
                            put("className", c.className)
                            put("methodName", DartNameDisplay.displayMethodName(c.methodName, c.functionVaddr))
                            put("functionOffset", c.functionVaddr)
                            put("functionSize", c.functionSize)
                            put("refCount", c.refCount)
                            put("firstString", c.firstString)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "getter_return_shape",
            description = "布尔 getter 返回形态分析（反混淆补丁位定位）：反汇编指定方法体，识别每条返回路径（ret/br lr）以及该路径上最后一次写入 w0/x0 的指令（mov w0,#x / ldrb/ldur w0,.. / cset/csinc 等）。输出可直接交给 patch_instruction 的补丁位（siteVaddr→fileOffset）与建议汇编（如 mov w0, #1）。用于把 find_bool_getters 的候选落成真补丁。analysisId 可省略（缺省 use_analysis）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选）") }
                    putJsonObject("methodId") { put("type", "integer"); put("description", "方法 ID（与 methodName 二选一）") }
                    putJsonObject("methodName") { put("type", "string"); put("description", "方法名（与 methodId 二选一，精确匹配）") }
                    putJsonObject("forceSize") { put("type", "integer"); put("description", "方法体扫描字节上限 64..4096（默认用 functionSize，未知时 512）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "getter_return_shape")
            val so = libappPathOf(id) ?: throw McpToolException("分析无 libappPath")
            val funcs = functionIndex.build(id)
            val methodId = p.long("methodId")
            val methodName = p.str("methodName")
            val m = when {
                methodId != null -> funcs.all.firstOrNull { it.id == methodId }
                methodName != null -> {
                    val subVaddr = DartNameDisplay.parseSubName(methodName)
                    if (subVaddr != null) funcs.all.firstOrNull { it.functionOffset == subVaddr }
                    else funcs.all.firstOrNull { it.methodName == methodName }
                }
                else -> null
            } ?: return@McpTool buildJsonObject {
                put("found", false); put("reason", "方法不存在（methodId 或 methodName 精确匹配，sub_<vaddr> 亦可）")
            }
            val vaddr = m.functionOffset ?: 0L
            val declaredSize = m.functionSize
            val forceSize = p.int("forceSize")
            val text = textSection(so)
                ?: return@McpTool buildJsonObject { put("found", false); put("reason", "无法定位 .text 节") }

            // functionSize=0 时先用 DartEntryResolver 探测真实入口，避免把相邻方法
            // 误扫进来（匿名闭包 methods.address 可能是槽地址，真实 body 大小未知）。
            val entry = if ((declaredSize ?: 0) > 0) {
                vaddr
            } else {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    DartEntryResolver.resolve(so, vaddr, text, 4096)?.entryVaddr ?: vaddr
                }
            }
            val bodySize = when {
                forceSize != null -> forceSize.coerceIn(64, 65536)
                declaredSize != null && declaredSize > 0 -> declaredSize.coerceIn(16, 65536).toInt()
                else -> 65536
            }
            if (vaddr <= 0) return@McpTool buildJsonObject { put("found", false); put("reason", "方法无 functionOffset") }
            val fileOffset = fileOffsetOf(so, entry)
                ?: return@McpTool buildJsonObject { put("found", false); put("reason", "无法把方法 vaddr 换算成文件偏移") }
            val endVaddr = entry + bodySize
            val cfg = MethodCfg.build(so, text, entry, endVaddr, maxBytes = bodySize.toLong())
                ?: return@McpTool buildJsonObject { put("found", false); put("reason", "方法体反汇编失败") }
            val returns = cfg.returns
            if (returns.isEmpty()) {
                // 无 ret 也无尾调用返回：可能是纯数据/switch 表，退化报告块形状
                buildJsonObject {
                    put("found", true)
                    put("note", "未识别到 ret/尾调用返回路径（可能是纯数据区或极端混淆），返回基本块划分")
                    put("className", m._className); put("methodName", DartNameDisplay.displayMethodName(m.methodName, m.functionOffset))
                    put("vaddr", vaddr); put("functionSize", declaredSize ?: 0L)
                    put("scanSize", bodySize); put("fileOffset", fileOffset)
                    put("blockCount", cfg.blocks.size)
                    putJsonArray("blocks") {
                        cfg.blocks.take(48).forEach { b ->
                            addJsonObject {
                                put("startVaddr", b.startVaddr); put("endVaddr", b.endVaddr)
                                put("terminator", b.terminator)
                                put("isTailCall", b.isTailCall)
                            }
                        }
                    }
                }
            } else {
                buildJsonObject {
                    put("found", true)
                    put("className", m._className); put("methodName", DartNameDisplay.displayMethodName(m.methodName, m.functionOffset))
                    put("vaddr", vaddr); put("entryVaddr", entry); put("functionSize", declaredSize ?: 0L)
                    put("scanSize", bodySize); put("fileOffset", fileOffset)
                    put("returnPathCount", returns.size)
                    putJsonArray("returns") {
                        returns.forEach { r ->
                            addJsonObject {
                                put("retVaddr", r.vaddr)
                                put("retFileOffset", fileOffsetOf(so, r.vaddr) ?: -1L)
                                put("isTailCall", r.isTailCall)
                                put("lastWriter", r.lastWriteW0 ?: "")
                                put("writerVaddr", r.writerVaddr)
                                put("writerFileOffset", fileOffsetOf(so, r.writerVaddr) ?: -1L)
                                put("writerHex", r.writerHex)
                            }
                        }
                    }
                    put("hint", "要强制返回 true：把每个 returns[].writerVaddr 换成 mov w0, #1（patch_instruction 接受 siteFileOffset）；false 则 mov w0, #0")
                }
            }
        },
        McpTool(
            name = "method_cfg",
            description = "方法级控制流图：反汇编单个 Dart 方法 body，按分支/返回指令划分基本块，输出块表（startVaddr/endVaddr/succs/isReturn/isTailCall）与返回路径（ret 地址 + 该路径最后一次写 w0/x0 的指令）。适合理解混淆方法控制流、定位所有返回点。analysisId 可省略（缺省 use_analysis）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选）") }
                    putJsonObject("methodId") { put("type", "integer"); put("description", "方法 ID（与 methodName 二选一）") }
                    putJsonObject("methodName") { put("type", "string"); put("description", "方法名（与 methodId 二选一，精确匹配，sub_<vaddr> 亦可）") }
                    putJsonObject("maxBytes") { put("type", "integer"); put("description", "方法体扫描上限字节 64..65536（默认 65536）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "method_cfg")
            val so = libappPathOf(id) ?: throw McpToolException("分析无 libappPath")
            val funcs = functionIndex.build(id)
            val methodId = p.long("methodId")
            val methodName = p.str("methodName")
            val m = when {
                methodId != null -> funcs.all.firstOrNull { it.id == methodId }
                methodName != null -> {
                    val subVaddr = DartNameDisplay.parseSubName(methodName)
                    if (subVaddr != null) funcs.all.firstOrNull { it.functionOffset == subVaddr }
                    else funcs.all.firstOrNull { it.methodName == methodName }
                }
                else -> null
            } ?: return@McpTool buildJsonObject {
                put("found", false); put("reason", "方法不存在（methodId 或 methodName 精确匹配，sub_<vaddr> 亦可）")
            }
            val vaddr = m.functionOffset ?: 0L
            if (vaddr <= 0) return@McpTool buildJsonObject { put("found", false); put("reason", "方法无 functionOffset") }
            val text = textSection(so)
                ?: return@McpTool buildJsonObject { put("found", false); put("reason", "无法定位 .text 节") }
            val maxBytes = (p.long("maxBytes") ?: 65536L).coerceIn(64, 65536)
            val endVaddr = if ((m.functionSize ?: 0) > 0) vaddr + (m.functionSize ?: 0) else vaddr + 512
            val cfg = MethodCfg.build(so, text, vaddr, endVaddr, maxBytes)
                ?: return@McpTool buildJsonObject { put("found", false); put("reason", "方法体反汇编失败") }
            buildJsonObject {
                put("found", true)
                put("className", m._className)
                put("methodName", DartNameDisplay.displayMethodName(m.methodName, m.functionOffset))
                put("vaddr", vaddr)
                put("endVaddr", cfg.endVaddr)
                put("blockCount", cfg.blocks.size)
                put("returnPathCount", cfg.returns.size)
                put("truncated", cfg.truncated)
                putJsonArray("blocks") {
                    cfg.blocks.forEach { b ->
                        addJsonObject {
                            put("startVaddr", b.startVaddr)
                            put("endVaddr", b.endVaddr)
                            put("instrCount", b.size)
                            putJsonArray("succs") { b.succs.forEach { add(JsonPrimitive(it)) } }
                            put("isReturn", b.isReturn)
                            put("isTailCall", b.isTailCall)
                        }
                    }
                }
                putJsonArray("returns") {
                    cfg.returns.forEach { r ->
                        addJsonObject {
                            put("retVaddr", r.vaddr)
                            put("lastWriter", r.lastWriteW0 ?: "")
                        }
                    }
                }
            }
        },
        McpTool(
            name = "blr_call_sites",
            description = "间接调用点（blr/br）扫描：反汇编方法 body，识别所有 blr/br 间接跳转并标注来源形态——isolate（线程槽闭包调度，Dart 间接调用主形态）、pool（ldr [poolReg,#imm] 池槽加载调用，给出槽偏移与描述）、field（对象字段虚调用）、dynamic（寄存器动态来源）。目标通常静态不可解析，本工具用于判断方法里有多少间接调用及其形态。analysisId 可省略（缺省 use_analysis）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer"); put("description", "分析记录 ID（可选）") }
                    putJsonObject("methodId") { put("type", "integer"); put("description", "方法 ID（与 methodName 二选一）") }
                    putJsonObject("methodName") { put("type", "string"); put("description", "方法名（与 methodId 二选一，精确匹配，sub_<vaddr> 亦可）") }
                }
            }
        ) { p ->
            val id = resolveAnalysisId(p, "blr_call_sites")
            val so = libappPathOf(id) ?: throw McpToolException("分析无 libappPath")
            val funcs = functionIndex.build(id)
            val methodId = p.long("methodId")
            val methodName = p.str("methodName")
            val m = when {
                methodId != null -> funcs.all.firstOrNull { it.id == methodId }
                methodName != null -> {
                    val subVaddr = DartNameDisplay.parseSubName(methodName)
                    if (subVaddr != null) funcs.all.firstOrNull { it.functionOffset == subVaddr }
                    else funcs.all.firstOrNull { it.methodName == methodName }
                }
                else -> null
            } ?: return@McpTool buildJsonObject {
                put("found", false); put("reason", "方法不存在（methodId 或 methodName 精确匹配，sub_<vaddr> 亦可）")
            }
            val vaddr = m.functionOffset ?: 0L
            if (vaddr <= 0) return@McpTool buildJsonObject { put("found", false); put("reason", "方法无 functionOffset") }
            val text = textSection(so)
                ?: return@McpTool buildJsonObject { put("found", false); put("reason", "无法定位 .text 节") }
            val endVaddr = if ((m.functionSize ?: 0) > 0) vaddr + (m.functionSize ?: 0) else vaddr + 512
            val sites = indirectCallScanner.scanSites(id, so, text, vaddr, endVaddr)
            buildJsonObject {
                put("found", true)
                put("className", m._className)
                put("methodName", DartNameDisplay.displayMethodName(m.methodName, m.functionOffset))
                put("vaddr", vaddr)
                put("count", sites.size)
                putJsonArray("sites") {
                    sites.forEach { s ->
                        addJsonObject {
                            put("vaddr", s.vaddr)
                            put("kind", s.kind)
                            put("reg", s.reg)
                            put("source", s.source)
                            if (s.kind == "pool") {
                                put("poolReg", s.poolReg)
                                put("poolImm", s.poolImm)
                                if (s.poolDescription.isNotEmpty()) put("poolDescription", s.poolDescription)
                            }
                        }
                    }
                }
            }
        },
    )

    /** 分析自带的 libappPath。 */
    private suspend fun libappPathOf(analysisId: Long) = cachedAnalysis(analysisId)?.libappPath

    /**
     * 获取分析的「字符串池槽」集合。
     * 优先 type='String' 条目（Blutter strings 表）；若空则回退：从 pp_entries 全量里
     * 挑 description 或 type 含引号字符串（形如 `[pp+0x2328] "Amd"`）的条目——部分 APK 的
     * Blutter 分析未把字符串写入 strings 表，但文本仍在 pp 槽 description/type 中。
     * 注意：实测发现混淆包存在「内容在 type 字段、description 只剩引号」的错位形态，
     * 因此回退同时检查两列。
     */
    private suspend fun stringPoolTargets(analysisId: Long): List<com.ai.fler.data.entity.PpEntry> {
        val typed = ppEntryDao.getStringsByAnalysisIdList(analysisId)
        if (typed.isNotEmpty()) return typed
        return ppEntryDao.getByAnalysisIdList(analysisId).filter {
            it.description?.contains('"') == true || it.type?.contains('"') == true
        }
    }

    // ========== 闭包槽（AnonymousClosure pp_entries）解析缓存 ==========
    private val closureMapCache = HashMap<Long, Map<Long, ClosureSlotResolver.ClosureSlot>>()

    /**
     * 解析该分析的 AnonymousClosure 槽，建立「闭包 vaddr → 槽」映射（每 analysisId 缓存一次）。
     * Blutter pp_entries 里这些槽的 value 形如 `(0x90f900), of [zuq] Ofa`，把匿名方法
     * vaddr 与归属类/方法名精确关联。
     */
    private suspend fun closureSlotsOf(analysisId: Long): Map<Long, ClosureSlotResolver.ClosureSlot> {
        closureMapCache[analysisId]?.let { return it }
        val all = ppEntryDao.getByAnalysisIdList(analysisId)
        val map = ClosureSlotResolver.buildVaddrMap(ClosureSlotResolver.parseAll(all))
        if (map.isNotEmpty()) closureMapCache[analysisId] = map
        return map
    }

    /** 查某方法 vaddr 对应的闭包槽（该方法若是匿名闭包）。 */
    private suspend fun closureSlotFor(analysisId: Long, vaddr: Long?): ClosureSlotResolver.ClosureSlot? {
        if (vaddr == null || vaddr <= 0) return null
        return closureSlotsOf(analysisId)[vaddr]
    }

    /** 字符串槽的「可搜索文本」：description 与 type 拼接，覆盖内容落在任一字段的形态。 */
    private fun stringSlotText(e: com.ai.fler.data.entity.PpEntry): String =
        listOfNotNull(e.description, e.type).joinToString(" ")

    /** 字符串槽的子串匹配：description 或 type 任一字段命中即算命中。 */
    private fun stringSlotContains(e: com.ai.fler.data.entity.PpEntry, query: String): Boolean =
        stringSlotText(e).contains(query, ignoreCase = true)

    /**
     * 分析内的「字符串池槽」搜索（SQL 下推优先；无 String 类型条目时回退内存过滤）。
     * 与 [stringPoolTargets] 的回退语义一致，供 search_strings/list_strings 复用，
     * 修复混淆包（无 type='String' 条目）下 search 恒 0 的问题。
     */
    private suspend fun searchStringSlots(
        analysisId: Long,
        query: String,
        limit: Int,
    ): List<com.ai.fler.data.entity.PpEntry> {
        if (ppEntryDao.countStringsByAnalysisId(analysisId) > 0) {
            return ppEntryDao.searchStrings(analysisId, query, limit)
        }
        return ppEntryDao.getByAnalysisIdList(analysisId)
            .filter { stringSlotContains(it, query) }
            .take(limit)
    }

    /** 用 ELF 解析器取 .text 节区间。 */
    private fun textSection(soPath: String): StringXrefScanner.TextRange? =
        ElfParserBindings().use { parser ->
            if (!parser.open(soPath)) null
            else parser.getSections().firstOrNull { it.name == ".text" }?.let {
                StringXrefScanner.TextRange(it.offset, it.size, it.address)
            }
        }

    private fun buildPatchTools(): List<McpTool> = listOf(
        McpTool(
            name = "patch_instruction",
            description = "用 Keystone 汇编一条指令并【写入】so 文件【文件偏移】（破坏性补丁，默认关闭；写前自动备份+CRC 校验+可撤销）。offset 为文件偏移；写前建议 read_so_bytes 确认原值。需先在设置启用补丁",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string" ); put("description", "so 文件绝对路径") }
                    putJsonObject("offset") { put("type", "integer"); put("description", "文件偏移") }
                    putJsonObject("assembly") { put("type", "string"); put("description", "ARM64 指令，如 NOP / MOV X0, #0") }
                }
                putJsonArray("required") { add("soPath"); add("offset"); add("assembly") }
            }
        ) { p ->
            requirePatchEnabled()
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val offset = p.long("offset") ?: throw McpToolException("offset 缺失")
            val asm = p.str("assembly") ?: throw McpToolException("assembly 缺失")
            val newBytes = KeystoneBindings.asm(asm, offset)
                ?: throw McpToolException("Keystone 无法汇编: $asm")
            val result = patchService.apply(so, offset, newBytes)
            buildJsonObject {
                put("ok", result.ok)
                put("offset", offset)
                put("size", newBytes.size)
                put("bytes", newBytes.joinToString(" ") { b -> b.toUByte().toString(16).uppercase().padStart(2, '0') })
                put("message", result.message)
            }
        },
        McpTool(
            name = "undo_patch",
            description = "撤销该 so 最近一次补丁，恢复原字节（默认关闭；需先启用补丁）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("soPath") { put("type", "string"); put("description", "so 文件绝对路径") } }
                putJsonArray("required") { add("soPath") }
            }
        ) { p ->
            requirePatchEnabled()
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val result = patchService.undo(so)
            buildJsonObject {
                put("ok", result.ok)
                put("message", result.message)
            }
        },
        McpTool(
            name = "list_patches",
            description = "列出指定 so 的全部补丁记录（offset/oldBytes/newBytes/timestamp，默认关闭；需先启用补丁）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("soPath") { put("type", "string"); put("description", "so 文件绝对路径") } }
                putJsonArray("required") { add("soPath") }
            }
        ) { p ->
            requirePatchEnabled()
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val records = patchService.list(so)
            buildJsonObject {
                put("count", records.size)
                putJsonArray("records") {
                    records.forEach { r ->
                        addJsonObject {
                            put("offset", r.address)
                            put("oldBytes", r.oldBytes.joinToString(" ") { b -> b.toUByte().toString(16).uppercase().padStart(2, '0') })
                            put("newBytes", r.newBytes.joinToString(" ") { b -> b.toUByte().toString(16).uppercase().padStart(2, '0') })
                            put("timestamp", r.timestamp)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "patch_bytes",
            description = "写任意原始字节到 so 文件指定【文件偏移】（破坏性，默认关闭；写前自动备份+可撤销）。offset 为文件偏移；hex 为空格分隔十六进制如 '1F 20 03 D5'。写前建议先 read_so_bytes 确认原值，需先启用补丁",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string"); put("description", "so 文件绝对路径") }
                    putJsonObject("offset") { put("type", "integer"); put("description", "文件偏移") }
                    putJsonObject("hex") { put("type", "string"); put("description", "空格分隔十六进制，如 '1F 20 03 D5'") }
                }
                putJsonArray("required") { add("soPath"); add("offset"); add("hex") }
            }
        ) { p ->
            requirePatchEnabled()
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val offset = p.long("offset") ?: throw McpToolException("offset 缺失")
            val hex = p.str("hex") ?: throw McpToolException("hex 缺失")
            val bytes = decodeHex(hex) ?: throw McpToolException("hex 格式非法（如 1F 20 03 D5）")
            if (bytes.isEmpty()) throw McpToolException("hex 为空")
            val result = patchService.apply(so, offset, bytes)
            buildJsonObject {
                put("ok", result.ok)
                put("offset", offset)
                put("size", bytes.size)
                put("message", result.message)
            }
        },
        McpTool(
            name = "export_patched_so",
            description = "把（已补丁的）so 文件复制/导出到指定目录，供用户获取。目标目录缺省用设置里用户选定的工作目录（补丁后 SO 的默认导出位置）；若无则落到 App 缓存 cacheDir/so_export——该目录会经 MCP 服务器暴露为 GET /export/<文件名>，可用 curl http://<手机IP>:<端口>/export/<destName> 直接下载。可传 destDir 绝对路径覆盖（需 App 有写入权限，否则报错）。destName 缺省用源文件名",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string"); put("description", "so 文件绝对路径") }
                    putJsonObject("destDir") { put("type", "string"); put("description", "目标目录绝对路径（可选，覆盖默认导出位置）") }
                    putJsonObject("destName") { put("type", "string"); put("description", "导出文件名（可选，默认用源文件名）") }
                }
                putJsonArray("required") { add("soPath") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val destDir = p.str("destDir").orEmpty()
            val src = java.io.File(so)
            if (!src.exists() || !src.isFile) throw McpToolException("源 so 不存在: $so")
            val destName = p.str("destName")?.takeIf { it.isNotBlank() } ?: src.name

            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                exportPatchedSo(src, destName, destDir)
            }
            buildJsonObject {
                put("ok", result.ok)
                put("fileName", destName)
                put("size", result.size)
                put("destPath", result.path)
                put("message", result.message)
            }
        },
    )

    private data class ExportResult(val ok: Boolean, val path: String, val size: Long = 0L, val message: String = "")

    private data class PoolLoadRecord(val base: String, val imm: Long, val address: Long)

    /** 从当前协程上下文读取请求级进度通道（无则返回空实现）。 */
    private suspend fun currentProgress(): ProgressSink =
        kotlinx.coroutines.currentCoroutineContext()[McpRequestContext]?.progress ?: NoopProgressSink

    /** 把补丁后的 so 复制到目标。优先 destDir（绝对路径），否则用户选定的工作目录，兜底 cacheDir/so_export。 */
    private suspend fun exportPatchedSo(src: java.io.File, destName: String, destDir: String): ExportResult {
        return try {
            val progress = currentProgress()
            // 各分支进度百分比在 10%~95% 之间按复制进度推进，最后置 100%
            progress.report(0.1f, "准备导出 $destName")
            if (destDir.isNotBlank()) {
                val dir = java.io.File(destDir)
                if (!dir.isDirectory && !dir.mkdirs()) {
                    return ExportResult(false, "", 0, "目标目录无法创建: $destDir")
                }
                val out = java.io.File(dir, destName)
                val size = copyFile(src, out) { p -> progress.report(rangeProgress(p, 0.1f, 0.95f), "复制 $destName ${(p * 100).toInt()}%") }
                progress.report(1f, "已复制到 $out")
                return ExportResult(true, out.absolutePath, size, "已复制到 $out")
            }

            val workDoc = workDirectory.asDocumentFile()
            if (workDoc != null && workDoc.canWrite()) {
                val child = workDoc.createFile("application/octet-stream", destName)
                if (child != null) {
                    context.contentResolver.openOutputStream(child.uri)?.use { os ->
                        copyStream(src, os) { p -> progress.report(rangeProgress(p, 0.1f, 0.95f), "复制 $destName ${(p * 100).toInt()}%") }
                        os.flush()
                    } ?: return ExportResult(false, "", 0, "无法打开工作目录输出流")
                    progress.report(1f, "已导出到工作目录")
                    return ExportResult(true, child.uri.toString(), src.length(), "已导出到工作目录")
                }
            }

            val fallbackDir = java.io.File(context.cacheDir, "so_export").apply { mkdirs() }
            val out = java.io.File(fallbackDir, destName)
            val size = copyFile(src, out) { p -> progress.report(rangeProgress(p, 0.1f, 0.95f), "复制 $destName ${(p * 100).toInt()}%") }
            progress.report(1f, "已复制到 App 缓存目录")
            ExportResult(true, out.absolutePath, size, "已复制到 App 缓存目录")
        } catch (e: Exception) {
            ExportResult(false, "", 0, "导出失败: ${e.message}")
        }
    }

    /** 把 [0,1] 相对进度映射到 [min,max] 区间（避免与大阶段百分比重叠）。 */
    private fun rangeProgress(p: Float, min: Float, max: Float): Float = min + (max - min) * p.coerceIn(0f, 1f)

    /** 分块复制文件，以块完成比回调 inProgress(0..1)。 */
    private fun copyFile(src: java.io.File, out: java.io.File, onProgress: (Float) -> Unit): Long {
        out.outputStream().use { o ->
            copyStream(src, o, onProgress)
            o.flush()
        }
        return src.length()
    }

    /** 分块复制流，每 ≥5% 间距回调一次相对进度（0..1）。 */
    private fun copyStream(src: java.io.File, o: java.io.OutputStream, onProgress: (Float) -> Unit) {
        val buf = ByteArray(64 * 1024)
        val total = src.length()
        var copied = 0L
        var lastBucket = -1
        src.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                copied += n
                if (total > 0) {
                    val bucket = (copied * 20 / total).toInt() // 0..19（每 5% 一档）
                    if (bucket != lastBucket) {
                        lastBucket = bucket
                        onProgress(bucket / 20f)
                    }
                }
            }
        }
        if (total <= 0) onProgress(1f)
    }

    private fun requirePatchEnabled() {
        if (!config.patchEnabled.value) {
            throw McpToolException("补丁工具未启用（需在设置页开启并确认客户端调用）")
        }
    }

    // ========== 文件读取 ==========

    private fun readFileBytes(path: String, offset: Long, size: Long): ByteArray {
        return try {
            RandomAccessFile(path, "r").use { raf ->
                val fileLen = raf.length()
                val start = offset.coerceAtLeast(0)
                if (start >= fileLen) return ByteArray(0)
                val len = size.coerceIn(1, fileLen - start).toInt()
                raf.seek(start)
                val buf = ByteArray(len)
                raf.readFully(buf)
                buf
            }
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    /** 解码 hex 字符串（空格分隔）为字节数组；非法格式返回 null。 */
    private fun decodeHex(hex: String): ByteArray? {
        return try {
            val cleaned = hex.trim().replace(Regex("\\s+"), "")
            if (cleaned.isEmpty()) return ByteArray(0)
            if (cleaned.length % 2 != 0 || !cleaned.all { it in "0123456789abcdefABCDEF" }) return null
            cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    // ========== MCP resources 数据源 ==========

    override suspend fun listResources(): List<McpResource> {
        return analysisDao.getRecentList(200).map { a ->
            McpResource(
                uri = "fler://analysis/${a.id}",
                name = "analysis-${a.id}",
                mimeType = "application/json",
            )
        }
    }

    override suspend fun readResource(uri: String): String? {
        val analysisMatch = Regex("^fler://analysis/(\\d+)$").find(uri)
        val methodMatch = Regex("^fler://method/(\\d+)/(\\d+)$").find(uri)
        return when {
            analysisMatch != null -> {
                val id = analysisMatch.groupValues[1].toLongOrNull() ?: return null
                val a = cachedAnalysis(id) ?: return null
                "analysis $id\nprojectId=${a.projectId}\nlibapp=${a.libappPath ?: ""}\n" +
                    "resultCode=${a.resultCode}\nclasses=${a.classesCount} methods=${a.methodsCount} pp=${a.ppEntriesCount}"
            }
            methodMatch != null -> {
                val methodId = methodMatch.groupValues[2].toLongOrNull() ?: return null
                val m = dartMethodDao.getMethodWithClassById(methodId) ?: return null
                "${m._className}.${m.method.methodName}\n${m.method.srcCode ?: ""}"
            }
            else -> null
        }
    }

    companion object {
        private const val MAX_SRC = 100_000
    }
}

/** 工具内部业务错误：会转成 JSON-RPC -32000 段错误。 */
class McpToolException(message: String) : Exception(message)

/** JsonArrayBuilder 增加 String 便捷重载（schema 的 required 列表）。 */
private fun JsonArrayBuilder.add(value: String) {
    add(JsonPrimitive(value))
}
