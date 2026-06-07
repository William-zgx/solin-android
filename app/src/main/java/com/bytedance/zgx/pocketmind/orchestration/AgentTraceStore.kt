package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.audit.ToolAuditSummaryRedactor
import com.bytedance.zgx.pocketmind.data.AgentSkillRunCheckpointEntity
import com.bytedance.zgx.pocketmind.data.AgentRunEntity
import com.bytedance.zgx.pocketmind.data.AgentStepEntity
import com.bytedance.zgx.pocketmind.data.AgentTraceDao
import com.bytedance.zgx.pocketmind.data.PendingAgentConfirmationEntity
import com.bytedance.zgx.pocketmind.skill.SkillManifest
import com.bytedance.zgx.pocketmind.skill.SkillPlan
import com.bytedance.zgx.pocketmind.skill.SkillRequest
import com.bytedance.zgx.pocketmind.skill.SkillRunCheckpoint
import com.bytedance.zgx.pocketmind.skill.SkillRunCheckpointPhase
import com.bytedance.zgx.pocketmind.skill.SkillStep
import com.bytedance.zgx.pocketmind.skill.validateStructure
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import com.bytedance.zgx.pocketmind.tool.UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX
import com.bytedance.zgx.pocketmind.tool.isUnverifiedExternalLaunch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

data class AgentTraceStepSummary(
    val runId: String,
    val position: Int,
    val type: String,
    val summary: String,
    val json: String,
    val createdAtMillis: Long,
)

data class AgentTraceRunSummary(
    val run: AgentRun,
    val steps: List<AgentTraceStepSummary>,
    val runDataReceiptStep: AgentTraceStepSummary? = null,
)

private val toolObservedCompletionMetadataAllowlist = listOf(
    "completionState",
    "completionVerified",
    "exceptionType",
    "externalOutcome",
    "externalOutcomeSource",
    "intentAction",
    "metadataPolicy",
    "ocrTextIncluded",
    "payloadMimeType",
    "rawPayloadIncluded",
    "rawTreeIncluded",
    "settingsAction",
    "screenTextIncluded",
    "specialAccess",
    "targetKind",
    "targetId",
    "targetPackage",
    "targetUriHost",
    "targetUriPort",
    "targetUriScheme",
    "recoveryTaskId",
    "recoveryToolName",
)

private const val REDACTED_AGENT_RUN_INPUT = "[redacted]"
private const val UNRESTORABLE_PENDING_CONFIRMATION_REASON =
    "Pending tool confirmation could not be restored after process restart."
private const val UNRESTORABLE_PENDING_EXTERNAL_OUTCOME_REASON =
    "Pending external outcome could not be restored after process restart."

private data class PendingConfirmationRepairResult(
    val validPendingRunIds: Set<String>,
    val failedRunIds: Set<String>,
)

interface AgentTraceStore {
    fun createRun(input: String, sessionId: String? = null): AgentRun
    fun run(runId: String): AgentRun?
    fun updateState(runId: String, state: AgentRunState): AgentRun
    fun appendStep(runId: String, step: AgentStep)
    fun steps(runId: String): List<AgentStep>
    fun stepSummaries(runId: String): List<AgentTraceStepSummary>
    fun recentRunSummaries(limit: Int = 5, stepLimit: Int = 20): List<AgentTraceRunSummary>
    fun savePendingConfirmation(snapshot: PendingToolConfirmationSnapshot)
    fun latestPendingConfirmation(sessionId: String? = null): PendingToolConfirmationSnapshot?
    fun clearPendingConfirmation(runId: String, requestId: String): Boolean
    fun clearPendingConfirmationsForRun(runId: String): Int
    fun nextActionInput(runId: String): String?
    fun continuationCursor(runId: String): AgentContinuationCursor?
    fun failStaleInFlightRuns(reason: String): Int
    fun deleteRunsForSession(sessionId: String): Int
}

