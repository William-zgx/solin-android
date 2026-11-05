package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.CurrentScreenTextActionParser
import com.bytedance.zgx.solin.action.looksLikeClipboardContextNonAction
import com.bytedance.zgx.solin.action.startsWithActionNegation
import com.bytedance.zgx.solin.tool.ToolRequest

/*
 * Intent matchers and slot extractors used by BuiltInSkillRuntime.plan(); `internal` only where
 * the runtime needs them.
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

private val SUMMARY_WORD_REGEX = Regex("""\b(summarize|summary|brief|recap)\b""")

private val SHARE_REGEX = Regex("""\bshare\b""")

internal fun String.requestsCurrentScreenTextSummaryShare(): Boolean {
    val normalized = lowercase()
    if (looksLikeCurrentScreenTextSummaryShareNonAction(normalized)) return false
    val referencesCurrentScreen = CurrentScreenTextActionParser.referencesCurrentScreenText(this)
    val asksForSummary = listOf("总结", "摘要", "概括", "归纳").any { it in this } ||
        SUMMARY_WORD_REGEX.containsMatchIn(normalized)
    val asksToShare = "分享" in this ||
        SHARE_REGEX.containsMatchIn(normalized)
    return referencesCurrentScreen && asksForSummary && asksToShare
}

private val CURRENT_SCREEN_OBSERVATION_ZH1_REGEX =
    Regex("""^\s*(?:请|帮我|麻烦|麻烦你)?\s*(?:不想|不需要|不用|不必|不要|别|请勿|请不要|先别|暂时别|不)\s*""")

private val CURRENT_SCREEN_OBSERVATION_PLEASE_DO_REGEX =
    Regex("""^\s*(?:(?:please\s+)?(?:do\s+not|don't|dont|never)|i\s+(?:do\s+not|don't|dont)\s+want\s+to)\b""")

private val CURRENT_SCREEN_OBSERVATION_CURRENT_ACTIVE_REGEX =
    Regex("""\b(?:current|active|this)\s+(?:screen|page|window|view)\b""", RegexOption.IGNORE_CASE)

private val CURRENT_SCREEN_OBSERVATION_OBSERVE_INSPECT_REGEX =
    Regex("""\b(?:observe|inspect|read)\b""", RegexOption.IGNORE_CASE)

internal fun String.requestsCurrentScreenObservation(): Boolean {
    val normalized = lowercase()
    if (
        startsWithQuestionOpener(normalized) ||
        CURRENT_SCREEN_OBSERVATION_ZH1_REGEX.containsMatchIn(this) ||
        CURRENT_SCREEN_OBSERVATION_PLEASE_DO_REGEX.containsMatchIn(normalized)
    ) {
        return false
    }
    val referencesScreen = listOf("当前屏幕", "当前界面", "现在屏幕", "这个界面").any { it in this } ||
        CURRENT_SCREEN_OBSERVATION_CURRENT_ACTIVE_REGEX.containsMatchIn(normalized)
    val asksObserve = listOf("观察", "查看", "看看", "读取状态", "屏幕状态").any { it in this } ||
        CURRENT_SCREEN_OBSERVATION_OBSERVE_INSPECT_REGEX.containsMatchIn(normalized)
    return referencesScreen && asksObserve
}

internal fun String.requestsCurrentSettingsUiControl(): Boolean =
    extractCurrentSettingsUiTarget() != null

private val EXTRACT_CURRENT_SETTINGS_UI_TARGET_CURRENT_THIS_REGEX =
    Regex("""\b(?:current|this)\s+(?:settings?|preferences?)\s+(?:screen|page)\b""", RegexOption.IGNORE_CASE)

private val EXTRACT_CURRENT_SETTINGS_UI_TARGET_ZH1_REGEX = Regex("""^.*?(?:点击|点开|打开|进入|选择)\s*""")

private val EXTRACT_CURRENT_SETTINGS_UI_TARGET_TAP_OPEN_REGEX =
    Regex("""(?i)^.*?\b(?:tap|open|select|choose)\s+""")

private val EXTRACT_CURRENT_SETTINGS_UI_TARGET_ZH2_REGEX = Regex("""(?:这个)?(?:设置项|选项|入口|按钮)?\s*$""")

private val EXTRACT_CURRENT_SETTINGS_UI_TARGET_OPTION_ENTRY_REGEX =
    Regex("""(?i)\s+(?:option|entry|button)$""")

internal fun String.extractCurrentSettingsUiTarget(): String? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val normalized = lowercase()
    val referencesCurrentSettings = listOf("当前设置页", "当前设置页面", "当前系统设置", "设置页里", "设置页面里")
        .any { it in this } ||
        EXTRACT_CURRENT_SETTINGS_UI_TARGET_CURRENT_THIS_REGEX.containsMatchIn(normalized)
    if (!referencesCurrentSettings) return null
    val target = replace(EXTRACT_CURRENT_SETTINGS_UI_TARGET_ZH1_REGEX, "")
        .replace(EXTRACT_CURRENT_SETTINGS_UI_TARGET_TAP_OPEN_REGEX, "")
        .replace(EXTRACT_CURRENT_SETTINGS_UI_TARGET_ZH2_REGEX, "")
        .replace(EXTRACT_CURRENT_SETTINGS_UI_TARGET_OPTION_ENTRY_REGEX, "")
        .trim()
    return target.takeIf { it.length in 1..80 }
}

internal fun String.requestsCurrentBrowserUiSearch(): Boolean =
    extractCurrentBrowserUiQuery() != null

private val EXTRACT_CURRENT_BROWSER_UI_QUERY_CURRENT_THIS_REGEX =
    Regex("""\b(?:current|this)\s+(?:browser|web\s+page|webpage)\b""", RegexOption.IGNORE_CASE)

private val EXTRACT_CURRENT_BROWSER_UI_QUERY_SEARCH_LOOK_REGEX =
    Regex("""\b(?:search|look\s+up|type)\b""", RegexOption.IGNORE_CASE)

private val EXTRACT_CURRENT_BROWSER_UI_QUERY_ZH1_REGEX = Regex("""^.*?(?:搜索|查询|搜|查|输入)\s*[:：]?""")

private val EXTRACT_CURRENT_BROWSER_UI_QUERY_SEARCH_LOOK_2_REGEX =
    Regex("""(?i)^.*?\b(?:search|look\s+up|type)\s+(?:for\s+)?""")

internal fun String.extractCurrentBrowserUiQuery(): String? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val normalized = lowercase()
    val referencesCurrentBrowser = listOf("当前浏览器", "当前网页", "浏览器页面", "网页页面")
        .any { it in this } ||
        EXTRACT_CURRENT_BROWSER_UI_QUERY_CURRENT_THIS_REGEX.containsMatchIn(normalized)
    val asksSearch = listOf("搜索", "查询", "搜", "查", "输入").any { it in this } ||
        EXTRACT_CURRENT_BROWSER_UI_QUERY_SEARCH_LOOK_REGEX.containsMatchIn(normalized)
    if (!referencesCurrentBrowser || !asksSearch) return null
    val query = replace(EXTRACT_CURRENT_BROWSER_UI_QUERY_ZH1_REGEX, "")
        .replace(EXTRACT_CURRENT_BROWSER_UI_QUERY_SEARCH_LOOK_2_REGEX, "")
        .trim()
    return query.takeIf { it.length in 1..120 }
}

internal fun String.requestsCurrentMapsUiRoute(): Boolean =
    extractCurrentMapsUiQuery() != null

private val EXTRACT_CURRENT_MAPS_UI_QUERY_CURRENT_THIS_REGEX =
    Regex("""\b(?:current|this)\s+maps?\b|\bmaps?\s+(?:screen|page)\b""", RegexOption.IGNORE_CASE)

private val EXTRACT_CURRENT_MAPS_UI_QUERY_ROUTE_DIRECTIONS_REGEX =
    Regex("""\b(?:route|directions?|navigate|search|type)\b""", RegexOption.IGNORE_CASE)

private val EXTRACT_CURRENT_MAPS_UI_QUERY_ZH1_REGEX =
    Regex("""^.*?(?:查(?:去|到)?|搜索|导航(?:到|去)?|输入)\s*[:：]?""")

private val EXTRACT_CURRENT_MAPS_UI_QUERY_ROUTE_DIRECTIONS_2_REGEX =
    Regex("""(?i)^.*?\b(?:route|directions?\s+to|navigate\s+to|search|type)\s+""")

private val EXTRACT_CURRENT_MAPS_UI_QUERY_ZH2_REGEX = Regex("""(?:的)?路线\s*$""")

internal fun String.extractCurrentMapsUiQuery(): String? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val normalized = lowercase()
    val referencesCurrentMaps = listOf("当前地图", "地图页面", "地图里", "地图内").any { it in this } ||
        EXTRACT_CURRENT_MAPS_UI_QUERY_CURRENT_THIS_REGEX.containsMatchIn(normalized)
    val asksRouteOrSearch = listOf("路线", "导航", "搜索", "查", "输入").any { it in this } ||
        EXTRACT_CURRENT_MAPS_UI_QUERY_ROUTE_DIRECTIONS_REGEX.containsMatchIn(normalized)
    if (!referencesCurrentMaps || !asksRouteOrSearch) return null
    val query = replace(EXTRACT_CURRENT_MAPS_UI_QUERY_ZH1_REGEX, "")
        .replace(EXTRACT_CURRENT_MAPS_UI_QUERY_ROUTE_DIRECTIONS_2_REGEX, "")
        .replace(EXTRACT_CURRENT_MAPS_UI_QUERY_ZH2_REGEX, "")
        .trim()
    return query.takeIf { it.length in 1..120 }
}

internal fun String.requestsCurrentAppUiSearch(): Boolean =
    extractCurrentAppUiSearchQuery() != null

private val EXTRACT_CURRENT_APP_UI_SEARCH_QUERY_REGEX =
    Regex("""\b(?:current|this)\s+(?:app|application|page|screen)\b""", RegexOption.IGNORE_CASE)

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
        EXTRACT_CURRENT_APP_UI_SEARCH_QUERY_REGEX.containsMatchIn(normalized)
    val asksSearch = containsSearchVerb()
    if (!referencesCurrentApp || !asksSearch) return null
    return extractQueryAfterSearchVerb()
}

internal fun String.requestsOpenAppThenUiSearch(): Boolean =
    extractOpenAppUiSearchRequest() != null

private val EXTRACT_OPEN_APP_UI_SEARCH_REQUEST_ZH1_REGEX = Regex(
    """^\s*(?:请|帮我|麻烦|麻烦你)?\s*(?:打开|启动|进入)\s*(.+?)\s*(?:后|之后|然后|接着|并|，|,|\s)*\s*(?:搜索|搜一下|搜|查询|查找|查)\s*[:：]?\s*(.+?)\s*[。.!！]?\s*$""",
)

private val EXTRACT_OPEN_APP_UI_SEARCH_REQUEST_PLEASE_OPEN_REGEX = Regex(
    """^\s*(?:please\s+)?(?:open|launch|start)\s+(?:the\s+)?(.+?)\s+(?:(?:and\s+then|then|after\s+that|and)\s+)?(?:search|look\s+up|find)\s+(?:for\s+)?(.+?)\s*$""",
    RegexOption.IGNORE_CASE,
)

internal fun String.extractOpenAppUiSearchRequest(): OpenAppUiSearchRequest? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val trimmed = trim()
    val chineseMatch = EXTRACT_OPEN_APP_UI_SEARCH_REQUEST_ZH1_REGEX.find(trimmed)
    if (chineseMatch != null) {
        val appName = chineseMatch.groupValues[1].cleanAppSearchSlot()
        val query = chineseMatch.groupValues[2].cleanSearchQuerySlot()
        if (appName != null && query != null) return OpenAppUiSearchRequest(appName, query)
    }

    val englishMatch = EXTRACT_OPEN_APP_UI_SEARCH_REQUEST_PLEASE_OPEN_REGEX.find(trimmed)
    if (englishMatch != null) {
        val appName = englishMatch.groupValues[1].cleanAppSearchSlot()
        val query = englishMatch.groupValues[2].cleanSearchQuerySlot()
        if (appName != null && query != null) return OpenAppUiSearchRequest(appName, query)
    }
    return null
}

internal fun String.requestsCurrentPageSimpleInteraction(): Boolean =
    extractCurrentPageUiCommand() != null

private val EXTRACT_CURRENT_PAGE_UI_COMMAND_CURRENT_THIS_REGEX =
    Regex("""\b(?:current|this)\s+(?:page|screen|app|application)\b""", RegexOption.IGNORE_CASE)

private val EXTRACT_CURRENT_PAGE_UI_COMMAND_WAIT_REGEX =
    Regex("""\bwait\b""", RegexOption.IGNORE_CASE)

private val EXTRACT_CURRENT_PAGE_UI_COMMAND_SCROLL_DOWN_REGEX =
    Regex("""\bscroll\s+(down|up|left|right|forward|backward)\b""", RegexOption.IGNORE_CASE)

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
        EXTRACT_CURRENT_PAGE_UI_COMMAND_CURRENT_THIS_REGEX.containsMatchIn(normalized)
    // A back word ANYWHERE used to be enough, which turned questions like "这个函数的返回值是什么"
    // and "back up my photos" into a ui_press_back. A back intent now needs either an explicit
    // reference to the current page or an input that is nothing but a back command.
    val asksBack = (referencesCurrentPage && mentionsBackWord()) || isWholeInputBackCommand()
    if (!referencesCurrentPage && !asksBack) return null

    if (asksBack) {
        return CurrentPageUiCommand.Back
    }
    if (EXTRACT_CURRENT_PAGE_UI_COMMAND_WAIT_REGEX.containsMatchIn(normalized) ||
        listOf("等一下", "等待", "稍等").any { it in this }
    ) {
        return CurrentPageUiCommand.Wait
    }
    if (EXTRACT_CURRENT_PAGE_UI_COMMAND_SCROLL_DOWN_REGEX.containsMatchIn(normalized) ||
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

private val MENTIONS_BACK_WORD_REGEX = Regex("""\b(?:back|go\s+back)\b""", RegexOption.IGNORE_CASE)

private fun String.mentionsBackWord(): Boolean {
    val normalized = lowercase()
    return MENTIONS_BACK_WORD_REGEX.containsMatchIn(normalized) ||
        listOf("返回", "后退").any { it in this }
}

private val IS_WHOLE_INPUT_BACK_COMMAND_REGEX =
    Regex("""^\s*(?:请|帮我)?\s*(?:返回(?:上一页|上一级)?|后退|go\s+back|back)\s*[。.!！]?\s*$""")

/**
 * True only when the WHOLE input is a back command, e.g. "返回", "请返回上一页", "go back".
 *
 * Deliberately anchored: a bare "返回" also appears inside ordinary sentences ("这个函数的返回值
 * 是什么") and "back" inside "back up my photos", and a ui_press_back for those is wrong.
 */
