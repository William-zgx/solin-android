package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.data.AgentRunEntity
import com.bytedance.zgx.solin.data.AgentRunPlacementBindingEntity
import com.bytedance.zgx.solin.data.AgentStepEntity
import com.bytedance.zgx.solin.data.PendingAgentConfirmationEntity
import com.bytedance.zgx.solin.data.RunPlacementRecoverySnapshotEntity
import com.bytedance.zgx.solin.data.RunPlacementTerminalizationEntity
import com.bytedance.zgx.solin.data.RunPlacementBindingDao
import com.bytedance.zgx.solin.resource.StableResourceBand

internal const val TEST_REMOTE_REVISION = "00000000-0000-0000-0000-000000000001"

internal fun testBinding(
    runId: String = "run-test",
    placement: RunPlacement = RunPlacement.Local,
    bootCount: Long = 7L,
    boundAtElapsedRealtimeMillis: Long = 1_000L,
    dispatchState: ModelDispatchState = ModelDispatchState.Pending,
    attempt: Int = 0,
    schemaVersion: Int = RUN_PLACEMENT_BINDING_SCHEMA_VERSION,
    remoteProfileRevision: String? = if (placement == RunPlacement.Remote) TEST_REMOTE_REVISION else null,
): RunPlacementBinding = RunPlacementBinding(
    schemaVersion = schemaVersion,
    runId = runId,
    policyVersion = 1,
    preference = InferenceMode.Auto,
    placement = placement,
    primaryReason = if (placement == RunPlacement.Local) {
        PlacementReasonCode.AUTO_SIMPLE_LOCAL
    } else {
        PlacementReasonCode.AUTO_COMPLEX_REMOTE
    },
    complexity = if (placement == RunPlacement.Local) RequestComplexity.Simple else RequestComplexity.Complex,
    resourceBand = StableResourceBand.Normal,
    localState = CandidateState.Eligible,
    remoteState = CandidateState.Eligible,
    remoteProfileRevision = remoteProfileRevision,
    bootCount = bootCount,
    boundAtElapsedRealtimeMillis = boundAtElapsedRealtimeMillis,
    dispatchState = dispatchState,
    attempt = attempt,
)

internal fun testReceipt(placement: RunPlacement): RunDataReceipt = RunDataReceipt(
    destination = placement.toRunDataDestination(),
    currentPromptPrivacy = "RemoteEligible",
    remoteHistoryCount = if (placement == RunPlacement.Remote) 2 else 0,
    localOnlyHistoryFilteredCount = 1,
    evidenceSourceTypes = listOf("Memory"),
    protectedContentTypes = listOf("本地记忆"),
    deletableRecordTypes = listOf("对话消息", "Agent 轨迹"),
)

internal class FakeRunPlacementBindingDao : RunPlacementBindingDao() {
    private val runs = linkedMapOf<String, AgentRunEntity>()
    private val bindings = linkedMapOf<String, AgentRunPlacementBindingEntity>()
    private val traceSteps = mutableListOf<AgentStepEntity>()
    private val pendingConfirmations = linkedMapOf<String, PendingAgentConfirmationEntity>()

    var failStepType: String? = null
    var failFinish = false
    var afterBindCommitted: (() -> Unit)? = null
    var afterRecoverySnapshot: (() -> Unit)? = null
    var beforeClaimTransaction: (() -> Unit)? = null
    var beforeTerminalizeTransaction: (() -> Unit)? = null
    var afterTerminalizedInsideTransaction: (() -> Unit)? = null

    override fun insertRunStrict(run: AgentRunEntity) {
        check(run.id !in runs) { "duplicate run" }
        runs[run.id] = run
    }

    override fun run(runId: String): AgentRunEntity? = runs[runId]

    override fun updateRunTerminal(runId: String, state: String, updatedAtMillis: Long): Int {
        val run = runs[runId] ?: return 0
        if (run.state in setOf("Completed", "Cancelled", "Failed")) return 0
        runs[runId] = run.copy(state = state, updatedAtMillis = updatedAtMillis)
        return 1
    }

    override fun insertBindingStrict(binding: AgentRunPlacementBindingEntity) {
        check(binding.runId in runs) { "foreign key failure" }
        check(binding.runId !in bindings) { "duplicate binding" }
        bindings[binding.runId] = binding
    }

    override fun binding(runId: String): AgentRunPlacementBindingEntity? = bindings[runId]

    override fun compareAndSetStarted(runId: String, placement: String, expectedAttempt: Int): Int {
        val binding = bindings[runId] ?: return 0
        if (
            binding.placement != placement ||
            binding.attempt != expectedAttempt ||
            binding.dispatchState !in setOf("Pending", "Idle")
        ) {
            return 0
        }
        bindings[runId] = binding.copy(
            dispatchState = "Started",
            attempt = binding.attempt + 1,
        )
        return 1
    }