class InMemoryAgentTraceStore(
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : AgentTraceStore {
    private val nextRunId = AtomicLong(0L)
    private val runs = linkedMapOf<String, AgentRun>()
    private val runSteps = linkedMapOf<String, MutableList<AgentStep>>()
    private val runStepSummaries = linkedMapOf<String, MutableList<AgentTraceStepSummary>>()
    private val pendingConfirmations = linkedMapOf<String, PendingToolConfirmationSnapshot>()
    private val continuationCursors = linkedMapOf<String, AgentContinuationCursor>()

    override fun createRun(input: String, sessionId: String?): AgentRun {
        val now = clockMillis()
        val run = AgentRun(
            id = "run-${nextRunId.incrementAndGet()}",
            input = input,
            state = AgentRunState.Created,
            createdAtMillis = now,
            updatedAtMillis = now,
            sessionId = sessionId,
        )
        runs[run.id] = run
        runSteps[run.id] = mutableListOf()
        runStepSummaries[run.id] = mutableListOf()
        return run
    }

    override fun run(runId: String): AgentRun? =
        runs[runId]

    override fun updateState(runId: String, state: AgentRunState): AgentRun {
        val existing = runs.getValue(runId)
        val updated = existing.copy(
            state = state,
            updatedAtMillis = clockMillis(),
        )
        runs[runId] = updated
        if (state.isTerminal()) {
            pendingConfirmations.remove(runId)
            continuationCursors.remove(runId)
        }
        return updated
    }

    override fun appendStep(runId: String, step: AgentStep) {
        val steps = runSteps.getValue(runId)
        runStepSummaries.getValue(runId).add(
            step.toTraceStepSummary(
                runId = runId,
                position = steps.size,
                createdAtMillis = clockMillis(),
            ),
        )
        steps.add(step)
    }

    override fun steps(runId: String): List<AgentStep> =
        runSteps[runId].orEmpty().toList()

    override fun stepSummaries(runId: String): List<AgentTraceStepSummary> =
        runStepSummaries[runId].orEmpty().toList()

    override fun recentRunSummaries(limit: Int, stepLimit: Int): List<AgentTraceRunSummary> {
        if (limit <= 0) return emptyList()
        return runs.values
            .sortedWith(compareByDescending<AgentRun> { it.updatedAtMillis }.thenByDescending { it.createdAtMillis })
            .take(limit)
            .map { run ->
                val summaries = stepSummaries(run.id)
                AgentTraceRunSummary(
                    run = run,
                    steps = summaries.takeLast(stepLimit.coerceAtLeast(0)),
                    runDataReceiptStep = summaries.lastOrNull { step -> step.type == "RunDataReceiptRecorded" },
                )
            }
    }

    override fun savePendingConfirmation(snapshot: PendingToolConfirmationSnapshot) {
        pendingConfirmations[snapshot.run.id] = snapshot
        snapshot.continuationCursor
            ?.let { continuationCursor -> continuationCursors[snapshot.run.id] = continuationCursor }
            ?: continuationCursors.remove(snapshot.run.id)
    }

    override fun latestPendingConfirmation(sessionId: String?): PendingToolConfirmationSnapshot? =
        pendingConfirmations.values
            .sortedByDescending { snapshot -> snapshot.run.updatedAtMillis }
            .firstNotNullOfOrNull { snapshot ->
                val run = runs[snapshot.run.id]
                if (sessionId != null && run != null && run.sessionId != sessionId) return@firstNotNullOfOrNull null
                when (run?.state) {
                    AgentRunState.AwaitingUserConfirmation -> snapshot.copy(run = run)
                    null -> null
                    else -> {
                        pendingConfirmations.remove(snapshot.run.id)
                        continuationCursors.remove(snapshot.run.id)
                        null
                    }
                }
            }

    override fun clearPendingConfirmation(runId: String, requestId: String): Boolean =
        pendingConfirmations[runId]
            ?.takeIf { snapshot -> snapshot.request.id == requestId }
            ?.let {
                pendingConfirmations.remove(runId)
                true
            } ?: false

    override fun clearPendingConfirmationsForRun(runId: String): Int {
        val removed = if (pendingConfirmations.remove(runId) != null) 1 else 0
        continuationCursors.remove(runId)
        return removed
    }

    override fun nextActionInput(runId: String): String? =
        pendingConfirmations[runId]?.nextActionInput

    override fun continuationCursor(runId: String): AgentContinuationCursor? {
        val run = runs[runId] ?: return null
        if (run.state.isTerminal()) {
            continuationCursors.remove(runId)
            return null
        }
        return continuationCursors[runId]
    }

    override fun failStaleInFlightRuns(reason: String): Int {
        val staleRuns = runs.values
            .filter { run -> run.state.isStaleAfterProcessRestart() }
        staleRuns.forEach { run ->
            appendStep(run.id, AgentStep.Failed(reason))
            updateState(run.id, AgentRunState.Failed)
            pendingConfirmations.remove(run.id)
        }
        return staleRuns.size
    }

    override fun deleteRunsForSession(sessionId: String): Int {
        val runIds = runs.values
            .filter { run -> run.sessionId == sessionId }
            .map { run -> run.id }
        runIds.forEach { runId ->
            runs.remove(runId)
            runSteps.remove(runId)
            runStepSummaries.remove(runId)
            pendingConfirmations.remove(runId)
            continuationCursors.remove(runId)
        }
        return runIds.size
    }
}

class RoomAgentTraceStore(
    private val traceDao: AgentTraceDao,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val runIdFactory: () -> String = { "run-${UUID.randomUUID()}" },
    private val toolRegistry: ToolRegistry = ToolRegistry(),
) : AgentTraceStore {
    private val liveRuns = linkedMapOf<String, AgentRun>()
    private val liveSteps = linkedMapOf<String, MutableList<AgentStep>>()
    private val livePendingConfirmations = linkedMapOf<String, PendingToolConfirmationSnapshot>()
    private val liveNextActionInputs = linkedMapOf<String, String>()
    private val liveContinuationCursors = linkedMapOf<String, AgentContinuationCursor>()

    override fun createRun(input: String, sessionId: String?): AgentRun {
        val now = clockMillis()
        val run = AgentRun(
            id = runIdFactory(),
            input = input,
            state = AgentRunState.Created,
            createdAtMillis = now,
            updatedAtMillis = now,
            sessionId = sessionId,
        )
        liveRuns[run.id] = run
        traceDao.upsertRun(run.toEntity())
        liveSteps[run.id] = mutableListOf()
        return run
    }

    override fun run(runId: String): AgentRun? =
        liveRuns[runId]
            ?: traceDao.run(runId)?.toDomain()

    override fun updateState(runId: String, state: AgentRunState): AgentRun {
        val now = clockMillis()
        val updatedRows = traceDao.updateRunState(runId, state.name, now)
        require(updatedRows > 0) { "Agent run does not exist: $runId" }
        liveRuns[runId]?.let { liveRun ->
            val updated = liveRun.copy(
                state = state,
                updatedAtMillis = now,
            )
            liveRuns[runId] = updated
            if (state.isTerminal()) {
                livePendingConfirmations.remove(runId)
                liveNextActionInputs.remove(runId)
                liveContinuationCursors.remove(runId)
                traceDao.deleteSkillRunCheckpointsForRun(runId)
            }
            return updated
        }
        if (state.isTerminal()) {
            livePendingConfirmations.remove(runId)
            liveNextActionInputs.remove(runId)
            liveContinuationCursors.remove(runId)
            traceDao.deleteSkillRunCheckpointsForRun(runId)
        }
        return traceDao.run(runId)?.toDomain()
            ?: error("Agent run disappeared after update: $runId")
    }

    override fun appendStep(runId: String, step: AgentStep) {
        requireNotNull(traceDao.run(runId)) { "Agent run does not exist: $runId" }
        val now = clockMillis()
        val position = traceDao.nextStepPosition(runId)
        traceDao.insertStep(step.toEntity(runId, position, now))
        traceDao.touchRun(runId, now)
        liveRuns[runId]?.let { liveRun ->
            liveRuns[runId] = liveRun.copy(updatedAtMillis = now)
        }
        liveSteps.getOrPut(runId) { mutableListOf() }.add(step)
    }

    override fun steps(runId: String): List<AgentStep> =
        liveSteps[runId]?.toList()
            ?: traceDao.steps(runId).map { entity -> entity.toRestoredStep() }

    override fun stepSummaries(runId: String): List<AgentTraceStepSummary> =
        traceDao.steps(runId).map { entity -> entity.toSummary() }

    override fun recentRunSummaries(limit: Int, stepLimit: Int): List<AgentTraceRunSummary> {
        if (limit <= 0) return emptyList()
        val safeStepLimit = stepLimit.coerceAtLeast(0)
        return traceDao.recentRuns(limit).map { entity ->
            val run = entity.toDomain()
            val summaries = stepSummaries(run.id)
            AgentTraceRunSummary(
                run = run,
                steps = summaries.takeLast(safeStepLimit),
                runDataReceiptStep = summaries.lastOrNull { step -> step.type == "RunDataReceiptRecorded" },
            )
        }
    }

    override fun savePendingConfirmation(snapshot: PendingToolConfirmationSnapshot) {
        val now = clockMillis()
        livePendingConfirmations[snapshot.run.id] = snapshot
        snapshot.nextActionInput
            ?.let { nextActionInput -> liveNextActionInputs[snapshot.run.id] = nextActionInput }
            ?: liveNextActionInputs.remove(snapshot.run.id)
        snapshot.continuationCursor
            ?.let { continuationCursor -> liveContinuationCursors[snapshot.run.id] = continuationCursor }
            ?: liveContinuationCursors.remove(snapshot.run.id)
        traceDao.upsertPendingConfirmationWithCheckpoint(
            pending = snapshot.toEntity(now, toolRegistry),
            checkpoint = snapshot.skillRunCheckpoint?.toEntity(now),
        )
    }

    override fun latestPendingConfirmation(sessionId: String?): PendingToolConfirmationSnapshot? {
        latestLivePendingConfirmation(sessionId)?.let { snapshot ->
            return snapshot
        }
        for (entity in traceDao.pendingConfirmations()) {
            val run = run(entity.runId)
            if (sessionId != null && run != null && run.sessionId != sessionId) continue
            if (run == null || run.state != AgentRunState.AwaitingUserConfirmation) {
                traceDao.deletePendingConfirmationWithRunCheckpoints(entity.runId, entity.requestId)
                continue
            }
            val checkpointEntity = traceDao.skillRunCheckpoint(entity.runId, entity.requestId)
            val snapshot = restorablePendingSnapshotOrFailRun(entity, checkpointEntity, run) ?: continue
            snapshot.nextActionInput
                ?.let { nextActionInput -> liveNextActionInputs[snapshot.run.id] = nextActionInput }
                ?: liveNextActionInputs.remove(snapshot.run.id)
            snapshot.continuationCursor
                ?.let { continuationCursor -> liveContinuationCursors[snapshot.run.id] = continuationCursor }
                ?: liveContinuationCursors.remove(snapshot.run.id)
            hydrateLivePendingSteps(snapshot)
            return snapshot
        }
        return null
    }

    override fun clearPendingConfirmation(runId: String, requestId: String): Boolean {
        val liveRemoved = livePendingConfirmations[runId]
            ?.takeIf { snapshot -> snapshot.request.id == requestId }
            ?.let {
                livePendingConfirmations.remove(runId)
                true
            } ?: false
        val persistedRemoved = traceDao.deletePendingConfirmationWithCheckpoint(runId, requestId) > 0
        return persistedRemoved || liveRemoved
    }

    override fun clearPendingConfirmationsForRun(runId: String): Int {
        val liveRemoved = if (livePendingConfirmations.remove(runId) != null) 1 else 0
        liveNextActionInputs.remove(runId)
        liveContinuationCursors.remove(runId)
        val persistedRemoved = traceDao.pendingConfirmations()
            .filter { entity -> entity.runId == runId }
            .sumOf { entity ->
                traceDao.deletePendingConfirmationWithRunCheckpoints(entity.runId, entity.requestId)
            }
        return maxOf(liveRemoved, persistedRemoved)
    }

    override fun nextActionInput(runId: String): String? =
        liveNextActionInputs[runId]

    override fun continuationCursor(runId: String): AgentContinuationCursor? {
        val run = run(runId) ?: return null
        if (run.state.isTerminal()) {
            liveContinuationCursors.remove(runId)
            return null
        }
        liveContinuationCursors[runId]?.let { cursor -> return cursor }
        return restoredContinuationCursor(runId)
            ?.also { cursor -> liveContinuationCursors[runId] = cursor }
    }

    override fun failStaleInFlightRuns(reason: String): Int {
        val pendingRepair = repairPendingConfirmations()
        val runs = traceDao.recentRuns(Int.MAX_VALUE)
            .map { entity -> entity.toDomain() }
        val staleRuns = runs.filter { run -> run.state.isStaleAfterProcessRestart() }
        staleRuns.forEach { run ->
            failRun(run, reason)
        }
        val unrestorableExternalOutcomeRuns = traceDao.recentRuns(Int.MAX_VALUE)
            .map { entity -> entity.toDomain() }
            .filter { run ->
                run.state == AgentRunState.AwaitingExternalOutcome &&
                    !hasRestorablePendingExternalOutcome(run.id)
            }
        unrestorableExternalOutcomeRuns.forEach { run ->
            failRun(run, UNRESTORABLE_PENDING_EXTERNAL_OUTCOME_REASON)
        }
        val awaitingWithoutPendingRuns = traceDao.recentRuns(Int.MAX_VALUE)
            .map { entity -> entity.toDomain() }
            .filter { run ->
                run.state == AgentRunState.AwaitingUserConfirmation &&
                    run.id !in pendingRepair.validPendingRunIds
            }
        awaitingWithoutPendingRuns.forEach { run ->
            failRun(run, UNRESTORABLE_PENDING_CONFIRMATION_REASON)
        }
        return pendingRepair.failedRunIds.size +
            staleRuns.size +
            unrestorableExternalOutcomeRuns.size +
            awaitingWithoutPendingRuns.size
    }

    override fun deleteRunsForSession(sessionId: String): Int {
        val runIds = traceDao.runIdsForSession(sessionId)
        if (runIds.isEmpty()) return 0
        val deletedCount = traceDao.deleteRunGraphForSession(sessionId)
        runIds.forEach { runId ->
            liveRuns.remove(runId)
            liveSteps.remove(runId)
            livePendingConfirmations.remove(runId)
            liveNextActionInputs.remove(runId)
            liveContinuationCursors.remove(runId)
        }
        return deletedCount
    }

    private fun repairPendingConfirmations(): PendingConfirmationRepairResult {
        val validPendingRunIds = mutableSetOf<String>()
        val failedRunIds = mutableSetOf<String>()
        traceDao.pendingConfirmations().forEach { entity ->
            val run = run(entity.runId)
            if (run == null || run.state != AgentRunState.AwaitingUserConfirmation) {
                traceDao.deletePendingConfirmationWithRunCheckpoints(entity.runId, entity.requestId)
                return@forEach
            }
            val checkpointEntity = traceDao.skillRunCheckpoint(entity.runId, entity.requestId)
            if (restorablePendingSnapshotOrFailRun(entity, checkpointEntity, run) != null) {
                validPendingRunIds += run.id
            } else {
                failedRunIds += run.id
            }
        }
        return PendingConfirmationRepairResult(
            validPendingRunIds = validPendingRunIds,
            failedRunIds = failedRunIds,
        )
    }

    private fun latestLivePendingConfirmation(sessionId: String?): PendingToolConfirmationSnapshot? =
        livePendingConfirmations.values
            .sortedByDescending { snapshot -> snapshot.run.updatedAtMillis }
            .firstNotNullOfOrNull { snapshot ->
                val run = run(snapshot.run.id)
                if (sessionId != null && run != null && run.sessionId != sessionId) return@firstNotNullOfOrNull null
                when (run?.state) {
                    AgentRunState.AwaitingUserConfirmation -> snapshot.copy(run = run)
                    null -> null
                    else -> {
                        livePendingConfirmations.remove(snapshot.run.id)
                        liveNextActionInputs.remove(snapshot.run.id)
                        liveContinuationCursors.remove(snapshot.run.id)
                        null
                    }
                }
            }

    private fun restoredContinuationCursor(runId: String): AgentContinuationCursor? {
        val requestedIds = stepSummaries(runId)
            .asSequence()
            .filter { step -> step.type == "ToolRequested" }
            .mapNotNull { step -> step.requestIdFromJson() }
            .toSet()
        if (requestedIds.isEmpty()) return null
        return stepSummaries(runId)
            .asReversed()
            .asSequence()
            .filter { step -> step.type == "ContinuationCursorRecorded" }
            .mapNotNull { step -> step.restoredContinuationCursorOrNull() }
            .firstOrNull { cursor ->
                cursor.sourceRequestId in requestedIds &&
                    cursor.isRestorableForSourceRequest(cursor.sourceRequestId, toolRegistry)
            }
    }

    private fun hasRestorablePendingExternalOutcome(runId: String): Boolean {
        val steps = stepSummaries(runId)
        val requestIds = steps
            .asSequence()
            .filter { step -> step.type == "ToolRequested" }
            .mapNotNull { step -> step.restorableToolRequestIdFromJson() }
            .toSet()
        if (requestIds.isEmpty()) return false
        val confirmedRequestIds = steps
            .asSequence()
            .filter { step -> step.type == "ExternalOutcomeConfirmed" }
            .mapNotNull { step -> step.requestIdFromJson() }
            .toSet()
        return steps
            .asReversed()
            .asSequence()
            .filter { step -> step.type == "ToolObserved" }
            .mapNotNull { step -> step.restoredUnverifiedExternalResultOrNull() }
            .any { result ->
                result.requestId in requestIds &&
                    result.requestId !in confirmedRequestIds
            }
    }

    private fun restorablePendingSnapshotOrFailRun(
        entity: PendingAgentConfirmationEntity,
        checkpointEntity: AgentSkillRunCheckpointEntity?,
        run: AgentRun,
    ): PendingToolConfirmationSnapshot? {
        val snapshot = try {
            entity.toSnapshot(
                run = run,
                skillRunCheckpoint = checkpointEntity?.toCheckpoint(),
            )
        } catch (_: Exception) {
            null
        }
        if (snapshot == null ||
            snapshot.hasRedactedExecutablePayload() ||
            toolRegistry.validate(snapshot.request) != null ||
            !snapshot.hasRestorableSkillPlanRequest() ||
            !snapshot.hasRestorableSkillRunCheckpoint(toolRegistry) ||
            !snapshot.hasRestorableContinuationCursor(toolRegistry)
        ) {
            traceDao.deletePendingConfirmationWithCheckpoint(entity.runId, entity.requestId)
            failRun(run, UNRESTORABLE_PENDING_CONFIRMATION_REASON)
            return null
        }
        return snapshot
    }

    private fun failRun(run: AgentRun, reason: String) {
        if (run.state.isTerminal()) return
        appendStep(run.id, AgentStep.Failed(reason))
        updateState(run.id, AgentRunState.Failed)
    }

    private fun hydrateLivePendingSteps(snapshot: PendingToolConfirmationSnapshot) {
        val steps = liveSteps.getOrPut(snapshot.run.id) { mutableListOf() }
        traceDao.steps(snapshot.run.id)
            .mapNotNull { entity -> entity.toRestoredToolRequestedOrNull() }
            .filterNot { step -> step.request.id == snapshot.request.id }
            .forEach { restored ->
                if (steps.none { step -> step is AgentStep.ToolRequested && step.request.id == restored.request.id }) {
                    steps += restored
                }
            }
        snapshot.skillPlan?.let { plan ->
            if (steps.none { step -> step is AgentStep.SkillPlanned && step.request.id == plan.request.id }) {
                val toolIndex = steps.indexOfFirst { step ->
                    step is AgentStep.ToolRequested && step.request.id == snapshot.request.id
                }
                val skillStep = AgentStep.SkillPlanned(plan.request, plan)
                if (toolIndex >= 0) {
                    steps.add(toolIndex, skillStep)
                } else {
                    steps += skillStep
                }
            }
        }
        if (steps.none { step -> step is AgentStep.ToolRequested && step.request.id == snapshot.request.id }) {
            steps += AgentStep.ToolRequested(snapshot.request, snapshot.draft)
        }
        if (steps.none { step ->
                step is AgentStep.UserConfirmationRequested && step.request.id == snapshot.request.id
            }
        ) {
            steps += AgentStep.UserConfirmationRequested(snapshot.request, snapshot.draft)
        }
    }
}

private fun AgentRunState.isStaleAfterProcessRestart(): Boolean =
    this in setOf(
        AgentRunState.Created,
        AgentRunState.LoadingContext,
        AgentRunState.Planning,
        AgentRunState.ExecutingTool,
        AgentRunState.RetryingTool,
        AgentRunState.Observing,
        AgentRunState.GeneratingAnswer,
    )

private fun AgentRunState.isTerminal(): Boolean =
    this in setOf(
        AgentRunState.Completed,
        AgentRunState.Cancelled,
        AgentRunState.Failed,
    )

private fun AgentRun.toEntity(): AgentRunEntity =
    AgentRunEntity(
        id = id,
        input = REDACTED_AGENT_RUN_INPUT,
        state = state.name,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        sessionId = sessionId,
    )

private fun AgentRunEntity.toDomain(): AgentRun =
    AgentRun(
        id = id,
        input = input,
        state = runCatching { AgentRunState.valueOf(state) }.getOrDefault(AgentRunState.Failed),
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        sessionId = sessionId,
    )

private fun AgentStep.toEntity(runId: String, position: Int, createdAtMillis: Long): AgentStepEntity {
    val summary = toTraceStepSummary(runId, position, createdAtMillis)
    return AgentStepEntity(
        runId = summary.runId,
        position = summary.position,
        type = summary.type,
        summary = summary.summary,
        json = summary.json,
        createdAtMillis = summary.createdAtMillis,
    )
}

private fun AgentStep.toTraceStepSummary(
    runId: String,
    position: Int,
    createdAtMillis: Long,
): AgentTraceStepSummary {
    val type = traceType()
    return AgentTraceStepSummary(
        runId = runId,
        position = position,
        type = type,
        summary = traceSummary(),
        json = traceJson(type).toString(),
        createdAtMillis = createdAtMillis,
    )
}

private fun AgentStepEntity.toSummary(): AgentTraceStepSummary =
    AgentTraceStepSummary(
        runId = runId,
        position = position,
        type = type,
        summary = summary,
        json = json,
        createdAtMillis = createdAtMillis,
    )

private fun AgentStepEntity.toRestoredStep(): AgentStep.RestoredSummary =
    AgentStep.RestoredSummary(
        persistedType = type,
        summary = summary,
        json = json,
    )

private fun AgentStepEntity.toRestoredToolRequestedOrNull(): AgentStep.ToolRequested? {
    if (type != "ToolRequested") return null
    return runCatching {
        val restoredJson = JSONObject(json)
        val toolName = restoredJson.optString("toolName").takeIf { it.isNotBlank() } ?: return null
        val requestId = restoredJson.optString("requestId").takeIf { it.isNotBlank() } ?: return null
        val draftTitle = restoredJson.optString("draftTitle").takeIf { it.isNotBlank() } ?: toolName
        AgentStep.ToolRequested(
            request = ToolRequest(
                id = requestId,
                toolName = toolName,
                arguments = emptyMap(),
                reason = "",
            ),
            draft = ActionDraft(
                functionName = toolName,
                title = draftTitle,
                summary = "",
                parameters = emptyMap(),
            ),
        )
    }.getOrNull()
}

private fun SkillRunCheckpoint.toEntity(now: Long): AgentSkillRunCheckpointEntity =
    AgentSkillRunCheckpointEntity(
        runId = runId,
        requestId = pendingRequestId,
        skillId = skillId,
        skillRequestId = skillRequestId,
        manifestId = manifestId,
        manifestVersion = manifestVersion,
        manifestHash = manifestHash,
        phase = phase.name,
        pendingStepIndex = pendingStepIndex,
        pendingStepId = pendingStepId,
        pendingToolName = pendingToolName,
        completedStepIdsJson = completedStepIds.toJsonArray().toString(),
        outputKeysByStepJson = outputKeysByStep.toStringListJsonObject().toString(),
        privateOutputRefsJson = privateOutputRefs.sorted().toJsonArray().toString(),
        schemaVersion = schemaVersion,
        createdAtMillis = now,
        updatedAtMillis = now,
    )

private fun AgentSkillRunCheckpointEntity.toCheckpoint(): SkillRunCheckpoint =
    SkillRunCheckpoint(
        schemaVersion = schemaVersion,
        runId = runId,
        skillId = skillId,
        skillRequestId = skillRequestId,
        manifestId = manifestId,
        manifestVersion = manifestVersion,
        manifestHash = manifestHash,
        phase = SkillRunCheckpointPhase.valueOf(phase),
        pendingStepIndex = pendingStepIndex,
        pendingStepId = pendingStepId,
        pendingRequestId = requestId,
        pendingToolName = pendingToolName,
        completedStepIds = JSONArray(completedStepIdsJson).toStringList(),
        outputKeysByStep = JSONObject(outputKeysByStepJson).toStringListMap(),
        privateOutputRefs = JSONArray(privateOutputRefsJson).toStringList().toSet(),
    )

private fun PendingToolConfirmationSnapshot.toEntity(
    now: Long,
    toolRegistry: ToolRegistry,
): PendingAgentConfirmationEntity =
    PendingAgentConfirmationEntity(
        runId = run.id,
        requestId = request.id,
        toolName = request.toolName,
        argumentsJson = request.arguments
            .persistablePendingArgumentsFor(request.toolName, toolRegistry)
            .toJsonObject()
            .toString(),
        reason = request.reason.redactedIfNotBlank(),
        draftFunctionName = draft.functionName,
        draftTitle = draft.title.redactedIfNotBlank(),
        draftSummary = draft.summary.redactedIfNotBlank(),
        draftParametersJson = draft.parameters
            .persistablePendingArgumentsFor(request.toolName, toolRegistry)
            .toJsonObject()
            .toString(),
        skillId = skillId,
        skillPlanJson = skillPlan?.redactedForPendingPersistence()?.toJsonObject()?.toString(),
        plannedByModel = plannedByModel,
        fallbackReason = fallbackReason,
        nextActionInput = null,
        continuationCursorJson = continuationCursor
            ?.persistableForPendingPersistence(sourceRequestId = request.id, toolRegistry = toolRegistry)
            ?.toJsonObject()
            ?.toString(),
        createdAtMillis = now,
        updatedAtMillis = now,
    )

private fun PendingAgentConfirmationEntity.toSnapshot(
    run: AgentRun,
    skillRunCheckpoint: SkillRunCheckpoint?,
): PendingToolConfirmationSnapshot =
    PendingToolConfirmationSnapshot(
        run = run,
        request = ToolRequest(
            id = requestId,
            toolName = toolName,
            arguments = argumentsJson.toStringMap(),
            reason = reason,
        ),
        draft = ActionDraft(
            functionName = draftFunctionName,
            title = draftTitle,
            summary = draftSummary,
            parameters = draftParametersJson.toStringMap(),
        ),
        skillId = skillId,
        skillPlan = skillPlanJson?.let { json -> JSONObject(json).toSkillPlan() },
        plannedByModel = plannedByModel,
        fallbackReason = fallbackReason,
        nextActionInput = null,
        continuationCursor = continuationCursorJson?.let { json ->
            JSONObject(json).toAgentContinuationCursor()
        },
        skillRunCheckpoint = skillRunCheckpoint,
    )

private fun PendingToolConfirmationSnapshot.hasRestorableSkillPlanRequest(): Boolean {
    val plan = skillPlan ?: return true
    return plan.steps
        .filterIsInstance<SkillStep.ToolStep>()
        .any { step ->
            step.request.id == request.id && step.request.toolName == request.toolName
        }
}

private fun PendingToolConfirmationSnapshot.hasRestorableSkillRunCheckpoint(
    toolRegistry: ToolRegistry,
): Boolean {
    val plan = skillPlan
    val checkpoint = skillRunCheckpoint
    if (plan == null) return checkpoint == null
    if (checkpoint == null) return false
    if (checkpoint.runId != run.id) return false
    if (checkpoint.pendingRequestId != request.id) return false
    if (checkpoint.pendingToolName != request.toolName) return false
    return checkpoint.validationErrorFor(plan, toolRegistry) == null
}

private fun PendingToolConfirmationSnapshot.hasRestorableContinuationCursor(
    toolRegistry: ToolRegistry,
): Boolean {
    val cursor = continuationCursor ?: return true
    return cursor.isRestorableForSourceRequest(request.id, toolRegistry)
}

private fun AgentContinuationCursor.isRestorableForSourceRequest(
    sourceRequestId: String,
    toolRegistry: ToolRegistry,
): Boolean {
    if (!hasValueFreeCursorPersistenceShape(sourceRequestId)) return false
    if (toolRegistry.validate(request) != null) return false
    val skillPlan = skillPlan ?: return true
    if (!skillPlan.isSingleToolStepPlanFor(request)) return false
    return skillPlan.validateStructure(toolRegistry).isValid
}

private fun AgentContinuationCursor.persistableForPendingPersistence(
    sourceRequestId: String,
    toolRegistry: ToolRegistry,
): AgentContinuationCursor? =
    redactedForPendingPersistence()
        .takeIf { cursor -> cursor.isRestorableForSourceRequest(sourceRequestId, toolRegistry) }

private fun AgentContinuationCursor.persistableForTracePersistence(): AgentContinuationCursor? =
    redactedForPendingPersistence()
        .takeIf { cursor -> cursor.hasValueFreeCursorPersistenceShape(cursor.sourceRequestId) }

private fun AgentContinuationCursor.hasValueFreeCursorPersistenceShape(sourceRequestId: String): Boolean {
    if (this.sourceRequestId != sourceRequestId) return false
    if (this.sourceRequestId.isBlank()) return false
    if (completedSegmentCount < 0) return false
    if (plannedByModel) return false
    if (request.toolName != draft.functionName) return false
    if (request.arguments.isNotEmpty()) return false
    if (draft.parameters.isNotEmpty()) return false
    if (!request.reason.isBlankOrRedacted()) return false
    if (!draft.title.isBlankOrRedacted()) return false
    if (!draft.summary.isBlankOrRedacted()) return false
    return skillPlan?.hasOnlyValueFreeOrRedactedPayloadForCursor() ?: true
}

private fun SkillPlan.hasOnlyValueFreeOrRedactedPayloadForCursor(): Boolean {
    if (!request.arguments.hasOnlyBlankOrRedactedValues()) return false
    if (!request.reason.isBlankOrRedacted()) return false
    val step = steps.singleOrNull() as? SkillStep.ToolStep ?: return false
    if (step.request.arguments.isNotEmpty()) return false
    if (step.draft.parameters.isNotEmpty()) return false
    if (step.argumentBindings.isNotEmpty()) return false
    if (!step.request.reason.isBlankOrRedacted()) return false
    if (!step.draft.title.isBlankOrRedacted()) return false
    if (!step.draft.summary.isBlankOrRedacted()) return false
    return true
}

private fun AgentContinuationCursor.redactedForPendingPersistence(): AgentContinuationCursor =
    copy(
        request = request.copy(reason = request.reason.redactedIfNotBlank()),
        draft = draft.copy(
            title = draft.title.redactedIfNotBlank(),
            summary = draft.summary.redactedIfNotBlank(),
        ),
        skillPlan = skillPlan?.redactedForPendingPersistence(),
    )

private fun PendingToolConfirmationSnapshot.hasRedactedExecutablePayload(): Boolean =
    request.arguments.hasRedactedValue() ||
        draft.parameters.hasRedactedValue()

private fun Map<String, String>.hasRedactedValue(): Boolean =
    values.any { value -> value == REDACTED_AGENT_RUN_INPUT }

private fun Map<String, String>.hasOnlyBlankOrRedactedValues(): Boolean =
    values.all { value -> value.isBlankOrRedacted() }

private fun String.isBlankOrRedacted(): Boolean =
    isBlank() || this == REDACTED_AGENT_RUN_INPUT

private fun Map<String, String>.persistablePendingArgumentsFor(
    toolName: String,
    toolRegistry: ToolRegistry,
): Map<String, String> {
    if (isEmpty()) return emptyMap()
    val allowlist = toolRegistry.pendingArgumentAllowlistFor(toolName)
    val originalRequestIsValid = toolRegistry.validate(
        ToolRequest(toolName = toolName, arguments = this),
    ) == null
    return filterKeys { key -> key in allowlist }
        .mapValues { (_, value) ->
            if (originalRequestIsValid) value else value.redactedIfNotBlank()
        }
}

private fun SkillPlan.redactedForPendingPersistence(): SkillPlan =
    copy(
        request = request.copy(
            arguments = request.arguments.redactedValuesForPendingPersistence(),
            reason = request.reason.redactedIfNotBlank(),
        ),
        steps = steps.map { step ->
            when (step) {
                is SkillStep.ToolStep -> step.copy(
                    request = step.request.copy(
                        arguments = step.request.arguments.redactedValuesForPendingPersistence(),
                        reason = step.request.reason.redactedIfNotBlank(),
                    ),
                    draft = step.draft.copy(
                        title = step.draft.title.redactedIfNotBlank(),
                        summary = step.draft.summary.redactedIfNotBlank(),
                        parameters = step.draft.parameters.redactedValuesForPendingPersistence(),
                    ),
                )

                is SkillStep.ModelStep -> step
            }
        },
    )

private fun SkillPlan.isSingleToolStepPlanFor(request: ToolRequest): Boolean {
    val step = steps.singleOrNull() as? SkillStep.ToolStep ?: return false
    return step.request.id == request.id && step.request.toolName == request.toolName
}

private fun Map<String, String>.redactedValuesForPendingPersistence(): Map<String, String> =
    mapValues { (_, value) -> value.redactedIfNotBlank() }

private fun String.redactedIfNotBlank(): String =
    if (isBlank()) this else REDACTED_AGENT_RUN_INPUT

private fun Map<String, String>.toJsonObject(): JSONObject {
    val json = JSONObject()
    entries.sortedBy { it.key }.forEach { (key, value) ->
        json.put(key, value)
    }
    return json
}

private fun Map<String, List<String>>.toStringListJsonObject(): JSONObject {
    val json = JSONObject()
    entries.sortedBy { it.key }.forEach { (key, values) ->
        json.put(key, values.distinct().sorted().toJsonArray())
    }
    return json
}

private fun String.toStringMap(): Map<String, String> =
    JSONObject(this).toStringMap()

private fun JSONObject.toStringMap(): Map<String, String> {
    return buildMap {
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, optString(key))
        }
    }
}

