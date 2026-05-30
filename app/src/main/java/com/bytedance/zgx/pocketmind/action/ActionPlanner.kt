package com.bytedance.zgx.pocketmind.action

interface ActionPlanner {
    fun isLikelyAction(input: String): Boolean
    fun plan(input: String): ActionPlan
}

class MobileActionPlanner : ActionPlanner {
    override fun isLikelyAction(input: String): Boolean {
        val normalized = input.lowercase()
        return listOf(
            "打开",
            "设置",
            "wifi",
            "wi-fi",
            "地图",
            "导航",
            "邮件",
            "日程",
            "联系人",
            "手电筒",
            "提醒我",
            "分钟后",
            "小时后",
            "剪贴板",
            "分享",
            "忙闲",
            "空闲",
            "有空",
            "calendar",
            "email",
            "map",
            "contact",
            "remind",
            "clipboard",
            "share",
            "availability",
            "free/busy",
        ).any { it in normalized } || isWebSearchRequest(input)
    }

    override fun plan(input: String): ActionPlan =
        parseModelOutput(input)?.let { ActionPlan(ActionPlanKind.Draft, it) }
            ?: inferDraft(input)?.let { ActionPlan(ActionPlanKind.Draft, it) }
            ?: ActionPlan(ActionPlanKind.NoAction)

    fun parseModelOutput(output: String): ActionDraft? {
        val match = CALL_PATTERN.find(output.trim()) ?: return null
        val functionName = match.groupValues[1]
        if (functionName !in MobileActionFunctions.supported) return null
        val parameters = parseJsonLikeObject(match.groupValues[2])
        return functionName.toDraft(parameters)
    }

    private fun inferDraft(input: String): ActionDraft? {
        val normalized = input.lowercase()
        val calendarWindowParameters = calendarAvailabilityParameters(input)
        return when {
            "wifi" in normalized || "wi-fi" in normalized || "无线" in input ->
                MobileActionFunctions.OPEN_WIFI_SETTINGS.toDraft(emptyMap())

            "地图" in input || "导航" in input || "map" in normalized ->
                MobileActionFunctions.SEARCH_MAPS.toDraft(mapOf("query" to cleanedObject(input)))

            "邮件" in input || "email" in normalized || "mail" in normalized ->
                MobileActionFunctions.COMPOSE_EMAIL.toDraft(mapOf("body" to cleanedObject(input)))

            isReminderRequest(input) ->
                MobileActionFunctions.SCHEDULE_REMINDER.toDraft(reminderParameters(input))

            "剪贴板" in input || "clipboard" in normalized ->
                MobileActionFunctions.READ_CLIPBOARD.toDraft(emptyMap())

            isShareTextRequest(input) ->
                MobileActionFunctions.SHARE_TEXT.toDraft(shareTextParameters(input))

            isCalendarAvailabilityRequest(input) && calendarWindowParameters != null ->
                MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY.toDraft(calendarWindowParameters)

            "日程" in input || "calendar" in normalized || "提醒" in input ->
                MobileActionFunctions.CREATE_CALENDAR_EVENT.toDraft(mapOf("title" to cleanedObject(input)))

            "联系人" in input || "contact" in normalized ->
                MobileActionFunctions.CREATE_CONTACT_DRAFT.toDraft(mapOf("name" to cleanedObject(input)))

            "手电筒" in input || "flashlight" in normalized ->
                MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS.toDraft(emptyMap())

            isWebSearchRequest(input) ->
                MobileActionFunctions.WEB_SEARCH.toDraft(mapOf("query" to cleanedWebSearchQuery(input)))

            else -> null
        }
    }

    private fun String.toDraft(parameters: Map<String, String>): ActionDraft =
        ActionDraft(
            functionName = this,
            title = titleFor(this),
            summary = summaryFor(this, parameters),
            parameters = parameters,
            requiresConfirmation = true,
        )

