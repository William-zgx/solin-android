package com.bytedance.zgx.solin.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifestyle / media / travel / settings interaction profiles, exercised on SYNTHETIC fixture screens
 * (hand-built node lists shaped like each app's search home → input → results flow). They pin the
 * resolver + verifier contract the profiles feed; they are not evidence of real-app behaviour, which
 * stays pending real-device acceptance.
 */
class AppInteractionProfileCatalogTest {
    private data class AppFixture(
        val appName: String,
        val packageName: String,
        /** Visible label of the home-page search affordance. */
        val homeSearchLabel: String,
        /** True when the affordance is an icon whose only label is its content description. */
        val homeSearchIsIcon: Boolean = false,
        val inputHint: String,
        val query: String,
    )

    private val fixtures = listOf(
        AppFixture("美团", "com.sankuai.meituan", "搜索商家或商品", inputHint = "搜索商家或商品", query = "火锅"),
        AppFixture("大众点评", "com.dianping.v1", "搜索商户、地点", inputHint = "搜索商户、地点", query = "烤肉"),
        AppFixture("小红书", "com.xingin.xhs", "搜索", homeSearchIsIcon = true, inputHint = "搜索笔记", query = "露营装备"),
        AppFixture("哔哩哔哩", "tv.danmaku.bili", "搜索视频", inputHint = "搜索视频", query = "原神"),
        AppFixture("抖音", "com.ss.android.ugc.aweme", "搜索", homeSearchIsIcon = true, inputHint = "搜索你感兴趣的内容", query = "猫咪"),
        AppFixture("网易云音乐", "com.netease.cloudmusic", "搜索音乐", inputHint = "搜索歌曲", query = "晴天"),
        AppFixture("QQ音乐", "com.tencent.qqmusic", "搜索歌曲", inputHint = "搜索歌曲", query = "稻香"),
        AppFixture("携程", "ctrip.android.view", "搜索目的地", inputHint = "搜索目的地、酒店、景点", query = "三亚"),
        AppFixture("饿了么", "me.ele", "搜索商家或商品", inputHint = "搜索饿了么商家、商品名称", query = "奶茶"),
        AppFixture("设置", "com.android.settings", "搜索设置", inputHint = "搜索设置", query = "蓝牙"),
    )

    /** Clause starts that would turn a UI fact into an order, including the critical-action verbs. */
    private val imperativeNoteStarts = listOf(
        "发送", "支付", "删除", "确认", "下单", "转账", "授权", "发布",
        "点击", "轻点", "按下", "输入", "选择", "打开", "关闭", "请", "先", "再", "要", "不要", "别", "勿",
        "tap", "click", "press", "send", "pay", "delete", "confirm", "type", "open", "do not", "don't",
    )

    /** Rule vocabulary that has no place in a factual note, wherever it appears. */
    private val directiveNoteWords = listOf("务必", "必须", "应该", "不要", "不能", "禁止", "should", "must", "never", "always")

    @Test
    fun everyNewAppResolvesByPackageAndByName() {
        fixtures.forEach { fixture ->
            val byPackage = AppInteractionProfiles.forPackage(fixture.packageName)
            assertNotNull("missing profile for ${fixture.packageName}", byPackage)
            assertEquals(fixture.appName, byPackage, AppInteractionProfiles.forAppName(fixture.appName))
        }
        assertEquals(
            AppInteractionProfiles.forPackage("com.android.settings"),
            AppInteractionProfiles.forPackage("com.google.android.settings.intelligence"),
        )
        assertEquals("com.sankuai.meituan", AppInteractionProfiles.forAppName("meituan")?.packageNames?.first())
        assertEquals("tv.danmaku.bili", AppInteractionProfiles.forAppName("B站")?.packageNames?.first())
        assertEquals("com.tencent.qqmusic", AppInteractionProfiles.forAppName("QQ 音乐")?.packageNames?.first())
    }

    @Test
    fun aliasesAndPackagesAreUniqueAcrossAllProfiles() {
        val aliasOwners = AppInteractionProfiles.profiles
            .flatMap { profile -> profile.appNameAliases.map { alias -> alias.normalizedLookupKey() to profile } }
            .groupBy({ it.first }, { it.second })
        aliasOwners.forEach { (alias, owners) ->
            assertEquals("alias '$alias' is claimed by several profiles", 1, owners.distinct().size)
        }
        val packageOwners = AppInteractionProfiles.profiles
            .flatMap { profile -> profile.packageNames.map { packageName -> packageName to profile } }
            .groupBy({ it.first }, { it.second })
        packageOwners.forEach { (packageName, owners) ->
            assertEquals("package '$packageName' is claimed by several profiles", 1, owners.distinct().size)
        }
    }

    @Test
    fun newSearchEntryHintsAllCarrySearchVocabulary() {
        // phraseScore also matches a short label CONTAINED in a hint, so a hint without 搜/search would
        // hand unrelated 2-char tabs ("歌手", "酒店") search-entry evidence.
        (AppInteractionProfileCatalog.lifestyleAndMediaProfiles + AppInteractionProfileCatalog.contentAndOfficeProfiles).forEach { profile ->
            profile.searchEntryHints.forEach { hint ->
                val normalized = hint.normalizedLookupKey()
                assertTrue(
                    "hint '$hint' of ${profile.packageNames} lacks search vocabulary",
                    normalized.contains("搜") || normalized.contains("search"),
                )
            }
        }
    }

    @Test
    fun uiNotesAreShortBoundedFactsWithoutImperatives() {
        AppInteractionProfiles.profiles.forEach { profile ->
            val owner = profile.packageNames.first()
            assertTrue("$owner has ${profile.uiNotes.size} notes", profile.uiNotes.size <= MAX_APP_UI_NOTES)
            assertEquals("$owner has duplicate notes", profile.uiNotes.size, profile.uiNotes.distinct().size)
            profile.uiNotes.forEach { note ->
                assertTrue("$owner note too long (${note.length}): $note", note.length <= MAX_APP_UI_NOTE_CHARS)
                assertTrue("$owner note is blank", note.isNotBlank())
                // Each clause is checked, so "…，点击发送" is caught as well as a leading imperative.
                note.split('，', '；', '。', ',', ';', '：', ':').map { it.trim().lowercase() }.forEach { clause ->
                    imperativeNoteStarts.firstOrNull { verb -> clause.startsWith(verb) }?.let { verb ->
                        throw AssertionError("$owner note reads as an instruction ('$verb'): $note")
                    }
                }
                directiveNoteWords.firstOrNull { word -> note.lowercase().contains(word) }?.let { word ->
                    throw AssertionError("$owner note carries a rule word ('$word'): $note")
                }
            }
        }
    }

    @Test
    fun requiredUiNoteFactsArePresent() {
        assertTrue(
            "Settings must note that a toggle changes state on tap",
            appUiNotesFor("com.android.settings").any { it.contains("开关") && it.contains("切换") },
        )
        assertTrue(
            "WeChat must note that input-box text is a draft",
            appUiNotesFor("com.tencent.mm").any { it.contains("草稿") && it.contains("输入框") },
        )
    }

    @Test
    fun appUiNotesForCoversEveryProfileAndFailsClosedForUnknownPackages() {
        AppInteractionProfiles.profiles.flatMap { it.packageNames }.forEach { packageName ->
            assertFalse("no UI notes for $packageName", appUiNotesFor(packageName).isEmpty())
        }
        assertEquals(appUiNotesFor("com.android.settings"), appUiNotesFor("com.google.android.settings.intelligence"))
        assertTrue(appUiNotesFor(null).isEmpty())
        assertTrue(appUiNotesFor("").isEmpty())
        assertTrue(appUiNotesFor("com.example.unknown").isEmpty())
    }

    @Test
    fun minimalProfilesResolveByNameWithoutResultHintShortcut() {
        mapOf(
            "微信" to "com.tencent.mm",
            "快手" to "com.smile.gifmaker",
            "知乎" to "com.zhihu.android",
            "微博" to "com.sina.weibo",
            "闲鱼" to "com.taobao.idlefish",
            "今日头条" to "com.ss.android.article.news",
            "飞书" to "com.ss.android.lark",
        ).forEach { (appName, packageName) ->
            val profile = requireNotNull(AppInteractionProfiles.forAppName(appName)) { appName }
            assertEquals(appName, setOf(packageName), profile.packageNames)
            assertEquals(appName, profile, AppInteractionProfiles.forPackage(packageName))
            // Empty resultHints: verification for these apps needs the query on the result page.
            assertTrue(appName, profile.resultHints.isEmpty())
        }
    }

    @Test
    fun homeScreenResolvesSearchEntryAndSkipsScanAndFeed() {
        fixtures.forEach { fixture ->
            val evidence = UiTargetResolver.explain(homeScreen(fixture), UiTargetKind.SearchEntry, target = "搜索入口")

            assertNull("${fixture.appName}: ${evidence.rankedCandidates}", evidence.failureKind)
            assertEquals(fixture.appName, "search_entry", evidence.selectedNodeId)
            assertTrue(fixture.appName, evidence.rankedCandidates.none { it.nodeId == "scan_entry" })
            assertTrue(fixture.appName, evidence.rankedCandidates.none { it.nodeId == "home_feed" })
        }
    }

    @Test
    fun inputScreenResolvesEditableAndSubmitAndHasSearchContext() {
        fixtures.forEach { fixture ->
            val screen = inputScreen(fixture)

            val editable = UiTargetResolver.explain(screen, UiTargetKind.EditableField, target = "搜索输入框")
            val submit = UiTargetResolver.explain(screen, UiTargetKind.SubmitSearch, target = "提交搜索")

            assertEquals(fixture.appName, "search_edit_text", editable.selectedNodeId)
            assertEquals(fixture.appName, "search_submit", submit.selectedNodeId)
            assertTrue(fixture.appName, submit.rankedCandidates.none { it.nodeId == "voice_search" })
            assertTrue(fixture.appName, screen.hasSearchSubmitContext())
        }
    }

    @Test
    fun resultHintsVerifySearchWhenQueryOnlyStaysInTheSearchBox() {
        // Results whose rows do not repeat the query verbatim: only the app's result-page vocabulary
        // can prove the search landed. This is exactly the evidence the profile contributes.
        fixtures.filterNot { it.packageName == "com.android.settings" }.forEach { fixture ->
            val profile = requireNotNull(AppInteractionProfiles.forPackage(fixture.packageName))
            val hints = profile.resultHints.take(2)

            val verification = AppSearchResultVerifier.verify(
                before = inputScreen(fixture),
                after = resultsScreen(fixture, hints),
                query = fixture.query,
                expectedPackageName = fixture.packageName,
                expectedAppName = fixture.appName,
            )

            assertTrue("${fixture.appName}: ${verification.summary}", verification.verified)
            assertEquals(fixture.appName, "result_hints_visible", verification.evidence)
        }
    }

    @Test
    fun sameResultsScreenIsNotVerifiedWithoutTheProfile() {
        // Control for the test above: identical nodes under an unknown package fail closed, proving the
        // verification came from the profile and not from generic heuristics.
        fixtures.filterNot { it.packageName == "com.android.settings" }.forEach { fixture ->
            val hints = requireNotNull(AppInteractionProfiles.forPackage(fixture.packageName)).resultHints.take(2)
            val unknownPackage = "com.example.unknown"

            val verification = AppSearchResultVerifier.verify(
                before = inputScreen(fixture).copy(packageName = unknownPackage),
                after = resultsScreen(fixture, hints).copy(packageName = unknownPackage),
                query = fixture.query,
            )

            assertFalse(fixture.appName, verification.verified)
            assertEquals(UiActionFailureKind.ResultNotVerified, verification.failureKind)
        }
    }

    @Test
    fun homePageTilesAloneDoNotVerifyAnyNewApp() {
        // Home-page vocabulary that was deliberately kept out of resultHints must not verify a mere
        // page change (e.g. navigating to the home page instead of the results page).
        val homeTiles = mapOf(
            "ctrip.android.view" to listOf("酒店", "机票", "火车票", "攻略"),
            "tv.danmaku.bili" to listOf("直播", "影视", "推荐", "热门"),
            "com.netease.cloudmusic" to listOf("推荐歌单", "数字专辑", "播客", "排行榜"),
            "com.tencent.qqmusic" to listOf("歌手", "分类歌单", "数字专辑", "排行"),
            "me.ele" to listOf("月售1000+", "起送¥20", "配送约30分钟", "美食"),
        )
        homeTiles.forEach { (packageName, tiles) ->
            val fixture = fixtures.single { it.packageName == packageName }
            val verification = AppSearchResultVerifier.verify(
                before = inputScreen(fixture),
                after = snapshot(
                    packageName = packageName,
                    nodes = tiles.mapIndexed { index, tile ->
                        node(id = "tile_$index", text = tile, bounds = ScreenBounds(40 + index * 250, 400, 260 + index * 250, 600), clickable = true)
                    },
                ),
                query = fixture.query,
                expectedPackageName = packageName,
            )
            assertFalse("$packageName home tiles verified a search: ${verification.evidence}", verification.verified)
        }
    }

    @Test
    fun settingsIncrementalSearchVerifiesOnMatchingResultRow() {
        // Settings search is incremental: rows appear while typing and there is no results header, so
        // verification rests on a new non-editable row carrying the query.
        val fixture = fixtures.single { it.packageName == "com.android.settings" }
        val results = snapshot(
            packageName = "com.google.android.settings.intelligence",
            nodes = listOf(
                node(id = "search_edit_text", text = fixture.query, bounds = ScreenBounds(40, 100, 1040, 190), clickable = true, editable = true),
                node(id = "result_0", text = "蓝牙", bounds = ScreenBounds(0, 260, 1080, 380), clickable = true),
                node(id = "result_0_breadcrumb", text = "已连接的设备 > 连接偏好设置", bounds = ScreenBounds(0, 380, 1080, 430)),
            ),
        )

        val verification = AppSearchResultVerifier.verify(
            before = inputScreen(fixture),
            after = results,
            query = fixture.query,
            expectedAppName = fixture.appName,
        )

        assertTrue(verification.summary, verification.verified)
        assertEquals("query_visible_after_change", verification.evidence)
    }

    private fun homeScreen(fixture: AppFixture): ScreenStateSnapshot =
        snapshot(
            packageName = fixture.packageName,
            nodes = listOf(
                if (fixture.homeSearchIsIcon) {
                    node(
                        id = "search_entry",
                        contentDescription = fixture.homeSearchLabel,
                        className = "android.widget.ImageView",
                        bounds = ScreenBounds(960, 100, 1040, 180),
                        clickable = true,
                    )
                } else {
                    node(
                        id = "search_entry",
                        text = fixture.homeSearchLabel,
                        className = "android.widget.TextView",
                        bounds = ScreenBounds(40, 100, 820, 180),
                        clickable = true,
                    )
                },
                node(
                    id = "scan_entry",
                    contentDescription = "扫一扫",
                    className = "android.widget.ImageView",
                    bounds = ScreenBounds(840, 100, 940, 180),
                    clickable = true,
                ),
                node(
                    id = "home_feed",
                    text = "推荐 猜你喜欢 热门内容",
                    className = "androidx.recyclerview.widget.RecyclerView",
                    bounds = ScreenBounds(0, 300, 1080, 2200),
                    scrollable = true,
                ),
            ),
        )

    private fun inputScreen(fixture: AppFixture): ScreenStateSnapshot =
        snapshot(
            packageName = fixture.packageName,
            nodes = listOf(
                node(
                    id = "search_edit_text",
                    text = fixture.query,
                    contentDescription = fixture.inputHint,
                    className = "android.widget.EditText",
                    bounds = ScreenBounds(40, 100, 820, 190),
                    clickable = true,
                    editable = true,
                ),
                node(
                    id = "voice_search",
                    contentDescription = "语音搜索",
                    className = "android.widget.ImageView",
                    bounds = ScreenBounds(830, 100, 900, 190),
                    clickable = true,
                ),
                node(
                    id = "search_submit",
                    text = "搜索",
                    className = "android.widget.TextView",
                    bounds = ScreenBounds(920, 100, 1040, 190),
                    clickable = true,
                ),
                node(
                    id = "history_header",
                    text = "历史记录",
                    bounds = ScreenBounds(40, 240, 400, 300),
                ),
            ),
        )

    private fun resultsScreen(fixture: AppFixture, hints: List<String>): ScreenStateSnapshot =
        snapshot(
            packageName = fixture.packageName,
            nodes = listOf(
                node(
                    id = "search_edit_text",
                    text = fixture.query,
                    className = "android.widget.EditText",
                    bounds = ScreenBounds(40, 100, 900, 190),
                    clickable = true,
                    editable = true,
                ),
            ) + hints.mapIndexed { index, hint ->
                node(
                    id = "result_tab_$index",
                    text = hint,
                    bounds = ScreenBounds(40 + index * 200, 220, 220 + index * 200, 290),
                    clickable = true,
                )
            } + node(
                id = "result_row_0",
                text = "第一条结果 4.8分",
                bounds = ScreenBounds(0, 320, 1080, 600),
                clickable = true,
            ),
        )

    private fun snapshot(packageName: String, nodes: List<ScreenNode>): ScreenStateSnapshot =
        ScreenStateSnapshot(
            id = "synthetic-$packageName-${nodes.size}",
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
            enabled = true,
        )
}
