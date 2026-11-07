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
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

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

/**
 * The chat side of a local backend: one loaded model, one live conversation, streamed sends. It is
 * also the [LocalEngine] the planners share; runtimes without one-shots inherit the Unsupported
 * defaults below.
 */
interface LocalChatRuntime : LocalEngine {
    val isLoaded: Boolean

    override fun engineCapabilities(): LocalEngineCapabilities =
        LocalEngineCapabilities(
            backendName = "none",
            accelerators = emptySet(),
            maxContextTokens = LocalModelTokenLimits.MAX_TOTAL_TOKENS,
        )

    override fun generateOneShot(request: LocalOneShotRequest): LocalOneShotResult =
        LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.Unsupported, "runtime serves no one-shots")

    fun configureModelCapabilities(
        capabilities: LocalModelRuntimeCapabilities,
    ) = Unit

    fun load(
        modelPath: String,
        backend: BackendChoice,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
    )

    /** Always starts a fresh conversation from [history]. */
    fun recreateConversation(
        history: List<ChatMessage>,
        parameters: GenerationParameters,
    )

    /**
     * Prepares the conversation for sending [prompt] after [history]. A runtime may keep the live
     * conversation, and its KV cache, when that would hold exactly the same content.
     */
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

    /** Measurements of the most recent finished chat send, or null before the first one. */
    fun lastCallMetrics(): LocalCallMetrics? = null

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

/** One streamed piece of a one-shot: text, plus any native tool calls the model emitted. */
internal data class LiteRtChunk(
    val text: String,
    val toolCalls: List<LocalToolCall> = emptyList(),
)

/**
 * Minimal seam over the native LiteRT [Conversation] so the runtime's lifecycle bookkeeping can
 * be exercised on the JVM without loading the native engine.
 */
internal interface LiteRtConversationHandle {
    fun sendMessageAsync(request: LocalModelRequest): Flow<String>

    /** A one-shot send; only a conversation created with tools can return tool calls. */
    fun sendOneShot(prompt: String): Flow<LiteRtChunk> =
        sendMessageAsync(LocalModelRequest(prompt = prompt)).map { text -> LiteRtChunk(text) }

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
    /** Overrides the default chat system instruction (one-shots). */
    val systemInstruction: String? = null,
    /** Declared to the model only; automatic tool calling stays off, so the engine runs none. */
    val tools: List<LocalToolDeclaration> = emptyList(),
)

/** The messages a conversation built from this spec starts with: budgeted, blanks dropped. */
internal fun LiteRtConversationSpec.initialMessages(): List<ChatMessage> =
    budgetLocalRuntimeHistory(history, currentPrompt, maxInputTokens).filter { message -> message.text.isNotBlank() }

internal fun interface LiteRtEngineFactory {
    /** [capabilities] null keeps the model file's own defaults: its token window and no vision. */
    fun create(
        modelPath: String,
        backend: BackendChoice,
        cacheDir: File,
        capabilities: LocalModelRuntimeCapabilities?,
    ): LiteRtEngineHandle
}