private fun JSONObject.toStringListMap(): Map<String, List<String>> =
    buildMap {
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, getJSONArray(key).toStringList())
        }
    }

private fun Map<String, String>.allowlistedCompletionMetadataJson(): JSONObject {
    val json = JSONObject()
    toolObservedCompletionMetadataAllowlist.forEach { key ->
        this[key]?.takeIf { value -> value.isNotBlank() }?.let { value ->
            json.put(key, value.shortTraceText())
        }
    }
    return json
}

private fun SkillPlan.toJsonObject(): JSONObject =
    JSONObject()
        .put("request", request.toJsonObject())
        .put("manifest", manifest.toJsonObject())
        .put(
            "steps",
            JSONArray().also { array ->
                steps.forEach { step -> array.put(step.toJsonObject()) }
            },
        )

private fun JSONObject.toSkillPlan(): SkillPlan =
    SkillPlan(
        request = getJSONObject("request").toSkillRequest(),
        manifest = getJSONObject("manifest").toSkillManifest(),
        steps = getJSONArray("steps").toSkillSteps(),
    )

private fun SkillRequest.toJsonObject(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("skillId", skillId)
        .put("arguments", arguments.toJsonObject())
        .put("reason", reason)

private fun JSONObject.toSkillRequest(): SkillRequest =
    SkillRequest(
        id = getString("id"),
        skillId = getString("skillId"),
        arguments = getJSONObject("arguments").toStringMap(),
        reason = optString("reason"),
    )

private fun SkillManifest.toJsonObject(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("version", version)
        .put("title", title)
        .put("description", description)
        .put("triggerExamples", triggerExamples.toJsonArray())
        .put("requiredTools", requiredTools.toJsonArray())
        .put("inputSchemaJson", inputSchemaJson)
        .put("riskLevel", riskLevel.name)

private fun JSONObject.toSkillManifest(): SkillManifest =
    SkillManifest(
        id = getString("id"),
        version = getInt("version"),
        title = getString("title"),
        description = getString("description"),
        triggerExamples = getJSONArray("triggerExamples").toStringList(),
        requiredTools = getJSONArray("requiredTools").toStringList(),
        inputSchemaJson = getString("inputSchemaJson"),
        riskLevel = RiskLevel.valueOf(getString("riskLevel")),
    )

private fun SkillStep.toJsonObject(): JSONObject =
    when (this) {
        is SkillStep.ToolStep -> JSONObject()
            .put("type", "tool")
            .put("id", id)
            .put("dependsOn", dependsOn.toJsonArray())
            .put("request", request.toJsonObject())
            .put("draft", draft.toJsonObject())
            .put("argumentBindings", argumentBindings.toJsonObject())

        is SkillStep.ModelStep -> JSONObject()
            .put("type", "model")
            .put("id", id)
            .put("dependsOn", dependsOn.toJsonArray())
            .put("title", title)
            .put("instruction", instruction)
            .put("inputBindings", inputBindings.toJsonObject())
            .put("outputKey", outputKey)
            .put("keepsSensitiveInputLocal", keepsSensitiveInputLocal)
    }

private fun JSONArray.toSkillSteps(): List<SkillStep> =
    buildList {
        for (index in 0 until length()) {
            val step = getJSONObject(index)
            add(step.toSkillStep())
        }
    }

private fun JSONObject.toSkillStep(): SkillStep =
    when (getString("type")) {
        "tool" -> SkillStep.ToolStep(
            id = getString("id"),
            dependsOn = getJSONArray("dependsOn").toStringList(),
            request = getJSONObject("request").toToolRequest(),
            draft = getJSONObject("draft").toActionDraft(),
            argumentBindings = getJSONObject("argumentBindings").toStringMap(),
        )

        "model" -> SkillStep.ModelStep(
            id = getString("id"),
            dependsOn = getJSONArray("dependsOn").toStringList(),
            title = getString("title"),
            instruction = getString("instruction"),
            inputBindings = getJSONObject("inputBindings").toStringMap(),
            outputKey = getString("outputKey"),
            keepsSensitiveInputLocal = optBoolean("keepsSensitiveInputLocal", true),
        )

        else -> error("Unknown skill step type: ${getString("type")}")
    }

private fun ToolRequest.toJsonObject(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("toolName", toolName)
        .put("arguments", arguments.toJsonObject())
        .put("reason", reason)

private fun JSONObject.toToolRequest(): ToolRequest =
    ToolRequest(
        id = getString("id"),
        toolName = getString("toolName"),
        arguments = getJSONObject("arguments").toStringMap(),
        reason = optString("reason"),
    )

private fun ActionDraft.toJsonObject(): JSONObject =
    JSONObject()
        .put("functionName", functionName)
        .put("title", title)
        .put("summary", summary)
        .put("parameters", parameters.toJsonObject())
        .put("requiresConfirmation", requiresConfirmation)

private fun JSONObject.toActionDraft(): ActionDraft =
    ActionDraft(
        functionName = getString("functionName"),
        title = getString("title"),
        summary = getString("summary"),
        parameters = getJSONObject("parameters").toStringMap(),
        requiresConfirmation = optBoolean("requiresConfirmation", true),
    )

private fun AgentContinuationCursor.toJsonObject(): JSONObject =
    JSONObject()
        .put("sourceRequestId", sourceRequestId)
        .put("completedSegmentCount", completedSegmentCount)
        .put("request", request.toJsonObject())
        .put("draft", draft.toJsonObject())
        .put("plannedByModel", plannedByModel)
        .put("fallbackReason", fallbackReason)
        .put("skillPlan", skillPlan?.toJsonObject())

private fun JSONObject.toAgentContinuationCursor(): AgentContinuationCursor =
    AgentContinuationCursor(
        sourceRequestId = getString("sourceRequestId"),
        completedSegmentCount = getInt("completedSegmentCount"),
        request = getJSONObject("request").toToolRequest(),
        draft = getJSONObject("draft").toActionDraft(),
        plannedByModel = optBoolean("plannedByModel", false),
        fallbackReason = optString("fallbackReason").takeIf { it.isNotBlank() },
        skillPlan = optJSONObject("skillPlan")?.toSkillPlan(),
    )

private fun AgentTraceStepSummary.restoredContinuationCursorOrNull(): AgentContinuationCursor? =
    runCatching {
        JSONObject(json)
            .optJSONObject("cursor")
            ?.toAgentContinuationCursor()
    }.getOrNull()

private fun AgentTraceStepSummary.requestIdFromJson(): String? =
    runCatching { JSONObject(json) }
        .getOrNull()
        ?.optString("requestId")
        ?.takeIf { it.isNotBlank() }

private fun AgentTraceStepSummary.restorableToolRequestIdFromJson(): String? {
    val json = runCatching { JSONObject(json) }.getOrNull() ?: return null
    json.optString("toolName").takeIf { it.isNotBlank() } ?: return null
    return json.optString("requestId").takeIf { it.isNotBlank() }
}

private fun AgentTraceStepSummary.restoredUnverifiedExternalResultOrNull(): ToolResult? {
    val json = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (json.optString("status") != ToolStatus.Succeeded.name) return null
    val requestId = json.optString("requestId").takeIf { it.isNotBlank() } ?: return null
    val metadata = json.optJSONObject("completionMetadata") ?: return null
    val data = buildMap {
        metadata.keys().forEach { key ->
            val value = metadata.optString(key)
            if (value.isNotBlank()) put(key, value)
        }
    }
    return ToolResult(
        requestId = requestId,
        status = ToolStatus.Succeeded,
        summary = UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX,
        data = data,
    ).takeIf { result -> result.isUnverifiedExternalLaunch() }
}

private fun List<String>.toJsonArray(): JSONArray =
    JSONArray().also { array -> forEach { value -> array.put(value) } }

private fun JSONArray.toStringList(): List<String> =
    buildList {
        for (index in 0 until length()) {
            add(optString(index))
        }
    }

private fun AgentStep.traceType(): String =
    when (this) {
        is AgentStep.ContextLoaded -> "ContextLoaded"
        is AgentStep.ModelPlanned -> "ModelPlanned"
        is AgentStep.RemoteToolsExposed -> "RemoteToolsExposed"
        is AgentStep.RunDataReceiptRecorded -> "RunDataReceiptRecorded"
        is AgentStep.ToolRequested -> "ToolRequested"
        is AgentStep.SkillPlanned -> "SkillPlanned"
        is AgentStep.SafetyChecked -> "SafetyChecked"
        is AgentStep.UserConfirmationRequested -> "UserConfirmationRequested"
        is AgentStep.UserConfirmed -> "UserConfirmed"
        is AgentStep.UserRejected -> "UserRejected"
        is AgentStep.ToolObserved -> "ToolObserved"
        is AgentStep.ExternalOutcomeConfirmed -> "ExternalOutcomeConfirmed"
        is AgentStep.ContinuationCursorRecorded -> "ContinuationCursorRecorded"
        is AgentStep.ToolRetryScheduled -> "ToolRetryScheduled"
        is AgentStep.ObservationDecided -> "ObservationDecided"
        is AgentStep.AssistantResponded -> "AssistantResponded"
        is AgentStep.ToolRejected -> "ToolRejected"
        is AgentStep.Failed -> "Failed"
        is AgentStep.RestoredSummary -> persistedType
    }

private fun AgentStep.traceSummary(): String =
    when (this) {
        is AgentStep.ContextLoaded -> {
            val deviceContext = if (deviceContext == null) "without device context" else "with device context"
            "Loaded ${memoryHits.size} memory hit(s) $deviceContext."
        }

        is AgentStep.ModelPlanned -> plan.traceSummary()
        is AgentStep.RemoteToolsExposed ->
            "Exposed ${toolNames.size} remote tool(s) in ${scope.name} scope."
        is AgentStep.RunDataReceiptRecorded ->
            "Data receipt ${receipt.destination.name}: remoteHistory=${receipt.remoteHistoryCount}, " +
                "memoryHitCount=${receipt.memoryHitCount}, images=${receipt.imageAttachmentCount}."
        is AgentStep.ToolRequested -> "Requested tool ${request.toolName}."
        is AgentStep.SkillPlanned -> "Planned skill ${request.skillId} with ${plan?.steps?.size ?: 0} step(s)."
        is AgentStep.SafetyChecked -> "Safety ${decision.outcome}: ${decision.reason.shortTraceText()}"
        is AgentStep.UserConfirmationRequested -> "Requested confirmation for ${request.toolName}."
        is AgentStep.UserConfirmed -> "User confirmed request $requestId."
        is AgentStep.UserRejected -> "User rejected request $requestId."
        is AgentStep.ToolObserved -> "Observed ${result.status} for ${result.requestId}."
        is AgentStep.ExternalOutcomeConfirmed ->
            "External outcome ${outcome.name} confirmed for $requestId."
        is AgentStep.ContinuationCursorRecorded ->
            "Recorded continuation cursor for ${cursor.sourceRequestId} -> ${cursor.request.toolName}."
        is AgentStep.ToolRetryScheduled -> "Retry $attempt scheduled for ${request.toolName}."
        is AgentStep.ObservationDecided -> decision.traceSummary()
        is AgentStep.AssistantResponded -> "Assistant responded: ${text.shortTraceText()}"
        is AgentStep.ToolRejected -> "Rejected tool result ${result.requestId}: ${result.summary.shortTraceText()}"
        is AgentStep.Failed -> "Failed: ${reason.shortTraceText()}"
        is AgentStep.RestoredSummary -> summary
    }

private fun AgentPlan.traceSummary(): String =
    when (this) {
        is AgentPlan.Answer -> "Planned answer generation."
        is AgentPlan.UseTool -> "Planned tool ${request.toolName}."
        is AgentPlan.RejectedTool -> "Rejected tool plan: ${result.summary.shortTraceText()}"
        is AgentPlan.MissingModel -> "Missing model capability ${capability.name}."
    }

private fun AgentObservationDecision.traceSummary(): String =
    when (this) {
        AgentObservationDecision.Complete -> "Observation complete."
        is AgentObservationDecision.ContinueWithModel ->
            "Continue with model (requiresLocalModel=$requiresLocalModel)."
        is AgentObservationDecision.RetryTool ->
            "Retry ${request.toolName} attempt $attempt."
        is AgentObservationDecision.PlanNextTool ->
            "Plan next tool ${plan.request.toolName}."
        is AgentObservationDecision.PlanToolBatch ->
            "Plan tool batch ${plans.joinToString { plan -> plan.request.toolName }}."
        is AgentObservationDecision.Fail -> "Observation failed."
        AgentObservationDecision.Cancel -> "Observation cancelled."
    }

private fun AgentStep.traceJson(type: String): JSONObject {
    val json = JSONObject().put("type", type)
    when (this) {
        is AgentStep.ContextLoaded -> json
            .put("memoryHitCount", memoryHits.size)
            .put("hasDeviceContext", deviceContext != null)

        is AgentStep.ModelPlanned -> json.put("plan", plan.traceJson())
        is AgentStep.RemoteToolsExposed -> json
            .put("scope", scope.name)
            .put("toolNames", toolNames.sorted())

        is AgentStep.RunDataReceiptRecorded -> json
            .put("destination", receipt.destination.name)
            .put("currentPromptPrivacy", receipt.currentPromptPrivacy)
            .put("remoteHistoryCount", receipt.remoteHistoryCount)
            .put("localOnlyHistoryFilteredCount", receipt.localOnlyHistoryFilteredCount)
            .put("memoryHitCount", receipt.memoryHitCount)
            .put("memoryContextIncluded", receipt.memoryContextIncluded)
            .put("deviceContextIncluded", receipt.deviceContextIncluded)
            .put("imageAttachmentCount", receipt.imageAttachmentCount)
            .put("protectedSourceCount", receipt.protectedSourceCount)
            .put("rawContentPersisted", receipt.rawContentPersisted)
            .put("protectedContentTypes", receipt.protectedContentTypes.toJsonArray())
            .put("deletableRecordTypes", receipt.deletableRecordTypes.toJsonArray())

        is AgentStep.ToolRequested -> json
            .put("requestId", request.id)
            .put("toolName", request.toolName)
            .put("argumentKeys", request.arguments.keys.sorted())
            .put("draftTitle", draft.title.shortTraceText())

        is AgentStep.SkillPlanned -> json
            .put("skillRequestId", request.id)
            .put("skillId", request.skillId)
            .put("stepCount", plan?.steps?.size ?: 0)

        is AgentStep.SafetyChecked -> json
            .put("outcome", decision.outcome.name)
            .put("reason", decision.reason.shortTraceText())

        is AgentStep.UserConfirmationRequested -> json
            .put("requestId", request.id)
            .put("toolName", request.toolName)
            .put("draftTitle", draft.title.shortTraceText())

        is AgentStep.UserConfirmed -> json.put("requestId", requestId)
        is AgentStep.UserRejected -> json.put("requestId", requestId)
        is AgentStep.ToolObserved -> json
            .put("requestId", result.requestId)
            .put("status", result.status.name)
            .put("retryable", result.retryable)
            .also {
                val metadata = result.data.allowlistedCompletionMetadataJson()
                if (metadata.length() > 0) {
                    it.put("completionMetadata", metadata)
                }
            }

        is AgentStep.ExternalOutcomeConfirmed -> json
            .put("requestId", requestId)
            .put("outcome", outcome.name)
            .put("status", result.status.name)
            .also {
                val metadata = result.data.allowlistedCompletionMetadataJson()
                if (metadata.length() > 0) {
                    it.put("completionMetadata", metadata)
                }
            }

        is AgentStep.ContinuationCursorRecorded -> json
            .put("sourceRequestId", cursor.sourceRequestId)
            .put("targetToolName", cursor.request.toolName)
            .also {
                cursor.persistableForTracePersistence()?.let { persistableCursor ->
                    it.put("cursor", persistableCursor.toJsonObject())
                }
            }

        is AgentStep.ToolRetryScheduled -> json
            .put("requestId", request.id)
            .put("toolName", request.toolName)
            .put("attempt", attempt)

        is AgentStep.ObservationDecided -> json.put("decision", decision.traceJson())
        is AgentStep.AssistantResponded -> json
            .put("textPreview", text.shortTraceText())
            .put("textLength", text.length)

        is AgentStep.ToolRejected -> json
            .put("requestId", result.requestId)
            .put("status", result.status.name)
            .put("summary", result.summary.shortTraceText())

        is AgentStep.Failed -> json.put("reason", reason.shortTraceText())

        is AgentStep.RestoredSummary -> runCatching { JSONObject(this.json) }.getOrElse {
            json
                .put("persistedType", persistedType)
                .put("summary", summary.shortTraceText())
        }
    }
    return json
}

private fun AgentPlan.traceJson(): JSONObject {
    val json = JSONObject()
    when (this) {
        is AgentPlan.Answer -> json
            .put("type", "Answer")
            .put("memoryHitCount", memoryHits.size)
            .put("hasDeviceContext", deviceContext != null)

        is AgentPlan.UseTool -> json
            .put("type", "UseTool")
            .put("requestId", request.id)
            .put("toolName", request.toolName)
            .put("plannedByModel", plannedByModel)
            .put("skillId", skillRequest?.skillId)
            .put("fallbackReason", fallbackReason?.shortTraceText())
            .put("safetyOutcome", safetyDecision.outcome.name)

        is AgentPlan.RejectedTool -> json
            .put("type", "RejectedTool")
            .put("requestId", result.requestId)
            .put("summary", result.summary.shortTraceText())

        is AgentPlan.MissingModel -> json
            .put("type", "MissingModel")
            .put("capability", capability.name)
    }
    return json
}

private fun AgentObservationDecision.traceJson(): JSONObject {
    val json = JSONObject()
    when (this) {
        AgentObservationDecision.Complete -> json.put("type", "Complete")
        is AgentObservationDecision.ContinueWithModel -> json
            .put("type", "ContinueWithModel")
            .put("requiresLocalModel", requiresLocalModel)

        is AgentObservationDecision.RetryTool -> json
            .put("type", "RetryTool")
            .put("requestId", request.id)
            .put("toolName", request.toolName)
            .put("attempt", attempt)

        is AgentObservationDecision.PlanNextTool -> json
            .put("type", "PlanNextTool")
            .put("requestId", plan.request.id)
            .put("toolName", plan.request.toolName)

        is AgentObservationDecision.PlanToolBatch -> json
            .put("type", "PlanToolBatch")
            .put(
                "requests",
                JSONArray().also { array ->
                    plans.forEach { plan ->
                        array.put(
                            JSONObject()
                                .put("requestId", plan.request.id)
                                .put("toolName", plan.request.toolName),
                        )
                    }
                },
            )

        is AgentObservationDecision.Fail -> json.put("type", "Fail")

        AgentObservationDecision.Cancel -> json.put("type", "Cancel")
    }
    return json
}

private fun String.shortTraceText(maxLength: Int = 160): String {
    val compact = lineSequence()
        .joinToString(" ") { line -> line.trim() }
        .replace(Regex("\\s+"), " ")
        .trim()
    val redacted = ToolAuditSummaryRedactor.redact(compact)
    return if (redacted.length <= maxLength) redacted else redacted.take(maxLength - 3) + "..."
}
