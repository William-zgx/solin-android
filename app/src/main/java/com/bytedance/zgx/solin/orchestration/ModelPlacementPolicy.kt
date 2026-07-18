package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.resource.StableResourceBand
import com.bytedance.zgx.solin.resource.StableResourceState
import com.bytedance.zgx.solin.resource.ThermalPressure

enum class RunPlacement {
    Local,
    Remote,
}

enum class CandidateState {
    Eligible,
    Unavailable,
    Unauthorized,
    NotConfigured,
    CapabilityMismatch,
    PrivacyBlocked,
    Stale,
    ResourceBlocked,
    Overloaded,
}

enum class PlacementReasonCode {
    USER_FORCED_LOCAL,
    USER_FORCED_REMOTE,
    PRIVACY_REQUIRES_LOCAL,
    LOCAL_MODEL_UNAVAILABLE,
    LOCAL_RESOURCE_BLOCKED,
    LOCAL_CAPABILITY_MISMATCH,
    REMOTE_NOT_AUTHORIZED,
    REMOTE_NOT_CONFIGURED,
    REMOTE_CONNECTIVITY_UNAVAILABLE,
    REMOTE_STATUS_STALE,
    REMOTE_CAPABILITY_MISMATCH,
    REMOTE_OVERLOADED,
    AUTO_SIMPLE_LOCAL,
    AUTO_IMAGE_LOCAL,
    AUTO_COMPLEX_REMOTE,
    AUTO_RESOURCE_REMOTE,
    NO_ELIGIBLE_TARGET,
    PLACEMENT_DECISION_MISSING,
    PLACEMENT_NOT_RESTORABLE,
    PLACEMENT_LOCAL_CONTINUATION_REQUIRED,
    MODEL_EXECUTION_FAILED,
}

data class ModelRequirements(
    val requiresText: Boolean,
    val requiresVision: Boolean,
    val requiresTools: Boolean,
    val estimatedInputTokens: Int?,
    val requestedOutputTokens: Int?,
)

data class ModelCandidateSnapshot(
    val available: Boolean,
    val supportsText: Boolean,
    val supportsVision: Boolean,
    val supportsTools: Boolean,
    val contextWindowTokens: Int?,
    val runtimeAdmissionFailed: Boolean = false,
    val configured: Boolean = false,
    val authorized: Boolean = false,
    val connectivityStatus: RemoteModelConnectivityStatus = RemoteModelConnectivityStatus.Unknown,
    val connectivityFresh: Boolean = false,
    val profileRevisionMatches: Boolean = false,
    val overloaded: Boolean = false,
)

data class PlacementDiagnostics(
    val complexity: RequestComplexity,
    val resourceBand: StableResourceBand,
    val stableLowMemory: Boolean,
    val latestLowMemory: Boolean,
    val localHardBlocked: Boolean,
    val thermalPressure: ThermalPressure,
    val localState: CandidateState,
    val remoteState: CandidateState,
    val secondaryReasons: List<PlacementReasonCode> = emptyList(),
)

sealed interface PlacementDecision {
    val policyVersion: Int
    val preference: InferenceMode
    val primaryReason: PlacementReasonCode
    val diagnostics: PlacementDiagnostics

    data class Chosen(
        override val policyVersion: Int,
        override val preference: InferenceMode,
        override val primaryReason: PlacementReasonCode,
        override val diagnostics: PlacementDiagnostics,
        val placement: RunPlacement,
    ) : PlacementDecision

    data class Blocked(
        override val policyVersion: Int,
        override val preference: InferenceMode,
        override val primaryReason: PlacementReasonCode,
        override val diagnostics: PlacementDiagnostics,
    ) : PlacementDecision
}

data class ModelPlacementInput(
    val preference: InferenceMode,
    val privacy: MessagePrivacy?,
    val requiresLocalModel: Boolean,
    val requirements: ModelRequirements,
    val local: ModelCandidateSnapshot,
    val remote: ModelCandidateSnapshot,
    val resources: StableResourceState,
    val complexity: RequestComplexity,
)

object ModelPlacementPolicy {
    const val POLICY_VERSION = 1

