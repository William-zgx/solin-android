package com.bytedance.zgx.solin.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One keyword source at two strengths: the screen-wide guard stays narrow so low-risk app search
 * keeps working on screens that merely show a generic "确认"/"Submit" button, while a single
 * ui_tap target uses the stricter superset before it may skip confirmation.
 */
class UiDangerousActionKeywordsTest {
    @Test
    fun tapTargetsCoverScreenMarkersAndGenericSubmitVerbs() {
        listOf(
            "允许", "授权", "同意", "提交订单", "立即购买",
            "提交", "确认", "Send", "DELETE", "Pay now", "Submit", "Post", "Publish", "Buy", "Order",
            "Confirm", "Transfer",
        ).forEach { label -> assertTrue(label, label.hasDangerousTapTargetText()) }
    }

    @Test
    fun everyScreenMarkerIsAlsoATapTargetMarker() {
        assertTrue(dangerousTapTargetMarkers.containsAll(dangerousActionTextMarkers))
    }

    @Test
    fun screenGuardKeepsGenericConfirmControlsUsable() {
        listOf("提交", "确认", "Submit", "Confirm", "Order by")
            .forEach { label -> assertFalse(label, label.hasDangerousActionText()) }
        listOf("允许", "授权", "同意", "提交订单", "删除", "发送")
            .forEach { label -> assertTrue(label, label.hasDangerousActionText()) }
    }

    @Test
    fun benignLabelsAreNotDangerous() {
        listOf("搜索", "返回", "设置", "Search", "Back", null, "").forEach { label ->
            assertFalse("$label", label.hasDangerousActionText())
            assertFalse("$label", label.hasDangerousTapTargetText())
        }
    }

    @Test
    fun tapTargetCheckOnlyLooksAtTheNodeTheTargetNames() {
        val screen = snapshot(
            node("input", "说点什么", left = 0, right = 800),
            node("send", "发送", left = 800, right = 1000),
        )
        assertFalse(screen.tapTargetHitsDangerousControl("input"))
        assertFalse(screen.tapTargetHitsDangerousControl("说点什么"))
        assertTrue(screen.tapTargetHitsDangerousControl("send"))
        assertTrue(screen.tapTargetHitsDangerousControl("发送"))
        assertTrue("target text alone is enough", screen.tapTargetHitsDangerousControl("确认支付"))
    }

    @Test
    fun coordinateTapChecksTheInnermostClickableNodeUnderThePoint() {
        val screen = snapshot(
            node("input", "说点什么", left = 0, right = 800),
            node("send", "发送", left = 800, right = 1000),
        )
        assertFalse(screen.normalizedPointHitsDangerousControl(xNorm = 400, yNorm = 500))
        assertTrue(screen.normalizedPointHitsDangerousControl(xNorm = 900, yNorm = 500))
        val unsized = screen.copy(widthPx = null)
        assertTrue("no screen size falls back to the screen-wide check", unsized.normalizedPointHitsDangerousControl(400, 500))
    }

    private fun node(id: String, text: String, left: Int, right: Int) = ScreenNode(
        id = id,
        text = text,
        contentDescription = "",
        className = "android.widget.Button",
        bounds = ScreenBounds(left, 0, right, 1000),
        clickable = true,
        editable = false,
        scrollable = false,
        enabled = true,
    )

    private fun snapshot(vararg nodes: ScreenNode) = ScreenStateSnapshot(
        id = "screen",
        packageName = "com.tencent.mm",
        capturedAtMillis = 1L,
        nodes = nodes.toList(),
        textSummary = "",
        truncated = false,
        widthPx = 1000,
        heightPx = 1000,
    )
}
