package com.bytedance.zgx.pocketmind.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTargetResolverTest {
    @Test
    fun kindForTargetPrefersSpecificSearchIntents() {
        assertEquals(UiTargetKind.SubmitSearch, UiTargetResolver.kindForTarget("提交搜索"))
        assertEquals(UiTargetKind.SearchEntry, UiTargetResolver.kindForTarget("搜索输入框"))
        assertEquals(UiTargetKind.EditableField, UiTargetResolver.kindForTarget("输入框"))
        assertEquals(UiTargetKind.SearchEntry, UiTargetResolver.kindForTarget("搜索入口"))
        assertEquals(UiTargetKind.SearchEntry, UiTargetResolver.kindForTarget("地址栏"))
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
    fun resultVerificationPassesWhenResultPageIsAlreadyStable() {
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

        assertTrue(verification.summary, verification.verified)
        assertEquals("result_hints_visible", verification.evidence)
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
}
