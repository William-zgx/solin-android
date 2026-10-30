package com.bytedance.zgx.solin.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1: bounded, accessibility-only blocking-overlay/ad dismissal.
 *
 * These guard tests cover the detection + fail-closed target selection that the ToolExecutor
 * dismiss loop relies on. The core trust-boundary property: the dismiss target is never surfaced on
 * a screen that also carries a dangerous-action control, so an auto-tap can never hit a
 * payment/authorize/delete surface.
 */
class BlockingOverlayGuardTest {
    private fun node(
        id: String,
        text: String = "",
        contentDescription: String = "",
        clickable: Boolean = false,
        bounds: ScreenBounds? = ScreenBounds(0, 0, 100, 48),
        enabled: Boolean = true,
    ): ScreenNode = ScreenNode(
        id = id,
        text = text,
        contentDescription = contentDescription,
        className = "android.view.View",
        bounds = bounds,
        clickable = clickable,
        editable = false,
        scrollable = false,
        enabled = enabled,
    )

    private fun snapshot(id: String, nodes: List<ScreenNode>): ScreenStateSnapshot =
        ScreenStateSnapshot(
            id = id,
            packageName = "com.example.app",
            capturedAtMillis = 1L,
            nodes = nodes,
            textSummary = nodes.joinToString(" ") { it.text.ifBlank { it.contentDescription } },
            truncated = false,
        )

    @Test
    fun detectsBlockingOverlayWhenMarkersAndCloseAffordancePresent() {
        val overlay = snapshot(
            id = "overlay",
            nodes = listOf(
                node(id = "promo-1", text = "限时抢购 立即购买"),
                node(id = "promo-2", text = "已获得 红包 倒计时"),
                node(id = "close", text = "关闭", clickable = true),
            ),
        )

        assertTrue(overlay.hasBlockingOverlay())
        val target = overlay.blockingOverlayDismissTarget()
        assertEquals("close", target?.id)
    }

    @Test
    fun doesNotDetectOverlayBelowMarkerThreshold() {
        val plain = snapshot(
            id = "plain",
            nodes = listOf(
                node(id = "promo-1", text = "限时抢购"),
                node(id = "content", text = "商品详情"),
                node(id = "close", text = "关闭", clickable = true),
            ),
        )

        assertFalse("single marker must not trigger overlay detection", plain.hasBlockingOverlay())
        assertNull(plain.blockingOverlayDismissTarget())
    }

    @Test
    fun failsClosedWhenDangerousControlPresentOnOverlay() {
        // An overlay that also carries a 立即购买 dangerous control: the dismiss target must be
        // withheld so the auto-dismiss loop can never tap on a purchase surface.
        val dangerousOverlay = snapshot(
            id = "dangerous-overlay",
            nodes = listOf(
                node(id = "promo-1", text = "限时抢购 专属权益"),
                node(id = "promo-2", text = "已获得 红包"),
                node(id = "buy", text = "立即购买", clickable = true),
                node(id = "close", text = "关闭", clickable = true),
            ),
        )

        assertTrue("overlay is still detected", dangerousOverlay.hasBlockingOverlay())
        assertNull(
            "dismiss target must be withheld when a dangerous control is present",
            dangerousOverlay.blockingOverlayDismissTarget(),
        )
    }

    @Test
    fun recognizesCommonDismissLabels() {
        listOf("关闭", "跳过", "我知道了", "close", "SKIP").forEach { label ->
            assertTrue("'$label' should be a dismiss label", label.isOverlayDismissLabel())
        }
        assertFalse("购买 must not be a dismiss label", "立即购买".isOverlayDismissLabel())
        assertFalse("overly long text is not a dismiss label", "关闭这个非常长的广告弹窗提示框内容".isOverlayDismissLabel())
    }

    // ── One overlay predicate for both the ToolExecutor loop and the accessibility service ────────

    @Test
    fun labelsThatOnlyContainACloseWordAreNotDismissTargets() {
        // The accessibility service used to run its own looser copy of this predicate: a length limit of
        // 16 plus a bare `contains("关闭"|"close"|"dismiss")`. That made these strings qualify as close
        // buttons on the ONE path that actually taps, so the loop could auto-tap a settings row or a
        // payment toggle while trying to dismiss an ad. The strict predicate is now the only one.
        listOf(
            "关闭免密支付",
            "关闭订单",
            "关闭自动续费",
            "close account",
            "dismiss all reminders",
            "确认关闭",
        ).forEach { label ->
            assertFalse(
                "'$label' must not be treated as an overlay dismiss control",
                label.isOverlayDismissLabel(),
            )
        }
    }

    @Test
    fun dismissLabelsMustBeShortStandaloneAffordances() {
        // The boundary that separates a real close control from content text that mentions closing.
        assertTrue("关闭".isOverlayDismissLabel())
        assertTrue("不感兴趣".isOverlayDismissLabel())
        // 8 normalized characters is the cap; anything longer is content, not an affordance.
        assertFalse("我知道了不再提醒我".isOverlayDismissLabel())
        assertFalse("".isOverlayDismissLabel())
        assertFalse((null as String?).isOverlayDismissLabel())
    }

