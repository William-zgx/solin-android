package com.bytedance.zgx.solin.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dangerous-action keyword list is shared by the device-control screen guard and the
 * ui_tap confirmation-bypass check; it is the union of both former lists.
 */
class UiDangerousActionKeywordsTest {
    @Test
    fun sharedKeywordsIncludeFormerRegistryOnlyEntries() {
        listOf("提交", "确认", "Send", "DELETE", "Pay now", "Submit", "Post", "Publish", "Buy", "Order", "Confirm", "Transfer")
            .forEach { label -> assertTrue(label, label.hasDangerousActionText()) }
    }

    @Test
    fun sharedKeywordsIncludeFormerDeviceOnlyEntries() {
        listOf("允许", "授权", "同意", "提交订单", "立即购买")
            .forEach { label -> assertTrue(label, label.hasDangerousActionText()) }
    }

    @Test
    fun benignLabelsAreNotDangerous() {
        listOf("搜索", "返回", "设置", "Search", "Back", null, "")
            .forEach { label -> assertFalse("$label", label.hasDangerousActionText()) }
    }
}
