package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.action.MobileActionFunctions

/**
 * Shortlist size for a planning model. On the desktop lab's 100-case retrieval set this retriever
 * scores 99/100 at k = 8, 12 and 16, with a mean shortlist of 3.7 tools. 12 is headroom for the
 * unseen cases, not a measured optimum.
 */
const val DEFAULT_TOOL_RETRIEVAL_LIMIT = 12

/** Below this a tool is not retrieved, so a plain question usually retrieves nothing. */
const val MIN_TOOL_RETRIEVAL_SCORE = 3

/**
 * Chooses which tools a planning model is shown for one request, so a small on-device model picks
 * from a shortlist instead of the whole registry.
 *
 * A pure, deterministic function: the result is a subset of `candidates`, most relevant first with
 * ties in candidate order, at most `limit` long, and empty when nothing is relevant. Scope belongs
 * to the caller, which filters `candidates` (see [isFirstStepPlanningCandidate]); a retriever never
 * adds a tool. Only a tool's name, title, description and planning hint are read, never its
 * argument schema, so the shortlist serves a text `call:` prompt and a native tool-calling prompt
 * alike. A tool scores for curated trigger phrases and request shapes (the words users type, which
 * tool text rarely contains) plus word overlap with its own text.
 */
object ToolRetriever {
    fun retrieve(
        request: String,
        candidates: List<ToolSpec>,
        limit: Int = DEFAULT_TOOL_RETRIEVAL_LIMIT,
    ): List<ToolSpec> {
        if (limit <= 0 || request.isBlank()) return emptyList()
        val query = RetrievalQuery(request.lowercase())
        return candidates
            .distinctBy { spec -> spec.name }
            .map { spec -> spec to query.score(spec) }
            .filter { (_, score) -> score >= MIN_TOOL_RETRIEVAL_SCORE }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (spec, _) -> spec }
    }
}

/**
 * Whether a tool may be offered as the first step of a run, before anything has been observed.
 * In-app gestures need an observed screen, orchestration and question tools only make sense
 * inside a loop, and extension tools hand their arguments to another process, so none of them is
 * offered to a local planning model up front.
 */
fun ToolSpec.isFirstStepPlanningCandidate(): Boolean =
    capability !in firstStepExcludedCapabilities &&
        tags.none { tag -> tag in firstStepExcludedTags }

private val firstStepExcludedCapabilities = setOf(
    ToolCapability.DeviceControl,
    ToolCapability.Orchestration,
    ToolCapability.Extension,
)

private val firstStepExcludedTags = setOf(
    ToolCapabilityTag.Planning,
    ToolCapabilityTag.UxInteraction,
    ToolCapabilityTag.McpTool,
)

private class RetrievalQuery(private val text: String) {
    private val hanBigrams: Set<String> = text.hanBigrams()
    private val asciiWords: Set<String> = text.asciiWords()

    fun score(spec: ToolSpec): Int {
        val triggerScore = builtInTriggers[spec.name].orEmpty().sumOf { trigger -> trigger.scoreIn(text) }
        val title = spec.title.lowercase()
        val body = "${spec.description} ${spec.planningPromptHint.orEmpty()}".lowercase()
        val titleWords = title.asciiWords()
        val bodyWords = body.asciiWords()
        val nameWords = spec.name.split('_').toSet()
        val titleHits = hanBigrams.count { bigram -> bigram in title } + asciiWords.count { word -> word in titleWords }
        val bodyHits = hanBigrams.count { bigram -> bigram in body } + asciiWords.count { word -> word in bodyWords }
        val nameHits = asciiWords.count { word -> word in nameWords }
        return triggerScore +
            TITLE_HIT_WEIGHT * titleHits.coerceAtMost(MAX_COUNTED_FIELD_HITS) +
            bodyHits.coerceAtMost(MAX_COUNTED_FIELD_HITS) +
            NAME_HIT_WEIGHT * nameHits
    }
}

private class Trigger(private val weight: Int, private val matches: (String) -> Boolean) {
    fun scoreIn(lowercaseText: String): Int = if (matches(lowercaseText)) weight else 0
}

