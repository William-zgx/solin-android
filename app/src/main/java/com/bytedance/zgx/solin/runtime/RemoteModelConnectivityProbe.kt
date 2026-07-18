package com.bytedance.zgx.solin.runtime

import com.bytedance.zgx.solin.RemoteConnectivitySnapshot
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.data.RemoteModelStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume

interface RemoteModelConnectivityProbe {
    suspend fun check(config: RemoteModelConfig): RemoteModelConnectivityStatus
}

class OkHttpRemoteModelConnectivityProbe(
    private val callFactory: Call.Factory = OkHttpClient.Builder()
        .connectTimeout(SolinConstants.Network.PROBE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(SolinConstants.Network.PROBE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
) : RemoteModelConnectivityProbe {
    override suspend fun check(config: RemoteModelConfig): RemoteModelConnectivityStatus {
        val normalized = config.normalized()
        if (!normalized.isConfigured) return RemoteModelConnectivityStatus.Unknown
        val request = try {
            Request.Builder()
                .url(normalized.modelsUrl())
                .get()
                .header("Accept", "application/json")
                .apply {
                    if (normalized.apiKey.isNotBlank()) {
                        header("Authorization", "Bearer ${normalized.apiKey}")
                    }
                }
                .build()
        } catch (_: IllegalArgumentException) {
            return RemoteModelConnectivityStatus.Unreachable
        }
        return suspendCancellableCoroutine { continuation ->
            val call = try {
                callFactory.newCall(request)
            } catch (_: Exception) {
                continuation.resumeProbe(RemoteModelConnectivityStatus.Unreachable)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { call.cancel() }
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeProbe(RemoteModelConnectivityStatus.Unreachable)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val status = response.use {
                            when (it.code) {
                                in 200..299 -> RemoteModelConnectivityStatus.Reachable
                                401, 403 -> RemoteModelConnectivityStatus.AuthenticationFailed
                                else -> RemoteModelConnectivityStatus.Unreachable
                            }
                        }
                        continuation.resumeProbe(status)
                    }
                })
            } catch (_: Exception) {
                continuation.resumeProbe(RemoteModelConnectivityStatus.Unreachable)
            }
        }
    }
}

private fun kotlinx.coroutines.CancellableContinuation<RemoteModelConnectivityStatus>.resumeProbe(
    status: RemoteModelConnectivityStatus,
) {
    if (isActive) resume(status)
}

class RemoteConnectivityRefreshCoordinator(
    private val remoteModelStore: RemoteModelStore,
    private val probe: RemoteModelConnectivityProbe,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private var inFlight: InFlight? = null

    fun refresh(
        config: RemoteModelConfig,
        force: Boolean = false,
    ): Deferred<RemoteConnectivitySnapshot?> {
        val captured = config.normalized()
        val revision = captured.profileRevision
        if (!captured.isConfigured || revision.isBlank()) return CompletableDeferred(null)

        return synchronized(lock) {
            inFlight
                ?.takeIf { it.revision == revision && it.deferred.isActive }
                ?.let { return@synchronized it.deferred }
            if (!force) {
                remoteModelStore.currentConnectivity(captured)?.let {
                    return@synchronized CompletableDeferred(it)
                }
            }

            inFlight?.deferred?.cancel()
            val deferred = scope.async(dispatcher, start = CoroutineStart.LAZY) {
                val status = try {
                    probe.check(captured)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    RemoteModelConnectivityStatus.Unreachable
                }
                remoteModelStore.recordConnectivity(captured, status)
                remoteModelStore.currentConnectivity(captured)
            }
            val current = InFlight(revision, deferred)
            inFlight = current
            deferred.invokeOnCompletion {
                synchronized(lock) {
                    if (inFlight === current) inFlight = null
                }
            }
            deferred.start()
            deferred
        }
    }

    private data class InFlight(
        val revision: String,
        val deferred: Deferred<RemoteConnectivitySnapshot?>,
    )
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
