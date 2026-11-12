package com.bytedance.zgx.solin.device

import com.bytedance.zgx.solin.multimodal.RawScreenshotReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic regressions for the review-findings batch on [SolinAccessibilityService].
 *
 * Everything asserted here is deliberately Android-free so it runs as a JVM unit test: the node-id
 * counting basis, the UI-action timeout arithmetic, and the selection-aware backspace. The node-walking
 * and gesture paths themselves need a device (see docs/phone_acceptance.md) and are not covered here.
 */
class SolinAccessibilityServiceBehaviorTest {

    /** No connected service means raw capture fails closed without throwing or returning pixels. */
    @Test
    fun rawScreenshotFailsClosedWhenAccessibilityServiceNotConnected() {
        val result = SolinAccessibilityService.performTakeScreenshotRaw("obs-1")

        assertTrue(result is RawScreenshotReadResult.Failed)
    }

    @Test
    fun takeScreenshotErrorMapsToFailedWithCode() {
        val result = takeScreenshotErrorToResult(errorCode = 3)

        assertTrue(result is RawScreenshotReadResult.Failed)
        assertTrue(result.reason.contains("3"))
    }

    // --- node id contract (C2): observe side and click side must agree ---

    @Test
    fun observedNodeIdIsMatchedAfterSaltIsStripped() {
        // Observe publishes "<base>_<snapshotSalt>"; the click side rebuilds only "<base>". The lookup
        // must still bind the two, otherwise every model-supplied id silently misses.
        val clickSideId = "n3_abcd1234"
        val observedId = "${clickSideId}_f00dbeef"

        assertEquals(950, transientNodeIdTargetMatchScore(clickSideId, observedId))
    }

    @Test
    fun nodeIdDoesNotMatchDifferentIndexWithSamePrefixDigits() {
        // "n1" must not absorb "n10": a prefix compare without the underscore boundary would.
        assertNull(transientNodeIdTargetMatchScore("n1_abcd1234", "n10_abcd1234_f00dbeef"))
    }

    @Test
    fun nodeIdDoesNotMatchSameIndexWithDifferentFingerprint() {
        // Same ordinal but a different node: the fingerprint half is what makes the id verifiable.
        assertNull(transientNodeIdTargetMatchScore("n3_abcd1234", "n3_99999999_f00dbeef"))
    }

    // --- ui_wait / ui_* hard timeout must dominate the work it wraps ---

    @Test
    fun hardTimeoutExceedsSchemaMaximumRequestedTimeout() {
        // The regression: ui_wait accepts timeoutMillis up to 10000, but the old (this + 4000)
        // .coerceAtMost(10000) formula returned exactly 10000 — the watchdog fired while the
        // pre-action wait was still sleeping, so a valid request always reported a spurious Timeout.
        val requested = 10_000L

        val hardTimeout = uiActionHardTimeoutMillis(requested)

        assertTrue(
            "hard timeout $hardTimeout must exceed the requested wait $requested",
            hardTimeout > requested
        )
    }

    @Test
    fun hardTimeoutLeavesRoomForBothObservesAndThePostActionWait() {
        // executeUiAction spends: preActionWait (up to requested) + 2 full-tree observes + post-action
        // wait. The watchdog has to clear all of it or long timeouts degrade into false failures.
        val requested = 6_000L
        val observeBudget = 3_000L
        val minimumNeeded = requested + (2 * observeBudget) + postActionWaitMillis(requested)

        assertTrue(
            "hard timeout ${uiActionHardTimeoutMillis(requested)} must cover $minimumNeeded",
            uiActionHardTimeoutMillis(requested) >= minimumNeeded
        )
    }

    @Test
    fun hardTimeoutGrowsMonotonicallyWithRequestedTimeout() {
        val small = uiActionHardTimeoutMillis(500L)
        val medium = uiActionHardTimeoutMillis(3_000L)
        val large = uiActionHardTimeoutMillis(10_000L)

        assertTrue(small < medium)
        assertTrue(medium < large)
    }

    @Test
    fun hardTimeoutClampsOutOfRangeRequestsIntoTheSchemaWindow() {
        // Out-of-schema input must not produce a shorter budget than the smallest legal request.
        assertEquals(uiActionHardTimeoutMillis(100L), uiActionHardTimeoutMillis(-5_000L))
        assertEquals(uiActionHardTimeoutMillis(10_000L), uiActionHardTimeoutMillis(60_000L))
    }

    // --- post-action wait honours the model's timeout instead of always truncating to 250ms ---

