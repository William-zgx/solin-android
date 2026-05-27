package com.bytedance.zgx.pocketmind.runtime

import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.GenerationParameters
import com.bytedance.zgx.pocketmind.MessageRole
import com.bytedance.zgx.pocketmind.RemoteModelConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class RemoteChatRuntime {
    fun send(
        prompt: String,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        config: RemoteModelConfig,
    ): Flow<String> = flow {
        emit(
            withContext(Dispatchers.IO) {
                requestChatCompletion(prompt, history, parameters, config.normalized())
            },
        )
    }

    private fun requestChatCompletion(
        prompt: String,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        config: RemoteModelConfig,
    ): String {
        require(config.isConfigured) { "请先配置远程模型地址和模型名" }
        val connection = (URL(config.chatCompletionsUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (config.apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
        }

        val body = buildChatCompletionBody(prompt, history, parameters, config).toString()
        connection.outputStream.use { output ->
            output.write(body.toByteArray(Charsets.UTF_8))
        }

        val responseBody = connection.responseText()
        if (connection.responseCode !in 200..299) {
            error("远程模型请求失败 ${connection.responseCode}: ${responseBody.take(180)}")
        }
        return parseChatCompletionText(responseBody)
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
        .put("stream", false)
        .put("temperature", parameters.temperature.toDouble())
        .put("top_p", parameters.topP.toDouble())
        .put(
            "messages",
            JSONArray()
                .put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", "你是一个简洁、可靠的中文问答助手。回答要直接，必要时说明不确定性。"),
                )
                .appendHistory(history)
                .put(JSONObject().put("role", "user").put("content", prompt)),
        )

internal fun parseChatCompletionText(raw: String): String {
    val json = JSONObject(raw)
    val choice = json.optJSONArray("choices")
        ?.optJSONObject(0)
        ?: error("远程模型响应为空")
    val messageText = choice.optJSONObject("message")
        ?.optString("content")
        .orEmpty()
        .trim()
    if (messageText.isNotBlank()) return messageText

    val text = choice.optString("text").trim()
    if (text.isNotBlank()) return text

    error("远程模型响应没有文本内容")
}

private fun JSONArray.appendHistory(history: List<ChatMessage>): JSONArray {
    history.forEach { message ->
        val text = message.text.takeIf { it.isNotBlank() } ?: return@forEach
        val role = when (message.role) {
            MessageRole.User -> "user"
            MessageRole.Assistant -> "assistant"
        }
        put(JSONObject().put("role", role).put("content", text))
    }
    return this
}

private fun RemoteModelConfig.chatCompletionsUrl(): String =
    if (baseUrl.endsWith("/chat/completions")) {
        baseUrl
    } else {
        "$baseUrl/chat/completions"
    }

private fun HttpURLConnection.responseText(): String {
    val source = if (responseCode in 200..299) inputStream else errorStream
    return source?.use { stream ->
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
    }.orEmpty()
}
