package com.ai.fler.core.service

import android.content.Context
import android.util.Log
import com.ai.fler.core.analysis.AnalysisSession
import com.ai.fler.core.analysis.SoEditorCache
import com.ai.fler.core.log.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 引擎包管理器（v0.4.0 按版本拆分协议）。
 *
 * 引擎资产不再是单一整包，而是：
 * - 运行库 `fler-runtime-libs.7z`（必装基线，内含 lib/libc++_shared.so），任何引擎加载前必须先就绪；
 * - 每个 Dart 版本独立 `dartvm-<v>.7z`（内含 dartvm_<v>.so，增量共存，互不覆盖）。
 *
 * 下载/校验/解压信息全部来自远程 manifest.json（见 [EngineManifest]）。
 *
 * 引擎目录布局（解压后）：
 * ```
 * filesDir/engines/
 * ├── lib/libc++_shared.so      ← 来自 fler-runtime-libs.7z（必装）
 * ├── dartvm_3.13.0.so          ← 来自 dartvm-3.13.0.7z（按需）
 * └── ...
 * ```
 *
 * 注：ICU 不再是运行库必需（blutter dartvm 构建期已跳过 ICU 链接，readelf 无
 * NEEDED libicuuc/libicudata），故不随包；EngineLoader 保留对旧包 ICU 的宽容加载。
 */