    private fun titleFor(functionName: String): String =
        when (functionName) {
            MobileActionFunctions.OPEN_WIFI_SETTINGS -> "打开 Wi-Fi 设置"
            MobileActionFunctions.SEARCH_MAPS -> "地图搜索"
            MobileActionFunctions.WEB_SEARCH -> "Web 搜索"
            MobileActionFunctions.COMPOSE_EMAIL -> "邮件草稿"
            MobileActionFunctions.CREATE_CALENDAR_EVENT -> "日程草稿"
            MobileActionFunctions.CREATE_CONTACT_DRAFT -> "联系人草稿"
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS -> "打开手电筒设置"
            MobileActionFunctions.SCHEDULE_REMINDER -> "后台提醒"
            MobileActionFunctions.READ_CLIPBOARD -> "读取剪贴板"
            MobileActionFunctions.SHARE_TEXT -> "系统分享"
            MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY -> "查询日历忙闲"
            else -> "动作草稿"
        }

    private fun summaryFor(functionName: String, parameters: Map<String, String>): String =
        when (functionName) {
            MobileActionFunctions.OPEN_WIFI_SETTINGS -> "将打开系统 Wi-Fi 设置页。"
            MobileActionFunctions.SEARCH_MAPS -> "将在地图中搜索：${parameters["query"].orEmpty()}"
            MobileActionFunctions.WEB_SEARCH -> "将在浏览器中搜索：${parameters["query"].orEmpty()}"
            MobileActionFunctions.COMPOSE_EMAIL -> "将打开邮件 App 并填入草稿内容。"
            MobileActionFunctions.CREATE_CALENDAR_EVENT -> "将打开日历新建事件页面。"
            MobileActionFunctions.CREATE_CONTACT_DRAFT -> "将打开联系人新建页面。"
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS -> "将打开系统设置，由你手动确认手电筒相关操作。"
            MobileActionFunctions.SCHEDULE_REMINDER ->
                "将在 ${parameters["delayMinutes"].orEmpty()} 分钟后提醒：${parameters["title"].orEmpty()}"
            MobileActionFunctions.READ_CLIPBOARD -> "将读取当前剪贴板文本。"
            MobileActionFunctions.SHARE_TEXT -> "将打开系统分享面板并填入文本。"
            MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY ->
                "将只读查询本机日历忙闲：${parameters["start"].orEmpty()} 到 ${parameters["end"].orEmpty()}"
            else -> "将打开系统页面完成这个动作。"
        }

    private fun cleanedObject(input: String): String =
        input.trim()
            .removePrefix("请")
            .removePrefix("帮我")
            .trim()
            .ifBlank { input.trim() }