private fun String.isWholeInputBackCommand(): Boolean =
    IS_WHOLE_INPUT_BACK_COMMAND_REGEX.containsMatchIn(this)

private val HAS_CURRENT_PAGE_TAP_INTENT_REGEX =
    Regex("""\b(?:tap|click|select|choose|open|filter)\b""", RegexOption.IGNORE_CASE)

private fun String.hasCurrentPageTapIntent(): Boolean {
    val normalized = lowercase()
    return listOf("点击", "点开", "点一下", "选择", "打开", "进入", "筛选").any { it in this } ||
        HAS_CURRENT_PAGE_TAP_INTENT_REGEX.containsMatchIn(normalized)
}

private val EXTRACT_CURRENT_PAGE_TAP_TARGET_ZH1_REGEX =
    Regex("""^.*?(?:点击|点开|点一下|选择|打开|进入|筛选)\s*""")

private val EXTRACT_CURRENT_PAGE_TAP_TARGET_TAP_CLICK_REGEX =
    Regex("""(?i)^.*?\b(?:tap|click|select|choose|open|filter)\s+""")

private val EXTRACT_CURRENT_PAGE_TAP_TARGET_ZH2_REGEX = Regex("""^\s*(?:的|上|里|内|中)\s*""")

private val EXTRACT_CURRENT_PAGE_TAP_TARGET_ZH3_REGEX = Regex("""(?:这个)?(?:按钮|入口|选项|筛选项|标签)?\s*$""")

