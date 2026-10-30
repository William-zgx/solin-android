package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.CurrentScreenTextActionParser
import com.bytedance.zgx.solin.action.looksLikeClipboardContextNonAction
import com.bytedance.zgx.solin.action.startsWithActionNegation
import com.bytedance.zgx.solin.tool.ToolRequest

/*
 * Intent matchers and slot extractors used by BuiltInSkillRuntime.plan(). Moved verbatim out of
 * BuiltInSkills.kt; `internal` only where the runtime needs them.
 */

internal data class DraftFormUiFill(
    val formTitle: String,
    val fieldTitle: String,
    val fieldTarget: String,
    val text: String,
)

internal data class OpenAppUiSearchRequest(
    val appName: String,
    val query: String,
)

internal sealed class CurrentPageUiCommand {
    data class Tap(val target: String) : CurrentPageUiCommand()
    data class Type(val text: String, val target: String?) : CurrentPageUiCommand()
    data class Scroll(val direction: String) : CurrentPageUiCommand()
    data object Back : CurrentPageUiCommand()
    data object Wait : CurrentPageUiCommand()
}

internal fun String.requestsCurrentScreenTextSummaryShare(): Boolean {
    val normalized = lowercase()
    if (looksLikeCurrentScreenTextSummaryShareNonAction(normalized)) return false
    val referencesCurrentScreen = CurrentScreenTextActionParser.referencesCurrentScreenText(this)
    val asksForSummary = listOf("总结", "摘要", "概括", "归纳").any { it in this } ||
        Regex("""\b(summarize|summary|brief|recap)\b""").containsMatchIn(normalized)
    val asksToShare = "分享" in this ||
        Regex("""\bshare\b""").containsMatchIn(normalized)
    return referencesCurrentScreen && asksForSummary && asksToShare
}

