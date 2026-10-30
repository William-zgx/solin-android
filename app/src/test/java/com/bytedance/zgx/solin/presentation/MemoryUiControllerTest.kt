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
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.memory.FakeMemoryRecordStore
import com.bytedance.zgx.solin.memory.MemoryArgumentResolver
import com.bytedance.zgx.solin.memory.MemoryArgumentSlot
import com.bytedance.zgx.solin.memory.MemoryController
import com.bytedance.zgx.solin.memory.MemoryRecordSource
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

    @Test
    fun userMessageProducesSuggestionWithoutStoringAnything() {
        val harness = MemoryUiControllerHarness()

        harness.controller.onUserMessage("对了，我对花生过敏，推荐几家餐厅")

        assertEquals(listOf("我对花生过敏"), harness.controller.memoryPanelState.value.suggestions.map { it.fact })
        assertTrue(harness.repository.savedRecords().isEmpty())
        assertTrue(harness.uiState.value.longTermMemories.isEmpty())
    }

    @Test
    fun confirmStoresTheSuggestedFactLocallyAndClearsTheChip() {
        val harness = MemoryUiControllerHarness()
        harness.controller.onUserMessage("我家在北京市朝阳区望京西园三区")
        val suggestion = harness.controller.memoryPanelState.value.suggestions.single()

        harness.controller.confirmMemorySuggestion(suggestion.id)

        val saved = harness.repository.savedRecords().single()
        assertEquals("用户偏好：我家在北京市朝阳区望京西园三区", saved.text)
        assertEquals(MessagePrivacy.LocalOnly, saved.privacy)
        assertEquals(MemoryRecordSource.ExplicitUser, saved.source)
        assertEquals(listOf(saved.id), harness.uiState.value.longTermMemories.map { it.id })
        assertTrue(harness.controller.memoryPanelState.value.suggestions.isEmpty())
        assertTrue(harness.sessionStore.activeMessages().isEmpty())
        assertEquals(
            "北京市朝阳区望京西园三区",
            MemoryArgumentResolver.resolve(MemoryArgumentSlot.HomeAddress, harness.repository)?.value,
        )

        // Already saved: the same fact is not suggested again.
        harness.controller.onUserMessage("我家在北京市朝阳区望京西园三区")
        assertTrue(harness.controller.memoryPanelState.value.suggestions.isEmpty())
    }

    @Test
    fun confirmIsIgnoredWhileBusyAndForUnknownIds() {
        val harness = MemoryUiControllerHarness()
        harness.controller.onUserMessage("我对花生过敏")
        val suggestion = harness.controller.memoryPanelState.value.suggestions.single()

        harness.controller.confirmMemorySuggestion("unknown")
        harness.uiState.value = harness.uiState.value.copy(isBusy = true)
        harness.controller.confirmMemorySuggestion(suggestion.id)

        assertTrue(harness.repository.savedRecords().isEmpty())
        assertEquals(listOf(suggestion.id), harness.controller.memoryPanelState.value.suggestions.map { it.id })
    }

    @Test
    fun dismissIsRememberedForThatFactText() {
        val harness = MemoryUiControllerHarness()
        harness.controller.onUserMessage("我对花生过敏")
        val suggestion = harness.controller.memoryPanelState.value.suggestions.single()

        harness.controller.dismissMemorySuggestion(suggestion.id)
        harness.controller.onUserMessage("我对花生过敏。")

        assertTrue(harness.controller.memoryPanelState.value.suggestions.isEmpty())
        assertTrue(harness.repository.savedRecords().isEmpty())

        harness.controller.onUserMessage("我对青霉素过敏")
        assertEquals(listOf("我对青霉素过敏"), harness.controller.memoryPanelState.value.suggestions.map { it.fact })
    }

    @Test
    fun noSuggestionsWhileMemoryIsDisabledAndDisablingClearsPendingOnes() {
        val disabled = MemoryUiControllerHarness(memoryEnabled = false)
        disabled.controller.onUserMessage("我对花生过敏")
        assertTrue(disabled.controller.memoryPanelState.value.suggestions.isEmpty())

        val enabled = MemoryUiControllerHarness()
        enabled.controller.onUserMessage("我对花生过敏")
        enabled.controller.updateMemoryEnabled(false)
        assertTrue(enabled.controller.memoryPanelState.value.suggestions.isEmpty())
        assertTrue(enabled.repository.savedRecords().isEmpty())
    }

    @Test
    fun pendingSuggestionsAreCappedNewestFirst() {
        val harness = MemoryUiControllerHarness()
        listOf("我对花生过敏", "我对青霉素过敏", "我不吃辣", "我住在上海浦东").forEach(harness.controller::onUserMessage)

        assertEquals(
            listOf("我住在上海浦东", "我不吃辣", "我对青霉素过敏"),
            harness.controller.memoryPanelState.value.suggestions.map { it.fact },
        )
    }
}

/** Minimal real [MemoryController] + [MemoryUiController] wiring over in-memory fakes. */
internal class MemoryUiControllerHarness(
    memoryEnabled: Boolean = true,
) {
    val repository = MemoryRepository(recordStore = FakeMemoryRecordStore())
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