internal object NativeLiteRtEngineFactory : LiteRtEngineFactory {
    override fun create(
        modelPath: String,
        backend: BackendChoice,
        cacheDir: File,
        capabilities: LocalModelRuntimeCapabilities?,
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
        NativeLiteRtConversationHandle(engine.createConversation(config.toConversationConfig()))

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

    override fun sendOneShot(prompt: String): Flow<LiteRtChunk> =
        conversation.sendMessageAsync(prompt).map { message ->
            LiteRtChunk(
                text = message.textContent(),
                toolCalls = message.toolCalls.map { call ->
                    LocalToolCall(name = call.name, argumentsJson = JSONObject(call.arguments).toString())
                },
            )
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

/** Declares a tool to the model. Automatic tool calling is off, so the engine never calls [execute]. */
private class DeclaredOnlyTool(
    private val declaration: LocalToolDeclaration,
) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String =
        JSONObject()
            .put("name", declaration.name)
            .put("description", declaration.description)
            .put("parameters", JSONObject(declaration.parametersJsonSchema))
            .toString()

    override fun execute(paramsJsonString: String): String =
        throw IllegalStateException("Solin never lets the engine run a tool")
}

/** Bounds on how long engine calls wait for each other; tests shorten them. */
internal data class LocalEngineWaits(
    /** How long a one-shot waits for chat, a load, or another one-shot to give the engine back. */
    val engineWaitMs: Long = 2_000,
    /** How long chat waits for a one-shot it cancelled; chat may run on the main thread. */
    val reclaimWaitMs: Long = 3_000,
    /** Upper bound on one one-shot generation; the native decode is cancelled after it. */
    val oneShotTimeoutMs: Long = 120_000,
)

/**
 * LiteRT-LM backend. One native [Engine] per loaded model serves both chat and [generateOneShot],
 * so a planner that uses the chat model does not map a second copy of it.
 *
 * Concurrency: [lock] guards all state and every native call that creates or closes a conversation
 * or the engine. The engine hosts one conversation at a time, so a one-shot parks the chat
 * conversation and the next chat use recreates it from what it held. Chat outranks one-shots: a
 * one-shot waits (bounded) for a chat generation and returns Busy, and a chat call cancels a
 * running one-shot and waits (bounded) for it to give the engine back.
 *
 * KV reuse: [recreateConversationForSend] keeps the live conversation when the system instruction,
 * sampling and the budgeted history equal what the conversation already holds. A cancelled, failed
 * or image-bearing send, a one-shot, a model load, or [close] ends the reuse.
 *
 * [fallbackEngine], the planners' dedicated engine, gives up its copy of a model before this
 * runtime loads that model, so the model is not mapped twice.
 */
class RealLiteRtRuntime internal constructor(
    private val cacheDir: File,
    private val contextCompactor: ContextCompactor?,
    private val engineFactory: LiteRtEngineFactory,
    private val waits: LocalEngineWaits = LocalEngineWaits(),
    private val fallbackEngine: DedicatedLiteRtEngine? = null,
) : LocalChatRuntime {
    constructor(
        cacheDir: File,
        contextCompactor: ContextCompactor? = null,
        fallbackEngine: DedicatedLiteRtEngine? = null,
    ) : this(cacheDir, contextCompactor, NativeLiteRtEngineFactory, fallbackEngine = fallbackEngine)

    private val lock = ReentrantLock()

    /** Signalled whenever a chat generation or a one-shot gives the engine back. */
    private val engineReleased = lock.newCondition()

    @Volatile private var engine: LiteRtEngineHandle? = null
    private var loadedModelPath: String? = null
    private var currentBackend: BackendChoice? = null

    @Volatile private var chat: ChatConversation? = null

    /** The chat handle [stop] cancels; read without [lock] so stopping never waits on a load. */
    @Volatile private var liveChatHandle: LiteRtConversationHandle? = null
    private var chatGenerating = false
    private var oneShot: LiteRtConversationHandle? = null
    private var oneShotReclaimed = false

    /** The last [recreateConversationForSend] kept the live conversation for the next send. */
    private var continueOnNextSend = false

    @Volatile private var lastLoadMs: Long? = null
    @Volatile private var lastFirstTokenMs: Long? = null
    @Volatile private var lastMetrics: LocalCallMetrics? = null
    @Volatile private var capabilities: LocalModelRuntimeCapabilities = LocalModelRuntimeCapabilities()

    override val isLoaded: Boolean
        get() = engine != null && chat != null

    override fun configureModelCapabilities(
        capabilities: LocalModelRuntimeCapabilities,
    ) {
        lock.withLock {
            // A changed window or vision setting means the live conversation no longer matches what
            // the next send would build, so the next send recreates it.
            if (this.capabilities != capabilities) chat?.reusable = false
            this.capabilities = capabilities
        }
    }

    override fun engineCapabilities(): LocalEngineCapabilities =
        LocalEngineCapabilities(
            backendName = LITERT_BACKEND_NAME,
            accelerators = setOf(LocalAccelerator.CPU, LocalAccelerator.GPU),
            maxContextTokens = capabilities.contextWindowTokens,
            visionInput = capabilities.supportsVisionInput,
            nativeToolCalling = true,
        )

    override fun load(
        modelPath: String,
        backend: BackendChoice,
        history: List<ChatMessage>,
        parameters: GenerationParameters,
    ) {
        lock.withLock {
            reclaimFromOneShotLocked()
            closeLocked()
            fallbackEngine?.releaseIfHolding(modelPath)
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
                // Publish the native handles only once both exist, so a failed load never leaves a
                // closed engine reachable from recreate/close.
                engine = createdEngine
                loadedModelPath = modelPath
                currentBackend = backend
                publishChatLocked(ChatConversation(createdConversation, loadSpec))
                lastLoadMs = elapsedMillisSince(loadStartedAtNanos)
                lastFirstTokenMs = null
            } catch (throwable: Throwable) {
                runCatching { createdEngine.close() }
                lastLoadMs = null
                lastFirstTokenMs = null
                throw throwable
            }
        }
    }

    override fun recreateConversation(
        history: List<ChatMessage>,
        parameters: GenerationParameters,
    ) {
        recreateConversationInternal(history, parameters, currentPrompt = "", maxInputTokens = null, mayContinue = false)
    }

    override fun recreateConversationForSend(
        history: List<ChatMessage>,
        prompt: String,
        parameters: GenerationParameters,
        maxInputTokens: Int?,
    ) {
        recreateConversationInternal(history, parameters, prompt, maxInputTokens, mayContinue = true)
    }

    private fun recreateConversationInternal(
        history: List<ChatMessage>,
        parameters: GenerationParameters,
        currentPrompt: String,
        maxInputTokens: Int?,
        mayContinue: Boolean,
    ) {
        if (engine == null) error("模型尚未就绪")
        // Wave 2: opt-in context compaction. If a compactor is supplied and a token budget is
        // known, run it BEFORE constructing the ConversationConfig. The existing
        // budgetLocalRuntimeHistory heuristic still runs afterwards as a safety net (coexistence).
        // System instruction is out-of-band via ConversationConfig.systemInstruction, so the
        // history passed here contains only user/assistant turns — no system prefix to preserve.
        val compactedHistory = compactHistoryIfRequested(history, currentPrompt, maxInputTokens)
        val spec = LiteRtConversationSpec(
            compactedHistory,
            parameters,
            currentPrompt,
            maxInputTokens ?: profileMaxInputTokens(),
        )
        lock.withLock {
            val currentEngine = engine ?: error("模型尚未就绪")
            reclaimFromOneShotLocked()
            val live = chat
            if (mayContinue && live?.handle != null && live.canContinueAs(spec)) {
                continueOnNextSend = true
                return
            }
            continueOnNextSend = false
            // Detach the old conversation before closing it: if creating the replacement fails, the
            // runtime must report not-loaded instead of keeping a closed native conversation.
            publishChatLocked(null)
            live?.handle?.close()
            publishChatLocked(ChatConversation(currentEngine.createConversation(spec), spec))
        }
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
        if (engine == null || chat == null) error("模型尚未就绪")
        if (request.imageAttachments.isNotEmpty() && !capabilities.supportsVisionInput) {
            error("当前本地模型不支持图片输入")
        }
        return flow {
            val startedAtNanos = System.nanoTime()
            val turn = beginChatTurn()
            val output = StringBuilder()
            var completed = false
            try {
                turn.handle.sendMessageAsync(request).collect { text ->
                    if (text.isNotBlank() && lastFirstTokenMs == null) {
                        lastFirstTokenMs = elapsedMillisSince(startedAtNanos)
                    }
                    output.append(text)
                    emit(text)
                }
                completed = true
            } finally {
                endChatTurn(turn, request, output.toString(), completed, startedAtNanos)
            }
        }
    }

    private class ChatTurn(val handle: LiteRtConversationHandle, val continued: Boolean)

    private fun beginChatTurn(): ChatTurn = lock.withLock {
        reclaimFromOneShotLocked()
        val handle = chatHandleLocked()
        chatGenerating = true
        lastFirstTokenMs = null
        ChatTurn(handle, continued = continueOnNextSend).also { continueOnNextSend = false }
    }

    private fun endChatTurn(
        turn: ChatTurn,
        request: LocalModelRequest,
        output: String,
        completed: Boolean,
        startedAtNanos: Long,
    ) {
        // The native decode keeps running when its Flow is abandoned, so stop it explicitly.
        if (!completed) runCatching { turn.handle.cancelProcess() }
        val benchmark = runCatching { turn.handle.benchmarkInfo() }.getOrNull()
        lock.withLock {
            chatGenerating = false
            val live = chat
            if (live != null && live.handle === turn.handle) {
                if (completed && request.imageAttachments.isEmpty()) {
                    live.transcript += Turn(MessageRole.User, request.prompt)
                    live.transcript += Turn(MessageRole.Assistant, output)
                } else {
                    live.reusable = false
                }
            }
            lastMetrics = LocalCallMetrics(
                source = LocalEngineSource.SharedChatEngine,
                accelerator = (currentBackend ?: BackendChoice.CPU).toAccelerator(),
                loadMs = null,
                firstTokenMs = lastFirstTokenMs,
                totalMs = elapsedMillisSince(startedAtNanos),
                inputTokens = benchmark?.lastPrefillTokenCount?.takeIf { it > 0 },
                outputTokens = benchmark?.lastDecodeTokenCount?.takeIf { it > 0 },
                conversationReused = turn.continued,
            )
            engineReleased.signalAll()
        }
    }

    override fun generateOneShot(request: LocalOneShotRequest): LocalOneShotResult =
        interruptAsCancelled { generateOneShotNow(request) }

    private fun generateOneShotNow(request: LocalOneShotRequest): LocalOneShotResult {
        val startedAtNanos = System.nanoTime()
        if (request.cancellation.isCancelled) return cancelledByCaller()
        if (!lock.tryLock(waits.engineWaitMs, TimeUnit.MILLISECONDS)) {
            return LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.Busy, "the engine is loading or closing")
        }
        val lease = try {
            refusalForOneShotLocked(request, startedAtNanos + waits.engineWaitMs * NANOS_PER_MILLI)?.let { return it }
            if (request.cancellation.isCancelled) return cancelledByCaller()
            val currentEngine = engine ?: return modelNotLoaded()
            parkChatLocked()
            val conversation = runCatching { currentEngine.createConversation(request.toConversationSpec()) }
                .getOrElse { throwable -> return failed(throwable) }
            oneShot = conversation
            oneShotReclaimed = false
            OneShotLease(conversation, (currentBackend ?: BackendChoice.CPU).toAccelerator())
        } finally {
            lock.unlock()
        }
        var cleanedUp = false
        try {
            val output = lease.conversation.collectOneShot(request, waits.oneShotTimeoutMs)
            val reclaimed = lock.withLock {
                runCatching { lease.conversation.close() }
                oneShot = null
                engineReleased.signalAll()
                oneShotReclaimed.also { oneShotReclaimed = false }
            }
            cleanedUp = true
            return output.toResult(LocalEngineSource.SharedChatEngine, lease.accelerator, loadMs = null, startedAtNanos, reclaimed)
        } finally {
            // runInterruptible can make runBlocking throw InterruptedException even after the native
            // cancellation listener fired. Never abandon the live conversation or block chat recovery.
            if (!cleanedUp) {
                runCatching { lease.conversation.cancelProcess() }
                lock.withLock {
                    runCatching { lease.conversation.close() }
                    oneShot = null
                    engineReleased.signalAll()
                    oneShotReclaimed = false
                }
            }
        }
    }

