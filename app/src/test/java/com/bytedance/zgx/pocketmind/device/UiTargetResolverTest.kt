package com.bytedance.zgx.pocketmind.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node as DomNode
import javax.xml.parsers.DocumentBuilderFactory

class UiTargetResolverTest {
    @Test
    fun kindForTargetPrefersSpecificSearchIntents() {
        assertEquals(UiTargetKind.SubmitSearch, UiTargetResolver.kindForTarget("提交搜索"))
        assertEquals(UiTargetKind.SearchEntry, UiTargetResolver.kindForTarget("搜索输入框"))
        assertEquals(UiTargetKind.EditableField, UiTargetResolver.kindForTarget("输入框"))
        assertEquals(UiTargetKind.SearchEntry, UiTargetResolver.kindForTarget("搜索入口"))
        assertEquals(UiTargetKind.SearchEntry, UiTargetResolver.kindForTarget("地址栏"))
        assertEquals(UiTargetKind.SearchEntry, UiTargetResolver.kindForTarget("目的地"))
        assertEquals(UiTargetKind.SearchEntry, UiTargetResolver.kindForTarget("搜地点"))
        assertEquals(UiTargetKind.SearchEntry, UiTargetResolver.kindForTarget("搜索地点"))
        assertEquals(UiTargetKind.SearchEntry, UiTargetResolver.kindForTarget("终点"))
        assertEquals(UiTargetKind.FilterEntry, UiTargetResolver.kindForTarget("筛选"))
        assertNull(UiTargetResolver.kindForTarget("天气怎么样"))
    }

    @Test
    fun resolvesEditableSearchFieldBeforeGenericSearchButtons() {
        val snapshot = snapshot(
            nodes = listOf(
                node(id = "disabled-search", text = "搜索", clickable = true, enabled = false),
                node(
                    id = "search-field",
                    text = "",
                    contentDescription = "搜索商品",
                    className = "android.widget.EditText",
                    bounds = ScreenBounds(12, 80, 1068, 160),
                    editable = true,
                ),
                node(
                    id = "bottom-search",
                    text = "搜索",
                    className = "android.widget.Button",
                    bounds = ScreenBounds(900, 1800, 1068, 1900),
                    clickable = true,
                ),
            ),
        )

        val resolved = UiTargetResolver.resolve(snapshot, UiTargetKind.SearchEntry)

        assertEquals("search-field", resolved?.nodeId)
    }

    @Test
    fun resolvesTopSearchBarBeforePureSearchButton() {
        val snapshot = snapshot(
            packageName = "com.taobao.taobao",
            nodes = listOf(
                node(
                    id = "pure-search-button",
                    text = "搜索",
                    className = "android.widget.TextView",
                    bounds = ScreenBounds(900, 96, 1060, 168),
                    clickable = true,
                ),
                node(
                    id = "search-bar",
                    contentDescription = "搜索栏",
                    className = "android.widget.FrameLayout",
                    bounds = ScreenBounds(40, 88, 860, 176),
                    clickable = true,
                ),
            ),
        )

        val resolved = UiTargetResolver.resolve(snapshot, UiTargetKind.SearchEntry, target = "搜索入口")

        assertEquals("search-bar", resolved?.nodeId)
    }

    @Test
    fun deprioritizesCameraSearchForTextSearchEntry() {
        val snapshot = snapshot(
            packageName = "com.xunmeng.pinduoduo",
            nodes = listOf(
                node(
                    id = "camera-search",
                    contentDescription = "拍照搜索",
                    className = "android.widget.ImageView",
                    bounds = ScreenBounds(1000, 92, 1120, 168),
                    clickable = true,
                ),
                node(
                    id = "text-search-entry",
                    text = "猫咪猫砂",
                    contentDescription = "搜索",
                    className = "android.widget.TextView",
                    bounds = ScreenBounds(120, 92, 900, 168),
                    clickable = false,
                ),
            ),
        )

        val resolved = UiTargetResolver.resolve(snapshot, UiTargetKind.SearchEntry, target = "搜索入口")

        assertEquals("text-search-entry", resolved?.nodeId)
    }

