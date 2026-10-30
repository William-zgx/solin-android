package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.orchestration.MidRunSteeringAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMidRunSteeringTest {
    private val uiState = MutableStateFlow(ChatUiState(activeSessionId = "s1"))

    @Test
    fun steerDuringModelStreamKeepsALocalCopyAndSendsOnlyALocalOnlyRuntimeCopy() {
        val router = RecordingSteeringRouter(state = AgentRunState.GeneratingAnswer)
        val steering = ChatMidRunSteering(router, uiState)

        val action = steering.offer("换个方向", MessagePrivacy.RemoteEligible, runId = "run-1")

        assertEquals(MidRunSteeringAction.Steer, action)
        val steered = router.steered.single()
        assertEquals("run-1", steered.first)
        assertEquals(MessagePrivacy.LocalOnly, steered.second.single().privacy)
        assertTrue(router.queued.isEmpty())
        assertEquals(1, uiState.value.pendingSteerMessageCount)
        assertTrue(steering.hasHeldMessages())
    }

    @Test
    fun toolPhaseQueuesAndNeverSteers() {
        val router = RecordingSteeringRouter(state = AgentRunState.ExecutingTool)
        val steering = ChatMidRunSteering(router, uiState)

        val action = steering.offer("等工具跑完再看", null, runId = "run-1")

        assertEquals(MidRunSteeringAction.Queue, action)
        assertTrue(router.steered.isEmpty())
        assertEquals("run-1", router.queued.single().first)
    }

    @Test
    fun parkedOrUnknownRunsOnlyHoldTheMessage() {
        val router = RecordingSteeringRouter(state = AgentRunState.AwaitingUserConfirmation)
        val steering = ChatMidRunSteering(router, uiState)

        assertEquals(MidRunSteeringAction.NotSteerable, steering.offer("a", null, runId = "run-1"))
        assertEquals(MidRunSteeringAction.NotSteerable, steering.offer("b", null, runId = null))

        assertTrue(router.steered.isEmpty())
        assertTrue(router.queued.isEmpty())
        assertEquals(2, uiState.value.pendingSteerMessageCount)
    }

    @Test
    fun heldInputPreemptsTheNextModelCallButModelCallsWithoutHeldInputAreOnlyRegistered() {
        val router = RecordingSteeringRouter(state = AgentRunState.ExecutingTool)
        val steering = ChatMidRunSteering(router, uiState)
        val firstCall = Job()

        steering.onModelCallStarted("run-1", firstCall)
        assertEquals(firstCall, router.registered["run-1"])
        assertTrue(router.steered.isEmpty())
        steering.onModelCallFinished("run-1", firstCall)
        assertNull(router.registered["run-1"])

        steering.offer("排队消息", null, runId = "run-1")
        val continuation = Job()
        steering.onModelCallStarted("run-1", continuation)

        assertEquals(listOf("排队消息"), router.steered.single().second.map(ChatMessage::text))
    }

    @Test
    fun takeMergedJoinsHeldMessagesForTheActiveSessionOnly() {
        val router = RecordingSteeringRouter(state = AgentRunState.GeneratingAnswer)
        val steering = ChatMidRunSteering(router, uiState)
        steering.offer("第一条", null, runId = "run-1")
        uiState.value = uiState.value.copy(activeSessionId = "s2")
        steering.offer("第二条", null, runId = "run-1")

        val merged = steering.takeMerged(sessionId = "s2", prompt = "新消息", explicitMessagePrivacy = null)

        assertEquals("第二条\n新消息", merged?.prompt)
        assertFalse(steering.hasHeldMessages())
        assertEquals(0, uiState.value.pendingSteerMessageCount)
        assertNull(steering.takeMerged(sessionId = "s2", prompt = "x", explicitMessagePrivacy = null))
    }

    @Test
    fun mergedPrivacyFailsClosed() {
        assertEquals(
            MessagePrivacy.LocalOnly,
            mergedMidRunPrivacy(listOf(MessagePrivacy.RemoteEligible, MessagePrivacy.LocalOnly)),
        )
        assertEquals(MessagePrivacy.LocalOnly, mergedMidRunPrivacy(listOf(null, MessagePrivacy.LocalOnly)))
        assertEquals(
            MessagePrivacy.RemoteEligible,
            mergedMidRunPrivacy(listOf(MessagePrivacy.RemoteEligible, MessagePrivacy.RemoteEligible)),
        )
        assertNull(mergedMidRunPrivacy(listOf(MessagePrivacy.RemoteEligible, null)))
        assertNull(mergedMidRunPrivacy(listOf(null)))
    }

    @Test
    fun clearAndClearSessionDropHeldMessages() {
        val router = RecordingSteeringRouter(state = AgentRunState.GeneratingAnswer)
        val steering = ChatMidRunSteering(router, uiState)
        steering.offer("a", null, runId = "run-1")
        steering.clearSession("other")
        assertTrue(steering.hasHeldMessages())
        steering.clearSession("s1")
        assertFalse(steering.hasHeldMessages())

        steering.offer("b", null, runId = "run-1")
        steering.clear()
        assertFalse(steering.hasHeldMessages())
        assertEquals(0, uiState.value.pendingSteerMessageCount)
    }

    private class RecordingSteeringRouter(
        private val state: AgentRunState?,
    ) : AssistantRouter by unusedRouter() {
        val steered = mutableListOf<Pair<String, List<ChatMessage>>>()
        val queued = mutableListOf<Pair<String, List<ChatMessage>>>()
        val registered = mutableMapOf<String, Job>()

        override fun runState(runId: String): AgentRunState? = state

        override fun steer(runId: String, messages: List<ChatMessage>): Boolean {
            steered += runId to messages
            return true
        }

        override fun queueUserInput(runId: String, messages: List<ChatMessage>): Boolean {
            queued += runId to messages
            return true
        }

        override fun registerModelCallJob(runId: String, job: Job) {
            registered[runId] = job
        }

        override fun unregisterModelCallJob(runId: String, job: Job?) {
            if (registered[runId] == job || job == null) registered.remove(runId)
        }
    }
}

private fun unusedRouter(): AssistantRouter =
    java.lang.reflect.Proxy.newProxyInstance(
        AssistantRouter::class.java.classLoader,
        arrayOf(AssistantRouter::class.java),
    ) { _, method, _ -> error("unexpected AssistantRouter call: ${method.name}") } as AssistantRouter
