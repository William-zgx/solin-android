package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy

internal sealed interface BoundRunContinuationResolution {
    data class Dispatch(val permit: ActiveRunPlacementPermit) : BoundRunContinuationResolution

    data class Blocked(val reason: PlacementReasonCode) : BoundRunContinuationResolution
}

/**
 * Pure gate for continuation/retry callers that already resolved the run's active binding.
 * It never chooses another placement and never activates, dispatches, or falls back by itself.
 */
internal object BoundRunContinuationResolver {
    fun resolve(
        permit: ActiveRunPlacementPermit?,
        privacyPlan: PromptPrivacyPlan?,
    ): BoundRunContinuationResolution {
        if (
            permit == null ||
            privacyPlan == null ||
            permit.binding.dispatchState == ModelDispatchState.Terminal
        ) {
            return BoundRunContinuationResolution.Blocked(
                PlacementReasonCode.PLACEMENT_NOT_RESTORABLE,
            )
        }
        if (
            permit.binding.placement == RunPlacement.Remote &&
            (
                privacyPlan.aggregatePrivacy != MessagePrivacy.RemoteEligible ||
                    privacyPlan.requiresLocalModel
            )
        ) {
            return BoundRunContinuationResolution.Blocked(
                PlacementReasonCode.PLACEMENT_LOCAL_CONTINUATION_REQUIRED,
            )
        }
        return BoundRunContinuationResolution.Dispatch(permit)
    }
}