    @Test
    fun resolvesSubmitSearchButtonBeforeEditableField() {
        val snapshot = snapshot(
            nodes = listOf(
                node(
                    id = "search-field",
                    contentDescription = "搜索商品",
                    className = "android.widget.EditText",
                    bounds = ScreenBounds(12, 80, 900, 160),
                    editable = true,
                ),
                node(
                    id = "submit-button",
                    text = "搜索",
                    className = "android.widget.Button",
                    bounds = ScreenBounds(920, 80, 1068, 160),
                    clickable = true,
                ),
            ),
        )

        val resolved = UiTargetResolver.resolve(snapshot, UiTargetKind.SubmitSearch)

        assertEquals("submit-button", resolved?.nodeId)
    }

    @Test
    fun doesNotResolveEditableOnlyFieldAsSubmitSearchButton() {
        val snapshot = snapshot(
            nodes = listOf(
                node(
                    id = "search-field",
                    contentDescription = "搜索商品",
                    className = "android.widget.EditText",
                    bounds = ScreenBounds(12, 80, 900, 160),
                    editable = true,
                    clickable = true,
                ),
            ),
        )

        val resolved = UiTargetResolver.resolve(snapshot, UiTargetKind.SubmitSearch)

        assertNull(resolved)
    }

    @Test
    fun doesNotResolveVoiceSearchAsSubmitSearchButton() {
        val snapshot = snapshot(
            nodes = listOf(
                node(
                    id = "voice-search",
                    contentDescription = "语音搜索",
                    className = "android.widget.ImageButton",
                    bounds = ScreenBounds(920, 80, 1032, 172),
                    clickable = true,
                ),
            ),
        )

        val resolved = UiTargetResolver.resolve(snapshot, UiTargetKind.SubmitSearch)

        assertNull(resolved)
    }

    @Test
    fun doesNotResolveControlsWithoutSemanticEvidence() {
        val snapshot = snapshot(
            nodes = listOf(
                node(
                    id = "top-action",
                    text = "首页",
                    bounds = ScreenBounds(12, 80, 200, 160),
                    clickable = true,
                ),
                node(
                    id = "content-card",
                    text = "推荐内容",
                    bounds = ScreenBounds(12, 300, 1068, 460),
                    clickable = true,
                ),
            ),
        )

        assertNull(UiTargetResolver.resolve(snapshot, UiTargetKind.SearchEntry))
        assertNull(UiTargetResolver.resolve(snapshot, UiTargetKind.SubmitSearch))
        assertNull(UiTargetResolver.resolve(snapshot, UiTargetKind.FilterEntry))
    }

    @Test
    fun resolvesClickableFilterEntryBeforeResultTextContainingFilter() {
        val snapshot = snapshot(
            packageName = "com.taobao.taobao",
            nodes = listOf(
                node(
                    id = "result-summary",
                    text = "海河牛奶 综合 销量 筛选 商品列表",
                    bounds = ScreenBounds(12, 300, 1068, 480),
                ),
                node(
                    id = "filter-button",
                    text = "筛选",
                    contentDescription = "FilterEntry",
                    className = "android.widget.Button",
                    bounds = ScreenBounds(760, 180, 940, 260),
                    clickable = true,
                ),
            ),
        )

        val resolved = UiTargetResolver.resolve(snapshot, UiTargetKind.FilterEntry, target = "筛选")

        assertEquals("filter-button", resolved?.nodeId)
    }

    @Test
    fun appProfileHintsPromoteAppSpecificSearchEntry() {
        val snapshot = snapshot(
            packageName = "com.autonavi.minimap",
            nodes = listOf(
                node(
                    id = "feed-card",
                    text = "附近推荐",
                    bounds = ScreenBounds(12, 300, 1068, 420),
                    clickable = true,
                ),
                node(
                    id = "destination-entry",
                    text = "去哪儿",
                    bounds = ScreenBounds(12, 72, 1068, 152),
                    clickable = true,
                ),
            ),
        )

        val resolved = UiTargetResolver.resolve(snapshot, UiTargetKind.SearchEntry)

        assertEquals("destination-entry", resolved?.nodeId)
    }

