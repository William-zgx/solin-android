package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.data.AgentRunEntity
import com.bytedance.zgx.solin.data.AgentStepEntity
import com.bytedance.zgx.solin.data.RunPlacementRecoverySnapshotEntity
import com.bytedance.zgx.solin.data.RunPlacementBindingDao
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

interface RunPlacementBindingStore {
    fun createCriticalRun(run: AgentRun): Boolean

    fun terminalizeRun(
        runId: String,
        state: AgentRunState,
        updatedAtMillis: Long,
    ): TerminalizeRunResult

    fun bindAndReserve(binding: RunPlacementBinding): BindAndReserveResult

    /** Returns only bindings activated in this process. It never falls back to Room. */
    fun activeBinding(runId: String): ActiveRunPlacementPermit?

    fun inspectForRecovery(
        runId: String,
        context: RunPlacementRecoveryContext,
    ): RecoveryInspection

    fun activate(candidate: RecoveryInspection.ContinuationCandidate): ActiveRunPlacementPermit?

    fun claimForDispatch(
        permit: ActiveRunPlacementPermit,
        receipt: RunDataReceipt,
    ): ClaimInvocationResult

    /**
     * Atomically publishes the handle returned by [start] for a claimed invocation. [start] must
     * be short and non-blocking, must not re-enter this store or its dispatcher, and may throw only
     * before producing external side effects.
     */
    fun <T> startInvocation(
        permit: ActiveRunPlacementPermit,
        invocation: ModelRuntimeInvocation,
        start: () -> RunningModelRuntimeCall<T>,
    ): RunningModelRuntimeCall<T>?

    fun finishInvocation(invocation: ModelRuntimeInvocation): Boolean
}