private val EXTRACT_CURRENT_PAGE_TAP_TARGET_BUTTON_ENTRY_REGEX =
    Regex("""(?i)\s+(?:button|entry|option|filter|tab)$""")

private fun String.extractCurrentPageTapTarget(): String? {
    val target = replace(EXTRACT_CURRENT_PAGE_TAP_TARGET_ZH1_REGEX, "")
        .replace(EXTRACT_CURRENT_PAGE_TAP_TARGET_TAP_CLICK_REGEX, "")
        .stripCurrentPageReference()
        .replace(EXTRACT_CURRENT_PAGE_TAP_TARGET_ZH2_REGEX, "")
        .replace(EXTRACT_CURRENT_PAGE_TAP_TARGET_ZH3_REGEX, "")
        .replace(EXTRACT_CURRENT_PAGE_TAP_TARGET_BUTTON_ENTRY_REGEX, "")
        .cleanUiTargetSlot()
    return target?.takeIf { it.length <= 80 && it.hasActionableUiTarget() }
}

private val HAS_ACTIONABLE_UI_TARGET_REGEX =
    Regex("""(?:是什么|什么意思|怎么|如何|说明|解释|总结|摘要|识别|读取|文字|文本|截图|通知|前台应用|当前应用是什么)""")

private fun String.hasActionableUiTarget(): Boolean =
    this !in setOf("当前页面", "当前页", "当前界面", "当前屏幕", "当前应用", "这个页面", "这个界面", "这个屏幕") &&
        !contains(HAS_ACTIONABLE_UI_TARGET_REGEX)