    private class OneShotLease(val conversation: LiteRtConversationHandle, val accelerator: LocalAccelerator)

    /**
     * Null once the engine may serve [request]; waits until [deadlineNanos] for it to be released.
     * Polls in short slices so a cancel from another thread is seen without a lock signal.
     */
    private fun refusalForOneShotLocked(
        request: LocalOneShotRequest,
        deadlineNanos: Long,
    ): LocalOneShotResult.Unavailable? {
        while (true) {
            if (request.cancellation.isCancelled) return cancelledByCaller()
            if (engine == null) return modelNotLoaded()
            if (loadedModelPath != request.modelPath) {
                return LocalOneShotResult.Unavailable(
                    LocalOneShotUnavailableReason.ModelNotLoaded,
                    "the loaded engine serves another model",
                )
            }
            if (!chatGenerating && oneShot == null) return null
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                return LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.Busy, "chat holds the engine")
            }
            engineReleased.awaitNanos(minOf(remainingNanos, CANCEL_POLL_NANOS))
        }
    }

    /** The engine hosts one conversation at a time; the next chat use recreates this one. */
    private fun parkChatLocked() {
        val live = chat ?: return
        val handle = live.handle ?: return
        live.handle = null
        liveChatHandle = null
        continueOnNextSend = false
        runCatching { handle.close() }
    }

    /** Chat outranks one-shots: cancel a running one and wait, bounded, for the engine. */
    private fun reclaimFromOneShotLocked() {
        val running = oneShot ?: return
        oneShotReclaimed = true
        runCatching { running.cancelProcess() }
        val deadlineNanos = System.nanoTime() + waits.reclaimWaitMs * NANOS_PER_MILLI
        while (oneShot != null) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            check(remainingNanos > 0) { LOCAL_ENGINE_BUSY_MESSAGE }
            engineReleased.awaitNanos(remainingNanos)
        }
    }

    /** The chat handle, recreated from the chat transcript if a one-shot parked it. */
    private fun chatHandleLocked(): LiteRtConversationHandle {
        val currentEngine = engine ?: error("模型尚未就绪")
        val live = chat ?: error("模型尚未就绪")
        live.handle?.let { return it }
        val restoreSpec = live.restoreSpec()
        publishChatLocked(null)
        val restored = currentEngine.createConversation(restoreSpec)
        publishChatLocked(ChatConversation(restored, restoreSpec))
        return restored
    }

    private fun publishChatLocked(conversation: ChatConversation?) {
        chat = conversation
        liveChatHandle = conversation?.handle
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

    override fun lastCallMetrics(): LocalCallMetrics? = lastMetrics

    @OptIn(ExperimentalApi::class)
    override fun lastBenchmarkResult(): RuntimeBenchmarkResult {
        val conversation = liveChatHandle
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
        runCatching { liveChatHandle?.cancelProcess() }

    override fun close() {
        lock.withLock {
            reclaimFromOneShotLocked()
            closeLocked()
        }
    }

    private fun closeLocked() {
        val closingConversation = chat?.handle
        val closingEngine = engine
        publishChatLocked(null)
        engine = null
        loadedModelPath = null
        currentBackend = null
        continueOnNextSend = false
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

        /**
         * Process-wide; applies to engines initialized afterwards. With it on, call metrics carry
         * native prefill/decode token counts and [lastBenchmarkResult] has decode throughput. Off by
         * default: it is not yet confirmed on a device that benchmark mode leaves generation unchanged.
         */
        @OptIn(ExperimentalApi::class)
        fun configureNativeBenchmark(enabled: Boolean) {
            ExperimentalFlags.enableBenchmark = enabled
        }
    }
}

/**
 * The chat conversation and the turns it holds. [handle] is null while a one-shot has the engine.
 * [reusable] turns false once a send was cancelled, failed or carried images, because the native
 * state then no longer matches [transcript].
 */
private class ChatConversation(
    var handle: LiteRtConversationHandle?,
    val spec: LiteRtConversationSpec,
) {
    val transcript: MutableList<Turn> = spec.initialMessages().mapTo(mutableListOf()) { Turn(it.role, it.text) }
    var reusable = true

    fun canContinueAs(next: LiteRtConversationSpec): Boolean =
        reusable &&
            next.systemInstruction == spec.systemInstruction &&
            next.parameters == spec.parameters &&
            next.initialMessages().map { Turn(it.role, it.text) } == transcript

    fun restoreSpec(): LiteRtConversationSpec =
        spec.copy(history = transcript.map { turn -> ChatMessage(turn.role, turn.text) }, currentPrompt = "")
}

private data class Turn(val role: MessageRole, val text: String)

/**
 * The fail-closed fallback for a one-shot caller whose model the chat engine does not serve. Loads
 * that one model on CPU with the model file's own defaults and keeps it until [close], a request
 * for another model, or [releaseIfHolding] once the chat engine serves that model, so a phone does
 * not keep two copies of a multi-GB model mapped.
 */
class DedicatedLiteRtEngine internal constructor(
    private val cacheDir: File,
    private val engineFactory: LiteRtEngineFactory,
    private val waits: LocalEngineWaits = LocalEngineWaits(),
) : LocalEngine, AutoCloseable {
    constructor(cacheDir: File) : this(cacheDir, NativeLiteRtEngineFactory)

    private val lock = ReentrantLock()
    private var engine: LiteRtEngineHandle? = null
    private var modelPath: String? = null

    /**
     * The model of a [releaseIfHolding] that arrived while a call held [lock]; whoever unlocks next
     * applies it. The newest request wins: chat serves one model at a time.
     */
    private val pendingRelease = AtomicReference<String?>(null)

    /** The window is the model file's own; [LocalModelTokenLimits.DEFAULT_CONTEXT_WINDOW_TOKENS] is the fail-safe. */
    override fun engineCapabilities(): LocalEngineCapabilities =
        LocalEngineCapabilities(
            backendName = LITERT_BACKEND_NAME,
            accelerators = setOf(LocalAccelerator.CPU),
            maxContextTokens = LocalModelTokenLimits.DEFAULT_CONTEXT_WINDOW_TOKENS,
            nativeToolCalling = true,
        )

    override fun generateOneShot(request: LocalOneShotRequest): LocalOneShotResult =
        interruptAsCancelled { generateOneShotNow(request) }

    private fun generateOneShotNow(request: LocalOneShotRequest): LocalOneShotResult {
        val startedAtNanos = System.nanoTime()
        if (request.cancellation.isCancelled) return cancelledByCaller()
        if (!lock.tryLock(waits.engineWaitMs, TimeUnit.MILLISECONDS)) {
            return LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.Busy, "another one-shot holds the engine")
        }
        try {
            if (request.cancellation.isCancelled) return cancelledByCaller()
            var loadMs: Long? = null
            val currentEngine = engine.takeIf { modelPath == request.modelPath } ?: run {
                closeLocked()
                val loadStartedAtNanos = System.nanoTime()
                val created = runCatching {
                    engineFactory.create(request.modelPath, BackendChoice.CPU, cacheDir, capabilities = null)
                }.getOrElse { throwable -> return failed(throwable) }
                runCatching { created.initialize() }.onFailure { throwable ->
                    runCatching { created.close() }
                    return failed(throwable)
                }
                engine = created
                modelPath = request.modelPath
                loadMs = elapsedMillisSince(loadStartedAtNanos)
                created
            }
            val conversation = runCatching { currentEngine.createConversation(request.toConversationSpec()) }
                .getOrElse { throwable -> return failed(throwable) }
            val output = try {
                conversation.collectOneShot(request, waits.oneShotTimeoutMs)
            } finally {
                runCatching { conversation.close() }
            }
            return output.toResult(LocalEngineSource.DedicatedEngine, LocalAccelerator.CPU, loadMs, startedAtNanos, reclaimed = false)
        } finally {
            lock.unlock()
            applyPendingRelease()
        }
    }

    /**
     * Unloads [modelPath] if this engine holds it, because the chat engine serves it now. Never
     * blocks: during a running one-shot the release waits until that call ends. Another model is
     * left loaded.
     */
    fun releaseIfHolding(modelPath: String) {
        pendingRelease.set(modelPath)
        applyPendingRelease()
    }

    private fun applyPendingRelease() {
        while (pendingRelease.get() != null && lock.tryLock()) {
            try {
                val release = pendingRelease.getAndSet(null)
                if (release != null && release == modelPath) closeLocked()
            } finally {
                lock.unlock()
            }
        }
    }

    override fun close() = lock.withLock { closeLocked() }

    private fun closeLocked() {
        val closing = engine
        engine = null
        modelPath = null
        closing?.close()
    }
}

