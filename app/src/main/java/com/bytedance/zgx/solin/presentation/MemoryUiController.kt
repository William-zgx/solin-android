package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.LongTermMemorySummary
import com.bytedance.zgx.solin.SemanticMemoryStatus
import com.bytedance.zgx.solin.data.FirstRunSetupStore
import com.bytedance.zgx.solin.data.SessionStore
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_MEMORY
import com.bytedance.zgx.solin.logging.solinD
import com.bytedance.zgx.solin.logging.solinW
import com.bytedance.zgx.solin.memory.MemoryCommandResult
import com.bytedance.zgx.solin.memory.MemoryController
import com.bytedance.zgx.solin.memory.MemoryControllerResult
import com.bytedance.zgx.solin.memory.MemoryFactSuggestion
import com.bytedance.zgx.solin.memory.MemoryFactSuggestionDetector
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.orchestration.MemoryRetrievalOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Memory-panel-only state that does not belong in [ChatUiState] (kept separate so this controller
 * does not have to grow the shared chat state model).
 *
 * [retrievalDegraded] reflects the most recent agent-run memory retrieval. [suggestions] are
 * "remember this?" chips detected in the user's own messages; nothing is stored until the user
 * confirms one.
 */
data class MemoryPanelState(
    val retrievalDegraded: Boolean = false,
    val suggestions: List<MemoryFactSuggestion> = emptyList(),
) {
    /** Short status line for the memory panel, or null when retrieval is healthy. */
    val retrievalStatusText: String?
        get() = if (retrievalDegraded) MEMORY_RETRIEVAL_DEGRADED_TEXT else null
}

internal const val MEMORY_RETRIEVAL_DEGRADED_TEXT = "记忆暂不可用"

private const val MAX_PENDING_SUGGESTIONS = 3

/** Comparable key for a saved record text or a suggested fact (label prefix, case, spacing). */
private fun String.savedFactKey(): String =
    trim()
        .removePrefix("用户事实：")
        .removePrefix("用户偏好：")
        .trim()
        .trimEnd('。', '.', '！', '!')
        .lowercase()
        .replace(Regex("""\s+"""), "")

/**
 * UI-state side of the memory paths: applies [MemoryController] results to [ChatUiState].
 *
 * Extracted from `SolinViewModel` so the ViewModel only forwards memory intents.
 */