    @Test
    fun searchEntryRankingDemotesResultListsAndProductCards() {
        val snapshot = snapshot(
            packageName = "com.taobao.taobao",
            nodes = listOf(
                node(
                    id = "result-list",
                    text = "搜索 海河牛奶 综合 销量 筛选 商品列表 旗舰店 ￥29 已售1000 评价",
                    bounds = ScreenBounds(0, 220, 1080, 1800),
                    clickable = true,
                    scrollable = true,
                ),
                node(
                    id = "product-card",
                    text = "搜索同款 海河牛奶旗舰店 ￥29 已售1000 商品 评价 购买",
                    bounds = ScreenBounds(0, 460, 1080, 980),
                    clickable = true,
                ),
                node(
                    id = "search-entry",
                    text = "搜索商品",
                    bounds = ScreenBounds(16, 72, 1064, 152),
                    clickable = true,
                ),
            ),
        )

        val ranked = UiTargetResolver.resolveAll(snapshot, UiTargetKind.SearchEntry, target = "搜索入口")

        assertEquals("search-entry", ranked.firstOrNull()?.nodeId)
        assertTrue(ranked.none { it.nodeId == "result-list" })
        assertTrue(ranked.none { it.nodeId == "product-card" })
    }

    @Test
    fun taobaoReplayShapePrefersTextSearchDiscoveryOverVisualSearchEntrypoints() {
        val snapshot = snapshot(
            packageName = "com.taobao.taobao",
            nodes = listOf(
                node(
                    id = "camera-search",
                    contentDescription = "拍照搜索",
                    className = "android.widget.ImageView",
                    bounds = ScreenBounds(920, 86, 1012, 172),
                    clickable = true,
                ),
                node(
                    id = "same-style-search",
                    text = "找同款",
                    className = "android.widget.TextView",
                    bounds = ScreenBounds(1012, 86, 1072, 172),
                    clickable = true,
                ),
                node(
                    id = "result-card",
                    text = "搜索发现 耳机 综合 销量 筛选 商品列表 旗舰店 ￥199",
                    bounds = ScreenBounds(0, 420, 1080, 1180),
                    clickable = true,
                    scrollable = true,
                ),
                node(
                    id = "search-discovery-entry",
                    text = "搜索发现",
                    contentDescription = "搜索宝贝和店铺",
                    className = "android.widget.TextView",
                    bounds = ScreenBounds(48, 86, 900, 172),
                    clickable = true,
                ),
            ),
        )

        val evidence = UiTargetResolver.explain(snapshot, UiTargetKind.SearchEntry, target = "搜索入口")

        assertEquals("search-discovery-entry", evidence.selectedNodeId)
        assertEquals("search-discovery-entry", evidence.rankedCandidates.firstOrNull()?.nodeId)
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "same-style-search" })
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "result-card" })
        val cameraScore = evidence.rankedCandidates.firstOrNull { candidate -> candidate.nodeId == "camera-search" }
            ?.score
            ?.finalScore
            ?: 0
        assertTrue(cameraScore < (evidence.rankedCandidates.firstOrNull()?.score?.finalScore ?: 0))
    }

    @Test
    fun gaodeReplayShapeResolvesClickableDestinationEntryWithoutChoosingMapCanvas() {
        val snapshot = snapshot(
            packageName = "com.autonavi.minimap",
            nodes = listOf(
                node(
                    id = "map-canvas",
                    text = "路线 导航 附近 美食 酒店 公交 地铁 查看地图 展开列表",
                    className = "android.view.View",
                    bounds = ScreenBounds(0, 220, 1080, 1900),
                    clickable = true,
                    scrollable = true,
                ),
                node(
                    id = "destination-entry",
                    text = "你要去哪儿",
                    contentDescription = "搜地点、公交、地铁",
                    className = "android.widget.TextView",
                    bounds = ScreenBounds(40, 96, 1040, 176),
                    clickable = true,
                ),
            ),
        )

        val evidence = UiTargetResolver.explain(snapshot, UiTargetKind.SearchEntry, target = "搜索入口")

        assertEquals("destination-entry", evidence.selectedNodeId)
        assertEquals("你要去哪儿 搜地点、公交、地铁", evidence.rankedCandidates.firstOrNull()?.label)
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "map-canvas" })
    }

    @Test
    fun jdAndBrowserReplayShapesUseProfileSpecificSearchHints() {
        val jd = snapshot(
            packageName = "com.jingdong.app.mall",
            nodes = listOf(
                node(
                    id = "home-feed",
                    text = "京东物流 百亿补贴 秒杀 推荐 商品列表",
                    bounds = ScreenBounds(0, 360, 1080, 1900),
                    clickable = true,
                    scrollable = true,
                ),
                node(
                    id = "jd-search",
                    text = "搜索京东商品/店铺",
                    className = "android.widget.TextView",
                    bounds = ScreenBounds(36, 84, 1044, 168),
                    clickable = true,
                ),
            ),
        )
        val browser = snapshot(
            packageName = "com.quark.browser",
            nodes = listOf(
                node(
                    id = "web-feed",
                    text = "热搜 新闻 推荐",
                    bounds = ScreenBounds(0, 320, 1080, 1900),
                    clickable = true,
                    scrollable = true,
                ),
                node(
                    id = "quark-address",
                    text = "请输入搜索词或网址",
                    className = "android.widget.TextView",
                    bounds = ScreenBounds(48, 80, 1032, 168),
                    clickable = true,
                ),
            ),
        )

        assertEquals("jd-search", UiTargetResolver.resolve(jd, UiTargetKind.SearchEntry)?.nodeId)
        assertEquals("quark-address", UiTargetResolver.resolve(browser, UiTargetKind.SearchEntry, target = "地址栏")?.nodeId)
    }

    @Test
    fun explainIncludesRankedScoreEvidenceAndFailureKind() {
        val snapshot = snapshot(
            packageName = "com.taobao.taobao",
            nodes = listOf(
                node(
                    id = "result-list",
                    text = "搜索 海河牛奶 综合 销量 筛选 商品列表 旗舰店 ￥29 已售1000 评价",
                    bounds = ScreenBounds(0, 220, 1080, 1800),
                    clickable = true,
                    scrollable = true,
                ),
                node(
                    id = "search-entry",
                    text = "搜索商品",
                    bounds = ScreenBounds(16, 72, 1064, 152),
                    clickable = true,
                ),
            ),
        )

        val evidence = UiTargetResolver.explain(snapshot, UiTargetKind.SearchEntry, target = "搜索入口")

        assertEquals(UiTargetKind.SearchEntry, evidence.kind)
        assertEquals("搜索入口", evidence.target)
        assertEquals("com.taobao.taobao", evidence.packageName)
        assertEquals(null, evidence.failureKind)
        assertEquals("search-entry", evidence.selectedNodeId)
        assertEquals("search-entry", evidence.rankedCandidates.firstOrNull()?.nodeId)
        assertEquals("搜索商品", evidence.rankedCandidates.firstOrNull()?.label)
        assertTrue(evidence.rankedCandidates.firstOrNull()?.clickable == true)
        assertTrue(evidence.rankedCandidates.firstOrNull()?.score?.semanticScore ?: 0 > 0)
        assertTrue(evidence.rankedCandidates.firstOrNull()?.score?.profileHintScore ?: 0 > 0)
        assertEquals(
            UiTargetResolver.resolve(snapshot, UiTargetKind.SearchEntry, target = "搜索入口")?.confidence,
            evidence.rankedCandidates.firstOrNull()?.score?.finalScore,
        )

        val failed = UiTargetResolver.explain(
            snapshot(
                nodes = listOf(
                    node(id = "home", text = "首页", bounds = ScreenBounds(0, 0, 200, 120), clickable = true),
                ),
            ),
            UiTargetKind.SearchEntry,
            target = "搜索入口",
        )

        assertEquals(UiActionFailureKind.SearchEntryNotFound, failed.failureKind)
        assertTrue(failed.rankedCandidates.isEmpty())
    }

    @Test
    fun explainCandidateEvidenceIncludesActionabilityBoundsHintPenaltyAndConfidenceScore() {
        val snapshot = snapshot(
            packageName = "com.jingdong.app.mall",
            nodes = listOf(
                node(
                    id = "home-feed",
                    text = "京东物流 百亿补贴 秒杀 推荐 搜索好物 商品列表",
                    bounds = ScreenBounds(0, 360, 1080, 1900),
                    clickable = true,
                    scrollable = true,
                ),
                node(
                    id = "jd-search",
                    text = "搜索京东商品",
                    className = "android.widget.TextView",
                    bounds = ScreenBounds(36, 84, 1044, 168),
                    clickable = true,
                ),
            ),
        )

        val evidence = UiTargetResolver.explain(snapshot, UiTargetKind.SearchEntry, target = "搜索京东商品")
        val candidate = requireNotNull(evidence.rankedCandidates.firstOrNull())

        assertEquals("jd-search", evidence.selectedNodeId)
        assertEquals("jd-search", candidate.nodeId)
        assertEquals("搜索京东商品", candidate.label)
        assertEquals(ScreenBounds(36, 84, 1044, 168), candidate.bounds)
        assertTrue(candidate.clickable)
        assertTrue(!candidate.editable)
        assertTrue(!candidate.scrollable)
        assertTrue(candidate.enabled)
        assertEquals("搜索京东商品", candidate.matchedProfileHint)
        assertTrue(candidate.score.semanticScore > 0)
        assertTrue(candidate.score.profileHintScore > 0)
        assertTrue(candidate.score.targetTextScore > 0)
        assertTrue(candidate.score.actionabilityScore > 0)
        assertTrue(candidate.score.positionScore > 0)
        assertEquals(0, candidate.score.riskPenalty)
        assertEquals(0, candidate.score.noisePenalty)
        assertEquals(
            UiTargetResolver.resolve(snapshot, UiTargetKind.SearchEntry, target = "搜索京东商品")?.confidence,
            candidate.score.finalScore,
        )
    }

    @Test
    fun doesNotResolveLargeResultListAsSearchEntry() {
        val snapshot = snapshot(
            packageName = "com.taobao.taobao",
            nodes = listOf(
                node(
                    id = "result-list",
                    text = "搜索 海河牛奶 综合 销量 筛选 商品列表 旗舰店 ￥29 已售1000 评价",
                    bounds = ScreenBounds(0, 120, 1080, 1800),
                    clickable = true,
                    scrollable = true,
                ),
            ),
        )

        val resolved = UiTargetResolver.resolve(snapshot, UiTargetKind.SearchEntry, target = "搜索入口")

        assertNull(resolved)
    }

    @Test
    fun doesNotResolveProductCardTextAsEditableField() {
        val snapshot = snapshot(
            packageName = "com.taobao.taobao",
            nodes = listOf(
                node(
                    id = "product-card",
                    text = "搜索输入框 海河牛奶旗舰店 ￥29 已售1000 商品 评价 购买",
                    bounds = ScreenBounds(0, 460, 1080, 980),
                    clickable = true,
                ),
            ),
        )

        val resolved = UiTargetResolver.resolve(snapshot, UiTargetKind.EditableField, target = "输入框")

        assertNull(resolved)
    }

    @Test
    fun chromePackageUsesBrowserProfileForAddressBar() {
        val profile = AppInteractionProfiles.forPackage("com.android.chrome")
        val snapshot = snapshot(
            packageName = "com.android.chrome",
            nodes = listOf(
                node(
                    id = "url-bar",
                    contentDescription = "Search or type web address",
                    className = "android.widget.EditText",
                    bounds = ScreenBounds(12, 80, 1068, 160),
                    editable = true,
                ),
            ),
        )

        val resolved = UiTargetResolver.resolve(snapshot, UiTargetKind.SearchEntry, target = "地址栏", profile = profile)

        assertEquals("url-bar", resolved?.nodeId)
    }

    @Test
    fun browserProfileResolvesLocalizedGoSubmitLabel() {
        val snapshot = snapshot(
            packageName = "com.android.browser",
            nodes = listOf(
                node(
                    id = "address-field",
                    text = "搜索或输入网址",
                    className = "android.widget.EditText",
                    bounds = ScreenBounds(48, 84, 820, 172),
                    editable = true,
                    clickable = true,
                ),
                node(
                    id = "go-button",
                    text = "转到",
                    className = "android.widget.TextView",
                    bounds = ScreenBounds(860, 84, 1032, 172),
                    clickable = true,
                ),
            ),
        )

        val evidence = UiTargetResolver.explain(snapshot, UiTargetKind.SubmitSearch, target = "提交搜索")

        assertEquals("go-button", evidence.selectedNodeId)
        assertEquals("go-button", evidence.rankedCandidates.firstOrNull()?.nodeId)
        assertEquals("转到", evidence.rankedCandidates.firstOrNull()?.matchedProfileHint)
    }

    @Test
    fun unknownAppDoesNotResolveBrowserOnlyGoSubmitLabel() {
        val snapshot = snapshot(
            packageName = "com.example.reader",
            nodes = listOf(
                node(
                    id = "enter-section",
                    text = "转到",
                    className = "android.widget.TextView",
                    bounds = ScreenBounds(860, 84, 1032, 172),
                    clickable = true,
                ),
            ),
        )

        val evidence = UiTargetResolver.explain(snapshot, UiTargetKind.SubmitSearch, target = "提交搜索")

        assertEquals(UiActionFailureKind.SubmitNotFound, evidence.failureKind)
        assertTrue(evidence.rankedCandidates.isEmpty())
    }

    @Test
    fun jdDisabledKeyboardSubmitDumpKeepsFailureEvidenceWithoutSelectingIt() {
        val snapshot = loadDump("ui_dumps/real_app_search/jd_disabled_keyboard_submit.xml")

        val evidence = UiTargetResolver.explain(snapshot, UiTargetKind.SubmitSearch, target = "提交搜索")

        assertEquals(UiActionFailureKind.SubmitNotFound, evidence.failureKind)
        assertNull(evidence.selectedNodeId)
        assertNull(UiTargetResolver.resolve(snapshot, UiTargetKind.SubmitSearch, target = "提交搜索"))
        val keyboardCandidate = requireNotNull(
            evidence.rankedCandidates.firstOrNull { candidate ->
                candidate.nodeId == "com.jingdong.app.mall:id/keyboard_search_action"
            },
        )
        assertEquals("键盘搜索", keyboardCandidate.label)
        assertEquals(false, keyboardCandidate.enabled)
        assertEquals("搜索", keyboardCandidate.matchedProfileHint)
        assertTrue(keyboardCandidate.score.finalScore < 650)
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.enabled && candidate.score.finalScore >= 650 })
    }

    @Test
    fun googleMapsSubmitHintsDoNotMatchGoogleByGoSubstring() {
        val profile = AppInteractionProfiles.forPackage("com.google.android.apps.maps")
        val snapshot = snapshot(
            packageName = "com.google.android.apps.maps",
            nodes = listOf(
                node(
                    id = "google-attribution",
                    text = "Google",
                    bounds = ScreenBounds(12, 1800, 240, 1900),
                    clickable = true,
                ),
            ),
        )

        val resolved = UiTargetResolver.resolve(snapshot, UiTargetKind.SubmitSearch, profile = profile)

        assertNull(resolved)
    }

    @Test
    fun resultVerificationPassesWhenQueryIsVisible() {
        val after = snapshot(
            packageName = "com.taobao.taobao",
            nodes = listOf(
                node(id = "search-box", text = "海河牛奶", editable = true),
                node(id = "result", text = "海河牛奶旗舰店", clickable = true),
            ),
        )

        val verification = AppSearchResultVerifier.verify(
            before = null,
            after = after,
            query = "海河牛奶",
            expectedPackageName = "com.taobao.taobao",
        )

        assertTrue(verification.summary, verification.verified)
        assertEquals("query_visible", verification.evidence)
    }

    @Test
    fun resultVerificationPassesWhenProfileHintAppearsAfterPageChange() {
        val before = snapshot(
            packageName = "com.xunmeng.pinduoduo",
            nodes = listOf(node(id = "search", text = "搜索商品", clickable = true)),
        )
        val after = snapshot(
            packageName = "com.xunmeng.pinduoduo",
            nodes = listOf(
                node(id = "sort", text = "综合", clickable = true),
                node(id = "filter", text = "筛选", clickable = true),
                node(id = "list", text = "百亿补贴", clickable = true),
            ),
        )

        val verification = AppSearchResultVerifier.verify(
            before = before,
            after = after,
            query = "纸巾",
            expectedPackageName = "com.xunmeng.pinduoduo",
        )

        assertTrue(verification.summary, verification.verified)
        assertEquals("result_hints_visible", verification.evidence)
    }

    @Test
    fun resultVerificationFailsWhenOnlyResultHintsRemainOnUnchangedPage() {
        val resultPage = snapshot(
            packageName = "com.taobao.taobao",
            nodes = listOf(
                node(id = "sort", text = "综合", clickable = true),
                node(id = "sales", text = "销量", clickable = true),
                node(id = "filter", text = "筛选", clickable = true),
            ),
        )

        val verification = AppSearchResultVerifier.verify(
            before = resultPage,
            after = resultPage,
            query = "海河牛奶",
            expectedPackageName = "com.taobao.taobao",
        )

        assertEquals(false, verification.verified)
        assertEquals(UiActionFailureKind.ResultNotVerified, verification.failureKind)
        assertEquals("page_not_changed", verification.evidence)
    }

    @Test
    fun resultVerificationPassesForStableAmapPlaceResults() {
        val resultPage = snapshot(
            packageName = "com.autonavi.minimap",
            nodes = listOf(
                node(id = "map", text = "查看地图", clickable = true),
                node(id = "list", text = "展开列表", clickable = true),
                node(id = "airport-1", text = "北京首都国际机场", clickable = true),
                node(id = "airport-2", text = "北京大兴国际机场", clickable = true),
            ),
        )

        val verification = AppSearchResultVerifier.verify(
            before = resultPage,
            after = resultPage,
            query = "机场",
            expectedPackageName = "com.autonavi.minimap",
        )

        assertTrue(verification.summary, verification.verified)
        assertEquals("query_visible_with_result_hint", verification.evidence)
    }

    @Test
    fun resultVerificationFailsWhenQueryOnlyAppearsInChangedEditableField() {
        val before = snapshot(
            packageName = "com.taobao.taobao",
            nodes = listOf(node(id = "search-field", text = "", editable = true)),
        )
        val after = snapshot(
            packageName = "com.taobao.taobao",
            nodes = listOf(node(id = "search-field", text = "海河牛奶", editable = true)),
        )

        val verification = AppSearchResultVerifier.verify(
            before = before,
            after = after,
            query = "海河牛奶",
            expectedPackageName = "com.taobao.taobao",
        )

        assertEquals(false, verification.verified)
        assertEquals(UiActionFailureKind.ResultNotVerified, verification.failureKind)
        assertEquals("page_changed_without_result_evidence", verification.evidence)
    }

    @Test
    fun resultVerificationFailsWhenOnlyOldSearchSuggestionContainsQuery() {
        val before = snapshot(
            packageName = "com.taobao.taobao",
            nodes = listOf(
                node(id = "suggestion", text = "海河牛奶", clickable = true),
            ),
        )
        val after = before.copy(
            id = "screen-2",
            nodes = before.nodes + node(id = "keyboard", text = "完成", clickable = true),
        )

        val verification = AppSearchResultVerifier.verify(
            before = before,
            after = after,
            query = "海河牛奶",
            expectedPackageName = "com.taobao.taobao",
        )

        assertEquals(false, verification.verified)
        assertEquals(UiActionFailureKind.ResultNotVerified, verification.failureKind)
        assertEquals("page_changed_without_result_evidence", verification.evidence)
    }

    @Test
    fun resultVerificationFailsOnPackageMismatchOrUnchangedPage() {
        val before = snapshot(
            packageName = "com.taobao.taobao",
            nodes = listOf(node(id = "search", text = "搜索商品", clickable = true)),
        )
        val mismatched = AppSearchResultVerifier.verify(
            before = before,
            after = before.copy(packageName = "com.example.other"),
            query = "海河牛奶",
            expectedPackageName = "com.taobao.taobao",
        )
        assertEquals(false, mismatched.verified)
        assertEquals(UiActionFailureKind.AppNotForeground, mismatched.failureKind)

        val unchanged = AppSearchResultVerifier.verify(
            before = before,
            after = before,
            query = "海河牛奶",
            expectedPackageName = "com.taobao.taobao",
        )
        assertEquals(false, unchanged.verified)
        assertEquals(UiActionFailureKind.ResultNotVerified, unchanged.failureKind)
        assertEquals("page_not_changed", unchanged.evidence)
    }

    @Test
    fun resultVerificationFailsWhenQueryOnlyRemainsInUnchangedEditableField() {
        val before = snapshot(
            packageName = "com.taobao.taobao",
            nodes = listOf(
                node(
                    id = "search-field",
                    text = "海河牛奶",
                    className = "android.widget.EditText",
                    editable = true,
                ),
            ),
        )

        val verification = AppSearchResultVerifier.verify(
            before = before,
            after = before,
            query = "海河牛奶",
            expectedPackageName = "com.taobao.taobao",
        )

        assertEquals(false, verification.verified)
        assertEquals(UiActionFailureKind.ResultNotVerified, verification.failureKind)
        assertEquals("page_not_changed", verification.evidence)
    }

    @Test
    fun resultVerificationDoesNotTreatClassNameAsQueryEvidence() {
        val before = snapshot(
            packageName = "com.example.app",
            nodes = listOf(node(id = "empty", className = "android.widget.TextView")),
        )
        val after = snapshot(
            packageName = "com.example.app",
            nodes = listOf(node(id = "empty", className = "android.view.View")),
        )

        val verification = AppSearchResultVerifier.verify(
            before = before,
            after = after,
            query = "view",
        )

        assertEquals(false, verification.verified)
        assertEquals(UiActionFailureKind.ResultNotVerified, verification.failureKind)
        assertEquals("page_changed_without_result_evidence", verification.evidence)
    }

    private fun snapshot(
        packageName: String = "com.example.app",
        nodes: List<ScreenNode>,
    ): ScreenStateSnapshot =
        ScreenStateSnapshot(
            id = "screen-1",
            packageName = packageName,
            capturedAtMillis = 1L,
            nodes = nodes,
            textSummary = nodes.joinToString(" ") { node -> node.text.ifBlank { node.contentDescription } },
            truncated = false,
        )

    private fun node(
        id: String,
        text: String = "",
        contentDescription: String = "",
        className: String = "android.view.View",
        bounds: ScreenBounds? = ScreenBounds(0, 0, 100, 48),
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        enabled: Boolean = true,
    ): ScreenNode =
        ScreenNode(
            id = id,
            text = text,
            contentDescription = contentDescription,
            className = className,
            bounds = bounds,
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            enabled = enabled,
        )

    private fun loadDump(resourcePath: String): ScreenStateSnapshot {
        val document = requireNotNull(javaClass.classLoader?.getResourceAsStream(resourcePath)) {
            "Missing test UIAutomator dump fixture: $resourcePath"
        }.use { input ->
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(input)
        }
        val nodes = mutableListOf<ScreenNode>()
        val root = document.documentElement
        collectNodes(root, path = "root", output = nodes)
        val packageName = firstPackageName(root)
            ?: nodes.firstOrNull { node -> node.id.contains(":id/") }
                ?.id
                ?.substringBefore(":id/")
        return ScreenStateSnapshot(
            id = resourcePath.substringAfterLast('/').substringBeforeLast('.'),
            packageName = packageName,
            capturedAtMillis = 1L,
            nodes = nodes,
            textSummary = nodes.joinToString(" ") { node -> node.text.ifBlank { node.contentDescription } },
            truncated = false,
        )
    }

    private fun collectNodes(element: Element, path: String, output: MutableList<ScreenNode>) {
        if (element.tagName == "node") {
            val resourceId = element.attr("resource-id")
            output += ScreenNode(
                id = resourceId.ifBlank { path },
                text = element.attr("text"),
                contentDescription = element.attr("content-desc"),
                className = element.attr("class"),
                bounds = parseBounds(element.attr("bounds")),
                clickable = element.attr("clickable").toBoolean(),
                editable = element.attr("class").contains("EditText") || element.attr("editable").toBoolean(),
                scrollable = element.attr("scrollable").toBoolean(),
                enabled = element.attr("enabled").ifBlank { "true" }.toBoolean(),
            )
        }
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == DomNode.ELEMENT_NODE) {
                collectNodes(child as Element, path = "$path/$index", output = output)
            }
        }
    }

    private fun firstPackageName(element: Element): String? {
        element.attr("package").takeIf { it.isNotBlank() }?.let { return it }
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == DomNode.ELEMENT_NODE) {
                firstPackageName(child as Element)?.let { return it }
            }
        }
        return null
    }

    private fun parseBounds(raw: String): ScreenBounds? {
        val match = BoundsRegex.matchEntire(raw) ?: return null
        val (left, top, right, bottom) = match.destructured
        return ScreenBounds(
            left = left.toInt(),
            top = top.toInt(),
            right = right.toInt(),
            bottom = bottom.toInt(),
        )
    }

    private fun Element.attr(name: String): String = getAttribute(name).orEmpty()

    private companion object {
        val BoundsRegex = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
    }
}