private class OneShotOutput(
    val text: String,
    val toolCalls: List<LocalToolCall>,
    val firstTokenMs: Long?,
    val benchmark: BenchmarkInfo?,
    val failure: Throwable?,
    val timedOutAfterMs: Long?,
    val cancelled: Boolean,
)

/**
 * Serializes native cancels against the end of one one-shot decode. Once [close]d no cancel reaches
 * the conversation, so the caller may close it straight after. A cancel may come from any thread.
 */
private class DecodeCancelGate(private val conversation: LiteRtConversationHandle) {
    private var open = true

    @Synchronized
    fun cancel() {
        if (open) runCatching { conversation.cancelProcess() }
    }

    @Synchronized
    fun close() {
        open = false
    }
}

/**
 * Collects one one-shot send. The output cap, the timeout and the caller's cancellation each cancel
 * the native decode and keep draining: abandoning the Flow alone would leave the decode running on
 * the engine.
 */
@OptIn(ExperimentalApi::class)
private fun LiteRtConversationHandle.collectOneShot(
    request: LocalOneShotRequest,
    timeoutMs: Long,
): OneShotOutput {
    val text = StringBuilder()
    val toolCalls = mutableListOf<LocalToolCall>()
    val sentAtNanos = System.nanoTime()
    var firstTokenMs: Long? = null
    var outputTokens = 0
    var capped = false
    var timedOut = false
    val gate = DecodeCancelGate(this)
    val stopListening = request.cancellation.onCancel { gate.cancel() }
    val failure = try {
        runBlocking {
            val watchdog = launch {
                delay(timeoutMs)
                timedOut = true
                gate.cancel()
            }
            try {
                sendOneShot(request.prompt).collect { chunk ->
                    if (capped) return@collect
                    if (firstTokenMs == null && (chunk.text.isNotBlank() || chunk.toolCalls.isNotEmpty())) {
                        firstTokenMs = elapsedMillisSince(sentAtNanos)
                    }
                    text.append(chunk.text)
                    toolCalls += chunk.toolCalls
                    outputTokens += estimateLocalRuntimeTokens(chunk.text)
                    if (outputTokens >= request.maxOutputTokens) {
                        capped = true
                        gate.cancel()
                    }
                }
                null
            } catch (cancellation: CancellationException) {
                // A native cancel ends the Flow with CancellationException; ours at the cap is success.
                cancellation.takeUnless { capped }
            } catch (throwable: Throwable) {
                throwable
            } finally {
                watchdog.cancel()
            }
        }
    } finally {
        gate.close()
        stopListening()
    }
    return OneShotOutput(
        text = text.toString(),
        toolCalls = toolCalls,
        firstTokenMs = firstTokenMs,
        benchmark = runCatching { benchmarkInfo() }.getOrNull(),
        failure = failure,
        timedOutAfterMs = timeoutMs.takeIf { timedOut },
        cancelled = request.cancellation.isCancelled,
    )
}