internal class MemoryUiController(
    private val memoryController: MemoryController,
    private val firstRunSetupStore: FirstRunSetupStore,
    private val sessionStore: SessionStore,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val skipModelRuntimeWork: Boolean,
) {
    private val panelState = MutableStateFlow(MemoryPanelState())

    /** Memory-panel state (retrieval health); render alongside [ChatUiState]. */
    val memoryPanelState: StateFlow<MemoryPanelState> = panelState.asStateFlow()

    /**
     * Records the outcome of the latest agent-run memory retrieval
     * (`orchestration.MemoryRetrieval.lastOutcome`). A later healthy or skipped retrieval clears
     * the degraded flag.
     */
    fun onMemoryRetrievalOutcome(outcome: MemoryRetrievalOutcome?) {
        val degraded = outcome?.degraded == true
        panelState.update { state -> state.copy(retrievalDegraded = degraded) }
    }

    /** Suggestion ids the user dismissed; the same fact text is not suggested again. */
    private val dismissedSuggestionIds = mutableSetOf<String>()

    /**
     * Offers "remember this?" suggestions for durable facts in a user message. Detection is local
     * and pure; nothing is stored here. Skipped while memory is disabled, for dismissed facts and
     * for facts already saved.
     */
    fun onUserMessage(text: String) {
        if (!uiState.value.memoryEnabled) return
        val detected = MemoryFactSuggestionDetector.detect(text)
        if (detected.isEmpty()) return
        val saved = uiState.value.longTermMemories.mapTo(mutableSetOf()) { memory -> memory.text.savedFactKey() }
        val dismissed = synchronized(dismissedSuggestionIds) { dismissedSuggestionIds.toSet() }
        panelState.update { state ->
            val pendingIds = state.suggestions.mapTo(mutableSetOf()) { it.id }
            val fresh = detected.filter { suggestion ->
                suggestion.id !in pendingIds &&
                    suggestion.id !in dismissed &&
                    suggestion.fact.savedFactKey() !in saved
            }
            state.copy(suggestions = (fresh + state.suggestions).take(MAX_PENDING_SUGGESTIONS))
        }
    }

    /** Stores the pending suggestion [suggestionId] after an explicit user tap. */
    fun confirmMemorySuggestion(suggestionId: String) {
        if (uiState.value.isBusy) return
        val suggestion = panelState.value.suggestions.firstOrNull { it.id == suggestionId } ?: return
        panelState.update { state -> state.copy(suggestions = state.suggestions.filterNot { it.id == suggestionId }) }
        val result = memoryController.rememberSuggestedFact(
            fact = suggestion.fact,
            memoryEnabled = uiState.value.memoryEnabled,
            skipModelRuntimeWork = skipModelRuntimeWork,
        )
        uiState.update { state ->
            state.withMemoryResult(
                rebuild = result.rebuildResult,
                longTermMemories = if (result.rebuildResult.memoryHitsCleared) {
                    result.rebuildResult.longTermMemories
                } else {
                    result.longTermMemories
                },
                statusText = result.statusText,
            )
        }
    }

    /** Hides the suggestion and remembers the dismissal for that fact text. Stores nothing. */
    fun dismissMemorySuggestion(suggestionId: String) {
        synchronized(dismissedSuggestionIds) { dismissedSuggestionIds += suggestionId }
        panelState.update { state -> state.copy(suggestions = state.suggestions.filterNot { it.id == suggestionId }) }
    }

    private fun clearMemorySuggestions() {
        panelState.update { state -> state.copy(suggestions = emptyList()) }
    }

    fun semanticMemoryStatus(): SemanticMemoryStatus =
        memoryController.semanticMemoryState().toSemanticMemoryStatus()

    fun loadLongTermMemories(): List<LongTermMemorySummary> =
        memoryController.loadLongTermMemories()

    fun syncTaskStateMemories(memoryEnabled: Boolean = uiState.value.memoryEnabled) {
        memoryController.syncTaskStateMemories(memoryEnabled = memoryEnabled)
    }

    fun updateMemoryEnabled(enabled: Boolean) {
        firstRunSetupStore.setMemoryEnabled(enabled)
        val result = memoryController.setMemoryEnabled(
            enabled = enabled,
            skipModelRuntimeWork = skipModelRuntimeWork,
        )
        val rebuild = result.rebuildResult
        uiState.update { state ->
            state.withMemoryResult(
                rebuild = rebuild,
                longTermMemories = if (rebuild.memoryHitsCleared) {
                    rebuild.longTermMemories
                } else {
                    result.longTermMemories
                },
                memoryHits = if (rebuild.memoryHitsCleared || result.clearMemoryHits) {
                    emptyList()
                } else {
                    state.memoryHits
                },
                statusText = rebuild.statusText ?: result.statusText,
            ).copy(memoryEnabled = result.enabled)
        }
        if (!result.enabled) clearMemorySuggestions()
    }

    fun forgetLongTermMemory(memoryId: String) {
        if (uiState.value.isBusy) return
        val result = memoryController.forgetLongTermMemory(
            memoryId = memoryId,
            memoryEnabled = uiState.value.memoryEnabled,
            skipModelRuntimeWork = skipModelRuntimeWork,
        )
        val rebuild = result.rebuildResult
        uiState.update { state ->
            state.withMemoryResult(
                rebuild = rebuild,
                longTermMemories = result.longTermMemories,
                memoryHits = if (rebuild.memoryHitsCleared) {
                    emptyList()
                } else {
                    state.memoryHits.filterNot { hit -> hit.id == memoryId }
                },
                // Match prior behavior: forget status overwrites rebuild failure text.
                statusText = result.statusText,
            )
        }
    }

    fun clearLongTermMemory() {
        if (uiState.value.isBusy) return
        val result = memoryController.clearLongTermMemory(
            memoryEnabled = uiState.value.memoryEnabled,
            skipModelRuntimeWork = skipModelRuntimeWork,
        )
        uiState.update { state ->
            state.withMemoryResult(
                rebuild = result.rebuildResult,
                longTermMemories = emptyList(),
                memoryHits = emptyList(),
                // Match prior behavior: clear status overwrites rebuild failure text.
                statusText = result.statusText,
            )
        }
    }

    fun rebuildMemoryIndex() {
        val startMs = System.currentTimeMillis()
        val result = memoryController.rebuildMemoryIndex(
            memoryEnabled = uiState.value.memoryEnabled,
            skipModelRuntimeWork = skipModelRuntimeWork,
        )
        val durationMs = System.currentTimeMillis() - startMs
        if (result.memoryHitsCleared || result.statusText != null) {
            solinW(TAG_MEMORY, "rebuildMemoryIndex: failed status=${result.statusText}")
        } else {
            solinD(
                TAG_MEMORY,
                "rebuildMemoryIndex: success hits=${result.semanticMemoryIndexedRecordCount} " +
                    "durationMs=$durationMs",
            )
        }
        uiState.update { state -> state.withMemoryResult(result) }
    }

    fun persistExplicitPreferenceMemory(message: ChatMessage): Boolean =
        runCatching {
            memoryController.persistExplicitPreferenceMemory(message).also { persisted ->
                if (persisted) {
                    uiState.update { state ->
                        state.copy(longTermMemories = loadLongTermMemories())
                    }
                }
            }
        }.onFailure {
            uiState.update { state ->
                state.copy(
                    longTermMemories = emptyList(),
                    statusText = "本地记忆暂不可用",
                )
            }
        }.getOrDefault(false)

    fun handleExplicitPreferenceCommand(trimmed: String) {
        applyMemoryCommandResult(
            memoryController.handleExplicitPreferenceCommand(
                trimmed = trimmed,
                memoryEnabled = uiState.value.memoryEnabled,
                skipModelRuntimeWork = skipModelRuntimeWork,
            ),
        )
    }

    fun handleExplicitUserFactCommand(trimmed: String) {
        applyMemoryCommandResult(
            memoryController.handleExplicitUserFactCommand(
                trimmed = trimmed,
                memoryEnabled = uiState.value.memoryEnabled,
                skipModelRuntimeWork = skipModelRuntimeWork,
            ),
        )
    }

    fun handleExplicitMemoryForgetCommand(trimmed: String) {
        applyMemoryCommandResult(
            memoryController.handleExplicitMemoryForgetCommand(
                trimmed = trimmed,
                memoryEnabled = uiState.value.memoryEnabled,
                skipModelRuntimeWork = skipModelRuntimeWork,
            ),
        )
    }

    private fun applyMemoryCommandResult(result: MemoryCommandResult) {
        val rebuild = result.rebuildResult
        uiState.update { state ->
            state.withMemoryResult(
                rebuild = rebuild,
                longTermMemories = if (rebuild.memoryHitsCleared) {
                    rebuild.longTermMemories
                } else {
                    result.longTermMemories
                },
                memoryHits = emptyList(),
                // Match prior behavior: command status overwrites rebuild failure text.
                statusText = result.statusText,
            ).copy(
                sessions = sessionStore.summaries(),
                activeSessionId = sessionStore.activeSessionId,
                messages = sessionStore.activeMessages(),
            )
        }
    }
}

