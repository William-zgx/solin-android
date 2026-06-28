package com.bytedance.zgx.solin.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.data.ModelDownloadSource
import com.bytedance.zgx.solin.isLocalDebugHost
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface ModelDownloadClient {
    fun enqueue(source: ModelDownloadSource, targetFile: File): Result<Long>
    fun cancelPreflight() = Unit
    fun cancel(downloadId: Long)
    fun query(downloadId: Long): DownloadInfo?
}

class ModelDownloadService(
    context: Context,
    private val huggingFaceAuthorizationHeaderProvider: () -> String? = { null },
    private val huggingFaceDownloadUrlResolver: HuggingFaceDownloadUrlResolver = HuggingFaceDownloadUrlResolver(),
) : ModelDownloadClient {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val directDownloadIds = AtomicLong(-1L)
    private val directDownloads = ConcurrentHashMap<Long, DownloadInfo>()
    @Volatile
    private var preflightCancelled = false

    override fun enqueue(source: ModelDownloadSource, targetFile: File): Result<Long> =
        runCatching {
            preflightCancelled = false
            val preparedDownloadUrl = huggingFaceDownloadUrlResolver.prepare(
                source = source,
                authorizationHeaderProvider = huggingFaceAuthorizationHeaderProvider,
                shouldCancel = { preflightCancelled },
            ).getOrThrow()
            if (preflightCancelled) throw CancellationException("下载已取消")
            if (modelDownloadNetworkPolicyFor(preparedDownloadUrl.url) == ModelDownloadNetworkPolicy.LocalDebug) {
                return@runCatching downloadLocalDebugUrl(preparedDownloadUrl, targetFile)
            }
            val request = DownloadManager.Request(Uri.parse(preparedDownloadUrl.url))
                .setTitle(source.title)
                .setDescription("正在下载本地模型")
                .setMimeType("application/octet-stream")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(
                    appContext,
                    Environment.DIRECTORY_DOWNLOADS,
                    targetFile.name,
                )
            request
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                .setAllowedOverMetered(false)
            preparedDownloadUrl.authorizationHeader?.let { authorizationHeader ->
                request.addRequestHeader("Authorization", authorizationHeader)
            }
            downloadManager.enqueue(request)
        }.also {
            preflightCancelled = false
        }

    override fun cancelPreflight() {
        preflightCancelled = true
        huggingFaceDownloadUrlResolver.cancelActiveProbe()
    }

    override fun cancel(downloadId: Long) {
        if (downloadId < 0L) {
            directDownloads.remove(downloadId)
        } else if (downloadId > 0L) {
            downloadManager.remove(downloadId)
        }
    }

    override fun query(downloadId: Long): DownloadInfo? {
        if (downloadId < 0L) return directDownloads[downloadId]
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val downloaded = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            )
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return DownloadInfo(
                status = status,
                reason = reason,
                downloadedBytes = downloaded,
                totalBytes = total,
            )
        }
        return null
    }

    private fun downloadLocalDebugUrl(preparedDownloadUrl: PreparedDownloadUrl, targetFile: File): Long {
        val downloadId = directDownloadIds.getAndDecrement()
        var downloadedBytes = 0L
        var totalBytes = -1L
        directDownloads[downloadId] = DownloadInfo(
            status = DownloadManager.STATUS_RUNNING,
            reason = 0,
            downloadedBytes = 0L,
            totalBytes = totalBytes,
        )
        val connection = URL(preparedDownloadUrl.url).openConnection()
        return try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            preparedDownloadUrl.authorizationHeader?.let { authorizationHeader ->
                connection.setRequestProperty("Authorization", authorizationHeader)
            }
            connection.setRequestProperty("User-Agent", "Solin model downloader")
            if (connection is HttpURLConnection) {
                connection.requestMethod = "GET"
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
            }
            totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: -1L
            targetFile.parentFile?.mkdirs()
            connection.getInputStream().use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        directDownloads[downloadId] = DownloadInfo(
                            status = DownloadManager.STATUS_RUNNING,
                            reason = 0,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                        )
                    }
                }
            }
            directDownloads[downloadId] = DownloadInfo(
                status = DownloadManager.STATUS_SUCCESSFUL,
                reason = 0,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes.takeIf { it > 0L } ?: downloadedBytes,
            )
            downloadId
        } catch (throwable: Throwable) {
            targetFile.delete()
            directDownloads[downloadId] = DownloadInfo(
                status = DownloadManager.STATUS_FAILED,
                reason = DownloadManager.ERROR_HTTP_DATA_ERROR,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
            )
            throw throwable
        } finally {
            if (connection is HttpURLConnection) {
                connection.disconnect()
            }
        }
    }
}

internal enum class ModelDownloadNetworkPolicy {
    LocalDebug,
    WifiOnly,
}

internal fun modelDownloadNetworkPolicyFor(downloadUrl: String): ModelDownloadNetworkPolicy =
    if (runCatching { URI(downloadUrl).host.isLocalDebugHost() }.getOrDefault(false)) {
        ModelDownloadNetworkPolicy.LocalDebug
    } else {
        ModelDownloadNetworkPolicy.WifiOnly
    }

data class DownloadInfo(
    val status: Int,
    val reason: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val progressPercent: Int?
        get() = ModelCatalog.progressPercent(downloadedBytes, totalBytes)

    val statusText: String
        get() = ModelCatalog.downloadStatusText(status, reason)

    val reasonText: String
        get() = ModelCatalog.downloadReasonText(reason)
}
