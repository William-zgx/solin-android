package com.bytedance.zgx.pocketmind.runtime

import com.bytedance.zgx.pocketmind.RemoteModelConfig
import com.bytedance.zgx.pocketmind.RemoteModelConnectivityStatus
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

interface RemoteModelConnectivityProbe {
    suspend fun check(config: RemoteModelConfig): RemoteModelConnectivityStatus
}

class OkHttpRemoteModelConnectivityProbe(
    private val callFactory: Call.Factory = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RemoteModelConnectivityProbe {
    override suspend fun check(config: RemoteModelConfig): RemoteModelConnectivityStatus =
        withContext(ioDispatcher) {
            val normalized = config.normalized()
            if (!normalized.isConfigured) return@withContext RemoteModelConnectivityStatus.Unknown
            val request = Request.Builder()
                .url(normalized.modelsUrl())
                .get()
                .header("Accept", "application/json")
                .apply {
                    if (normalized.apiKey.isNotBlank()) {
                        header("Authorization", "Bearer ${normalized.apiKey}")
                    }
                }
                .build()
            try {
                callFactory.newCall(request).execute().use { response ->
                    when (response.code) {
                        in 200..299 -> RemoteModelConnectivityStatus.Reachable
                        401, 403 -> RemoteModelConnectivityStatus.AuthenticationFailed
                        else -> RemoteModelConnectivityStatus.Unreachable
                    }
                }
            } catch (_: IOException) {
                RemoteModelConnectivityStatus.Unreachable
            } catch (_: IllegalArgumentException) {
                RemoteModelConnectivityStatus.Unreachable
            }
        }
}

private fun RemoteModelConfig.modelsUrl(): String =
    baseUrl.trimEnd('/').let { normalizedBaseUrl ->
        when {
            normalizedBaseUrl.endsWith("/models") -> normalizedBaseUrl
            normalizedBaseUrl.endsWith("/chat/completions") ->
                normalizedBaseUrl.removeSuffix("/chat/completions") + "/models"
            else -> "$normalizedBaseUrl/models"
        }
    }
