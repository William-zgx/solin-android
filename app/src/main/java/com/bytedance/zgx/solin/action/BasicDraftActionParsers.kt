package com.bytedance.zgx.solin.action

import java.time.ZoneId

private val CLEANED_SEARCH_QUERY_MAPS_REGEX =
    Regex("""^(?:打开|启动|进入)?\s*(?:高德地图|高德|地图|maps?)\s*(?:搜索|搜一下|搜|查询|查找|查)\s*[:：]?\s*""", RegexOption.IGNORE_CASE)

private val CLEANED_SEARCH_QUERY_ZH1_REGEX = Regex("""^(地图)?\s*(搜索|搜|查)(一下|一查)?\s*地图?\s*[:：]?\s*""")

private val CLEANED_SEARCH_QUERY_ZH2_REGEX = Regex("""^查(一下)?\s*(去|到)?\s*""")

private val CLEANED_SEARCH_QUERY_ZH3_REGEX = Regex("""^导航(到|去)?\s*""")

private val CLEANED_SEARCH_QUERY_SEARCH_MAPS_REGEX =
    Regex("""(?i)^(search\s+maps?\s+for|map\s+(?:search|route)|directions?\s+to|navigate\s+to|route\s+to)\s+""")

internal object MapSearchActionParser {
    private val englishPattern =
        Regex("""\b(search\s+maps?\s+for|map\s+(?:search|route)\s+(?:to|for)|directions?\s+to|navigate\s+to|route\s+to)\b""", RegexOption.IGNORE_CASE)
    private val chineseRoutePattern =
        Regex("""^\s*(?:请\s*)?(?:帮我\s*)?查(?:一下)?(?:去|到).+(?:路线|怎么走)\s*$""")

    fun matches(input: String): Boolean {
        val normalized = input.lowercase()
        if (input.startsWithActionNegation() || input.looksLikeDiscussion()) return false
        return listOf("地图搜索", "搜索地图", "导航到", "导航去", "查路线", "查一下路线")
            .any { it in input } ||
            chineseRoutePattern.containsMatchIn(input) ||
            englishPattern.containsMatchIn(normalized)
    }

    fun draft(input: String): ActionDraft {
        val query = cleanedSearchQuery(input)
        return ActionDraft(
            functionName = MobileActionFunctions.SEARCH_MAPS,
            title = "地图搜索",
            summary = "将在地图中搜索：$query",
            parameters = mapOf("query" to query),
            requiresConfirmation = true
        )
    }

    private fun cleanedSearchQuery(input: String): String = cleanedDraftValue(
        input,
        CLEANED_SEARCH_QUERY_MAPS_REGEX,
        CLEANED_SEARCH_QUERY_ZH1_REGEX,
        CLEANED_SEARCH_QUERY_ZH2_REGEX,
        CLEANED_SEARCH_QUERY_ZH3_REGEX,
        CLEANED_SEARCH_QUERY_SEARCH_MAPS_REGEX
    )
}

private val EMAIL_DRAFT_NEGATION_REGEX =
    Regex("""\b(do\s+not|don't|dont)\s+(send|write|compose|draft)\s+(an?\s+)?(?:email|mail)\b""")

private val CLEANED_EMAIL_BODY_ZH1_REGEX = Regex("""^(写|起草|发)(一?封)?邮件\s*[:：]?\s*""")

private val CLEANED_EMAIL_BODY_ZH2_REGEX = Regex("""^邮件草稿\s*[:：]?\s*""")

private val CLEANED_EMAIL_BODY_DRAFT_WRITE_REGEX =
    Regex("""(?i)^(draft|write|compose|send)\s+(an?\s+)?(?:email|mail)\s*[:：]?\s*""")

internal object EmailDraftActionParser {
    private val englishPattern =
        Regex("""\b(draft|write|compose|send)\s+(an?\s+)?(?:email|mail)\b|\bemail\s+draft\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        val normalized = input.lowercase()
        if (input.startsWithActionNegation() || input.looksLikeDiscussion()) return false
        if (normalized.contains(EMAIL_DRAFT_NEGATION_REGEX) ||
            "不要发邮件" in input ||
            "不要写邮件" in input
        ) {
            return false
        }
        return listOf("写封邮件", "写邮件", "邮件草稿", "发邮件", "起草邮件")
            .any { it in input } ||
            englishPattern.containsMatchIn(normalized)
    }

    fun draft(input: String): ActionDraft {
        val body = cleanedEmailBody(input)
        return ActionDraft(
            functionName = MobileActionFunctions.COMPOSE_EMAIL,
            title = "邮件草稿",
            summary = "将打开邮件 App 并填入草稿内容。",
            parameters = mapOf("body" to body),
            requiresConfirmation = true
        )
    }

    private fun cleanedEmailBody(input: String): String = cleanedDraftValue(
        input,
        CLEANED_EMAIL_BODY_ZH1_REGEX,
        CLEANED_EMAIL_BODY_ZH2_REGEX,
        CLEANED_EMAIL_BODY_DRAFT_WRITE_REGEX
    )
}

private val CLEANED_CALENDAR_TITLE_ZH1_REGEX = Regex("""^(新建|创建|添加|安排|加|建)(一?个)?日程\s*[:：]?\s*""")

private val CLEANED_CALENDAR_TITLE_ADD_CREATE_REGEX =
    Regex("""(?i)^(add|create|schedule)\s+(an?\s+)?(?:calendar\s+)?event\s*[:：]?\s*""")

internal object CalendarDraftActionParser {
    private val englishPattern =
        Regex("""\b(add|create|schedule)\s+(an?\s+)?calendar\s+event\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        val normalized = input.lowercase()
        if (input.startsWithActionNegation() || input.looksLikeDiscussion()) return false
        if ("不要创建日程" in input || "不要新建日程" in input) return false
        return listOf("建个日程", "新建日程", "创建日程", "加个日程", "添加日程", "安排日程")
            .any { it in input } ||
            englishPattern.containsMatchIn(normalized)
    }

