package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.data.AgentRunEntity
import com.bytedance.zgx.pocketmind.data.AgentStepEntity
import com.bytedance.zgx.pocketmind.data.AgentTraceDao
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTraceStoreTest {
    @Test
    fun inMemoryStoreKeepsTypedStepsAndStepSummaries() {
        var now = 1_000L
        val store = InMemoryAgentTraceStore(clockMillis = { now++ })
        val run = store.createRun("hello")

        store.appendStep(run.id, AgentStep.AssistantResponded("first\nanswer"))

        assertEquals(listOf(AgentStep.AssistantResponded("first\nanswer")), store.steps(run.id))
        val summary = store.stepSummaries(run.id).single()
        assertEquals(run.id, summary.runId)
        assertEquals(0, summary.position)
        assertEquals("AssistantResponded", summary.type)
        assertTrue(summary.summary.contains("first answer"))
        assertTrue(summary.json.contains("textPreview"))
    }

    @Test
    fun roomStorePersistsRunAndStepSummariesWithoutRawToolArguments() {
        var now = 2_000L
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { now++ },
            runIdFactory = { "run-persisted" },
        )
        val run = store.createRun("share something")
        val request = ToolRequest(
            id = "request-1",
            toolName = "share_text",
            arguments = mapOf("text" to "raw text that should stay out of trace json"),
            reason = "Share drafted text",
        )
        val draft = ActionDraft(
            functionName = "share_text",
            title = "Share",
            summary = "Share drafted text",
            parameters = request.arguments,
        )

        store.updateState(run.id, AgentRunState.Planning)
        store.appendStep(run.id, AgentStep.ToolRequested(request, draft))

        assertEquals(AgentRunState.Planning, store.run(run.id)?.state)
        assertEquals(listOf(AgentStep.ToolRequested(request, draft)), store.steps(run.id))
        val persistedStep = store.stepSummaries(run.id).single()
        assertEquals("ToolRequested", persistedStep.type)
        assertTrue(persistedStep.summary.contains("share_text"))
        assertTrue(persistedStep.json.contains("argumentKeys"))
        assertFalse(persistedStep.json.contains("raw text that should stay out"))

        val restartedStore = RoomAgentTraceStore(traceDao = dao)
        assertEquals(AgentRunState.Planning, restartedStore.run(run.id)?.state)
        assertEquals(emptyList<AgentStep>(), restartedStore.steps(run.id))
        assertEquals(listOf(persistedStep), restartedStore.stepSummaries(run.id))
    }

    private class FakeAgentTraceDao : AgentTraceDao {
        private val runs = linkedMapOf<String, AgentRunEntity>()
        private val steps = mutableListOf<AgentStepEntity>()

        override fun run(runId: String): AgentRunEntity? =
            runs[runId]

        override fun upsertRun(run: AgentRunEntity) {
            runs[run.id] = run
        }

        override fun updateRunState(runId: String, state: String, updatedAtMillis: Long): Int {
            val run = runs[runId] ?: return 0
            runs[runId] = run.copy(state = state, updatedAtMillis = updatedAtMillis)
            return 1
        }

        override fun touchRun(runId: String, updatedAtMillis: Long): Int {
            val run = runs[runId] ?: return 0
            runs[runId] = run.copy(updatedAtMillis = updatedAtMillis)
            return 1
        }

        override fun nextStepPosition(runId: String): Int =
            steps
                .filter { step -> step.runId == runId }
                .maxOfOrNull { step -> step.position + 1 }
                ?: 0

        override fun insertStep(step: AgentStepEntity) {
            steps.removeAll { existing ->
                existing.runId == step.runId && existing.position == step.position
            }
            steps += step
        }

        override fun steps(runId: String): List<AgentStepEntity> =
            steps
                .filter { step -> step.runId == runId }
                .sortedBy { step -> step.position }
    }
}
