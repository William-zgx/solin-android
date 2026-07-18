package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.orchestration.ActiveRunPlacementPermit
import com.bytedance.zgx.solin.orchestration.BoundRunContinuationResolution
import com.bytedance.zgx.solin.orchestration.ModelDispatchState
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.orchestration.testBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolContinuationSupportTest {
    @Test
    fun remoteBindingBlocksLocalOnlyObservationWithoutChoosingFallback() {
        val permit = permit(
            placement = RunPlacement.Remote,
            preference = InferenceMode.Local,
        )

        val resolution = resolveBoundToolContinuation(
            runId = permit.binding.runId,
            observationPrivacy = MessagePrivacy.LocalOnly,
            requiresLocalModel = true,
            activeRunPlacement = { permit },
        )

        assertEquals(
            PlacementReasonCode.PLACEMENT_LOCAL_CONTINUATION_REQUIRED,
            (resolution as BoundRunContinuationResolution.Blocked).reason,
        )
    }

    @Test
    fun remoteBindingBlocksUnknownObservation() {
        val permit = permit(placement = RunPlacement.Remote)

        val resolution = resolveBoundToolContinuation(
            runId = permit.binding.runId,
            observationPrivacy = null,
            requiresLocalModel = null,
            activeRunPlacement = { permit },
        )

        assertEquals(
            PlacementReasonCode.PLACEMENT_LOCAL_CONTINUATION_REQUIRED,
            (resolution as BoundRunContinuationResolution.Blocked).reason,
        )
    }

    @Test
    fun bindingPlacementWinsOverStoredPreference() {
        val remotePermit = permit(
            placement = RunPlacement.Remote,
            preference = InferenceMode.Local,
        )
        val localPermit = permit(
            placement = RunPlacement.Local,
            preference = InferenceMode.Remote,
        )

        val remote = resolveBoundToolContinuation(
            runId = remotePermit.binding.runId,
            observationPrivacy = MessagePrivacy.RemoteEligible,
            requiresLocalModel = false,
            activeRunPlacement = { remotePermit },
        )
        val local = resolveBoundToolContinuation(
            runId = localPermit.binding.runId,
            observationPrivacy = MessagePrivacy.LocalOnly,
            requiresLocalModel = true,
            activeRunPlacement = { localPermit },
        )

        assertEquals(
            RunPlacement.Remote,
            (remote as BoundRunContinuationResolution.Dispatch).permit.binding.placement,
        )
        assertEquals(
            RunPlacement.Local,
            (local as BoundRunContinuationResolution.Dispatch).permit.binding.placement,
        )
    }

    @Test
    fun missingBindingFailsClosed() {
        val resolution = resolveBoundToolContinuation(
            runId = "run-missing",
            observationPrivacy = MessagePrivacy.RemoteEligible,
            requiresLocalModel = false,
            activeRunPlacement = { null },
        )

        assertTrue(resolution is BoundRunContinuationResolution.Blocked)
        assertEquals(
            PlacementReasonCode.PLACEMENT_NOT_RESTORABLE,
            (resolution as BoundRunContinuationResolution.Blocked).reason,
        )
    }

    private fun permit(
        placement: RunPlacement,
        preference: InferenceMode = InferenceMode.Auto,
    ): ActiveRunPlacementPermit = ActiveRunPlacementPermit.detached(
        testBinding(
            runId = "run-${placement.name.lowercase()}-${preference.name.lowercase()}",
            placement = placement,
            dispatchState = ModelDispatchState.Idle,
            attempt = 1,
        ).copy(preference = preference),
    )
}
