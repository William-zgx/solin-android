package com.bytedance.zgx.solin.testing

import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.GenerationStats
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import com.bytedance.zgx.solin.runtime.LocalModelRequest
import com.bytedance.zgx.solin.runtime.LocalModelRuntimeCapabilities
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Scripted [LiteRtRuntime] that records every call and streams [localChunks]. */
class FakeLiteRtRuntime(
    private val localResponse: String = "本地回复",
    private val localChunks: List<String> = listOf(localResponse),
    private val failure: Throwable? = null,
    private val hangDuringSend: Boolean = false,
    private val loadFailures: Map<BackendChoice, Throwable> = emptyMap(),
    private val generationStats: GenerationStats? = null,
) : LiteRtRuntime {
    val prompts = mutableListOf<String>()
    val localRequests = mutableListOf<LocalModelRequest>()
    val recreatedHistories = mutableListOf<List<ChatMessage>>()
    val preparedForSendPrompts = mutableListOf<String>()
    val preparedForSendMaxInputTokens = mutableListOf<Int?>()
    val loadCalls = mutableListOf<BackendChoice>()
    val configuredCapabilities = mutableListOf<LocalModelRuntimeCapabilities>()
    var loadedModelPath: String? = null
        private set
    var loadedParameters: GenerationParameters? = null
        private set
    var stopCallCount: Int = 0
        private set
    var closeCallCount: Int = 0
        private set
    var recreateCallCount: Int = 0
        private set
    override var isLoaded: Boolean = false

    override fun configureModelCapabilities(capabilities: LocalModelRuntimeCapabilities) {
        configuredCapabilities += capabilities
    }

    override fun load(
        modelPath: String,
        backend: BackendChoice,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
    ) {
        loadCalls += backend
        loadFailures[backend]?.let { throwable ->
            isLoaded = false
            throw throwable
        }
        loadedModelPath = modelPath
        loadedParameters = parameters
        isLoaded = true
    }

    override fun recreateConversation(history: List<ChatMessage>, parameters: GenerationParameters) {
        recreateCallCount += 1
        recreatedHistories += history
        loadedParameters = parameters
        isLoaded = true
    }

    override fun recreateConversationForSend(
        history: List<ChatMessage>,
        prompt: String,
        parameters: GenerationParameters,
        maxInputTokens: Int?,
    ) {
        preparedForSendPrompts += prompt
        preparedForSendMaxInputTokens += maxInputTokens
        recreateConversation(history, parameters)
    }

    override fun send(prompt: String): Flow<String> {
        prompts += prompt
        failure?.let { throw it }
        if (hangDuringSend) return flow { awaitCancellation() }
        return flow { localChunks.forEach { emit(it) } }
    }

    override fun send(request: LocalModelRequest): Flow<String> {
        localRequests += request
        return send(request.prompt)
    }

    override fun lastGenerationStats(): GenerationStats? = generationStats

    override fun stop() {
        stopCallCount += 1
    }

    override fun close() {
        closeCallCount += 1
        isLoaded = false
    }
}
