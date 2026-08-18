package com.ai.fler.feature.project

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.ai.fler.core.service.AddressTranslator
import com.ai.fler.core.service.AnalysisImporter
import com.ai.fler.core.service.ApkExtractor
import com.ai.fler.core.service.DartVersionDetector
import com.ai.fler.core.service.EngineLoader
import com.ai.fler.core.service.EngineNotReadyException
import com.ai.fler.core.service.EnginePackManager
import com.ai.fler.core.service.WorkDirectory
import com.ai.fler.data.AppDatabase
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.DartClassDao
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.LibraryDao
import com.ai.fler.data.dao.PpEntryDao
import com.ai.fler.data.dao.ProjectDao
import com.ai.fler.data.entity.Analysis
import com.ai.fler.data.entity.Library
import com.ai.fler.data.entity.Project
import com.ai.fler.features.analysis.AnalysisService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用级分析任务运行器。
 *
 * 分析是耗时流程（提取 so + Blutter 分析 + 结果导入），必须能在用户离开
 * 项目详情页（NavBackStackEntry 销毁 → 屏幕级 ViewModel 被清理）后继续在后台
 * 运行直至完成。因此分析协程归属 [AnalysisRunner] 自身的应用级 Scope
 * （SupervisorJob + Dispatchers.Default），与任何屏幕生命周期解耦。
 *
 * 所有读分析进度的 UI（项目列表、项目详情）观察同一个 [analysisProgress] Flow。
 *
 * 单一性约束：同一时刻只运行一个分析任务（新分析启动会取消旧任务）。
 */
