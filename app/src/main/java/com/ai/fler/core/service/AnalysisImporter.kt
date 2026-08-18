package com.ai.fler.core.service

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.ai.fler.core.log.AppLogger
import com.ai.fler.data.AppDatabase
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.DartClassDao
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.PpEntryDao
import com.ai.fler.data.entity.DartClass
import com.ai.fler.data.entity.DartMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Blutter 分析结果导入器。
 *
 * blutter_analyze() 把分析结果直接写入 SQLite（cache/analysis_{id}.db），
 * 该数据库的表结构由 fler-dart 引擎决定，与 App 的 Room schema 不同。
 * 本类把 Blutter DB 中的 classes/methods/pp_entries/strings 表
 * 防御式地读入 Room（DartClass/DartMethod/PpEntry），并回写统计计数。
 *
 * 优化：使用 ATTACH DATABASE + INSERT INTO ... SELECT 表对表搬运，
 * 避免逐行读 → Kotlin 对象映射 → 分批插的 JVM 内存开销。
 *
 * 关键实现约束（Room 2.7.1 TriggerBasedInvalidationTracker）：
 * - Android 框架的 SQLiteDatabase.executeSql 对 ATTACH 语句硬编码调用
 *   disableWriteAheadLogging()，会触发 SQLiteConnectionPool.reconfigure()
 *   关闭并重建连接池所有连接；
 * - Room 的失效追踪依赖 per-connection 的 TEMP 表 room_table_modification_log
 *   （数据库首次打开时仅对单个连接创建），连接重建后新连接没有该表，
 *   导致后续所有 invalidation 查询报 "no such table"。
 * - 因此 ATTACH 导入必须使用独立的 SQLiteDatabase 连接（直接打开 App DB 文件），
 *   绝不能在 Room 的 openHelper 连接上执行 ATTACH。
 *
 * 防御式说明：
 * - 用 sqlite_master 枚举实际存在的表，缺失的表跳过
 * - 用 PRAGMA table_info 读取实际列名，按列名（非序号）取值
 * - 单表失败不影响其他表
 */
