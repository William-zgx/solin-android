package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.runtime.LocalAccelerator
import com.bytedance.zgx.solin.runtime.LocalCallMetrics
import com.bytedance.zgx.solin.runtime.LocalEngine
import com.bytedance.zgx.solin.runtime.LocalEngineCapabilities
import com.bytedance.zgx.solin.runtime.LocalEngineSource
import com.bytedance.zgx.solin.runtime.LocalOneShotRequest
import com.bytedance.zgx.solin.runtime.LocalOneShotResult
import com.bytedance.zgx.solin.runtime.LocalOneShotUnavailableReason
import com.bytedance.zgx.solin.runtime.LocalToolCall
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Test-only [LocalEngine] transport for the production planner, backed by `lab/scripts/litert_shim.py`.
 * Planning, prompts, parsing, retrieval and acceptance stay in production code; this class only maps
 * one-shot requests to `/api/chat` and returns production result types.
 */
class LiteRtShimLocalEngine(
    private val loadedModelPath: String,
    private val baseUrl: String = LabEnv.ollamaBaseUrl,
    timeoutSeconds: Long = PRODUCTION_ONE_SHOT_TIMEOUT_SECONDS,
) : LocalEngine {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()
    private val observedCalls = CopyOnWriteArrayList<ObservedCall>()

    val calls: List<ObservedCall>
        get() = observedCalls.toList()

    fun clearCalls() = observedCalls.clear()

    override fun engineCapabilities(): LocalEngineCapabilities =
        LocalEngineCapabilities(
            backendName = "litert-lm",
            accelerators = setOf(LocalAccelerator.CPU),
            maxContextTokens = 8_192,
            nativeToolCalling = false,
        )

    override fun generateOneShot(request: LocalOneShotRequest): LocalOneShotResult {
        val started = System.nanoTime()
        if (request.cancellation.isCancelled) {
            return record(request, started, unavailable(LocalOneShotUnavailableReason.Cancelled, "cancelled before HTTP"), "")
        }
        val modelId = MODEL_IDS[File(loadedModelPath).name]
            ?: return record(
                request,
                started,
                unavailable(LocalOneShotUnavailableReason.Unsupported, "no shim mapping for $loadedModelPath"),
                "",
            )
        if (File(request.modelPath).absolutePath != File(loadedModelPath).absolutePath) {
            return record(
                request,
                started,
                unavailable(LocalOneShotUnavailableReason.ModelNotLoaded, "requested model is not loaded"),
                "",
            )
        }
        val body = JSONObject()
            .put("model", modelId)
            .put("stream", false)
            .put("keep_alive", "30m")
            .put(
                "options",
                JSONObject()
                    .put("temperature", 0.0)
                    .put("top_k", 1)
                    .put("top_p", 0.1)
                    // LiteRT's unset production seed converts to zero in the 0.12.0 Python API.
                    .put("seed", 0)
                    .put("num_ctx", 8_192)
                    .put("num_predict", request.maxOutputTokens),
            )
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", request.systemInstruction))
                    .put(JSONObject().put("role", "user").put("content", request.prompt)),
            )
        val httpCall = http.newCall(
            Request.Builder()
                .url(baseUrl.trimEnd('/') + "/api/chat")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build(),
        )
        val stopListening = request.cancellation.onCancel { httpCall.cancel() }
        try {
            httpCall.execute().use { response ->
                val responseText = response.body.string()
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        404 -> LocalOneShotUnavailableReason.ModelNotLoaded
                        409, 423 -> LocalOneShotUnavailableReason.Busy
                        501 -> LocalOneShotUnavailableReason.Unsupported
                        else -> LocalOneShotUnavailableReason.Failed
                    }
                    return record(
                        request,
                        started,
                        unavailable(reason, "http_${response.code}:${responseText.take(ERROR_DETAIL_CHARS)}"),
                        "",
                        "http_${response.code}",
                    )
                }
                val json = runCatching { JSONObject(responseText) }.getOrElse { error ->
                    return record(
                        request,
                        started,
                        unavailable(LocalOneShotUnavailableReason.Failed, "invalid shim JSON: ${error.message}"),
                        "",
                        "invalid_shim_json",
                    )
                }
                val engineError = json.optString("error").takeIf { it.isNotBlank() }
                if (engineError != null) {
                    return record(
                        request,
                        started,
                        unavailable(LocalOneShotUnavailableReason.Failed, engineError.take(ERROR_DETAIL_CHARS)),
                        "",
                        engineError.take(ERROR_DETAIL_CHARS),
                        json,
                    )
                }
                if (request.cancellation.isCancelled) {
                    return record(
                        request,
                        started,
                        unavailable(LocalOneShotUnavailableReason.Cancelled, "cancelled after shim response"),
                        "",
                        "cancelled",
                        json,
                    )
                }
                val text = json.optJSONObject("message")?.optString("content").orEmpty()
                val wallMs = elapsedMillis(started)
                val generated = LocalOneShotResult.Generated(
                    text = text,
                    metrics = LocalCallMetrics(
                        source = LocalEngineSource.SharedChatEngine,
                        accelerator = LocalAccelerator.CPU,
                        loadMs = json.optLong("load_duration").nanosToMillis().takeIf { it > 0 },
                        firstTokenMs = null,
                        totalMs = wallMs,
                        inputTokens = json.optInt("prompt_eval_count").takeIf { it > 0 },
                        outputTokens = json.optInt("eval_count").takeIf { it > 0 },
                    ),
                    toolCalls = json.localToolCalls(),
                )
                return record(request, started, generated, text, null, json)
            }
        } catch (error: IOException) {
            val cancelled = request.cancellation.isCancelled
            val reason = if (cancelled) {
                LocalOneShotUnavailableReason.Cancelled
            } else {
                LocalOneShotUnavailableReason.Failed
            }
            val detail = "${error.javaClass.simpleName}:${error.message.orEmpty()}".take(ERROR_DETAIL_CHARS)
            return record(request, started, unavailable(reason, detail), "", detail)
        } catch (error: Throwable) {
            val detail = "${error.javaClass.simpleName}:${error.message.orEmpty()}".take(ERROR_DETAIL_CHARS)
            return record(
                request,
                started,
                unavailable(LocalOneShotUnavailableReason.Failed, detail),
                "",
                detail,
            )
        } finally {
            stopListening()
        }
    }

    private fun unavailable(reason: LocalOneShotUnavailableReason, detail: String) =
        LocalOneShotResult.Unavailable(reason, detail)

    private fun record(
        request: LocalOneShotRequest,
        started: Long,
        result: LocalOneShotResult,
        rawText: String,
        error: String? = (result as? LocalOneShotResult.Unavailable)?.detail,
        shimResponse: JSONObject? = null,
    ): LocalOneShotResult {
        val wallMs = elapsedMillis(started)
        observedCalls += ObservedCall(
            request = request,
            result = result,
            rawText = rawText,
            metrics = metricsJson(request, wallMs, shimResponse, error),
        )
        return result
    }

    private fun metricsJson(
        request: LocalOneShotRequest,
        wallMs: Long,
        response: JSONObject?,
        error: String?,
    ): JSONObject =
        JSONObject()
            .put("wallMs", wallMs)
            .put("totalMs", response?.optLong("total_duration")?.nanosToMillis() ?: wallMs)
            .put("loadMs", response?.optLong("load_duration")?.nanosToMillis() ?: 0)
            .put("promptTokens", response?.optInt("prompt_eval_count") ?: 0)
            .put("promptCachedTokens", response?.optInt("prompt_eval_cached_count") ?: 0)
            .put("promptEvalMs", response?.optLong("prompt_eval_duration")?.nanosToMillis() ?: 0)
            .put("evalTokens", response?.optInt("eval_count") ?: 0)
            .put("evalMs", response?.optLong("eval_duration")?.nanosToMillis() ?: 0)
            .put("doneReason", response?.optString("done_reason")?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("maxOutputTokens", request.maxOutputTokens)
            .put("source", LocalEngineSource.SharedChatEngine.name)
            .put("error", error ?: JSONObject.NULL)

    private fun JSONObject.localToolCalls(): List<LocalToolCall> {
        val calls = optJSONObject("message")?.optJSONArray("tool_calls") ?: return emptyList()
        return (0 until calls.length()).mapNotNull { index ->
            val function = calls.optJSONObject(index)?.optJSONObject("function") ?: return@mapNotNull null
            val name = function.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val arguments = when (val value = function.opt("arguments")) {
                is JSONObject -> value
                is String -> runCatching { JSONObject(value) }.getOrDefault(JSONObject())
                else -> JSONObject()
            }
            LocalToolCall(name, arguments.toString())
        }
    }

    private fun Long.nanosToMillis(): Long = this / 1_000_000

    private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    data class ObservedCall(
        val request: LocalOneShotRequest,
        val result: LocalOneShotResult,
        val rawText: String,
        val metrics: JSONObject,
    )

    companion object {
        private const val PRODUCTION_ONE_SHOT_TIMEOUT_SECONDS = 120L
        private const val ERROR_DETAIL_CHARS = 240
        private val MODEL_IDS = mapOf(
            "gemma-4-E2B-it.litertlm" to "litert/gemma-4-e2b",
            "gemma-4-E4B-it.litertlm" to "litert/gemma-4-e4b",
            "mobile-actions_q8_ekv1024.litertlm" to "litert/mobile-actions-270m",
        )
    }
}