private val EXTRACT_CURRENT_PAGE_TYPE_COMMAND_ZH1_REGEX =
    Regex("""^.*?(?:输入|填写)\s*[:：]?\s*(.+?)\s*$""")

private val EXTRACT_CURRENT_PAGE_TYPE_COMMAND_TYPE_ENTER_REGEX =
    Regex("""^.*?\b(?:type|enter|fill)\s+(.+?)\s*$""", RegexOption.IGNORE_CASE)

private fun String.extractCurrentPageTypeCommand(): CurrentPageUiCommand.Type? {
    val chineseMatch = EXTRACT_CURRENT_PAGE_TYPE_COMMAND_ZH1_REGEX.find(this)
    if (chineseMatch != null) {
        val text = chineseMatch.groupValues[1].cleanSearchQuerySlot() ?: return null
        return CurrentPageUiCommand.Type(text = text, target = null)
    }
    val englishMatch = EXTRACT_CURRENT_PAGE_TYPE_COMMAND_TYPE_ENTER_REGEX.find(this)
    if (englishMatch != null) {
        val text = englishMatch.groupValues[1].cleanSearchQuerySlot() ?: return null
        return CurrentPageUiCommand.Type(text = text, target = null)
    }
    return null
}

private val STRIP_CURRENT_PAGE_REFERENCE_REGEX =
    Regex("""^\s*(?:当前页面|当前页|当前界面|当前屏幕|这个页面|这个界面|这个屏幕|当前应用|当前\s*app)(?:上|里|内|中|的|上的|里的|中的)?\s*""")

