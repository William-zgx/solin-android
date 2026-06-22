package com.bytedance.zgx.pocketmind.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node as DomNode
import javax.xml.parsers.DocumentBuilderFactory

class UiAutomatorDumpReplayTest {
    @Test
    fun taobaoSearchHomeDumpResolvesTextSearchEntryAndDemotesVisualSearch() {
        val snapshot = loadDump("ui_dumps/real_app_search/taobao_search_home.xml")

        val evidence = UiTargetResolver.explain(snapshot, UiTargetKind.SearchEntry, target = "搜索入口")

        assertNull(evidence.failureKind)
        assertEquals("com.taobao.taobao:id/search_bar", evidence.selectedNodeId)
        assertEquals("com.taobao.taobao:id/search_bar", evidence.rankedCandidates.firstOrNull()?.nodeId)
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "com.taobao.taobao:id/camera_search" })
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "com.taobao.taobao:id/same_style" })
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "com.taobao.taobao:id/result_feed" })
    }

    @Test
    fun taobaoResultDumpVerifiesSearchResultAfterPageChange() {
        val before = loadDump("ui_dumps/real_app_search/taobao_search_home.xml")
        val after = loadDump("ui_dumps/real_app_search/taobao_search_results.xml")

        val verification = AppSearchResultVerifier.verify(
            before = before,
            after = after,
            query = "海河牛奶",
            expectedPackageName = "com.taobao.taobao",
            expectedAppName = "淘宝",
        )

        assertTrue(verification.summary, verification.verified)
        assertEquals("query_visible_after_change", verification.evidence)
        assertNull(verification.failureKind)
    }

    @Test
    fun taobaoInputDumpResolvesEditableFieldAndSubmitButton() {
        val snapshot = loadDump("ui_dumps/real_app_search/taobao_search_input.xml")

        val editable = UiTargetResolver.explain(snapshot, UiTargetKind.EditableField, target = "搜索输入框")
        val submit = UiTargetResolver.explain(snapshot, UiTargetKind.SubmitSearch, target = "提交搜索")

        assertNull(editable.failureKind)
        assertEquals("com.taobao.taobao:id/search_edit_text", editable.selectedNodeId)
        assertEquals("com.taobao.taobao:id/search_edit_text", editable.rankedCandidates.firstOrNull()?.nodeId)
        assertTrue(editable.rankedCandidates.none { candidate -> candidate.nodeId == "com.taobao.taobao:id/camera_search" })

        assertNull(submit.failureKind)
        assertEquals("com.taobao.taobao:id/search_submit", submit.selectedNodeId)
        assertEquals("com.taobao.taobao:id/search_submit", submit.rankedCandidates.firstOrNull()?.nodeId)
        assertTrue(submit.rankedCandidates.none { candidate -> candidate.nodeId == "com.taobao.taobao:id/search_edit_text" })
    }

    @Test
    fun gaodeHomeDumpResolvesDestinationSearchEntryAndDemotesMapCanvas() {
        val snapshot = loadDump("ui_dumps/real_app_search/gaode_destination_home.xml")

        val evidence = UiTargetResolver.explain(snapshot, UiTargetKind.SearchEntry, target = "搜索入口")

        assertNull(evidence.failureKind)
        assertEquals("com.autonavi.minimap:id/search_entry", evidence.selectedNodeId)
        assertEquals("你要去哪儿 搜地点、公交、地铁", evidence.rankedCandidates.firstOrNull()?.label)
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "com.autonavi.minimap:id/map_canvas" })
    }

    @Test
    fun gaodeInputDumpResolvesEditableFieldAndSubmitButton() {
        val snapshot = loadDump("ui_dumps/real_app_search/gaode_destination_input.xml")

        val editable = UiTargetResolver.explain(snapshot, UiTargetKind.EditableField, target = "目的地")
        val submit = UiTargetResolver.explain(snapshot, UiTargetKind.SubmitSearch, target = "提交搜索")

        assertNull(editable.failureKind)
        assertEquals("com.autonavi.minimap:id/search_edit_text", editable.selectedNodeId)
        assertEquals("搜索地点、公交、地铁", editable.rankedCandidates.firstOrNull()?.label)
        assertTrue(editable.rankedCandidates.none { candidate -> candidate.nodeId == "com.autonavi.minimap:id/voice_search" })

        assertNull(submit.failureKind)
        assertEquals("com.autonavi.minimap:id/search_submit", submit.selectedNodeId)
        assertEquals("搜索", submit.rankedCandidates.firstOrNull()?.label)
        assertTrue(submit.rankedCandidates.none { candidate -> candidate.nodeId == "com.autonavi.minimap:id/search_edit_text" })
        assertTrue(submit.rankedCandidates.none { candidate -> candidate.nodeId == "com.autonavi.minimap:id/voice_search" })
    }

    @Test
    fun gaodeResultDumpVerifiesSearchResultAfterPageChange() {
        val before = loadDump("ui_dumps/real_app_search/gaode_destination_home.xml")
        val after = loadDump("ui_dumps/real_app_search/gaode_destination_results.xml")

        val verification = AppSearchResultVerifier.verify(
            before = before,
            after = after,
            query = "北京机场",
            expectedPackageName = "com.autonavi.minimap",
            expectedAppName = "高德地图",
        )

        assertTrue(verification.summary, verification.verified)
        assertEquals("query_visible_after_change", verification.evidence)
        assertNull(verification.failureKind)
    }

    @Test
    fun pddSearchHomeDumpResolvesProductSearchEntryAndDemotesFeed() {
        val snapshot = loadDump("ui_dumps/real_app_search/pdd_search_home.xml")

        val evidence = UiTargetResolver.explain(snapshot, UiTargetKind.SearchEntry, target = "搜索入口")

        assertNull(evidence.failureKind)
        assertEquals("com.xunmeng.pinduoduo:id/search_bar", evidence.selectedNodeId)
        assertEquals("搜索商品 多多搜索", evidence.rankedCandidates.firstOrNull()?.label)
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "com.xunmeng.pinduoduo:id/scan_entry" })
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "com.xunmeng.pinduoduo:id/home_feed" })
    }

    @Test
    fun pddInputDumpResolvesEditableFieldAndSubmitButton() {
        val snapshot = loadDump("ui_dumps/real_app_search/pdd_search_input.xml")

        val editable = UiTargetResolver.explain(snapshot, UiTargetKind.EditableField, target = "搜索商品")
        val submit = UiTargetResolver.explain(snapshot, UiTargetKind.SubmitSearch, target = "提交搜索")

        assertNull(editable.failureKind)
        assertEquals("com.xunmeng.pinduoduo:id/search_edit_text", editable.selectedNodeId)
        assertEquals("搜索商品", editable.rankedCandidates.firstOrNull()?.label)
        assertTrue(editable.rankedCandidates.none { candidate -> candidate.nodeId == "com.xunmeng.pinduoduo:id/scan_entry" })
        assertTrue(editable.rankedCandidates.none { candidate -> candidate.nodeId == "com.xunmeng.pinduoduo:id/suggestion_list" })

        assertNull(submit.failureKind)
        assertEquals("com.xunmeng.pinduoduo:id/search_submit", submit.selectedNodeId)
        assertEquals("搜索", submit.rankedCandidates.firstOrNull()?.label)
        assertTrue(submit.rankedCandidates.none { candidate -> candidate.nodeId == "com.xunmeng.pinduoduo:id/search_edit_text" })
        assertTrue(submit.rankedCandidates.none { candidate -> candidate.nodeId == "com.xunmeng.pinduoduo:id/scan_entry" })
        assertTrue(submit.rankedCandidates.none { candidate -> candidate.nodeId == "com.xunmeng.pinduoduo:id/suggestion_list" })
    }

    @Test
    fun pddResultDumpVerifiesSearchResultAfterPageChange() {
        val before = loadDump("ui_dumps/real_app_search/pdd_search_home.xml")
        val after = loadDump("ui_dumps/real_app_search/pdd_search_results.xml")

        val verification = AppSearchResultVerifier.verify(
            before = before,
            after = after,
            query = "纸巾",
            expectedPackageName = "com.xunmeng.pinduoduo",
            expectedAppName = "拼多多",
        )

        assertTrue(verification.summary, verification.verified)
        assertEquals("query_visible_after_change", verification.evidence)
        assertNull(verification.failureKind)
        assertTrue(after.textSummary.contains("筛选"))
        assertTrue(after.textSummary.contains("百亿补贴"))
    }

    @Test
    fun jdSearchHomeDumpResolvesProfileSearchEntryAndDemotesFeed() {
        val snapshot = loadDump("ui_dumps/real_app_search/jd_search_home.xml")

        val evidence = UiTargetResolver.explain(snapshot, UiTargetKind.SearchEntry, target = "搜索入口")

        assertNull(evidence.failureKind)
        assertEquals("com.jingdong.app.mall:id/search_box", evidence.selectedNodeId)
        assertEquals("搜索京东商品/店铺", evidence.rankedCandidates.firstOrNull()?.label)
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "com.jingdong.app.mall:id/scan_entry" })
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "com.jingdong.app.mall:id/home_feed" })
    }

    @Test
    fun jdInputDumpResolvesEditableFieldAndSubmitButton() {
        val snapshot = loadDump("ui_dumps/real_app_search/jd_search_input.xml")

        val editable = UiTargetResolver.explain(snapshot, UiTargetKind.EditableField, target = "搜索京东商品")
        val submit = UiTargetResolver.explain(snapshot, UiTargetKind.SubmitSearch, target = "提交搜索")

        assertNull(editable.failureKind)
        assertEquals("com.jingdong.app.mall:id/search_edit_text", editable.selectedNodeId)
        assertEquals("搜索京东商品/店铺", editable.rankedCandidates.firstOrNull()?.label)
        assertTrue(editable.rankedCandidates.none { candidate -> candidate.nodeId == "com.jingdong.app.mall:id/scan_entry" })
        assertTrue(editable.rankedCandidates.none { candidate -> candidate.nodeId == "com.jingdong.app.mall:id/suggestion_list" })

        assertNull(submit.failureKind)
        assertEquals("com.jingdong.app.mall:id/search_submit", submit.selectedNodeId)
        assertEquals("搜索", submit.rankedCandidates.firstOrNull()?.label)
        assertTrue(submit.rankedCandidates.none { candidate -> candidate.nodeId == "com.jingdong.app.mall:id/search_edit_text" })
        assertTrue(submit.rankedCandidates.none { candidate -> candidate.nodeId == "com.jingdong.app.mall:id/scan_entry" })
        assertTrue(submit.rankedCandidates.none { candidate -> candidate.nodeId == "com.jingdong.app.mall:id/suggestion_list" })
    }

    @Test
    fun jdResultDumpVerifiesSearchResultAfterPageChange() {
        val before = loadDump("ui_dumps/real_app_search/jd_search_home.xml")
        val after = loadDump("ui_dumps/real_app_search/jd_search_results.xml")

        val verification = AppSearchResultVerifier.verify(
            before = before,
            after = after,
            query = "机械键盘",
            expectedPackageName = "com.jingdong.app.mall",
            expectedAppName = "京东",
        )

        assertTrue(verification.summary, verification.verified)
        assertEquals("query_visible_after_change", verification.evidence)
        assertNull(verification.failureKind)
    }

    @Test
    fun jdHomeDumpDoesNotVerifyUnchangedSearchResultFromFeedHints() {
        val home = loadDump("ui_dumps/real_app_search/jd_search_home.xml")

        val verification = AppSearchResultVerifier.verify(
            before = home,
            after = home,
            query = "机械键盘",
            expectedPackageName = "com.jingdong.app.mall",
            expectedAppName = "京东",
        )

        assertEquals(false, verification.verified)
        assertEquals(UiActionFailureKind.ResultNotVerified, verification.failureKind)
        assertEquals("page_not_changed", verification.evidence)
    }

    @Test
    fun browserHomeDumpResolvesAddressBarAndDemotesFeed() {
        val snapshot = loadDump("ui_dumps/real_app_search/quark_address_home.xml")

        val evidence = UiTargetResolver.explain(snapshot, UiTargetKind.SearchEntry, target = "地址栏")

        assertNull(evidence.failureKind)
        assertEquals("com.quark.browser:id/address_bar", evidence.selectedNodeId)
        assertEquals("请输入搜索词或网址 地址栏", evidence.rankedCandidates.firstOrNull()?.label)
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "com.quark.browser:id/scan_entry" })
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "com.quark.browser:id/web_feed" })
    }

    @Test
    fun chromeHomeDumpResolvesOmniboxAndDemotesToolbarActions() {
        val snapshot = loadDump("ui_dumps/real_app_search/chrome_address_home.xml")

        val evidence = UiTargetResolver.explain(snapshot, UiTargetKind.SearchEntry, target = "地址栏")

        assertNull(evidence.failureKind)
        assertEquals("com.android.chrome:id/search_box_text", evidence.selectedNodeId)
        assertEquals("搜索或输入网址 地址栏", evidence.rankedCandidates.firstOrNull()?.label)
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "com.android.chrome:id/voice_search_button" })
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "com.android.chrome:id/feed_stream" })
    }

    @Test
    fun androidBrowserHomeDumpResolvesAddressBarAndDemotesToolbarActions() {
        val snapshot = loadDump("ui_dumps/real_app_search/android_browser_address_home.xml")

        val evidence = UiTargetResolver.explain(snapshot, UiTargetKind.SearchEntry, target = "地址栏")

        assertNull(evidence.failureKind)
        assertEquals("com.android.browser:id/url", evidence.selectedNodeId)
        assertEquals("搜索或输入网址 地址栏", evidence.rankedCandidates.firstOrNull()?.label)
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "com.android.browser:id/voice_search" })
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "com.android.browser:id/web_feed" })
    }

    @Test
    fun ucBrowserHomeDumpResolvesAddressBarAndDemotesNewsFeed() {
        val snapshot = loadDump("ui_dumps/real_app_search/uc_address_home.xml")

        val evidence = UiTargetResolver.explain(snapshot, UiTargetKind.SearchEntry, target = "地址栏")

        assertNull(evidence.failureKind)
        assertEquals("com.UCMobile:id/search_address_bar", evidence.selectedNodeId)
        assertEquals("搜索或输入网址 地址栏", evidence.rankedCandidates.firstOrNull()?.label)
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "com.UCMobile:id/scan_entry" })
        assertTrue(evidence.rankedCandidates.none { candidate -> candidate.nodeId == "com.UCMobile:id/news_feed" })
    }

    @Test
    fun browserInputDumpResolvesEditableFieldAndSubmitButton() {
        val snapshot = loadDump("ui_dumps/real_app_search/quark_search_input.xml")

        val editable = UiTargetResolver.explain(snapshot, UiTargetKind.EditableField, target = "地址栏")
        val submit = UiTargetResolver.explain(snapshot, UiTargetKind.SubmitSearch, target = "提交搜索")

        assertNull(editable.failureKind)
        assertEquals("com.quark.browser:id/address_edit_text", editable.selectedNodeId)
        assertEquals("搜索或输入网址", editable.rankedCandidates.firstOrNull()?.label)

        assertNull(submit.failureKind)
        assertEquals("com.quark.browser:id/search_submit", submit.selectedNodeId)
        assertEquals("搜索", submit.rankedCandidates.firstOrNull()?.label)
        assertTrue(submit.rankedCandidates.none { candidate -> candidate.nodeId == "com.quark.browser:id/address_edit_text" })
    }

    @Test
    fun browserResultDumpVerifiesSearchResultAfterPageChange() {
        val before = loadDump("ui_dumps/real_app_search/quark_address_home.xml")
        val after = loadDump("ui_dumps/real_app_search/quark_search_results.xml")

        val verification = AppSearchResultVerifier.verify(
            before = before,
            after = after,
            query = "Kotlin 协程",
            expectedPackageName = "com.quark.browser",
            expectedAppName = "浏览器",
        )

        assertTrue(verification.summary, verification.verified)
        assertEquals("query_visible_after_change", verification.evidence)
        assertNull(verification.failureKind)
    }

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
