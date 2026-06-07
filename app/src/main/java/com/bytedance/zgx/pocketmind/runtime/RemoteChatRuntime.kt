package com.bytedance.zgx.pocketmind.runtime

import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.ChatImageAttachment
import com.bytedance.zgx.pocketmind.GenerationParameters
import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.MessageRole
import com.bytedance.zgx.pocketmind.RemoteModelConfig
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolSpec
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

sealed class RemoteChatEvent {
    data class TextDelta(val text: String) : RemoteChatEvent()
    data class ToolCall(val request: ToolRequest) : RemoteChatEvent()
    data class ToolCalls(val requests: List<ToolRequest>) : RemoteChatEvent()
    data class ParseError(val summary: String) : RemoteChatEvent()
}

interface RemoteChatRuntime {
    fun send(
        prompt: String,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        config: RemoteModelConfig,
        imageAttachments: List<ChatImageAttachment> = emptyList(),
    ): Flow<String>

    fun sendWithTools(
        prompt: String,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        config: RemoteModelConfig,
        tools: List<ToolSpec>,
        imageAttachments: List<ChatImageAttachment> = emptyList(),
    ): Flow<RemoteChatEvent> =
        send(prompt, history, parameters, config, imageAttachments).map { chunk -> RemoteChatEvent.TextDelta(chunk) }

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

    fun send(
        prompt: String,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        config: RemoteModelConfig,
    ): Flow<String> =
        send(prompt, history, parameters, config, emptyList())