    /**
     * [nowMillis] enables date/time pre-fill (周五下午三点 → begin/end millis). Without a clock, or
     * when the phrase is ambiguous or in the past, the draft opens without a time (fail closed:
     * never pre-fill a guessed time).
     */
    fun draft(input: String, nowMillis: Long? = null, zoneId: ZoneId = ZoneId.systemDefault()): ActionDraft {
        val title = cleanedCalendarTitle(input)
        val timing = nowMillis?.let { CalendarDraftTiming.parse(input, it, zoneId) }
        val parameters = buildMap {
            put("title", title)
            if (timing != null) {
                put("beginTimeMillis", timing.beginTimeMillis.toString())
                put("endTimeMillis", timing.endTimeMillis.toString())
                put("allDay", timing.allDay.toString())
            }
        }
        return ActionDraft(
            functionName = MobileActionFunctions.CREATE_CALENDAR_EVENT,
            title = "日程草稿",
            summary = timing?.let { "将打开日历新建事件页面，时间：${it.displayText(zoneId)}。" }
                ?: "将打开日历新建事件页面。",
            parameters = parameters,
            requiresConfirmation = true
        )
    }

    private fun cleanedCalendarTitle(input: String): String = cleanedDraftValue(
        input,
        CLEANED_CALENDAR_TITLE_ZH1_REGEX,
        CLEANED_CALENDAR_TITLE_ADD_CREATE_REGEX
    )
}

private fun cleanedDraftValue(input: String, vararg prefixes: Regex): String {
    val cleaned = prefixes.fold(cleanedObject(input)) { value, prefix -> value.replace(prefix, "") }.trim()
    return cleaned.ifBlank { cleanedObject(input) }
}

private val DISCUSSION_REGEX =
    Regex("""\b(how\s+do\s+i|what\s+does|what\s+is|explain|meaning|parser|tests?|listener|handler|schema|stream)\b""")

internal fun String.looksLikeDiscussion(): Boolean {
    val normalized = lowercase()
    return listOf(
        "什么意思",
        "什么含义",
        "怎么说",
        "如何表达",
        "解释",
        "怎么理解",
        "怎么写",
        "怎么实现",
        "如何实现",
        "怎么设计",
        "是什么"
    )
        .any { it in this } ||
        listOf("错误原因", "日志", "算法", "数据结构", "功能测试", "路线图", "导航栏").any { it in this } ||
        normalized.contains(DISCUSSION_REGEX)
}

private val SEQUENTIAL_ACTION_ZH1_REGEX =
    Regex(""".+(?:然后|接着|随后|之后|再)\s*(?:打开|进入|启动|发|发送|写|建|创建|添加|查询|查|搜索|读取|读|总结|分享|导航|跳转|访问|取消|提醒|设置提醒|返回|后退).*""")

private val SEQUENTIAL_ACTION_AND_THEN_REGEX =
    Regex("""\b(?:and\s+then|then|after\s+that)\b.*\b(?:go\s+back|back|open|launch|search|share|send|create|read|summarize)\b""")

internal fun String.looksLikeSequentialAction(): Boolean {
    val normalized = lowercase()
    return SEQUENTIAL_ACTION_ZH1_REGEX.containsMatchIn(this) ||
        normalized.contains(
            SEQUENTIAL_ACTION_AND_THEN_REGEX
        )
}

private val STARTS_WITH_ACTION_NEGATION_ZH1_REGEX =
    Regex("""^\s*(?:请|帮我|麻烦|麻烦你)?\s*(?:我\s*)?(?:不想|不需要|不用|不必|不要|别|请勿|请不要|先别|暂时别|不)\s*""")

private val STARTS_WITH_ACTION_NEGATION_PLEASE_DO_REGEX =
    Regex("""^\s*(?:(?:please\s+)?(?:do\s+not|don't|dont|never)|i\s+(?:do\s+not|don't|dont)\s+want\s+to)\b""")

internal fun String.startsWithActionNegation(): Boolean {
    val normalized = lowercase()
    return STARTS_WITH_ACTION_NEGATION_ZH1_REGEX.containsMatchIn(this) ||
        normalized.contains(STARTS_WITH_ACTION_NEGATION_PLEASE_DO_REGEX)
}

internal fun cleanedObject(input: String): String = input.trim()
    .removePrefix("请")
    .removePrefix("帮我")
    .trim()
    .ifBlank { input.trim() }
