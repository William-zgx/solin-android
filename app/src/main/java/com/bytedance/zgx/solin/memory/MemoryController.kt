package com.bytedance.zgx.solin.memory

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.LongTermMemorySummary
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.background.BackgroundTaskScheduler
import com.bytedance.zgx.solin.background.ScheduledTaskStatus
import com.bytedance.zgx.solin.data.ModelRepositoryFacade
import com.bytedance.zgx.solin.data.SessionStore
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Snapshot of memory-related state that the ViewModel should apply to its UI state
 * after a [MemoryController] operation completes.
 */
data class MemoryControllerResult(
    val semanticMemoryEnabled: Boolean = false,
    val semanticMemoryRuntimeStatus: SemanticMemoryRuntimeStatus =
        SemanticMemoryRuntimeStatus.RuntimeUnavailable,
    val semanticMemoryIndexedRecordCount: Int = 0,
    val semanticMemoryLastRebuiltAtMillis: Long? = null,
    val longTermMemories: List<LongTermMemorySummary> = emptyList(),
    val memoryHitsCleared: Boolean = false,
    val statusText: String? = null,
)

/**
 * Result of enabling/disabling local memory. Preference persistence remains in the
 * ViewModel ([com.bytedance.zgx.solin.data.FirstRunSetupStore]); this result covers
 * index/sync/load/rebuild side effects for the UI layer.
 */
data class SetMemoryEnabledResult(
    val enabled: Boolean,
    val longTermMemories: List<LongTermMemorySummary>,
    val clearMemoryHits: Boolean,
    val statusText: String,
    val rebuildResult: MemoryControllerResult,
)

/**
 * Result of forgetting a single long-term memory record by id.
 */
data class ForgetLongTermMemoryResult(
    val memoryId: String,
    val removed: Boolean,
    val longTermMemories: List<LongTermMemorySummary>,
    val statusText: String,
    val rebuildResult: MemoryControllerResult,
)

/**
 * Result of clearing all long-term memory records.
 */
data class ClearLongTermMemoryResult(
    val statusText: String,
    val rebuildResult: MemoryControllerResult,
)

/**
 * Encapsulates all memory-related business logic currently living in [SolinViewModel].
 *
 * This class is intentionally **not** a ViewModel; it is a pure controller that takes
 * its dependencies via the constructor and returns result snapshots that the caller
 * (typically the ViewModel) applies to its own UI state.
 *
 * Public mutators and rebuild/load/sync/explicit command paths are synchronous so
 * ViewModel helpers and unit tests keep the same call style (already dispatched onto
 * IO via `launchPersistenceWork` / test dispatcher when needed).
 *
 * [ioDispatcher] is retained for future suspend migration of the data layer.
 */
