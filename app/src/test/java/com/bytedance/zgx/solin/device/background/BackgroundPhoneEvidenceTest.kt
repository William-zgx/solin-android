package com.bytedance.zgx.solin.device.background

import org.junit.Assert.*
import org.junit.Test

/** Pure checks: no socket, Looper, sleep or device. */
class BackgroundPhoneEvidenceTest {
    private fun state(pkg: String?, focus: Boolean = true, display: Int = 7) =
        BackgroundPhoneState(display, pkg, 320, 640, 160, focus)

    private fun code(block: () -> Unit): String =
        assertThrows(BackgroundPhoneFailure::class.java) { block() }.code

    @Test fun waitsUntilRequestedPackageIsTopmost() {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        val polls = ArrayDeque(listOf(state("android.chooser"), state("example.target")))
        val result = BackgroundPhoneSession.awaitTopmost("example.target", state("com.launcher"),
            nowNanos = { now }, sleep = { sleeps += it; now += 200_000_000L }) { polls.removeFirst() }
        assertEquals("example.target", result.foregroundPackage)
        assertEquals(2, sleeps.size)
    }

    @Test fun neverAcceptsChooserOrOtherPackageAndTimesOutClosed() {
        var now = 0L
        assertEquals("target_not_foreground", code {
            BackgroundPhoneSession.awaitTopmost("example.target", state("com.launcher"),
                nowNanos = { now }, sleep = { now += 1_000_000_000L }) { state("android.chooser") }
        })
        assertTrue(now in 1L..10_000_000_000L)
    }

    @Test fun waitFailsClosedWithoutOwnFocusOrOnDisplayChange() {
        assertEquals("independent_focus_unavailable", code {
            BackgroundPhoneSession.awaitTopmost("example.target", state("example.target", focus = false)) { error("no poll") }
        })
        assertThrows(IllegalStateException::class.java) {
            BackgroundPhoneSession.awaitTopmost("example.target", state(null), nowNanos = { 0L }, sleep = {}) { state("example.target", display = 1) }
        }
    }

    @Test fun verifyBoundKeepsValidatingExpectedTargetAndFocus() {
        val initial = state("example.target")
        BackgroundPhoneSession.verifyBound(state("example.target"), initial, "example.target")
        assertThrows(IllegalStateException::class.java) {
            BackgroundPhoneSession.verifyBound(state("other.app"), initial, "example.target")
        }
        assertEquals("independent_focus_unavailable", code {
            BackgroundPhoneSession.verifyBound(state("example.target", focus = false), initial, "example.target")
        })
    }

    @Test fun staleOrMissingFrameTimestampsAreRejected() {
        listOf(0L, -5L, 99L).forEach { ts ->
            assertEquals("stale_frame", code { BackgroundPhoneSession.requireFreshFrame(ts, 100L) })
        }
        assertEquals(100L, BackgroundPhoneSession.requireFreshFrame(100L, 100L))
        assertEquals(500L, BackgroundPhoneSession.requireFreshFrame(500L, 100L))
    }

    @Test fun focusUsesActualDisplayFlag() {
        assertTrue(BackgroundDisplayPolicy.hasOwnFocus(BackgroundDisplayPolicy.DISPLAY_FLAG_OWN_FOCUS or 1))
        assertFalse(BackgroundDisplayPolicy.hasOwnFocus(0))
    }

    @Test fun captureGateRejectsDisplayWithoutOwnFocusBeforeAnyFrame() {
        val error = assertThrows(IllegalStateException::class.java) {
            BackgroundDisplayPolicy.requireOwnFocus(BackgroundDisplayPolicy.hasOwnFocus(0))
        }
        assertEquals("independent_focus_unavailable", error.message)
        assertEquals("independent_focus_unavailable", BackgroundPhoneProtocol.safeFailure(error.message))
        BackgroundDisplayPolicy.requireOwnFocus(true)
    }
}