private fun String.stripCurrentPageReference(): String =
    replace(
        STRIP_CURRENT_PAGE_REFERENCE_REGEX,
        "",
    )

private val SCROLL_DIRECTION_FOR_CURRENT_PAGE_COMMAND_UP_BACKWARD_REGEX =
    Regex("""\b(up|backward)\b""", RegexOption.IGNORE_CASE)

private val SCROLL_DIRECTION_FOR_CURRENT_PAGE_COMMAND_LEFT_REGEX =
    Regex("""\bleft\b""", RegexOption.IGNORE_CASE)

private val SCROLL_DIRECTION_FOR_CURRENT_PAGE_COMMAND_RIGHT_REGEX =
    Regex("""\bright\b""", RegexOption.IGNORE_CASE)

private val SCROLL_DIRECTION_FOR_CURRENT_PAGE_COMMAND_FORWARD_REGEX =
    Regex("""\bforward\b""", RegexOption.IGNORE_CASE)

private fun String.scrollDirectionForCurrentPageCommand(): String {
    val normalized = lowercase()
    return when {
        "往上" in this || "上滑" in this || "向上" in this ||
            SCROLL_DIRECTION_FOR_CURRENT_PAGE_COMMAND_UP_BACKWARD_REGEX.containsMatchIn(normalized) -> "up"
        "向左" in this || SCROLL_DIRECTION_FOR_CURRENT_PAGE_COMMAND_LEFT_REGEX.containsMatchIn(normalized) -> "left"
        "向右" in this || SCROLL_DIRECTION_FOR_CURRENT_PAGE_COMMAND_RIGHT_REGEX.containsMatchIn(normalized) -> "right"
        SCROLL_DIRECTION_FOR_CURRENT_PAGE_COMMAND_FORWARD_REGEX.containsMatchIn(normalized) -> "forward"
        else -> "down"
    }
}

