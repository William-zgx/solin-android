package com.bytedance.zgx.pocketmind.runtime

import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.GenerationParameters
import com.bytedance.zgx.pocketmind.GenerationStats
import com.bytedance.zgx.pocketmind.LocalModelTokenLimits
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

interface LocalChatRuntime {
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

    fun recreateConversationForSend(
        history: List<ChatMessage>,
        prompt: String,
        parameters: GenerationParameters,
    ) {
        recreateConversation(history, parameters)
    }

    fun send(prompt: String): Flow<String>

    fun lastGenerationStats(): GenerationStats?

    fun stop()

    fun close()
}

interface LiteRtRuntime : LocalChatRuntime

class RealLiteRtRuntime(
    private val cacheDir: File,
) : LiteRtRuntime {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var currentBackend: BackendChoice? = null
    private var lastLoadMs: Long? = null
    private var lastFirstTokenMs: Long? = null

    override val isLoaded: Boolean
        get() = engine != null && conversation != null

    override fun load(
        modelPath: String,
        backend: BackendChoice,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
    ) {
        close()
        val loadStartedAtNanos = System.nanoTime()
        val createdEngine = Engine(defaultEngineConfig(modelPath = modelPath, backend = backend, cacheDir = cacheDir))
        try {
            createdEngine.initialize()
            engine = createdEngine
            conversation = createdEngine.createConversation(defaultConversationConfig(history, parameters))
            currentBackend = backend
            lastLoadMs = elapsedMillisSince(loadStartedAtNanos)
            lastFirstTokenMs = null
        } catch (throwable: Throwable) {
            runCatching { createdEngine.close() }
            currentBackend = null
            lastLoadMs = null
            lastFirstTokenMs = null
            throw throwable
        }
    }

    override fun recreateConversation(
        history: List<ChatMessage>,
        parameters: GenerationParameters,
    ) {
        recreateConversationInternal(history, parameters, currentPrompt = "")
    }

    override fun recreateConversationForSend(
        history: List<ChatMessage>,
        prompt: String,
        parameters: GenerationParameters,
    ) {
        recreateConversationInternal(history, parameters, currentPrompt = prompt)
    }

    private fun recreateConversationInternal(
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        currentPrompt: String,
    ) {
        val currentEngine = engine ?: error("模型尚未就绪")
        conversation?.close()
        conversation = currentEngine.createConversation(defaultConversationConfig(history, parameters, currentPrompt))
    }

    override fun send(prompt: String): Flow<String> {
        val activeConversation = conversation ?: error("模型尚未就绪")
        val startedAtNanos = System.nanoTime()
        lastFirstTokenMs = null
        return activeConversation.sendMessageAsync(prompt)
            .map { chunk ->
                val text = chunk.textContent().ifBlank { chunk.toString() }
                if (text.isNotBlank() && lastFirstTokenMs == null) {
                    lastFirstTokenMs = elapsedMillisSince(startedAtNanos)
                }
                text
            }
    }

    @OptIn(ExperimentalApi::class)
    override fun lastGenerationStats(): GenerationStats? {
        val benchmark = runCatching {
            conversation?.getBenchmarkInfo()
        }.getOrNull() ?: return null
        return GenerationStats(
            tokenCount = benchmark.lastDecodeTokenCount,
            tokensPerSecond = benchmark.lastDecodeTokensPerSecond,
            backend = currentBackend,
            loadMs = lastLoadMs,
            firstTokenMs = lastFirstTokenMs,
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
        currentBackend = null
        lastLoadMs = null
        lastFirstTokenMs = null
    }

    companion object {
        fun configureNativeLogging() {
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        }
    }
}

private fun elapsedMillisSince(startedAtNanos: Long): Long =
    ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)

internal fun defaultEngineConfig(
    modelPath: String,
    backend: BackendChoice,
    cacheDir: File,
): EngineConfig =
    EngineConfig(
        modelPath = modelPath,
        backend = backend.toLiteRtBackend(),
        maxNumTokens = LocalModelTokenLimits.MAX_TOTAL_TOKENS,
        cacheDir = cacheDir.absolutePath,
    )

