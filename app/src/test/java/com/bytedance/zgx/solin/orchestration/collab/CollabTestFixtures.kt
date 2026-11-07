package com.bytedance.zgx.solin.orchestration.collab

import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.orchestration.ModelCandidateSnapshot
import com.bytedance.zgx.solin.orchestration.ModelRequirements
import com.bytedance.zgx.solin.orchestration.PlacementDecision
import com.bytedance.zgx.solin.orchestration.PromptPrivacyPlan
import com.bytedance.zgx.solin.orchestration.PromptPrivacySegment
import com.bytedance.zgx.solin.orchestration.PromptSegmentSource
import com.bytedance.zgx.solin.orchestration.RequestComplexity
import com.bytedance.zgx.solin.resource.StableResourceBand
import com.bytedance.zgx.solin.resource.StableResourceState
import com.bytedance.zgx.solin.resource.ThermalPressure

internal fun segment(
    source: PromptSegmentSource = PromptSegmentSource.CurrentInput,
    privacy: MessagePrivacy? = MessagePrivacy.RemoteEligible,
    requiresLocalModel: Boolean? = false,
    optionalHistory: Boolean = false,
): PromptPrivacySegment = PromptPrivacySegment(
    source = source,
    privacy = privacy,
    requiresLocalModel = requiresLocalModel,
    optionalHistory = optionalHistory,
)

/** Defaults to Complex so that a Local result can only come from the role contract or privacy. */
internal fun roleInput(
    role: CollaborationRole,
    inputs: List<PromptPrivacySegment>? = listOf(segment()),
    preference: InferenceMode = InferenceMode.Auto,
    local: ModelCandidateSnapshot = localCandidate(),
    remote: ModelCandidateSnapshot = remoteCandidate(),
    complexity: RequestComplexity = RequestComplexity.Complex,
): RolePlacementInput = RolePlacementInput(
    role = role,
    inputs = inputs,
    preference = preference,
    requirements = ModelRequirements(
        requiresText = true,
        requiresVision = false,
        requiresTools = false,
        estimatedInputTokens = 200,
        requestedOutputTokens = 512,
    ),
    local = local,
    remote = remote,
    resources = StableResourceState(
        band = StableResourceBand.Normal,
        stableLowMemory = false,
        latestLowMemory = false,
        localHardBlocked = false,
        thermalPressure = ThermalPressure.Normal,
    ),
    complexity = complexity,
)

/** This decision with fields replaced, as [RolePlacementPolicy] would never return it. */
internal fun RolePlacementDecision.forged(
    privacy: PromptPrivacyPlan = this.privacy,
    decision: PlacementDecision = this.decision,
): RolePlacementDecision = RolePlacementDecision.forTest(role, constraint, privacy, decision)

internal fun localCandidate(available: Boolean = true): ModelCandidateSnapshot = ModelCandidateSnapshot(
    available = available,
    supportsText = true,
    supportsVision = false,
    supportsTools = true,
    contextWindowTokens = 8_192,
)

internal fun remoteCandidate(
    configured: Boolean = true,
    authorized: Boolean = true,
    connectivityStatus: RemoteModelConnectivityStatus = RemoteModelConnectivityStatus.Reachable,
    connectivityFresh: Boolean = true,
    overloaded: Boolean = false,
): ModelCandidateSnapshot = ModelCandidateSnapshot(
    available = true,
    supportsText = true,
    supportsVision = false,
    supportsTools = true,
    contextWindowTokens = 32_768,
    configured = configured,
    authorized = authorized,
    connectivityStatus = connectivityStatus,
    connectivityFresh = connectivityFresh,
    profileRevisionMatches = true,
    overloaded = overloaded,
)
