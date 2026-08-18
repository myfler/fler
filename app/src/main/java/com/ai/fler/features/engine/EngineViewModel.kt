package com.ai.fler.features.engine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.core.service.EngineManifest
import com.ai.fler.core.service.EnginePackManager
import com.ai.fler.core.service.EngineSourceConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 引擎下载/管理 ViewModel（v0.4.0 按版本按需下载）。
 *
 * 负责：
 * 1. 拉取远程 manifest（设置页下拉数据源）
 * 2. 按需安装单个 Dart 版本引擎（先补必装运行库）
 * 3. 安装/更新必装运行库
 * 4. 监听下载进度并更新 UI
 */
@HiltViewModel
class EngineViewModel @Inject constructor(
    private val enginePackManager: EnginePackManager,
    private val sourceConfig: EngineSourceConfig,
) : ViewModel() {

    data class EngineUiState(
        val isReady: Boolean = false,
        val isRuntimeReady: Boolean = false,
        val installedPackVersion: String? = null,
        val updateableVersions: Set<String> = emptySet(),
        val progress: EnginePackManager.EngineProgress? = null,
        val isDownloading: Boolean = false,
        val isDownloadingAll: Boolean = false,
        val errorMessage: String? = null,
        val isCustomSource: Boolean = false,
        val manifest: EngineManifest? = null,
        val loadingManifest: Boolean = false,
        val manifestError: String? = null,
        val selectedVersion: String? = null,
    )

    private val _uiState = MutableStateFlow(EngineUiState())
    val uiState: StateFlow<EngineUiState> = _uiState.asStateFlow()

    /** 当前进行中的下载 Job（取消下载用）。 */
    private var downloadJob: Job? = null

    init {
        checkEngineStatus()
        loadManifest()
        // 引擎版本变化（下载完成/清除）时实时刷新就绪状态与可更新列表
        viewModelScope.launch {
            enginePackManager.versionsEpoch.collect {
                val current = _uiState.value
                val manifestPack = current.manifest?.packVersion
                _uiState.update {
                    it.copy(
                        isReady = enginePackManager.isEnginePackReady(),
                        isRuntimeReady = enginePackManager.isRuntimeReady(),
                        installedPackVersion = enginePackManager.currentInstalledPackVersion(),
                        updateableVersions = if (manifestPack != null) {
                            enginePackManager.listUpdateableVersions(manifestPack).toSet()
                        } else {
                            emptySet()
                        },
                    )
                }
            }
        }
    }

    /**
     * 检查引擎包就绪状态。
     */
    fun checkEngineStatus() {
        _uiState.update {
            it.copy(
                isReady = enginePackManager.isEnginePackReady(),
                isRuntimeReady = enginePackManager.isRuntimeReady(),
                isCustomSource = sourceConfig.isCustom(),
            )
        }
    }

    /**
     * 拉取远程 manifest（填充设置页版本下拉）。失败时保留旧数据并置 manifestError。
     */
    fun loadManifest() {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingManifest = true, manifestError = null) }
            try {
                val manifest = enginePackManager.fetchManifest()
                val updateable = if (manifest != null) {
                    enginePackManager.listUpdateableVersions(manifest.packVersion).toSet()
                } else {
                    emptySet()
                }
                _uiState.update {
                    it.copy(
                        manifest = manifest,
                        loadingManifest = false,
                        manifestError = if (manifest == null) "无法获取远程引擎清单" else null,
                        updateableVersions = updateable,
                        selectedVersion = it.selectedVersion
                            ?: manifest?.engines?.firstOrNull()?.dartVersion,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loadingManifest = false,
                        manifestError = e.message ?: "无法获取远程引擎清单",
                    )
                }
            }
        }
    }

    /** 选中下拉中的某个远程版本。 */
    fun selectVersion(version: String) {
        _uiState.update { it.copy(selectedVersion = version) }
    }

    /** 安装下拉当前选中的版本（先自动补装运行库）。 */
    fun installSelectedVersion() {
        val version = _uiState.value.selectedVersion ?: return
        installEngine(version)
    }

    /** 安装指定 Dart 版本引擎。 */
    fun installEngine(version: String) {
        if (_uiState.value.isDownloading || _uiState.value.isDownloadingAll) return
        _uiState.update { it.copy(isDownloading = true, errorMessage = null) }
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                enginePackManager.installEngineVersion(version).collectLatest { progress ->
                    applyProgress(progress)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 主动取消：状态已在 cancelDownload 中复位
            }
        }
    }

    /** 安装（或更新）必装运行库。 */
    fun installRuntimeLibs(force: Boolean = false) {
        if (_uiState.value.isDownloading || _uiState.value.isDownloadingAll) return
        _uiState.update { it.copy(isDownloading = true, errorMessage = null) }
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                enginePackManager.installRuntimeLibs(force).collectLatest { progress ->
                    applyProgress(progress)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 主动取消：状态已在 cancelDownload 中复位
            }
        }
    }

    /**
     * 一键下载全部引擎（逐个串行，由 [EnginePackManager.installAllEngines] 执行）。
     * 下载期间禁用单个安装与下拉。
     */
    fun downloadAllEngines() {
        if (_uiState.value.isDownloading || _uiState.value.isDownloadingAll) return
        _uiState.update { it.copy(isDownloadingAll = true, errorMessage = null) }
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                enginePackManager.installAllEngines().collectLatest { progress ->
                    applyProgress(progress)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 主动取消：状态已在 cancelDownload 中复位
            }
        }
    }

    /**
     * 取消当前下载/安装流程（单个引擎或全部引擎）。
     */
    fun cancelDownload() {
        enginePackManager.cancelDownloads()
        downloadJob?.cancel()
        downloadJob = null
        _uiState.update {
            it.copy(
                isDownloading = false,
                isDownloadingAll = false,
                progress = null,
                errorMessage = "下载已取消",
            )
        }
    }

    private fun applyProgress(progress: EnginePackManager.EngineProgress) {
        val isBusy = progress.phase == EnginePackManager.EngineProgress.Phase.DOWNLOADING ||
            progress.phase == EnginePackManager.EngineProgress.Phase.EXTRACTING ||
            progress.phase == EnginePackManager.EngineProgress.Phase.VERIFYING ||
            progress.phase == EnginePackManager.EngineProgress.Phase.LOADING
        _uiState.update {
            it.copy(
                progress = progress,
                isDownloading = isBusy && !it.isDownloadingAll,
                isDownloadingAll = it.isDownloadingAll && isBusy,
            )
        }

        when (progress.phase) {
            EnginePackManager.EngineProgress.Phase.COMPLETED -> {
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        isDownloadingAll = false,
                        isReady = enginePackManager.isEnginePackReady(),
                        isRuntimeReady = enginePackManager.isRuntimeReady(),
                    )
                }
            }
            EnginePackManager.EngineProgress.Phase.CANCELLED -> {
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        isDownloadingAll = false,
                        progress = null,
                        errorMessage = progress.errorMessage,
                    )
                }
            }
            EnginePackManager.EngineProgress.Phase.FAILED -> {
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        isDownloadingAll = false,
                        errorMessage = progress.errorMessage,
                    )
                }
            }
            else -> { /* 继续 */ }
        }
    }
}
