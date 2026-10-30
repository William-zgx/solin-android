package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_MEMORY
import com.bytedance.zgx.solin.logging.solinW
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.memory.MemoryIndex
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Result of one local memory retrieval for an agent run.
 *
 * [degraded] is true when the memory index threw: the run continues without memory context
 * (fail-open for answering, never for privacy: no hits means nothing extra reaches any model), but
 * the failure is observable instead of looking identical to "no relevant memory".
 */
data class MemoryRetrievalOutcome(
    val hits: List<MemoryHit>,
    val attempted: Boolean,
    val degraded: Boolean,
) {
    companion object {
        val Skipped = MemoryRetrievalOutcome(hits = emptyList(), attempted = false, degraded = false)
    }
}

/**
 * Agent-loop collaborator that runs the per-run local memory search.
 *
 * Extracted from `AgentLoopRuntime` (which previously swallowed search failures with
 * `runCatching { … }.getOrDefault(emptyList())`). Failures are logged with the exception type only
 * (never the query or memory text) and published through [lastOutcome] so presentation can show
 * that memory is temporarily unavailable. Hits stay LocalOnly; this class does not decide whether
 * they may be sent anywhere — the existing context assembly / privacy pipeline still does.
 */
class MemoryRetrieval(
    private val memoryIndex: MemoryIndex,
    private val topK: Int = DEFAULT_TOP_K,
) {
    private val outcomes = MutableStateFlow<MemoryRetrievalOutcome?>(null)

    /** Most recent retrieval outcome, or null before the first run. Skipped runs (memory off) count. */
    val lastOutcome: StateFlow<MemoryRetrievalOutcome?> = outcomes.asStateFlow()

    fun retrieve(input: String, memoryEnabled: Boolean): MemoryRetrievalOutcome {
        val outcome = if (!memoryEnabled) {
            MemoryRetrievalOutcome.Skipped
        } else {
            try {
                MemoryRetrievalOutcome(
                    hits = memoryIndex.search(input, topK = topK),
                    attempted = true,
                    degraded = false,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                solinW(TAG_MEMORY, "memory retrieval degraded: ${failure.javaClass.simpleName}")
                MemoryRetrievalOutcome(hits = emptyList(), attempted = true, degraded = true)
            }
        }
        outcomes.value = outcome
        return outcome
    }

    companion object {
        const val DEFAULT_TOP_K = 3
    }
}
