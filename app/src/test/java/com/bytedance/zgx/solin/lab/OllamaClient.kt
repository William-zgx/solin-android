package com.bytedance.zgx.solin.lab

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Minimal non-streaming client for Ollama's `/api/chat`, used only by the opt-in lab bench. */
class OllamaClient(
    private val baseUrl: String = LabEnv.ollamaBaseUrl,
    timeoutSeconds: Long = 600,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    data class Message(val role: String, val content: String)

    /**
     * Greedy decoding matching the app's action sampler (topK=1, topP=0.1, temperature=0), plus a
     * fixed seed and an output cap. `numPredict` is a lab-only bound: a valid `call:` line is far
     * shorter, so the cap only truncates outputs that would fail parsing anyway.
     */
    data class Options(
        val temperature: Double = 0.0,
        val topK: Int = 1,
        val topP: Double = 0.1,
        val seed: Int = 7,
        val numCtx: Int = 8192,
        val numPredict: Int = 384,
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("temperature", temperature)
                .put("top_k", topK)
                .put("top_p", topP)
                .put("seed", seed)
                .put("num_ctx", numCtx)
                .put("num_predict", numPredict)
    }

    data class ToolCall(val name: String, val arguments: JSONObject)

    data class ChatResult(
        val text: String,
        val toolCalls: List<ToolCall>,
        val wallMillis: Long,
        val totalDurationNs: Long,
        val loadDurationNs: Long,
        val promptEvalCount: Int,
        val promptEvalCachedCount: Int,
        val promptEvalDurationNs: Long,
        val evalCount: Int,
        val evalDurationNs: Long,
        val doneReason: String?,
        val error: String? = null,
    ) {
        fun metricsJson(): JSONObject =
            JSONObject()
                .put("wallMs", wallMillis)
                .put("totalMs", totalDurationNs / 1_000_000)
                .put("loadMs", loadDurationNs / 1_000_000)
                .put("promptTokens", promptEvalCount)
                .put("promptCachedTokens", promptEvalCachedCount)
                .put("promptEvalMs", promptEvalDurationNs / 1_000_000)
                .put("evalTokens", evalCount)
                .put("evalMs", evalDurationNs / 1_000_000)
                .put("doneReason", doneReason ?: JSONObject.NULL)
                .put("error", error ?: JSONObject.NULL)
    }

    fun chat(
        model: String,
        messages: List<Message>,
        options: Options = Options(),
        tools: JSONArray? = null,
        format: Any? = null,
    ): ChatResult {
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("keep_alive", "30m")
            .put("options", options.toJson())
            .put(
                "messages",
                JSONArray(messages.map { message -> JSONObject().put("role", message.role).put("content", message.content) }),
            )
        // The app runs non-thinking. `think` is only sent to models that declare thinking support.
        if (CAPABILITY_THINKING in capabilities(model)) body.put("think", false)
        if (tools != null) body.put("tools", tools)
        if (format != null) body.put("format", format)
        var lastError: String? = null
        repeat(2) {
            val started = System.nanoTime()
            try {
                val response = post("/api/chat", body)
                val wall = (System.nanoTime() - started) / 1_000_000
                return parseChat(response, wall)
            } catch (error: IOException) {
                lastError = "${error.javaClass.simpleName}:${error.message}"
                LabLog.info("ollama chat failed for $model: $lastError")
            }
        }
        return ChatResult(
            text = "",
            toolCalls = emptyList(),
            wallMillis = 0,
            totalDurationNs = 0,
            loadDurationNs = 0,
            promptEvalCount = 0,
            promptEvalCachedCount = 0,
            promptEvalDurationNs = 0,
            evalCount = 0,
            evalDurationNs = 0,
            doneReason = null,
            error = lastError ?: "unknown_error",
        )
    }

    /** Loads the model so the first measured call does not pay the load time. */
    fun warmUp(model: String) {
        chat(model, listOf(Message("user", "你好")), Options(numPredict = 4))
    }

    private val capabilityCache = mutableMapOf<String, Set<String>>()

    /**
     * Ollama's declared capabilities for [model] (`completion`, `tools`, `thinking`, ...), read from
     * `/api/show` without loading the model. Empty when the server cannot say.
     */
    @Synchronized
    fun capabilities(model: String): Set<String> =
        capabilityCache.getOrPut(model) {
            runCatching {
                JSONObject(post("/api/show", JSONObject().put("model", model))).optJSONArray("capabilities").stringList().toSet()
            }.getOrDefault(emptySet())
        }

    fun tags(): JSONArray = JSONObject(get("/api/tags")).getJSONArray("models")

    fun version(): String = JSONObject(get("/api/version")).optString("version")

    private fun parseChat(raw: String, wallMillis: Long): ChatResult {
        val json = JSONObject(raw)
        json.optString("error").takeIf { it.isNotBlank() }?.let { throw IOException("ollama_error:$it") }
        val message = json.optJSONObject("message") ?: JSONObject()
        val toolCallsJson = message.optJSONArray("tool_calls") ?: JSONArray()
        val toolCalls = (0 until toolCallsJson.length()).mapNotNull { index ->
            val function = toolCallsJson.optJSONObject(index)?.optJSONObject("function") ?: return@mapNotNull null
            val arguments = when (val value = function.opt("arguments")) {
                is JSONObject -> value
                is String -> runCatching { JSONObject(value) }.getOrDefault(JSONObject())
                else -> JSONObject()
            }
            ToolCall(function.optString("name"), arguments)
        }
        return ChatResult(
            text = message.optString("content"),
            toolCalls = toolCalls,
            wallMillis = wallMillis,
            totalDurationNs = json.optLong("total_duration"),
            loadDurationNs = json.optLong("load_duration"),
            promptEvalCount = json.optInt("prompt_eval_count"),
            promptEvalCachedCount = json.optInt("prompt_eval_cached_count"),
            promptEvalDurationNs = json.optLong("prompt_eval_duration"),
            evalCount = json.optInt("eval_count"),
            evalDurationNs = json.optLong("eval_duration"),
            doneReason = json.optString("done_reason").takeIf { it.isNotBlank() },
        )
    }

    private fun post(path: String, body: JSONObject): String {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException("http_${response.code}:${text.take(200)}")
            return text
        }
    }

    private fun get(path: String): String {
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path).get().build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException("http_${response.code}:${text.take(200)}")
            return text
        }
    }

    companion object {
        const val CAPABILITY_TOOLS = "tools"
        const val CAPABILITY_THINKING = "thinking"
    }
}
