package com.bytedance.zgx.solin.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollGestureFallbackTest {
    private val width = 1080
    private val height = 2400

    private fun path(direction: UiScrollDirection, container: ScreenBounds? = null): ScrollSwipePath =
        requireNotNull(scrollFallbackSwipePath(direction, container, width, height)) { "expected a path for $direction" }

    @Test
    fun downAndForwardMoveTheFingerUpOnTheScreenCentreline() {
        listOf(UiScrollDirection.Down, UiScrollDirection.Forward).forEach { direction ->
            val swipe = path(direction)
            assertTrue("$direction: finger must move up", swipe.endY < swipe.startY)
            assertEquals(swipe.startX, swipe.endX)
            assertEquals(width / 2, swipe.startX)
        }
    }

    @Test
    fun upAndBackwardMoveTheFingerDown() {
        listOf(UiScrollDirection.Up, UiScrollDirection.Backward).forEach { direction ->
            val swipe = path(direction)
            assertTrue("$direction: finger must move down", swipe.endY > swipe.startY)
            assertEquals(swipe.startX, swipe.endX)
        }
    }

    @Test
    fun rightMovesTheFingerLeftAndLeftMovesItRight() {
        val right = path(UiScrollDirection.Right)
        val left = path(UiScrollDirection.Left)

        assertTrue(right.endX < right.startX)
        assertEquals(right.startY, right.endY)
        assertTrue(left.endX > left.startX)
        assertEquals(left.startY, left.endY)
    }

    @Test
    fun unknownContainerStaysInsideTheSystemGestureSafeArea() {
        val safeLeft = (width * SCROLL_FALLBACK_SAFE_SIDE_FRACTION).toInt()
        val safeRight = width - safeLeft
        val safeTop = (height * SCROLL_FALLBACK_SAFE_TOP_FRACTION).toInt()
        val safeBottom = (height * SCROLL_FALLBACK_SAFE_BOTTOM_FRACTION).toInt()
        UiScrollDirection.entries.forEach { direction ->
            val swipe = path(direction)
            listOf(swipe.startX, swipe.endX).forEach { x ->
                assertTrue("$direction x=$x leaves the safe area (edge back gesture)", x in safeLeft..safeRight)
            }
            listOf(swipe.startY, swipe.endY).forEach { y ->
                assertTrue("$direction y=$y leaves the safe area (status bar / home bar)", y in safeTop..safeBottom)
            }
            assertEquals(SCROLL_FALLBACK_SWIPE_DURATION_MILLIS, swipe.durationMillis)
        }
    }

    @Test
    fun fullScreenContainerIsClippedAwayFromTheHomeGestureBar() {
        // A WebView that reports the whole screen as its bounds must not get a stroke that starts in the
        // bottom gesture zone: an upward swipe from there is "go home", not "scroll down".
        val swipe = path(UiScrollDirection.Down, ScreenBounds(0, 0, width, height))

        assertTrue(swipe.startY <= (height * SCROLL_FALLBACK_SAFE_BOTTOM_FRACTION).toInt())
        assertTrue(swipe.endY >= (height * SCROLL_FALLBACK_SAFE_TOP_FRACTION).toInt())
    }

    @Test
    fun knownContainerKeepsTheStrokeInsideItsBounds() {
        val container = ScreenBounds(200, 900, 1000, 1700)
        UiScrollDirection.entries.forEach { direction ->
            val swipe = path(direction, container)
            listOf(swipe.startX, swipe.endX).forEach { x -> assertTrue("$direction x=$x", x in container.left..container.right) }
            listOf(swipe.startY, swipe.endY).forEach { y -> assertTrue("$direction y=$y", y in container.top..container.bottom) }
        }
        val down = path(UiScrollDirection.Down, container)
        // Clipped to the safe area on the right (951), so the centreline is (200 + 951) / 2.
        assertEquals(575, down.startX)
        assertEquals(1500, down.startY)
        assertEquals(1100, down.endY)
    }

    @Test
    fun forwardOnAHorizontalStripScrollsHorizontally() {
        val carousel = ScreenBounds(0, 600, 1080, 900)

        val forward = path(UiScrollDirection.Forward, carousel)
        val backward = path(UiScrollDirection.Backward, carousel)

        assertEquals(forward.startY, forward.endY)
        assertTrue("forward reveals content to the right", forward.endX < forward.startX)
        assertTrue(backward.endX > backward.startX)
    }

    @Test
    fun strokeTravelsAtLeastTheMinimumDistance() {
        UiScrollDirection.entries.forEach { direction ->
            val swipe = path(direction)
            val travel = maxOf(kotlin.math.abs(swipe.endX - swipe.startX), kotlin.math.abs(swipe.endY - swipe.startY))
            assertTrue("$direction travel=$travel", travel >= MIN_SCROLL_FALLBACK_DISTANCE_PX)
        }
    }

    @Test
    fun failsClosedWhenAKnownContainerIsTooSmallOrOutsideTheSafeArea() {
        // Too short along the axis: swiping outside it would scroll a different surface.
        assertNull(scrollFallbackSwipePath(UiScrollDirection.Down, ScreenBounds(100, 1000, 900, 1100), width, height))
        // Entirely inside the bottom gesture zone.
        assertNull(scrollFallbackSwipePath(UiScrollDirection.Down, ScreenBounds(0, 2100, width, height), width, height))
        // Entirely inside the status-bar zone.
        assertNull(scrollFallbackSwipePath(UiScrollDirection.Right, ScreenBounds(0, 0, width, 200), width, height))
    }

    @Test
    fun failsClosedOnUnusableScreenSize() {
        assertNull(scrollFallbackSwipePath(UiScrollDirection.Down, null, 0, height))
        assertNull(scrollFallbackSwipePath(UiScrollDirection.Down, null, width, -1))
    }

    @Test
    fun degenerateContainerFallsBackToTheSafeArea() {
        val fromEmpty = scrollFallbackSwipePath(UiScrollDirection.Down, ScreenBounds(10, 10, 10, 10), width, height)

        assertNotNull(fromEmpty)
        assertEquals(path(UiScrollDirection.Down), fromEmpty)
    }
}