class RoomRunPlacementBindingStore(
    private val dao: RunPlacementBindingDao,
    private val currentRecoveryContext: () -> RunPlacementRecoveryContext,
    private val traceClockMillis: () -> Long = { System.currentTimeMillis() },
) : RunPlacementBindingStore {
    private val storeToken = Any()
    private val activeBindings = ConcurrentHashMap<String, ActiveRunPlacementPermit>()
    private val lifecycleLocks = ConcurrentHashMap<String, Any>()

    override fun createCriticalRun(run: AgentRun): Boolean = runCatching {
        dao.insertRunStrict(
            AgentRunEntity(
                id = run.id,
                input = REDACTED_CRITICAL_RUN_INPUT,
                state = run.state.name,
                createdAtMillis = run.createdAtMillis,
                updatedAtMillis = run.updatedAtMillis,
                sessionId = run.sessionId,
            ),
        )
    }.isSuccess

    override fun terminalizeRun(
        runId: String,
        state: AgentRunState,
        updatedAtMillis: Long,
    ): TerminalizeRunResult {
        if (!state.isTerminalPlacementRunState()) return TerminalizeRunResult.Rejected
        return synchronized(lifecycleLock(runId)) {
            val permit = activeBindings[runId]
            if (permit == null) {
                terminalizePersisted(runId, state, updatedAtMillis, permit = null)
            } else {
                synchronized(permit.entry) {
                    terminalizePersisted(runId, state, updatedAtMillis, permit)
                }
            }
        }
    }

    private fun terminalizePersisted(
        runId: String,
        state: AgentRunState,
        updatedAtMillis: Long,
        permit: ActiveRunPlacementPermit?,
    ): TerminalizeRunResult {
        val result = runCatching {
            dao.terminalizeTransaction(
                runId = runId,
                state = state.name,
                updatedAtMillis = updatedAtMillis,
            )
        }.getOrNull() ?: return TerminalizeRunResult.Rejected
        val stopHandle = permit?.entry?.runtimeEntry
            ?.let { runtime -> (runtime as? RuntimeInvocationEntry.Running)?.stopHandle }
        permit?.let { active ->
            active.entry.binding = active.binding.copy(dispatchState = ModelDispatchState.Terminal)
            active.entry.runtimeEntry = RuntimeInvocationEntry.Terminal
        }
        if (!result.targetStateMatched) return TerminalizeRunResult.Rejected
        val placement = result.binding?.placement?.let { raw ->
            runCatching { RunPlacement.valueOf(raw) }.getOrNull()
        }
        return TerminalizeRunResult.Terminalized(placement, stopHandle)
    }

    override fun bindAndReserve(binding: RunPlacementBinding): BindAndReserveResult {
        if (!binding.isInitialCanonicalBinding()) return BindAndReserveResult.Rejected()
        if (activeBindings.containsKey(binding.runId)) return BindAndReserveResult.Rejected()
        val persisted = runCatching {
            dao.bindAndReserveTransaction(
                binding = binding.toEntity(),
                placementStep = AgentStep.PlacementSelected(binding).toTraceEntity(
                    runId = binding.runId,
                    position = 0,
                    createdAtMillis = traceClockMillis(),
                ),
            )
        }.getOrNull()?.toDomainOrNull() ?: return BindAndReserveResult.Rejected()
        if (persisted != binding) return BindAndReserveResult.Rejected()

        return synchronized(lifecycleLock(binding.runId)) {
            if (activeBindings.containsKey(binding.runId) || !isDurablyPublishable(binding)) {
                return@synchronized BindAndReserveResult.Rejected()
            }
            val permit = ActiveRunPlacementPermit(ActiveRunPlacementEntry(binding), storeToken)
            if (activeBindings.putIfAbsent(binding.runId, permit) != null) {
                BindAndReserveResult.Rejected()
            } else {
                BindAndReserveResult.Bound(permit)
            }
        }
    }

    override fun activeBinding(runId: String): ActiveRunPlacementPermit? =
        activeBindings[runId]

    override fun inspectForRecovery(
        runId: String,
        context: RunPlacementRecoveryContext,
    ): RecoveryInspection {
        val snapshot = runCatching { dao.recoverySnapshot(runId) }.getOrNull()
            ?: return when {
                runCatching { dao.binding(runId) }.getOrNull() == null -> notRestorable(
                    RunPlacementRecoveryRejection.MissingBinding,
                )
                else -> notRestorable(RunPlacementRecoveryRejection.MissingRun)
            }
        val entity = snapshot.binding
        if (entity.schemaVersion != RUN_PLACEMENT_BINDING_SCHEMA_VERSION) {
            return notRestorable(RunPlacementRecoveryRejection.UnknownSchema)
        }
        val binding = entity.toDomainOrNull()
            ?: return notRestorable(RunPlacementRecoveryRejection.UnknownEnum)
        val runState = runCatching { AgentRunState.valueOf(snapshot.run.state) }.getOrNull()
            ?: return notRestorable(RunPlacementRecoveryRejection.UnknownEnum)
        if (runState.isTerminalPlacementRunState()) {
            return notRestorable(RunPlacementRecoveryRejection.TerminalRun)
        }
        if (binding.bootCount != context.bootCount) {
            return notRestorable(RunPlacementRecoveryRejection.BootChanged)
        }
        val age = context.elapsedRealtimeMillis - binding.boundAtElapsedRealtimeMillis
        if (age < 0L) return notRestorable(RunPlacementRecoveryRejection.ClockMovedBackwards)
        if (age > RUN_PLACEMENT_RECOVERY_MAX_AGE_MILLIS) {
            return notRestorable(RunPlacementRecoveryRejection.Expired)
        }
        if (
            binding.placement == RunPlacement.Remote &&
            binding.remoteProfileRevision != context.remoteProfileRevision
        ) {
            return notRestorable(RunPlacementRecoveryRejection.RemoteRevisionChanged)
        }
        when (binding.dispatchState) {
            ModelDispatchState.Pending -> return notRestorable(
                RunPlacementRecoveryRejection.PendingNeverStarted,
            )
            ModelDispatchState.Started -> return notRestorable(
                RunPlacementRecoveryRejection.StartedNeverReplayed,
            )
            ModelDispatchState.Terminal -> return notRestorable(
                RunPlacementRecoveryRejection.DispatchStateNotIdle,
            )
            ModelDispatchState.Idle -> Unit
        }
        if (binding.attempt < 1) return notRestorable(RunPlacementRecoveryRejection.InvalidAttempt)
        if (!snapshot.steps.haveConsistentPlacementTrace(binding)) {
            return notRestorable(RunPlacementRecoveryRejection.TraceMismatch)
        }
        if (!runState.isRestorablePlacementContinuationState()) {
            return notRestorable(RunPlacementRecoveryRejection.RunStateNotRestorable)
        }
        if (!snapshot.hasRestorablePlacementContinuationEvidence(runState)) {
            return notRestorable(RunPlacementRecoveryRejection.ContinuationEvidenceMissing)
        }
        return RecoveryInspection.ContinuationCandidate(binding, context, storeToken)
    }

    override fun activate(candidate: RecoveryInspection.ContinuationCandidate): ActiveRunPlacementPermit? {
        if (candidate.storeToken !== storeToken) return null
        return synchronized(lifecycleLock(candidate.binding.runId)) {
            val freshContext = runCatching(currentRecoveryContext).getOrNull() ?: return@synchronized null
            val current = inspectForRecovery(candidate.binding.runId, freshContext)
                as? RecoveryInspection.ContinuationCandidate
                ?: return@synchronized null
            if (current.binding != candidate.binding) return@synchronized null
            val permit = ActiveRunPlacementPermit(ActiveRunPlacementEntry(current.binding), storeToken)
            if (activeBindings.putIfAbsent(current.binding.runId, permit) == null) permit else null
        }
    }

    override fun claimForDispatch(
        permit: ActiveRunPlacementPermit,
        receipt: RunDataReceipt,
    ): ClaimInvocationResult {
        if (!owns(permit)) return ClaimInvocationResult.Rejected()
        return synchronized(permit.entry) {
            claimForDispatchLocked(permit, receipt)
        }
    }

    private fun claimForDispatchLocked(
        permit: ActiveRunPlacementPermit,
        receipt: RunDataReceipt,
    ): ClaimInvocationResult {
        val current = permit.binding
        if (receipt.destination != current.placement.toRunDataDestination()) {
            return ClaimInvocationResult.Rejected()
        }
        if (current.dispatchState !in setOf(ModelDispatchState.Pending, ModelDispatchState.Idle)) {
            return ClaimInvocationResult.Rejected()
        }
        val freshContext = runCatching(currentRecoveryContext).getOrNull()
            ?: return ClaimInvocationResult.Rejected()
        if (!current.hasCurrentDispatchSnapshot(freshContext)) {
            return ClaimInvocationResult.Rejected()
        }
        val invocation = ModelRuntimeInvocation(
            runId = current.runId,
            placement = current.placement,
            attempt = current.attempt + 1,
            remoteProfileRevision = current.remoteProfileRevision,
        )
        val persisted = runCatching {
            dao.claimAndRecordTransaction(
                runId = current.runId,
                placement = current.placement.name,
                expectedAttempt = current.attempt,
                receiptStep = AgentStep.RunDataReceiptRecorded(receipt).toTraceEntity(
                    runId = current.runId,
                    position = 0,
                    createdAtMillis = traceClockMillis(),
                ),
                invocationStep = AgentStep.ModelRuntimeInvocationStarted(invocation).toTraceEntity(
                    runId = current.runId,
                    position = 0,
                    createdAtMillis = traceClockMillis(),
                ),
            )
        }.getOrNull()?.toDomainOrNull() ?: return ClaimInvocationResult.Rejected()
        if (
            persisted.dispatchState != ModelDispatchState.Started ||
            persisted.attempt != invocation.attempt ||
            persisted.placement != invocation.placement
        ) {
            return ClaimInvocationResult.Rejected()
        }
        permit.entry.binding = persisted
        permit.entry.runtimeEntry = RuntimeInvocationEntry.Claimed(invocation)
        return ClaimInvocationResult.Started(invocation)
    }

    override fun <T> startInvocation(
        permit: ActiveRunPlacementPermit,
        invocation: ModelRuntimeInvocation,
        start: () -> RunningModelRuntimeCall<T>,
    ): RunningModelRuntimeCall<T>? {
        if (!owns(permit)) return null
        return synchronized(permit.entry) {
            val claimed = permit.entry.runtimeEntry as? RuntimeInvocationEntry.Claimed
                ?: return@synchronized null
            val current = permit.binding
            if (
                claimed.invocation != invocation ||
                current.dispatchState != ModelDispatchState.Started ||
                current.placement != invocation.placement ||
                current.attempt != invocation.attempt ||
                current.remoteProfileRevision != invocation.remoteProfileRevision
            ) {
                return@synchronized null
            }
            val call = ExactlyOnceRunningModelRuntimeCall(start())
            check(permit.entry.runtimeEntry == claimed) {
                "Runtime starter must not re-enter placement lifecycle"
            }
            permit.entry.runtimeEntry = RuntimeInvocationEntry.Running(invocation, call)
            call
        }
    }

    override fun finishInvocation(invocation: ModelRuntimeInvocation): Boolean {
        val permit = activeBindings[invocation.runId] ?: return false
        if (permit.activationToken !== storeToken) return false
        return synchronized(permit.entry) {
            finishInvocationLocked(permit, invocation)
        }
    }

    private fun finishInvocationLocked(
        permit: ActiveRunPlacementPermit,
        invocation: ModelRuntimeInvocation,
    ): Boolean {
        val current = permit.binding
        if (
            current.dispatchState in setOf(ModelDispatchState.Idle, ModelDispatchState.Terminal) &&
            current.placement == invocation.placement &&
            current.attempt == invocation.attempt &&
            current.remoteProfileRevision == invocation.remoteProfileRevision
        ) {
            permit.entry.runtimeEntry = if (current.dispatchState == ModelDispatchState.Terminal) {
                RuntimeInvocationEntry.Terminal
            } else {
                RuntimeInvocationEntry.Ready
            }
            return true
        }
        if (
            current.dispatchState != ModelDispatchState.Started ||
            current.placement != invocation.placement ||
            current.attempt != invocation.attempt ||
            current.remoteProfileRevision != invocation.remoteProfileRevision
        ) {
            return false
        }
        runCatching {
            dao.compareAndSetIdle(
                runId = invocation.runId,
                placement = invocation.placement.name,
                attempt = invocation.attempt,
            ) == 1
        }.getOrDefault(false)
        val persisted = runCatching { dao.binding(invocation.runId) }.getOrNull()?.toDomainOrNull()
        if (
            persisted != null &&
            persisted.dispatchState in setOf(ModelDispatchState.Idle, ModelDispatchState.Terminal) &&
            persisted.placement == invocation.placement &&
            persisted.attempt == invocation.attempt &&
            persisted.remoteProfileRevision == invocation.remoteProfileRevision
        ) {
            permit.entry.binding = persisted
            permit.entry.runtimeEntry = if (persisted.dispatchState == ModelDispatchState.Terminal) {
                RuntimeInvocationEntry.Terminal
            } else {
                RuntimeInvocationEntry.Ready
            }
            return true
        }
        return false
    }

    private fun owns(permit: ActiveRunPlacementPermit): Boolean =
        permit.activationToken === storeToken && activeBindings[permit.binding.runId] === permit

    private fun lifecycleLock(runId: String): Any = lifecycleLocks.computeIfAbsent(runId) { Any() }

    private fun isDurablyPublishable(binding: RunPlacementBinding): Boolean = runCatching {
        val snapshot = dao.recoverySnapshot(binding.runId) ?: return@runCatching false
        val runState = AgentRunState.valueOf(snapshot.run.state)
        !runState.isTerminalPlacementRunState() &&
            snapshot.binding.toDomainOrNull() == binding &&
            snapshot.steps.haveConsistentPlacementTrace(binding)
    }.getOrDefault(false)

    private fun RunPlacementBinding.hasCurrentDispatchSnapshot(
        context: RunPlacementRecoveryContext,
    ): Boolean = runCatching {
        val snapshot = dao.recoverySnapshot(runId) ?: return@runCatching false
        val persisted = snapshot.binding.toDomainOrNull() ?: return@runCatching false
        val runState = AgentRunState.valueOf(snapshot.run.state)
        val age = context.elapsedRealtimeMillis - boundAtElapsedRealtimeMillis
        persisted == this &&
            !runState.isTerminalPlacementRunState() &&
            schemaVersion == RUN_PLACEMENT_BINDING_SCHEMA_VERSION &&
            bootCount == context.bootCount &&
            age in 0L..RUN_PLACEMENT_RECOVERY_MAX_AGE_MILLIS &&
            (placement != RunPlacement.Remote || remoteProfileRevision == context.remoteProfileRevision) &&
            snapshot.steps.haveConsistentPlacementTrace(this)
    }.getOrDefault(false)
}