private val CONTAINS_SEARCH_VERB_REGEX =
    Regex("""\b(?:search|look\s+up|find)\b""", RegexOption.IGNORE_CASE)

private fun String.containsSearchVerb(): Boolean {
    val normalized = lowercase()
    return listOf("搜索", "搜一下", "搜", "查询", "查找", "查").any { it in this } ||
        CONTAINS_SEARCH_VERB_REGEX.containsMatchIn(normalized)
}

private val EXTRACT_QUERY_AFTER_SEARCH_VERB_ZH1_REGEX =
    Regex("""^.*?(?:搜索|搜一下|搜|查询|查找|查)\s*[:：]?""")

private val EXTRACT_QUERY_AFTER_SEARCH_VERB_SEARCH_LOOK_REGEX =
    Regex("""(?i)^.*?\b(?:search|look\s+up|find)\s+(?:for\s+)?""")

private fun String.extractQueryAfterSearchVerb(): String? {
    val query = replace(EXTRACT_QUERY_AFTER_SEARCH_VERB_ZH1_REGEX, "")
        .replace(EXTRACT_QUERY_AFTER_SEARCH_VERB_SEARCH_LOOK_REGEX, "")
        .cleanSearchQuerySlot()
    return query?.takeIf { it.length <= 120 }
}

private val APP_SEARCH_SLOT_PATH_CHARS_REGEX = Regex("""[/:\\]""")

private fun String.cleanAppSearchSlot(): String? =
    trim()
        .removePrefix("应用")
        .removePrefix("app")
        .trim()
        .trimEnd('。', '.', '！', '!', '，', ',')
        .takeIf { candidate ->
            candidate.length in 1..80 &&
                candidate !in setOf("应用", "app", "应用程序", "软件") &&
                !candidate.contains(APP_SEARCH_SLOT_PATH_CHARS_REGEX) &&
                !candidate.looksLikeSystemOrWebTarget()
        }

private val CLEAN_SEARCH_QUERY_SLOT_REGEX = Regex("""^\s*(?:搜索|查询|查找|筛选)\s*$""")

