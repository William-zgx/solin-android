package com.bytedance.zgx.solin.device

/** The selectable candidates [UiTargetResolver.explain] ranks, best first (empty when none qualify). */
internal fun UiTargetResolver.resolveAll(
    snapshot: ScreenStateSnapshot,
    kind: UiTargetKind,
    target: String? = null,
    profile: AppInteractionProfile? = AppInteractionProfiles.forPackage(snapshot.packageName),
): List<UiTargetEvidenceCandidate> =
    explain(snapshot, kind, target, profile).selectableCandidates()

/** The candidate [UiTargetResolver.explain] selects, or null when it reports a failure. */
internal fun UiTargetResolver.resolve(
    snapshot: ScreenStateSnapshot,
    kind: UiTargetKind,
    target: String? = null,
    profile: AppInteractionProfile? = AppInteractionProfiles.forPackage(snapshot.packageName),
): UiTargetEvidenceCandidate? =
    resolveAll(snapshot, kind, target, profile).firstOrNull()

internal fun UiTargetResolver.resolve(
    observation: ScreenObservation,
    kind: UiTargetKind,
    target: String? = null,
    profile: AppInteractionProfile? = AppInteractionProfiles.forPackage(observation.packageName),
): UiTargetEvidenceCandidate? =
    explain(observation, kind, target, profile).selectableCandidates().firstOrNull()

private fun UiTargetResolutionEvidence.selectableCandidates(): List<UiTargetEvidenceCandidate> =
    if (failureKind == null) rankedCandidates else emptyList()