    override fun compareAndSetIdle(
        runId: String,
        placement: String,
        attempt: Int,
    ): Int {
        if (failFinish) return 0
        val binding = bindings[runId] ?: return 0
        if (
            binding.placement != placement ||
            binding.attempt != attempt ||
            binding.dispatchState != "Started"
        ) {
            return 0
        }
        bindings[runId] = binding.copy(dispatchState = "Idle")
        return 1
    }

    override fun markBindingTerminal(runId: String): Int {
        val binding = bindings[runId] ?: return 0
        if (binding.dispatchState == "Terminal") return 0
        bindings[runId] = binding.copy(dispatchState = "Terminal")
        return 1
    }

    override fun nextStepPosition(runId: String): Int = traceSteps
        .filter { it.runId == runId }
        .maxOfOrNull { it.position + 1 }
        ?: 0

    override fun insertStepStrict(step: AgentStepEntity) {
        check(step.type != failStepType) { "injected ${step.type} failure" }
        check(traceSteps.none { it.runId == step.runId && it.position == step.position }) {
            "duplicate step"
        }
        traceSteps += step
    }

    override fun steps(runId: String): List<AgentStepEntity> = traceSteps
        .filter { it.runId == runId }
        .sortedBy { it.position }

    override fun pendingConfirmation(runId: String): PendingAgentConfirmationEntity? =
        pendingConfirmations[runId]

    override fun bindAndReserveTransaction(
        binding: AgentRunPlacementBindingEntity,
        placementStep: AgentStepEntity,
    ): AgentRunPlacementBindingEntity {
        val persisted = synchronized(this) {
            rollbackOnFailure {
                super.bindAndReserveTransaction(binding, placementStep)
            }
        }
        afterBindCommitted?.invoke()
        return persisted
    }

    override fun recoverySnapshot(runId: String): RunPlacementRecoverySnapshotEntity? =
        super.recoverySnapshot(runId).also { snapshot ->
            if (snapshot != null) afterRecoverySnapshot?.invoke()
        }

    override fun claimAndRecordTransaction(
        runId: String,
        placement: String,
        expectedAttempt: Int,
        receiptStep: AgentStepEntity,
        invocationStep: AgentStepEntity,
    ): AgentRunPlacementBindingEntity? {
        beforeClaimTransaction?.invoke()
        return synchronized(this) {
            rollbackOnFailure {
                super.claimAndRecordTransaction(runId, placement, expectedAttempt, receiptStep, invocationStep)
            }
        }
    }

    override fun terminalizeTransaction(
        runId: String,
        state: String,
        updatedAtMillis: Long,
    ): RunPlacementTerminalizationEntity? {
        beforeTerminalizeTransaction?.invoke()
        return synchronized(this) {
            rollbackOnFailure {
                super.terminalizeTransaction(
                    runId,
                    state,
                    updatedAtMillis,
                ).also { terminalized ->
                    if (terminalized != null) afterTerminalizedInsideTransaction?.invoke()
                }
            }
        }
    }

    fun replaceRunLikeLegacy(run: AgentRunEntity) {
        if (run.id in runs) {
            bindings.remove(run.id)
        }
        runs[run.id] = run
    }

    fun deleteRun(runId: String) {
        runs.remove(runId)
        bindings.remove(runId)
    }

    fun mutateBinding(runId: String, transform: (AgentRunPlacementBindingEntity) -> AgentRunPlacementBindingEntity) {
        bindings[runId] = transform(bindings.getValue(runId))
    }

    fun mutateStep(runId: String, type: String, transform: (AgentStepEntity) -> AgentStepEntity) {
        val index = traceSteps.indexOfFirst { it.runId == runId && it.type == type }
        check(index >= 0)
        traceSteps[index] = transform(traceSteps[index])
    }

    fun mutateRun(runId: String, transform: (AgentRunEntity) -> AgentRunEntity) {
        runs[runId] = transform(runs.getValue(runId))
    }

    fun addStep(step: AgentStepEntity) {
        traceSteps += step.copy(position = nextStepPosition(step.runId))
    }

    fun setPendingConfirmation(pending: PendingAgentConfirmationEntity) {
        pendingConfirmations[pending.runId] = pending
    }

    private fun <T> rollbackOnFailure(block: () -> T): T {
        val runSnapshot = LinkedHashMap(runs)
        val bindingSnapshot = LinkedHashMap(bindings)
        val stepSnapshot = traceSteps.toList()
        val pendingSnapshot = LinkedHashMap(pendingConfirmations)
        return try {
            block()
        } catch (throwable: Throwable) {
            runs.clear()
            runs.putAll(runSnapshot)
            bindings.clear()
            bindings.putAll(bindingSnapshot)
            traceSteps.clear()
            traceSteps.addAll(stepSnapshot)
            pendingConfirmations.clear()
            pendingConfirmations.putAll(pendingSnapshot)
            throw throwable
        }
    }
}
