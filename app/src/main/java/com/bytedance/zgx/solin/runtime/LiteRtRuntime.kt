package com.bytedance.zgx.solin.runtime

import android.util.Log
import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.GenerationStats
import com.bytedance.zgx.solin.LocalModelTokenLimits
import com.bytedance.zgx.solin.LocalImageAttachment
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.ModelCapabilityProfile
import com.bytedance.zgx.solin.isUsable
import com.bytedance.zgx.solin.orchestration.CompactionTrigger
import com.bytedance.zgx.solin.orchestration.ContextCompactor
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

data class LocalModelRequest(
    val prompt: String,
    val imageAttachments: List<LocalImageAttachment> = emptyList(),
)

/**
 * Outcome of reading the LiteRT decode benchmark for the most recent generation.
 *
 * [tokensPerSecond] is only ever populated from the LiteRT benchmark API
 * ([com.google.ai.edge.litertlm.Conversation.getBenchmarkInfo]). It is never derived from
 * character counts, UI text length, or any other estimate. When the benchmark is missing or
 * reports non-usable numbers, callers receive an explicit [Unavailable] reason instead so the
 * RC perf harness can fail with a diagnosable cause rather than fabricating throughput.
 */
sealed interface RuntimeBenchmarkResult {
    data class Available(
        val tokenCount: Int,
        val tokensPerSecond: Double,
    ) : RuntimeBenchmarkResult {
        init {
            require(tokenCount > 0) { "benchmark token count must be positive" }
            require(
                tokensPerSecond > 0.0 && !tokensPerSecond.isNaN() && !tokensPerSecond.isInfinite(),
            ) { "benchmark tokensPerSecond must be a positive finite value" }
        }
    }

    data class Unavailable(val reason: String) : RuntimeBenchmarkResult
}

data class LocalModelRuntimeCapabilities(
    val supportsVisionInput: Boolean = false,
    val contextWindowTokens: Int = LocalModelTokenLimits.MAX_TOTAL_TOKENS,
    val preferredBackends: Set<BackendChoice> = emptySet(),
) {
    init {
        require(contextWindowTokens > 0) { "Local model context window must be positive" }
    }

    companion object {
        fun fromProfile(profile: ModelCapabilityProfile?): LocalModelRuntimeCapabilities =
            LocalModelRuntimeCapabilities(
                supportsVisionInput = profile?.supportsVisionInput == true,
                contextWindowTokens = LocalModelTokenLimits.contextWindowTokensFor(profile),
                preferredBackends = profile?.preferredLocalBackends.orEmpty(),
            )
    }
}

interface LocalChatRuntime {
    val isLoaded: Boolean

    fun configureModelCapabilities(
        capabilities: LocalModelRuntimeCapabilities,
    ) = Unit

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
        maxInputTokens: Int? = null,
    ) {
        recreateConversation(history, parameters)
    }

    fun send(prompt: String): Flow<String>

    fun send(request: LocalModelRequest): Flow<String> =
        send(request.prompt)

    fun lastGenerationStats(): GenerationStats?

    /**
     * Real model load time of the most recent successful [load], in milliseconds, or null when no
     * load has completed. Exposed independently of [lastGenerationStats] so the RC perf harness can
     * report `modelLoadMs` even when the decode benchmark is unavailable.
     */
    fun lastLoadMillis(): Long? = null

    /**
     * Real elapsed time from sending a request to the first non-blank chunk of the most recent
     * generation, in milliseconds, or null when no first token has been observed. Reported
     * independently of the decode benchmark.
     */
    fun lastFirstTokenMillis(): Long? = null

    /**
     * The LiteRT decode benchmark for the most recent generation, or an explicit
     * [RuntimeBenchmarkResult.Unavailable] reason. [tokensPerSecond] is only ever the raw LiteRT
     * benchmark value; it is never estimated.
     */
    fun lastBenchmarkResult(): RuntimeBenchmarkResult =
        RuntimeBenchmarkResult.Unavailable("benchmark not supported by runtime")

    fun stop()

    fun close()
}