/**
 * ASCII phrases match whole words only, so "mail" does not fire inside "gmail.com". The trailing
 * boundary applies only when the phrase ends in a word character: "www." followed by "baidu" must
 * still match, while "mail" must not match inside "mailbox".
 */
private fun phraseMatcher(phrase: String): (String) -> Boolean {
    if (!phrase.first().isAsciiWordChar()) return { text -> phrase in text }
    val trailingBoundary = if (phrase.last().isAsciiWordChar()) "(?![a-z0-9])" else ""
    return Regex("(?<![a-z0-9])" + Regex.escape(phrase) + trailingBoundary)::containsMatchIn
}

private fun String.hanBigrams(): Set<String> = buildSet {
    val text = this@hanBigrams
    for (index in 0 until text.length - 1) {
        val first = text[index]
        val second = text[index + 1]
        if (first.isContentHan() && second.isContentHan()) add("$first$second")
    }
}

private fun String.asciiWords(): Set<String> =
    asciiWordPattern.findAll(this)
        .map { match -> match.value }
        .filter { word -> word.length >= 3 && word.any(Char::isLetter) }
        .toSet()

private val asciiWordPattern = Regex("[a-z0-9]+")

private fun Char.isAsciiWordChar(): Boolean = this in 'a'..'z' || this in '0'..'9'

/** Han characters that carry meaning; function words would match almost every description. */
private fun Char.isContentHan(): Boolean = this in '一'..'鿿' && this !in HAN_FUNCTION_CHARACTERS

private const val HAN_FUNCTION_CHARACTERS = "的了吗呢吧啊呀么是和与把被给让请我你他她它们这那个一也都就还在不为有要想帮下"

private const val STRONG_TRIGGER_WEIGHT = 8
private const val WEAK_TRIGGER_WEIGHT = 3
private const val TITLE_HIT_WEIGHT = 2
private const val NAME_HIT_WEIGHT = 3
private const val MAX_COUNTED_FIELD_HITS = 3

private fun strong(vararg phrases: String): List<Trigger> =
    phrases.map { phrase -> Trigger(STRONG_TRIGGER_WEIGHT, phraseMatcher(phrase)) }

private fun weak(vararg phrases: String): List<Trigger> =
    phrases.map { phrase -> Trigger(WEAK_TRIGGER_WEIGHT, phraseMatcher(phrase)) }

private fun strongShape(pattern: Regex): Trigger = Trigger(STRONG_TRIGGER_WEIGHT, pattern::containsMatchIn)

/** "在淘宝搜索牛奶", "打开小红书搜咖啡": a search inside a named app starts by launching that app. */
private val inAppSearchShape = Regex("(?:在|去|到|用|打开)[^，。,.!?！？\\s]{1,12}?(?:里|上|中)?(?:搜索|搜|查找)")

/** "23*17+5", "1.5/3"; whole numbers on both sides, so dates and phone numbers do not match. */
private val arithmeticShape = Regex("(?<![\\d.-])\\d+(?:\\.\\d+)?\\s*[-+*/×÷^]\\s*\\d+(?:\\.\\d+)?(?![\\d-])")

