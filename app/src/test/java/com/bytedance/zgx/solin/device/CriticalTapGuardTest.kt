package com.bytedance.zgx.solin.device

import org.junit.Assert.assertEquals
import org.junit.Test

/** The live critical-tap decision, one test per refusal case plus the taps it must keep allowing. */
class CriticalTapGuardTest {
    private val search = node("搜索", ScreenBounds(0, 0, 500, 100))
    private val send = node("发送", ScreenBounds(800, 900, 1000, 1000))

    @Test
    fun allowsATapOnASafeLabelledControl() {
        assertEquals(CriticalTapDecision.Allow, checkTap(x = 250, y = 50, windowNodes = listOf(search, send)))
    }

    @Test
    fun refusesWhenTheTappedNodeHasACriticalLabel() {
        val tapped = listOf(TappedNode(node("立即支付", ScreenBounds(0, 0, 500, 100)), viewIdResourceName = null))
        assertRefused(CriticalTapRefusalReason.CriticalTappedNode, checkTap(250, 50, tapped, listOf(search)))
    }

    @Test
    fun refusesWhenTheClickableAncestorHasACriticalLabel() {
        val tapped = listOf(
            TappedNode(node("", ScreenBounds(0, 0, 100, 100)), viewIdResourceName = null),
            TappedNode(node("发送", ScreenBounds(0, 0, 500, 100)), viewIdResourceName = null),
        )
        assertRefused(CriticalTapRefusalReason.CriticalTappedNode, checkTap(250, 50, tapped, listOf(search)))
    }

    @Test
    fun refusesWhenTheTappedNodeHasACriticalViewId() {
        val tapped = listOf(TappedNode(node("", ScreenBounds(0, 0, 500, 100)), "com.example:id/btn_send"))
        assertRefused(CriticalTapRefusalReason.CriticalTappedNode, checkTap(250, 50, tapped, listOf(search)))
        val benign = listOf(TappedNode(node("", ScreenBounds(0, 0, 500, 100)), "com.example:id/search_entry"))
        assertEquals(CriticalTapDecision.Allow, checkTap(250, 50, benign, listOf(search)))
    }

    @Test
    fun refusesWhenNoWindowCouldBeRead() {
        assertRefused(CriticalTapRefusalReason.UnreadableWindow, checkTap(x = 250, y = 50, windowNodes = emptyList()))
    }

    @Test
    fun truncatedWalkIsTrustedOnlyWithALabelledNodeUnderThePoint() {
        val blank = node("", ScreenBounds(500, 0, 1000, 100))
        val nodes = listOf(search, blank)
        assertRefused(
            CriticalTapRefusalReason.UnverifiedTruncatedWalk,
            checkTap(x = 750, y = 50, windowNodes = nodes, walkComplete = false),
        )
        assertEquals(CriticalTapDecision.Allow, checkTap(x = 250, y = 50, windowNodes = nodes, walkComplete = false))
    }

    @Test
    fun refusesWhenThePointHitsADangerousControl() {
        assertRefused(
            CriticalTapRefusalReason.DangerousControlUnderPoint,
            checkTap(x = 900, y = 950, windowNodes = listOf(search, send)),
        )
    }

    @Test
    fun refusesAContainerClickThatHoldsACriticalControl() {
        val bar = ScreenBounds(0, 850, 1000, 1000)
        assertRefused(
            CriticalTapRefusalReason.ContainerHoldsCriticalControl,
            CriticalTapGuard.checkContainerClick(bar, listOf(search, send)),
        )
        assertEquals(
            CriticalTapDecision.Allow,
            CriticalTapGuard.checkContainerClick(ScreenBounds(0, 0, 600, 200), listOf(search, send)),
        )
    }

    @Test
    fun refusesAContainerClickWithoutBounds() {
        assertRefused(CriticalTapRefusalReason.MissingBounds, CriticalTapGuard.checkContainerClick(null, listOf(search)))
    }

    @Test
    fun refusalThrowsWithItsReason() {
        val refused = runCatching {
            CriticalTapDecision.Refuse(CriticalTapRefusalReason.UnreadableWindow).throwIfRefused()
        }.exceptionOrNull()
        assertEquals(CriticalTapRefusalReason.UnreadableWindow, (refused as CriticalControlTapRefused).reason)
        CriticalTapDecision.Allow.throwIfRefused()
    }

    private fun checkTap(
        x: Int,
        y: Int,
        tapped: List<TappedNode> = emptyList(),
        windowNodes: List<ScreenNode>,
        walkComplete: Boolean = true,
    ) = CriticalTapGuard.checkTap(x, y, tapped, windowNodes, walkComplete)

    private fun assertRefused(reason: CriticalTapRefusalReason, decision: CriticalTapDecision) =
        assertEquals(CriticalTapDecision.Refuse(reason), decision)

    private fun node(text: String, bounds: ScreenBounds) = ScreenNode(
        id = "",
        text = text,
        contentDescription = "",
        className = "android.widget.Button",
        bounds = bounds,
        clickable = true,
        editable = false,
        scrollable = false,
        enabled = true,
    )
}
