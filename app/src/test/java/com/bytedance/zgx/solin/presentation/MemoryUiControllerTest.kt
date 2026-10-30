package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatSessionSummary
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.background.BackgroundTaskScheduler
import com.bytedance.zgx.solin.background.ReminderScheduleRequest
import com.bytedance.zgx.solin.background.ScheduledTask
import com.bytedance.zgx.solin.data.FirstRunSetupStore
import com.bytedance.zgx.solin.data.ModelRepositoryFacade
import com.bytedance.zgx.solin.data.SessionStore
import com.bytedance.zgx.solin.memory.MemoryController
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.orchestration.MemoryRetrievalOutcome
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryUiControllerTest {
    @Test
    fun degradedRetrievalShowsMemoryUnavailableStatusUntilAHealthyRetrieval() {
        val controller = MemoryUiControllerHarness().controller

        assertNull(controller.memoryPanelState.value.retrievalStatusText)

        controller.onMemoryRetrievalOutcome(
            MemoryRetrievalOutcome(hits = emptyList(), attempted = true, degraded = true),
        )
        assertTrue(controller.memoryPanelState.value.retrievalDegraded)
        assertEquals("记忆暂不可用", controller.memoryPanelState.value.retrievalStatusText)

        controller.onMemoryRetrievalOutcome(
            MemoryRetrievalOutcome(hits = emptyList(), attempted = true, degraded = false),
        )
        assertFalse(controller.memoryPanelState.value.retrievalDegraded)
        assertNull(controller.memoryPanelState.value.retrievalStatusText)
    }

    @Test
    fun skippedOrMissingRetrievalOutcomeIsNotDegraded() {
        val controller = MemoryUiControllerHarness().controller
        controller.onMemoryRetrievalOutcome(
            MemoryRetrievalOutcome(hits = emptyList(), attempted = true, degraded = true),
        )

        controller.onMemoryRetrievalOutcome(MemoryRetrievalOutcome.Skipped)
        assertFalse(controller.memoryPanelState.value.retrievalDegraded)

        controller.onMemoryRetrievalOutcome(
            MemoryRetrievalOutcome(hits = emptyList(), attempted = true, degraded = true),
        )
        controller.onMemoryRetrievalOutcome(null)
        assertFalse(controller.memoryPanelState.value.retrievalDegraded)
    }
}

/** Minimal real [MemoryController] + [MemoryUiController] wiring over in-memory fakes. */
internal class MemoryUiControllerHarness(
    memoryEnabled: Boolean = true,
) {
    val repository = MemoryRepository()
    val sessionStore = InMemorySessionStore()
    val uiState = MutableStateFlow(ChatUiState(memoryEnabled = memoryEnabled))
    private val memoryController = MemoryController(
        memoryIndex = repository,
        longTermMemoryControls = repository,
        sessionStore = sessionStore,
        modelRepository = unusedModelRepository(),
        backgroundTaskScheduler = NoTasksScheduler,
        semanticMemoryRuntimeController = repository,
        ioDispatcher = Dispatchers.Unconfined,
    )
    val controller = MemoryUiController(
        memoryController = memoryController,
        firstRunSetupStore = InMemoryFirstRunSetupStore(memoryEnabled),
        sessionStore = sessionStore,
        uiState = uiState,
        skipModelRuntimeWork = true,
    )

    private fun unusedModelRepository(): ModelRepositoryFacade =
        Proxy.newProxyInstance(
            ModelRepositoryFacade::class.java.classLoader,
            arrayOf(ModelRepositoryFacade::class.java),
        ) { _, method, _ -> error("ModelRepositoryFacade.${method.name} is not expected in memory UI tests") }
            as ModelRepositoryFacade
}

internal class InMemorySessionStore : SessionStore {
    private val messages = mutableListOf<ChatMessage>()
    override val activeSessionId: String = "session-1"
    override fun summaries(): List<ChatSessionSummary> = emptyList()
    override fun activeMessages(): List<ChatMessage> = messages.toList()
    override fun allMessages(limit: Int): List<ChatMessage> = messages.takeLast(limit)
    override fun createNewSession(): List<ChatMessage> = emptyList<ChatMessage>().also { messages.clear() }
    override fun selectSession(sessionId: String): List<ChatMessage>? = null
    override fun deleteActiveSession(): List<ChatMessage>? = null
    override fun replaceActiveSessionMessages(messages: List<ChatMessage>, persistNow: Boolean) {
        this.messages.clear()
        this.messages += messages
    }
    override fun persistActiveSessionFrom(messages: List<ChatMessage>) = Unit
}

private class InMemoryFirstRunSetupStore(private var memoryEnabled: Boolean) : FirstRunSetupStore {
    override fun isSetupDismissed(): Boolean = true
    override fun markSetupDismissed() = Unit
    override fun isMemoryEnabled(): Boolean = memoryEnabled
    override fun setMemoryEnabled(enabled: Boolean) {
        memoryEnabled = enabled
    }
}

private object NoTasksScheduler : BackgroundTaskScheduler {
    override fun scheduleReminder(request: ReminderScheduleRequest): Result<ScheduledTask> =
        Result.failure(UnsupportedOperationException())
    override fun cancel(taskId: String): Result<Unit> = Result.success(Unit)
}
