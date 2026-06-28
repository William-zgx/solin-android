package com.bytedance.zgx.solin.download

import com.bytedance.zgx.solin.data.ModelDownloadSource
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

data class PreparedDownloadUrl(
    val url: String,
    val authorizationHeader: String?,
)

class HuggingFaceDownloadUrlResolver(
    private val callFactory: Call.Factory = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) {
    fun prepare(
        source: ModelDownloadSource,
        shouldCancel: () -> Boolean = { false },
        authorizationHeaderProvider: () -> String?,
    ): Result<PreparedDownloadUrl> =
        runCatching {
            if (!source.requiresHuggingFaceAuthorization) {
                if (shouldCancel()) throw IOException("下载已取消")
                return@runCatching PreparedDownloadUrl(source.downloadUrl, authorizationHeader = null)
            }
            val authorizationHeader = authorizationHeaderProvider()
                ?.takeIf { it.isNotBlank() }
                ?: error("需要先完成 Hugging Face 授权")
            val resolvedUrl = resolveAuthorizedUrl(source.downloadUrl, authorizationHeader, shouldCancel)
            if (shouldCancel()) throw IOException("下载已取消")
            PreparedDownloadUrl(
                url = resolvedUrl,
                authorizationHeader = authorizationHeader.takeIf { resolvedUrl == source.downloadUrl },
            )
        }

    private fun resolveAuthorizedUrl(
        url: String,
        authorizationHeader: String,
        shouldCancel: () -> Boolean,
    ): String {
        if (shouldCancel()) throw IOException("下载已取消")
        val headResponse = executeProbe(url, authorizationHeader, method = ProbeMethod.Head, shouldCancel = shouldCancel)
        headResponse.use { response ->
            if (shouldCancel()) throw IOException("下载已取消")
            if (response.isSuccessful) return response.request.url.toString()
            if (response.canRetryWithRangeGet(originalUrl = url)) {
                val rangeResponse = executeProbe(
                    url,
                    authorizationHeader,
                    method = ProbeMethod.RangeGet,
                    shouldCancel = shouldCancel,
                )
                rangeResponse.use { ranged ->
                    if (shouldCancel()) throw IOException("下载已取消")
                    if (ranged.isSuccessful) return ranged.request.url.toString()
                    throw IOException(ranged.huggingFaceFailureMessage())
                }
            }
            throw IOException(response.huggingFaceFailureMessage())
        }
    }

    private fun executeProbe(
        url: String,
        authorizationHeader: String,
        method: ProbeMethod,
        shouldCancel: () -> Boolean,
    ): Response {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", authorizationHeader)
            .header("User-Agent", "Solin model downloader")
        when (method) {
            ProbeMethod.Head -> builder.head()
            ProbeMethod.RangeGet -> builder.get().header("Range", "bytes=0-0")
        }
        val call = callFactory.newCall(builder.build())
        activeProbeCall = call
        if (shouldCancel()) call.cancel()
        return try {
            call.execute()
        } finally {
            if (activeProbeCall == call) activeProbeCall = null
        }
    }

    fun cancelActiveProbe() {
        activeProbeCall?.cancel()
    }

    private fun Response.canRetryWithRangeGet(originalUrl: String): Boolean =
        code == 405 ||
            code == 501 ||
            request.url.host != Request.Builder().url(originalUrl).build().url.host

    private fun Response.huggingFaceFailureMessage(): String =
        when (code) {
            401 -> "Hugging Face 授权无效：请清除旧 token，使用已接受模型许可账号的新 read token"
            403 -> "Hugging Face 无权访问：请确认已接受模型许可，token 具备读取 gated 模型权限"
            404 -> "Hugging Face 文件地址不可用：请更新应用内置模型地址或稍后重试"
            else -> "Hugging Face 下载预检失败：HTTP $code"
        }

    private enum class ProbeMethod {
        Head,
        RangeGet,
    }

    @Volatile
    private var activeProbeCall: Call? = null
}
