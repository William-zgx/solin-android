package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.memory.MemoryIndex
import com.bytedance.zgx.solin.memory.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrievalTest {
    @Test
    fun healthySearchReturnsHitsAndIsNotDegraded() {
        val repository = MemoryRepository().apply { indexUserFact("blood", "我的血型是O型") }
        val retrieval = MemoryRetrieval(repository)

        val outcome = retrieval.retrieve("我是什么血型", memoryEnabled = true)

        assertEquals(listOf("blood"), outcome.hits.map { it.id })
        assertTrue(outcome.attempted)
        assertFalse(outcome.degraded)
        assertEquals(outcome, retrieval.lastOutcome.value)
    }

    @Test
    fun searchFailureIsReportedAsDegradedInsteadOfBeingSwallowed() {
        val retrieval = MemoryRetrieval(ThrowingMemoryIndex())

        val outcome = retrieval.retrieve("我是什么血型", memoryEnabled = true)

        assertTrue(outcome.hits.isEmpty())
        assertTrue(outcome.attempted)
        assertTrue(outcome.degraded)
        assertTrue(retrieval.lastOutcome.value!!.degraded)
    }

    @Test
    fun disabledMemorySkipsSearchAndClearsDegradedFlag() {
        val index = ThrowingMemoryIndex()
        val retrieval = MemoryRetrieval(index)
        retrieval.retrieve("first", memoryEnabled = true)

        val outcome = retrieval.retrieve("second", memoryEnabled = false)

        assertEquals(MemoryRetrievalOutcome.Skipped, outcome)
        assertEquals(1, index.searchCount)
        assertFalse(retrieval.lastOutcome.value!!.degraded)
    }

    @Test
    fun usesConfiguredTopK() {
        val index = RecordingMemoryIndex()

        MemoryRetrieval(index).retrieve("query", memoryEnabled = true)

        assertEquals(listOf(MemoryRetrieval.DEFAULT_TOP_K), index.topKs)
    }

    @Test
    fun lastOutcomeIsNullBeforeFirstRetrieval() {
        assertNull(MemoryRetrieval(RecordingMemoryIndex()).lastOutcome.value)
    }

    @Test
    fun agentLoopRuntimeContinuesWithEmptyContextAndExposesDegradedRetrieval() {
        val retrieval = MemoryRetrieval(ThrowingMemoryIndex())
        val runtime = AgentLoopRuntime(
            memoryIndex = ThrowingMemoryIndex(),
            actionPlanningRuntime = ScriptedActionRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            memoryRetrieval = retrieval,
        )

        val result = runtime.runOnce("普通问题", setOf(ModelCapability.Chat), memoryEnabled = true)

        assertEquals(AgentRunState.GeneratingAnswer, result.run.state)
        require(result.plan is AgentPlan.Answer)
        assertTrue(result.plan.memoryHits.isEmpty())
        assertTrue(result.steps.any { step -> step is AgentStep.ContextLoaded && step.memoryHits.isEmpty() })
        assertTrue(retrieval.lastOutcome.value!!.degraded)
    }

    @Test
    fun agentLoopRuntimeUsesRetrievalHitsForAnswerContext() {
        val repository = MemoryRepository().apply { indexUserFact("blood", "我的血型是O型") }
        val retrieval = MemoryRetrieval(repository)
        val runtime = AgentLoopRuntime(
            memoryIndex = repository,
            actionPlanningRuntime = ScriptedActionRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            memoryRetrieval = retrieval,
        )

        val result = runtime.runOnce("我是什么血型", setOf(ModelCapability.Chat), memoryEnabled = true)

        require(result.plan is AgentPlan.Answer)
        assertEquals(listOf("blood"), result.plan.memoryHits.map { it.id })
        assertFalse(retrieval.lastOutcome.value!!.degraded)
    }

    private class ThrowingMemoryIndex : MemoryIndex {
        var searchCount = 0
        override var enabled: Boolean = true
        override fun rebuild(messages: List<ChatMessage>) = Unit
        override fun index(id: String, text: String) = Unit
        override fun search(query: String, topK: Int): List<MemoryHit> {
            searchCount++
            error("memory unavailable")
        }
        override fun buildContext(hits: List<MemoryHit>): String = error("memory context unavailable")
    }

    private class RecordingMemoryIndex : MemoryIndex {
        val topKs = mutableListOf<Int>()
        override var enabled: Boolean = true
        override fun rebuild(messages: List<ChatMessage>) = Unit
        override fun index(id: String, text: String) = Unit
        override fun search(query: String, topK: Int): List<MemoryHit> {
            topKs += topK
            return emptyList()
        }
        override fun buildContext(hits: List<MemoryHit>): String = ""
    }
}