private fun RunPlacementBinding.isInitialCanonicalBinding(): Boolean =
    schemaVersion == RUN_PLACEMENT_BINDING_SCHEMA_VERSION &&
        dispatchState == ModelDispatchState.Pending &&
        attempt == 0

private fun AgentRunState.isTerminalPlacementRunState(): Boolean =
    this in setOf(AgentRunState.Completed, AgentRunState.Cancelled, AgentRunState.Failed)

private fun AgentRunState.isRestorablePlacementContinuationState(): Boolean =
    this in setOf(AgentRunState.AwaitingUserConfirmation, AgentRunState.AwaitingExternalOutcome)

private fun notRestorable(rejection: RunPlacementRecoveryRejection): RecoveryInspection.NotRestorable =
    RecoveryInspection.NotRestorable(rejection)

private fun List<AgentStepEntity>.haveConsistentPlacementTrace(binding: RunPlacementBinding): Boolean {
    val placements = filter { it.type == "PlacementSelected" }
    if (placements.size != 1 || !placements.single().matchesPlacement(binding)) return false
    val dispatchTrace = filter {
        it.type == "RunDataReceiptRecorded" || it.type == "ModelRuntimeInvocationStarted"
    }
    if (dispatchTrace.size != binding.attempt * 2) return false
    if (dispatchTrace.isNotEmpty() && placements.single().position >= dispatchTrace.first().position) return false
    return dispatchTrace.chunked(2).withIndex().all { (index, pair) ->
        pair.size == 2 &&
            pair[0].matchesReceipt(binding) &&
            pair[1].matchesInvocation(binding, index + 1)
    }
}