/** Strong phrases name the object of a request; weak ones are generic verbs shared by many tools. */
private val builtInTriggers: Map<String, List<Trigger>> = mapOf(
    MobileActionFunctions.SET_SYSTEM_ALARM to strong("闹钟", "叫醒", "叫我起床", "起床", "alarm", "wake me"),
    MobileActionFunctions.SET_SYSTEM_TIMER to strong("倒计时", "计时器", "定时器", "计时", "番茄钟", "timer", "countdown"),
    MobileActionFunctions.SCHEDULE_REMINDER to strong("提醒我", "别忘了", "记得提醒", "remind me") + weak("提醒"),
    MobileActionFunctions.CANCEL_REMINDER to strong("取消提醒", "删除提醒", "不用提醒", "cancel reminder") + weak("取消"),
    MobileActionFunctions.QUERY_BACKGROUND_TASKS to strong("有哪些提醒", "提醒列表", "查看提醒", "后台任务"),
    MobileActionFunctions.CONFIGURE_PERIODIC_CHECK to strong("周期检查", "定期检查", "周期巡检"),
    MobileActionFunctions.OPEN_APP_BY_NAME to
        weak("打开", "启动", "进入", "open", "launch") + strongShape(inAppSearchShape),
    MobileActionFunctions.OPEN_APP_INTENT to strong("包名", "package name"),
    MobileActionFunctions.OPEN_WIFI_SETTINGS to strong("wifi", "wi-fi", "无线网", "无线局域网"),
    MobileActionFunctions.OPEN_SYSTEM_SETTINGS to strong(
        "蓝牙", "bluetooth", "定位", "位置服务", "gps", "亮度", "铃声", "静音", "震动", "振动", "省电",
        "飞行模式", "输入法", "无障碍", "勿扰", "音量", "移动数据", "通知设置",
    ) + weak("设置", "settings"),
    MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS to strong("手电筒", "闪光灯", "flashlight", "torch"),
    MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS to strong("使用情况访问", "usage access"),
    MobileActionFunctions.WEB_SEARCH to strong(
        "天气", "新闻", "股价", "汇率", "比分", "最新", "热搜", "百度一下", "谷歌", "google", "网上查", "上网查",
    ) + weak("搜索", "搜一下", "查一下", "查查", "search", "look up"),
    MobileActionFunctions.SEARCH_MAPS to strong(
        "导航", "地图", "路线", "怎么去", "怎么走", "附近的", "高德", "navigate", "directions", "maps",
    ),
    MobileActionFunctions.SHARE_TEXT to strong("分享", "转发", "share"),
    MobileActionFunctions.COMPOSE_EMAIL to strong("邮件", "邮箱", "email", "e-mail", "mail"),
    MobileActionFunctions.COMPOSE_SMS to strong("短信", "sms", "text message"),
    MobileActionFunctions.DIAL_PHONE_NUMBER to strong("打电话", "拨打", "拨号", "呼叫", "dial") + weak("电话", "call"),
    MobileActionFunctions.CREATE_CALENDAR_EVENT to
        strong("日程", "日历", "会议", "开会", "约会", "calendar", "meeting") + weak("安排"),
    MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY to
        strong("有空", "空闲", "忙不忙", "忙闲", "availability", "free time"),
    MobileActionFunctions.CREATE_CONTACT_DRAFT to strong("新建联系人", "添加联系人", "保存号码", "存号码", "add contact"),
    MobileActionFunctions.QUERY_CONTACTS to strong("通讯录", "联系人", "号码是多少", "电话号码", "contacts"),
    MobileActionFunctions.READ_CLIPBOARD to strong("剪贴板", "粘贴板", "剪切板", "复制的内容", "clipboard"),
    MobileActionFunctions.OPEN_DEEP_LINK to strong("链接", "网址", "://", "www.", "url", "link"),
    MobileActionFunctions.OPEN_CAMERA to strong("相机", "拍照", "摄像", "自拍", "camera"),
    MobileActionFunctions.CONTROL_MEDIA to strong(
        "暂停", "下一首", "上一首", "切歌", "继续播放", "停止播放", "pause", "next track", "previous track",
    ) + weak("播放", "音乐", "歌", "play", "music"),
    MobileActionFunctions.CALCULATE to strong(
        "计算", "算一下", "算算", "等于多少", "等于几", "换算", "乘以", "除以", "加上", "减去", "平方根", "开根号",
        "calculate", "convert",
    ) + strongShape(arithmeticShape),
    MobileActionFunctions.QUERY_FOREGROUND_APP to strong("前台应用", "当前应用", "什么应用", "foreground app"),
    MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS to strong("通知", "未读消息", "notification", "notifications"),
    MobileActionFunctions.QUERY_RECENT_FILES to
        strong("最近文件", "最近的文件", "下载的文件", "recent files") + weak("文件"),
    MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR to strong("截图", "截屏", "screenshot"),
    MobileActionFunctions.READ_RECENT_IMAGE_OCR to strong("图片", "照片", "相册", "image", "photo"),
    MobileActionFunctions.READ_CURRENT_SCREEN_TEXT to
        strong("屏幕上", "当前屏幕", "这个页面", "当前页面", "屏幕内容", "screen"),
    MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR to strong("截取屏幕", "识别屏幕", "屏幕文字", "ocr"),
)