/**
 * Chat takes precedence over everything: a reclaimed one-shot is Busy even if the native decode
 * ended quietly with partial text. Then the caller's cancel, then timeout (a timed-out decode may be
 * truncated, so it is never Generated), then success or failure.
 */
@OptIn(ExperimentalApi::class)
private fun OneShotOutput.toResult(
    source: LocalEngineSource,
    accelerator: LocalAccelerator,
    loadMs: Long?,
    startedAtNanos: Long,
    reclaimed: Boolean,
): LocalOneShotResult =
    when {
        reclaimed -> LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.Busy, "chat took the engine back")
        cancelled -> cancelledByCaller()
        timedOutAfterMs != null -> LocalOneShotResult.Unavailable(
            LocalOneShotUnavailableReason.Failed,
            "timed out after $timedOutAfterMs ms",
        )

        failure == null -> LocalOneShotResult.Generated(
            text = text,
            toolCalls = toolCalls,
            metrics = LocalCallMetrics(
                source = source,
                accelerator = accelerator,
                loadMs = loadMs,
                firstTokenMs = firstTokenMs,
                totalMs = elapsedMillisSince(startedAtNanos),
                inputTokens = benchmark?.lastPrefillTokenCount?.takeIf { it > 0 },
                outputTokens = benchmark?.lastDecodeTokenCount?.takeIf { it > 0 },
            ),
        )

        else -> failed(failure)
    }

