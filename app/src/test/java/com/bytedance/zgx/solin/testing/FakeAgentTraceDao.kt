package com.bytedance.zgx.solin.testing

import com.bytedance.zgx.solin.data.AgentRunEntity
import com.bytedance.zgx.solin.data.AgentSkillRunCheckpointEntity
import com.bytedance.zgx.solin.data.AgentStepEntity
import com.bytedance.zgx.solin.data.AgentTraceDao
import com.bytedance.zgx.solin.data.PendingAgentConfirmationEntity
import com.bytedance.zgx.solin.orchestration.AgentRunState

/** In-memory [AgentTraceDao] mirroring the Room queries' terminal-state guards and ordering. */
class FakeAgentTraceDao : AgentTraceDao {
    private val runs = linkedMapOf<String, AgentRunEntity>()
    private val steps = mutableListOf<AgentStepEntity>()
    private val pendingConfirmations = linkedMapOf<String, PendingAgentConfirmationEntity>()
    private val skillRunCheckpoints = linkedMapOf<Pair<String, String>, AgentSkillRunCheckpointEntity>()

    /** One-shot: the next [insertStep] throws. */
    var failNextStepInsert = false
    var afterStepInserted: ((AgentStepEntity) -> Unit)? = null

    /** When true, [steps] throws, simulating a DB read failure. */
    var failStepReads = false

    /** Bypasses the terminal-state guard, e.g. to simulate a concurrent writer. */
    fun forceRunState(runId: String, state: AgentRunState, updatedAtMillis: Long) {
        runs[runId] = runs.getValue(runId).copy(state = state.name, updatedAtMillis = updatedAtMillis)
    }

    override fun run(runId: String): AgentRunEntity? = runs[runId]

    override fun recentRuns(limit: Int): List<AgentRunEntity> =
        runs.values
            .sortedWith(
                compareByDescending<AgentRunEntity> { it.updatedAtMillis }
                    .thenByDescending { it.createdAtMillis }
                    .thenByDescending { it.id },
            )
            .take(limit)

    override fun runIdsForSession(sessionId: String): List<String> =
        runs.values.filter { it.sessionId == sessionId }.map { it.id }

    override fun insertRunIfAbsent(run: AgentRunEntity): Long {
        if (run.id in runs) return -1L
        runs[run.id] = run
        return 1L
    }

    override fun updateRunState(runId: String, state: String, updatedAtMillis: Long): Int {
        val run = runs[runId] ?: return 0
        if (run.state in TERMINAL_STATES) return 0
        runs[runId] = run.copy(state = state, updatedAtMillis = updatedAtMillis)
        return 1
    }

    override fun compareAndSetRunStateIfExpected(
        runId: String,
        expectedState: String,
        state: String,
        updatedAtMillis: Long,
    ): Int {
        val run = runs[runId] ?: return 0
        if (run.state != expectedState) return 0
        return updateRunState(runId, state, updatedAtMillis)
    }

    override fun touchRun(runId: String, updatedAtMillis: Long): Int {
        val run = runs[runId] ?: return 0
        runs[runId] = run.copy(updatedAtMillis = updatedAtMillis)
        return 1
    }

    override fun nextStepPosition(runId: String): Int =
        steps.filter { it.runId == runId }.maxOfOrNull { it.position + 1 } ?: 0

    override fun insertStep(step: AgentStepEntity) {
        if (failNextStepInsert) {
            failNextStepInsert = false
            error("injected step insert failure")
        }
        steps.removeAll { it.runId == step.runId && it.position == step.position }
        steps += step
        afterStepInserted?.invoke(step)
    }

    override fun steps(runId: String): List<AgentStepEntity> {
        if (failStepReads) error("injected step read failure")
        return steps.filter { it.runId == runId }.sortedBy { it.position }
    }

    override fun deleteStepsForSession(sessionId: String): Int {
        val runIds = runIdsForSession(sessionId).toSet()
        val before = steps.size
        steps.removeAll { it.runId in runIds }
        return before - steps.size
    }

    override fun pendingConfirmations(): List<PendingAgentConfirmationEntity> =
        pendingConfirmations.values.sortedWith(
            compareByDescending<PendingAgentConfirmationEntity> { it.updatedAtMillis }
                .thenByDescending { it.createdAtMillis }
                .thenByDescending { it.runId },
        )

    override fun latestPendingConfirmation(): PendingAgentConfirmationEntity? =
        pendingConfirmations().firstOrNull()

    override fun upsertPendingConfirmation(pending: PendingAgentConfirmationEntity) {
        pendingConfirmations[pending.runId] = pending
    }

    override fun deletePendingConfirmation(runId: String, requestId: String): Int {
        val existing = pendingConfirmations[runId] ?: return 0
        if (existing.requestId != requestId) return 0
        pendingConfirmations.remove(runId)
        return 1
    }

    override fun deletePendingConfirmationsForSession(sessionId: String): Int =
        removeKeys(pendingConfirmations, runIdsForSession(sessionId).toSet()::contains)

    override fun skillRunCheckpoint(runId: String, requestId: String): AgentSkillRunCheckpointEntity? =
        skillRunCheckpoints[runId to requestId]

    override fun skillRunCheckpointsForRun(runId: String): List<AgentSkillRunCheckpointEntity> =
        skillRunCheckpoints.values
            .filter { it.runId == runId }
            .sortedWith(
                compareByDescending<AgentSkillRunCheckpointEntity> { it.updatedAtMillis }
                    .thenByDescending { it.requestId },
            )

    override fun upsertSkillRunCheckpoint(checkpoint: AgentSkillRunCheckpointEntity) {
        skillRunCheckpoints[checkpoint.runId to checkpoint.requestId] = checkpoint
    }

    override fun deleteSkillRunCheckpoint(runId: String, requestId: String): Int =
        if (skillRunCheckpoints.remove(runId to requestId) != null) 1 else 0

    override fun deleteSkillRunCheckpointsForRun(runId: String): Int =
        removeKeys(skillRunCheckpoints) { (checkpointRunId, _) -> checkpointRunId == runId }

    override fun deleteSkillRunCheckpointsForSession(sessionId: String): Int {
        val runIds = runIdsForSession(sessionId).toSet()
        return removeKeys(skillRunCheckpoints) { (checkpointRunId, _) -> checkpointRunId in runIds }
    }

    override fun deleteRunsForSession(sessionId: String): Int =
        removeKeys(runs, runIdsForSession(sessionId).toSet()::contains)

    private fun <K> removeKeys(map: MutableMap<K, *>, predicate: (K) -> Boolean): Int {
        val before = map.size
        map.keys.removeAll(predicate)
        return before - map.size
    }

    private companion object {
        val TERMINAL_STATES = setOf("Completed", "Cancelled", "Failed")
    }
}
