package com.bytedance.zgx.pocketmind.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.bytedance.zgx.pocketmind.ModelCatalog
import com.bytedance.zgx.pocketmind.data.ModelDownloadSource
import java.io.File

interface ModelDownloadClient {
    fun enqueue(source: ModelDownloadSource, targetFile: File): Result<Long>
    fun cancel(downloadId: Long)
    fun query(downloadId: Long): DownloadInfo?
}

class ModelDownloadService(
    context: Context,
    private val huggingFaceAuthorizationHeaderProvider: () -> String? = { null },
) : ModelDownloadClient {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)

    override fun enqueue(source: ModelDownloadSource, targetFile: File): Result<Long> =
        runCatching {
            val request = DownloadManager.Request(Uri.parse(source.downloadUrl))
                .setTitle(source.title)
                .setDescription("正在下载本地模型")
                .setMimeType("application/octet-stream")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                .setAllowedOverMetered(false)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(
                    appContext,
                    Environment.DIRECTORY_DOWNLOADS,
                    targetFile.name,
                )
            if (source.requiresHuggingFaceAuthorization) {
                val authorizationHeader = huggingFaceAuthorizationHeaderProvider()
                    ?.takeIf { it.isNotBlank() }
                    ?: error("需要先完成 Hugging Face 授权")
                request.addRequestHeader("Authorization", authorizationHeader)
            }
            downloadManager.enqueue(request)
        }

    override fun cancel(downloadId: Long) {
        if (downloadId > 0L) {
            downloadManager.remove(downloadId)
        }
    }

    override fun query(downloadId: Long): DownloadInfo? {
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