    @Test
    fun blockingOverlayMarkerPredicateIsSharedAndSubstringBased() {
        // Markers are intentionally substring matches (a promo banner embeds them in longer copy), unlike
        // dismiss labels which must be exact. Exposing the predicate is what lets the accessibility
        // service's `looksLikeSearchBlockingOverlay` share this exact list instead of re-listing it.
        assertTrue("恭喜已获得 88 元红包".hasBlockingOverlayMarker())
        assertTrue("限时抢购倒计时 00:30".hasBlockingOverlayMarker())
        assertFalse("商品详情".hasBlockingOverlayMarker())
        assertFalse("".hasBlockingOverlayMarker())
    }

    // ── Update dialogs, splash ads and nag pop-ups ───────────────────────────────────────────────

    @Test
    fun recognizesUpdateAndAdDismissLabels() {
        listOf(
            "以后再说",
            "下次再说",
            "稍后再说",
            "稍后提醒",
            "暂不更新",
            "暂不升级",
            "跳过广告",
            "残忍拒绝",
            "跳过 3",
            "3s 跳过",
            "跳过(5)",
            "跳过广告 5秒",
            "Skip 5",
        ).forEach { label ->
            assertTrue("'$label' should be a dismiss label", label.isOverlayDismissLabel())
        }
    }

    @Test
    fun newDismissLabelsStayExactAndShort() {
        listOf(
            // Content text that merely contains a new dismiss word.
            "以后再说吧我们先聊聊",
            "残忍拒绝后将失去优惠券资格",
            "暂不更新会导致部分功能不可用",
            // Countdown look-alikes that are not countdowns.
            "跳过3天",
            "跳过此步骤3",
            "跳过123",
            "3",
            "5秒",
            // Dangerous rows stay refused whatever is added to the list.
            "关闭免密支付",
            "关闭自动续费",
        ).forEach { label ->
            assertFalse("'$label' must not be an overlay dismiss control", label.isOverlayDismissLabel())
        }
    }

    @Test
    fun updateDialogIsDetectedAndDismissedViaLaterLabel() {
        val updateDialog = snapshot(
            id = "update",
            nodes = listOf(
                node(id = "title", text = "发现新版本 V9.2.0"),
                node(id = "body", text = "更新内容：1. 修复已知问题"),
                node(id = "update", text = "立即更新", clickable = true),
                node(id = "later", text = "以后再说", clickable = true),
            ),
        )

        assertTrue(updateDialog.hasBlockingOverlay())
        assertEquals("later", updateDialog.blockingOverlayDismissTarget()?.id)
    }

    @Test
    fun splashAdWithCountdownSkipIsDetectedAndDismissed() {
        val splash = snapshot(
            id = "splash",
            nodes = listOf(
                node(id = "ad_copy", text = "点击跳转详情页或第三方应用"),
                node(id = "skip", text = "跳过 3", clickable = true),
            ),
        )

        assertTrue(splash.hasBlockingOverlay())
        assertEquals("skip", splash.blockingOverlayDismissTarget()?.id)
    }

    @Test
    fun updateDialogWithDangerousControlIsNeverDismissed() {
        // The "no dismiss on screens with dangerous controls" rule holds for the new overlay kinds: an
        // update prompt that also asks to 同意 a policy, or offers a 关闭免密支付 row, gets no target.
        val withConsent = snapshot(
            id = "update-consent",
            nodes = listOf(
                node(id = "title", text = "发现新版本"),
                node(id = "body", text = "更新内容"),
                node(id = "agree", text = "同意并更新", clickable = true),
                node(id = "later", text = "以后再说", clickable = true),
            ),
        )
        val withPaymentToggle = snapshot(
            id = "update-payment",
            nodes = listOf(
                node(id = "title", text = "版本更新"),
                node(id = "body", text = "立即升级享受新功能"),
                node(id = "payment", text = "关闭免密支付", clickable = true),
                node(id = "later", text = "下次再说", clickable = true),
            ),
        )

        assertTrue(withConsent.hasBlockingOverlay())
        assertNull(withConsent.blockingOverlayDismissTarget())
        assertTrue(withPaymentToggle.hasBlockingOverlay())
        assertNull(withPaymentToggle.blockingOverlayDismissTarget())
    }

    @Test
    fun singleUpdateOrAdMarkerIsNotAnOverlay() {
        // A settings "版本更新" row or a feed card's "摇一摇" copy alone is ordinary content.
        val settingsRow = snapshot(
            id = "settings",
            nodes = listOf(
                node(id = "row", text = "版本更新", clickable = true),
                node(id = "later", text = "以后再说", clickable = true),
            ),
        )

        assertFalse(settingsRow.hasBlockingOverlay())
        assertNull(settingsRow.blockingOverlayDismissTarget())
    }

    @Test
    fun overlayDetectionThresholdIsTheSharedConstant() {
        // Both detectors count markers against the same threshold; a single marker is normal commerce
        // copy, two or more is an interstitial.
        assertEquals(2, BLOCKING_OVERLAY_MARKER_THRESHOLD)

        val oneMarker = snapshot(
            id = "one",
            nodes = listOf(node(id = "promo", text = "限时抢购"), node(id = "body", text = "商品详情")),
        )
        val twoMarkers = snapshot(
            id = "two",
            nodes = listOf(node(id = "promo", text = "限时抢购"), node(id = "gift", text = "红包")),
        )

        assertFalse(oneMarker.hasBlockingOverlay())
        assertTrue(twoMarkers.hasBlockingOverlay())
    }
}
