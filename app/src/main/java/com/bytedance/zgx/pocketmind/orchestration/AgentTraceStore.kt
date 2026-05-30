package com.bytedance.zgx.pocketmind.orchestration

import java.util.concurrent.atomic.AtomicLong

interface AgentTraceStore {
    fun createRun(input: String): AgentRun
    fun updateState(runId: String, state: AgentRunState): AgentRun
    fun appendStep(runId: String, step: AgentStep)
    fun steps(runId: String): List<AgentStep>
}

class InMemoryAgentTraceStore(
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : AgentTraceStore {
    private val nextRunId = AtomicLong(0L)
    private val runs = linkedMapOf<String, AgentRun>()
    private val runSteps = linkedMapOf<String, MutableList<AgentStep>>()

    override fun createRun(input: String): AgentRun {
        val now = clockMillis()
        val run = AgentRun(
            id = "run-${nextRunId.incrementAndGet()}",
            input = input,
            state = AgentRunState.Created,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        runs[run.id] = run
        runSteps[run.id] = mutableListOf()
        return run
    }

    override fun updateState(runId: String, state: AgentRunState): AgentRun {
        val existing = runs.getValue(runId)
        val updated = existing.copy(
            state = state,
            updatedAtMillis = clockMillis(),
        )
        runs[runId] = updated
        return updated
    }

    override fun appendStep(runId: String, step: AgentStep) {
        runSteps.getValue(runId).add(step)
    }

    override fun steps(runId: String): List<AgentStep> =
        runSteps[runId].orEmpty().toList()
}