@Singleton
class EnginePackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: DualSourceDownloader,
    private val extractor: EngineExtractor,
    private val engineLoader: EngineLoader,
    private val sourceConfig: EngineSourceConfig,
    private val analysisSession: AnalysisSession,
    private val soEditorCache: SoEditorCache,
    private val backupManager: BackupManager,
    private val appLogger: AppLogger,
) {
    private val engineDir: File by lazy { File(context.filesDir, "engines") }

    companion object {
        private const val TAG = "FlerEngine"
        private const val KEY_INSTALLED_PACK_VERSION = "installed_pack_version"

        /** per-version 来源包版本记录前缀：engine_<dartVersion>_pack = v0.4.4 */
        private const val KEY_ENGINE_PACK_PREFIX = "engine_"
        private const val KEY_ENGINE_PACK_SUFFIX = "_pack"

        /** 下载+SHA256 校验的最大尝试次数（失败自动重试）。 */
        private const val MAX_DOWNLOAD_ATTEMPTS = 3

        /** 运行库包缓存文件名（cacheDir 下）。 */
        private const val FILE_RUNTIME_LIBS = "fler-runtime-libs.7z"
    }

    /**
     * 引擎进度数据类，用于向 UI 汇报当前状态。
     */
    data class EngineProgress(
        val phase: Phase,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val speed: String = "",
        val extractProgress: Float = 0f,
        val errorMessage: String? = null,
        /** 批量下载（一键下载全部）时的进度信息：已完成数 / 总数。 */
        val batchCompleted: Int = 0,
        val batchTotal: Int = 0,
        /** 批量下载时的版本提示文案（如「正在下载 Dart 3.12.2」）。 */
        val batchLabel: String = "",
    ) {
        enum class Phase {
            IDLE,
            DOWNLOADING,
            VERIFYING,
            EXTRACTING,
            LOADING,
            COMPLETED,
            CANCELLED,
            FAILED,
        }

        val downloadProgress: Float
            get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f

        val overallProgress: Float
            get() = when (phase) {
                Phase.IDLE -> 0f
                Phase.DOWNLOADING -> downloadProgress * 0.7f
                Phase.VERIFYING -> 0.75f
                Phase.EXTRACTING -> 0.75f + extractProgress * 0.2f
                Phase.LOADING -> 0.95f
                Phase.COMPLETED -> 1.0f
                Phase.CANCELLED -> 0f
                Phase.FAILED -> 0f
            }
    }

    private val _progress = MutableStateFlow(EngineProgress(EngineProgress.Phase.IDLE))
    val progress: Flow<EngineProgress> = _progress.asStateFlow()

    /** 取消下载请求标志：置位后正在进行的安装流程尽快中止。 */
    @Volatile
    private var cancelRequested = false

    /** 引擎版本变更信号：下载完成 / 清除后自增，供设置页等 UI 实时刷新已安装版本。 */
    private val _versionsEpoch = MutableStateFlow(0L)
    val versionsEpoch: StateFlow<Long> = _versionsEpoch.asStateFlow()

    /** 已安装引擎包版本（装完新版本后更新检测不再提示；缺省回退内置版本）。 */
    private var installedPackVersion: String
        get() = prefs().getString(KEY_INSTALLED_PACK_VERSION, null)
            ?: EngineSourceConfig.ENGINE_PACKAGE_VERSION
        set(value) = prefs().edit().putString(KEY_INSTALLED_PACK_VERSION, value).apply()

    /** 当前已安装的引擎包版本（未装过时回退内置缺省版本）。 */
    fun currentInstalledPackVersion(): String = installedPackVersion

    /** 某 Dart 版本引擎的来源包版本记录（未安装过返回 null）。 */
    fun getEngineInstalledPack(dartVersion: String): String? =
        prefs().getString(KEY_ENGINE_PACK_PREFIX + dartVersion + KEY_ENGINE_PACK_SUFFIX, null)

    private fun setEngineInstalledPack(dartVersion: String, packVersion: String) {
        prefs().edit()
            .putString(KEY_ENGINE_PACK_PREFIX + dartVersion + KEY_ENGINE_PACK_SUFFIX, packVersion)
            .apply()
    }

    private fun clearEngineInstalledPack(dartVersion: String) {
        prefs().edit().remove(KEY_ENGINE_PACK_PREFIX + dartVersion + KEY_ENGINE_PACK_SUFFIX).apply()
    }

    /**
     * 清理当前 manifest 中已不存在的旧版本引擎文件（远程包移除某版本时残留）。
     */
    private fun cleanupStaleEngines(manifest: EngineManifest) {
        val valid = manifest.engines.map { it.dartVersion }.toSet()
        engineDir.listFiles()
            ?.filter { it.name.startsWith("dartvm_") && it.name.endsWith(".so") }
            ?.map { it.name.removePrefix("dartvm_").removeSuffix(".so") }
            ?.filter { it !in valid }
            ?.forEach { stale ->
                val staleFile = File(engineDir, "dartvm_$stale.so")
                if (staleFile.delete()) {
                    clearEngineInstalledPack(stale)
                    Log.i(TAG, "清理旧版本引擎文件: dartvm_$stale.so")
                }
            }
    }

    private fun prefs() = context.getSharedPreferences("engine_pack", Context.MODE_PRIVATE)

    /** 通知已安装引擎版本发生变化（下载完成 / 清除）。 */
    fun notifyVersionsChanged() {
        _versionsEpoch.value++
    }

    // ========== 就绪状态 ==========

    /** 运行库（必装基线）是否就绪：lib/libc++_shared.so 存在。 */
    fun isRuntimeReady(): Boolean = File(engineDir, "lib/libc++_shared.so").exists()

    /** 指定 Dart 版本的引擎是否已安装。 */
    fun isEngineVersionReady(dartVersion: String): Boolean =
        File(engineDir, "dartvm_${dartVersion}.so").exists()

    /** 整体就绪：运行库 + 至少一个引擎版本。 */
    fun isEnginePackReady(): Boolean =
        isRuntimeReady() && engineDir.listFiles()
            ?.any { it.name.startsWith("dartvm_") && it.name.endsWith(".so") } == true

    /**
     * 列出已安装的 Dart 引擎版本。
     */
    suspend fun listInstalledVersions(): List<String> = withContext(Dispatchers.IO) {
        if (!engineDir.exists()) return@withContext emptyList()

        engineDir.listFiles()
            ?.filter { it.name.startsWith("dartvm_") && it.name.endsWith(".so") }
            ?.map { file ->
                // 从 dartvm_3.12.1.so 提取 3.12.1
                file.name.removePrefix("dartvm_").removeSuffix(".so")
            }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * 判断指定 Dart 版本引擎是否需要更新（已安装 且 来源包版本落后于最新）。
     *
     * 未安装返回 false；已安装但无来源记录（旧版 App 装的）视为可更新。
     */
    fun isEngineUpdateable(dartVersion: String, currentPackVersion: String): Boolean {
        if (!isEngineVersionReady(dartVersion)) return false
        val source = getEngineInstalledPack(dartVersion)
        return source != currentPackVersion
    }

    /**
     * 列出已安装但来源包版本落后于 [currentPackVersion] 的可更新版本。
     */
    suspend fun listUpdateableVersions(currentPackVersion: String): List<String> =
        withContext(Dispatchers.IO) {
            listInstalledVersions().filter { isEngineUpdateable(it, currentPackVersion) }
        }

    /**
     * 获取远程引擎清单（manifest.json）。失败返回 null（网络/源配置问题）。
     */
    suspend fun fetchManifest(): EngineManifest? = downloader.fetchManifest()

    /**
     * 请求取消当前下载/安装流程。
     *
     * 置位取消标志并中断 okhttp Call；进行中的 [installEngineVersion] /
     * [installAllEngines] / [installRuntimeLibs] 会在下载阶段尽快中止。
     * 流程结束时由各入口复位标志。
     */
    fun cancelDownloads() {
        cancelRequested = true
        downloader.cancel()
    }

    /** 复位取消标志（下载入口启动时 / 流程结束时调用）。 */
    private fun resetCancelFlag() {
        cancelRequested = false
    }

    // ========== 安装流程 ==========

    /**
     * 安装（或更新）运行库包 —— 必装基线。
     *
     * 已就绪且非 force 时直接 COMPLETED 短路。
     *
     * @param force 为 true 时强制重新下载（「下载更新」用），即使已就绪也重下。
     */
    fun installRuntimeLibs(force: Boolean = false): Flow<EngineProgress> = channelFlow {
        resetCancelFlag()
        if (!force && isRuntimeReady()) {
            Log.i(TAG, "运行库已就绪，跳过下载")
            emitProgress(this, EngineProgress(EngineProgress.Phase.COMPLETED))
            return@channelFlow
        }

        try {
            val manifest = downloader.fetchManifest()
                ?: throw IllegalStateException("无法获取引擎清单 manifest.json（请检查下载源配置与网络）")
            val rt = manifest.runtimeLibs
                ?: throw IllegalStateException("manifest 缺少运行库信息（runtimeLibs）")

            installAsset(this, rt.file, rt.url, rt.sha256, FILE_RUNTIME_LIBS)

            if (!isRuntimeReady()) {
                throw IllegalStateException("运行库安装后仍不可用（lib/libc++_shared.so 缺失），请清除引擎后重试")
            }

            emitProgress(this, EngineProgress(EngineProgress.Phase.LOADING))
            engineLoader.ensureSharedLibsLoaded()

            installedPackVersion = manifest.packVersion
            notifyVersionsChanged()
            Log.i(TAG, "运行库安装完成")
            appLogger.info(TAG, "运行库安装完成")
            emitProgress(this, EngineProgress(EngineProgress.Phase.COMPLETED))
        } catch (e: kotlinx.coroutines.CancellationException) {
            resetCancelFlag()
            Log.i(TAG, "运行库下载已取消")
            val err = EngineProgress(
                phase = EngineProgress.Phase.CANCELLED,
                errorMessage = "下载已取消",
            )
            _progress.value = err
            send(err)
        } catch (e: Exception) {
            Log.e(TAG, "运行库安装失败: ${e.message}", e)
            appLogger.error(TAG, "运行库安装失败: ${e.message}")
            val err = EngineProgress(
                phase = EngineProgress.Phase.FAILED,
                errorMessage = e.message ?: "未知错误",
            )
            _progress.value = err
            send(err)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 按需安装指定 Dart 版本的引擎。
     *
     * 运行库未就绪时先自动补装运行库（必装基线）。已安装则直接 COMPLETED 短路。
     */
    fun installEngineVersion(dartVersion: String): Flow<EngineProgress> = channelFlow {
        resetCancelFlag()
        try {
            val manifest = downloader.fetchManifest()
                ?: throw IllegalStateException("无法获取引擎清单 manifest.json（请检查下载源配置与网络）")
            val entry = manifest.engines.firstOrNull { it.dartVersion == dartVersion }
                ?: throw IllegalStateException("远程清单中不存在 Dart $dartVersion 引擎")

            // 已安装且来源包版本 == 当前最新 → 无需重下
            if (isEngineVersionReady(dartVersion) &&
                getEngineInstalledPack(dartVersion) == manifest.packVersion
            ) {
                Log.i(TAG, "引擎 Dart $dartVersion 已就绪且为最新，跳过下载")
                emitProgress(this, EngineProgress(EngineProgress.Phase.COMPLETED))
                return@channelFlow
            }

            // 运行库必装：缺失时先补装
            if (!isRuntimeReady()) {
                val rt = manifest.runtimeLibs
                    ?: throw IllegalStateException("manifest 缺少运行库信息（runtimeLibs），无法加载引擎")
                installAsset(this, rt.file, rt.url, rt.sha256, FILE_RUNTIME_LIBS)
                if (!isRuntimeReady()) {
                    throw IllegalStateException("运行库安装后仍不可用，无法加载引擎")
                }
                engineLoader.ensureSharedLibsLoaded()
            }

            installAsset(this, entry.file, entry.url, entry.sha256, "dartvm-${dartVersion}.7z")

            if (!isEngineVersionReady(dartVersion)) {
                throw IllegalStateException("引擎 Dart $dartVersion 安装后仍不可用，请清除引擎后重试")
            }

            installedPackVersion = manifest.packVersion
            setEngineInstalledPack(dartVersion, manifest.packVersion)
            cleanupStaleEngines(manifest)
            notifyVersionsChanged()
            Log.i(TAG, "引擎 Dart $dartVersion 安装完成（来源包 $manifest.packVersion）")
            appLogger.info(TAG, "引擎 Dart $dartVersion 安装完成")
            emitProgress(this, EngineProgress(EngineProgress.Phase.COMPLETED))
        } catch (e: kotlinx.coroutines.CancellationException) {
            resetCancelFlag()
            Log.i(TAG, "引擎下载已取消: Dart $dartVersion")
            val err = EngineProgress(
                phase = EngineProgress.Phase.CANCELLED,
                errorMessage = "下载已取消",
            )
            _progress.value = err
            send(err)
        } catch (e: Exception) {
            Log.e(TAG, "引擎安装失败: ${e.message}", e)
            appLogger.error(TAG, "引擎安装失败: ${e.message}")
            val err = EngineProgress(
                phase = EngineProgress.Phase.FAILED,
                errorMessage = e.message ?: "未知错误",
            )
            _progress.value = err
            send(err)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 一键下载全部引擎（逐个串行）。
     *
     * 单次 fetchManifest；先确保运行库就绪，再对 manifest.engines 中每个
     * 未安装的版本逐个执行下载/校验/解压。已安装的跳过；单个版本失败记录
     * 错误并继续下一个，最后汇总（COMPLETED 若至少成功一个，否则 FAILED）。
     */
    fun installAllEngines(): Flow<EngineProgress> = channelFlow {
        resetCancelFlag()
        try {
            val manifest = downloader.fetchManifest()
                ?: throw IllegalStateException("无法获取引擎清单 manifest.json（请检查下载源配置与网络）")
            if (manifest.engines.isEmpty()) {
                throw IllegalStateException("远程清单中没有任何引擎版本")
            }

            // 运行库必装基线
            if (!isRuntimeReady()) {
                val rt = manifest.runtimeLibs
                    ?: throw IllegalStateException("manifest 缺少运行库信息（runtimeLibs）")
                emitProgress(this, EngineProgress(EngineProgress.Phase.DOWNLOADING, batchLabel = "正在安装运行库..."))
                installAsset(this, rt.file, rt.url, rt.sha256, FILE_RUNTIME_LIBS)
                if (!isRuntimeReady()) {
                    throw IllegalStateException("运行库安装后仍不可用，无法加载引擎")
                }
                engineLoader.ensureSharedLibsLoaded()
            }

            // 需要安装/更新的版本列表（未装 或 来源包版本落后于最新）
            val pending = manifest.engines
                .map { it.dartVersion }
                .filter {
                    !isEngineVersionReady(it) || getEngineInstalledPack(it) != manifest.packVersion
                }
            val total = pending.size
            if (total == 0) {
                emitProgress(this, EngineProgress(EngineProgress.Phase.COMPLETED, batchTotal = total))
                return@channelFlow
            }

            var completed = 0
            var failed = 0
            val failedVersions = mutableListOf<String>()
            for (version in pending) {
                emitProgress(
                    this,
                    EngineProgress(
                        phase = EngineProgress.Phase.DOWNLOADING,
                        batchCompleted = completed,
                        batchTotal = total,
                        batchLabel = "正在下载 Dart $version ($completed/$total)",
                    )
                )
                try {
                    val entry = manifest.engines.first { it.dartVersion == version }
                    installAsset(this, entry.file, entry.url, entry.sha256, "dartvm-${version}.7z")
                    if (!isEngineVersionReady(version)) {
                        throw IllegalStateException("引擎 Dart $version 安装后仍不可用")
                    }
                    setEngineInstalledPack(version, manifest.packVersion)
                    completed++
                    Log.i(TAG, "批量下载：Dart $version 完成 ($completed/$total)")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    failedVersions.add(version)
                    Log.e(TAG, "批量下载：Dart $version 失败: ${e.message}", e)
                }
            }

            installedPackVersion = manifest.packVersion
            cleanupStaleEngines(manifest)
            notifyVersionsChanged()
            Log.i(TAG, "批量下载结束: 成功 $completed/$total, 失败 $failed")
            appLogger.info(TAG, "批量下载引擎结束: 成功 $completed/$total")

            if (failed == 0) {
                emitProgress(
                    this,
                    EngineProgress(
                        phase = EngineProgress.Phase.COMPLETED,
                        batchCompleted = completed,
                        batchTotal = total,
                        batchLabel = "全部引擎下载完成 ($completed/$total)",
                    )
                )
            } else {
                emitProgress(
                    this,
                    EngineProgress(
                        phase = EngineProgress.Phase.FAILED,
                        batchCompleted = completed,
                        batchTotal = total,
                        batchLabel = "部分引擎下载失败（${failedVersions.joinToString()}）",
                        errorMessage = "失败版本: ${failedVersions.joinToString()}（已成功 $completed/$total）",
                    )
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            resetCancelFlag()
            Log.i(TAG, "批量下载引擎已取消")
            val err = EngineProgress(
                phase = EngineProgress.Phase.CANCELLED,
                errorMessage = "下载已取消",
            )
            _progress.value = err
            send(err)
        } catch (e: Exception) {
            Log.e(TAG, "批量下载引擎失败: ${e.message}", e)
            appLogger.error(TAG, "批量下载引擎失败: ${e.message}")
            val err = EngineProgress(
                phase = EngineProgress.Phase.FAILED,
                errorMessage = e.message ?: "未知错误",
            )
            _progress.value = err
            send(err)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 检查引擎包更新。
     *
     * 对比本地已安装版本与远程最新 manifest 的 packVersion。
     */
    suspend fun checkForUpdates(): EngineUpdate? = withContext(Dispatchers.IO) {
        try {
            val manifest = downloader.fetchManifest() ?: return@withContext null
            if (manifest.packVersion == installedPackVersion) {
                return@withContext null
            }
            EngineUpdate(
                version = manifest.packVersion,
                downloadUrl = manifest.runtimeLibs?.url ?: "",
                sizeBytes = manifest.runtimeLibs?.sizeBytes ?: 0L,
                releaseNotes = manifest.releaseNotes,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 清理引擎包（调试/卸载场景）。
     */
    suspend fun clearEngines() = withContext(Dispatchers.IO) {
        engineDir.deleteRecursively()
        // 清理全部 per-version 来源记录 + 包级版本
        val editor = prefs().edit()
        prefs().all.keys
            .filter { it.startsWith(KEY_ENGINE_PACK_PREFIX) && it.endsWith(KEY_ENGINE_PACK_SUFFIX) }
            .forEach { editor.remove(it) }
        editor.remove(KEY_INSTALLED_PACK_VERSION)
        editor.apply()
        notifyVersionsChanged()
    }

    /**
     * 清理项目缓存（磁盘 + 内存态），返回释放字节数。
     *
     * 磁盘范围（不包含引擎引擎文件、不包含 Room DB）：
     *  - cacheDir/：apk_import_* / so_import_* / extracted_* / analysis_*.db{,-wal,-shm}
     *                patches / blutter_tmp / fler-runtime-libs.7z / dartvm-*.7z / address_mappings
     *  - filesDir/：undo / mcp_patches
     *
     * 内存范围：
     *  - SoEditorCache（sections/symbols/functions/Dart 标签/Rizin 注入标记）
     *  - AnalysisSession（所有 RzCore open handle，pathToHandle/sessions 清空）
     *  - BackupManager（撤销栈内存态、backupCreated 标记）
     */
    suspend fun cleanProjectCaches(): Long = withContext(Dispatchers.IO) {
        var freed = 0L

        // ---------- 1. cacheDir：原 8 类 + 分版本下载临时包 + address_mappings 兜底 ----------
        val cache = context.cacheDir
        cache.listFiles()?.forEach { f ->
            val name = f.name
            val isAnalysisDb = f.isFile && name.startsWith("analysis_") &&
                (name.endsWith(".db") || name.endsWith("-wal") || name.endsWith("-shm"))
            val isEngineTemp = name == FILE_RUNTIME_LIBS ||
                (name.startsWith("dartvm-") && name.endsWith(".7z"))
            val shouldDelete = name.startsWith("apk_import_") ||
                name.startsWith("so_import_") ||
                name.startsWith("extracted_") ||
                name == "patches" ||
                name == "blutter_tmp" ||
                isEngineTemp ||
                name == "address_mappings" ||
                isAnalysisDb
            if (shouldDelete) {
                freed += f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                f.deleteRecursively()
            }
        }

        // ---------- 2. filesDir：undo（BackupManager）/ mcp_patches（McpPatchService） ----------
        val files = context.filesDir
        files.listFiles()?.forEach { f ->
            val name = f.name
            if (name == "undo" || name == "mcp_patches") {
                freed += f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                f.deleteRecursively()
            }
        }

        // ---------- 3. 内存层：清空 @Singleton 态 ----------
        soEditorCache.clearAll()
        analysisSession.closeAll()
        backupManager.clearAllInMemory()

        freed
    }

    // ========== 私有工具 ==========

    /**
     * 下载 → 7z 头校验 → SHA256 → 增量解压 指定资产到引擎目录。
     * 失败自动重试（最多 [MAX_DOWNLOAD_ATTEMPTS] 次），完成后删除临时归档。
     */
    private suspend fun installAsset(
        scope: ProducerScope<EngineProgress>,
        displayName: String,
        url: String,
        sha256: String,
        archiveFileName: String,
    ) {
        val archiveFile = File(context.cacheDir, archiveFileName)
        var attempt = 0
        while (true) {
            attempt++
            Log.i(TAG, "开始下载 $displayName (第 $attempt/${MAX_DOWNLOAD_ATTEMPTS} 次尝试), 源: ${downloader.sourceDescription()}")
            appLogger.info(TAG, "开始下载 $displayName")
            emitProgress(scope, EngineProgress(EngineProgress.Phase.DOWNLOADING))

            try {
                downloader.downloadAsset(url, archiveFile) { downloaded, total, speed ->
                    val p = EngineProgress(
                        phase = EngineProgress.Phase.DOWNLOADING,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        speed = speed,
                    )
                    _progress.value = p
                    scope.trySend(p)
                }
                // 下载完成但已被请求取消 → 中止
                if (cancelRequested) {
                    Log.i(TAG, "下载被取消: $displayName")
                    throw kotlinx.coroutines.CancellationException("下载已取消")
                }
                Log.i(TAG, "下载完成: $displayName, 大小 ${archiveFile.length()} bytes, 路径: ${archiveFile.absolutePath}")

                emitProgress(scope, EngineProgress(EngineProgress.Phase.VERIFYING))
                if (!isValid7zFile(archiveFile)) {
                    Log.e(TAG, "文件头不是有效 7z 归档: ${archiveFile.absolutePath}")
                    throw IllegalStateException("下载的文件不是有效的 7z 归档，可能下载不完整")
                }
                if (sha256.isNotBlank()) {
                    val isValid = extractor.verifyChecksum(archiveFile, sha256)
                    if (!isValid) {
                        val actual = extractor.computeSha256(archiveFile)
                        Log.e(TAG, "SHA256 校验失败: 期望 ${sha256.take(16)}..., 实际 ${actual.take(16)}...")
                        throw IllegalStateException(
                            "SHA256 校验失败（若在设置中自定义过下载源，请重置为默认）"
                        )
                    }
                    Log.i(TAG, "SHA256 校验通过: $displayName")
                } else {
                    Log.w(TAG, "manifest 未提供 $displayName 的 sha256，跳过校验")
                }
                break
            } catch (e: kotlinx.coroutines.CancellationException) {
                archiveFile.delete()
                throw e
            } catch (e: Exception) {
                archiveFile.delete()
                // 用户取消下载（okhttp 中断抛 IOException 等）→ 统一转 CancellationException 供上层识别
                if (cancelRequested) {
                    throw kotlinx.coroutines.CancellationException("下载已取消")
                }
                if (attempt >= MAX_DOWNLOAD_ATTEMPTS) {
                    throw e
                }
                Log.w(TAG, "第 $attempt 次尝试失败: ${e.message}, 将重试", e)
                continue
            }
        }

        emitProgress(scope, EngineProgress(EngineProgress.Phase.EXTRACTING))
        extractor.extractIncremental(archiveFile, engineDir) { progress ->
            val p = EngineProgress(
                phase = EngineProgress.Phase.EXTRACTING,
                extractProgress = progress,
            )
            _progress.value = p
            scope.trySend(p)
        }

        // 清理临时文件
        archiveFile.delete()
    }

    /** 设置 _progress 状态并推送给 flow 订阅者。 */
    private fun emitProgress(scope: ProducerScope<EngineProgress>, p: EngineProgress) {
        _progress.value = p
        scope.trySend(p)
    }

    /**
     * 验证文件是否为有效的 7z 归档（检查魔术字节）。
     *
     * 7z 文件头前 6 字节: 37 7A BC AF 27 1C
     */
    private fun isValid7zFile(file: File): Boolean {
        if (!file.exists() || file.length() < 6) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(6)
                val read = input.read(header)
                if (read < 6) return@use false
                // 7z magic: 37 7A BC AF 27 1C
                header[0] == 0x37.toByte() &&
                    header[1] == 0x7A.toByte() &&
                    header[2] == 0xBC.toByte() &&
                    header[3] == 0xAF.toByte() &&
                    header[4] == 0x27.toByte() &&
                    header[5] == 0x1C.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }
}

/** 引擎更新信息（预留）。 */
data class EngineUpdate(
    val version: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val releaseNotes: String?,
)
