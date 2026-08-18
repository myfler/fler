package com.ai.fler.core.service

import android.util.Log
import com.ai.fler.core.log.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 双源下载器：按配置依次尝试主源与备用源（代理前缀 + 原始地址）。
 *
 * 任一源下载失败时自动切换下一个源。引擎资产协议 v0.4.0：
 * - 下载 URL / sha256 全部来自远程 manifest.json（[fetchManifest]）；
 * - 每个资产（运行库 / 单版本引擎）通过 [downloadAsset] 按 URL 独立下载。
 */
@Singleton
class DualSourceDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val sourceConfig: EngineSourceConfig,
    private val appLogger: AppLogger,
) {
    companion object {
        private const val TAG = "FlerEngine"
    }

    /** 当前活动下载 Call（取消下载用）。 */
    @Volatile
    private var activeCall: Call? = null

    /**
     * 取消当前下载（中断 okhttp Call 的阻塞 IO）。
     */
    fun cancel() {
        activeCall?.cancel()
    }

    /**
     * 下载单个引擎资产（运行库包或单版本引擎包）到目标文件。
     *
     * 代理开启时：候选 = 代理前缀 URL + 原始 GitHub 地址；否则仅该 URL。
     *
     * @param url 资产下载地址（来自 manifest）
     * @param target 目标文件路径
     * @param onProgress 进度回调：(已下载字节, 总字节, 速度字符串)
     * @throws IllegalStateException 所有源均下载失败
     */
    suspend fun downloadAsset(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long, speed: String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val resolved = resolveUrl(url)
        val candidates = if (resolved != url) listOf(resolved, url) else listOf(url)
        var lastException: Exception? = null

        for (cand in candidates) {
            try {
                Log.i(TAG, "尝试下载源: $cand")
                appLogger.info(TAG, "尝试下载源: $cand")
                downloadFromSource(cand, target, onProgress)
                Log.i(TAG, "下载源成功: $cand")
                appLogger.info(TAG, "下载源成功: $cand")
                return@withContext target
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户取消下载 → 不切换下一候选源，直接终止
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "下载源失败: $cand, 原因: ${e.message}", e)
                appLogger.error(TAG, "下载源失败: $cand, 原因: ${e.message}")
                lastException = e
                target.delete()
            }
        }

        throw IllegalStateException("下载引擎资产失败，所有源均不可用", lastException)
    }

    /**
     * 获取远程引擎清单 manifest.json。
     *
     * 格式：
     * ```json
     * {"packVersion":"v0.4.0","releaseNotes":"...",
     *  "runtimeLibs":{"file":"fler-runtime-libs.7z","url":"...","sha256":"...","sizeBytes":123},
     *  "engines":[{"dartVersion":"3.12.2","file":"dartvm-3.12.2.7z","url":"...","sha256":"...","sizeBytes":456}]}
     * ```
     */
    suspend fun fetchManifest(): EngineManifest? = withContext(Dispatchers.IO) {
        val url = sourceConfig.manifestUrl
        if (url.isBlank()) {
            Log.i(TAG, "未配置 manifest 地址，跳过")
            return@withContext null
        }
        val resolved = resolveUrl(url)
        fetchManifestFrom(resolved) ?: if (resolved != url) fetchManifestFrom(url) else null
    }

    private fun fetchManifestFrom(url: String): EngineManifest? {
        return try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = response.body?.string() ?: return null
                    parseManifest(json)
                } else {
                    Log.w(TAG, "manifest 请求失败: HTTP ${response.code}, url=$url")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取 manifest 异常: ${e.message}", e)
            null
        }
    }

    private fun parseManifest(json: String): EngineManifest? {
        return try {
            val root = JSONObject(json)
            val runtimeObj = root.optJSONObject("runtimeLibs")
            val enginesJson = root.optJSONArray("engines") ?: org.json.JSONArray()
            val engines = buildList {
                for (i in 0 until enginesJson.length()) {
                    val e = enginesJson.getJSONObject(i)
                    add(
                        EngineEntry(
                            dartVersion = e.getString("dartVersion"),
                            file = e.getString("file"),
                            url = e.getString("url"),
                            sha256 = e.getString("sha256"),
                            sizeBytes = e.optLong("sizeBytes", 0L),
                        )
                    )
                }
            }
            EngineManifest(
                packVersion = root.getString("packVersion"),
                releaseNotes = root.optString("releaseNotes").takeIf { it.isNotEmpty() },
                runtimeLibs = runtimeObj?.let {
                    RuntimeLibsEntry(
                        file = it.getString("file"),
                        url = it.getString("url"),
                        sha256 = it.getString("sha256"),
                        sizeBytes = it.optLong("sizeBytes", 0L),
                    )
                },
                engines = engines,
            )
        } catch (e: Exception) {
            Log.w(TAG, "manifest 解析失败: ${e.message}")
            null
        }
    }

    /**
     * 当前生效的下载源描述（用于日志诊断）。
     */
    fun sourceDescription(): String {
        return "manifest=${sourceConfig.manifestUrl}"
    }

    private fun downloadFromSource(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long, speed: String) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Fler/1.0")
            .build()

        val call = okHttpClient.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw HttpException(response.code, "HTTP ${response.code}")
                }

                val body = response.body ?: throw IllegalStateException("空响应体")
                val totalBytes = body.contentLength()
                Log.i(TAG, "HTTP ${response.code}, Content-Length: $totalBytes bytes, url=$url")
                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(target)

                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = 0L
                var lastTime = System.currentTimeMillis()
                var lastDownloaded = 0L

                inputStream.use { input ->
                    outputStream.use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            // 每 500ms 报告一次进度
                            val now = System.currentTimeMillis()
                            if (now - lastTime >= 500) {
                                val elapsed = (now - lastTime) / 1000.0
                                val delta = downloaded - lastDownloaded
                                val speed = if (elapsed > 0) {
                                    formatSpeed(delta / elapsed)
                                } else "-- KB/s"
                                onProgress(downloaded, totalBytes, speed)
                                lastTime = now
                                lastDownloaded = downloaded
                            }
                        }
                    }
                }

                onProgress(downloaded, totalBytes, formatSpeed(0.0))
            }
        } catch (e: Exception) {
            // 主动取消（call.cancel()）→ 统一转 CancellationException，上层不切换下一源
            if (call.isCanceled()) {
                throw kotlinx.coroutines.CancellationException("下载已取消")
            }
            throw e
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private fun formatSpeed(bytesPerSecond: Double): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSecond / (1024 * 1024))
            bytesPerSecond >= 1024 -> "%.1f KB/s".format(bytesPerSecond / 1024)
            else -> "%.0f B/s".format(bytesPerSecond)
        }
    }

    /**
     * GitHub 加速：配置了代理前缀时，把 GitHub 域名 URL 前缀为 $proxy$url。
     * 仅对 github.com / raw.githubusercontent.com 生效，避免破坏自建下载源。
     */
    private fun resolveUrl(url: String): String {
        val proxy = sourceConfig.githubProxy
        if (proxy.isBlank()) return url
        if (!isGithubUrl(url)) return url
        if (url.startsWith(proxy)) return url
        // 代理前缀统一无尾斜杠，需补分隔符：https://gh-proxy.com/https://github.com/...
        return "$proxy/$url"
    }

    private fun isGithubUrl(url: String): Boolean {
        return url.startsWith("https://github.com/") ||
            url.startsWith("http://github.com/") ||
            url.startsWith("https://raw.githubusercontent.com/") ||
            url.startsWith("http://raw.githubusercontent.com/")
    }
}

private class HttpException(code: Int, override val message: String) : Exception("HTTP $code: $message")
