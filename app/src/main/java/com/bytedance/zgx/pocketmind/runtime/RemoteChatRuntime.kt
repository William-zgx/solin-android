package com.bytedance.zgx.pocketmind.runtime

import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.GenerationParameters
import com.bytedance.zgx.pocketmind.MessageRole
import com.bytedance.zgx.pocketmind.RemoteModelConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

interface RemoteChatRuntime {
    fun send(
        prompt: String,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        config: RemoteModelConfig,
    ): Flow<String>

    fun stop()
}

class OkHttpRemoteChatRuntime(
    private val callFactory: Call.Factory = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RemoteChatRuntime {
    @Volatile
    private var activeCall: Call? = null

    override fun send(
        prompt: String,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        config: RemoteModelConfig,
    ): Flow<String> = callbackFlow {
        val normalized = config.normalized()
        require(normalized.isConfigured) { "请先配置远程模型地址和模型名" }
        val request = Request.Builder()
            .url(normalized.chatCompletionsUrl())
            .post(
                buildChatCompletionBody(prompt, history, parameters, normalized)
                    .toString()
                    .toRequestBody(JSON),
            )
            .header("Accept", "text/event-stream, application/json")
            .header("Content-Type", "application/json")
            .apply {
                if (normalized.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${normalized.apiKey}")
                }
            }
            .build()
        val call = callFactory.newCall(request)
        activeCall = call
        val worker = launch(ioDispatcher) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        response.body.close()
                        error("远程模型请求失败 ${response.code}")
                    }
                    val body = response.body
                    if (response.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)) {
                        body.charStream().buffered().use { reader ->
                            while (isActive) {
                                val line = reader.readLine() ?: break
                                if (!line.startsWith("data:")) continue
                                val payload = line.removePrefix("data:").trim()
                                if (payload == "[DONE]") break
                                val chunk = parseChatCompletionChunkText(payload)
                                if (chunk.isNotEmpty()) send(chunk)
                            }
                        }
                    } else {
                        send(parseChatCompletionText(body.string()))
                    }
                }
                close()
            } catch (throwable: Throwable) {
                if (call.isCanceled()) {
                    close(CancellationException("远程生成已取消", throwable))
                } else {
                    close(throwable)
                }
            } finally {
                if (activeCall == call) {
                    activeCall = null
                }
            }
        }
        awaitClose {
            call.cancel()
            worker.cancel()
            if (activeCall == call) {
                activeCall = null
            }
        }
    }

    override fun stop() {
        activeCall?.cancel()
    }
}

internal fun buildChatCompletionBody(
    prompt: String,
    history: List<ChatMessage>,
    parameters: GenerationParameters,
    config: RemoteModelConfig,
): JSONObject =
    JSONObject()
        .put("model", config.modelName)
        .put("stream", true)
        .put("temperature", parameters.temperature.toDouble())
        .put("top_p", parameters.topP.toDouble())
        .put(
            "messages",
            JSONArray()
                .put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", DEFAULT_CHAT_SYSTEM_INSTRUCTION),
                )
                .appendHistory(history)
                .put(JSONObject().put("role", "user").put("content", prompt)),
        )

internal fun parseChatCompletionChunkText(raw: String): String {
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return ""
    val choice = json.optJSONArray("choices")
        ?.optJSONObject(0)
        ?: return ""
    val deltaText = choice.optJSONObject("delta")
        ?.optNonNullString("content")
        .orEmpty()
    if (deltaText.isNotEmpty()) return deltaText

    val messageText = choice.optJSONObject("message")
        ?.optNonNullString("content")
        .orEmpty()
    if (messageText.isNotEmpty()) return messageText

    return choice.optNonNullString("text")
}

internal fun parseChatCompletionText(raw: String): String {
    val json = JSONObject(raw)
    val choice = json.optJSONArray("choices")
        ?.optJSONObject(0)
        ?: error("远程模型响应为空")
    val messageText = choice.optJSONObject("message")
        ?.optNonNullString("content")
        .orEmpty()
        .trim()
    if (messageText.isNotBlank()) return messageText

    val text = choice.optNonNullString("text").trim()
    if (text.isNotBlank()) return text

    error("远程模型响应没有文本内容")
}

private fun JSONObject.optNonNullString(name: String): String =
    if (has(name) && !isNull(name)) optString(name) else ""

private fun JSONArray.appendHistory(history: List<ChatMessage>): JSONArray {
    history
        .filter { it.text.isNotBlank() }
        .takeLast(20)
        .forEach { message ->
            val role = when (message.role) {
                MessageRole.User -> "user"
                MessageRole.Assistant -> "assistant"
            }
            put(JSONObject().put("role", role).put("content", message.text))
        }
    return this
}

private fun RemoteModelConfig.chatCompletionsUrl(): String =
    if (baseUrl.endsWith("/chat/completions")) {
        baseUrl
    } else {
        "$baseUrl/chat/completions"
    }

private val JSON = "application/json; charset=utf-8".toMediaType()