    fun decide(input: ModelPlacementInput): PlacementDecision {
        val localState = input.localState()
        val remoteState = input.remoteState()
        return when (input.preference) {
            InferenceMode.Local -> if (localState == CandidateState.Eligible) {
                input.choose(RunPlacement.Local, PlacementReasonCode.USER_FORCED_LOCAL, localState, remoteState)
            } else {
                input.block(localState.localFailureReason(), localState, remoteState)
            }
            InferenceMode.Remote -> if (remoteState == CandidateState.Eligible) {
                input.choose(RunPlacement.Remote, PlacementReasonCode.USER_FORCED_REMOTE, localState, remoteState)
            } else {
                input.block(remoteState.remoteFailureReason(), localState, remoteState)
            }
            InferenceMode.Auto -> input.decideAuto(localState, remoteState)
        }
    }

    private fun ModelPlacementInput.decideAuto(
        localState: CandidateState,
        remoteState: CandidateState,
    ): PlacementDecision {
        val localEligible = localState == CandidateState.Eligible
        val remoteEligible = remoteState == CandidateState.Eligible
        return when {
            localEligible && !remoteEligible -> choose(
                RunPlacement.Local,
                remoteState.remoteFailureReason(),
                localState,
                remoteState,
            )
            !localEligible && remoteEligible -> choose(
                RunPlacement.Remote,
                localState.localFailureReason(),
                localState,
                remoteState,
            )
            !localEligible -> block(
                if (
                    localState == CandidateState.CapabilityMismatch &&
                    remoteState == CandidateState.CapabilityMismatch
                ) {
                    PlacementReasonCode.LOCAL_CAPABILITY_MISMATCH
                } else {
                    PlacementReasonCode.NO_ELIGIBLE_TARGET
                },
                localState,
                remoteState,
            )
            requirements.requiresVision && local.supportsVision -> choose(
                RunPlacement.Local,
                PlacementReasonCode.AUTO_IMAGE_LOCAL,
                localState,
                remoteState,
            )
            resources.hasRemoteResourceBias() -> choose(
                RunPlacement.Remote,
                PlacementReasonCode.AUTO_RESOURCE_REMOTE,
                localState,
                remoteState,
            )
            complexity == RequestComplexity.Complex -> choose(
                RunPlacement.Remote,
                PlacementReasonCode.AUTO_COMPLEX_REMOTE,
                localState,
                remoteState,
            )
            else -> choose(
                RunPlacement.Local,
                PlacementReasonCode.AUTO_SIMPLE_LOCAL,
                localState,
                remoteState,
            )
        }
    }

    private fun ModelPlacementInput.localState(): CandidateState = when {
        !local.available -> CandidateState.Unavailable
        !local.meets(requirements, requireContextProof = false) -> CandidateState.CapabilityMismatch
        local.runtimeAdmissionFailed || resources.isLocalHardBlocked() -> CandidateState.ResourceBlocked
        else -> CandidateState.Eligible
    }

    private fun ModelPlacementInput.remoteState(): CandidateState {
        if (requiresLocalModel || privacy != MessagePrivacy.RemoteEligible) return CandidateState.PrivacyBlocked
        if (!remote.configured) return CandidateState.NotConfigured
        if (!remote.available) return CandidateState.Unavailable
        if (!remote.meets(requirements, requireContextProof = preference == InferenceMode.Auto)) {
            return CandidateState.CapabilityMismatch
        }

        return if (preference == InferenceMode.Remote) {
            when {
                remote.connectivityStatus == RemoteModelConnectivityStatus.AuthenticationFailed ->
                    CandidateState.Unauthorized
                remote.connectivityStatus == RemoteModelConnectivityStatus.Unreachable ->
                    CandidateState.Unavailable
                else -> CandidateState.Eligible
            }
        } else {
            when {
                !remote.authorized -> CandidateState.Unauthorized
                remote.connectivityStatus == RemoteModelConnectivityStatus.AuthenticationFailed ->
                    CandidateState.Unauthorized
                remote.connectivityStatus == RemoteModelConnectivityStatus.Unreachable ->
                    CandidateState.Unavailable
                !remote.connectivityFresh || !remote.profileRevisionMatches -> CandidateState.Stale
                remote.connectivityStatus != RemoteModelConnectivityStatus.Reachable -> CandidateState.Stale
                remote.overloaded -> CandidateState.Overloaded
                else -> CandidateState.Eligible
            }
        }
    }

    private fun ModelPlacementInput.choose(
        placement: RunPlacement,
        reason: PlacementReasonCode,
        localState: CandidateState,
        remoteState: CandidateState,
    ): PlacementDecision.Chosen = PlacementDecision.Chosen(
        policyVersion = POLICY_VERSION,
        preference = preference,
        primaryReason = reason,
        diagnostics = diagnostics(reason, localState, remoteState),
        placement = placement,
    )

