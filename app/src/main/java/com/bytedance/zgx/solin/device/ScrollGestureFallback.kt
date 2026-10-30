package com.bytedance.zgx.solin.device

/**
 * Pure geometry for the `ui_scroll` gesture fallback.
 *
 * `ACTION_SCROLL_FORWARD/BACKWARD` is refused (or no node is marked scrollable at all) by most WebView,
 * Flutter and React Native surfaces even though they scroll fine under a finger. When that happens the
 * accessibility service replays the scroll as ONE straight swipe computed here. Keeping the arithmetic
 * pure means the "never start a stroke in a system-gesture zone" property is JVM-testable.
 *
 * All values are absolute screen pixels, matching `getBoundsInScreen` and `dispatchGesture`.
 */
internal data class ScrollSwipePath(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMillis: Long,
)

/**
 * Fractions of the screen kept clear of system gestures: the status bar / notification shade at the top,
 * the home / recents gesture bar at the bottom, and the edge back-gesture strips on both sides. A stroke
 * that starts inside one of these triggers the system gesture instead of scrolling the app.
 */
internal const val SCROLL_FALLBACK_SAFE_TOP_FRACTION = 0.12f
internal const val SCROLL_FALLBACK_SAFE_BOTTOM_FRACTION = 0.85f
internal const val SCROLL_FALLBACK_SAFE_SIDE_FRACTION = 0.12f

/** The stroke covers the middle half of the usable span: from 75% to 25% (or back) along the axis. */
private const val SCROLL_FALLBACK_NEAR_FRACTION = 0.25f
private const val SCROLL_FALLBACK_FAR_FRACTION = 0.75f

/** Shorter than this the platform treats the stroke as a tap/long-press rather than a scroll. */
internal const val MIN_SCROLL_FALLBACK_DISTANCE_PX = 64

/** Deliberate rather than flinging: roughly one viewport-half per fallback, like a scroll action. */
internal const val SCROLL_FALLBACK_SWIPE_DURATION_MILLIS = 350L

/**
 * The swipe that scrolls [direction] inside [containerBounds], clipped to the system-gesture-safe area of
 * a [screenWidthPx] x [screenHeightPx] screen.
 *
 * Direction follows the scroll-action convention used by `ui_scroll`: `down`/`forward` reveals content
 * further down, so the finger moves UP; `right` reveals content to the right, so the finger moves LEFT.
 * `forward`/`backward` are vertical unless the known container is clearly a horizontal strip
 * (width >= 2 x height), mirroring how a horizontal RecyclerView maps those actions.
 *
 * Returns null — the caller must fail rather than guess — when the screen size is unusable, or when a
 * KNOWN container leaves less than [MIN_SCROLL_FALLBACK_DISTANCE_PX] of travel inside the safe area
 * (swiping outside the container would scroll something else). An unknown or degenerate container falls
 * back to the whole safe area.
 */
internal fun scrollFallbackSwipePath(
    direction: UiScrollDirection,
    containerBounds: ScreenBounds?,
    screenWidthPx: Int,
    screenHeightPx: Int,
): ScrollSwipePath? {
    if (screenWidthPx <= 0 || screenHeightPx <= 0) return null
    // Symmetric side margins, so the stroke runs on the true centreline of the screen.
    val sideMargin = (screenWidthPx * SCROLL_FALLBACK_SAFE_SIDE_FRACTION).toInt()
    val safeArea = ScreenBounds(
        left = sideMargin,
        top = (screenHeightPx * SCROLL_FALLBACK_SAFE_TOP_FRACTION).toInt(),
        right = screenWidthPx - sideMargin,
        bottom = (screenHeightPx * SCROLL_FALLBACK_SAFE_BOTTOM_FRACTION).toInt(),
    )
    val knownContainer = containerBounds?.takeIf { bounds -> bounds.width() > 0 && bounds.height() > 0 }
    val area = if (knownContainer == null) {
        safeArea
    } else {
        knownContainer.intersectWith(safeArea) ?: return null
    }
    val horizontal = when (direction) {
        UiScrollDirection.Left, UiScrollDirection.Right -> true
        UiScrollDirection.Up, UiScrollDirection.Down -> false
        UiScrollDirection.Forward, UiScrollDirection.Backward ->
            knownContainer != null && knownContainer.width() >= knownContainer.height() * 2
    }
    // True when the finger travels toward larger coordinates (down / right).
    val fingerTowardEnd = when (direction) {
        UiScrollDirection.Up, UiScrollDirection.Left -> true
        UiScrollDirection.Down, UiScrollDirection.Right -> false
        UiScrollDirection.Forward -> false
        UiScrollDirection.Backward -> true
    }
    val spanStart = if (horizontal) area.left else area.top
    val span = if (horizontal) area.width() else area.height()
    val near = spanStart + (span * SCROLL_FALLBACK_NEAR_FRACTION).toInt()
    val far = spanStart + (span * SCROLL_FALLBACK_FAR_FRACTION).toInt()
    if (far - near < MIN_SCROLL_FALLBACK_DISTANCE_PX) return null
    val (from, to) = if (fingerTowardEnd) near to far else far to near
    val crossCenter = if (horizontal) (area.top + area.bottom) / 2 else (area.left + area.right) / 2
    return if (horizontal) {
        ScrollSwipePath(from, crossCenter, to, crossCenter, SCROLL_FALLBACK_SWIPE_DURATION_MILLIS)
    } else {
        ScrollSwipePath(crossCenter, from, crossCenter, to, SCROLL_FALLBACK_SWIPE_DURATION_MILLIS)
    }
}

private fun ScreenBounds.intersectWith(other: ScreenBounds): ScreenBounds? {
    val left = maxOf(left, other.left)
    val top = maxOf(top, other.top)
    val right = minOf(right, other.right)
    val bottom = minOf(bottom, other.bottom)
    return if (right > left && bottom > top) ScreenBounds(left, top, right, bottom) else null
}