class MemoryController(
    private val memoryIndex: MemoryIndex,
    private val longTermMemoryControls: LongTermMemoryControls,
    private val sessionStore: SessionStore,
    private val modelRepository: ModelRepositoryFacade,
    private val backgroundTaskScheduler: BackgroundTaskScheduler,
    private val semanticMemoryRuntimeController: SemanticMemoryRuntimeController? = null,
    @Suppress("unused")
    private val ioDispatcher: CoroutineDispatcher,
) {
    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Enables or disables local memory, syncs task-state records, and rebuilds the index.
     *
     * Mirrors `SolinViewModel.updateMemoryEnabled()` business logic (preference write stays
     * in the ViewModel). Synchronous so ViewModel mutators and unit tests keep the same
     * call style (already dispatched onto IO via `launchPersistenceWork` / test dispatcher).
     */
    fun setMemoryEnabled(
        enabled: Boolean,
        skipModelRuntimeWork: Boolean = false,
    ): SetMemoryEnabledResult {
        memoryIndex.enabled = enabled
        syncTaskStateMemories(memoryEnabled = enabled)
        val longTermMemories = loadLongTermMemories()
        val rebuildResult = rebuildMemoryIndex(
            memoryEnabled = enabled,
            skipModelRuntimeWork = skipModelRuntimeWork,
        )
        return SetMemoryEnabledResult(
            enabled = enabled,
            longTermMemories = longTermMemories,
            clearMemoryHits = !enabled,
            statusText = if (enabled) "本地记忆已开启" else "本地记忆已关闭",
            rebuildResult = rebuildResult,
        )
    }

    /**
     * Forgets a single long-term memory by id, suppresses auto-managed task-state if needed,
     * rebuilds the index, and returns the refreshed summary list.
     *
     * Mirrors `SolinViewModel.forgetLongTermMemory()` business logic (busy-gate stays in VM).
     */
    fun forgetLongTermMemory(
        memoryId: String,
        memoryEnabled: Boolean,
        skipModelRuntimeWork: Boolean = false,
    ): ForgetLongTermMemoryResult {
        val removed = longTermMemoryControls.forget(memoryId)
        if (memoryId.startsWith(TASK_STATE_MEMORY_RECORD_PREFIX)) {
            longTermMemoryControls.suppressAutoManagedTaskState(memoryId)
        }
        val rebuildResult = rebuildMemoryIndex(
            memoryEnabled = memoryEnabled,
            skipModelRuntimeWork = skipModelRuntimeWork,
        )
        return ForgetLongTermMemoryResult(
            memoryId = memoryId,
            removed = removed,
            longTermMemories = loadLongTermMemories(),
            statusText = if (removed) "已遗忘这条记忆" else "未找到这条记忆",
            rebuildResult = rebuildResult,
        )
    }

    /**
     * Clears all long-term memories, suppresses active task-state auto-resync, and rebuilds.
     *
     * Mirrors `SolinViewModel.clearLongTermMemory()` business logic (busy-gate stays in VM).
     */
    fun clearLongTermMemory(
        memoryEnabled: Boolean,
        skipModelRuntimeWork: Boolean = false,
    ): ClearLongTermMemoryResult {
        val activeTaskMemoryIds = activeTaskStateMemoryIds()
        longTermMemoryControls.clear()
        activeTaskMemoryIds.forEach { memoryId ->
            longTermMemoryControls.suppressAutoManagedTaskState(memoryId)
        }
        val rebuildResult = rebuildMemoryIndex(
            memoryEnabled = memoryEnabled,
            skipModelRuntimeWork = skipModelRuntimeWork,
        )
        return ClearLongTermMemoryResult(
            statusText = "长期记忆已清空",
            rebuildResult = rebuildResult,
        )
    }

    /**
     * Rebuilds the in-memory index from the last 500 session messages and returns
     * a [MemoryControllerResult] describing the new state.
     *
     * Mirrors `SolinViewModel.rebuildMemoryIndex()` business logic (UI apply stays in VM).
     */
    fun rebuildMemoryIndex(
        memoryEnabled: Boolean,
        skipModelRuntimeWork: Boolean = false,
    ): MemoryControllerResult =
        runCatching {
            syncSemanticMemoryRuntime(skipModelRuntimeWork)
            memoryIndex.enabled = memoryEnabled
            memoryIndex.rebuild(sessionStore.allMessages(limit = 500))
            semanticMemoryState()
        }.getOrElse {
            semanticMemoryState().copy(
                memoryHitsCleared = true,
                longTermMemories = emptyList(),
                statusText = "本地记忆暂不可用",
            )
        }

    /**
     * Current semantic-memory runtime fields without rebuilding the index.
     * Used by ViewModel initial state construction.
     */
    fun semanticMemoryState(): MemoryControllerResult =
        MemoryControllerResult(
            semanticMemoryEnabled = currentSemanticMemoryEnabled(),
            semanticMemoryRuntimeStatus = currentSemanticMemoryRuntimeStatus(),
            semanticMemoryIndexedRecordCount = currentSemanticMemoryIndexedRecordCount(),
            semanticMemoryLastRebuiltAtMillis = currentSemanticMemoryLastRebuiltAtMillis(),
        )

    /**
     * Returns the current list of long-term memory summaries.
     *
     * Mirrors `SolinViewModel.loadLongTermMemories()`.
     */
    fun loadLongTermMemories(): List<LongTermMemorySummary> =
        runCatching {
            longTermMemoryControls.savedRecords().map { record ->
                LongTermMemorySummary(
                    id = record.id,
                    type = record.type,
                    text = record.text,
                    source = record.source,
                    sensitivity = record.sensitivity,
                    privacy = record.privacy,
                    expiresAtMillis = record.expiresAtMillis,
                    conflictKey = record.conflictKey,
                )
            }
        }.getOrDefault(emptyList())

    /**
     * Syncs task-state memories: removes stale task-state records and indexes
     * currently-active scheduled tasks.
     *
     * Mirrors `SolinViewModel.syncTaskStateMemories()`.
     */
    fun syncTaskStateMemories(memoryEnabled: Boolean) {
        runCatching {
            val activeTasks = backgroundTaskScheduler.scheduledTasks()
                .filter { task ->
                    task.status == ScheduledTaskStatus.Scheduled ||
                        task.status == ScheduledTaskStatus.Running
                }
            val activeMemoryIds = activeTasks
                .mapTo(mutableSetOf()) { task -> taskStateMemoryRecordId(task.id) }
            longTermMemoryControls.savedRecords()
                .filter { record ->
                    record.type == MemoryRecordType.TaskState &&
                        record.id.startsWith(TASK_STATE_MEMORY_RECORD_PREFIX) &&
                        (!memoryEnabled || record.id !in activeMemoryIds)
                }
                .forEach { record ->
                    longTermMemoryControls.forgetAutoManagedTaskState(record.id)
                }
            if (!memoryEnabled) return@runCatching
            activeTasks.forEach { task ->
                val memoryId = taskStateMemoryRecordId(task.id)
                if (longTermMemoryControls.isAutoManagedTaskStateSuppressed(memoryId)) {
                    return@forEach
                }
                longTermMemoryControls.indexTaskState(
                    id = memoryId,
                    text = task.toTaskStateMemoryText(),
                )
            }
        }
    }

    /**
     * Persists an explicit user preference extracted from [message], if any.
     *
     * @return true when a preference was indexed; false when the message is not a
     *   user preference message. Storage failures propagate to the caller.
     */
    fun persistExplicitPreferenceMemory(message: ChatMessage): Boolean =
        persistExplicitMemory(
            message = message,
            parse = ::explicitUserPreferenceFrom,
            persist = { preference ->
                longTermMemoryControls.indexPreference(
                    explicitUserPreferenceRecordId(preference),
                    preference,
                )
            },
        )

    /**
     * Persists an explicit user fact extracted from [message], if any.
     *
     * @return true when a fact was indexed; false when the message is not a user-fact
     *   message. Storage failures propagate to the caller.
     */
    fun persistExplicitUserFactMemory(message: ChatMessage): Boolean =
        persistExplicitMemory(
            message = message,
            parse = ::explicitUserFactFrom,
            persist = { fact ->
                longTermMemoryControls.indexUserFact(
                    explicitUserFactRecordId(fact),
                    fact,
                )
            },
        )

    /**
     * Handles an explicit "remember this preference" command.
     *
     * Session message writes go through [sessionStore]; the ViewModel applies the
     * returned [MemoryCommandResult] to UI state (messages, status, semantic fields).
     */
    fun handleExplicitPreferenceCommand(
        trimmed: String,
        memoryEnabled: Boolean,
        skipModelRuntimeWork: Boolean = false,
    ): MemoryCommandResult =
        handleExplicitMemoryCommand(
            trimmed = trimmed,
            memoryEnabled = memoryEnabled,
            skipModelRuntimeWork = skipModelRuntimeWork,
            persist = ::persistExplicitPreferenceMemory,
            savedText = "已记住这条本地偏好。你可以在长期记忆中查看或删除。",
            disabledText = "本地记忆已关闭，未保存这条偏好。",
            failedText = "本地记忆暂不可用，未保存这条偏好。",
        )

    /**
     * Handles an explicit "remember this fact about me" command.
     */
    fun handleExplicitUserFactCommand(
        trimmed: String,
        memoryEnabled: Boolean,
        skipModelRuntimeWork: Boolean = false,
    ): MemoryCommandResult =
        handleExplicitMemoryCommand(
            trimmed = trimmed,
            memoryEnabled = memoryEnabled,
            skipModelRuntimeWork = skipModelRuntimeWork,
            persist = ::persistExplicitUserFactMemory,
            savedText = "已记住这条本地事实。你可以在长期记忆中查看或删除。",
            disabledText = "本地记忆已关闭，未保存这条事实。",
            failedText = "本地记忆暂不可用，未保存这条事实。",
        )

    /**
     * Generic handler for explicit memory commands (preference / fact).
     *
     * Session writes stay on [sessionStore]; callers apply [MemoryCommandResult] to UI.
     */
    fun handleExplicitMemoryCommand(
        trimmed: String,
        memoryEnabled: Boolean,
        persist: (ChatMessage) -> Boolean,
        savedText: String,
        disabledText: String,
        failedText: String,
        skipModelRuntimeWork: Boolean = false,
    ): MemoryCommandResult {
        if (memoryEnabled) syncTaskStateMemories(memoryEnabled = true)
        val userMessage = ChatMessage(
            role = MessageRole.User,
            text = trimmed,
            privacy = MessagePrivacy.LocalOnly,
        )
        sessionStore.replaceActiveSessionMessages(
            sessionStore.activeMessages() + userMessage,
            persistNow = true,
        )
        val persisted = memoryEnabled && runCatching { persist(userMessage) }.getOrDefault(false)
        val assistantText = when {
            persisted -> savedText
            !memoryEnabled -> disabledText
            else -> failedText
        }
        val assistantMessage = ChatMessage(
            role = MessageRole.Assistant,
            text = assistantText,
            privacy = MessagePrivacy.LocalOnly,
        )
        sessionStore.replaceActiveSessionMessages(
            sessionStore.activeMessages() + assistantMessage,
            persistNow = true,
        )
        val rebuildResult = rebuildMemoryIndex(
            memoryEnabled = memoryEnabled,
            skipModelRuntimeWork = skipModelRuntimeWork,
        )
        return MemoryCommandResult(
            userMessage = userMessage,
            assistantMessage = assistantMessage,
            persisted = persisted,
            memoryEnabled = memoryEnabled,
            longTermMemories = loadLongTermMemories(),
            rebuildResult = rebuildResult,
            statusText = when {
                persisted -> "长期记忆已更新"
                !memoryEnabled -> "本地记忆已关闭"
                else -> "本地记忆暂不可用"
            },
        )
    }

    /**
     * Handles an explicit "forget this memory" command.
     */
    fun handleExplicitMemoryForgetCommand(
        trimmed: String,
        memoryEnabled: Boolean,
        skipModelRuntimeWork: Boolean = false,
    ): MemoryCommandResult {
        if (memoryEnabled) syncTaskStateMemories(memoryEnabled = true)
        val userMessage = ChatMessage(
            role = MessageRole.User,
            text = trimmed,
            privacy = MessagePrivacy.LocalOnly,
        )
        sessionStore.replaceActiveSessionMessages(
            sessionStore.activeMessages() + userMessage,
            persistNow = true,
        )
        val target = explicitUserPreferenceForgetFrom(trimmed)
        val removed = target?.let {
            runCatching {
                val removedPreference = longTermMemoryControls.forgetPreference(it)
                val removedFact = longTermMemoryControls.forgetUserFact(it)
                removedPreference || removedFact
            }.getOrDefault(false)
        } == true
        val assistantText = if (removed) {
            "已遗忘这条本地记忆。"
        } else {
            "未找到这条本地记忆。"
        }
        val assistantMessage = ChatMessage(
            role = MessageRole.Assistant,
            text = assistantText,
            privacy = MessagePrivacy.LocalOnly,
        )
        sessionStore.replaceActiveSessionMessages(
            sessionStore.activeMessages() + assistantMessage,
            persistNow = true,
        )
        val rebuildResult = rebuildMemoryIndex(
            memoryEnabled = memoryEnabled,
            skipModelRuntimeWork = skipModelRuntimeWork,
        )
        return MemoryCommandResult(
            userMessage = userMessage,
            assistantMessage = assistantMessage,
            persisted = removed,
            memoryEnabled = memoryEnabled,
            longTermMemories = loadLongTermMemories(),
            rebuildResult = rebuildResult,
            statusText = if (removed) "长期记忆已更新" else "未找到这条记忆",
        )
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun activeTaskStateMemoryIds(): Set<String> =
        runCatching {
            backgroundTaskScheduler.scheduledTasks()
                .filter { task ->
                    task.status == ScheduledTaskStatus.Scheduled ||
                        task.status == ScheduledTaskStatus.Running
                }
                .mapTo(mutableSetOf()) { task -> taskStateMemoryRecordId(task.id) }
        }.getOrDefault(emptySet())

    private fun syncSemanticMemoryRuntime(skipModelRuntimeWork: Boolean) {
        val controller = semanticMemoryRuntimeController ?: return
        if (skipModelRuntimeWork) {
            controller.useMemoryModel(null)
            return
        }
        controller.useMemoryModel(modelRepository.verifiedMemoryEmbeddingModelPath())
    }

    private fun currentSemanticMemoryEnabled(): Boolean =
        semanticMemoryRuntimeController?.semanticMemoryEnabled == true

    private fun currentSemanticMemoryRuntimeStatus(): SemanticMemoryRuntimeStatus =
        semanticMemoryRuntimeController?.semanticMemoryRuntimeStatus
            ?: SemanticMemoryRuntimeStatus.RuntimeUnavailable

    private fun currentSemanticMemoryIndexedRecordCount(): Int =
        semanticMemoryRuntimeController?.semanticMemoryIndexedRecordCount ?: 0

    private fun currentSemanticMemoryLastRebuiltAtMillis(): Long? =
        semanticMemoryRuntimeController?.semanticMemoryLastRebuiltAtMillis

    private fun persistExplicitMemory(
        message: ChatMessage,
        parse: (String) -> String?,
        persist: (String) -> Unit,
    ): Boolean {
        if (message.role != MessageRole.User) return false
        val text = parse(message.text) ?: return false
        persist(text)
        return true
    }

    private fun com.bytedance.zgx.solin.background.ScheduledTask.toTaskStateMemoryText(): String =
        listOf(
            "后台任务=${type.name}",
            "任务记录=${taskStateMemoryRecordId(id)}",
            "状态=${status.name}",
            "触发时间=$triggerAtMillis",
        ).joinToString(separator = "；")
}

/**
 * Result of an explicit memory command (preference/fact/forget) handled by
 * [MemoryController].  The ViewModel uses this to update its message list,
 * long-term memory display, and status text.
 */
data class MemoryCommandResult(
    val userMessage: ChatMessage,
    val assistantMessage: ChatMessage,
    val persisted: Boolean,
    val memoryEnabled: Boolean,
    val longTermMemories: List<LongTermMemorySummary>,
    val rebuildResult: MemoryControllerResult,
    val statusText: String,
)
