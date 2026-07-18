package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.data.AgentRunPlacementBindingEntity
import com.bytedance.zgx.solin.resource.StableResourceBand
import java.util.UUID

const val RUN_PLACEMENT_BINDING_SCHEMA_VERSION = 1
const val RUN_PLACEMENT_TRACE_SCHEMA_VERSION = 1
const val RUN_PLACEMENT_RECOVERY_MAX_AGE_MILLIS = 30L * 60L * 1_000L

enum class ModelDispatchState {
    Pending,
    Started,
    Idle,
    Terminal,
}

data class RunPlacementBinding(
    val schemaVersion: Int = RUN_PLACEMENT_BINDING_SCHEMA_VERSION,
    val runId: String,
    val policyVersion: Int,
    val preference: InferenceMode,
    val placement: RunPlacement,
    val primaryReason: PlacementReasonCode,
    val complexity: RequestComplexity,
    val resourceBand: StableResourceBand,
    val localState: CandidateState,
    val remoteState: CandidateState,
    val remoteProfileRevision: String?,
    val bootCount: Long,
    val boundAtElapsedRealtimeMillis: Long,
    val dispatchState: ModelDispatchState = ModelDispatchState.Pending,
    val attempt: Int = 0,
) {
    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(policyVersion > 0) { "policyVersion must be positive" }
        require(bootCount >= 0L) { "bootCount must be non-negative" }
        require(boundAtElapsedRealtimeMillis >= 0L) { "bound time must be non-negative" }
        require(attempt >= 0) { "attempt must be non-negative" }
        require(dispatchState != ModelDispatchState.Pending || attempt == 0) {
            "Pending binding must have attempt 0"
        }
        require(dispatchState !in setOf(ModelDispatchState.Started, ModelDispatchState.Idle) || attempt >= 1) {
            "$dispatchState binding must have a started attempt"
        }
        when (placement) {
            RunPlacement.Local -> require(remoteProfileRevision == null) {
                "Local binding must not retain a remote profile revision"
            }
            RunPlacement.Remote -> require(remoteProfileRevision.isCanonicalRemoteProfileRevision()) {
                "Remote binding requires a canonical opaque UUID revision"
            }
        }
    }

    companion object {
        fun fromDecision(
            runId: String,
            decision: PlacementDecision.Chosen,
            remoteProfileRevision: String?,
            bootCount: Long,
            boundAtElapsedRealtimeMillis: Long,
        ): RunPlacementBinding = RunPlacementBinding(
            runId = runId,
            policyVersion = decision.policyVersion,
            preference = decision.preference,
            placement = decision.placement,
            primaryReason = decision.primaryReason,
            complexity = decision.diagnostics.complexity,
            resourceBand = decision.diagnostics.resourceBand,
            localState = decision.diagnostics.localState,
            remoteState = decision.diagnostics.remoteState,
            remoteProfileRevision = remoteProfileRevision.takeIf { decision.placement == RunPlacement.Remote },
            bootCount = bootCount,
            boundAtElapsedRealtimeMillis = boundAtElapsedRealtimeMillis,
        )
    }
}

internal class ActiveRunPlacementEntry(
    @Volatile var binding: RunPlacementBinding,
)

class ActiveRunPlacementPermit internal constructor(
    internal val entry: ActiveRunPlacementEntry,
    internal val activationToken: Any,
) {
    val binding: RunPlacementBinding
        get() = entry.binding

    internal companion object {
        fun detached(binding: RunPlacementBinding): ActiveRunPlacementPermit =
            ActiveRunPlacementPermit(ActiveRunPlacementEntry(binding), Any())
    }
}

data class ModelRuntimeInvocation(
    val runId: String,
    val placement: RunPlacement,
    val attempt: Int,
    val remoteProfileRevision: String?,
) {
    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(attempt >= 1) { "Runtime invocation attempt must be positive" }
        when (placement) {
            RunPlacement.Local -> require(remoteProfileRevision == null) {
                "Local invocation must not retain a remote profile revision"
            }
            RunPlacement.Remote -> require(remoteProfileRevision.isCanonicalRemoteProfileRevision()) {
                "Remote invocation requires a canonical opaque UUID revision"
            }
        }
    }
}

data class RunPlacementRecoveryContext(
    val bootCount: Long,
    val elapsedRealtimeMillis: Long,
    val remoteProfileRevision: String? = null,
)

