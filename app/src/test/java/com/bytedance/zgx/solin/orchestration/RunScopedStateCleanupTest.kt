package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.memory.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunScopedStateCleanupTest {
    private val eventBus = RecordingSolinEventBus()
    private val runtime = AgentLoopRuntime(
        memoryIndex = MemoryRepository(),
        actionPlanningRuntime = ScriptedActionRuntime(),
        traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        eventBus = eventBus,
    )

    private fun startedRun(): String =
        runtime.runOnce(
            input = "普通问题",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            options = AgentRunOptions(remoteGuiDrivingEnabled = true, reduceDeviceActionConfirmations = true),
        ).run.id

    @Test
    fun liveRunKeepsItsScopedState() {
        val runId = startedRun()

        assertTrue(runId in runtime.runIdsWithScopedState())
    }

    @Test
    fun completedRunDropsAllScopedStateAfterPublishingRunEnded() {
        val runId = startedRun()

        val completed = runtime.observeModelResult(runId, "回答")

        assertEquals(AgentRunState.Completed, completed?.run?.state)
        assertFalse(runId in runtime.runIdsWithScopedState())
        val ended = eventBus.ofType<SolinEvent.Agent.RunEnded>().single { it.runId == runId }
        // Lifecycle metadata is still read before the cleanup.
        assertNotNull(ended.durationMs)
    }

    @Test
    fun failedRunDropsAllScopedState() {
        val runId = startedRun()

        runtime.failModelGeneration(runId, "boom")

        assertFalse(runId in runtime.runIdsWithScopedState())
    }

    @Test
    fun cancelledRunDropsAllScopedState() {
        val runId = startedRun()

        runtime.cancelRun(runId)

        assertFalse(runId in runtime.runIdsWithScopedState())
    }
}
