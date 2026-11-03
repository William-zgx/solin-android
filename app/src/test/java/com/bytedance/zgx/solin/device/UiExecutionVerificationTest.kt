package com.bytedance.zgx.solin.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UiExecutionVerificationTest {
    private class Clock {
        var time = 0L
        fun now() = time
        fun pause(millis: Long) { time += millis }
    }

    @Test fun exactTextPreservesSpacesAndNewlines() {
        val clock = Clock()
        val expected = "meet at 9\nroom  2"
        assertEquals(UiInputVerification.Mismatch, verifyWrittenText(
            expected, 100, clock::now, clock::pause, { "meetat9room2" },
        ))
        assertEquals(UiInputVerification.Match, verifyWrittenText(
            expected, 100, clock::now, clock::pause, { expected },
        ))
    }

    @Test fun delayedTextCanBeObservedWithoutASecondWrite() {
        val clock = Clock()
        var reads = 0
        assertEquals(UiInputVerification.Match, verifyWrittenText(
            "query", 200, clock::now, clock::pause,
            { reads++; if (reads >= 3) "query" else "old" },
        ))
        assertEquals(3, reads)
        assertEquals(100L, clock.time)
    }

    @Test fun staleNodeIsUnknownRatherThanMismatchOrEarlierEvidence() {
        val clock = Clock()
        var reads = 0
        assertEquals(UiInputVerification.Unknown, verifyWrittenText(
            "query", 200, clock::now, clock::pause,
            { if (++reads == 1) "old" else null },
        ))
        assertEquals(2, reads)
    }

    @Test fun lateMatchingReadIsUnknown() {
        val clock = Clock()
        assertEquals(UiInputVerification.Unknown, verifyWrittenText(
            "query", 100, clock::now, clock::pause,
            { clock.time += 101; "query" },
        ))
    }

    @Test fun cancellationBeforeTextReadDoesNotReadTheField() {
        var read = false
        try {
            verifyWrittenText("query", 100, { 0 }, {}, { read = true; "query" },
                requireActive = { throw InterruptedException("cancelled") })
            throw AssertionError("cancellation must propagate")
        } catch (_: InterruptedException) {
            assertFalse(read)
        }
    }

    @Test fun stableEvidenceRequiresContinuousQuietInterval() {
        val clock = Clock()
        assertEquals(UiScreenStability.Stable, awaitStableUiEvidence(
            1_000, clock::now, clock::pause, { "page" }, { clock.time },
        ))
        assertEquals(400L, clock.time)
    }

    @Test fun delayedPageChangeRestartsStabilityWindow() {
        val clock = Clock()
        assertEquals(UiScreenStability.Stable, awaitStableUiEvidence(
            1_000, clock::now, clock::pause,
            { if (clock.time < 300) "old" else "new" },
            { if (clock.time < 300) clock.time else clock.time - 300 },
        ))
        assertEquals(700L, clock.time)
    }

    @Test fun missingFrameOrTreeIsNeverStable() {
        val clock = Clock()
        assertEquals(UiScreenStability.Unknown, awaitStableUiEvidence<String>(
            500, clock::now, clock::pause, { null }, { 10_000 },
        ))
        assertEquals(500L, clock.time)
    }

    @Test fun missingReadResetsEarlierStabilityEvidence() {
        val clock = Clock()
        assertEquals(UiScreenStability.TimedOut, awaitStableUiEvidence(
            600, clock::now, clock::pause,
            { if (clock.time == 300L) null else "page" }, { 10_000 },
        ))
    }

    @Test fun continuousEventsAndAnimationRemainBounded() {
        val clock = Clock()
        assertEquals(UiScreenStability.TimedOut, awaitStableUiEvidence(
            500, clock::now, clock::pause, { clock.time.toString() }, { 0 },
        ))
        assertEquals(500L, clock.time)
    }

    @Test fun lateStableSampleCannotEstablishStability() {
        val clock = Clock()
        assertEquals(UiScreenStability.TimedOut, awaitStableUiEvidence(
            500, clock::now, clock::pause,
            { clock.time += 300; "page" }, { 10_000 },
        ))
    }

    @Test fun cancellationDuringStabilityPollingPropagates() {
        val clock = Clock()
        var reads = 0
        try {
            awaitStableUiEvidence(1_000, clock::now, clock::pause,
                { reads++; "page" }, { 10_000 },
                requireActive = { if (clock.time >= 100) throw InterruptedException("cancelled") })
            throw AssertionError("cancellation must propagate")
        } catch (_: InterruptedException) {
            assertEquals(1, reads)
        }
    }

    private fun snapshot() = ScreenStateSnapshot(
        id = "one", packageName = "app", capturedAtMillis = 1,
        nodes = listOf(ScreenNode("node1", "text", "", "TextView", ScreenBounds(0, 0, 10, 10),
            clickable = false, editable = false, scrollable = false, enabled = true)),
        textSummary = "text", truncated = false,
    )

    @Test fun snapshotIdsAndTimestampsDoNotPreventStability() {
        val first = snapshot()
        assertEquals(first.stabilityEvidence(), first.copy(id = "two", capturedAtMillis = 2,
            nodes = first.nodes.map { it.copy(id = "node2") }).stabilityEvidence())
    }

    @Test fun incompleteOrEmptyTreeIsUnknown() {
        assertNull(snapshot().copy(truncated = true).stabilityEvidence())
        assertNull(snapshot().copy(nodes = emptyList()).stabilityEvidence())
        assertNull(snapshot().copy(packageName = null).stabilityEvidence())
    }
}
