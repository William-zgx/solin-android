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
}
