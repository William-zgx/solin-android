package com.bytedance.zgx.solin.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.data.CUSTOM_MODEL_MAX_BYTES
import com.bytedance.zgx.solin.data.ModelDownloadSource
import com.bytedance.zgx.solin.data.ModelTransferSizeLimitExceededException
import com.bytedance.zgx.solin.data.copyStreamWithByteLimit
import com.bytedance.zgx.solin.isLocalDebugHost
import java.io.File
import java.io.IOException
import java.io.InputStream
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
    private val downloadMaximumBytes = ConcurrentHashMap<Long, Long>()
    private val downloadTargets = ConcurrentHashMap<Long, File>()
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
            if (isLocalDebugModelDownloadUrl(preparedDownloadUrl.url)) {
                return@runCatching downloadLocalDebugUrl(
                    preparedDownloadUrl = preparedDownloadUrl,
                    targetFile = targetFile,
                    maximumBytes = source.maximumTransferBytes(),
                )
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
            downloadManager.enqueue(request).also { downloadId ->
                downloadMaximumBytes[downloadId] = source.maximumTransferBytes()
                downloadTargets[downloadId] = targetFile
            }
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
            downloadTargets.remove(downloadId)?.delete()
            downloadMaximumBytes.remove(downloadId)
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
            val info = DownloadInfo(
                status = status,
                reason = reason,
                downloadedBytes = downloaded,
                totalBytes = total,
            )
            val maximumBytes = downloadMaximumBytes[downloadId] ?: CUSTOM_MODEL_MAX_BYTES
            val boundedInfo = enforceDownloadByteLimit(info, maximumBytes) {
                downloadManager.remove(downloadId)
                downloadTargets.remove(downloadId)?.delete()
                downloadMaximumBytes.remove(downloadId)
            }
            if (boundedInfo !== info) return boundedInfo
            if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                downloadTargets.remove(downloadId)
                downloadMaximumBytes.remove(downloadId)
            }
            return info
        }
        return null
    }

    private fun downloadLocalDebugUrl(
        preparedDownloadUrl: PreparedDownloadUrl,
        targetFile: File,
        maximumBytes: Long,
    ): Long {
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
            if (totalBytes > maximumBytes) {
                throw ModelTransferSizeLimitExceededException(maximumBytes)
            }
            targetFile.parentFile?.mkdirs()
            downloadStreamToFileWithByteLimit(
                input = connection.getInputStream(),
                targetFile = targetFile,
                maximumBytes = maximumBytes,
            ) { copiedBytes ->
                downloadedBytes = copiedBytes
                directDownloads[downloadId] = DownloadInfo(
                    status = DownloadManager.STATUS_RUNNING,
                    reason = 0,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                )
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

internal fun downloadStreamToFileWithByteLimit(
    input: InputStream,
    targetFile: File,
    maximumBytes: Long,
    onBytesCopied: (Long) -> Unit = {},
): Long =
    try {
        input.use {
            targetFile.outputStream().use { output ->
                copyStreamWithByteLimit(it, output, maximumBytes, onBytesCopied)
            }
        }
    } catch (throwable: Throwable) {
        targetFile.delete()
        throw throwable
    }

internal fun enforceDownloadByteLimit(
    info: DownloadInfo,
    maximumBytes: Long,
    onLimitExceeded: () -> Unit,
): DownloadInfo {
    if (!downloadExceedsByteLimit(info, maximumBytes)) return info
    onLimitExceeded()
    return info.copy(
        status = DownloadManager.STATUS_FAILED,
        reason = DownloadManager.ERROR_FILE_ERROR,
    )
}

internal fun downloadExceedsByteLimit(
    info: DownloadInfo,
    maximumBytes: Long,
): Boolean =
    info.downloadedBytes > maximumBytes ||
        (info.totalBytes > 0L && info.totalBytes > maximumBytes)

internal fun isLocalDebugModelDownloadUrl(downloadUrl: String): Boolean =
    runCatching { URI(downloadUrl).host.isLocalDebugHost() }.getOrDefault(false)

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