@Singleton
class AnalysisRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDatabase: AppDatabase,
    private val projectDao: ProjectDao,
    private val analysisDao: AnalysisDao,
    private val dartClassDao: DartClassDao,
    private val dartMethodDao: DartMethodDao,
    private val ppEntryDao: PpEntryDao,
    private val libraryDao: LibraryDao,
    private val apkExtractor: ApkExtractor,
    private val dartVersionDetector: DartVersionDetector,
    private val enginePackManager: EnginePackManager,
    private val engineLoader: EngineLoader,
    private val analysisImporter: AnalysisImporter,
    private val addressTranslator: AddressTranslator,
    private val workDirectory: WorkDirectory
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _analysisProgress = MutableStateFlow(AnalysisProgress())
    val analysisProgress: StateFlow<AnalysisProgress> = _analysisProgress.asStateFlow()

    /** 当前运行中的分析任务（null = 空闲）。 */
    private var activeJob: Job? = null
    private var activeProjectId: Long = 0L
    private var activeAnalysisId: Long = 0L

    /**
     * 启动分析。可跨页面后台执行，不再绑定到调用方 ViewModel 生命周期。
     *
     * 启动前台服务（[AnalysisService]）保活 + 持有 WakeLock，防止进程被杀/锁屏 CPU 休眠中断分析。
     *
     * @param projectId 项目 ID
     */
    fun startAnalysis(projectId: Long) {
        activeJob?.cancel()
        releaseWakeLock()
        // 前台服务保活：分析期间进程不被回收
        AnalysisService.start(context)
        // WakeLock：锁屏后 CPU 不休眠，Blutter 分析不降速
        acquireWakeLock()
        _analysisProgress.value = AnalysisProgress(
            projectId = projectId,
            dismissedToBackground = true
        )
        activeProjectId = projectId
        activeJob = scope.launch {
            try {
                analyzeProject(projectId)
            } catch (e: CancellationException) {
                Log.i(TAG, "分析任务已取消: project=$projectId, msg=${e.message}")
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "分析任务未预期异常: project=$projectId", t)
            } finally {
                activeProjectId = 0L
                activeAnalysisId = 0L
                activeJob = null
                // 分析结束：释放 WakeLock + 停止前台服务
                releaseWakeLock()
                AnalysisService.stop(context)
            }
        }
    }

    /**
     * 若当前正在分析指定项目，则取消该任务（用于删除项目等场景）。
     */
    fun cancelProjectIfActive(projectId: Long) {
        if (activeProjectId == projectId) {
            activeJob?.cancel()
        }
    }

    /**
     * 若当前正在分析指定分析记录，则取消该任务（用于删除分析记录等场景）。
     */
    fun cancelAnalysisIfActive(analysisId: Long) {
        if (activeAnalysisId == analysisId) {
            activeJob?.cancel()
        }
    }

    /**
     * 关闭分析进度对话框。仅在分析已完成或失败时重置，让 UI 不再展示。
     */
    fun dismissAnalysisDialog() {
        val current = _analysisProgress.value
        if (current.stage == AnalysisStage.Completed || current.stage == AnalysisStage.Failed) {
            _analysisProgress.value = AnalysisProgress()
        }
    }

    /**
     * 将分析转入后台执行：关闭对话框，分析继续在后台运行。
     */
    fun dismissToBackground() {
        _analysisProgress.value = _analysisProgress.value.copy(dismissedToBackground = true)
    }

    // ========== 分析管线 ==========

    /**
     * 完整分析流程。
     *
     * 每个阶段独立 try-catch，失败时通过 [failAnalysis] 切换进度状态到 Failed，
     * 并把异常详情打到 logcat（TAG = "AnalysisRunner"）。
     */
    private suspend fun analyzeProject(projectId: Long) {
        val project = projectDao.getById(projectId)
        if (project == null) {
            Log.e(TAG, "startAnalysis: project $projectId not found")
            return
        }

        // 创建分析记录（即使后续失败也要有数据库行）
        val analysisId = createAnalysisRecord(projectId)
        activeAnalysisId = analysisId

        // ====================================================================
        // 阶段 1: 提取 so 文件
        // ====================================================================
        val extractResult: ApkExtractor.ExtractResult
        try {
            updateProgress(projectId, AnalysisStage.Extracting, 0.1f, "Extracting .so files...")
            Log.i(TAG, "阶段 1/5: 提取 so 文件, apkPath=${project.apkPath}")

            extractResult = extractSoFiles(project)
            if (!extractResult.isSuccess) {
                failAnalysis(analysisId, extractResult.error ?: "Extraction failed")
                return
            }
            Log.i(TAG, "阶段 1/5 完成: libapp=${extractResult.libappPath}, libflutter=${extractResult.libflutterPath}, " +
                "extraLibs=${extractResult.extraLibs.size}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "阶段 1/5 提取 so 失败", e)
            failAnalysis(analysisId, "提取 so 文件失败: ${e.message}")
            return
        }

        // ====================================================================
        // 非 Flutter 兜底：无 libapp.so 时跳过 Blutter 阶段（2/3/4），
        // 直接把提取到的 native 库登记入库并完成分析
        // （产物浏览复用项目详情页的「SO 文件」列表 → SO 编辑器）
        // ====================================================================
        if (!extractResult.isFlutter) {
            val libCount = extractResult.extraLibs.size +
                (if (extractResult.libflutter != null) 1 else 0)
            Log.i(TAG, "非 Flutter APK，跳过 Blutter 阶段，仅提取 $libCount 个 native 库")
            try {
                updateProgress(
                    projectId, AnalysisStage.SavingResults, 0.8f,
                    "非 Flutter 应用：保存 native 库信息..."
                )
                val nativeResult = AnalyzeResult(
                    success = true,
                    classesCount = 0,
                    methodsCount = 0,
                    ppEntriesCount = 0,
                    errorMessage = null
                )
                completeAnalysis(analysisId, extractResult, nativeResult)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "非 Flutter 分析保存失败", e)
                failAnalysis(analysisId, "保存分析结果失败: ${e.message}")
                return
            }
            updateProgress(
                projectId, AnalysisStage.Completed, 1.0f,
                "非 Flutter 应用：已提取 $libCount 个 native 库"
            )
            projectDao.update(project.copy(status = Project.STATUS_COMPLETED))
            return
        }

        // ====================================================================
        // 阶段 2: 检测 Dart 版本
        // ====================================================================
        val dartVersion: String
        try {
            updateProgress(projectId, AnalysisStage.DetectingVersion, 0.3f, "Detecting Dart version...")
            val libflutterPath = extractResult.libflutterPath
            val detected = if (libflutterPath.isNullOrEmpty()) {
                Log.w(TAG, "libflutterPath 为空，跳过版本检测")
                null
            } else {
                dartVersionDetector.detect(libflutterPath)
            }

            if (detected != null) {
                dartVersion = detected
                Log.i(TAG, "阶段 2/5 完成: 检测到 Dart 版本 = $dartVersion")
            } else {
                // 检测失败：不再静默 fallback（避免用错版本引擎导致快照解析越界），明确报错并列出已安装引擎
                val installed = enginePackManager.listInstalledVersions()
                Log.e(TAG, "阶段 2/5: 未检测到 Dart 版本。已安装引擎: $installed")
                failAnalysis(
                    analysisId,
                    "未检测到 Dart 版本。请确认 APK 是 Flutter 应用。" +
                        (if (installed.isNotEmpty()) " 已安装引擎版本: ${installed.joinToString()}。" else " 本地未安装任何引擎，请先在设置页下载。")
                )
                return
            }

            // 更新项目的 Dart 版本
            projectDao.update(
                project.copy(
                    dartVersion = dartVersion,
                    status = Project.STATUS_ANALYZING
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "阶段 2/5 Dart 版本检测失败", e)
            failAnalysis(analysisId, "Dart 版本检测失败: ${e.message}")
            return
        }

        // ====================================================================
        // 阶段 3: 加载引擎
        // 必须 System.load dartvm_<dartVersion>.so，否则 dlsym 找不到 blutter_analyze 符号
        // ====================================================================
        try {
            updateProgress(projectId, AnalysisStage.LoadingEngine, 0.5f, "Loading engine $dartVersion...")
            Log.i(TAG, "阶段 3/5: 加载引擎 dartvm_${dartVersion}.so")
            engineLoader.loadEngine(dartVersion)
            Log.i(TAG, "阶段 3/5 完成: 引擎已加载")
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineNotReadyException) {
            Log.e(TAG, "阶段 3/5 引擎未就绪: ${e.message}")
            failAnalysis(analysisId, "引擎未就绪: ${e.message}。请在设置页下载 Dart $dartVersion 引擎（需先安装运行库）。")
            return
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "阶段 3/5 引擎加载失败 (UnsatisfiedLinkError)", e)
            failAnalysis(analysisId, "引擎加载失败 (UnsatisfiedLinkError): ${e.message}")
            return
        } catch (e: Exception) {
            Log.e(TAG, "阶段 3/5 引擎加载失败", e)
            failAnalysis(analysisId, "引擎加载失败: ${e.message}")
            return
        }

        // ====================================================================
        // 阶段 4: 执行分析
        // ====================================================================
        val analyzeOutcome: RunOutcome
        try {
            updateProgress(projectId, AnalysisStage.Analyzing, 0.6f, "Running analysis...")
            val libappPath = extractResult.libappPath
            if (libappPath.isNullOrEmpty()) {
                failAnalysis(analysisId, "libapp.so 路径为空，无法执行分析")
                return
            }
            Log.i(TAG, "阶段 4/5: 调用 BlutterEngine.analyze, libapp=$libappPath, dartVersion=$dartVersion")
            analyzeOutcome = runAnalysis(
                libappPath = libappPath,
                dartVersion = dartVersion,
                analysisId = analysisId
            )
            if (!analyzeOutcome.result.success) {
                Log.e(TAG, "阶段 4/5 分析失败: ${analyzeOutcome.result.errorMessage}")
                failAnalysis(analysisId, analyzeOutcome.result.errorMessage ?: "分析失败")
                return
            }
            Log.i(TAG, "阶段 4/5 完成")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "阶段 4/5 BlutterEngine.analyze 异常", e)
            failAnalysis(analysisId, "分析执行异常: ${e.message}")
            return
        }

        // ====================================================================
        // 阶段 5: 保存结果
        // ====================================================================
        try {
            updateProgress(projectId, AnalysisStage.SavingResults, 0.8f, "Saving results...")
            saveAnalysisResults(analysisId, projectId, analyzeOutcome)
            completeAnalysis(analysisId, extractResult, analyzeOutcome.result)

            // 构建地址映射（产物 ↔ SO 联动）
            try {
                val libappPath = extractResult.libapp?.path
                if (libappPath != null) {
                    // 轻量投影（不含 src_code 大字段）：55781 条全量载入会占数百 MB 内存
                    val methods = dartMethodDao.getByAnalysisIdLight(analysisId)
                    addressTranslator.importMethods(projectId, libappPath, methods)
                }
            } catch (e: Exception) {
                Log.w(TAG, "构建地址映射失败（不影响分析结果）: ${e.message}", e)
            }

            Log.i(TAG, "阶段 5/5 完成")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "阶段 5/5 保存结果失败", e)
            failAnalysis(analysisId, "保存分析结果失败: ${e.message}")
            return
        }

        updateProgress(projectId, AnalysisStage.Completed, 1.0f, "Analysis completed!")
        // 必须用 dartVersion（阶段 2 检测到的）保留，否则会覆盖回 null（用最初的 project 会导致 Dart N/A）
        projectDao.update(
            project.copy(
                dartVersion = dartVersion,
                engineVersion = dartVersion,
                status = Project.STATUS_COMPLETED
            )
        )
    }

    /**
     * 创建分析记录。
     */
    private suspend fun createAnalysisRecord(projectId: Long): Long {
        val analysis = Analysis(
            projectId = projectId,
            resultCode = Analysis.RESULT_PENDING
        )
        return analysisDao.insert(analysis)
    }

    /**
     * 提取 so 文件。
     */
    private suspend fun extractSoFiles(project: Project): ApkExtractor.ExtractResult {
        val extractDir = File(context.cacheDir, "extracted_${project.id}")
        return apkExtractor.extract(project.apkPath, extractDir)
    }

    /**
     * 运行 Blutter 分析。
     *
     * @param libappPath libapp.so 路径
     * @param dartVersion 检测到的 Dart 版本
     * @param analysisId 分析记录 ID（用于生成数据库路径）
     */
    private suspend fun runAnalysis(
        libappPath: String,
        dartVersion: String,
        analysisId: Long
    ): RunOutcome {
        return try {
            // loadEngine 会触发 System.load dartvm_<dartVersion>.so（如已在阶段 3 加载则跳过）
            // 并返回持有 so 路径的 BlutterEngine，供 JNI 用 dlopen(RTLD_NOLOAD) 查找符号
            val engine = engineLoader.loadEngine(dartVersion)

            // 生成数据库文件路径
            val dbPath = File(context.cacheDir, "analysis_${analysisId}.db").absolutePath
            val cacheDir = context.cacheDir.absolutePath
            // 引擎产物落地目录（按分析 ID 区分，隔离多项目）
            val outDir = File(cacheDir, "blutter_tmp/analysis_${analysisId}").absolutePath
            File(outDir).mkdirs()

            // 调用 analyze 方法（在 IO 线程执行，避免阻塞主线程）
            val result = withContext(Dispatchers.IO) { engine.analyze(libappPath, dbPath, cacheDir, outDir) }

            // 转换结果
            val success = result.isSuccess
            RunOutcome(
                AnalyzeResult(
                    success = success,
                    // 注意：实际统计数据在导入阶段从分析结果数据库读取
                    classesCount = 0,
                    methodsCount = 0,
                    ppEntriesCount = 0,
                    errorMessage = if (success) null
                    else "Blutter 引擎错误码 code=${result.rawCode}（查看 logcat TAG=FlerBlutterJNI 输入诊断信息）"
                ),
                dbPath = dbPath,
                outDir = outDir
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RunOutcome(
                AnalyzeResult(
                    success = false,
                    errorMessage = e.message ?: "Analysis exception"
                ),
                dbPath = "",
                outDir = ""
            )
        }
    }

    /**
     * 保存分析结果到数据库。
     *
     * 通过 [AnalysisImporter] 把 Blutter 生成的 SQLite 中的
     * classes/methods/pp_entries/strings 读入 Room；统计计数由 [AnalysisImporter] 内部回写。
     *
     * 分析成功后还会把引擎产物（outDir 目录）打包 zip 拷贝到用户设置的工作目录，
     * 产物仍在 App 缓存保留（blutter_tmp/analysis_<id>/），供 MCP 读取。
     */
    private suspend fun saveAnalysisResults(
        analysisId: Long,
        projectId: Long,
        outcome: RunOutcome
    ) {
        if (outcome.result.success && outcome.dbPath.isNotBlank()) {
            analysisImporter.import(analysisId, outcome.dbPath)
        }
        if (outcome.result.success && outcome.outDir.isNotBlank()) {
            archiveAndExportProducts(projectId, analysisId, outcome.outDir)
        }
    }

    /**
     * 把引擎产物目录打包 zip 并拷贝到工作目录。
     *
     * 产物目录结构：
     *   outDir/pp.txt、objs.txt、asm 目录、ida_script 目录、blutter_frida.js
     *
     * zip 命名：blutter_products_&lt;项目名&gt;_&lt;analysisId&gt;.zip，放在工作目录根；未设置工作目录则跳过（产物仍在缓存）。
     */
    private suspend fun archiveAndExportProducts(
        projectId: Long,
        analysisId: Long,
        outDir: String
    ) {
        withContext(Dispatchers.IO) {
            val dir = File(outDir)
            if (!dir.isDirectory) return@withContext
            val topFiles = dir.listFiles()?.filter { it.isFile } ?: emptyList()
            val subDirs = dir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            if (topFiles.isEmpty() && subDirs.isEmpty()) return@withContext
            val projectName = projectDao.getById(projectId)?.name?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: "unknown"
            val zipFile = File(dir, "blutter_products_${projectName}_$analysisId.zip")
            try {
                ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                    // 顶层文件（pp.txt / objs.txt / blutter_frida.js 等）
                    topFiles.forEach { f ->
                        zip.putNextEntry(ZipEntry(f.name))
                        FileInputStream(f).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    // 子目录递归打包（asm/、ida_script/），保留相对路径
                    subDirs.forEach { sub ->
                        addDirToZip(zip, sub, sub.name)
                    }
                }
                // 拷贝到工作目录（SAF 或兜底 App 缓存）；失败不影响分析结果
                try {
                    exportZipToWorkDir(zipFile)
                } catch (e: Exception) {
                    Log.w(TAG, "产物 zip 拷贝工作目录失败（不影响分析）: ${e.message}", e)
                }
            } catch (e: Exception) {
                Log.w(TAG, "产物打包失败（不影响分析）: ${e.message}", e)
            }
        }
    }

    /** 递归把 [dir] 下全部文件写入 zip，入口目录名作前缀（保留目录层级）。 */
    private fun addDirToZip(zip: ZipOutputStream, dir: File, entryPrefix: String) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            val entryName = "$entryPrefix/${f.name}"
            if (f.isDirectory) {
                addDirToZip(zip, f, entryName)
            } else {
                zip.putNextEntry(ZipEntry(entryName))
                FileInputStream(f).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** 拷贝产物 zip 到工作目录；未设置/不可写则跳过（产物仍在 App 缓存）。 */
    private suspend fun exportZipToWorkDir(zipFile: File) {
        val doc = workDirectory.asDocumentFile()
        if (doc == null || !doc.canWrite()) return
        val child = doc.createFile("application/zip", zipFile.name) ?: return
        context.contentResolver.openOutputStream(child.uri)?.use { os ->
            zipFile.inputStream().use { it.copyTo(os) }
            os.flush()
        }
    }

    /**
     * 完成分析：写入结果码与库信息。
     */
    private suspend fun completeAnalysis(
        analysisId: Long,
        extractResult: ApkExtractor.ExtractResult,
        result: AnalyzeResult
    ) {
        analysisDao.completeAnalysis(
            id = analysisId,
            resultCode = if (result.success) Analysis.RESULT_SUCCESS else Analysis.RESULT_GENERIC_ERROR
        )

        // 保存 SO 路径到分析记录（供 SO 编辑器查询 Dart 方法标签）
        analysisDao.updateLibPaths(
            id = analysisId,
            libappPath = extractResult.libapp?.path,
            libflutterPath = extractResult.libflutter?.path
        )

        // 保存库信息（Flutter：libapp/libflutter；非 Flutter：回退提取的全部 native 库）
        extractResult.libapp?.let { lib ->
            libraryDao.insert(lib.copy(analysisId = analysisId))
        }
        extractResult.libflutter?.let { lib ->
            libraryDao.insert(lib.copy(analysisId = analysisId))
        }
        extractResult.extraLibs.forEach { lib ->
            libraryDao.insert(lib.copy(analysisId = analysisId))
        }
    }

    /**
     * 标记分析失败。
     *
     * 同步更新进度状态（stage=Failed + error），避免 UI 永远卡在 Extracting。
     */
    private suspend fun failAnalysis(analysisId: Long, error: String) {
        analysisDao.updateResult(
            id = analysisId,
            resultCode = Analysis.RESULT_GENERIC_ERROR,
            errorMessage = error
        )
        // 关键：必须更新 _analysisProgress，否则对话框永远停在当前阶段
        _analysisProgress.value = AnalysisProgress(
            projectId = _analysisProgress.value.projectId,
            stage = AnalysisStage.Failed,
            progress = _analysisProgress.value.progress,
            message = "分析失败",
            error = error
        )
    }

    /**
     * 更新进度。
     */
    private fun updateProgress(
        projectId: Long,
        stage: AnalysisStage,
        progress: Float,
        message: String
    ) {
        _analysisProgress.value = AnalysisProgress(
            projectId = projectId,
            stage = stage,
            progress = progress,
            message = message
        )
    }

    // ========== WakeLock 管理 ==========

    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fler:analysis").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "AnalysisRunner"

        /** WakeLock 最长持有时间（分析超长时兜底释放，避免系统强制回收）。 */
        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L
    }
}