internal fun String.requestsCurrentScreenObservation(): Boolean {
    val normalized = lowercase()
    if (
        startsWithQuestionOpener(normalized) ||
        Regex("""^\s*(?:请|帮我|麻烦|麻烦你)?\s*(?:不想|不需要|不用|不必|不要|别|请勿|请不要|先别|暂时别|不)\s*""")
            .containsMatchIn(this) ||
        Regex("""^\s*(?:(?:please\s+)?(?:do\s+not|don't|dont|never)|i\s+(?:do\s+not|don't|dont)\s+want\s+to)\b""")
            .containsMatchIn(normalized)
    ) {
        return false
    }
    val referencesScreen = listOf("当前屏幕", "当前界面", "现在屏幕", "这个界面").any { it in this } ||
        Regex("""\b(?:current|active|this)\s+(?:screen|page|window|view)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    val asksObserve = listOf("观察", "查看", "看看", "读取状态", "屏幕状态").any { it in this } ||
        Regex("""\b(?:observe|inspect|read)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    return referencesScreen && asksObserve
}

internal fun String.requestsCurrentSettingsUiControl(): Boolean =
    extractCurrentSettingsUiTarget() != null

internal fun String.extractCurrentSettingsUiTarget(): String? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val normalized = lowercase()
    val referencesCurrentSettings = listOf("当前设置页", "当前设置页面", "当前系统设置", "设置页里", "设置页面里")
        .any { it in this } ||
        Regex("""\b(?:current|this)\s+(?:settings?|preferences?)\s+(?:screen|page)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    if (!referencesCurrentSettings) return null
    val target = replace(Regex("""^.*?(?:点击|点开|打开|进入|选择)\s*"""), "")
        .replace(Regex("""(?i)^.*?\b(?:tap|open|select|choose)\s+"""), "")
        .replace(Regex("""(?:这个)?(?:设置项|选项|入口|按钮)?\s*$"""), "")
        .replace(Regex("""(?i)\s+(?:option|entry|button)$"""), "")
        .trim()
    return target.takeIf { it.length in 1..80 }
}

internal fun String.requestsCurrentBrowserUiSearch(): Boolean =
    extractCurrentBrowserUiQuery() != null

internal fun String.extractCurrentBrowserUiQuery(): String? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val normalized = lowercase()
    val referencesCurrentBrowser = listOf("当前浏览器", "当前网页", "浏览器页面", "网页页面")
        .any { it in this } ||
        Regex("""\b(?:current|this)\s+(?:browser|web\s+page|webpage)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    val asksSearch = listOf("搜索", "查询", "搜", "查", "输入").any { it in this } ||
        Regex("""\b(?:search|look\s+up|type)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    if (!referencesCurrentBrowser || !asksSearch) return null
    val query = replace(Regex("""^.*?(?:搜索|查询|搜|查|输入)\s*[:：]?"""), "")
        .replace(Regex("""(?i)^.*?\b(?:search|look\s+up|type)\s+(?:for\s+)?"""), "")
        .trim()
    return query.takeIf { it.length in 1..120 }
}

internal fun String.requestsCurrentMapsUiRoute(): Boolean =
    extractCurrentMapsUiQuery() != null

internal fun String.extractCurrentMapsUiQuery(): String? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val normalized = lowercase()
    val referencesCurrentMaps = listOf("当前地图", "地图页面", "地图里", "地图内").any { it in this } ||
        Regex("""\b(?:current|this)\s+maps?\b|\bmaps?\s+(?:screen|page)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    val asksRouteOrSearch = listOf("路线", "导航", "搜索", "查", "输入").any { it in this } ||
        Regex("""\b(?:route|directions?|navigate|search|type)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    if (!referencesCurrentMaps || !asksRouteOrSearch) return null
    val query = replace(Regex("""^.*?(?:查(?:去|到)?|搜索|导航(?:到|去)?|输入)\s*[:：]?"""), "")
        .replace(Regex("""(?i)^.*?\b(?:route|directions?\s+to|navigate\s+to|search|type)\s+"""), "")
        .replace(Regex("""(?:的)?路线\s*$"""), "")
        .trim()
    return query.takeIf { it.length in 1..120 }
}

internal fun String.requestsCurrentAppUiSearch(): Boolean =
    extractCurrentAppUiSearchQuery() != null

internal fun String.extractCurrentAppUiSearchQuery(): String? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val normalized = lowercase()
    val referencesCurrentApp = listOf(
        "当前应用",
        "当前 app",
        "当前app",
        "当前页面",
        "当前页",
        "当前界面",
        "这个应用",
        "这个页面",
        "这个界面",
        "应用里",
        "app 里",
        "app里",
    ).any { it in this } ||
        Regex("""\b(?:current|this)\s+(?:app|application|page|screen)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    val asksSearch = containsSearchVerb()
    if (!referencesCurrentApp || !asksSearch) return null
    return extractQueryAfterSearchVerb()
}

internal fun String.requestsOpenAppThenUiSearch(): Boolean =
    extractOpenAppUiSearchRequest() != null

internal fun String.extractOpenAppUiSearchRequest(): OpenAppUiSearchRequest? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val trimmed = trim()
    val chineseMatch = Regex(
        """^\s*(?:请|帮我|麻烦|麻烦你)?\s*(?:打开|启动|进入)\s*(.+?)\s*(?:后|之后|然后|接着|并|，|,|\s)*\s*(?:搜索|搜一下|搜|查询|查找|查)\s*[:：]?\s*(.+?)\s*[。.!！]?\s*$""",
    ).find(trimmed)
    if (chineseMatch != null) {
        val appName = chineseMatch.groupValues[1].cleanAppSearchSlot()
        val query = chineseMatch.groupValues[2].cleanSearchQuerySlot()
        if (appName != null && query != null) return OpenAppUiSearchRequest(appName, query)
    }

    val englishMatch = Regex(
        """^\s*(?:please\s+)?(?:open|launch|start)\s+(?:the\s+)?(.+?)\s+(?:(?:and\s+then|then|after\s+that|and)\s+)?(?:search|look\s+up|find)\s+(?:for\s+)?(.+?)\s*$""",
        RegexOption.IGNORE_CASE,
    ).find(trimmed)
    if (englishMatch != null) {
        val appName = englishMatch.groupValues[1].cleanAppSearchSlot()
        val query = englishMatch.groupValues[2].cleanSearchQuerySlot()
        if (appName != null && query != null) return OpenAppUiSearchRequest(appName, query)
    }
    return null
}

internal fun String.requestsCurrentPageSimpleInteraction(): Boolean =
    extractCurrentPageUiCommand() != null

internal fun String.extractCurrentPageUiCommand(): CurrentPageUiCommand? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val normalized = lowercase()
    val referencesCurrentPage = listOf(
        "当前页面",
        "当前页",
        "当前界面",
        "当前屏幕",
        "这个页面",
        "这个界面",
        "这个屏幕",
        "当前应用",
        "当前 app",
        "当前app",
    ).any { it in this } ||
        Regex("""\b(?:current|this)\s+(?:page|screen|app|application)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    val asksBack = Regex("""\b(?:back|go\s+back)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ||
        listOf("返回", "后退").any { it in this }
    if (!referencesCurrentPage && !asksBack) return null

    if (asksBack) {
        return CurrentPageUiCommand.Back
    }
    if (Regex("""\bwait\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ||
        listOf("等一下", "等待", "稍等").any { it in this }
    ) {
        return CurrentPageUiCommand.Wait
    }
    if (Regex("""\bscroll\s+(down|up|left|right|forward|backward)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized) ||
        listOf("滚动", "滑动", "往下翻", "往上翻", "下滑", "上滑").any { it in this }
    ) {
        return CurrentPageUiCommand.Scroll(direction = scrollDirectionForCurrentPageCommand())
    }

    extractCurrentPageTypeCommand()?.let { return it }
    if (hasCurrentPageTapIntent()) {
        extractCurrentPageTapTarget()?.let { target -> return CurrentPageUiCommand.Tap(target) }
    }
    return null
}

private fun String.hasCurrentPageTapIntent(): Boolean {
    val normalized = lowercase()
    return listOf("点击", "点开", "点一下", "选择", "打开", "进入", "筛选").any { it in this } ||
        Regex("""\b(?:tap|click|select|choose|open|filter)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
}

private fun String.extractCurrentPageTapTarget(): String? {
    val target = replace(Regex("""^.*?(?:点击|点开|点一下|选择|打开|进入|筛选)\s*"""), "")
        .replace(Regex("""(?i)^.*?\b(?:tap|click|select|choose|open|filter)\s+"""), "")
        .stripCurrentPageReference()
        .replace(Regex("""^\s*(?:的|上|里|内|中)\s*"""), "")
        .replace(Regex("""(?:这个)?(?:按钮|入口|选项|筛选项|标签)?\s*$"""), "")
        .replace(Regex("""(?i)\s+(?:button|entry|option|filter|tab)$"""), "")
        .cleanUiTargetSlot()
    return target?.takeIf { it.length <= 80 && it.hasActionableUiTarget() }
}

private fun String.hasActionableUiTarget(): Boolean =
    this !in setOf("当前页面", "当前页", "当前界面", "当前屏幕", "当前应用", "这个页面", "这个界面", "这个屏幕") &&
        !contains(Regex("""(?:是什么|什么意思|怎么|如何|说明|解释|总结|摘要|识别|读取|文字|文本|截图|通知|前台应用|当前应用是什么)"""))

private fun String.extractCurrentPageTypeCommand(): CurrentPageUiCommand.Type? {
    val chineseMatch = Regex("""^.*?(?:输入|填写)\s*[:：]?\s*(.+?)\s*$""").find(this)
    if (chineseMatch != null) {
        val text = chineseMatch.groupValues[1].cleanSearchQuerySlot() ?: return null
        return CurrentPageUiCommand.Type(text = text, target = null)
    }
    val englishMatch = Regex("""^.*?\b(?:type|enter|fill)\s+(.+?)\s*$""", RegexOption.IGNORE_CASE).find(this)
    if (englishMatch != null) {
        val text = englishMatch.groupValues[1].cleanSearchQuerySlot() ?: return null
        return CurrentPageUiCommand.Type(text = text, target = null)
    }
    return null
}

private fun String.stripCurrentPageReference(): String =
    replace(
        Regex("""^\s*(?:当前页面|当前页|当前界面|当前屏幕|这个页面|这个界面|这个屏幕|当前应用|当前\s*app)(?:上|里|内|中|的|上的|里的|中的)?\s*"""),
        "",
    )

private fun String.scrollDirectionForCurrentPageCommand(): String {
    val normalized = lowercase()
    return when {
        "往上" in this || "上滑" in this || "向上" in this ||
            Regex("""\b(up|backward)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) -> "up"
        "向左" in this || Regex("""\bleft\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) -> "left"
        "向右" in this || Regex("""\bright\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) -> "right"
        Regex("""\bforward\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) -> "forward"
        else -> "down"
    }
}

private fun String.containsSearchVerb(): Boolean {
    val normalized = lowercase()
    return listOf("搜索", "搜一下", "搜", "查询", "查找", "查").any { it in this } ||
        Regex("""\b(?:search|look\s+up|find)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
}

private fun String.extractQueryAfterSearchVerb(): String? {
    val query = replace(Regex("""^.*?(?:搜索|搜一下|搜|查询|查找|查)\s*[:：]?"""), "")
        .replace(Regex("""(?i)^.*?\b(?:search|look\s+up|find)\s+(?:for\s+)?"""), "")
        .cleanSearchQuerySlot()
    return query?.takeIf { it.length <= 120 }
}

private fun String.cleanAppSearchSlot(): String? =
    trim()
        .removePrefix("应用")
        .removePrefix("app")
        .trim()
        .trimEnd('。', '.', '！', '!', '，', ',')
        .takeIf { candidate ->
            candidate.length in 1..80 &&
                candidate !in setOf("应用", "app", "应用程序", "软件") &&
                !candidate.contains(Regex("""[/:\\]""")) &&
                !candidate.looksLikeSystemOrWebTarget()
        }

private fun String.cleanSearchQuerySlot(): String? =
    trim()
        .trim('"', '\'', '“', '”', '‘', '’')
        .trimEnd('。', '.', '！', '!', '，', ',')
        .trim()
        .takeIf { candidate ->
            candidate.isNotBlank() &&
                candidate.length <= 120 &&
                !candidate.contains(Regex("""^\s*(?:搜索|查询|查找|筛选)\s*$"""))
        }

private fun String.cleanUiTargetSlot(): String? =
    trim()
        .trim('"', '\'', '“', '”', '‘', '’')
        .trimEnd('。', '.', '！', '!', '，', ',')
        .trim()
        .takeIf { candidate ->
            candidate.isNotBlank() &&
                candidate.length <= 120
        }

private fun String.looksLikeSystemOrWebTarget(): Boolean {
    val normalized = lowercase()
    return listOf("设置", "权限", "网页", "网站", "链接", "网址").any { it in this } ||
        Regex("""\b(?:wi[-\s]?fi|wifi|wlan|settings?|preferences?|permission|web\s*page|website|link|url)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
}

internal fun String.requestsCurrentDraftFormUiFill(): Boolean =
    extractCurrentDraftFormUiFill() != null

internal fun String.extractCurrentDraftFormUiFill(): DraftFormUiFill? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val normalized = lowercase()
    val isEmail = listOf("当前邮件表单", "当前邮件页面", "当前邮件草稿", "邮件草稿页").any { it in this } ||
        Regex("""\b(?:current|this)\s+(?:email|mail)\s+(?:draft|form|compose)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    val isCalendar = listOf("当前日历表单", "当前日程页面", "当前日程草稿", "日历草稿页").any { it in this } ||
        Regex("""\b(?:current|this)\s+(?:calendar|event)\s+(?:draft|form)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    if (!isEmail && !isCalendar) return null
    val asksFill = listOf("填写", "填入", "输入", "写入").any { it in this } ||
        Regex("""\b(?:fill|type|enter)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    if (!asksFill) return null
    val text = replace(Regex("""^.*?(?:填写|填入|输入|写入)\s*[:：]?"""), "")
        .replace(Regex("""(?i)^.*?\b(?:fill|type|enter)\s+"""), "")
        .trim()
    if (text.isBlank() || text.length > 500) return null
    return if (isEmail) {
        DraftFormUiFill(
            formTitle = "邮件",
            fieldTitle = "正文",
            fieldTarget = "正文",
            text = text,
        )
    } else {
        DraftFormUiFill(
            formTitle = "日历",
            fieldTitle = "标题",
            fieldTarget = "标题",
            text = text,
        )
    }
}

/** "How do I…" / "请问…" style openers: the user is asking about something, not requesting it. */
private fun String.startsWithQuestionOpener(normalized: String): Boolean =
    Regex("""^\s*(?:请问|问一下|如何|怎么|怎样|为什么|解释|说明|介绍)""").containsMatchIn(this) ||
        Regex("""^\s*(?:how\s+(?:do|can|to)|what\s+is|explain)\b""").containsMatchIn(normalized)

private fun String.looksLikeUiControlDiscussion(): Boolean {
    val normalized = lowercase()
    return startsWithQuestionOpener(normalized) ||
        listOf(" API", "api", "接口", "怎么实现", "如何实现", "怎么设计").any { it in this }
}

internal fun String.looksLikeCurrentScreenTextSummaryShareNonAction(normalized: String): Boolean =
    startsWithQuestionOpener(normalized) &&
        CurrentScreenTextActionParser.referencesCurrentScreenText(this) &&
        ("分享" in this || Regex("""\bshare\b""").containsMatchIn(normalized)) ||
        Regex("""^\s*(?:请|帮我|麻烦|麻烦你)?\s*(?:不想|不需要|不用|不必|不要|别|请勿|请不要|先别|暂时别|不).*(?:总结|摘要|概括|归纳).*(?:当前屏幕|当前界面|现在屏幕|屏幕内容|屏幕文字|屏幕文本|这个界面).*(?:分享)""")
        .containsMatchIn(this) ||
        Regex("""^\s*(?:请问|问一下|如何|怎么|怎样|为什么|解释|说明|介绍).*(?:总结|摘要|概括|归纳).*(?:当前屏幕|当前界面|现在屏幕|屏幕内容|屏幕文字|屏幕文本|这个界面).*(?:分享)""")
            .containsMatchIn(this) ||
        Regex("""^\s*(?:(?:please\s+)?(?:do\s+not|don't|dont|never)|i\s+(?:do\s+not|don't|dont)\s+want\s+to)\b.*\b(?:summarize|summary|brief|recap)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view)\b.*\bshare\b""")
            .containsMatchIn(normalized) ||
        Regex("""^\s*(?:how\s+(?:do|can|to)|what\s+is|explain)\b.*\b(?:summarize|summary|brief|recap)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view)\b.*\bshare\b""")
            .containsMatchIn(normalized)

internal fun String.requestsClipboardSummaryShare(): Boolean {
    val normalized = lowercase()
    if (looksLikeClipboardSummaryShareNonAction(normalized)) return false
    val referencesClipboard = "剪贴板" in this || "clipboard" in normalized
    val asksForSummary = listOf("总结", "摘要", "概括", "归纳").any { it in this } ||
        Regex("""\b(summarize|summary|brief|recap)\b""").containsMatchIn(normalized)
    val asksToShare = "分享" in this ||
        Regex("""\bshare\b""").containsMatchIn(normalized)
    return referencesClipboard && asksForSummary && asksToShare
}

private fun String.looksLikeClipboardSummaryShareNonAction(normalized: String): Boolean =
    Regex("""^\s*(?:请|帮我|麻烦|麻烦你)?\s*(?:不想|不需要|不用|不必|不要|别|请勿|请不要|先别|暂时别|不).*(?:总结|摘要|概括|归纳).*(?:剪贴板).*(?:分享)""")
        .containsMatchIn(this) ||
        Regex("""^\s*(?:请问|问一下|如何|怎么|怎样|为什么|解释|说明|介绍).*(?:总结|摘要|概括|归纳).*(?:剪贴板).*(?:分享)""")
            .containsMatchIn(this) ||
        Regex("""^\s*(?:(?:please\s+)?(?:do\s+not|don't|dont|never)|i\s+(?:do\s+not|don't|dont)\s+want\s+to)\b.*\b(?:summarize|summary|brief|recap)\b.*\bclipboard\b.*\bshare\b""")
            .containsMatchIn(normalized) ||
        Regex("""^\s*(?:how\s+(?:do|can|to)|what\s+is|explain)\b.*\b(?:summarize|summary|brief|recap)\b.*\bclipboard\b.*\bshare\b""")
            .containsMatchIn(normalized)

internal fun String.requestsClipboardContext(): Boolean {
    val normalized = lowercase()
    if (looksLikeClipboardContextNonAction()) return false
    val referencesClipboard = "剪贴板" in this || "clipboard" in normalized
    val asksToRead = listOf("读取", "读一下", "看看", "查看", "总结", "摘要", "概括").any { it in this } ||
        Regex("""\b(read|summarize|summary|recap)\b""").containsMatchIn(normalized)
    val asksToShare = "分享" in this || Regex("""\bshare\b""").containsMatchIn(normalized)
    return referencesClipboard && asksToRead && !asksToShare
}

internal data class DraftRequestPair(
    val draft: ActionDraft,
    val request: ToolRequest,
)

internal fun ActionDraft.toRequestPair(): DraftRequestPair =
    DraftRequestPair(
        draft = this,
        request = ToolRequest(
            toolName = functionName,
            arguments = parameters,
            reason = summary,
        ),
    )
