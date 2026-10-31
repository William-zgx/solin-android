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

    @Test
    fun pointTapLooksInsideAnUnlabelledClickableContainer() {
        val container = node("bar", "", left = 0, right = 1000).copy(bounds = ScreenBounds(0, 900, 1000, 1000))
        val payText = node("pay", "立即支付", left = 600, right = 900)
            .copy(clickable = false, bounds = ScreenBounds(600, 920, 900, 980))
        val nodes = listOf(container, payText)
        assertTrue(nodes.tapAtPointHitsDangerousControl(x = 100, y = 950))
        // A product card whose sales line mentions 付款 is not a pay button.
        val card = container.copy(bounds = ScreenBounds(0, 0, 1000, 400))
        val sales = payText.copy(text = "1000+人付款", bounds = ScreenBounds(10, 300, 300, 350))
        assertFalse(listOf(card, sales).tapAtPointHitsDangerousControl(x = 500, y = 200))
    }

    @Test
    fun pointTapWithNothingActionableUnderItFallsBackToTheScreenWideCheck() {
        val send = node("send", "发送", left = 800, right = 1000).copy(bounds = ScreenBounds(800, 900, 1000, 1000))
        assertTrue(listOf(send).tapAtPointHitsDangerousControl(x = 100, y = 100))
        val search = send.copy(text = "搜索")
        assertFalse(listOf(search).tapAtPointHitsDangerousControl(x = 100, y = 100))
    }

    @Test
    fun pointTapOnAnEditableFieldIsSafeNextToASendButton() {
        val input = node("input", "", left = 0, right = 800).copy(editable = true, clickable = true)
        val send = node("send", "发送", left = 800, right = 1000)
        assertFalse(listOf(input, send).tapAtPointHitsDangerousControl(x = 400, y = 500))
        assertTrue(listOf(input, send).tapAtPointHitsDangerousControl(x = 900, y = 500))
    }

    @Test
    fun anyCriticalControlUnderThePointRefusesEvenWithASmallerNodeBehindIt() {
        val sheetButton = node("pay", "确认支付", left = 0, right = 1000).copy(bounds = ScreenBounds(0, 900, 1000, 1000))
        val iconBehind = node("fav", "收藏", left = 100, right = 200).copy(bounds = ScreenBounds(100, 920, 200, 980))
        assertTrue(listOf(sheetButton, iconBehind).tapAtPointHitsDangerousControl(x = 150, y = 950))
        val fieldBehind = iconBehind.copy(text = "", editable = true)
        assertTrue(listOf(sheetButton, fieldBehind).tapAtPointHitsDangerousControl(x = 150, y = 950))
    }

    @Test
    fun contentDescriptionCountsWhenTextIsHarmless() {
        val button = node("buy", "¥99", left = 0, right = 1000).copy(contentDescription = "立即支付")
        assertTrue(listOf(button).tapAtPointHitsDangerousControl(x = 500, y = 500))
        assertTrue(snapshot(button).tapTargetHitsDangerousControl("¥99"))
    }

    @Test
    fun anyButtonLikeLabelInsideAnUnlabelledContainerIsCritical() {
        val bar = node("bar", "", left = 0, right = 1000).copy(bounds = ScreenBounds(0, 900, 1000, 1000))
        listOf("去支付", "马上下单", "Pay now").forEach { text ->
            val inner = node("inner", text, left = 600, right = 900)
                .copy(clickable = false, bounds = ScreenBounds(600, 920, 900, 980))
            assertTrue(text, listOf(bar, inner).tapAtPointHitsDangerousControl(x = 100, y = 950))
        }
    }

    @Test
    fun everydayPhrasesAndWordsThatMerelyContainAMarkerAreNotCritical() {
        listOf(
            "发布于 2026-10-30", "最新发布", "发布时间", "苹果发布会", "1000+人付款", "支付宝",
            "Voice Recorder", "Reposts", "display", "border",
        ).forEach { label ->
            assertFalse(label, label.hasDangerousTapTargetText())
            assertFalse(label, label.hasStrongDangerousActionText())
        }
        listOf("Payment", "sendButton", "btn_send", "Post", "使用支付宝支付", "发布").forEach { label ->
            assertTrue(label, label.hasDangerousTapTargetText())
        }
    }

    @Test
    fun longContentIsNotAButtonButTheNamedTargetStaysStrict() {
        val row = node("row", "张三：我刚刚发送了一张图片，你看一下这个", left = 0, right = 1000)
        assertFalse(listOf(row).tapAtPointHitsDangerousControl(x = 500, y = 500))
    }

    @Test
    fun confirmOnAResultsPageWithSalesCountsIsNotCritical() {
        val sales = node("sales", "1000+人付款", left = 0, right = 400)
            .copy(clickable = false, bounds = ScreenBounds(0, 0, 400, 100))
        val apply = node("ok", "确定", left = 500, right = 1000).copy(bounds = ScreenBounds(500, 800, 1000, 900))
        assertFalse(listOf(sales, apply).tapAtPointHitsDangerousControl(x = 700, y = 850))
    }

    @Test
    fun editableLabelsDoNotTriggerTheScreenWideCheck() {
        val comment = node("comment", "发表评论", left = 0, right = 1000).copy(editable = true)
        assertFalse(snapshot(comment).hasDangerousActionControl())
    }

    @Test
    fun truncatedWalkTrustsOnlyALabelledNodeUnderThePoint() {
        val labelled = node("tab", "销量", left = 0, right = 500)
        val blank = node("bar", "", left = 500, right = 1000)
        assertTrue(listOf(labelled, blank).hasLabelledActionableNodeAt(x = 100, y = 500))
        assertFalse(listOf(labelled, blank).hasLabelledActionableNodeAt(x = 800, y = 500))
    }

    @Test
    fun bareConfirmIsCriticalOnlyUnderACriticalDialog() {
        val title = node("title", "确定删除聊天记录？", left = 0, right = 1000)
            .copy(clickable = false, bounds = ScreenBounds(0, 0, 1000, 100))
        val ok = node("ok", "确定", left = 500, right = 1000).copy(bounds = ScreenBounds(500, 800, 1000, 900))
        assertTrue(snapshot(title, ok).tapTargetHitsDangerousControl("确定"))
        assertTrue(listOf(title, ok).tapAtPointHitsDangerousControl(x = 700, y = 850))
        val filterTitle = title.copy(text = "筛选条件")
        assertFalse(snapshot(filterTitle, ok).tapTargetHitsDangerousControl("好的"))
        assertFalse(listOf(filterTitle, ok.copy(text = "好的")).tapAtPointHitsDangerousControl(x = 700, y = 850))
        assertTrue("发表".hasDangerousTapTargetText())
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