private fun cancelledByCaller(): LocalOneShotResult.Unavailable =
    LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.Cancelled, "cancelled by the caller")

/**
 * A one-shot whose thread is interrupted (for example by `runInterruptible` on a cancelled
 * coroutine) gives up its wait and reports Cancelled; the interrupt flag is restored for the caller.
 */
private inline fun interruptAsCancelled(block: () -> LocalOneShotResult): LocalOneShotResult =
    try {
        block()
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.Cancelled, "interrupted")
    }

private fun modelNotLoaded(): LocalOneShotResult.Unavailable =
    LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.ModelNotLoaded, "no local model is loaded")

private fun failed(throwable: Throwable): LocalOneShotResult.Unavailable =
    LocalOneShotResult.Unavailable(
        LocalOneShotUnavailableReason.Failed,
        throwable.message ?: throwable.javaClass.simpleName,
    )

private fun LocalOneShotRequest.toConversationSpec(): LiteRtConversationSpec =
    LiteRtConversationSpec(
        history = emptyList(),
        parameters = ONE_SHOT_PARAMETERS,
        systemInstruction = systemInstruction,
        tools = tools,
    )

private val ONE_SHOT_PARAMETERS = GenerationParameters(temperature = 0f, topP = 0.1f, topK = 1)

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
    ((System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI).coerceAtLeast(0L)

internal data class LiteRtEngineConfigSpec(
    val modelPath: String,
    val backend: BackendChoice,
    val visionBackend: BackendChoice?,
    /** Null keeps the model file's default window. */
    val maxNumTokens: Int?,
    val maxNumImages: Int?,
    val cacheDir: String,
)

internal fun defaultEngineConfigSpec(
    modelPath: String,
    backend: BackendChoice,
    cacheDir: File,
    capabilities: LocalModelRuntimeCapabilities? = LocalModelRuntimeCapabilities(),
): LiteRtEngineConfigSpec {
    val vision = capabilities?.supportsVisionInput == true
    return LiteRtEngineConfigSpec(
        modelPath = modelPath,
        backend = backend,
        visionBackend = if (vision) backend else null,
        maxNumTokens = capabilities?.contextWindowTokens,
        maxNumImages = if (vision) MAX_LOCAL_MODEL_IMAGES else null,
        cacheDir = cacheDir.absolutePath,
    )
}

internal fun defaultEngineConfig(
    modelPath: String,
    backend: BackendChoice,
    cacheDir: File,
    capabilities: LocalModelRuntimeCapabilities? = LocalModelRuntimeCapabilities(),
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

private fun LiteRtConversationSpec.toConversationConfig(): ConversationConfig =
    ConversationConfig(
        systemInstruction = Contents.of(systemInstruction ?: DEFAULT_CHAT_SYSTEM_INSTRUCTION),
        initialMessages = initialMessages().map { message ->
            when (message.role) {
                MessageRole.User -> Message.user(message.text)
                MessageRole.Assistant -> Message.model(message.text)
            }
        },
        tools = tools.map { declaration -> tool(DeclaredOnlyTool(declaration)) },
        samplerConfig = SamplerConfig(
            topK = parameters.topK,
            topP = parameters.topP.toDouble(),
            temperature = parameters.temperature.toDouble(),
        ),
        automaticToolCalling = false,
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
        if (!char.isWhitespace() && char.code in 0x20..0x7E) {
            asciiRunLength += 1
        } else {
            flushAsciiRun()
            tokens += 1
        }
    }
    flushAsciiRun()
    return tokens
}

private fun ChatMessage.estimatedLocalRuntimeTokens(): Int =
    estimateLocalRuntimeTokens(text) + MESSAGE_TOKEN_OVERHEAD

/** Charges one token per character, a conservative bound on [estimateLocalRuntimeTokens]. */
private fun String.takeLastEstimatedLocalTokens(maxTokens: Int): String =
    if (maxTokens <= 0) "" else takeLast(maxTokens).trimStart()

private fun BackendChoice.toLiteRtBackend(): Backend =
    when (this) {
        BackendChoice.CPU -> Backend.CPU()
        BackendChoice.GPU -> Backend.GPU()
    }

private fun BackendChoice.toAccelerator(): LocalAccelerator =
    when (this) {
        BackendChoice.CPU -> LocalAccelerator.CPU
        BackendChoice.GPU -> LocalAccelerator.GPU
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

private fun Message.textContent(): String =
    contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString(separator = "") { it.text }

private const val LITERT_BACKEND_NAME = "litert-lm"
private const val NANOS_PER_MILLI = 1_000_000L
private const val CANCEL_POLL_NANOS = 50_000_000L
private const val MESSAGE_TOKEN_OVERHEAD = 4
private const val MAX_LOCAL_MODEL_IMAGES = 5
private const val ASCII_CHARS_PER_TOKEN = 4
private const val MIN_TRIMMED_MESSAGE_TOKENS = 64