private fun RunPlacementRecoverySnapshotEntity.hasRestorablePlacementContinuationEvidence(
    runState: AgentRunState,
): Boolean = when (runState) {
    AgentRunState.AwaitingUserConfirmation -> hasPendingConfirmationEvidence()
    AgentRunState.AwaitingExternalOutcome -> steps.havePendingExternalOutcomeEvidence()
    else -> false
}

private fun RunPlacementRecoverySnapshotEntity.hasPendingConfirmationEvidence(): Boolean {
    val pending = pendingConfirmation ?: return false
    return pending.runId == run.id && pending.requestId.isNotBlank() && pending.toolName.isNotBlank()
}

private fun List<AgentStepEntity>.havePendingExternalOutcomeEvidence(): Boolean {
    val observation = lastOrNull { step -> step.type == "ToolObserved" } ?: return false
    if (!observation.matchesUnverifiedExternalObservation()) return false
    val observationJson = JSONObject(observation.json)
    val requestId = observationJson.getString("requestId")
    val request = asReversed().firstOrNull { step ->
        step.position < observation.position && step.matchesToolRequest(requestId)
    } ?: return false
    if (request.position >= observation.position) return false
    if (any { step -> step.position > observation.position && step.type == "ToolRequested" }) return false
    return none { step -> step.matchesExternalOutcomeConfirmation(requestId) }
}