@Singleton
class AnalysisImporter @Inject constructor(
    private val analysisDao: AnalysisDao,
    private val dartClassDao: DartClassDao,
    private val dartMethodDao: DartMethodDao,
    private val ppEntryDao: PpEntryDao,
    private val appLogger: AppLogger,
    private val appDatabase: AppDatabase,
) {
    companion object {
        private const val TAG = "AnalysisImporter"
        private const val UNKNOWN_CLASS = "<unknown>"
        private const val UNKNOWN_METHOD = "<unknown>"

        /** 单批插入上限：避免一次性绑定数万条参数，SQLite 变量数有上限（默认 999）。 */
        private const val BATCH_SIZE = 500
    }

    /** 导入结果统计。 */
    data class ImportResult(
        val classesCount: Int = 0,
        val methodsCount: Int = 0,
        val ppEntriesCount: Int = 0,
        val objectsCount: Int = 0,
        val enumsCount: Int = 0,
        val asmBlocksCount: Int = 0,
    )

    /**
     * 导入指定分析结果到 Room。
     *
     * 使用 ATTACH DATABASE + INSERT INTO ... SELECT 表对表搬运，
     * 10 万行级搬运从分钟级降至 1-3 秒，峰值内存从数百 MB 降至数十 MB。
     *
     * @param analysisId App 侧 Analysis 记录 ID（必须先于本调用创建）
     * @param dbPath blutter_analyze 生成的 SQLite 绝对路径
     * @return 各类导入计数；失败/无文件时返回全 0
     */
    suspend fun import(analysisId: Long, dbPath: String): ImportResult = withContext(Dispatchers.IO) {
        val dbFile = File(dbPath)
        if (!dbFile.exists() || dbFile.length() == 0L) {
            Log.w(TAG, "Blutter DB 不存在或为空: $dbPath")
            return@withContext ImportResult()
        }

        var classes = 0
        var methodsCount = 0
        var pp = 0
        var objects = 0
        var enums = 0
        var asmBlocks = 0

        try {
            // 确保 Room schema 已建好，并拿到 App DB 文件路径
            val roomDbPath = appDatabase.openHelper.writableDatabase.path!!

            // 独立连接：ATTACH 会触发 Android 框架 WAL reconfigure（连接池重建、
            // Room 的 per-connection TEMP 失效表丢失），因此绝不能在 Room 连接池上执行。
            // WAL 模式下多连接读写是安全的；导入期间 UI 只读，无写写冲突。
            val roomDb = SQLiteDatabase.openDatabase(
                roomDbPath, null, SQLiteDatabase.OPEN_READWRITE
            )
            try {
                // 1. ATTACH 挂载 Blutter DB
                roomDb.execSQL("ATTACH DATABASE ? AS blutter", arrayOf(dbPath))
                val blutterTables = listAttachedTables(roomDb, "blutter")

                // ========== classes ==========
                // 需要自增 ID 映射，仍走 DAO（Room 连接）；行数不多（~9K），逐批插入可接受。
                val blutterClassIdToRoomId = LinkedHashMap<Long, Long>()
                if ("classes" in blutterTables) {
                    try {
                        val cols = columnNamesAttached(roomDb, "blutter", "classes")
                        val idxId = cols.indexOf("id")
                        val idxName = cols.indexOf("name")
                        val idxSuper = cols.indexOf("super_cls")
                        if (idxId >= 0 && idxName >= 0) {
                            val rows = mutableListOf<Triple<Long, String, String?>>()
                            roomDb.rawQuery("SELECT * FROM blutter.classes", null).use { c ->
                                while (c.moveToNext()) {
                                    val name = c.getString(idxName)?.takeIf { it.isNotBlank() } ?: continue
                                    rows.add(
                                        Triple(
                                            c.getLong(idxId),
                                            name,
                                            if (idxSuper >= 0) c.getString(idxSuper) else null,
                                        )
                                    )
                                }
                            }
                            if (rows.isNotEmpty()) {
                                val entities = rows.map { (_, name, superCls) ->
                                    DartClass(
                                        analysisId = analysisId,
                                        className = name,
                                        libraryPath = "",
                                        superClass = superCls,
                                    )
                                }
                                val ids = mutableListOf<Long>()
                                for (batch in entities.chunked(BATCH_SIZE)) {
                                    ids += dartClassDao.insertAll(batch)
                                }
                                rows.forEachIndexed { i, (blutterId, _, _) ->
                                    if (i < ids.size) blutterClassIdToRoomId[blutterId] = ids[i]
                                }
                            }
                            classes = rows.size
                            Log.i(TAG, "导入 classes: $classes 条")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "导入 classes 失败", e)
                    }
                }

                // 2. 兜底 class（方法可能引用不存在的 class；也用于孤立 pp 条目）
                val unknownClassId = dartClassDao.insert(
                    DartClass(analysisId = analysisId, className = UNKNOWN_CLASS, libraryPath = "")
                )

                // 3. 兜底 method（Blutter 的 pp_entries / strings 没有 method 关联）
                val unknownMethodId = dartMethodDao.insert(
                    DartMethod(
                        analysisId = analysisId,
                        classId = unknownClassId,
                        methodName = UNKNOWN_METHOD,
                        selector = UNKNOWN_METHOD,
                    )
                )

                // 4. 创建临时映射表：blutter.classes.id → Room DB dart_classes.id
                //    TEMP 表仅本连接可见，连接关闭自动消失
                roomDb.execSQL("CREATE TEMP TABLE IF NOT EXISTS _cm (bid INTEGER, rid INTEGER)")
                blutterClassIdToRoomId.forEach { (bid, rid) ->
                    roomDb.execSQL("INSERT INTO _cm (bid, rid) VALUES (?, ?)", arrayOf(bid, rid))
                }

                // ========== methods / pp_entries / strings：单事务直搬 ==========
                roomDb.beginTransaction()
                try {
                    if ("methods" in blutterTables) {
                        try {
                            val cols = columnNamesAttached(roomDb, "blutter", "methods")
                            val hasClassId = "class_id" in cols
                            val hasName = "name" in cols
                            val hasAddress = "address" in cols
                            val hasSize = "size" in cols
                            val hasSrcCode = "src_code" in cols
                            if (hasClassId && hasName) {
                                val sql = """
                                    INSERT INTO dart_methods(
                                        analysis_id, class_id, method_name, selector,
                                        function_offset, function_size, src_code,
                                        is_static, is_getter, is_setter, is_constructor, pp_count
                                    )
                                    SELECT
                                        ?,
                                        COALESCE(cm.rid, ?),
                                        m.name,
                                        m.name,
                                        NULLIF(m.address, 0),
                                        NULLIF(m.size, 0),
                                        m.src_code,
                                        0, 0, 0, 0, 0
                                    FROM blutter.methods m
                                    LEFT JOIN _cm cm ON m.class_id = cm.bid
                                """.trimIndent()
                                roomDb.execSQL(sql, arrayOf(analysisId, unknownClassId))
                                methodsCount = getLastChangeCount(roomDb)
                                Log.i(TAG, "导入 methods: $methodsCount 条 (SQL 直搬)")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "导入 methods 失败", e)
                        }
                    }

                    if ("pp_entries" in blutterTables) {
                        try {
                            val cols = columnNamesAttached(roomDb, "blutter", "pp_entries")
                            val hasPpOffset = "pp_offset" in cols
                            val hasType = "type" in cols
                            val hasSoAddr = "so_addr" in cols
                            val hasValue = "value" in cols
                            if (hasPpOffset && hasType) {
                                val sql = """
                                    INSERT INTO pp_entries(
                                        method_id, analysis_id, vm_offset, file_offset,
                                        description, type,
                                        function_size, is_leaf, caller_count
                                    )
                                    SELECT
                                        ?,
                                        ?,
                                        p.pp_offset,
                                        COALESCE(p.so_addr, 0),
                                        COALESCE(p.value, p.type),
                                        p.type,
                                        0, 0, 0
                                    FROM blutter.pp_entries p
                                """.trimIndent()
                                roomDb.execSQL(sql, arrayOf(unknownMethodId, analysisId))
                                val ppCount = getLastChangeCount(roomDb)
                                pp += ppCount
                                Log.i(TAG, "导入 pp_entries: $ppCount 条 (SQL 直搬)")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "导入 pp_entries 失败", e)
                        }
                    }

                    if ("strings" in blutterTables) {
                        try {
                            val cols = columnNamesAttached(roomDb, "blutter", "strings")
                            val hasPpOffset = "pp_offset" in cols
                            val hasValue = "value" in cols
                            if (hasPpOffset && hasValue) {
                                val sql = """
                                    INSERT INTO pp_entries(
                                        method_id, analysis_id, vm_offset, file_offset,
                                        description, type,
                                        function_size, is_leaf, caller_count
                                    )
                                    SELECT
                                        ?,
                                        ?,
                                        s.pp_offset,
                                        0,
                                        s.value,
                                        'String',
                                        0, 0, 0
                                    FROM blutter.strings s
                                    WHERE s.value IS NOT NULL AND s.value != ''
                                """.trimIndent()
                                roomDb.execSQL(sql, arrayOf(unknownMethodId, analysisId))
                                val strCount = getLastChangeCount(roomDb)
                                pp += strCount
                                Log.i(TAG, "导入 strings: $strCount 条 (SQL 直搬)")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "导入 strings 失败", e)
                        }
                    }

                    if ("objs" in blutterTables) {
                        try {
                            val cols = columnNamesAttached(roomDb, "blutter", "objs")
                            val hasAddress = "obj_address" in cols
                            val hasClass = "class_name" in cols
                            val hasHint = "field_hint" in cols
                            if (hasAddress && hasClass) {
                                // 引擎写入 analysis_id=0 占位，这里改写为真实 analysisId
                                val sql = """
                                    INSERT INTO objs(
                                        analysis_id, obj_address, class_name, field_hint
                                    )
                                    SELECT
                                        ?,
                                        o.obj_address,
                                        o.class_name,
                                        o.field_hint
                                    FROM blutter.objs o
                                """.trimIndent()
                                roomDb.execSQL(sql, arrayOf(analysisId))
                                objects = getLastChangeCount(roomDb)
                                Log.i(TAG, "导入 objs: $objects 条 (SQL 直搬)")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "导入 objs 失败", e)
                        }
                    }

                    if ("enum_map" in blutterTables) {
                        try {
                            val cols = columnNamesAttached(roomDb, "blutter", "enum_map")
                            val hasClass = "class_name" in cols
                            val hasIndex = "enum_index" in cols
                            val hasName = "enum_name" in cols
                            if (hasClass && hasIndex && hasName) {
                                val sql = """
                                    INSERT INTO enum_map(
                                        analysis_id, class_name, enum_index, enum_name
                                    )
                                    SELECT
                                        ?,
                                        e.class_name,
                                        e.enum_index,
                                        e.enum_name
                                    FROM blutter.enum_map e
                                """.trimIndent()
                                roomDb.execSQL(sql, arrayOf(analysisId))
                                enums = getLastChangeCount(roomDb)
                                Log.i(TAG, "导入 enum_map: $enums 条 (SQL 直搬)")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "导入 enum_map 失败", e)
                        }
                    }

                    if ("asm_blocks" in blutterTables) {
                        try {
                            val cols = columnNamesAttached(roomDb, "blutter", "asm_blocks")
                            val hasAddress = "method_address" in cols
                            val hasSize = "size" in cols
                            val hasUrl = "url" in cols
                            val hasBody = "body" in cols
                            if (hasAddress && hasBody) {
                                // 映射表：引擎 methods.address(vaddr) → Room dart_methods.id。
                                // dart_methods.function_offset 即引擎 methods.address（libapp 上
                                // vaddr==fileOffset），直接关联。临时表仅本连接可见。
                                roomDb.execSQL("DROP TABLE IF EXISTS _maddr")
                                roomDb.execSQL("CREATE TEMP TABLE _maddr (eaddr INTEGER, rid INTEGER)")
                                roomDb.execSQL(
                                    "INSERT INTO _maddr (eaddr, rid) " +
                                        "SELECT function_offset, id FROM dart_methods " +
                                        "WHERE analysis_id = ?",
                                    arrayOf(analysisId)
                                )
                                // 独立表搬运：method_address → Room method_id
                                val sql = """
                                    INSERT INTO asm_blocks(
                                        analysis_id, method_id, vaddr, size, url, body
                                    )
                                    SELECT
                                        ?,
                                        COALESCE(ma.rid, ?),
                                        a.method_address,
                                        COALESCE(a.size, 0),
                                        a.url,
                                        a.body
                                    FROM blutter.asm_blocks a
                                    LEFT JOIN _maddr ma ON a.method_address = ma.eaddr
                                """.trimIndent()
                                roomDb.execSQL(sql, arrayOf(analysisId, unknownMethodId))
                                asmBlocks = getLastChangeCount(roomDb)
                                Log.i(TAG, "导入 asm_blocks: $asmBlocks 条 (SQL 直搬)")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "导入 asm_blocks 失败", e)
                        }
                    }

                    roomDb.setTransactionSuccessful()
                    Log.i(TAG, "ATTACH 导入完成: classes=$classes, methods=$methodsCount, pp=$pp, objs=$objects, enums=$enums, asm_blocks=$asmBlocks")
                } catch (e: Exception) {
                    Log.e(TAG, "ATTACH 导入失败，回滚", e)
                    // 事务回滚
                } finally {
                    roomDb.endTransaction()
                }

                // 5. DETACH Blutter DB
                roomDb.execSQL("DETACH DATABASE blutter")

            } finally {
                roomDb.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开/读取 Blutter DB 失败: $dbPath", e)
            return@withContext ImportResult()
        }

        val result = ImportResult(
            classesCount = classes,
            methodsCount = methodsCount,
            ppEntriesCount = pp,
            objectsCount = objects,
            enumsCount = enums,
            asmBlocksCount = asmBlocks
        )
        appLogger.info(TAG, "导入完成: ${result.classesCount} 类, ${result.methodsCount} 方法, ${result.ppEntriesCount} PP, ${result.objectsCount} 对象, ${result.enumsCount} 枚举, ${result.asmBlocksCount} ASM 块")
        // 回写统计计数（走 Room 连接），产物页据此展示真实数据。
        // 该 UPDATE 同时触发 Room 的 invalidation tracker，让观察 analyses 表的 Flow 刷新。
        try {
            analysisDao.updateCounts(analysisId, classes, methodsCount, pp)
        } catch (e: Exception) {
            Log.e(TAG, "回写统计计数失败", e)
        }
        Log.i(TAG, "导入完成: classes=$classes, methods=$methodsCount, pp=$pp, objs=$objects, enums=$enums, asm_blocks=$asmBlocks")
        result
    }

    // ========== 辅助函数 ==========

    /** 查询已 ATTACH 的数据库中的表名列表。 */
    private fun listAttachedTables(db: SQLiteDatabase, schema: String): Set<String> {
        val tables = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM ${schema}.sqlite_master WHERE type='table'", null).use { c ->
            while (c.moveToNext()) {
                tables.add(c.getString(0))
            }
        }
        return tables
    }

    /** 查询已 ATTACH 的数据库中指定表的列名列表。 */
    private fun columnNamesAttached(db: SQLiteDatabase, schema: String, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.rawQuery("PRAGMA ${schema}.table_info('$table')", null).use { c ->
            while (c.moveToNext()) {
                names.add(c.getString(1))
            }
        }
        return names
    }

    /** 查询上一次 INSERT/UPDATE/DELETE 影响的行数（基于 SQLite changes() 函数）。 */
    private fun getLastChangeCount(db: SQLiteDatabase): Int {
        db.rawQuery("SELECT changes()", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
