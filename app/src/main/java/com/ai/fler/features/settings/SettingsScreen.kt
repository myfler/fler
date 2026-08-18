package com.ai.fler.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.core.service.EnginePackManager
import com.ai.fler.feature.settings.FridaStatusUiState
import com.ai.fler.feature.settings.McpUiState
import com.ai.fler.feature.settings.SettingsViewModel
import com.ai.fler.features.engine.EngineViewModel
import com.ai.fler.ui.components.CardListTile

/**
 * 设置 Tab。
 *
 * 集成引擎包管理、版本更新检测、下载源配置、MCP 服务器、缓存清理与关于等入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenMcpSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenHookScripts: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    engineViewModel: EngineViewModel = hiltViewModel(),
) {
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val installedVersions by viewModel.installedVersions.collectAsStateWithLifecycle()
    val sourceState by viewModel.sourceState.collectAsStateWithLifecycle()
    val cacheCleanResult by viewModel.cacheCleanResult.collectAsStateWithLifecycle()
    val mcpState by viewModel.mcpState.collectAsStateWithLifecycle()
    val fridaStatus by viewModel.fridaStatus.collectAsStateWithLifecycle()
    val keepAliveState by viewModel.keepAliveState.collectAsStateWithLifecycle()
    val engineState by engineViewModel.uiState.collectAsStateWithLifecycle()
    val workDirTreeUri by viewModel.workDirTreeUri.collectAsStateWithLifecycle()
    var showCacheCleanConfirm by remember { mutableStateOf(false) }

    // 后台保活状态刷新：每次屏幕回到前台（含从电池优化设置页返回）都重读，
    // 保证「去开启」后的豁免状态实时更新
    LifecycleResumeEffect(Unit) {
        viewModel.refreshKeepAliveStatus()
        onPauseOrDispose { }
    }

    // Android 13+ 通知权限（前台服务通知需要）
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* 授权结果无需处理，服务通知可正常展示 */ }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = context.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // 工作目录选择器（SAF tree，持久化授权，写回 ViewModel）
    val workDirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            viewModel.setWorkDirTreeUri(uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设置") })
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 引擎包 + 引擎版本（合并为一个引擎卡片）
            item {
                EngineVersionCard(
                    state = updateState,
                    installedVersions = installedVersions,
                    engineState = engineState,
                    onRefreshManifest = { engineViewModel.loadManifest() },
                    onCheckForUpdates = { viewModel.checkForUpdates() },
                    onClearEngines = { viewModel.clearEngines() },
                    onInstallRuntime = { engineViewModel.installRuntimeLibs() },
                    onInstallSelected = { engineViewModel.installSelectedVersion() },
                    onSelectVersion = { engineViewModel.selectVersion(it) },
                    onCancelDownload = { engineViewModel.cancelDownload() },
                    onDownloadUpdate = { engineViewModel.installRuntimeLibs(force = true) },
                    onDownloadAll = { engineViewModel.downloadAllEngines() },
                )
            }

        // 下载源配置
        item {
            EngineSourceCard(
                state = sourceState,
                onSave = { manifestUrl, proxy ->
                    viewModel.saveSourceConfig(manifestUrl, proxy)
                },
                onReset = { viewModel.resetSourceConfig() }
            )
        }

        // MCP 服务器（紧凑入口，详细配置在二级 Screen）
        item {
            McpEntryCard(
                state = mcpState,
                onClick = onOpenMcpSettings
            )
        }

        // 工作目录（App 级一级设置项：产物导出 / MCP /export 下载根）
        item {
            WorkDirectoryCard(
                treeUri = workDirTreeUri,
                displayName = viewModel.workDirDisplayName,
                onPick = { workDirPicker.launch(null) },
                onClear = { viewModel.clearWorkDir() },
            )
        }

        // Hook 脚本管理（Frida 落地脚本增删改查）
        item {
            HookScriptsEntryCard(onClick = onOpenHookScripts)
        }

        // Frida 动态插桩状态（客户端/root/server 探测）
        item {
            FridaStatusCard(
                state = fridaStatus,
                onProbe = { viewModel.refreshFridaStatus(ensureReady = false) },
                onPrepare = { viewModel.refreshFridaStatus(ensureReady = true) },
            )
        }

        // 项目缓存清理
        item {
            CacheCleanCard(
                onClean = { showCacheCleanConfirm = true },
                result = cacheCleanResult,
                onClearResult = { viewModel.clearCacheCleanResult() }
            )
        }

        // 后台保活（电池优化豁免引导 + 悬浮窗保活）
        item {
            KeepAliveCard(
                state = keepAliveState,
                onToggleOverlay = { viewModel.setOverlayKeepAlive(it) },
            )
        }

        // 关于
        item {
            AboutCard(onClick = onOpenAbout)
        }
        }
    }

    if (showCacheCleanConfirm) {
        AlertDialog(
            onDismissRequest = { showCacheCleanConfirm = false },
            title = { Text("清理项目缓存") },
            text = {
                Text("将删除 APK/SO 导入副本、提取产物、分析数据库（analysis_*.db）、残留引擎包文件与补丁导出文件。引擎文件与已导入的分析记录不受影响。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showCacheCleanConfirm = false
                    viewModel.cleanProjectCache()
                }) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { showCacheCleanConfirm = false }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngineVersionCard(
    state: com.ai.fler.feature.settings.UpdateCheckState,
    installedVersions: List<String>,
    engineState: com.ai.fler.features.engine.EngineViewModel.EngineUiState,
    onRefreshManifest: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onClearEngines: () -> Unit,
    onInstallRuntime: () -> Unit,
    onInstallSelected: () -> Unit,
    onSelectVersion: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onDownloadAll: () -> Unit,
) {
    var showUpdateConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "引擎包",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                engineState.manifest?.packVersion?.let { packVersion ->
                    val hasUpdate = engineState.installedPackVersion != null &&
                        engineState.installedPackVersion != packVersion
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (hasUpdate) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (hasUpdate) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (hasUpdate) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = packVersion,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (hasUpdate) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            if (hasUpdate) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.NewReleases,
                                    contentDescription = "有新版可用",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                if (engineState.loadingManifest) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = onRefreshManifest) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新引擎清单"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 状态图标 + 已安装版本号（芯片装饰）
            if (installedVersions.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "引擎就绪",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "引擎就绪",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    installedVersions.forEach { version ->
                        val isUpdateable = version in engineState.updateableVersions
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isUpdateable) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                            border = if (isUpdateable) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                null
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    imageVector = if (isUpdateable) {
                                        Icons.Default.NewReleases
                                    } else {
                                        Icons.Default.Check
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = if (isUpdateable) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    }
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (isUpdateable) "Dart $version · 可更新" else "Dart $version",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isUpdateable) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "引擎未就绪",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "引擎未就绪，需下载引擎包",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 必装运行库状态行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "运行库（必装）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (engineState.isRuntimeReady) "已安装 libc++_shared.so" else "未安装",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (engineState.isRuntimeReady) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                if (!engineState.isRuntimeReady) {
                    OutlinedButton(
                        onClick = onInstallRuntime,
                        enabled = !engineState.isDownloading && !engineState.isDownloadingAll,
                    ) {
                        Text("安装运行库")
                    }
                }
            }

            // 自定义下载源提示
            if (engineState.isCustomSource) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "⚠️ 当前使用自定义下载源，可能不是最新版本",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // 远程版本下拉
            val manifest = engineState.manifest
            if (manifest != null && manifest.engines.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                EngineVersionDropdown(
                    engines = manifest.engines,
                    installedVersions = installedVersions,
                    updateableVersions = engineState.updateableVersions,
                    selectedVersion = engineState.selectedVersion,
                    enabled = !engineState.isDownloading && !engineState.isDownloadingAll,
                    onSelect = onSelectVersion,
                )

                val selectedInstalled = engineState.selectedVersion?.let { installedVersions.contains(it) } == true
                val selectedUpdateable = engineState.selectedVersion?.let {
                    it in engineState.updateableVersions
                } == true
                val canInstall = engineState.selectedVersion != null &&
                    (!selectedInstalled || selectedUpdateable)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (engineState.isDownloading) {
                            onCancelDownload()
                        } else if (selectedUpdateable) {
                            showUpdateConfirm = true
                        } else {
                            onInstallSelected()
                        }
                    },
                    enabled = (canInstall && !engineState.isDownloading && !engineState.isDownloadingAll) ||
                        engineState.isDownloading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            engineState.isDownloading -> "取消下载"
                            engineState.isDownloadingAll -> "正在批量下载..."
                            selectedUpdateable -> "更新 Dart ${engineState.selectedVersion}"
                            selectedInstalled -> "已安装 Dart ${engineState.selectedVersion}"
                            engineState.selectedVersion != null -> "下载 Dart ${engineState.selectedVersion}"
                            else -> "请选择版本"
                        }
                    )
                }

                // 一键下载全部（存在未安装版本或可更新版本时可用）
                val pendingCount = manifest.engines.count {
                    it.dartVersion !in installedVersions || it.dartVersion in engineState.updateableVersions
                }
                if (pendingCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (engineState.isDownloadingAll) {
                                onCancelDownload()
                            } else {
                                onDownloadAll()
                            }
                        },
                        enabled = (!engineState.isDownloading && !engineState.isDownloadingAll) ||
                            engineState.isDownloadingAll,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = if (engineState.isDownloadingAll) {
                                Icons.Default.Close
                            } else {
                                Icons.Default.Download
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            when {
                                engineState.isDownloadingAll ->
                                    "取消下载全部"
                                else -> "下载全部引擎（缺 $pendingCount 个）"
                            }
                        )
                    }
                }
            }

            // manifest 加载失败提示
            engineState.manifestError?.let { error ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "❌ 清单获取失败: $error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // 下载进度
            engineState.progress?.let { progress ->
                if (progress.phase != EnginePackManager.EngineProgress.Phase.IDLE &&
                    progress.phase != EnginePackManager.EngineProgress.Phase.COMPLETED
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = phaseLabel(progress.phase),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            LinearProgressIndicator(
                                progress = { progress.overallProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "%.0f%%".format(progress.overallProgress * 100),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = progressDetail(progress),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 错误提示
            engineState.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "❌ $error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // 清除引擎按钮
            if (installedVersions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onClearEngines,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("清除引擎")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when {
                state.isChecking -> {
                    Text(
                        text = "正在检查更新...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state.errorMessage != null -> {
                    Text(
                        text = "检查失败: ${state.errorMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                state.hasUpdate && state.update != null -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Update,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "发现新版本: ${state.update!!.version}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (!state.update!!.releaseNotes.isNullOrBlank()) {
                        Text(
                            text = state.update!!.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = onDownloadUpdate,
                        enabled = !engineState.isDownloading && !engineState.isDownloadingAll,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("下载更新运行库")
                    }
                }

                !state.hasUpdate && state.lastChecked > 0 -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "已是最新版本",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                else -> {
                    OutlinedButton(
                        onClick = onCheckForUpdates,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("检查更新")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 说明
            Text(
                text = "引擎包由「必装运行库 + 各 Dart 版本引擎」组成，按需下载。" +
                        "运行库仅需安装一次；引擎按分析需要的 Dart 版本逐个下载。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 更新确认对话框
    if (showUpdateConfirm) {
        AlertDialog(
            onDismissRequest = { showUpdateConfirm = false },
            title = { Text("更新引擎") },
            text = {
                Text("将重新下载 Dart ${engineState.selectedVersion} 并覆盖安装。确定继续？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUpdateConfirm = false
                        onInstallSelected()
                    }
                ) {
                    Text("更新", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineVersionDropdown(
    engines: List<com.ai.fler.core.service.EngineEntry>,
    installedVersions: List<String>,
    updateableVersions: Set<String>,
    selectedVersion: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedInstalled = selectedVersion?.let { installedVersions.contains(it) } == true
    val selectedUpdateable = selectedVersion?.let { it in updateableVersions } == true
    val selectedEntry = engines.firstOrNull { it.dartVersion == selectedVersion }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = selectedVersion?.let { "Dart $it" } ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Dart 版本") },
            placeholder = { Text("选择要下载的版本") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            engines.forEach { entry ->
                val isInstalled = installedVersions.contains(entry.dartVersion)
                val isUpdateable = entry.dartVersion in updateableVersions
                val isSelected = entry.dartVersion == selectedVersion
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Dart ${entry.dartVersion}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            when {
                                isUpdateable -> {
                                    Icon(
                                        imageVector = Icons.Default.NewReleases,
                                        contentDescription = "可更新",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "可更新",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                isInstalled -> {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "已安装",
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "已安装",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }

                                else -> {
                                    val mb = entry.sizeBytes / (1024.0 * 1024.0)
                                    Text(
                                        text = "%.1f MB".format(mb),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    },
                    onClick = {
                        onSelect(entry.dartVersion)
                        expanded = false
                    }
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = when {
                selectedUpdateable -> "Dart $selectedVersion 有新版可更新，点击「更新」覆盖安装"
                selectedInstalled -> "Dart $selectedVersion 已安装，无需下载"
                selectedEntry != null -> "Dart $selectedVersion 尚未安装，可下载"
                else -> "选择版本后即可下载"
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                selectedUpdateable -> MaterialTheme.colorScheme.primary
                selectedInstalled -> MaterialTheme.colorScheme.tertiary
                selectedEntry != null -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private fun phaseLabel(phase: com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase): String = when (phase) {
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.DOWNLOADING -> "下载中"
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.VERIFYING -> "校验中"
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.EXTRACTING -> "解压中"
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.LOADING -> "加载中"
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.COMPLETED -> "完成"
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.CANCELLED -> "已取消"
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.FAILED -> "失败"
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.IDLE -> "等待中"
}

private fun progressDetail(progress: com.ai.fler.core.service.EnginePackManager.EngineProgress): String = when (progress.phase) {
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.DOWNLOADING -> {
        // 批量下载（一键下载全部）优先展示版本进度文案
        if (progress.batchLabel.isNotBlank()) {
            progress.batchLabel
        } else {
            val mb = progress.downloadedBytes / (1024.0 * 1024.0)
            val totalMb = progress.totalBytes / (1024.0 * 1024.0)
            if (progress.totalBytes > 0) {
                "%.1f / %.1f MB · %s".format(mb, totalMb, progress.speed)
            } else {
                "%.1f MB · %s".format(mb, progress.speed)
            }
        }
    }
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.EXTRACTING -> "%.0f%%".format(progress.extractProgress * 100)
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.VERIFYING -> "SHA256 校验中..."
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.FAILED -> progress.errorMessage ?: "未知错误"
    else -> ""
}

@Composable
private fun EngineSourceCard(
    state: com.ai.fler.feature.settings.EngineSourceState,
    onSave: (String, String) -> Unit,
    onReset: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var manifestUrl by remember(state) { mutableStateOf(state.manifestUrl) }
    var githubProxy by remember(state) { mutableStateOf(state.githubProxy) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "下载源配置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (state.isCustom) {
                    Text(
                        text = "已自定义",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isEditing) {
                // 编辑模式
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manifestUrl,
                        onValueChange = { manifestUrl = it },
                        label = { Text("引擎清单地址 (manifest.json)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = githubProxy,
                        onValueChange = { githubProxy = it },
                        label = { Text("GitHub 加速前缀") },
                        placeholder = { Text("如 https://gh-proxy.com（留空关闭）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onReset() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("重置默认")
                        }
                        Button(
                            onClick = {
                                onSave(manifestUrl, githubProxy)
                                isEditing = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("保存")
                        }
                    }
                }
            } else {
                // 展示模式
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceItem(label = "引擎清单", url = state.manifestUrl)
                    SourceItem(
                        label = "GitHub 加速",
                        url = if (state.githubProxy.isBlank()) "未启用" else state.githubProxy
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { isEditing = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("编辑地址")
                }
            }
        }
    }
}

@Composable
private fun SourceItem(label: String, url: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun McpEntryCard(
    state: McpUiState,
    onClick: () -> Unit
) {
    val statusText = if (state.isRunning) "运行中" else "已停止"
    val urlText = if (state.isRunning) {
        (state.localUrl.ifBlank { state.lanUrl }).let {
            if (it.isNotBlank()) "本机 $it" else statusText
        }
    } else {
        "点击进入详细配置"
    }
    CardListTile(
        title = "MCP 服务器 · $statusText",
        subtitle = urlText,
        leadingIcon = Icons.Outlined.Storage,
        onClick = onClick,
    )
}

@Composable
private fun WorkDirectoryCard(
    treeUri: String,
    displayName: String?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    val isSet = treeUri.isNotBlank()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "工作目录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "补丁后的 so / .patch 等产物导出位置，也是 MCP 服务器 /export 的下载根。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isSet) {
                    "当前: ${displayName ?: treeUri}"
                } else {
                    "未设置（默认 App 缓存 cacheDir/so_export）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (isSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isSet) "更换" else "选择")
                }
                if (isSet) {
                    OutlinedButton(
                        onClick = onClear,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("清除")
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutCard(
    onClick: () -> Unit
) {
    CardListTile(
        title = "关于",
        subtitle = "Fler ${com.ai.fler.BuildConfig.VERSION_NAME} · 开源项目与第三方库",
        onClick = onClick,
    )
}

@Composable
private fun HookScriptsEntryCard(onClick: () -> Unit) {
    CardListTile(
        title = "Hook 脚本",
        subtitle = "Frida JS 落地管理：内置预设与自定义脚本增删改查",
        leadingIcon = Icons.Outlined.Code,
        onClick = onClick,
    )
}

@Composable
private fun FridaStatusCard(
    state: FridaStatusUiState,
    onProbe: () -> Unit,
    onPrepare: () -> Unit,
) {
    val statusColor = when {
        state.errorMessage != null -> MaterialTheme.colorScheme.error
        state.available && state.serverRunning -> MaterialTheme.colorScheme.tertiary
        state.available -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when {
        state.loading -> "探测中..."
        state.errorMessage != null -> "探测失败：${state.errorMessage}"
        state.available && state.serverRunning && state.initialized -> "就绪（frida ${state.version}）"
        state.available && state.serverRunning -> "server 已运行，客户端未初始化"
        state.available -> "客户端可用，需部署/启动 server"
        else -> "frida-core 未启用（编译禁用或库缺失）"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Frida 动态插桩",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "root:${if (state.root) "✓" else "✗"} · server:${if (state.serverRunning) "✓" else "✗"} · client:${if (state.available) "✓" else "✗"} · initialized:${if (state.initialized) "✓" else "✗"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onProbe,
                    enabled = !state.loading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("状态探测")
                }
                Button(
                    onClick = onPrepare,
                    enabled = !state.loading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("就绪检查")
                }
            }
        }
    }
}

@Composable
private fun CacheCleanCard(
    onClean: () -> Unit,
    result: Long?,
    onClearResult: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "项目缓存",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "APK/SO 导入副本、提取产物、分析数据库（analysis_*.db）、残留引擎包文件与补丁导出文件。引擎文件与已导入的分析记录不受影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onClean) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("清理项目缓存")
                }
                if (result != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    val sizeStr = if (result >= 1024 * 1024) "${result / 1024 / 1024} MB"
                                  else if (result >= 1024) "${result / 1024} KB"
                                  else "$result B"
                    Text(
                        text = "已释放 $sizeStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = onClearResult, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun KeepAliveCard(
    state: com.ai.fler.feature.settings.KeepAliveUiState,
    onToggleOverlay: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    // 悬浮窗权限授权（SYSTEM_ALERT_WINDOW）
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // 返回时由 LifecycleResumeEffect 自动刷新权限/开关状态
    }

    fun launchBatteryOptimizationSettings() {
        // 1) 非 MIUI：优先系统请求对话框
        val isMiui = (android.os.Build.MANUFACTURER.equals("Xiaomi", true) ||
            android.os.Build.BRAND.equals("Xiaomi", true))
        if (!isMiui) {
            val requestIntent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${context.packageName}")
            )
            if (requestIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(requestIntent)
                return
            }
        }
        // 2) MIUI：优先打开 MIUI 电池优化专属页（省电与电池 → 应用智能省电）
        val miuiIntent = android.content.Intent().apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.powercenter.MiuiPowerSave"
            )
        }
        if (isMiui && miuiIntent.resolveActivity(context.packageManager) != null) {
            val launched = runCatching {
                context.startActivity(miuiIntent)
                true
            }.getOrDefault(false)
            if (launched) return
        }
        // 3) 兜底：通用电池优化设置列表
        val fallback = android.content.Intent(
            android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
        )
        if (fallback.resolveActivity(context.packageManager) != null) {
            context.startActivity(fallback)
            return
        }
        android.widget.Toast.makeText(
            context,
            "无法打开电池优化设置，请手动到系统设置关闭",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "后台保活",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "分析任务通过前台服务 + WakeLock 在后台持续运行。MIUI 等厂商系统仍可能限制后台进程，建议关闭本 App 的电池优化。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            // ---- 电池优化豁免 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (state.isIgnoringBatteryOptimizations) "电池优化：已豁免" else "电池优化：未豁免",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.isIgnoringBatteryOptimizations) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                if (!state.isIgnoringBatteryOptimizations) {
                    Button(onClick = ::launchBatteryOptimizationSettings) {
                        Text("去开启")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            // ---- 悬浮窗保活 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "悬浮窗保活",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when {
                            state.overlayRunning -> "运行中：桌面显示可拖动悬浮球，防止后台进程被回收"
                            !state.canDrawOverlay -> "需授予悬浮窗权限后开启"
                            else -> "在桌面显示小型悬浮球，提升后台存活率"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when {
                    !state.canDrawOverlay -> OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }.onFailure {
                                android.widget.Toast.makeText(
                                    context,
                                    "无法打开悬浮窗授权页",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) {
                        Text("去授权")
                    }
                    state.overlayRunning -> OutlinedButton(
                        onClick = { onToggleOverlay(false) }
                    ) {
                        Text("关闭")
                    }
                    else -> Button(
                        onClick = { onToggleOverlay(true) }
                    ) {
                        Text("开启")
                    }
                }
            }
        }
    }
}