private fun AgentStepEntity.matchesToolRequest(requestId: String): Boolean = runCatching {
    if (type != "ToolRequested") return@runCatching false
    val json = JSONObject(json)
    json.keysSet() == TOOL_REQUESTED_TRACE_KEYS &&
        json.getString("type") == "ToolRequested" &&
        json.getString("requestId") == requestId &&
        json.getString("toolName").isNotBlank()
}.getOrDefault(false)

private fun AgentStepEntity.matchesUnverifiedExternalObservation(): Boolean = runCatching {
    if (type != "ToolObserved") return@runCatching false
    val json = JSONObject(json)
    if (json.keysSet() != TOOL_OBSERVED_EXTERNAL_TRACE_KEYS) return@runCatching false
    val metadata = json.getJSONObject("completionMetadata")
    json.getString("type") == "ToolObserved" &&
        json.getString("requestId").isNotBlank() &&
        json.getString("status") == "Succeeded" &&
        metadata.getString("completionState") == "ExternalActivityOpened" &&
        metadata.getString("completionVerified") == "false" &&
        metadata.getString("externalOutcome") == "Unknown" &&
        metadata.getString("externalOutcomeSource") == "Unknown"
}.getOrDefault(false)

private fun AgentStepEntity.matchesExternalOutcomeConfirmation(requestId: String): Boolean = runCatching {
    type == "ExternalOutcomeConfirmed" && JSONObject(json).getString("requestId") == requestId
}.getOrDefault(false)

