package com.bytedance.zgx.solin.runtime

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backend-neutral on-device inference engine. LiteRT-LM is the only production backend:
 * [RealLiteRtRuntime] serves the engine loaded for chat, [DedicatedLiteRtEngine] loads one model
 * on its own for a caller that cannot use the chat engine. Callers in `action/` and `memory/` see
 * this contract, never LiteRT-LM classes, and branch on [engineCapabilities], never on the backend.
 *
 * [generateOneShot] runs one short, stateless, greedy generation (the action and observation
 * planners). It is blocking: call it off the main thread, or wrap it in `runInterruptible` and
 * cancel [LocalOneShotRequest.cancellation] from the coroutine that gave up on it. Cancelling from
 * another thread ends the native decode and returns [LocalOneShotUnavailableReason.Cancelled].
 *
 * Every one-shot runs on a fresh conversation with an empty history: it never sees chat turns, and
 * chat never sees it. Engine conditions never throw; they come back as
 * [LocalOneShotResult.Unavailable]:
 * - the chat engine serves only the model it loaded; any other model, or no loaded model, is
 *   [LocalOneShotUnavailableReason.ModelNotLoaded] and the caller decides whether to load its own;
 * - a one-shot never preempts chat: while a chat generation runs past a short bounded wait, or when
 *   chat takes the engine back mid-request, the result is [LocalOneShotUnavailableReason.Busy].
 *
 * Output stops at [LocalOneShotRequest.maxOutputTokens]. Everything stays on the device; privacy
 * decisions remain the caller's.
 */
interface LocalEngine {
    fun engineCapabilities(): LocalEngineCapabilities

    fun generateOneShot(request: LocalOneShotRequest): LocalOneShotResult
}

enum class LocalAccelerator {
    CPU,
    GPU,
    NPU,
}

/** What a local backend supports. Declared by the backend. */
data class LocalEngineCapabilities(
    /** Backend family, e.g. `litert-lm`. For diagnostics only; never branch on it. */
    val backendName: String,
    val accelerators: Set<LocalAccelerator>,
    /** Token window of the model the engine is configured for. */
    val maxContextTokens: Int,
    val visionInput: Boolean = false,
    /**
     * [LocalOneShotRequest.tools] reach the model's chat template and calls come back in
     * [LocalOneShotResult.Generated.toolCalls]. Whether a given model emits them is a model
     * property the backend cannot promise.
     */
    val nativeToolCalling: Boolean = false,
) {
    init {
        require(maxContextTokens > 0) { "Local engine context window must be positive" }
    }
}

/** One stateless generation. Always decoded greedily (top-k 1, temperature 0). */
data class LocalOneShotRequest(
    val modelPath: String,
    val systemInstruction: String,
    val prompt: String,
    /**
     * Declared to the model only when the backend has [LocalEngineCapabilities.nativeToolCalling].
     * The backend never executes a tool.
     */
    val tools: List<LocalToolDeclaration> = emptyList(),
    /** Generation is cancelled once the output reaches this many (estimated) tokens. */
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    /** Cancel from any thread to end this call early; see [LocalOneShotCancellation]. */
    val cancellation: LocalOneShotCancellation = LocalOneShotCancellation(),
) {
    init {
        require(maxOutputTokens > 0) { "One-shot output limit must be positive" }
    }

    companion object {
        const val DEFAULT_MAX_OUTPUT_TOKENS = 512
    }
}

/**
 * Cancels one one-shot from any thread. [cancel] ends the native decode of a running call, which
 * then returns [LocalOneShotUnavailableReason.Cancelled]; a call that has not yet started returns
 * Cancelled without loading anything. Cancelling twice is harmless.
 */
class LocalOneShotCancellation {
    private val cancelled = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val isCancelled: Boolean
        get() = cancelled.get()

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            listeners.forEach { listener -> listener() }
        }
    }

    /**
     * Runs [listener] when cancelled, at once if already cancelled, and returns the way to stop
     * listening. A listener may run twice around a racing [cancel]; it must tolerate that.
     */
    internal fun onCancel(listener: () -> Unit): () -> Unit {
        listeners += listener
        if (cancelled.get()) listener()
        return { listeners -= listener }
    }
}

/** A tool the model may call. [parametersJsonSchema] is a JSON Schema object. */
data class LocalToolDeclaration(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
)

/** A structured call the model emitted. [argumentsJson] is a JSON object; nothing has run. */
data class LocalToolCall(
    val name: String,
    val argumentsJson: String,
)

sealed interface LocalOneShotResult {
    data class Generated(
        val text: String,
        val metrics: LocalCallMetrics,
        val toolCalls: List<LocalToolCall> = emptyList(),
    ) : LocalOneShotResult

    data class Unavailable(
        val reason: LocalOneShotUnavailableReason,
        val detail: String? = null,
    ) : LocalOneShotResult
}

enum class LocalOneShotUnavailableReason {
    /** No engine is loaded for the requested model: none is loaded, or another model is. */
    ModelNotLoaded,

    /** Chat held the engine past the bounded wait, or took it back mid-request. */
    Busy,

    /** This runtime serves no one-shots (local runtime disabled). */
    Unsupported,

    /** Engine creation or generation failed or timed out; nothing was left open. */
    Failed,

    /** The caller cancelled the one-shot, or its thread was interrupted; nothing was left open. */
    Cancelled,
}

enum class LocalEngineSource {
    /** The engine loaded for chat. */
    SharedChatEngine,

    /** An engine loaded for one-shots alone, because the chat engine could not serve the model. */
    DedicatedEngine,
}

/**
 * Per-call measurements for the device bench. Numbers only: never prompt or output text, and they
 * never leave the device.
 */
data class LocalCallMetrics(
    val source: LocalEngineSource,
    val accelerator: LocalAccelerator,
    /** Engine load paid by this call, or null when the engine was already loaded. */
    val loadMs: Long?,
    /** From sending the prompt to the first non-blank output, or null when none arrived. */
    val firstTokenMs: Long?,
    /** The whole call, including any wait for the engine and any load. */
    val totalMs: Long,
    /** Prefill token count from the native benchmark; null unless the benchmark is enabled. */
    val inputTokens: Int?,
    /** Decode token count from the native benchmark; null unless the benchmark is enabled. */
    val outputTokens: Int?,
    /** The chat send continued the live conversation instead of re-prefilling its history. */
    val conversationReused: Boolean = false,
)