private fun String.cleanSearchQuerySlot(): String? =
    trim()
        .trim('"', '\'', '“', '”', '‘', '’')
        .trimEnd('。', '.', '！', '!', '，', ',')
        .trim()
        .takeIf { candidate ->
            candidate.isNotBlank() &&
                candidate.length <= 120 &&
                !candidate.contains(CLEAN_SEARCH_QUERY_SLOT_REGEX)
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

private val SYSTEM_OR_WEB_TARGET_REGEX =
    Regex("""\b(?:wi[-\s]?fi|wifi|wlan|settings?|preferences?|permission|web\s*page|website|link|url)\b""", RegexOption.IGNORE_CASE)

private fun String.looksLikeSystemOrWebTarget(): Boolean {
    val normalized = lowercase()
    return listOf("设置", "权限", "网页", "网站", "链接", "网址").any { it in this } ||
        SYSTEM_OR_WEB_TARGET_REGEX.containsMatchIn(normalized)
}

internal fun String.requestsCurrentDraftFormUiFill(): Boolean =
    extractCurrentDraftFormUiFill() != null

private val EXTRACT_CURRENT_DRAFT_FORM_UI_FILL_CURRENT_THIS_REGEX =
    Regex("""\b(?:current|this)\s+(?:email|mail)\s+(?:draft|form|compose)\b""", RegexOption.IGNORE_CASE)

private val EXTRACT_CURRENT_DRAFT_FORM_UI_FILL_CURRENT_THIS_2_REGEX =
    Regex("""\b(?:current|this)\s+(?:calendar|event)\s+(?:draft|form)\b""", RegexOption.IGNORE_CASE)

private val EXTRACT_CURRENT_DRAFT_FORM_UI_FILL_FILL_TYPE_REGEX =
    Regex("""\b(?:fill|type|enter)\b""", RegexOption.IGNORE_CASE)

private val EXTRACT_CURRENT_DRAFT_FORM_UI_FILL_ZH1_REGEX = Regex("""^.*?(?:填写|填入|输入|写入)\s*[:：]?""")

private val EXTRACT_CURRENT_DRAFT_FORM_UI_FILL_FILL_TYPE_2_REGEX =
    Regex("""(?i)^.*?\b(?:fill|type|enter)\s+""")

internal fun String.extractCurrentDraftFormUiFill(): DraftFormUiFill? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val normalized = lowercase()
    val isEmail = listOf("当前邮件表单", "当前邮件页面", "当前邮件草稿", "邮件草稿页").any { it in this } ||
        EXTRACT_CURRENT_DRAFT_FORM_UI_FILL_CURRENT_THIS_REGEX.containsMatchIn(normalized)
    val isCalendar = listOf("当前日历表单", "当前日程页面", "当前日程草稿", "日历草稿页").any { it in this } ||
        EXTRACT_CURRENT_DRAFT_FORM_UI_FILL_CURRENT_THIS_2_REGEX.containsMatchIn(normalized)
    if (!isEmail && !isCalendar) return null
    val asksFill = listOf("填写", "填入", "输入", "写入").any { it in this } ||
        EXTRACT_CURRENT_DRAFT_FORM_UI_FILL_FILL_TYPE_REGEX.containsMatchIn(normalized)
    if (!asksFill) return null
    val text = replace(EXTRACT_CURRENT_DRAFT_FORM_UI_FILL_ZH1_REGEX, "")
        .replace(EXTRACT_CURRENT_DRAFT_FORM_UI_FILL_FILL_TYPE_2_REGEX, "")
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

private val STARTS_WITH_QUESTION_OPENER_ZH1_REGEX =
    Regex("""^\s*(?:请问|问一下|如何|怎么|怎样|为什么|解释|说明|介绍)""")

private val STARTS_WITH_QUESTION_OPENER_HOW_DO_REGEX =
    Regex("""^\s*(?:how\s+(?:do|can|to)|what\s+is|explain)\b""")

/** "How do I…" / "请问…" style openers: the user is asking about something, not requesting it. */
private fun String.startsWithQuestionOpener(normalized: String): Boolean =
    STARTS_WITH_QUESTION_OPENER_ZH1_REGEX.containsMatchIn(this) ||
        STARTS_WITH_QUESTION_OPENER_HOW_DO_REGEX.containsMatchIn(normalized)

private fun String.looksLikeUiControlDiscussion(): Boolean {
    val normalized = lowercase()
    return startsWithQuestionOpener(normalized) ||
        listOf(" API", "api", "接口", "怎么实现", "如何实现", "怎么设计").any { it in this }
}

private val CURRENT_SCREEN_TEXT_SUMMARY_SHARE_NON_ACTION_ZH1_REGEX =
    Regex("""^\s*(?:请|帮我|麻烦|麻烦你)?\s*(?:不想|不需要|不用|不必|不要|别|请勿|请不要|先别|暂时别|不).*(?:总结|摘要|概括|归纳).*(?:当前屏幕|当前界面|现在屏幕|屏幕内容|屏幕文字|屏幕文本|这个界面).*(?:分享)""")

private val CURRENT_SCREEN_TEXT_SUMMARY_SHARE_NON_ACTION_ZH2_REGEX =
    Regex("""^\s*(?:请问|问一下|如何|怎么|怎样|为什么|解释|说明|介绍).*(?:总结|摘要|概括|归纳).*(?:当前屏幕|当前界面|现在屏幕|屏幕内容|屏幕文字|屏幕文本|这个界面).*(?:分享)""")

private val CURRENT_SCREEN_TEXT_SUMMARY_SHARE_NON_ACTION_PLEASE_DO_REGEX =
    Regex("""^\s*(?:(?:please\s+)?(?:do\s+not|don't|dont|never)|i\s+(?:do\s+not|don't|dont)\s+want\s+to)\b.*\b(?:summarize|summary|brief|recap)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view)\b.*\bshare\b""")

private val CURRENT_SCREEN_TEXT_SUMMARY_SHARE_NON_ACTION_HOW_DO_REGEX =
    Regex("""^\s*(?:how\s+(?:do|can|to)|what\s+is|explain)\b.*\b(?:summarize|summary|brief|recap)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view)\b.*\bshare\b""")

internal fun String.looksLikeCurrentScreenTextSummaryShareNonAction(normalized: String): Boolean =
    startsWithQuestionOpener(normalized) &&
        CurrentScreenTextActionParser.referencesCurrentScreenText(this) &&
        ("分享" in this || SHARE_REGEX.containsMatchIn(normalized)) ||
        CURRENT_SCREEN_TEXT_SUMMARY_SHARE_NON_ACTION_ZH1_REGEX.containsMatchIn(this) ||
        CURRENT_SCREEN_TEXT_SUMMARY_SHARE_NON_ACTION_ZH2_REGEX.containsMatchIn(this) ||
        CURRENT_SCREEN_TEXT_SUMMARY_SHARE_NON_ACTION_PLEASE_DO_REGEX.containsMatchIn(normalized) ||
        CURRENT_SCREEN_TEXT_SUMMARY_SHARE_NON_ACTION_HOW_DO_REGEX.containsMatchIn(normalized)

internal fun String.requestsClipboardSummaryShare(): Boolean {
    val normalized = lowercase()
    if (looksLikeClipboardSummaryShareNonAction(normalized)) return false
    val referencesClipboard = "剪贴板" in this || "clipboard" in normalized
    val asksForSummary = listOf("总结", "摘要", "概括", "归纳").any { it in this } ||
        SUMMARY_WORD_REGEX.containsMatchIn(normalized)
    val asksToShare = "分享" in this ||
        SHARE_REGEX.containsMatchIn(normalized)
    return referencesClipboard && asksForSummary && asksToShare
}

private val CLIPBOARD_SUMMARY_SHARE_NON_ACTION_ZH1_REGEX =
    Regex("""^\s*(?:请|帮我|麻烦|麻烦你)?\s*(?:不想|不需要|不用|不必|不要|别|请勿|请不要|先别|暂时别|不).*(?:总结|摘要|概括|归纳).*(?:剪贴板).*(?:分享)""")

private val CLIPBOARD_SUMMARY_SHARE_NON_ACTION_ZH2_REGEX =
    Regex("""^\s*(?:请问|问一下|如何|怎么|怎样|为什么|解释|说明|介绍).*(?:总结|摘要|概括|归纳).*(?:剪贴板).*(?:分享)""")

private val CLIPBOARD_SUMMARY_SHARE_NON_ACTION_PLEASE_DO_REGEX =
    Regex("""^\s*(?:(?:please\s+)?(?:do\s+not|don't|dont|never)|i\s+(?:do\s+not|don't|dont)\s+want\s+to)\b.*\b(?:summarize|summary|brief|recap)\b.*\bclipboard\b.*\bshare\b""")

private val CLIPBOARD_SUMMARY_SHARE_NON_ACTION_HOW_DO_REGEX =
    Regex("""^\s*(?:how\s+(?:do|can|to)|what\s+is|explain)\b.*\b(?:summarize|summary|brief|recap)\b.*\bclipboard\b.*\bshare\b""")

private fun String.looksLikeClipboardSummaryShareNonAction(normalized: String): Boolean =
    CLIPBOARD_SUMMARY_SHARE_NON_ACTION_ZH1_REGEX.containsMatchIn(this) ||
        CLIPBOARD_SUMMARY_SHARE_NON_ACTION_ZH2_REGEX.containsMatchIn(this) ||
        CLIPBOARD_SUMMARY_SHARE_NON_ACTION_PLEASE_DO_REGEX.containsMatchIn(normalized) ||
        CLIPBOARD_SUMMARY_SHARE_NON_ACTION_HOW_DO_REGEX.containsMatchIn(normalized)

private val CLIPBOARD_CONTEXT_REGEX = Regex("""\b(read|summarize|summary|recap)\b""")

internal fun String.requestsClipboardContext(): Boolean {
    val normalized = lowercase()
    if (looksLikeClipboardContextNonAction()) return false
    val referencesClipboard = "剪贴板" in this || "clipboard" in normalized
    val asksToRead = listOf("读取", "读一下", "看看", "查看", "总结", "摘要", "概括").any { it in this } ||
        CLIPBOARD_CONTEXT_REGEX.containsMatchIn(normalized)
    val asksToShare = "分享" in this || SHARE_REGEX.containsMatchIn(normalized)
    return referencesClipboard && asksToRead && !asksToShare
}

internal fun ActionDraft.toToolRequest(): ToolRequest =
    ToolRequest(toolName = functionName, arguments = parameters, reason = summary)