internal fun MemoryControllerResult.toSemanticMemoryStatus(): SemanticMemoryStatus =
    SemanticMemoryStatus(
        enabled = semanticMemoryEnabled,
        runtimeStatus = semanticMemoryRuntimeStatus,
        indexedRecordCount = semanticMemoryIndexedRecordCount,
        lastRebuiltAtMillis = semanticMemoryLastRebuiltAtMillis,
    )

/**
 * Applies a memory-index rebuild [rebuild] to the state. Defaults mirror a plain rebuild: a
 * failed rebuild that cleared memory hits also replaces the long-term memory list, and its status
 * text (if any) wins; callers override the parts their own command decides.
 */
internal fun ChatUiState.withMemoryResult(
    rebuild: MemoryControllerResult,
    longTermMemories: List<LongTermMemorySummary> =
        if (rebuild.memoryHitsCleared) rebuild.longTermMemories else this.longTermMemories,
    memoryHits: List<MemoryHit> = if (rebuild.memoryHitsCleared) emptyList() else this.memoryHits,
    statusText: String = rebuild.statusText ?: this.statusText,
): ChatUiState =
    copy(
        semanticMemory = rebuild.toSemanticMemoryStatus(),
        longTermMemories = longTermMemories,
        memoryHits = memoryHits,
        statusText = statusText,
    )