private fun AgentStepEntity.matchesPlacement(binding: RunPlacementBinding): Boolean = runCatching {
    val json = JSONObject(json)
    json.keysSet() == PLACEMENT_SELECTED_TRACE_KEYS &&
        json.getInt("schemaVersion") == RUN_PLACEMENT_TRACE_SCHEMA_VERSION &&
        json.getString("runId") == binding.runId &&
        json.getInt("policyVersion") == binding.policyVersion &&
        json.getString("preference") == binding.preference.name &&
        json.getString("placement") == binding.placement.name &&
        json.getString("primaryReason") == binding.primaryReason.name &&
        json.getString("complexity") == binding.complexity.name &&
        json.getString("resourceBand") == binding.resourceBand.name &&
        json.getString("localState") == binding.localState.name &&
        json.getString("remoteState") == binding.remoteState.name &&
        json.nullableString("remoteProfileRevision") == binding.remoteProfileRevision
}.getOrDefault(false)

private fun AgentStepEntity.matchesReceipt(binding: RunPlacementBinding): Boolean = runCatching {
    val json = JSONObject(json)
    json.keysSet() == RUN_DATA_RECEIPT_TRACE_KEYS &&
        json.getString("destination") == binding.placement.toRunDataDestination().name
}.getOrDefault(false)

private fun AgentStepEntity.matchesInvocation(binding: RunPlacementBinding, expectedAttempt: Int): Boolean =
    runCatching {
        val json = JSONObject(json)
        json.keysSet() == MODEL_RUNTIME_INVOCATION_TRACE_KEYS &&
            json.getInt("schemaVersion") == RUN_PLACEMENT_TRACE_SCHEMA_VERSION &&
            json.getString("runId") == binding.runId &&
            json.getString("placement") == binding.placement.name &&
            json.getInt("attempt") == expectedAttempt &&
            json.nullableString("remoteProfileRevision") == binding.remoteProfileRevision
    }.getOrDefault(false)

internal val PLACEMENT_SELECTED_TRACE_KEYS = setOf(
    "type",
    "schemaVersion",
    "runId",
    "policyVersion",
    "preference",
    "placement",
    "primaryReason",
    "complexity",
    "resourceBand",
    "localState",
    "remoteState",
    "remoteProfileRevision",
)

internal val MODEL_RUNTIME_INVOCATION_TRACE_KEYS = setOf(
    "type",
    "schemaVersion",
    "runId",
    "placement",
    "attempt",
    "remoteProfileRevision",
)

internal val SHADOW_PLACEMENT_TRACE_KEYS = setOf(
    "type",
    "schemaVersion",
    "runId",
    "policyVersion",
    "preference",
    "placement",
    "primaryReason",
    "complexity",
    "resourceBand",
    "localState",
    "remoteState",
)

internal val RUN_DATA_RECEIPT_TRACE_KEYS = setOf(
    "type",
    "destination",
    "currentPromptPrivacy",
    "remoteHistoryCount",
    "localOnlyHistoryFilteredCount",
    "memoryHitCount",
    "semanticMemoryHitCount",
    "lexicalMemoryHitCount",
    "memoryContextIncluded",
    "deviceContextIncluded",
    "imageAttachmentCount",
    "protectedSourceCount",
    "evidenceCardCount",
    "localOnlyEvidenceCardCount",
    "truncatedEvidenceCardCount",
    "lowQualityEvidenceCardCount",
    "evidenceSourceTypes",
    "rawContentPersisted",
    "protectedContentTypes",
    "deletableRecordTypes",
    "outputQualityGuardTriggered",
    "outputQualityIssue",
    "outputQualityRule",
    "outputQualityAction",
    "outputQualityStopped",
    "outputQualityKeptPrefix",
)

private val TOOL_REQUESTED_TRACE_KEYS = setOf(
    "type",
    "requestId",
    "toolName",
    "argumentKeys",
    "draftTitle",
)

private val TOOL_OBSERVED_EXTERNAL_TRACE_KEYS = setOf(
    "type",
    "requestId",
    "status",
    "retryable",
    "completionMetadata",
)

internal fun JSONObject.keysSet(): Set<String> = keys().asSequence().toSet()

private fun JSONObject.nullableString(key: String): String? =
    if (isNull(key)) null else getString(key)

private const val REDACTED_CRITICAL_RUN_INPUT = "[redacted]"