    private fun ModelPlacementInput.block(
        reason: PlacementReasonCode,
        localState: CandidateState,
        remoteState: CandidateState,
    ): PlacementDecision.Blocked = PlacementDecision.Blocked(
        policyVersion = POLICY_VERSION,
        preference = preference,
        primaryReason = reason,
        diagnostics = diagnostics(reason, localState, remoteState),
    )

    private fun ModelPlacementInput.diagnostics(
        primaryReason: PlacementReasonCode,
        localState: CandidateState,
        remoteState: CandidateState,
    ): PlacementDiagnostics = PlacementDiagnostics(
        complexity = complexity,
        resourceBand = resources.band,
        stableLowMemory = resources.stableLowMemory,
        latestLowMemory = resources.latestLowMemory,
        localHardBlocked = resources.isLocalHardBlocked(),
        thermalPressure = resources.thermalPressure,
        localState = localState,
        remoteState = remoteState,
        secondaryReasons = listOfNotNull(
            localState.failureReason(isLocal = true),
            remoteState.failureReason(isLocal = false),
        ).filterNot { it == primaryReason }.distinct(),
    )
}

private fun ModelCandidateSnapshot.meets(
    requirements: ModelRequirements,
    requireContextProof: Boolean,
): Boolean {
    if (requirements.requiresText && !supportsText) return false
    if (requirements.requiresVision && !supportsVision) return false
    if (requirements.requiresTools && !supportsTools) return false

    val estimatedInput = requirements.estimatedInputTokens
    val requestedOutput = requirements.requestedOutputTokens
    if (!requireContextProof) {
        val contextWindow = contextWindowTokens
        if (
            contextWindow == null || contextWindow <= 0 ||
            estimatedInput == null || estimatedInput <= 0 ||
            requestedOutput == null || requestedOutput <= 0
        ) {
            return true
        }
        return estimatedInput.toLong() + requestedOutput.toLong() <= contextWindow.toLong()
    }
    if (estimatedInput == null || estimatedInput <= 0 || requestedOutput == null || requestedOutput <= 0) {
        return false
    }
    val contextWindow = contextWindowTokens ?: return false
    if (contextWindow <= 0) return false
    return estimatedInput.toLong() + requestedOutput.toLong() <= contextWindow.toLong()
}

private fun StableResourceState.isLocalHardBlocked(): Boolean = localHardBlocked ||
    thermalPressure == ThermalPressure.Emergency ||
    thermalPressure == ThermalPressure.Shutdown

private fun StableResourceState.hasRemoteResourceBias(): Boolean =
    stableLowMemory ||
        latestLowMemory ||
        band == StableResourceBand.Hot ||
        thermalPressure == ThermalPressure.Severe ||
        thermalPressure == ThermalPressure.Critical

private fun CandidateState.localFailureReason(): PlacementReasonCode =
    failureReason(isLocal = true) ?: PlacementReasonCode.NO_ELIGIBLE_TARGET

private fun CandidateState.remoteFailureReason(): PlacementReasonCode =
    failureReason(isLocal = false) ?: PlacementReasonCode.NO_ELIGIBLE_TARGET

private fun CandidateState.failureReason(isLocal: Boolean): PlacementReasonCode? = when (this) {
    CandidateState.Eligible -> null
    CandidateState.CapabilityMismatch -> if (isLocal) {
        PlacementReasonCode.LOCAL_CAPABILITY_MISMATCH
    } else {
        PlacementReasonCode.REMOTE_CAPABILITY_MISMATCH
    }
    CandidateState.ResourceBlocked -> if (isLocal) {
        PlacementReasonCode.LOCAL_RESOURCE_BLOCKED
    } else {
        PlacementReasonCode.REMOTE_CONNECTIVITY_UNAVAILABLE
    }
    CandidateState.Unavailable -> if (isLocal) {
        PlacementReasonCode.LOCAL_MODEL_UNAVAILABLE
    } else {
        PlacementReasonCode.REMOTE_CONNECTIVITY_UNAVAILABLE
    }
    CandidateState.Unauthorized -> PlacementReasonCode.REMOTE_NOT_AUTHORIZED
    CandidateState.NotConfigured -> PlacementReasonCode.REMOTE_NOT_CONFIGURED
    CandidateState.PrivacyBlocked -> PlacementReasonCode.PRIVACY_REQUIRES_LOCAL
    CandidateState.Stale -> PlacementReasonCode.REMOTE_STATUS_STALE
    CandidateState.Overloaded -> PlacementReasonCode.REMOTE_OVERLOADED
}
