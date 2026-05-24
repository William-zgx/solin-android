package com.bytedance.zgx.pocketmind.runtime

import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.GenerationParameters
import com.bytedance.zgx.pocketmind.GenerationStats
import com.bytedance.zgx.pocketmind.MessageRole
import com.bytedance.zgx.pocketmind.isUsable
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface LiteRtRuntime {
    val isLoaded: Boolean

    fun load(
        modelPath: String,
        backend: BackendChoice,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
    )

    fun recreateConversation(
        history: List<ChatMessage>,
        parameters: GenerationParameters,
    )

    fun send(prompt: String): Flow<String>

    fun lastGenerationStats(): GenerationStats?

    fun stop()

    fun close()
}

class RealLiteRtRuntime(
    private val cacheDir: File,
) : LiteRtRuntime {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    override val isLoaded: Boolean
        get() = engine != null && conversation != null

    override fun load(
        modelPath: String,
        backend: BackendChoice,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
    ) {
        close()
        val createdEngine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend.toLiteRtBackend(),
                cacheDir = cacheDir.absolutePath,
            ),
        )
        try {
            createdEngine.initialize()
            engine = createdEngine
            conversation = createdEngine.createConversation(defaultConversationConfig(history, parameters))
        } catch (throwable: Throwable) {
            runCatching { createdEngine.close() }
            throw throwable
        }
    }

    override fun recreateConversation(
        history: List<ChatMessage>,
        parameters: GenerationParameters,
    ) {
        val currentEngine = engine ?: error("模型尚未就绪")
        conversation?.close()
        conversation = currentEngine.createConversation(defaultConversationConfig(history, parameters))
    }

    override fun send(prompt: String): Flow<String> {
        val activeConversation = conversation ?: error("模型尚未就绪")
        return activeConversation.sendMessageAsync(prompt)
            .map { chunk -> chunk.textContent().ifBlank { chunk.toString() } }
    }

    @OptIn(ExperimentalApi::class)
    override fun lastGenerationStats(): GenerationStats? {
        val benchmark = conversation?.getBenchmarkInfo() ?: return null
        return GenerationStats(
            tokenCount = benchmark.lastDecodeTokenCount,
            tokensPerSecond = benchmark.lastDecodeTokensPerSecond,
        ).takeIf { it.isUsable() }
    }

    override fun stop() {
        runCatching { conversation?.cancelProcess() }
    }

    override fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }

    companion object {
        fun configureNativeLogging() {
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        }
    }
}

private fun defaultConversationConfig(
    messages: List<ChatMessage>,
    parameters: GenerationParameters,
): ConversationConfig =
    ConversationConfig(
        systemInstruction = Contents.of("你是一个简洁、可靠的中文问答助手。回答要直接，必要时说明不确定性。"),
        initialMessages = messages.toLiteRtInitialMessages(),
        samplerConfig = SamplerConfig(
            topK = parameters.topK,
            topP = parameters.topP.toDouble(),
            temperature = parameters.temperature.toDouble(),
        ),
    )

private fun List<ChatMessage>.toLiteRtInitialMessages(): List<Message> =
    mapNotNull { message ->
        val text = message.text.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        when (message.role) {
            MessageRole.User -> Message.user(text)
            MessageRole.Assistant -> Message.model(text)
        }
    }

private fun BackendChoice.toLiteRtBackend(): Backend =
    when (this) {
        BackendChoice.CPU -> Backend.CPU()
        BackendChoice.GPU -> Backend.GPU()
    }

private fun com.google.ai.edge.litertlm.Message.textContent(): String =
    contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString(separator = "") { it.text }
