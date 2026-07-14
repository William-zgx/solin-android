package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.BackgroundTaskSummary
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.LongTermMemorySummary
import com.bytedance.zgx.solin.background.BackgroundTaskScheduler
import com.bytedance.zgx.solin.background.BackgroundTaskUseCases
import com.bytedance.zgx.solin.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.solin.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.solin.background.isActivePeriodicCheck
import com.bytedance.zgx.solin.memory.LongTermMemoryControls
import com.bytedance.zgx.solin.memory.taskStateMemoryRecordId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns background-task list refresh, cancellation, and periodic-check policy updates.
 *
 * Extracted from [com.bytedance.zgx.solin.SolinViewModel] (Wave 6 Track C6b). Memory sync stays
 * injected so task-state long-term memories remain coordinated with MemoryController.
 * Constructed before `_uiState` so [createInitialState] can snapshot tasks; [bindUiState] is
 * called once the ViewModel state flow exists.
 */
class BackgroundTaskController(
    private val backgroundTaskScheduler: BackgroundTaskScheduler,
    private val longTermMemoryControls: LongTermMemoryControls,
    private val backgroundTaskUseCases: BackgroundTaskUseCases,
    private val syncTaskStateMemories: () -> Unit,
    private val loadLongTermMemories: () -> List<LongTermMemorySummary>,
) {
    private var uiState: MutableStateFlow<ChatUiState>? = null

    fun bindUiState(uiState: MutableStateFlow<ChatUiState>) {
        this.uiState = uiState
    }

    fun loadBackgroundTasks(): List<BackgroundTaskSummary> =
        backgroundTaskUseCases.snapshot().activeTasks

    fun loadBackgroundTaskHistory(): List<BackgroundTaskSummary> =
        backgroundTaskUseCases.snapshot().history

    fun loadPeriodicCheckPolicy(): PeriodicCheckPolicySummary =
        backgroundTaskUseCases.snapshot().periodicCheckPolicy

    fun recoverBackgroundTasksOnStartup() {
        val state = uiState ?: return
        backgroundTaskScheduler.rescheduleScheduledReminders()
        backgroundTaskScheduler.reconcilePeriodicCheckOnStartup()
        state.update {
            it.copy(
                backgroundTasks = loadBackgroundTasks(),
                backgroundTaskHistory = loadBackgroundTaskHistory(),
                periodicCheckPolicy = loadPeriodicCheckPolicy(),
            )
        }
    }

    fun refreshBackgroundTasks() {
        val state = uiState ?: return
        syncTaskStateMemories()
        val snapshot = backgroundTaskUseCases.snapshot()
        state.update {
            it.copy(
                backgroundTasks = snapshot.activeTasks,
                backgroundTaskHistory = snapshot.history,
                periodicCheckPolicy = snapshot.periodicCheckPolicy,
                longTermMemories = loadLongTermMemories(),
            )
        }
    }

    fun cancelBackgroundTask(taskId: String) {
        val state = uiState ?: return
        if (state.value.isBusy) return
        val result = backgroundTaskUseCases.cancelScheduledTask(taskId)
        syncTaskStateMemories()
        state.update { current ->
            current.copy(
                backgroundTasks = result.snapshot.activeTasks,
                backgroundTaskHistory = result.snapshot.history,
                periodicCheckPolicy = result.snapshot.periodicCheckPolicy,
                longTermMemories = loadLongTermMemories(),
                statusText = result.statusText,
            )
        }
    }

    fun setPeriodicCheckPolicy(request: PeriodicCheckScheduleRequest) {
        val state = uiState ?: return
        if (state.value.isBusy) return
        val previousPolicy = loadPeriodicCheckPolicy()
        val result = backgroundTaskUseCases.setPeriodicCheckPolicy(request)
        if (
            result.succeeded &&
            result.snapshot.periodicCheckPolicy.request.enabled &&
            !previousPolicy.isActivePeriodicCheck()
        ) {
            longTermMemoryControls.unsuppressAutoManagedTaskState(
                taskStateMemoryRecordId(PeriodicCheckScheduleRequest.TASK_ID),
            )
        }
        syncTaskStateMemories()
        state.update { current ->
            current.copy(
                backgroundTasks = result.snapshot.activeTasks,
                backgroundTaskHistory = result.snapshot.history,
                periodicCheckPolicy = result.snapshot.periodicCheckPolicy,
                longTermMemories = loadLongTermMemories(),
                statusText = result.statusText,
            )
        }
    }

    fun disablePeriodicCheckPolicy() {
        val state = uiState ?: return
        if (state.value.isBusy) return
        val result = backgroundTaskUseCases.disablePeriodicCheckPolicy()
        syncTaskStateMemories()
        state.update { current ->
            current.copy(
                backgroundTasks = result.snapshot.activeTasks,
                backgroundTaskHistory = result.snapshot.history,
                periodicCheckPolicy = result.snapshot.periodicCheckPolicy,
                longTermMemories = loadLongTermMemories(),
                statusText = result.statusText,
            )
        }
    }
}