    override fun send(
        prompt: String,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        config: RemoteModelConfig,
        imageAttachments: List<ChatImageAttachment>,
    ): Flow<String> = callbackFlow {
        val normalized = config.normalized()
        require(normalized.isConfigured) { "请先配置远程模型地址和模型名" }
        val request = Request.Builder()
            .url(normalized.chatCompletionsUrl())
            .post(
                buildChatCompletionBody(prompt, history, parameters, normalized, imageAttachments = imageAttachments)
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
                        error(remoteFailureMessage(response.code, imageAttachments))
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

    fun sendWithTools(
        prompt: String,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        config: RemoteModelConfig,
        tools: List<ToolSpec>,
    ): Flow<RemoteChatEvent> =
        sendWithTools(prompt, history, parameters, config, tools, emptyList())

    override fun sendWithTools(
        prompt: String,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        config: RemoteModelConfig,
        tools: List<ToolSpec>,
        imageAttachments: List<ChatImageAttachment>,
    ): Flow<RemoteChatEvent> = callbackFlow {
        val normalized = config.normalized()
        require(normalized.isConfigured) { "请先配置远程模型地址和模型名" }
        val request = Request.Builder()
            .url(normalized.chatCompletionsUrl())
            .post(
                buildChatCompletionBody(
                    prompt,
                    history,
                    parameters,
                    normalized,
                    tools,
                    imageAttachments,
                )
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
                        error(remoteFailureMessage(response.code, imageAttachments))
                    }
                    val body = response.body
                    if (response.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)) {
                        val accumulator = RemoteToolCallAccumulator()
                        body.charStream().buffered().use { reader ->
                            while (isActive) {
                                val line = reader.readLine() ?: break
                                if (!line.startsWith("data:")) continue
                                val payload = line.removePrefix("data:").trim()
                                if (payload == "[DONE]") break
                                accumulator.absorb(payload).forEach { event -> send(event) }
                            }
                        }
                        accumulator.finish().forEach { event -> send(event) }
                    } else {
                        parseChatCompletionEvents(body.string()).forEach { event -> send(event) }
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
    tools: List<ToolSpec> = emptyList(),
    imageAttachments: List<ChatImageAttachment> = emptyList(),
): JSONObject {
    require(imageAttachments.isEmpty() || config.supportsVisionInput) {
        "当前远程模型未启用图片输入能力，未读取、OCR 或发送图片；请切换支持视觉的远程模型后重新选择图片。"
    }
    return JSONObject()
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
                .put(userMessageJson(prompt, imageAttachments)),
        ).apply {
            if (tools.isNotEmpty()) {
                put("tools", tools.toOpenAiToolsJson())
                put("tool_choice", "auto")
            }
        }
}

private fun userMessageJson(prompt: String, imageAttachments: List<ChatImageAttachment>): JSONObject {
    val message = JSONObject().put("role", "user")
    if (imageAttachments.isEmpty()) {
        return message.put("content", prompt)
    }
    val content = JSONArray()
        .put(JSONObject().put("type", "text").put("text", prompt))
    imageAttachments.forEach { image ->
        content.put(
            JSONObject()
                .put("type", "image_url")
                .put(
                    "image_url",
                    JSONObject()
                        .put("url", image.dataUrl),
                ),
        )
    }
    return message.put("content", content)
}

private fun remoteFailureMessage(code: Int, imageAttachments: List<ChatImageAttachment>): String =
    if (imageAttachments.isNotEmpty()) {
        when (code) {
            400, 404, 415 ->
                "图片输入请求失败，当前远程模型或接口可能不支持图片输入（HTTP $code）"

            401, 403 ->
                "远程模型认证失败，图片未发送成功（HTTP $code）"

            429 ->
                "远程模型请求受限，图片未发送成功（HTTP $code）"

            in 500..599 ->
                "远程模型服务异常，图片未发送成功（HTTP $code）"

            else ->
                "图片输入请求失败，图片未发送成功（HTTP $code）"
        }
    } else {
        "远程模型请求失败 $code"
    }

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

internal fun parseChatCompletionEvents(raw: String): List<RemoteChatEvent> {
    val json = runCatching { JSONObject(raw) }.getOrElse {
        return listOf(RemoteChatEvent.ParseError("远程模型响应不是有效 JSON"))
    }
    val choice = json.optJSONArray("choices")
        ?.optJSONObject(0)
        ?: return listOf(RemoteChatEvent.ParseError("远程模型响应为空"))
    val events = mutableListOf<RemoteChatEvent>()
    val message = choice.optJSONObject("message")
    val messageText = message
        ?.optNonNullString("content")
        .orEmpty()
        .trim()
    if (messageText.isNotBlank()) {
        events += RemoteChatEvent.TextDelta(messageText)
    }
    val toolCalls = message?.optJSONArray("tool_calls")
    val legacyFunctionCall = message?.optJSONObject("function_call")
    if (toolCalls != null && toolCalls.length() > 0 && legacyFunctionCall != null) {
        events += RemoteChatEvent.ParseError("远程模型同时返回多种工具调用格式，已拒绝执行")
    } else {
        val accumulator = RemoteToolCallAccumulator()
        var hasToolCall = false
        if (toolCalls != null && toolCalls.length() > 0) {
            hasToolCall = true
            accumulator.absorbToolCalls(toolCalls)
        }
        legacyFunctionCall?.let { functionCall ->
            hasToolCall = true
            accumulator.absorbLegacyFunctionCall(functionCall)
        }
        if (hasToolCall) {
            events += accumulator.finish()
        }
    }
    val text = choice.optNonNullString("text").trim()
    if (events.isEmpty() && text.isNotBlank()) {
        events += RemoteChatEvent.TextDelta(text)
    }
    return events.ifEmpty {
        listOf(RemoteChatEvent.ParseError("远程模型响应没有文本内容"))
    }
}

internal class RemoteToolCallAccumulator {
    private val builders = linkedMapOf<Int, ToolCallBuilder>()
    private var nextSyntheticIndex = 0
    private var sawToolCalls = false
    private var sawLegacyFunctionCall = false
    private var mixedToolCallFormats = false
    private var ambiguousUnindexedToolCallFragments = false

    fun absorb(raw: String): List<RemoteChatEvent> {
        val json = runCatching { JSONObject(raw) }.getOrElse {
            return listOf(RemoteChatEvent.ParseError("远程模型流式片段不是有效 JSON"))
        }
        val choice = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?: return emptyList()
        val delta = choice.optJSONObject("delta")
            ?: choice.optJSONObject("message")
            ?: return emptyList()
        val toolCalls = delta.optJSONArray("tool_calls")
        val legacyFunctionCall = delta.optJSONObject("function_call")
        if (toolCalls != null && toolCalls.length() > 0) {
            sawToolCalls = true
        }
        if (legacyFunctionCall != null) {
            sawLegacyFunctionCall = true
        }
        if (sawToolCalls && sawLegacyFunctionCall) {
            mixedToolCallFormats = true
            return listOf(RemoteChatEvent.ParseError("远程模型同时返回多种工具调用格式，已拒绝执行"))
        }
        val events = mutableListOf<RemoteChatEvent>()
        val text = delta.optNonNullString("content")
        if (text.isNotEmpty()) {
            events += RemoteChatEvent.TextDelta(text)
        }
        toolCalls?.let(::absorbToolCalls)
        legacyFunctionCall?.let(::absorbLegacyFunctionCall)
        return events
    }

    fun absorbToolCalls(toolCalls: JSONArray) {
        for (position in 0 until toolCalls.length()) {
            val call = toolCalls.optJSONObject(position) ?: continue
            val function = call.optJSONObject("function")
            val id = call.optNonNullString("id")
            val name = function?.optNonNullString("name").orEmpty()
            val index = resolveToolCallIndex(call, id, name)
            val builder = builders.getOrPut(index) { ToolCallBuilder() }
            if (id.isNotBlank()) builder.id = id
            if (name.isNotBlank()) builder.name = name
            builder.arguments.append(function?.optNonNullString("arguments").orEmpty())
        }
    }

    private fun resolveToolCallIndex(call: JSONObject, id: String, name: String): Int {
        if (call.has("index")) return call.optInt("index")
        if (id.isNotBlank()) {
            builders.entries.firstOrNull { (_, builder) -> builder.id == id }?.let { return it.key }
        }
        if (id.isBlank() && name.isBlank() && builders.size == 1) {
            return builders.keys.single()
        }
        if (id.isBlank() && name.isBlank() && builders.size > 1) {
            ambiguousUnindexedToolCallFragments = true
            return nextSyntheticToolCallIndex()
        }
        return nextSyntheticToolCallIndex()
    }

    private fun nextSyntheticToolCallIndex(): Int {
        while (builders.containsKey(nextSyntheticIndex)) {
            nextSyntheticIndex += 1
        }
        return nextSyntheticIndex.also { nextSyntheticIndex += 1 }
    }

    fun absorbLegacyFunctionCall(functionCall: JSONObject) {
        val builder = builders.getOrPut(0) { ToolCallBuilder() }
        val name = functionCall.optNonNullString("name")
        if (name.isNotBlank()) builder.name = name
        builder.arguments.append(functionCall.optNonNullString("arguments"))
    }

    fun finish(): List<RemoteChatEvent> {
        if (mixedToolCallFormats) {
            return listOf(RemoteChatEvent.ParseError("远程模型同时返回多种工具调用格式，已拒绝执行"))
        }
        if (ambiguousUnindexedToolCallFragments) {
            return listOf(RemoteChatEvent.ParseError("远程模型流式多工具调用缺少稳定 index，已拒绝执行"))
        }
        if (builders.isEmpty()) return emptyList()
        val requests = mutableListOf<ToolRequest>()
        builders.entries.sortedBy { (index, _) -> index }.forEach { (_, builder) ->
            val name = builder.name.trim()
            if (name.isBlank()) {
                return listOf(RemoteChatEvent.ParseError("远程模型工具调用缺少工具名称"))
            }
            val argumentsJson = builder.arguments.toString().ifBlank { "{}" }
            val arguments = argumentsJson.toToolArgumentMapOrNull()
                ?: return listOf(RemoteChatEvent.ParseError("远程模型工具参数不是有效 JSON 对象"))
            requests += ToolRequest(
                id = builder.id.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                toolName = name,
                arguments = arguments,
                reason = "remote tool call",
            )
        }
        return listOf(
            if (requests.size == 1) {
                RemoteChatEvent.ToolCall(requests.single())
            } else {
                RemoteChatEvent.ToolCalls(requests)
            },
        )
    }
}

private data class ToolCallBuilder(
    var id: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder(),
)

private fun List<ToolSpec>.toOpenAiToolsJson(): JSONArray =
    JSONArray().also { array ->
        forEach { spec ->
            array.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", spec.name)
                            .put("description", spec.description)
                            .put("parameters", JSONObject(spec.inputSchemaJson)),
                    ),
            )
        }
    }

private fun String.toToolArgumentMapOrNull(): Map<String, String>? {
    val json = runCatching { JSONObject(this) }.getOrNull() ?: return null
    val result = linkedMapOf<String, String>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (json.isNull(key)) continue
        val value = json.get(key)
        result[key] = when (value) {
            is String -> value
            is Number -> value.toString()
            is Boolean -> value.toString()
            is JSONObject, is JSONArray -> return null
            else -> return null
        }
    }
    return result
}

private fun JSONObject.optNonNullString(name: String): String =
    if (has(name) && !isNull(name)) optString(name) else ""

private fun JSONArray.appendHistory(history: List<ChatMessage>): JSONArray {
    history
        .filter { it.text.isNotBlank() && it.privacy == MessagePrivacy.RemoteEligible }
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
