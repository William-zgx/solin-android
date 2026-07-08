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
import kotlinx.coroutines.withContext

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
 * Encapsulates all memory-related business logic currently living in [SolinViewModel].
 *
 * This class is intentionally **not** a ViewModel; it is a pure controller that takes
 * its dependencies via the constructor and returns result snapshots that the caller
 * (typically the ViewModel) applies to its own UI state.
 *
 * TODO(Phase-3 refactor): Wire [com.bytedance.zgx.solin.SolinViewModel] to delegate
 *   `rebuildMemoryIndex`, `loadLongTermMemories`, `handleExplicitPreferenceCommand`,
 *   `handleExplicitUserFactCommand`, `handleExplicitMemoryCommand`,
 *   `handleExplicitMemoryForgetCommand`, and `syncTaskStateMemories` to this class.
 */
class MemoryController(
    private val memoryIndex: MemoryIndex,
    private val longTermMemoryControls: LongTermMemoryControls,
    private val sessionStore: SessionStore,
    private val modelRepository: ModelRepositoryFacade,
    private val backgroundTaskScheduler: BackgroundTaskScheduler,
    private val semanticMemoryRuntimeController: SemanticMemoryRuntimeController? = null,
    private val ioDispatcher: CoroutineDispatcher,
) {

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Rebuilds the in-memory index from the last 500 session messages and returns
     * a [MemoryControllerResult] describing the new state.
     *
     * Mirrors `SolinViewModel.rebuildMemoryIndex()`.
     */
    suspend fun rebuildMemoryIndex(
        memoryEnabled: Boolean,
        skipModelRuntimeWork: Boolean = false,
    ): MemoryControllerResult = withContext(ioDispatcher) {
        runCatching {
            syncSemanticMemoryRuntime(skipModelRuntimeWork)
            memoryIndex.enabled = memoryEnabled
            memoryIndex.rebuild(sessionStore.allMessages(limit = 500))
            MemoryControllerResult(
                semanticMemoryEnabled = currentSemanticMemoryEnabled(),
                semanticMemoryRuntimeStatus = currentSemanticMemoryRuntimeStatus(),
                semanticMemoryIndexedRecordCount = currentSemanticMemoryIndexedRecordCount(),
                semanticMemoryLastRebuiltAtMillis = currentSemanticMemoryLastRebuiltAtMillis(),
            )
        }.getOrElse {
            MemoryControllerResult(
                semanticMemoryEnabled = currentSemanticMemoryEnabled(),
                semanticMemoryRuntimeStatus = currentSemanticMemoryRuntimeStatus(),
                semanticMemoryIndexedRecordCount = currentSemanticMemoryIndexedRecordCount(),
                semanticMemoryLastRebuiltAtMillis = currentSemanticMemoryLastRebuiltAtMillis(),
                memoryHitsCleared = true,
                longTermMemories = emptyList(),
                statusText = "本地记忆暂不可用",
            )
        }
    }

    /**
     * Returns the current list of long-term memory summaries.
     *
     * Mirrors `SolinViewModel.loadLongTermMemories()`.
     */
    suspend fun loadLongTermMemories(): List<LongTermMemorySummary> =
        withContext(ioDispatcher) {
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
        }

    /**
     * Syncs task-state memories: removes stale task-state records and indexes
     * currently-active scheduled tasks.
     *
     * Mirrors `SolinViewModel.syncTaskStateMemories()`.
     */
    suspend fun syncTaskStateMemories(memoryEnabled: Boolean): Unit =
        withContext(ioDispatcher) {
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
            Unit
        }

    /**
     * Handles an explicit "remember this preference" command.
     *
     * Mirrors `SolinViewModel.handleExplicitPreferenceCommand()`.
     */
    suspend fun handleExplicitPreferenceCommand(
        trimmed: String,
        memoryEnabled: Boolean,
    ): MemoryCommandResult =
        handleExplicitMemoryCommand(
            trimmed = trimmed,
            memoryEnabled = memoryEnabled,
            persist = ::persistExplicitPreferenceMemory,
            savedText = "已记住这条本地偏好。你可以在长期记忆中查看或删除。",
            disabledText = "本地记忆已关闭，未保存这条偏好。",
            failedText = "本地记忆暂不可用，未保存这条偏好。",
        )

    /**
     * Handles an explicit "remember this fact about me" command.
     *
     * Mirrors `SolinViewModel.handleExplicitUserFactCommand()`.
     */
    suspend fun handleExplicitUserFactCommand(
        trimmed: String,
        memoryEnabled: Boolean,
    ): MemoryCommandResult =
        handleExplicitMemoryCommand(
            trimmed = trimmed,
            memoryEnabled = memoryEnabled,
            persist = ::persistExplicitUserFactMemory,
            savedText = "已记住这条本地事实。你可以在长期记忆中查看或删除。",
            disabledText = "本地记忆已关闭，未保存这条事实。",
            failedText = "本地记忆暂不可用，未保存这条事实。",
        )

    /**
     * Generic handler for explicit memory commands (preference / fact).
     *
     * Mirrors `SolinViewModel.handleExplicitMemoryCommand()`.
     */
    suspend fun handleExplicitMemoryCommand(
        trimmed: String,
        memoryEnabled: Boolean,
        persist: (ChatMessage) -> Boolean,
        savedText: String,
        disabledText: String,
        failedText: String,
    ): MemoryCommandResult = withContext(ioDispatcher) {
        if (memoryEnabled) syncTaskStateMemories(memoryEnabled = true)
        val userMessage = ChatMessage(
            role = MessageRole.User,
            text = trimmed,
            privacy = MessagePrivacy.LocalOnly,
        )
        // Persist the user message into the active session.
        sessionStore.replaceActiveSessionMessages(
            sessionStore.activeMessages() + userMessage,
            persistNow = true,
        )
        val persisted = memoryEnabled && persist(userMessage)
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
        )
        MemoryCommandResult(
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
     *
     * Mirrors `SolinViewModel.handleExplicitMemoryForgetCommand()`.
     */
    suspend fun handleExplicitMemoryForgetCommand(
        trimmed: String,
        memoryEnabled: Boolean,
    ): MemoryCommandResult = withContext(ioDispatcher) {
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
        )
        MemoryCommandResult(
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
    // Internal helpers (mirror private helpers in SolinViewModel)
    // ------------------------------------------------------------------

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

    private fun persistExplicitPreferenceMemory(message: ChatMessage): Boolean =
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

    private fun persistExplicitUserFactMemory(message: ChatMessage): Boolean =
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

    private fun persistExplicitMemory(
        message: ChatMessage,
        parse: (String) -> String?,
        persist: (String) -> Unit,
    ): Boolean {
        if (message.role != MessageRole.User) return false
        val text = parse(message.text) ?: return false
        return runCatching {
            persist(text)
            // Side-effect: refresh long-term memories after a successful persist.
            longTermMemoryControls.savedRecords()
        }.isSuccess
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
