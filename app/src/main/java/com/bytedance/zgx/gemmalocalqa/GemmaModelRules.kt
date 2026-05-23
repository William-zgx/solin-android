package com.bytedance.zgx.gemmalocalqa

import android.app.DownloadManager
import java.util.Locale

internal const val GEMMA_MODEL_EXTENSION = ".litertlm"
internal const val GEMMA_MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
internal const val GEMMA_RECOMMENDED_MODEL_BYTES = 2_588_147_712L
const val GEMMA_MODEL_REPOSITORY_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
internal const val GEMMA_MODEL_DOWNLOAD_URL =
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"

internal object GemmaModelRules {
    fun isAcceptedModelName(displayName: String): Boolean =
        displayName.endsWith(GEMMA_MODEL_EXTENSION, ignoreCase = true)

    fun sanitizeModelName(displayName: String): String {
        val safeName = displayName
            .trim()
            .replace(Regex("""[^\w.\-]+"""), "_")
            .ifBlank { "gemma-4-e2b$GEMMA_MODEL_EXTENSION" }
        return if (isAcceptedModelName(safeName)) {
            safeName
        } else {
            "$safeName$GEMMA_MODEL_EXTENSION"
        }
    }

    fun hasEnoughSpace(usableBytes: Long, requiredBytes: Long = GEMMA_RECOMMENDED_MODEL_BYTES): Boolean =
        usableBytes >= requiredBytes

    fun isCompleteRecommendedModel(fileBytes: Long): Boolean =
        fileBytes == GEMMA_RECOMMENDED_MODEL_BYTES

    fun progressPercent(doneBytes: Long, totalBytes: Long): Int? {
        if (totalBytes <= 0L) return null
        return ((doneBytes * 100L) / totalBytes).coerceIn(0L, 100L).toInt()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }

    fun downloadStatusText(status: Int, reason: Int): String =
        when (status) {
            DownloadManager.STATUS_PENDING -> "等待下载"
            DownloadManager.STATUS_PAUSED -> "下载暂停：${downloadReasonText(reason)}"
            DownloadManager.STATUS_RUNNING -> "模型下载中"
            DownloadManager.STATUS_SUCCESSFUL -> "模型下载完成"
            DownloadManager.STATUS_FAILED -> "下载失败：${downloadReasonText(reason)}"
            else -> "下载状态未知"
        }

    fun downloadReasonText(reason: Int): String =
        when (reason) {
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "等待 Wi-Fi"
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "等待网络"
            DownloadManager.PAUSED_WAITING_TO_RETRY -> "等待重试"
            DownloadManager.ERROR_CANNOT_RESUME -> "无法继续"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "存储不可用"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "文件已存在"
            DownloadManager.ERROR_FILE_ERROR -> "文件写入失败"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "网络数据错误"
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "下载重定向过多"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "服务器响应异常"
            DownloadManager.ERROR_UNKNOWN -> "未知错误"
            else -> "代码 $reason"
        }
}
