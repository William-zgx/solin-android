package com.bytedance.zgx.solin.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            CriticalTapGuard.checkContainerClick(bar, listOf(search, send), walkComplete = true),
        )
        assertEquals(
            CriticalTapDecision.Allow,
            CriticalTapGuard.checkContainerClick(ScreenBounds(0, 0, 600, 200), listOf(search, send), walkComplete = true),
        )
    }

    @Test
    fun refusesAContainerClickWithoutBounds() {
        assertRefused(CriticalTapRefusalReason.MissingBounds, CriticalTapGuard.checkContainerClick(null, listOf(search), walkComplete = true))
    }

    @Test
    fun refusesAnUnlabelledControlOnAPaymentPasswordSheet() {
        val title = node("请输入支付密码", ScreenBounds(0, 500, 1000, 560), clickable = false)
        val keypad = node("", ScreenBounds(0, 700, 1000, 1000))
        val cancel = node("取消", ScreenBounds(0, 400, 200, 460))
        val sheet = listOf(title, keypad, cancel)
        assertRefused(CriticalTapRefusalReason.UnlabelledControlOnPaymentScreen, checkTap(x = 500, y = 850, windowNodes = sheet))
        assertRefused(
            "nothing actionable under the point cannot be attributed",
            CriticalTapRefusalReason.UnlabelledControlOnPaymentScreen,
            checkTap(x = 500, y = 650, windowNodes = sheet),
        )
        assertEquals(CriticalTapDecision.Allow, checkTap(x = 100, y = 430, windowNodes = sheet))
    }

    @Test
    fun refusesAnUnlabelledControlOnTransferAndAuthorizationScreens() {
        val icon = node("", ScreenBounds(800, 0, 1000, 100))
        listOf("转账金额", "确认转账", "授权登录", "同意授权", "确认付款", "Confirm payment").forEach { context ->
            val screen = listOf(node(context, ScreenBounds(0, 300, 1000, 400), clickable = false), icon)
            assertRefused(context, CriticalTapRefusalReason.UnlabelledControlOnPaymentScreen, checkTap(900, 50, windowNodes = screen))
        }
    }

    @Test
    fun paymentScreenStillAllowsLabelledRowsAndEditableFields() {
        val title = node("确认付款", ScreenBounds(0, 0, 1000, 100), clickable = false)
        val row = node("", ScreenBounds(0, 200, 1000, 300))
        val rowText = node("使用余额", ScreenBounds(0, 200, 600, 300), clickable = false)
        val field = node("", ScreenBounds(0, 400, 1000, 500)).copy(editable = true)
        val screen = listOf(title, row, rowText, field)
        assertEquals(CriticalTapDecision.Allow, checkTap(x = 300, y = 250, windowNodes = screen))
        assertEquals(CriticalTapDecision.Allow, checkTap(x = 500, y = 450, windowNodes = screen))
        assertRefused(
            "the row's label does not cover an icon at its far end",
            CriticalTapRefusalReason.UnlabelledControlOnPaymentScreen,
            checkTap(x = 900, y = 250, windowNodes = screen),
        )
    }

    @Test
    fun unlabelledIconsStayTappableOnShoppingSearchResults() {
        val screen = listOf(
            node("iPhone 16 Pro 256G", ScreenBounds(0, 200, 800, 260), clickable = false),
            node("¥7999 1000+人付款", ScreenBounds(0, 260, 800, 320), clickable = false),
            node("支付宝", ScreenBounds(0, 0, 200, 100), clickable = false),
            node("立即购买", ScreenBounds(600, 900, 1000, 1000)),
            node("", ScreenBounds(850, 200, 1000, 320)),
        )
        assertEquals(CriticalTapDecision.Allow, checkTap(x = 920, y = 260, windowNodes = screen))
    }

    @Test
    fun unlabelledIconsStayTappableOnAChatList() {
        val screen = listOf(
            node("微信", ScreenBounds(0, 0, 1000, 100), clickable = false),
            node("", ScreenBounds(900, 0, 1000, 100)),
            node("张三", ScreenBounds(150, 200, 800, 250), clickable = false),
            node("[转账]请收款", ScreenBounds(150, 250, 800, 300), clickable = false),
            node("我已经确认付款了，今晚能发货吗？麻烦帮我看一下物流信息", ScreenBounds(150, 350, 800, 400), clickable = false),
        )
        assertEquals(CriticalTapDecision.Allow, checkTap(x = 950, y = 50, windowNodes = screen))
    }

    @Test
    fun unlabelledIconsStayTappableOnASettingsPage() {
        val screen = listOf(
            node("", ScreenBounds(0, 0, 100, 100)),
            node("设置", ScreenBounds(100, 0, 900, 100), clickable = false),
            node("通用", ScreenBounds(0, 200, 1000, 300)),
            node("隐私", ScreenBounds(0, 300, 1000, 400)),
            node("", ScreenBounds(900, 300, 1000, 400)),
        )
        assertEquals(CriticalTapDecision.Allow, checkTap(x = 50, y = 50, windowNodes = screen))
        assertEquals(CriticalTapDecision.Allow, checkTap(x = 950, y = 350, windowNodes = screen))
    }

    // ── Review fixes for 8f2c33e ──────────────────────────────────────────────────────────────────

    @Test
    fun longCriticalLabelsUnderThePointAreStillRefused() {
        listOf("Buy now with 1-Click", "确认支付 ¥1,299.00（含运费 ¥10.00）").forEach { label ->
            val button = node(label, ScreenBounds(0, 900, 1000, 1000))
            assertRefused(label, CriticalTapRefusalReason.DangerousControlUnderPoint, checkTap(500, 950, windowNodes = listOf(button)))
            val tapped = listOf(TappedNode(button, viewIdResourceName = null))
            assertRefused(label, CriticalTapRefusalReason.CriticalTappedNode, checkTap(500, 950, tapped, listOf(button)))
        }
    }

    @Test
    fun chatRowPreviewTextDoesNotMakeTheRowCritical() {
        val row = node("", ScreenBounds(0, 200, 1000, 320))
        listOf("好的，我同意", "已确认", "[转账]", "[转账]¥100.00", "【红包】恭喜发财", "明天去下单吧").forEach { preview ->
            val screen = listOf(
                row,
                node("文件传输助手", ScreenBounds(150, 210, 700, 260), clickable = false),
                node(preview, ScreenBounds(150, 260, 700, 310), clickable = false),
            )
            assertEquals(preview, CriticalTapDecision.Allow, checkTap(x = 900, y = 260, windowNodes = screen))
            assertEquals(preview, CriticalTapDecision.Allow, CriticalTapGuard.checkContainerClick(row.bounds, screen, walkComplete = true))
        }
        // Known strictness: a short preview with a strong phrase (发送, 发布, …) still refuses the row.
        val strongPreview = listOf(row, node("我昨天发送的文件", ScreenBounds(150, 260, 700, 310), clickable = false))
        assertRefused(CriticalTapRefusalReason.DangerousControlUnderPoint, checkTap(x = 900, y = 260, windowNodes = strongPreview))
    }

    @Test
    fun containerHoldingACriticalControlOrCaptionIsStillRefused() {
        val bar = node("", ScreenBounds(0, 850, 1000, 1000))
        val clickablePay = node("确认支付", ScreenBounds(600, 900, 1000, 1000))
        assertRefused(
            CriticalTapRefusalReason.ContainerHoldsCriticalControl,
            CriticalTapGuard.checkContainerClick(bar.bounds, listOf(bar, clickablePay), walkComplete = true),
        )
        listOf("立即支付", "支付 ¥99", "去支付", "发送", "Pay now", "Place order", "提交", "【立即支付】").forEach { caption ->
            val text = node(caption, ScreenBounds(100, 900, 900, 1000), clickable = false)
            assertRefused(caption, CriticalTapRefusalReason.DangerousControlUnderPoint, checkTap(500, 950, windowNodes = listOf(bar, text)))
        }
    }

    @Test
    fun viewIdsAreMatchedByWholeToken() {
        listOf(
            "com.tencent.mm:id/btn_send", "com.x:id/payButton", "com.x:id/pay_button", "com.x:id/confirm-order",
            "com.x:id/sendBtn", "com.x:id/btnsend", "com.x:id/order_item_btn", "com.x:id/delete_item", "com.x:id/submit",
        ).forEach { id -> assertTrue(id, id.isCriticalViewId()) }
        listOf(
            "com.x:id/sender_name", "com.x:id/post_card", "com.x:id/order_item", "com.x:id/display_name",
            "com.x:id/search_entry", "com.x:id/payee_avatar", "com.x:id/sendee", null, "",
        ).forEach { id -> assertFalse("$id", id.isCriticalViewId()) }
    }

    @Test
    fun rowViewIdsAndEditableFieldsDoNotRefuseTheTappedNode() {
        val row = node("", ScreenBounds(0, 0, 1000, 100))
        listOf("com.x:id/sender_name", "com.x:id/post_card", "com.x:id/order_item").forEach { id ->
            assertEquals(id, CriticalTapDecision.Allow, checkTap(500, 50, listOf(TappedNode(row, id)), listOf(search)))
        }
        val field = row.copy(editable = true)
        assertEquals(
            CriticalTapDecision.Allow,
            checkTap(500, 50, listOf(TappedNode(field, "com.x:id/send_message_input")), listOf(search)),
        )
    }

    @Test
    fun swipeThatStaysOnOneControlIsCheckedAsATap() {
        val payBar = node("立即支付", ScreenBounds(0, 800, 1000, 1000))
        val nodes = listOf(search, payBar)
        assertRefused(
            CriticalTapRefusalReason.DangerousControlUnderPoint,
            CriticalTapGuard.checkSwipe(100, 900, 300, 900, nodes, walkComplete = true),
        )
        assertRefused(
            "a degenerate swipe is a tap",
            CriticalTapRefusalReason.DangerousControlUnderPoint,
            CriticalTapGuard.checkSwipe(100, 900, 110, 905, nodes, walkComplete = true),
        )
        assertEquals(
            "a swipe that leaves the control is a scroll",
            CriticalTapDecision.Allow,
            CriticalTapGuard.checkSwipe(100, 900, 100, 50, nodes, walkComplete = true),
        )
        assertEquals(
            "staying on a safe control is fine",
            CriticalTapDecision.Allow,
            CriticalTapGuard.checkSwipe(50, 50, 400, 50, nodes, walkComplete = true),
        )
    }

    @Test
    fun containerClickRefusesAnUnreadableWindowOrATruncatedWalk() {
        val bounds = ScreenBounds(0, 0, 600, 200)
        assertRefused(
            CriticalTapRefusalReason.UnreadableWindow,
            CriticalTapGuard.checkContainerClick(bounds, emptyList(), walkComplete = true),
        )
        assertRefused(
            CriticalTapRefusalReason.UnverifiedTruncatedWalk,
            CriticalTapGuard.checkContainerClick(bounds, listOf(search), walkComplete = false),
        )
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

    private fun assertRefused(message: String, reason: CriticalTapRefusalReason, decision: CriticalTapDecision) =
        assertEquals(message, CriticalTapDecision.Refuse(reason), decision)

    private fun node(text: String, bounds: ScreenBounds, clickable: Boolean = true) = ScreenNode(
        id = "",
        text = text,
        contentDescription = "",
        className = "android.widget.Button",
        bounds = bounds,
        clickable = clickable,
        editable = false,
        scrollable = false,
        enabled = true,
    )
}
