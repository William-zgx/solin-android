package com.bytedance.zgx.solin.runtime

/**
 * Engine-sharing seam: lets a secondary local consumer (the observation/action planner in
 * `action/ActionPlanningRuntime.kt`, which today creates its own `Engine(Backend.CPU())`) run a
 * short, one-shot generation on the chat runtime's ALREADY LOADED LiteRT engine instead of
 * initializing a second native engine (a second multi-GB model mapping and KV cache).
 *
 * Contract (fail-closed; the caller keeps its own engine as fallback):
 * - Only served when an engine for exactly [SharedEngineOneShotRequest.modelPath] is loaded.
 * - Never preempts a chat generation in flight: returns [SharedEngineUnavailableReason.Busy].
 * - LiteRT-LM engines host one conversation at a time, so the chat conversation is parked for
 *   the duration of the one-shot and recreated from its last spec afterwards (chat history is
 *   rebuilt from app state on the next send anyway).
 * - Uses the engine's existing backend (GPU/CPU) chosen by the chat load; the request cannot
 *   change backend or context window.
 * - Purely local: no content leaves the device; privacy semantics are the caller's.
 */
interface SharedLocalEngineProvider {
    fun generateOneShot(request: SharedEngineOneShotRequest): SharedEngineOneShotResult
}

data class SharedEngineOneShotRequest(
    val modelPath: String,
    val systemInstruction: String,
    val prompt: String,
    val topK: Int = 1,
    val topP: Float = 0.1f,
    val temperature: Float = 0f,
)

sealed interface SharedEngineOneShotResult {
    data class Generated(val text: String) : SharedEngineOneShotResult

    data class Unavailable(
        val reason: SharedEngineUnavailableReason,
        val detail: String? = null,
    ) : SharedEngineOneShotResult
}

enum class SharedEngineUnavailableReason {
    /** No local chat engine is loaded. */
    NoEngineLoaded,

    /** The loaded engine serves a different model file than the one requested. */
    DifferentModel,

    /** A chat generation or another one-shot is in flight. */
    Busy,

    /** The one-shot conversation failed; the chat conversation was restored. */
    Failed,
}

/** Provider for configurations without a shareable engine (tests, disabled local runtime). */
object NoSharedLocalEngine : SharedLocalEngineProvider {
    override fun generateOneShot(request: SharedEngineOneShotRequest): SharedEngineOneShotResult =
        SharedEngineOneShotResult.Unavailable(SharedEngineUnavailableReason.NoEngineLoaded)
}