enum class RunPlacementRecoveryRejection {
    MissingBinding,
    MissingRun,
    UnknownSchema,
    UnknownEnum,
    BootChanged,
    ClockMovedBackwards,
    Expired,
    RemoteRevisionChanged,
    PendingNeverStarted,
    StartedNeverReplayed,
    DispatchStateNotIdle,
    TerminalRun,
    RunStateNotRestorable,
    ContinuationEvidenceMissing,
    InvalidAttempt,
    TraceMismatch,
}

sealed interface RecoveryInspection {
    class ContinuationCandidate internal constructor(
        val binding: RunPlacementBinding,
        internal val context: RunPlacementRecoveryContext,
        internal val storeToken: Any,
    ) : RecoveryInspection

    data class NotRestorable(
        val rejection: RunPlacementRecoveryRejection,
        val reason: PlacementReasonCode = PlacementReasonCode.PLACEMENT_NOT_RESTORABLE,
    ) : RecoveryInspection
}

sealed interface BindAndReserveResult {
    data class Bound(val permit: ActiveRunPlacementPermit) : BindAndReserveResult
    data class Rejected(val reason: PlacementReasonCode = PlacementReasonCode.PLACEMENT_NOT_RESTORABLE) :
        BindAndReserveResult
}

sealed interface ClaimInvocationResult {
    data class Started(val invocation: ModelRuntimeInvocation) : ClaimInvocationResult
    data class Rejected(val reason: PlacementReasonCode = PlacementReasonCode.PLACEMENT_NOT_RESTORABLE) :
        ClaimInvocationResult
}

sealed interface TerminalizeRunResult {
    data class Terminalized(val placement: RunPlacement?) : TerminalizeRunResult

    object Rejected : TerminalizeRunResult
}

data class ShadowPlacementTrace(
    val policyVersion: Int,
    val preference: InferenceMode,
    val placement: RunPlacement?,
    val primaryReason: PlacementReasonCode,
    val complexity: RequestComplexity,
    val resourceBand: StableResourceBand,
    val localState: CandidateState,
    val remoteState: CandidateState,
) {
    companion object {
        fun from(decision: PlacementDecision): ShadowPlacementTrace = ShadowPlacementTrace(
            policyVersion = decision.policyVersion,
            preference = decision.preference,
            placement = (decision as? PlacementDecision.Chosen)?.placement,
            primaryReason = decision.primaryReason,
            complexity = decision.diagnostics.complexity,
            resourceBand = decision.diagnostics.resourceBand,
            localState = decision.diagnostics.localState,
            remoteState = decision.diagnostics.remoteState,
        )
    }
}

fun RunPlacement.toRunDataDestination(): RunDataDestination = when (this) {
    RunPlacement.Local -> RunDataDestination.Local
    RunPlacement.Remote -> RunDataDestination.Remote
}

internal fun RunPlacementBinding.toEntity(): AgentRunPlacementBindingEntity =
    AgentRunPlacementBindingEntity(
        runId = runId,
        schemaVersion = schemaVersion,
        policyVersion = policyVersion,
        preference = preference.name,
        placement = placement.name,
        primaryReason = primaryReason.name,
        complexity = complexity.name,
        resourceBand = resourceBand.name,
        localState = localState.name,
        remoteState = remoteState.name,
        remoteProfileRevision = remoteProfileRevision,
        bootCount = bootCount,
        boundAtElapsedRealtimeMillis = boundAtElapsedRealtimeMillis,
        dispatchState = dispatchState.name,
        attempt = attempt,
    )

internal fun AgentRunPlacementBindingEntity.toDomainOrNull(): RunPlacementBinding? = runCatching {
    RunPlacementBinding(
        runId = runId,
        schemaVersion = schemaVersion,
        policyVersion = policyVersion,
        preference = InferenceMode.valueOf(preference),
        placement = RunPlacement.valueOf(placement),
        primaryReason = PlacementReasonCode.valueOf(primaryReason),
        complexity = RequestComplexity.valueOf(complexity),
        resourceBand = StableResourceBand.valueOf(resourceBand),
        localState = CandidateState.valueOf(localState),
        remoteState = CandidateState.valueOf(remoteState),
        remoteProfileRevision = remoteProfileRevision,
        bootCount = bootCount,
        boundAtElapsedRealtimeMillis = boundAtElapsedRealtimeMillis,
        dispatchState = ModelDispatchState.valueOf(dispatchState),
        attempt = attempt,
    )
}.getOrNull()

internal fun String?.isCanonicalRemoteProfileRevision(): Boolean {
    val revision = this ?: return false
    if (revision.length != CANONICAL_UUID_LENGTH || revision != revision.lowercase()) return false
    return runCatching { UUID.fromString(revision).toString() == revision }.getOrDefault(false)
}

private const val CANONICAL_UUID_LENGTH = 36