    @Test
    fun postActionWaitHonoursALargerRequestedTimeout() {
        // Previously coerceAtMost(250) discarded the request, so the `after` snapshot was read before
        // the screen had settled.
        assertTrue(postActionWaitMillis(3_000L) > 250L)
    }

    @Test
    fun postActionWaitStaysWithinTheReservedOverheadBudget() {
        val atSchemaMax = postActionWaitMillis(10_000L)

        assertTrue("post-action wait $atSchemaMax must stay bounded", atSchemaMax <= 1_500L)
        assertEquals(atSchemaMax, postActionWaitMillis(60_000L))
    }

    @Test
    fun postActionWaitKeepsAUsableFloorForTinyTimeouts() {
        assertEquals(250L, postActionWaitMillis(100L))
        assertEquals(250L, postActionWaitMillis(0L))
    }

    // --- selection-aware backspace ---

    @Test
    fun backspaceDeletesTheCharacterBeforeAMidStringCaret() {
        // "abcd" with the caret after 'b' must lose 'b', not 'd' — the old dropLast(1) lost 'd'.
        val edit = backspaceEdit("abcd", selectionStart = 2, selectionEnd = 2)

        assertEquals("acd", edit.text)
        assertEquals(1, edit.selection)
    }

    @Test
    fun backspaceDeletesAnEntireNonEmptySelection() {
        val edit = backspaceEdit("abcdef", selectionStart = 1, selectionEnd = 4)

        assertEquals("aef", edit.text)
        assertEquals(1, edit.selection)
    }

    @Test
    fun backspaceHandlesAReversedSelectionRange() {
        // Selection reported end-before-start (drag right-to-left) must behave identically.
        val edit = backspaceEdit("abcdef", selectionStart = 4, selectionEnd = 1)

        assertEquals("aef", edit.text)
        assertEquals(1, edit.selection)
    }

    @Test
    fun backspaceAtStartOfTextIsANoOp() {
        val edit = backspaceEdit("abc", selectionStart = 0, selectionEnd = 0)

        assertEquals("abc", edit.text)
        assertEquals(0, edit.selection)
    }

    @Test
    fun backspaceFallsBackToDroppingTheLastCharacterWhenSelectionIsUnknown() {
        // AccessibilityNodeInfo reports -1 when it has no selection; that must not reach substring().
        val edit = backspaceEdit("abc", selectionStart = -1, selectionEnd = -1)

        assertEquals("ab", edit.text)
        assertEquals(-1, edit.selection)
        assertFalse("caret restore must be skipped when unknown", edit.selection >= 0)
    }

    @Test
    fun backspaceFallsBackWhenSelectionIsOutOfRangeForTheText() {
        // Stale nodes have been observed reporting offsets past the current text length.
        val edit = backspaceEdit("abc", selectionStart = 9, selectionEnd = 9)

        assertEquals("ab", edit.text)
        assertEquals(-1, edit.selection)
    }

    @Test
    fun backspaceDeletesTheLastCharacterWhenTheCaretIsAtTheEnd() {
        val edit = backspaceEdit("abc", selectionStart = 3, selectionEnd = 3)

        assertEquals("ab", edit.text)
        assertEquals(2, edit.selection)
    }

    // --- gesture outcome: timeout must never authorise a compensating click ---

    @Test
    fun onlyACompletedGestureCountsAsPerformed() {
        assertTrue(GestureOutcome.Completed.performed)
        assertFalse(GestureOutcome.Cancelled.performed)
        assertFalse(GestureOutcome.TimedOut.performed)
        assertFalse(GestureOutcome.NotAccepted.performed)
    }

    @Test
    fun aTimedOutGestureMustNotAuthoriseAFallbackClick() {
        // This is the double-tap guard: a late callback means the touch may already have landed, and a
        // second activation on a payment button is a duplicate irreversible action.
        assertFalse(GestureOutcome.TimedOut.allowsFallbackClick)
    }

    @Test
    fun explicitlyRejectedOrCancelledGesturesMayFallBackToAccessibilityClick() {
        // These two prove no touch was delivered, so compensating cannot double-fire.
        assertTrue(GestureOutcome.NotAccepted.allowsFallbackClick)
        assertTrue(GestureOutcome.Cancelled.allowsFallbackClick)
    }

    @Test
    fun aCompletedGestureNeverNeedsAFallbackClick() {
        assertFalse(GestureOutcome.Completed.allowsFallbackClick)
    }
}
