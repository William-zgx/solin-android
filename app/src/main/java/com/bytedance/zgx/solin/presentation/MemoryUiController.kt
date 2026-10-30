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
import com.bytedance.zgx.solin.memory.MemoryHit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

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