typealias LiteRtRuntime = LocalChatRuntime

object DisabledLiteRtRuntime : LocalChatRuntime {
    private fun unavailable(): Nothing =
        throw UnsupportedOperationException("Local LiteRT runtime is disabled for this debug launch")

    override val isLoaded: Boolean = false

    override fun load(
        modelPath: String,
        backend: BackendChoice,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
    ) {
        unavailable()
    }

    override fun recreateConversation(
        history: List<ChatMessage>,
        parameters: GenerationParameters,
    ) = Unit

    override fun send(prompt: String): Flow<String> = unavailable()

    override fun lastGenerationStats(): GenerationStats? = null

    override fun stop() = Unit

    override fun close() = Unit
}

/**
 * Minimal seam over the native LiteRT [Conversation] so the runtime's lifecycle bookkeeping can
 * be exercised on the JVM without loading the native engine.
 */
internal interface LiteRtConversationHandle {
    fun sendMessageAsync(request: LocalModelRequest): Flow<String>

    @OptIn(ExperimentalApi::class)
    fun benchmarkInfo(): BenchmarkInfo

    fun cancelProcess()

    fun close()
}

/** Minimal seam over the native LiteRT [Engine]; see [LiteRtConversationHandle]. */
internal interface LiteRtEngineHandle {
    fun initialize()

    fun createConversation(config: LiteRtConversationSpec): LiteRtConversationHandle

    fun close()
}

internal data class LiteRtConversationSpec(
    val history: List<ChatMessage>,
    val parameters: GenerationParameters,
    val currentPrompt: String = "",
    val maxInputTokens: Int? = null,
    /** Overrides the default chat system instruction (used by shared-engine one-shots). */
    val systemInstruction: String? = null,
)

internal fun interface LiteRtEngineFactory {
    fun create(
        modelPath: String,
        backend: BackendChoice,
        cacheDir: File,
        capabilities: LocalModelRuntimeCapabilities,
    ): LiteRtEngineHandle
}

internal object NativeLiteRtEngineFactory : LiteRtEngineFactory {
    override fun create(
        modelPath: String,
        backend: BackendChoice,
        cacheDir: File,
        capabilities: LocalModelRuntimeCapabilities,
    ): LiteRtEngineHandle =
        NativeLiteRtEngineHandle(
            Engine(
                defaultEngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    cacheDir = cacheDir,
                    capabilities = capabilities,
                ),
            ),
        )
}

private class NativeLiteRtEngineHandle(
    private val engine: Engine,
) : LiteRtEngineHandle {
    override fun initialize() {
        engine.initialize()
    }

    override fun createConversation(config: LiteRtConversationSpec): LiteRtConversationHandle =
        NativeLiteRtConversationHandle(
            engine.createConversation(
                defaultConversationConfig(
                    messages = config.history,
                    parameters = config.parameters,
                    currentPrompt = config.currentPrompt,
                    maxInputTokens = config.maxInputTokens,
                    systemInstruction = config.systemInstruction,
                ),
            ),
        )

    override fun close() {
        engine.close()
    }
}

private class NativeLiteRtConversationHandle(
    private val conversation: Conversation,
) : LiteRtConversationHandle {
    override fun sendMessageAsync(request: LocalModelRequest): Flow<String> {
        val responseFlow = if (request.imageAttachments.isEmpty()) {
            conversation.sendMessageAsync(request.prompt)
        } else {
            conversation.sendMessageAsync(request.toLiteRtContents())
        }
        return responseFlow.map { chunk -> chunk.textContent().ifBlank { chunk.toString() } }
    }

    @OptIn(ExperimentalApi::class)
    override fun benchmarkInfo(): BenchmarkInfo = conversation.getBenchmarkInfo()

    override fun cancelProcess() {
        conversation.cancelProcess()
    }

    override fun close() {
        conversation.close()
    }
}