    private fun cleanedWebSearchQuery(input: String): String {
        val cleaned = cleanedObject(input)
        return cleaned
            .replace(Regex("""^百度一下\s*[:：]?\s*"""), "")
            .replace(Regex("""^(网页|网络|互联网|上网|网上)?\s*(搜索|搜|查)(一下|一搜|一查)?\s*[:：]?\s*"""), "")
            .replace(Regex("""^(web\s+search|search\s+the\s+web|search\s+online|look\s+up|google|bing)\s+(for\s+)?""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { cleaned }
    }

    private fun isWebSearchRequest(input: String): Boolean {
        val normalized = input.lowercase()
        return listOf(
            "网页搜索",
            "网络搜索",
            "互联网搜索",
            "上网搜",
            "网上搜",
            "搜索一下",
            "搜一下",
            "搜一搜",
            "查一下",
            "查一查",
            "百度一下",
        ).any { it in input } || Regex("""\b(web search|search the web|search online|look up|google|bing)\b""")
            .containsMatchIn(normalized)
    }

    private fun isReminderRequest(input: String): Boolean =
        ("提醒" in input || "remind" in input.lowercase()) &&
            (REMINDER_MINUTES_PATTERN.containsMatchIn(input) || REMINDER_HOURS_PATTERN.containsMatchIn(input))

    private fun reminderParameters(input: String): Map<String, String> {
        val delayMinutes = extractDelayMinutes(input)
        val title = cleanedReminderTitle(input)
        return mapOf(
            "title" to title,
            "body" to input.trim(),
            "delayMinutes" to delayMinutes.toString(),
        )
    }

    private fun extractDelayMinutes(input: String): Long {
        REMINDER_HOURS_PATTERN.find(input)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { hours ->
            return (hours * 60L).coerceAtLeast(1L)
        }
        REMINDER_MINUTES_PATTERN.find(input)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { minutes ->
            return minutes.coerceAtLeast(1L)
        }
        return 1L
    }

    private fun cleanedReminderTitle(input: String): String =
        input.trim()
            .replace(Regex("""^(请)?(帮我)?提醒我\s*"""), "")
            .replace(Regex("""\d+\s*(分钟|小时)后"""), "")
            .replace(Regex("""(?i)remind me (in )?"""), "")
            .replace(Regex("""(?i)in \d+\s*(minutes?|hours?)"""), "")
            .trim { it <= ' ' || it == '，' || it == ',' || it == ':' || it == '：' }
            .ifBlank { cleanedObject(input) }

    private fun isShareTextRequest(input: String): Boolean {
        val normalized = input.lowercase()
        return listOf("分享这段", "分享以下", "分享文字", "分享内容", "分享到", "分享出去")
            .any { it in input } ||
            Regex("""\bshare\s+(this\s+)?(text|message|content)\b""").containsMatchIn(normalized)
    }

    private fun shareTextParameters(input: String): Map<String, String> {
        val cleaned = cleanedObject(input)
            .replace(Regex("""^分享(这段|以下)?\s*(文字|内容)?\s*(出去|一下)?\s*[:：]?\s*"""), "")
            .replace(Regex("""^(把|将)?\s*(这段|以下)?\s*(文字|内容)?\s*分享(出去|一下)?\s*[:：]?\s*"""), "")
            .replace(Regex("""(?i)^share\s+(this\s+)?(text\s+)?"""), "")
            .trim()
            .ifBlank { cleanedObject(input) }
        return mapOf("text" to cleaned)
    }

    private fun isCalendarAvailabilityRequest(input: String): Boolean {
        val normalized = input.lowercase()
        return listOf("忙闲", "空闲", "有空", "日历可用", "日历占用")
            .any { it in input } ||
            Regex("""\b(availability|free/busy|free busy|available|calendar availability)\b""")
                .containsMatchIn(normalized)
    }

    private fun calendarAvailabilityParameters(input: String): Map<String, String>? {
        val matches = ISO_OFFSET_DATE_TIME_PATTERN.findAll(input).map { it.value }.take(2).toList()
        if (matches.size < 2) return null
        return mapOf(
            "start" to matches[0],
            "end" to matches[1],
        )
    }

    private fun parseJsonLikeObject(raw: String): Map<String, String> {
        val content = raw.trim().removePrefix("{").removeSuffix("}")
        if (content.isBlank()) return emptyMap()
        return KEY_VALUE_PATTERN.findAll(content)
            .associate { match ->
                match.groupValues[1] to match.groupValues[2]
            }
    }

    private companion object {
        val CALL_PATTERN = Regex("""^call:([a-zA-Z0-9_]+)\s*(\{.*\})$""", RegexOption.DOT_MATCHES_ALL)
        val KEY_VALUE_PATTERN = Regex(""""([^"]+)"\s*:\s*"([^"]*)"""")
        val REMINDER_MINUTES_PATTERN = Regex("""(\d+)\s*(分钟|minutes?|mins?)""", RegexOption.IGNORE_CASE)
        val REMINDER_HOURS_PATTERN = Regex("""(\d+)\s*(小时|hours?|hrs?)""", RegexOption.IGNORE_CASE)
        val ISO_OFFSET_DATE_TIME_PATTERN =
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})""")
    }
}
