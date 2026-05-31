package com.bytedance.zgx.pocketmind.action

internal object MapSearchActionParser {
    private val englishPattern =
        Regex("""\b(search\s+maps?\s+for|map\s+(?:search|route)\s+(?:to|for)|directions?\s+to|navigate\s+to|route\s+to)\b""", RegexOption.IGNORE_CASE)
    private val chineseRoutePattern =
        Regex("""^\s*(?:请\s*)?(?:帮我\s*)?查(?:一下)?(?:去|到).+(?:路线|怎么走)\s*$""")

    fun matches(input: String): Boolean {
        val normalized = input.lowercase()
        if (input.looksLikeDiscussion()) return false
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
            requiresConfirmation = true,
        )
    }

    private fun cleanedSearchQuery(input: String): String {
        val cleaned = cleanedObject(input)
            .replace(Regex("""^(地图)?\s*(搜索|搜|查)(一下|一查)?\s*地图?\s*[:：]?\s*"""), "")
            .replace(Regex("""^查(一下)?\s*(去|到)?\s*"""), "")
            .replace(Regex("""^导航(到|去)?\s*"""), "")
            .replace(Regex("""(?i)^(search\s+maps?\s+for|map\s+(?:search|route)|directions?\s+to|navigate\s+to|route\s+to)\s+"""), "")
            .trim()
        return cleaned.ifBlank { cleanedObject(input) }
    }
}

internal object EmailDraftActionParser {
    private val englishPattern =
        Regex("""\b(draft|write|compose|send)\s+(an?\s+)?(?:email|mail)\b|\bemail\s+draft\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        val normalized = input.lowercase()
        if (input.looksLikeDiscussion()) return false
        if (normalized.contains(Regex("""\b(do\s+not|don't|dont)\s+(send|write|compose|draft)\s+(an?\s+)?(?:email|mail)\b""")) ||
            "不要发邮件" in input || "不要写邮件" in input
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
            requiresConfirmation = true,
        )
    }

    private fun cleanedEmailBody(input: String): String {
        val cleaned = cleanedObject(input)
            .replace(Regex("""^(写|起草|发)(一?封)?邮件\s*[:：]?\s*"""), "")
            .replace(Regex("""^邮件草稿\s*[:：]?\s*"""), "")
            .replace(Regex("""(?i)^(draft|write|compose|send)\s+(an?\s+)?(?:email|mail)\s*[:：]?\s*"""), "")
            .trim()
        return cleaned.ifBlank { cleanedObject(input) }
    }
}

internal object CalendarDraftActionParser {
    private val englishPattern =
        Regex("""\b(add|create|schedule)\s+(an?\s+)?calendar\s+event\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        val normalized = input.lowercase()
        if (input.looksLikeDiscussion()) return false
        if ("不要创建日程" in input || "不要新建日程" in input) return false
        return listOf("建个日程", "新建日程", "创建日程", "加个日程", "添加日程", "安排日程")
            .any { it in input } ||
            englishPattern.containsMatchIn(normalized)
    }

    fun draft(input: String): ActionDraft {
        val title = cleanedCalendarTitle(input)
        return ActionDraft(
            functionName = MobileActionFunctions.CREATE_CALENDAR_EVENT,
            title = "日程草稿",
            summary = "将打开日历新建事件页面。",
            parameters = mapOf("title" to title),
            requiresConfirmation = true,
        )
    }

    private fun cleanedCalendarTitle(input: String): String {
        val cleaned = cleanedObject(input)
            .replace(Regex("""^(新建|创建|添加|安排|加|建)(一?个)?日程\s*[:：]?\s*"""), "")
            .replace(Regex("""(?i)^(add|create|schedule)\s+(an?\s+)?(?:calendar\s+)?event\s*[:：]?\s*"""), "")
            .trim()
        return cleaned.ifBlank { cleanedObject(input) }
    }
}

private fun String.looksLikeDiscussion(): Boolean {
    val normalized = lowercase()
    return listOf("什么意思", "什么含义", "怎么说", "如何表达", "解释", "怎么理解", "怎么写", "是什么")
        .any { it in this } ||
        listOf("错误原因", "日志", "算法", "数据结构", "功能测试", "路线图", "导航栏").any { it in this } ||
        normalized.contains(Regex("""\b(how\s+do\s+i|what\s+does|what\s+is|explain|meaning|parser|tests?|listener|handler|schema|stream)\b"""))
}

private fun cleanedObject(input: String): String =
    input.trim()
        .removePrefix("请")
        .removePrefix("帮我")
        .trim()
        .ifBlank { input.trim() }