private fun defaultConversationConfig(
    messages: List<ChatMessage>,
    parameters: GenerationParameters,
    currentPrompt: String = "",
): ConversationConfig =
    ConversationConfig(
        systemInstruction = Contents.of(DEFAULT_CHAT_SYSTEM_INSTRUCTION),
        initialMessages = budgetLocalRuntimeHistory(messages, currentPrompt).toLiteRtInitialMessages(),
        samplerConfig = SamplerConfig(
            topK = parameters.topK,
            topP = parameters.topP.toDouble(),
            temperature = parameters.temperature.toDouble(),
        ),
    )

internal fun budgetLocalRuntimeHistory(
    messages: List<ChatMessage>,
    currentPrompt: String = "",
): List<ChatMessage> {
    val promptReserve = maxOf(
        LocalModelTokenLimits.CURRENT_PROMPT_TOKEN_RESERVE,
        estimateLocalRuntimeTokens(currentPrompt),
    )
    val historyBudget = (
        LocalModelTokenLimits.MAX_TOTAL_TOKENS -
            LocalModelTokenLimits.OUTPUT_TOKEN_RESERVE -
            LocalModelTokenLimits.SYSTEM_PROMPT_TOKEN_RESERVE -
            promptReserve
        ).coerceAtLeast(0)
    if (historyBudget <= 0 || messages.isEmpty()) return emptyList()

    var usedTokens = 0
    val selected = ArrayDeque<ChatMessage>()
    for (message in messages.asReversed()) {
        val cost = message.estimatedLocalRuntimeTokens()
        if (usedTokens + cost <= historyBudget) {
            selected.addFirst(message)
            usedTokens += cost
            continue
        }
        val remaining = historyBudget - usedTokens - MESSAGE_TOKEN_OVERHEAD
        if (remaining > MIN_TRIMMED_MESSAGE_TOKENS) {
            val trimmed = message.text.takeLastEstimatedLocalTokens(remaining)
            if (trimmed.isNotBlank()) {
                selected.addFirst(message.copy(text = trimmed))
            }
        }
        break
    }
    return selected.toList()
}

private fun List<ChatMessage>.toLiteRtInitialMessages(): List<Message> =
    mapNotNull { message ->
        val text = message.text.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        when (message.role) {
            MessageRole.User -> Message.user(text)
            MessageRole.Assistant -> Message.model(text)
        }
    }

internal fun estimateLocalRuntimeTokens(text: String): Int {
    if (text.isBlank()) return 0
    var tokens = 0
    var asciiRunLength = 0
    fun flushAsciiRun() {
        if (asciiRunLength > 0) {
            tokens += (asciiRunLength + ASCII_CHARS_PER_TOKEN - 1) / ASCII_CHARS_PER_TOKEN
            asciiRunLength = 0
        }
    }
    text.forEach { char ->
        when {
            char.isWhitespace() -> {
                flushAsciiRun()
                tokens += 1
            }
            char.code in 0x20..0x7E -> asciiRunLength += 1
            else -> {
                flushAsciiRun()
                tokens += 1
            }
        }
    }
    flushAsciiRun()
    return tokens
}

private fun ChatMessage.estimatedLocalRuntimeTokens(): Int =
    estimateLocalRuntimeTokens(text) + MESSAGE_TOKEN_OVERHEAD

private fun String.takeLastEstimatedLocalTokens(maxTokens: Int): String {
    if (maxTokens <= 0) return ""
    var usedTokens = 0
    var index = length
    while (index > 0 && usedTokens < maxTokens) {
        val previous = index - 1
        val cost = if (this[previous].code in 0x20..0x7E && !this[previous].isWhitespace()) {
            1
        } else {
            1
        }
        if (usedTokens + cost > maxTokens) break
        usedTokens += cost
        index = previous
    }
    return substring(index).trimStart()
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

private const val MESSAGE_TOKEN_OVERHEAD = 4
private const val ASCII_CHARS_PER_TOKEN = 4
private const val MIN_TRIMMED_MESSAGE_TOKENS = 64