class RealLiteRtRuntime internal constructor(
    private val cacheDir: File,
    private val contextCompactor: ContextCompactor?,
    private val engineFactory: LiteRtEngineFactory,
) : LocalChatRuntime, SharedLocalEngineProvider {
    constructor(
        cacheDir: File,
        contextCompactor: ContextCompactor? = null,
    ) : this(cacheDir, contextCompactor, NativeLiteRtEngineFactory)

    private var loadedModelPath: String? = null
    private var lastConversationSpec: LiteRtConversationSpec? = null
    private val activeGenerations = AtomicInteger(0)
    private val oneShotInFlight = AtomicBoolean(false)

    private var engine: LiteRtEngineHandle? = null
    private var conversation: LiteRtConversationHandle? = null
    private var currentBackend: BackendChoice? = null
    private var lastLoadMs: Long? = null
    private var lastFirstTokenMs: Long? = null
    private var capabilities: LocalModelRuntimeCapabilities = LocalModelRuntimeCapabilities()

    override val isLoaded: Boolean
        get() = engine != null && conversation != null

    override fun configureModelCapabilities(
        capabilities: LocalModelRuntimeCapabilities,
    ) {
        this.capabilities = capabilities
    }

    override fun load(
        modelPath: String,
        backend: BackendChoice,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
    ) {
        close()
        val loadStartedAtNanos = System.nanoTime()
        val createdEngine = engineFactory.create(
            modelPath = modelPath,
            backend = backend,
            cacheDir = cacheDir,
            capabilities = capabilities,
        )
        try {
            createdEngine.initialize()
            val loadSpec = LiteRtConversationSpec(
                history,
                parameters,
                maxInputTokens = profileMaxInputTokens(),
            )
            val createdConversation = createdEngine.createConversation(loadSpec)
            lastConversationSpec = loadSpec
            // Publish the native handles only once both exist, so a failed load never leaves a
            // closed engine reachable from recreate/close.
            engine = createdEngine
            conversation = createdConversation
            loadedModelPath = modelPath
            currentBackend = backend
            lastLoadMs = elapsedMillisSince(loadStartedAtNanos)
            lastFirstTokenMs = null
        } catch (throwable: Throwable) {
            runCatching { createdEngine.close() }
            engine = null
            conversation = null
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
        maxInputTokens: Int?,
    ) {
        recreateConversationInternal(history, parameters, currentPrompt = prompt, maxInputTokens = maxInputTokens)
    }

    private fun recreateConversationInternal(
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        currentPrompt: String,
        maxInputTokens: Int? = null,
    ) {
        val currentEngine = engine ?: error("模型尚未就绪")
        check(!oneShotInFlight.get()) { LOCAL_ENGINE_BUSY_MESSAGE }
        // Wave 2: opt-in context compaction. If a compactor is supplied and a token budget is
        // known, run it BEFORE constructing the ConversationConfig. The existing
        // budgetLocalRuntimeHistory heuristic still runs afterwards as a safety net (coexistence).
        // System instruction is out-of-band via ConversationConfig.systemInstruction, so the
        // history passed here contains only user/assistant turns — no system prefix to preserve.
        val compactedHistory = compactHistoryIfRequested(history, currentPrompt, maxInputTokens)
        // Detach the old conversation before closing it: if creating the replacement fails, the
        // runtime must report not-loaded instead of keeping a closed native conversation.
        val previousConversation = conversation
        conversation = null
        previousConversation?.close()
        val spec = LiteRtConversationSpec(
            compactedHistory,
            parameters,
            currentPrompt,
            maxInputTokens ?: profileMaxInputTokens(),
        )
        conversation = currentEngine.createConversation(spec)
        lastConversationSpec = spec
    }

    /**
     * History budget when the caller supplies none: derived from the configured model profile
     * window (8K fail-safe default) instead of the static 8K input budget.
     */
    private fun profileMaxInputTokens(): Int =
        LocalModelTokenLimits.maxInputTokensFor(capabilities.contextWindowTokens)

    /**
     * Run [ContextCompactor.compact] on [history] when configured. Uses [runBlocking] because
     * the Wave 2 heuristic impl is purely CPU-bound and synchronous; the `suspend` modifier on
     * [ContextCompactor.compact] is forward-compatibility for future LLM-based compactors.
     *
     * Logs via [Log.d] when compaction actually collapses messages. TelemetrySink wiring is
     * deferred to a later wave to avoid constructor-parameter ripple.
     */
    private fun compactHistoryIfRequested(
        history: List<ChatMessage>,
        currentPrompt: String,
        maxInputTokens: Int?,
    ): List<ChatMessage> {
        val compactor = contextCompactor ?: return history
        val budget = maxInputTokens ?: return history
        if (history.isEmpty()) return history
        // Reserve budget for system prompt + current prompt (mirrors budgetLocalRuntimeHistory).
        val promptReserve = maxOf(
            LocalModelTokenLimits.CURRENT_PROMPT_TOKEN_RESERVE,
            estimateLocalRuntimeTokens(currentPrompt),
        )
        val historyBudget = (
            budget -
                LocalModelTokenLimits.SYSTEM_PROMPT_TOKEN_RESERVE -
                promptReserve
            ).coerceAtLeast(0)
        if (historyBudget <= 0) return history
        return try {
            val result = runBlocking {
                compactor.compact(
                    messages = history,
                    tokenBudget = historyBudget,
                    estimatedTokens = { msgs -> msgs.sumOf { it.estimatedLocalRuntimeTokens() } },
                )
            }
            if (result.compactionCount > 0) {
                Log.d(
                    TAG,
                    "Context compaction: trigger=${result.triggerReason} " +
                        "count=${result.compactionCount} " +
                        "tokensBefore=${result.tokensBefore} tokensAfter=${result.tokensAfter} " +
                        "budget=$historyBudget",
                )
            }
            result.messages
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Log.w(TAG, "Context compaction failed, falling through to budget heuristic", t)
            history
        }
    }

    override fun send(prompt: String): Flow<String> =
        send(LocalModelRequest(prompt = prompt))

    override fun send(request: LocalModelRequest): Flow<String> {
        check(!oneShotInFlight.get()) { LOCAL_ENGINE_BUSY_MESSAGE }
        val activeConversation = conversation ?: error("模型尚未就绪")
        if (request.imageAttachments.isNotEmpty() && !capabilities.supportsVisionInput) {
            error("当前本地模型不支持图片输入")
        }
        val startedAtNanos = System.nanoTime()
        lastFirstTokenMs = null
        return activeConversation.sendMessageAsync(request)
            .onStart { activeGenerations.incrementAndGet() }
            .onCompletion { activeGenerations.decrementAndGet() }
            .map { text ->
                if (text.isNotBlank() && lastFirstTokenMs == null) {
                    lastFirstTokenMs = elapsedMillisSince(startedAtNanos)
                }
                text
            }
    }

    /**
     * [SharedLocalEngineProvider] implementation: runs one short generation on the loaded engine
     * so the action planner does not need its own native Engine. See [SharedLocalEngineProvider]
     * for the contract; this never preempts a chat generation and always restores the chat
     * conversation from its last spec.
     */
    override fun generateOneShot(request: SharedEngineOneShotRequest): SharedEngineOneShotResult {
        val currentEngine = engine
            ?: return SharedEngineOneShotResult.Unavailable(SharedEngineUnavailableReason.NoEngineLoaded)
        if (loadedModelPath != request.modelPath) {
            return SharedEngineOneShotResult.Unavailable(SharedEngineUnavailableReason.DifferentModel)
        }
        if (activeGenerations.get() > 0 || !oneShotInFlight.compareAndSet(false, true)) {
            return SharedEngineOneShotResult.Unavailable(SharedEngineUnavailableReason.Busy)
        }
        try {
            // LiteRT-LM engines host one conversation at a time: park the chat conversation.
            val parked = conversation
            conversation = null
            parked?.close()
            return try {
                val oneShot = currentEngine.createConversation(
                    LiteRtConversationSpec(
                        history = emptyList(),
                        parameters = GenerationParameters(
                            temperature = request.temperature,
                            topP = request.topP,
                            topK = request.topK,
                        ),
                        systemInstruction = request.systemInstruction,
                    ),
                )
                try {
                    val text = runBlocking {
                        oneShot.sendMessageAsync(LocalModelRequest(prompt = request.prompt))
                            .toList()
                            .joinToString(separator = "")
                    }
                    SharedEngineOneShotResult.Generated(text)
                } finally {
                    oneShot.close()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                SharedEngineOneShotResult.Unavailable(
                    SharedEngineUnavailableReason.Failed,
                    throwable.message ?: throwable.javaClass.simpleName,
                )
            } finally {
                conversation = runCatching {
                    currentEngine.createConversation(
                        lastConversationSpec ?: LiteRtConversationSpec(emptyList(), GenerationParameters()),
                    )
                }.getOrNull()
            }
        } finally {
            oneShotInFlight.set(false)
        }
    }

    @OptIn(ExperimentalApi::class)
    override fun lastGenerationStats(): GenerationStats? {
        val benchmark = lastBenchmarkResult() as? RuntimeBenchmarkResult.Available ?: return null
        return GenerationStats(
            tokenCount = benchmark.tokenCount,
            tokensPerSecond = benchmark.tokensPerSecond,
            backend = currentBackend,
            loadMs = lastLoadMs,
            firstTokenMs = lastFirstTokenMs,
        ).takeIf { it.isUsable() }
    }

    override fun lastLoadMillis(): Long? = lastLoadMs

    override fun lastFirstTokenMillis(): Long? = lastFirstTokenMs

    @OptIn(ExperimentalApi::class)
    override fun lastBenchmarkResult(): RuntimeBenchmarkResult {
        val conversation = conversation
            ?: return RuntimeBenchmarkResult.Unavailable("no active conversation")
        val benchmark = runCatching { conversation.benchmarkInfo() }
            .getOrElse { throwable ->
                return RuntimeBenchmarkResult.Unavailable(
                    "getBenchmarkInfo failed: ${throwable.message ?: throwable::class.java.simpleName}",
                )
            }
        return benchmark.toRuntimeBenchmarkResult()
    }

    override fun stop() {
        stopWithResult()
    }

    fun stopWithResult(): Result<Unit> =
        runCatching { conversation?.cancelProcess() }

    override fun close() {
        val closingConversation = conversation
        val closingEngine = engine
        conversation = null
        engine = null
        loadedModelPath = null
        lastConversationSpec = null
        currentBackend = null
        lastLoadMs = null
        lastFirstTokenMs = null
        try {
            closingConversation?.close()
        } finally {
            closingEngine?.close()
        }
    }

    companion object {
        private const val TAG = "LiteRtRuntime"
        private const val LOCAL_ENGINE_BUSY_MESSAGE = "本地模型正忙，请稍后重试"

        fun configureNativeLogging() {
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        }
    }
}

internal fun BenchmarkInfo.toRuntimeBenchmarkResult(): RuntimeBenchmarkResult {
    val tokenCount = lastDecodeTokenCount
    val tokensPerSecond = lastDecodeTokensPerSecond
    if (tokenCount <= 0) {
        return RuntimeBenchmarkResult.Unavailable("benchmark reported non-positive token count")
    }
    if (tokensPerSecond <= 0.0 || tokensPerSecond.isNaN() || tokensPerSecond.isInfinite()) {
        return RuntimeBenchmarkResult.Unavailable(
            "benchmark reported non-usable tokensPerSecond",
        )
    }
    return RuntimeBenchmarkResult.Available(
        tokenCount = tokenCount,
        tokensPerSecond = tokensPerSecond,
    )
}

private fun elapsedMillisSince(startedAtNanos: Long): Long =
    ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)

internal data class LiteRtEngineConfigSpec(
    val modelPath: String,
    val backend: BackendChoice,
    val visionBackend: BackendChoice?,
    val maxNumTokens: Int,
    val maxNumImages: Int?,
    val cacheDir: String,
)

internal fun defaultEngineConfigSpec(
    modelPath: String,
    backend: BackendChoice,
    cacheDir: File,
    capabilities: LocalModelRuntimeCapabilities = LocalModelRuntimeCapabilities(),
): LiteRtEngineConfigSpec =
    LiteRtEngineConfigSpec(
        modelPath = modelPath,
        backend = backend,
        visionBackend = if (capabilities.supportsVisionInput) backend else null,
        maxNumTokens = capabilities.contextWindowTokens,
        maxNumImages = if (capabilities.supportsVisionInput) MAX_LOCAL_MODEL_IMAGES else null,
        cacheDir = cacheDir.absolutePath,
    )

internal fun defaultEngineConfigSpec(
    modelPath: String,
    backend: BackendChoice,
    cacheDir: File,
    supportsVisionInput: Boolean,
): LiteRtEngineConfigSpec =
    defaultEngineConfigSpec(
        modelPath = modelPath,
        backend = backend,
        cacheDir = cacheDir,
        capabilities = LocalModelRuntimeCapabilities(supportsVisionInput = supportsVisionInput),
    )

internal fun defaultEngineConfig(
    modelPath: String,
    backend: BackendChoice,
    cacheDir: File,
    capabilities: LocalModelRuntimeCapabilities = LocalModelRuntimeCapabilities(),
): EngineConfig {
    val spec = defaultEngineConfigSpec(
        modelPath = modelPath,
        backend = backend,
        cacheDir = cacheDir,
        capabilities = capabilities,
    )
    return EngineConfig(
        modelPath = spec.modelPath,
        backend = spec.backend.toLiteRtBackend(),
        visionBackend = spec.visionBackend?.toLiteRtBackend(),
        maxNumTokens = spec.maxNumTokens,
        maxNumImages = spec.maxNumImages,
        cacheDir = spec.cacheDir,
    )
}

private fun defaultConversationConfig(
    messages: List<ChatMessage>,
    parameters: GenerationParameters,
    currentPrompt: String = "",
    maxInputTokens: Int? = null,
    systemInstruction: String? = null,
): ConversationConfig =
    ConversationConfig(
        systemInstruction = Contents.of(systemInstruction ?: DEFAULT_CHAT_SYSTEM_INSTRUCTION),
        initialMessages = budgetLocalRuntimeHistory(messages, currentPrompt, maxInputTokens).toLiteRtInitialMessages(),
        samplerConfig = SamplerConfig(
            topK = parameters.topK,
            topP = parameters.topP.toDouble(),
            temperature = parameters.temperature.toDouble(),
        ),
    )

internal fun budgetLocalRuntimeHistory(
    messages: List<ChatMessage>,
    currentPrompt: String = "",
    maxInputTokens: Int? = null,
): List<ChatMessage> {
    val promptReserve = maxOf(
        LocalModelTokenLimits.CURRENT_PROMPT_TOKEN_RESERVE,
        estimateLocalRuntimeTokens(currentPrompt),
    )
    val inputBudget = (maxInputTokens ?: LocalModelTokenLimits.MAX_INPUT_TOKENS)
        .coerceAtLeast(0)
    val historyBudget = (
        inputBudget -
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

private fun LocalModelRequest.toLiteRtContents(): Contents =
    Contents.of(
        buildList {
            if (prompt.isNotBlank()) add(Content.Text(prompt))
            imageAttachments.take(MAX_LOCAL_MODEL_IMAGES).forEach { image ->
                add(Content.ImageBytes(image.bytes))
            }
        },
    )

private fun com.google.ai.edge.litertlm.Message.textContent(): String =
    contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString(separator = "") { it.text }

private const val MESSAGE_TOKEN_OVERHEAD = 4
private const val MAX_LOCAL_MODEL_IMAGES = 5
private const val ASCII_CHARS_PER_TOKEN = 4
private const val MIN_TRIMMED_MESSAGE_TOKENS = 64
