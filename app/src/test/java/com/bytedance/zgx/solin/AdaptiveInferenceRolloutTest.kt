package com.bytedance.zgx.solin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveInferenceRolloutTest {
    @Test
    fun parsesEveryStageAndFailsClosedForBlankOrUnknownValues() {
        assertEquals(AdaptiveInferenceRollout.Off, AdaptiveInferenceRollout.parse(null))
        assertEquals(AdaptiveInferenceRollout.Off, AdaptiveInferenceRollout.parse(""))
        assertEquals(AdaptiveInferenceRollout.Off, AdaptiveInferenceRollout.parse("future"))
        assertEquals(AdaptiveInferenceRollout.Off, AdaptiveInferenceRollout.parse("off"))
        assertEquals(AdaptiveInferenceRollout.Shadow, AdaptiveInferenceRollout.parse("shadow"))
        assertEquals(AdaptiveInferenceRollout.OptIn, AdaptiveInferenceRollout.parse("opt_in"))
        assertEquals(AdaptiveInferenceRollout.Visible, AdaptiveInferenceRollout.parse("visible"))
        assertEquals(AdaptiveInferenceRollout.OptIn, AdaptiveInferenceRollout.parse(" OPT_IN "))
    }

    @Test
    fun onlyOptInAndVisibleAllowAutoSelection() {
        assertFalse(AdaptiveInferenceRollout.Off.autoSelectable)
        assertFalse(AdaptiveInferenceRollout.Shadow.autoSelectable)
        assertTrue(AdaptiveInferenceRollout.OptIn.autoSelectable)
        assertTrue(AdaptiveInferenceRollout.Visible.autoSelectable)
    }

    @Test
    fun onlyShadowEvaluatesShadowPlacement() {
        AdaptiveInferenceRollout.entries.forEach { rollout ->
            assertEquals(
                rollout == AdaptiveInferenceRollout.Shadow,
                rollout.evaluatesShadowPlacement,
            )
        }
    }

    @Test
    fun offAndShadowDowngradeAutoWhileManualPreferencesStayUnchanged() {
        listOf(AdaptiveInferenceRollout.Off, AdaptiveInferenceRollout.Shadow).forEach { rollout ->
            assertEquals(InferenceMode.Local, rollout.sanitizePreference(InferenceMode.Auto))
            assertEquals(InferenceMode.Local, rollout.sanitizePreference(InferenceMode.Local))
            assertEquals(InferenceMode.Remote, rollout.sanitizePreference(InferenceMode.Remote))
        }
        listOf(AdaptiveInferenceRollout.OptIn, AdaptiveInferenceRollout.Visible).forEach { rollout ->
            assertEquals(InferenceMode.Auto, rollout.sanitizePreference(InferenceMode.Auto))
        }
    }
}
