package com.bytedance.zgx.solin.action

import com.bytedance.zgx.solin.device.CalendarAvailabilityQuery
import com.bytedance.zgx.solin.device.CalendarAvailabilityQueryValidation

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
            requiresConfirmation = true,
        )
    }

    private fun cleanedSearchQuery(input: String): String {
        val cleaned = cleanedObject(input)
            .replace(Regex("""^(?:打开|启动|进入)?\s*(?:高德地图|高德|地图|maps?)\s*(?:搜索|搜一下|搜|查询|查找|查)\s*[:：]?\s*""", RegexOption.IGNORE_CASE), "")
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
        if (input.startsWithActionNegation() || input.looksLikeDiscussion()) return false
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
        if (input.startsWithActionNegation() || input.looksLikeDiscussion()) return false
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

internal object DeviceSettingsActionParser {
    private val wifiPattern =
        Regex("""(?i)(^|[^a-z0-9])(?:wi[-\s]?fi|wifi|wlan|wireless)(?=$|[^a-z0-9])""")
    private val englishWifiSettingsPattern =
        Regex("""\b(?:(?:open|show|go\s+to|launch)\s+(?:the\s+)?(?:wi[-\s]?fi|wifi|wlan|wireless)\s+(?:settings?|preferences?)|(?:wi[-\s]?fi|wifi|wlan|wireless)\s+(?:settings?|preferences?))\b""", RegexOption.IGNORE_CASE)
    private val englishFlashlightSettingsPattern =
        Regex("""\b(?:(?:open|show|go\s+to|launch)\s+(?:the\s+)?(?:flashlight|torch)\s+(?:settings?|preferences?)|(?:flashlight|torch)\s+(?:settings?|preferences?))\b""", RegexOption.IGNORE_CASE)
    private val englishUsageAccessPattern =
        Regex("""\b(?:(?:open|show|go\s+to|launch|grant)\s+(?:the\s+)?(?:usage\s+access|usage\s+stats(?:\s+permission)?)(?:\s+(?:settings?|permissions?|preferences?))?|(?:usage\s+access|usage\s+stats(?:\s+permission)?)\s+(?:settings?|preferences?))\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean = targetTool(input) != null

    fun draft(input: String): ActionDraft {
        val toolName = targetTool(input) ?: MobileActionFunctions.OPEN_WIFI_SETTINGS
        return when (toolName) {
            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS -> ActionDraft(
                functionName = MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
                title = "打开使用情况访问权限设置",
                summary = "将打开系统使用情况访问权限设置页，由你手动为Solin授权。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS -> ActionDraft(
                functionName = MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
                title = "打开手电筒设置",
                summary = "将打开系统设置，由你手动确认手电筒相关操作。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            else -> ActionDraft(
                functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                title = "打开 Wi-Fi 设置",
                summary = "将打开系统 Wi-Fi 设置页。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
        }
    }

    private fun targetTool(input: String): String? {
        if (input.looksLikeDiscussion() || input.looksLikeDeviceSettingsNonAction()) return null
        if (input.looksLikeExplicitAppNameOpen()) return null
        return when {
            input.requestsWifiSettings() -> MobileActionFunctions.OPEN_WIFI_SETTINGS
            input.requestsUsageAccessSettings() -> MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS
            input.requestsFlashlightSettings() -> MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS
            else -> null
        }
    }

    private fun String.looksLikeExplicitAppNameOpen(): Boolean {
        val normalized = lowercase()
        val referencesApp = listOf("app", "应用", "应用程序").any { it in this } ||
            Regex("""\bapp(?:lication)?\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        val namesTarget = listOf("名为", "叫做", "叫").any { it in this } ||
            Regex("""\b(?:named|called)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        return referencesApp && namesTarget
    }

    private fun String.requestsWifiSettings(): Boolean {
        val normalized = lowercase()
        val referencesWifi = wifiPattern.containsMatchIn(normalized) || "无线" in this
        val referencesSettings = "设置" in this ||
            Regex("""\b(settings?|preferences?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        val hasChineseOpenTrigger = listOf("打开", "开启", "进入", "跳转", "前往", "去", "设置").any { it in this }
        val hasEnglishOpenTrigger =
            Regex("""\b(?:open|show|go\s+to|launch|enable|turn\s+on)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(normalized)
        return referencesWifi && (
            (referencesSettings && (hasChineseOpenTrigger || englishWifiSettingsPattern.containsMatchIn(normalized))) ||
                hasChineseOpenTrigger ||
                hasEnglishOpenTrigger
            )
    }

    private fun String.requestsFlashlightSettings(): Boolean {
        val normalized = lowercase()
        val referencesFlashlight = "手电筒" in this ||
            Regex("""\b(flashlight|torch)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        val referencesSettings = "设置" in this ||
            Regex("""\b(settings?|preferences?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        return referencesFlashlight && referencesSettings && (
            listOf("打开", "进入", "跳转", "前往", "去", "设置").any { it in this } ||
                englishFlashlightSettingsPattern.containsMatchIn(normalized)
            )
    }

    private fun String.requestsUsageAccessSettings(): Boolean {
        val normalized = lowercase()
        val referencesUsageAccess = listOf(
            "使用情况访问权限",
            "应用使用情况权限",
            "查看应用使用情况",
        ).any { it in this } ||
            Regex("""\b(?:usage\s+access|usage\s+stats(?:\s+permission)?)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(normalized)
        return referencesUsageAccess && (
            listOf("打开", "进入", "跳转", "前往", "去", "设置", "开启", "授权").any { it in this } ||
                englishUsageAccessPattern.containsMatchIn(normalized)
            )
    }
}

internal object SystemSettingsActionParser {
    private val englishOpenPattern =
        Regex("""\b(?:open|show|go\s+to|launch|enable|turn\s+on)\b""", RegexOption.IGNORE_CASE)
    private val englishSettingsPattern =
        Regex("""\b(?:settings?|preferences?)\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean = targetFor(input) != null

    fun draft(input: String): ActionDraft {
        val target = targetFor(input) ?: SystemSettingsTargets.GENERAL
        val label = SystemSettingsTargets.pageLabel(target)
        return ActionDraft(
            functionName = MobileActionFunctions.OPEN_SYSTEM_SETTINGS,
            title = "打开 $label",
            summary = "将打开$label，由你手动完成后续操作。",
            parameters = mapOf("target" to target),
            requiresConfirmation = true,
        )
    }

    private fun targetFor(input: String): String? {
        if (input.looksLikeDiscussion() || input.looksLikeDeviceSettingsNonAction()) return null
        if (input.looksLikeAppScopedSystemSettings()) return null
        val target = referencedTarget(input) ?: return null
        return target.takeIf { input.hasSettingsOpenIntent() }
    }

    private fun referencedTarget(input: String): String? {
        val normalized = input.lowercase()
        return when {
            listOf("蓝牙").any { it in input } ||
                Regex("""\bbluetooth\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ->
                SystemSettingsTargets.BLUETOOTH

            listOf("定位", "位置信息", "位置服务", "gps", "GPS").any { it in input } ||
                Regex("""\blocation\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ->
                SystemSettingsTargets.LOCATION

            listOf("通知设置", "通知权限", "系统通知").any { it in input } ||
                Regex("""\bnotifications?\s+(?:settings?|preferences?|permissions?)\b|\b(?:open|show|go\s+to|launch)\s+(?:the\s+)?notifications?\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(normalized) ->
                SystemSettingsTargets.NOTIFICATION

            listOf("显示设置", "屏幕设置", "亮度", "屏幕亮度").any { it in input } ||
                Regex("""\b(?:display|brightness)\s+(?:settings?|preferences?)\b|\b(?:open|show|go\s+to|launch)\s+(?:the\s+)?(?:display|brightness)\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(normalized) ->
                SystemSettingsTargets.DISPLAY

            listOf("声音设置", "音量设置", "铃声设置").any { it in input } ||
                Regex("""\b(?:sound|volume|ringtone)\s+(?:settings?|preferences?)\b|\b(?:open|show|go\s+to|launch)\s+(?:the\s+)?(?:sound|volume|ringtone)\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(normalized) ->
                SystemSettingsTargets.SOUND

            listOf("省电", "省电模式", "电池设置", "电量设置").any { it in input } ||
                Regex("""\b(?:battery|battery\s+saver|power\s+saver)\s+(?:settings?|preferences?)\b|\b(?:open|show|go\s+to|launch)\s+(?:the\s+)?(?:battery|battery\s+saver|power\s+saver)\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(normalized) ->
                SystemSettingsTargets.BATTERY_SAVER

            listOf("网络设置", "移动网络", "蜂窝网络", "数据网络").any { it in input } ||
                Regex("""\b(?:network|mobile\s+data|cellular)\s+(?:settings?|preferences?)\b|\b(?:open|show|go\s+to|launch)\s+(?:the\s+)?(?:network|mobile\s+data|cellular)\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(normalized) ->
                SystemSettingsTargets.NETWORK

            listOf("飞行模式").any { it in input } ||
                Regex("""\bairplane\s+mode\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ->
                SystemSettingsTargets.AIRPLANE_MODE

            listOf("输入法", "键盘设置").any { it in input } ||
                Regex("""\b(?:input\s+method|keyboard)\s+(?:settings?|preferences?)\b|\b(?:open|show|go\s+to|launch)\s+(?:the\s+)?(?:input\s+method|keyboard)\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(normalized) ->
                SystemSettingsTargets.INPUT_METHOD

            listOf("无障碍", "辅助功能").any { it in input } ||
                Regex("""\baccessibility\s+(?:settings?|preferences?)\b|\b(?:open|show|go\s+to|launch)\s+(?:the\s+)?accessibility\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(normalized) ->
                SystemSettingsTargets.ACCESSIBILITY

            listOf("系统设置", "手机设置", "设置页").any { it in input } ||
                Regex("""\b(?:system|phone|android)\s+(?:settings?|preferences?)\b|\b(?:open|show|go\s+to|launch)\s+(?:the\s+)?settings?\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(normalized) ->
                SystemSettingsTargets.GENERAL

            else -> null
        }
    }

    private fun String.hasSettingsOpenIntent(): Boolean {
        val normalized = lowercase()
        return listOf("打开", "开启", "进入", "跳转", "前往", "去", "设置", "调出", "启动").any { it in this } ||
            englishOpenPattern.containsMatchIn(normalized) ||
            englishSettingsPattern.containsMatchIn(normalized)
    }

    private fun String.looksLikeAppScopedSystemSettings(): Boolean {
        val normalized = lowercase()
        val referencesAppScope = listOf(
            "微信",
            "wechat",
            "支付宝",
            "alipay",
            "抖音",
            "douyin",
            "淘宝",
            "taobao",
            "应用",
            "app",
        ).any { it in normalized }
        val referencesScopedSettings = listOf("通知设置", "权限", "应用设置", "app settings").any { it in normalized }
        return referencesAppScope && referencesScopedSettings
    }
}

internal object WebSearchActionParser {
    private val englishPattern =
        Regex("""\b(web\s+search|search\s+the\s+web|search\s+online|look\s+up|google|bing)\b""", RegexOption.IGNORE_CASE)
    private val discussionPrefixPattern =
        Regex("""^\s*(what\s+is|what\s+does|explain|how\s+do\s+i|how\s+to)\b""", RegexOption.IGNORE_CASE)
    private val negativeEnglishPattern =
        Regex("""\b(do\s+not|don't|dont)\s+(?:search|look\s+up|google|bing)\b""", RegexOption.IGNORE_CASE)
    private val cjkAsciiBoundaryPattern =
        Regex("""(?<=[\u4E00-\u9FFF])(?=[A-Za-z0-9])|(?<=[A-Za-z0-9])(?=[\u4E00-\u9FFF])""")
    private val searchQueryPunctuationPattern = Regex("""[，。！？、；;:：,.?()（）\[\]{}"'“”]+""")
    private val chineseSearchQuestionNoisePattern =
        Regex("""(搜索一下|搜一下|查一下|看一下|帮我看看|帮我查查|帮我|帮忙|麻烦|请问|请|给我|告诉我|你能不能|能不能|你能|可以|分别是什么|是什么|有哪些|怎么样|如何|多少|一下|分别|吗|呢)""")
    private val englishSearchQuestionNoisePattern =
        Regex("""(?i)\b(?:please|can\s+you|could\s+you|search\s+for|search|look\s+up|google|bing|tell\s+me|what\s+is|what\s+are|what's|which|who\s+is)\b""")

    fun matches(input: String): Boolean {
        if (input.looksLikeWebSearchNonAction()) return false
        val normalized = input.lowercase()
        val hasExplicitChineseTrigger = listOf(
            "网页搜索",
            "网络搜索",
            "互联网搜索",
            "上网搜",
            "网上搜",
            "上网查",
            "网上查",
            "网页查",
            "网络查",
            "搜索一下",
            "搜一下",
            "搜一搜",
            "百度一下",
        ).any { it in input }
        val hasEnglishTrigger = englishPattern.containsMatchIn(normalized)
        val hasWeatherTrigger = input.looksLikeWeatherInfoQuery()
        if (!hasExplicitChineseTrigger && !hasEnglishTrigger && !hasWeatherTrigger) return false
        return if (hasWeatherTrigger && !hasExplicitChineseTrigger && !hasEnglishTrigger) {
            strippedWeatherQuery(input).isMeaningfulSearchQuery()
        } else {
            strippedWebSearchQuery(input).isMeaningfulSearchQuery()
        }
    }

    fun draft(input: String): ActionDraft {
        val query = cleanedWebSearchQuery(input)
        return ActionDraft(
            functionName = MobileActionFunctions.WEB_SEARCH,
            title = "Web 搜索",
            summary = "将使用 Web 搜索工具查询并整理结果：$query",
            parameters = mapOf("query" to query),
            requiresConfirmation = false,
        )
    }

    private fun cleanedWebSearchQuery(input: String): String {
        val stripped = strippedWebSearchQuery(input)
        val keywordQuery = stripped.keywordizedWebSearchQuery()
        return keywordQuery.ifBlank { cleanedObject(input) }
    }

    private fun strippedWebSearchQuery(input: String): String {
        val cleaned = cleanedObject(input)
        return cleaned
            .replace(Regex("""^百度一下\s*[:：]?\s*"""), "")
            .replace(Regex("""^(网页|网络|互联网|上网|网上)?\s*(搜索|搜|查)(一下|一搜|一查)?\s*[:：]?\s*"""), "")
            .replace(
                Regex(
                    """^(web\s+search|search\s+the\s+web|search\s+online|look\s+up|google|bing)\s+(for\s+)?""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .trim()
    }

    private fun String.isMeaningfulSearchQuery(): Boolean =
        isNotBlank() && this !in setOf("是什么", "什么意思", "怎么实现", "如何实现", "怎么用")

    private fun String.looksLikeWeatherInfoQuery(): Boolean {
        val normalized = lowercase()
        return listOf("天气", "气温", "温度", "降雨", "下雨", "预报").any { it in this } ||
            Regex("""\b(?:weather|temperature|rain|forecast)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(normalized)
    }

    private fun strippedWeatherQuery(input: String): String =
        cleanedObject(input)
            .replace(Regex("""(?i)\b(?:what(?:'s| is)?|how(?:'s| is)?|tell me|check|look up)\b"""), " ")
            .replace(Regex("""(?i)\b(?:weather|temperature|rain|forecast)\b"""), " ")
            .replace(Regex("""(?i)\b(?:in|for|today|now|currently)\b"""), " ")
            .replace(Regex("""(天气|气温|温度|降雨|下雨|预报|怎么样|如何|多少|现在|今天|查询|查一下|搜一下|帮我|请|一下)"""), " ")
            .replace(Regex("""[，。！？、:：?]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun String.keywordizedWebSearchQuery(): String =
        replace(cjkAsciiBoundaryPattern, " ")
            .replace(searchQueryPunctuationPattern, " ")
            .replace(englishSearchQuestionNoisePattern, " ")
            .replace(chineseSearchQuestionNoisePattern, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun String.looksLikeWebSearchNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            "不要搜索" in this ||
            "别搜索" in this ||
            "不要搜" in this ||
            "别搜" in this ||
            "不要查" in this ||
            "别查" in this ||
            "搜索功能" in this ||
            "搜索框" in this ||
            "搜索页面" in this ||
            "网页搜索是什么" in this ||
            ("错误原因" in this && "吗" in this) ||
            negativeEnglishPattern.containsMatchIn(normalized) ||
            (discussionPrefixPattern.containsMatchIn(normalized) && englishPattern.containsMatchIn(normalized))
    }
}

internal object RecentFilesActionParser {
    private val englishMediaPattern =
        Regex(
            """\b(recent|latest)\b.*\b(images?|screenshots?|screen\s+captures?|videos?|audios?|photos?)\b""",
            RegexOption.IGNORE_CASE,
    )

    fun matches(input: String, includeNonMediaKinds: Boolean = false): Boolean {
        if (input.looksLikeRecentFileNonAction()) return false
        if (input.looksLikeRecentFileContentExtraction()) return false
        val normalized = input.lowercase()
        val hasChineseMediaTrigger = listOf(
            "最近图片",
            "最近照片",
            "最近截图",
            "最近截屏",
            "最近视频",
            "最近音频",
        ).any { it in input } ||
            Regex("""最近\s*\d{0,2}\s*(?:个|份|条|张)?\s*(图片|照片|截图|截屏|视频|音频)""")
                .containsMatchIn(input)
        val hasEnglishMediaTrigger = englishMediaPattern.containsMatchIn(normalized)
        if (hasChineseMediaTrigger || hasEnglishMediaTrigger) return true

        if (!includeNonMediaKinds) return false
        return listOf(
            "最近文件",
            "最近文档",
            "最近下载",
            "查询文件",
            "文件列表",
            "最近文件列表",
        ).any { it in input } ||
            Regex("""最近\s*\d{0,2}\s*(?:个|份|条|张)?\s*(文件|文档|下载)""")
                .containsMatchIn(input) ||
            Regex("""\b(recent|latest)\b.*\b(files?|documents?)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(normalized)
    }

    fun draft(input: String): ActionDraft {
        val parameters = parameters(input)
        val kind = parameters["kind"].orEmpty().ifBlank { "all" }
        return ActionDraft(
            functionName = MobileActionFunctions.QUERY_RECENT_FILES,
            title = "查询最近文件",
            summary = summary(kind, parameters["maxCount"]),
            parameters = parameters,
            requiresConfirmation = true,
        )
    }

    private fun parameters(input: String): Map<String, String> {
        val normalized = input.lowercase()
        val cleaned = input.replace(Regex("\\s+"), "")
        val countMatch = Regex("最近(\\d{1,2})条?(?:个|份)?").find(cleaned)
            ?: Regex(
                "(?:recent|latest)\\s+(\\d{1,2})\\s+" +
                    "(?:files?|documents?|images?|screenshots?|screen\\s+captures?|videos?|audios?|photos?)",
            )
                .find(normalized)

        val maxCount = countMatch?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.toString()

        val kind = when {
            "截图" in input || "截屏" in input || "screenshot" in normalized || "screen capture" in normalized ->
                "screenshots"
            "图片" in input || "照片" in input || "image" in normalized || "photo" in normalized -> "images"
            "视频" in input || "video" in normalized || "vid" in normalized -> "videos"
            "音频" in input || "audio" in normalized -> "audio"
            "文档" in input || "document" in normalized || "doc" in normalized -> "documents"
            "下载" in input || "download" in normalized -> "downloads"
            "其他" in input || "other" in normalized -> "others"
            else -> "all"
        }

        return buildMap {
            if (maxCount != null) {
                put("maxCount", maxCount)
            }
            if (kind != "all") {
                put("kind", kind)
            }
        }
    }

    private fun summary(kind: String, maxCount: String?): String {
        val label = kindLabel(kind)
        val base = if (maxCount.isNullOrBlank()) {
            "将读取最近的${label}文件摘要"
        } else {
            "将读取最近 ${maxCount} 个${label}文件摘要"
        }
        val boundary = when (kind) {
            "all" -> "仅返回文件名、类型、大小和修改时间；Android 13 及以上仅包含已授权的图片、视频或音频媒体"
            "documents", "downloads", "others" ->
                "仅返回文件名、类型、大小和修改时间；Android 13 及以上需要通过系统文件选择器授权非媒体文件"
            else -> "仅返回文件名、类型、大小和修改时间"
        }
        return "$base（$boundary）。"
    }

    private fun kindLabel(kind: String): String =
        when (kind) {
            "all" -> "全部"
            "screenshots" -> "截图"
            "images" -> "图片"
            "videos" -> "视频"
            "audio" -> "音频"
            "documents" -> "文档"
            "downloads" -> "下载"
            "others" -> "其他"
            else -> kind
        }

    private fun String.looksLikeRecentFileNonAction(): Boolean {
        val normalized = lowercase()
        val mentionsRecentFiles = ("最近" in this && listOf(
            "文件",
            "文档",
            "下载",
            "图片",
            "照片",
            "截图",
            "截屏",
            "视频",
            "音频",
        ).any { it in this }) ||
            Regex(
                """\b(?:recent|latest|last|most\s+recent)\b.*\b(?:files?|documents?|images?|photos?|pictures?|screenshots?|screen\s+captures?|videos?|audios?)\b""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(normalized)
        val mentionsFileQuery = listOf(
            "查询文件",
            "文件列表",
            "文件 API",
            "文件API",
            "文件接口",
            "文件权限",
        ).any { it in this } ||
            normalized.contains(Regex("""\bfiles?\s+(?:api|implementation|docs?|documentation|permissions?|list)\b"""))
        if (!mentionsRecentFiles && !mentionsFileQuery) return false

        val hasNegativeIntent = listOf("不要", "别", "请勿", "不需要", "不想", "不用", "不必").any { it in this } ||
            normalized.contains(Regex("""\b(?:do\s+not|don't|dont|never)\b"""))
        val hasDiscussionIntent = listOf(
            "权限",
            "怎么",
            "如何",
            "实现",
            "api",
            "API",
            "接口",
            "代码",
            "功能",
            "是什么",
            "什么意思",
        ).any { it in this } ||
            normalized.contains(
                Regex(
                    """\b(?:how\s+(?:do|can|to)|what\s+is|explain|implement|implementation|api|permissions?|docs?|documentation|code|feature)\b""",
                ),
            )
        return hasNegativeIntent || hasDiscussionIntent
    }

    private fun String.looksLikeRecentFileContentExtraction(): Boolean {
        val normalized = lowercase()
        val mentionsRecentMedia = ("最近" in this && listOf("图片", "照片", "截图", "截屏", "相册").any { it in this }) ||
            Regex("""\b(recent|latest)\b.*\b(images?|photos?|pictures?|screenshots?|screen\s+captures?)\b""")
                .containsMatchIn(normalized)
        val asksForTextExtraction = listOf(
            "识别",
            "提取",
            "摘录",
            "读取文字",
            "文字",
            "文本",
            "ocr",
            "text",
        ).any { marker -> marker in normalized }
        val asksForVisualUnderstanding = listOf(
            "描述",
            "分析",
            "看图",
            "图片内容",
            "照片内容",
            "图片里有什么",
            "照片里有什么",
        ).any { marker -> marker in this } ||
            normalized.contains(Regex("""\b(describe|analy[sz]e|what\s+is\s+in|what's\s+in)\b.*\b(images?|photos?|pictures?)\b"""))
        return mentionsRecentMedia && (asksForTextExtraction || asksForVisualUnderstanding)
    }
}

internal object RecentScreenshotOcrActionParser {
    private val englishSingularScreenshotPattern =
        Regex("""\b(?:recent|latest|last|most\s+recent)\s+(?:the\s+)?(?:screenshot|screen\s+capture)\b""", RegexOption.IGNORE_CASE)
    private val englishCountPattern =
        Regex("""\b(?:recent|latest|last)\s+(\d{1,2})\s+(?:screenshots?|screen\s+captures?)\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        if (input.looksLikeRecentScreenshotOcrNonAction()) return false
        val normalized = input.lowercase()
        val count = requestedScreenshotCount(input)
        if (count != null && count != 1) return false
        return input.mentionsRecentScreenshotForOcr() && normalized.asksForOcrTextExtraction()
    }

    fun draft(input: String): ActionDraft =
        ActionDraft(
            functionName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            title = "读取最近截图 OCR",
            summary = "将读取最近 1 张截图的像素并在本地提取 OCR 文本；不会保存图片、URI 或路径。",
            parameters = mapOf("maxCount" to "1"),
            requiresConfirmation = true,
        )

    private fun String.mentionsRecentScreenshotForOcr(): Boolean {
        val normalized = lowercase()
        val cleaned = replace(Regex("\\s+"), "")
        return ("最近" in this && ("截图" in this || "截屏" in this)) ||
            Regex("""最近1(?:张|个)?(?:截图|截屏)""").containsMatchIn(cleaned) ||
            englishSingularScreenshotPattern.containsMatchIn(normalized) ||
            englishCountPattern.containsMatchIn(normalized)
    }

    private fun String.asksForOcrTextExtraction(): Boolean {
        val normalized = lowercase()
        return listOf(
            "识别",
            "提取",
            "摘录",
            "读取文字",
            "读文字",
            "文字",
            "文本",
            "ocr",
        ).any { marker -> marker in normalized } ||
            normalized.contains(Regex("""\b(read|extract|recognize|scan)\b.*\btext\b""")) ||
            normalized.contains(Regex("""\btext\b.*\b(?:from|in)\b.*\b(?:screenshot|screen\s+capture)\b"""))
    }

    private fun requestedScreenshotCount(input: String): Int? {
        val normalized = input.lowercase()
        val cleaned = input.replace(Regex("\\s+"), "")
        return Regex("""最近(\d{1,2})(?:张|个)?(?:截图|截屏)""").find(cleaned)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: englishCountPattern.find(normalized)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun String.looksLikeRecentScreenshotOcrNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
            "不要识别",
            "不要提取",
            "不要读取",
            "别识别",
            "别提取",
            "别读取",
            "最近截图权限",
            "截图权限",
            "截图 OCR 怎么",
            "截图OCR怎么",
            "怎么实现",
            "如何实现",
            "怎么总结",
            "如何总结",
            "怎样总结",
            "怎么分享",
            "如何分享",
            "怎样分享",
            "怎么设计",
            "所有截图",
            "全部截图",
            "多张截图",
            "当前屏幕",
            "当前界面",
        ).any { it in this } ||
            Regex("""最近[二两三四五六七八九十百千万多几]+(?:张|个)?(?:截图|截屏)""").containsMatchIn(this) ||
            normalized.contains(Regex("""\b(do\s+not|don't|dont)\s+(?:read|extract|recognize|scan)\b.*\b(?:screenshot|screen\s+capture)\b""")) ||
            normalized.contains(Regex("""\b(?:screenshot|screen\s+capture)\s+ocr\s+(?:api|implementation|architecture|design|schema|tests?|parser|docs?|permissions?)\b""")) ||
            normalized.contains(Regex("""\b(?:what\s+is|explain|describe|meaning|how\s+do\s+i|how\s+to|implement|design)\b.*\b(?:screenshot|screen\s+capture)\s+ocr\b""")) ||
            normalized.contains(Regex("""\b(?:all|multiple|many)\s+(?:screenshots?|screen\s+captures?)\b"""))
    }
}

internal object RecentImageOcrActionParser {
    private val englishRecentImagePattern =
        Regex("""\b(?:recent|latest|last|most\s+recent)\s+(?:\d{1,2}\s+)?(?:images?|photos?|pictures?)\b""", RegexOption.IGNORE_CASE)
    private val englishCountPattern =
        Regex("""\b(?:recent|latest|last)\s+(\d{1,2})\s+(?:images?|photos?|pictures?)\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        if (input.looksLikeRecentImageOcrNonAction()) return false
        val count = requestedImageCount(input)
        if (count != null && count !in 1..3) return false
        return input.mentionsRecentImageForOcr() && input.asksForOcrTextExtraction()
    }

    fun draft(input: String): ActionDraft {
        val maxCount = requestedImageCount(input)?.coerceIn(1, 3) ?: 3
        return ActionDraft(
            functionName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            title = "读取最近图片 OCR",
            summary = "将扫描最近 ${maxCount} 张图片并在本地提取第一条 OCR 文本；不会保存图片、URI 或路径。",
            parameters = mapOf("maxCount" to maxCount.toString()),
            requiresConfirmation = true,
        )
    }

    private fun String.mentionsRecentImageForOcr(): Boolean {
        val normalized = lowercase()
        val cleaned = replace(Regex("\\s+"), "")
        return ("最近" in this && listOf("图片", "照片", "相册").any { it in this }) ||
            Regex("""最近[一二两三123]?(?:张|个)?(?:图片|照片)""").containsMatchIn(cleaned) ||
            englishRecentImagePattern.containsMatchIn(normalized)
    }

    private fun String.asksForOcrTextExtraction(): Boolean {
        val normalized = lowercase()
        return listOf(
            "识别",
            "提取",
            "摘录",
            "读取文字",
            "读文字",
            "文字",
            "文本",
            "ocr",
        ).any { marker -> marker in normalized } ||
            normalized.contains(Regex("""\b(read|extract|recognize|scan)\b.*\btext\b""")) ||
            normalized.contains(Regex("""\btext\b.*\b(?:from|in)\b.*\b(?:images?|photos?|pictures?)\b"""))
    }

    private fun requestedImageCount(input: String): Int? {
        val normalized = input.lowercase()
        val cleaned = input.replace(Regex("\\s+"), "")
        Regex("""最近(\d{1,2})(?:张|个)?(?:图片|照片)""").find(cleaned)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }
        Regex("""最近([一二两三四五六七八九十百千万多几]+)(?:张|个)?(?:图片|照片)""").find(cleaned)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return chineseImageCount(it) }
        return englishCountPattern.find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun chineseImageCount(value: String): Int =
        when (value) {
            "一" -> 1
            "二", "两" -> 2
            "三" -> 3
            else -> 4
        }

    private fun String.looksLikeRecentImageOcrNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
            "不要识别",
            "不要提取",
            "不要读取",
            "别识别",
            "别提取",
            "别读取",
            "图片权限",
            "相册权限",
            "图片 OCR 怎么",
            "图片OCR怎么",
            "怎么实现",
            "如何实现",
            "怎么设计",
            "所有图片",
            "全部图片",
            "大量图片",
            "多张图片",
            "图片里有什么",
            "照片里有什么",
            "描述图片",
            "描述最近图片",
            "分析图片",
            "分析最近图片",
            "看图",
            "图片内容",
        ).any { it in this } ||
            normalized.contains(Regex("""\b(do\s+not|don't|dont)\s+(?:read|extract|recognize|scan)\b.*\b(?:images?|photos?|pictures?)\b""")) ||
            normalized.contains(Regex("""\b(?:image|photo|picture)\s+ocr\s+(?:api|implementation|architecture|design|schema|tests?|parser|docs?|permissions?)\b""")) ||
            normalized.contains(Regex("""\b(?:what\s+is|explain|describe|meaning|how\s+do\s+i|how\s+to|implement|design)\b.*\b(?:image|photo|picture)\s+ocr\b""")) ||
            normalized.contains(Regex("""\b(?:all|multiple|many)\s+(?:images?|photos?|pictures?)\b""")) ||
            normalized.contains(Regex("""\b(?:describe|analy[sz]e|what\s+is\s+in|what's\s+in)\b.*\b(?:images?|photos?|pictures?)\b"""))
    }
}

internal object DeepLinkActionParser {
    private val uriPattern = Regex("""\bhttps://\S+""", RegexOption.IGNORE_CASE)
    private val englishOpenPattern = Regex("""\b(open|visit|go\s+to|launch)\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        if (input.looksLikeDeepLinkNonAction()) return false
        val hasUri = uriPattern.containsMatchIn(input)
        val hasOpenIntent = listOf("打开", "访问", "前往", "跳转", "进入").any { it in input } ||
            englishOpenPattern.containsMatchIn(input)
        return hasUri && hasOpenIntent
    }

    fun draft(input: String): ActionDraft {
        val uri = extractUri(input)
        return ActionDraft(
            functionName = MobileActionFunctions.OPEN_DEEP_LINK,
            title = "打开深链",
            summary = "将打开深度链接：$uri",
            parameters = mapOf("uri" to uri),
            requiresConfirmation = true,
        )
    }

    private fun extractUri(input: String): String =
        cleanUri(uriPattern.find(input)?.value.orEmpty()).ifBlank { cleanedObject(input) }

    private fun cleanUri(raw: String): String =
        raw.trim().trimEnd(*TRAILING_URI_PUNCTUATION.toCharArray())

    private fun String.looksLikeDeepLinkNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
            "不要打开",
            "别打开",
            "不要访问",
            "别访问",
            "解释",
            "说明",
            "什么意思",
            "是什么",
        ).any { it in this } ||
            normalized.contains(Regex("""\b(do\s+not|don't|dont)\s+(?:open|visit|go\s+to|launch)\b""")) ||
            normalized.contains(Regex("""^\s*how\s+do\s+i\s+(?:open|visit|go\s+to|launch)\b""")) ||
            normalized.contains(Regex("""^\s*(what\s+is|explain|describe)\b"""))
    }

    private const val TRAILING_URI_PUNCTUATION = ".,;:!?)]}。！）；】"
}

internal object CameraActionParser {
    private val englishPattern =
        Regex("""\b(?:open|launch|start)\s+(?:the\s+)?camera\b|\b(?:take|capture)\s+(?:a\s+)?(?:photo|picture)\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        if (input.looksLikeCameraNonAction()) return false
        val normalized = input.lowercase()
        val hasChineseTarget = listOf("相机", "摄像头", "摄像机", "拍照").any { it in input }
        val hasChineseTrigger = listOf("打开", "启动", "开启", "拍").any { it in input }
        return (hasChineseTarget && hasChineseTrigger) || englishPattern.containsMatchIn(normalized)
    }

    fun draft(): ActionDraft =
        ActionDraft(
            functionName = MobileActionFunctions.OPEN_CAMERA,
            title = "打开相机",
            summary = "将打开系统相机应用；不会拍照、录像或读取照片。",
            parameters = emptyMap(),
            requiresConfirmation = true,
        )

    private fun String.looksLikeCameraNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
                "不要打开",
                "别打开",
                "不要启动",
                "别启动",
                "不要开启",
                "解释",
                "说明",
                "是什么",
                "什么意思",
                "怎么",
                "如何",
                "代码",
                "组件",
                "接口",
                "权限",
                "api",
                "API",
                "实现",
                "设计",
            ).any { it in this } ||
            normalized.contains(Regex("""\b(do\s+not|don't|dont)\s+(?:open|launch|start|use)\s+(?:the\s+)?camera\b""")) ||
            normalized.contains(Regex("""\b(?:what\s+is|explain|meaning|how\s+do\s+i|how\s+to|implement|design|api|sdk|code|schema|tests?|parser|docs?|architecture|permissions?)\b.*\bcamera\b"""))
    }
}

internal object AppNavigationActionParser {
    private val packageNamePattern =
        Regex("""\b[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+\b""")
    private val uriSchemePattern =
        Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://""")
    private val chineseLaunchPattern =
        Regex("""(?:打开并|打开|启动并|启动)\s*(?:应用|app|应用程序)?""")
    private val englishLaunchPattern =
        Regex("""^\s*(?:please\s+)?(?:open|launch|start)\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean = targetTool(input) != null

    fun draft(input: String): ActionDraft {
        return when (targetTool(input)) {
            MobileActionFunctions.OPEN_APP_DEEP_TARGET -> {
                val packageName = packageNameFromInput(input).orEmpty()
                ActionDraft(
                    functionName = MobileActionFunctions.OPEN_APP_DEEP_TARGET,
                    title = "打开应用深层目标",
                    summary = "将打开应用详情设置：$packageName",
                    parameters = mapOf(
                        AppDeepTargets.TARGET_ID_ARGUMENT to AppDeepTargets.APP_DETAILS_SETTINGS_ID,
                        AppDeepTargets.PACKAGE_NAME_ARGUMENT to packageName,
                    ),
                    requiresConfirmation = true,
                )
            }
            MobileActionFunctions.OPEN_APP_INTENT -> {
                val packageName = packageNamePattern.find(input)?.value.orEmpty()
                ActionDraft(
                    functionName = MobileActionFunctions.OPEN_APP_INTENT,
                    title = "打开应用",
                    summary = "将打开应用启动页：$packageName",
                    parameters = mapOf("packageName" to packageName),
                    requiresConfirmation = true,
                )
            }
            else -> ActionDraft(
                functionName = MobileActionFunctions.OPEN_APP_BY_NAME,
                title = "打开应用",
                summary = "将按本机应用名打开：${appNameFromInput(input).orEmpty()}",
                parameters = mapOf("appName" to appNameFromInput(input).orEmpty()),
                requiresConfirmation = true,
            )
        }
    }

    private fun targetTool(input: String): String? {
        if (input.looksLikeAppNavigationNonAction() || uriSchemePattern.containsMatchIn(input)) return null
        if (input.looksLikeSystemFunctionRatherThanApp()) return null
        if (input.looksLikeWebTargetRatherThanApp()) return null
        if (!input.hasAppLaunchTrigger()) return null
        return if (input.isAppDetailsSettingsTarget()) {
            packageNameFromInput(input) ?: return null
            MobileActionFunctions.OPEN_APP_DEEP_TARGET
        } else if (packageNamePattern.containsMatchIn(input)) {
            MobileActionFunctions.OPEN_APP_INTENT
        } else {
            appNameFromInput(input) ?: return null
            MobileActionFunctions.OPEN_APP_BY_NAME
        }
    }

    private fun packageNameFromInput(input: String): String? {
        val normalized = input.lowercase()
        if (input.isBareAppInfoTarget()) return null
        return knownAppPackages.entries.firstNotNullOfOrNull { (alias, packageName) ->
            if (normalized.contains(alias.lowercase())) packageName else null
        } ?: packageNamePattern.find(input)?.value
    }

    private fun appNameFromInput(input: String): String? {
        if (input.isBareAppInfoTarget() || packageNamePattern.containsMatchIn(input)) return null
        val cleaned = cleanedObject(input)
            .replace(chineseLaunchPattern, "")
            .replace(Regex("""(?i)^\s*(?:open|launch|start)\s+(?:the\s+)?(?:app(?:lication)?\s+)?"""), "")
            .replace(Regex("""^\s*(?:应用|app|应用程序)\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*(?:应用|app|应用程序)\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .trimEnd('。', '.', '！', '!', '，', ',')
        return cleaned.takeIf { candidate ->
            candidate.length in 1..80 &&
                candidate !in setOf("应用", "app", "应用程序", "软件") &&
                !candidate.contains(Regex("""[/:\\]"""))
        }
    }

    private fun String.hasAppLaunchTrigger(): Boolean =
        chineseLaunchPattern.containsMatchIn(this) ||
            englishLaunchPattern.containsMatchIn(this)

    private fun String.isAppDetailsSettingsTarget(): Boolean {
        val normalized = lowercase()
        return listOf(
            "应用详情",
            "应用信息",
            "app info",
            "application details",
            "application info",
        ).any { it in normalized }
    }

    private fun String.isBareAppInfoTarget(): Boolean {
        if (!isAppDetailsSettingsTarget()) return false
        val normalized = lowercase()
        val hasConcreteAppAlias = knownAppPackages.keys
            .filterNot { it in genericMessagingAliases }
            .any { normalized.contains(it.lowercase()) }
        return !hasConcreteAppAlias && !packageNamePattern.containsMatchIn(this)
    }

    private fun String.looksLikeAppNavigationNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
            "不要打开",
            "别打开",
            "不要启动",
            "别启动",
            "不要进入",
            "别进入",
            "解释",
            "说明",
            "是什么",
            "什么意思",
            "在哪里",
            "在哪",
            "哪里",
            "怎么",
            "如何",
            "代码",
            "组件",
            "接口",
            "小程序",
            "应用设置",
            "搜索",
            "搜一下",
            "筛选",
            "然后搜索",
            "支付",
            "收款码",
            "扫一扫",
            "聊天",
            "朋友圈",
            "权限",
            "通知页",
            "通知设置",
            "打不开",
            "无法打开",
            "不能打开",
            "启动失败",
            "打开方式",
            "然后",
            "接着",
            "/",
        ).any { it in this } ||
            normalized.contains(Regex("""\b(do\s+not|don't|dont)\s+(?:open|launch|start)\b""")) ||
            normalized.contains(Regex("""(?:open\s+source|start\s+using|package:|intent:|content:|file:)""")) ||
            normalized.contains(Regex("""\b(what\s+is|explain|meaning|how\s+do\s+i|how\s+to|implement|design|api|sdk|code|schema|tests?|parser|docs?|architecture|activity|extras?|data\s+uri|intent|app\s+settings|then|after\s+that)\b"""))
    }

    private fun String.looksLikeSystemFunctionRatherThanApp(): Boolean {
        val normalized = lowercase()
        val referencesExplicitApp = listOf("应用", "app", "应用程序").any { it in this } ||
            Regex("""\bapp(?:lication)?\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        val referencesFlashlight = "手电筒" in this ||
            Regex("""\b(?:flashlight|torch)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        return referencesFlashlight && !referencesExplicitApp
    }

    private fun String.looksLikeWebTargetRatherThanApp(): Boolean {
        val normalized = lowercase()
        val referencesExplicitApp = listOf("应用", "app", "应用程序").any { it in this } ||
            Regex("""\bapp(?:lication)?\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        val referencesWebTarget = listOf("网页", "网站", "链接", "网址").any { it in this } ||
            Regex("""\b(?:web\s*page|website|link|url)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        return referencesWebTarget && !referencesExplicitApp
    }

    private val knownAppPackages = mapOf(
        "微信" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "支付宝" to "com.eg.android.AlipayGphone",
        "alipay" to "com.eg.android.AlipayGphone",
        "抖音" to "com.ss.android.ugc.aweme",
        "douyin" to "com.ss.android.ugc.aweme",
        "哔哩哔哩" to "tv.danmaku.bili",
        "bilibili" to "tv.danmaku.bili",
        "淘宝" to "com.taobao.taobao",
        "taobao" to "com.taobao.taobao",
        "拼多多" to "com.xunmeng.pinduoduo",
        "pinduoduo" to "com.xunmeng.pinduoduo",
        "pdd" to "com.xunmeng.pinduoduo",
        "京东" to "com.jingdong.app.mall",
        "jd" to "com.jingdong.app.mall",
        "美团" to "com.sankuai.meituan",
        "meituan" to "com.sankuai.meituan",
        "高德地图" to "com.autonavi.minimap",
        "高德" to "com.autonavi.minimap",
        "amap" to "com.autonavi.minimap",
        "gaode" to "com.autonavi.minimap",
        "autonavi" to "com.autonavi.minimap",
        "谷歌地图" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "google map" to "com.google.android.apps.maps",
        "maps" to "com.google.android.apps.maps",
        "地图" to "com.autonavi.minimap",
        "浏览器" to "com.android.browser",
        "browser" to "com.android.browser",
        "夸克" to "com.quark.browser",
        "quark" to "com.quark.browser",
        "日历" to "com.android.calendar",
        "calendar" to "com.android.calendar",
        "邮件" to "com.android.email",
        "邮箱" to "com.android.email",
        "email" to "com.android.email",
        "mail" to "com.android.email",
        "相册" to "com.miui.gallery",
        "图库" to "com.miui.gallery",
        "gallery" to "com.miui.gallery",
        "photos" to "com.miui.gallery",
        "计算器" to "com.miui.calculator",
        "calculator" to "com.miui.calculator",
        "时钟" to "com.android.deskclock",
        "闹钟" to "com.android.deskclock",
        "clock" to "com.android.deskclock",
        "alarm" to "com.android.deskclock",
        "便签" to "com.miui.notes",
        "笔记" to "com.miui.notes",
        "notes" to "com.miui.notes",
        "note" to "com.miui.notes",
        "通讯录" to "com.android.contacts",
        "联系人" to "com.android.contacts",
        "电话" to "com.android.contacts",
        "拨号" to "com.android.contacts",
        "contacts" to "com.android.contacts",
        "dialer" to "com.android.contacts",
        "phone" to "com.android.contacts",
        "信息" to "com.android.mms",
        "短信" to "com.android.mms",
        "messages" to "com.android.mms",
        "messaging" to "com.android.mms",
        "sms" to "com.android.mms",
    )

    private val genericMessagingAliases = setOf(
        "信息",
        "短信",
        "messages",
        "messaging",
        "sms",
    )
}

internal object ForegroundAppActionParser {
    private val englishPattern =
        Regex(
            """\b(what\s+(?:app|application)\s+is\s+(?:currently\s+)?(?:open|active|foreground)|current\s+(?:foreground\s+)?app|foreground\s+app|active\s+app)\b""",
            RegexOption.IGNORE_CASE,
        )

    fun matches(input: String): Boolean {
        if (input.looksLikeForegroundAppNonAction()) return false
        val normalized = input.lowercase()
        val hasChineseTrigger = listOf(
            "当前应用",
            "当前 app",
            "当前app",
            "当前前台应用",
            "前台应用",
            "前台 app",
            "前台app",
            "正在打开的应用",
            "现在打开的应用",
        ).any { it in input }
        return hasChineseTrigger || englishPattern.containsMatchIn(normalized)
    }

    fun draft(): ActionDraft =
        ActionDraft(
            functionName = MobileActionFunctions.QUERY_FOREGROUND_APP,
            title = "查询当前前台应用",
            summary = "将通过 UsageStats 估计当前前台应用（包名与应用名）；不读取屏幕内容或使用历史。",
            parameters = emptyMap(),
            requiresConfirmation = true,
        )

    private fun String.looksLikeForegroundAppNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
            "前台服务",
            "前台任务",
            "前台进程",
            "后台前台",
            "架构",
            "代码",
            "模块",
            "组件",
            "接口",
            "权限",
            "api",
            "API",
            "通知",
            "怎么实现",
            "如何实现",
            "怎么设计",
        ).any { it in this } ||
            normalized.contains(Regex("""\bnotifications?\b""")) ||
            normalized.contains(Regex("""\bforeground\s+services?\b""")) ||
            normalized.contains(Regex("""\b(current|foreground|active)\s+app\s+(architecture|module|component|code|api|screen|state)\b""")) ||
            normalized.contains(Regex("""\b(how\s+do\s+i|how\s+to|implement|design)\b.*\b(current|foreground|active)\s+app\b"""))
    }
}

internal object RecentNotificationsActionParser {
    private val englishPattern =
        Regex(
            """\b(?:recent|latest|last)\s+(?:\d{1,2}\s+)?(?:current\s+app|this\s+app|solin)\s+notifications?\b|\b(?:current\s+app|this\s+app|solin)\s+(?:(?:recent|latest|last)\s+)?(?:\d{1,2}\s+)?notifications?\b|\b(?:recent|latest|last)\s+(?:\d{1,2}\s+)?notifications?\s+(?:for|from)\s+(?:current\s+app|this\s+app|solin)\b""",
            RegexOption.IGNORE_CASE,
        )

    fun matches(input: String): Boolean {
        if (input.looksLikeNotificationNonAction()) return false
        val normalized = input.lowercase()
        val hasChineseTrigger = listOf(
            "最近通知",
            "最近的通知",
            "最近通知摘要",
            "通知摘要",
            "当前应用最近通知",
            "当前应用通知",
            "当前 app 最近通知",
            "当前app最近通知",
            "本应用最近通知",
            "本应用通知",
        ).any { it in input } ||
            Regex("""(?:当前应用|当前\s*app|本应用)?\s*最近\s*\d{1,2}\s*条?\s*(?:消息|通知|讯息)""")
                .containsMatchIn(input) ||
            ((input.contains("Solin") || input.contains("Solin", ignoreCase = true)) && "通知" in input && ("最近" in input || "摘要" in input))
        return hasChineseTrigger || englishPattern.containsMatchIn(normalized)
    }

    fun draft(input: String): ActionDraft {
        val parameters = parameters(input)
        return ActionDraft(
            functionName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
            title = "查询最近通知",
            summary = summary(parameters["maxCount"]),
            parameters = parameters,
            requiresConfirmation = true,
        )
    }

    private fun parameters(input: String): Map<String, String> {
        val cleaned = input.replace(Regex("\\s+"), "")
        val normalized = input.lowercase()
        val match = Regex("""最近(\d{1,2})条?(?:消息|通知|讯息)?""").find(cleaned)
            ?: Regex("""(?:recent|latest|last)\s+(\d{1,2})\s+(?:current\s+app|this\s+app|app)?\s*notifications?""")
                .find(normalized)
            ?: Regex("""(?:current\s+app|this\s+app|solin)\s+(?:recent|latest|last)\s+(\d{1,2})\s+notifications?""")
                .find(normalized)
        val maxCount = match?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }?.toString()
        return if (maxCount == null) emptyMap() else mapOf("maxCount" to maxCount)
    }

    private fun summary(maxCount: String?): String =
        if (maxCount.isNullOrBlank()) {
            "将读取当前应用最近通知的摘要。"
        } else {
            "将读取当前应用最近 ${maxCount} 条通知的摘要。"
        }

    private fun String.looksLikeNotificationNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
            "系统通知",
            "所有通知",
            "全局通知",
            "其他应用通知",
            "通知权限",
            "通知栏权限",
            "通知系统",
            "通知架构",
            "通知渠道",
            "通知 channel",
            "通知 API",
            "通知API",
            "通知接口",
            "通知文档",
            "当前应用通知 API",
            "当前应用通知API",
            "通知列表怎么",
            "通知栏",
            "怎么实现",
            "如何实现",
            "怎么设计",
        ).any { it in this } ||
            normalized.contains(Regex("""\b(all|global|system|other\s+apps?)\s+notifications?\b""")) ||
            normalized.contains(Regex("""\b(notifications?\s+(permission|permissions|channel|system|architecture|api|implementation|docs?|documentation|listener|bar|drawer)|push\s+notifications?)\b""")) ||
            normalized.contains(Regex("""\b(how\s+do\s+i|how\s+to|implement|design)\b.*\bnotifications?\b"""))
    }
}

internal object CurrentScreenshotOcrActionParser {
    private val englishPattern =
        Regex(
            """\b(?:capture|read|extract|recognize|scan)\b.*\b(?:current|active|this)\s+(?:screen|window|display)\b.*\b(?:ocr|text)\b|\b(?:current|active|this)\s+(?:screen|window|display)\b.*\b(?:screenshot|screen\s+capture|ocr)\b.*\btext\b|\bocr\b.*\b(?:current|active|this)\s+(?:screen|window|display)\b""",
            RegexOption.IGNORE_CASE,
        )

    fun matches(input: String): Boolean {
        if (input.looksLikeCurrentScreenshotOcrNonAction()) return false
        val normalized = input.lowercase()
        return input.hasCurrentScreenReference(normalized) &&
            input.hasScreenshotOrOcrCaptureMarker(normalized) &&
            input.asksForCurrentScreenshotOcrText(normalized)
    }

    private fun String.hasCurrentScreenReference(normalized: String): Boolean =
        listOf(
            "当前屏幕",
            "当前界面",
            "现在屏幕",
        ).any { marker -> marker in this } ||
            Regex("""\b(?:current|active|this)\s+(?:screen|window|display)\b""")
                .containsMatchIn(normalized)

    private fun String.hasScreenshotOrOcrCaptureMarker(normalized: String): Boolean =
        listOf("截图", "截屏", "拍屏").any { marker -> marker in this } ||
            "ocr" in normalized ||
            Regex("""\b(?:screenshot|screen\s+capture)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(normalized)

    private fun String.asksForCurrentScreenshotOcrText(normalized: String): Boolean =
        listOf(
            "当前屏幕 OCR",
            "当前屏幕ocr",
            "当前界面 OCR",
            "当前界面ocr",
            "识别当前屏幕截图文字",
            "识别当前界面截图文字",
            "识别",
            "提取",
            "摘录",
            "读取文字",
            "读文字",
            "文字",
            "文本",
            "ocr",
        ).any { marker -> marker in normalized } ||
            englishPattern.containsMatchIn(normalized)

    fun draft(): ActionDraft =
        ActionDraft(
            functionName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            title = "截取当前屏幕 OCR",
            summary = "将请求 Android MediaProjection 前台同意，单次截取当前屏幕并在本地提取 OCR 文本；不会保存图片、像素、URI、路径或窗口标题。",
            parameters = mapOf("captureMode" to "current_screen"),
            requiresConfirmation = true,
        )

    private fun String.looksLikeCurrentScreenshotOcrNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
                "怎么实现",
                "如何实现",
                "怎么设计",
                "是什么",
                "什么意思",
                "解释",
                "说明",
                "介绍",
                "权限怎么",
                "MediaProjection API",
                "mediaprojection api",
                "截图 OCR API",
                "截图ocr API",
            ).any { marker -> marker in this } ||
            normalized.contains(
                Regex(
                    """\b(?:how\s+(?:do|can|to)|what\s+is|explain|implement|implementation|api|permissions?|docs?|documentation|code|architecture|design)\b.*\b(?:current|active|this)\s+(?:screen|window|display|screenshot|screen\s+capture|ocr)\b""",
                ),
            ) ||
            normalized.contains(
                Regex(
                    """\b(?:current|active|this)\s+(?:screen|window|display)\b.*\b(?:ocr|screenshot|screen\s+capture)\b.*\b(?:api|implementation|permissions?|docs?|documentation|code|architecture|design)\b""",
                ),
            )
    }
}

internal object CurrentScreenTextActionParser {
    private val chineseScreenTextPattern =
        Regex("""(?:当前屏幕|当前界面|现在屏幕|这个界面|屏幕)(?:\s|的|上|里|中|内)*(?:可访问|无障碍)?(?:文字|文本)|(?:文字|文本)(?:\s|来自|取自|读取自|在)*(?:当前屏幕|当前界面|现在屏幕|这个界面|屏幕)""")
    private val chineseAccessibleTextPattern =
        Regex("""(?:可访问|无障碍)(?:文字|文本)""")
    private val currentScreenTextPattern =
        Regex("""\b(?:current\s+)?screen\s+text\b|\b(?:current|active|this)\s+(?:screen|page|window|view)\s+(?:visible\s+|accessibility\s+|accessible\s+)?text\b|\btext\s+(?:from|on|in|of)\s+(?:the\s+)?(?:current|active|this)\s+(?:screen|page|window|view)\b""", RegexOption.IGNORE_CASE)
    private val visibleOrAccessibleTextPattern =
        Regex("""\b(?:current\s+)?(?:visible|accessibility|accessible)\s+text\b""", RegexOption.IGNORE_CASE)
    private val maxCharsPattern =
        Regex("""(?:max|limit)\s*(\d{1,5})\s*(?:chars?|characters?)""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        if (input.looksLikeCurrentScreenTextNonAction()) return false
        val normalized = input.lowercase()
        return input.hasExplicitCurrentScreenTextReference(normalized)
    }

    private fun String.hasExplicitCurrentScreenTextReference(normalized: String): Boolean =
        chineseScreenTextPattern.containsMatchIn(this) ||
            chineseAccessibleTextPattern.containsMatchIn(this) ||
            currentScreenTextPattern.containsMatchIn(normalized) ||
            visibleOrAccessibleTextPattern.containsMatchIn(normalized)

    fun draft(input: String): ActionDraft {
        val parameters = parameters(input)
        val maxChars = parameters.getValue("maxChars")
        return ActionDraft(
            functionName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            title = "读取当前屏幕文本",
            summary = "将读取当前屏幕的可访问文本快照（最多 ${maxChars} 字符）；不会读取截图、像素、坐标或完整节点树。",
            parameters = parameters,
            requiresConfirmation = true,
        )
    }

    private fun parameters(input: String): Map<String, String> =
        mapOf("maxChars" to (maxChars(input)?.coerceIn(1, 4000) ?: 2000).toString())

    private fun maxChars(input: String): Int? {
        val normalized = input.lowercase()
        val cleaned = input.replace(Regex("\\s+"), "")
        return Regex("""最多(\d{1,5})字""").find(cleaned)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: maxCharsPattern.find(normalized)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun String.looksLikeCurrentScreenTextNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
            "不要读取",
            "别读取",
            "不要读",
            "别读",
            "不要总结",
            "别总结",
            "不要提取",
            "别提取",
            "当前屏幕截图",
            "当前界面截图",
            "屏幕截图",
            "截图",
            "截屏",
            "拍屏",
            "ocr",
            "OCR",
            "像素",
            "图片",
            "照片",
            "视觉理解",
            "语义屏幕理解",
            "屏幕理解",
            "图像理解",
            "看图",
            "怎么实现",
            "如何实现",
            "怎么设计",
            "是什么",
            "什么意思",
            "解释",
            "说明",
            "介绍",
            "无障碍权限",
            "无障碍文本权限",
        ).any { it in this } ||
            normalized.contains(Regex("""\b(do\s+not|don't|dont)\s+(?:read|extract|summarize)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view)\b""")) ||
            normalized.contains(Regex("""\b(?:current|active|this)\s+(?:screen|page|window|view)\s+(?:screenshots?|screen\s+captures?|images?|photos?|pixels?|ocr|api|implementation|architecture|design|schema|tests?|parser|docs?|permissions?|state)\b""")) ||
            normalized.contains(Regex("""\b(?:current|active|this)\s+(?:screen|page|window|view)\s+text\s+(?:api|implementation|architecture|design|schema|tests?|parser|docs?|permissions?|state)\b""")) ||
            normalized.contains(Regex("""\b(?:screenshots?|screen\s+captures?|images?|photos?|pixels?|ocr)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view)\b""")) ||
            normalized.contains(Regex("""\b(?:what\s+is|explain|describe|meaning)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view|screen\s+text)\b""")) ||
            normalized.contains(Regex("""\b(?:what\s+is|explain|describe|meaning)\b.*\b(?:screen|visible|accessibility|accessible)\s+text\b""")) ||
            normalized.contains(Regex("""\b(?:how\s+do\s+i|how\s+to|implement|design)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view|screen\s+text)\b"""))
    }
}

internal object ContactQueryActionParser {
    private val englishPattern =
        Regex(
            """\b(?:find|search|look\s+up)\s+(?:my\s+)?contacts?\b|\b(?:find|search|look\s+up)\s+.+\s+in\s+(?:my\s+)?contacts?\b|\bfind\s+.+\s+contact\s+(?:number|phone)\b|\bcontacts?\s+(?:search|lookup|query)\b""",
            RegexOption.IGNORE_CASE,
        )

    fun matches(input: String): Boolean {
        if (input.looksLikeContactQueryNonAction()) return false
        val normalized = input.lowercase()
        val hasChineseTrigger = listOf(
            "查询联系人",
            "查联系人",
            "查找联系人",
            "搜索联系人",
            "找联系人",
            "联系人查询",
            "通讯录找",
            "通讯录里找",
            "搜索通讯录",
            "查通讯录",
        ).any { it in input } ||
            Regex("""(?:联系人|通讯录)(?:里|里的)?\s*(?:查询|查找|查|搜索|找)""").containsMatchIn(input) ||
            Regex("""(?:前|最多)\s*\d{1,2}\s*(?:个|位|条)?\s*(?:联系人|通讯录)(?:里|里的)?\s*(?:查询|查找|查|搜索|找)""")
                .containsMatchIn(input)
        if (!hasChineseTrigger && !englishPattern.containsMatchIn(normalized)) return false
        return cleanedContactQuery(input).isMeaningfulContactQuery()
    }

    fun draft(input: String): ActionDraft {
        val parameters = parameters(input)
        val query = parameters.getValue("query")
        return ActionDraft(
            functionName = MobileActionFunctions.QUERY_CONTACTS,
            title = "查询联系人",
            summary = summary(query, parameters["maxCount"]),
            parameters = parameters,
            requiresConfirmation = true,
        )
    }

    private fun parameters(input: String): Map<String, String> {
        val maxCount = maxCount(input)
        val query = cleanedContactQuery(input)
        return buildMap {
            put("query", query)
            if (maxCount != null) {
                put("maxCount", maxCount)
            }
        }
    }

    private fun maxCount(input: String): String? {
        val cleaned = input.replace(Regex("\\s+"), "")
        val normalized = input.lowercase()
        val match = Regex("""(?:前|最多)(\d{1,2})(?:个|位|条)?""").find(cleaned)
            ?: Regex("""\b(?:top|first|max|limit)\s+(\d{1,2})\s*(?:contacts?|results?)?\b""")
                .find(normalized)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }?.toString()
    }

    private fun cleanedContactQuery(input: String): String {
        val cleaned = cleanedObject(input)
        return cleaned
            .replace(
                Regex("""^(?:前|最多)\s*\d{1,2}\s*(?:个|位|条)?\s*(?:联系人|通讯录)(?:里|里的)?\s*(?:查询|查找|查|搜索|找)\s*[:：]?\s*"""),
                "",
            )
            .replace(
                Regex("""^(?:联系人|通讯录)(?:里|里的)?\s*(?:查询|查找|查|搜索|找)\s*[:：]?\s*"""),
                "",
            )
            .replace(
                Regex(
                    """^请?\s*(?:帮我\s*)?(?:查询|查找|查|搜索|找)\s*联系人\s*[:：]?\s*""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(Regex("""^联系人查询\s*[:：]?\s*"""), "")
            .replace(Regex("""^(?:从)?通讯录(?:里|里的)?(?:查询|查找|查|搜索|找)\s*[:：]?\s*"""), "")
            .replace(Regex("""^(?:查询|查找|查|搜索|找)\s*通讯录(?:里|里的)?\s*[:：]?\s*"""), "")
            .replace(
                Regex("""^(?:find|search|look\s+up)\s+(?:my\s+)?contacts?\s*(?:for|named|called)?\s*[:：]?\s*""", RegexOption.IGNORE_CASE),
                "",
            )
            .replace(
                Regex("""^(?:find|search|look\s+up)\s+(.+?)\s+in\s+(?:my\s+)?contacts?\s*$""", RegexOption.IGNORE_CASE),
                "$1",
            )
            .replace(
                Regex("""^find\s+(.+?)\s+contact\s+(?:number|phone)\s*$""", RegexOption.IGNORE_CASE),
                "$1",
            )
            .replace(Regex("""^contacts?\s+(?:search|lookup|query)\s*[:：]?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""(?:^|[\s，,;；]*)?(?:前|最多)\s*\d{1,2}\s*(?:个|位|条)?"""), "")
            .replace(Regex("""(?:^|[\s,;]*)?(?:top|first|max|limit)\s*\d{1,2}\s*(?:contacts?|results?)?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\bcontacts?\b""", RegexOption.IGNORE_CASE), "")
            .replace("联系人", "")
            .trim()
    }

    private fun summary(query: String, maxCount: String?): String =
        if (maxCount.isNullOrBlank()) {
            "将按“$query”查询联系人。"
        } else {
            "将按“$query”查询最多 ${maxCount} 个联系人。"
        }

    private fun String.isMeaningfulContactQuery(): Boolean {
        val normalized = lowercase()
        return isNotBlank() &&
            normalized !in setOf("contact", "contacts", "联系人", "permission", "permissions") &&
            this !in setOf("权限", "联系人权限")
    }

    private fun String.looksLikeContactQueryNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
            "联系人权限",
            "联系人列表权限",
            "通讯录权限",
            "联系人页面",
            "联系人组件",
            "联系人接口",
            "联系人列表",
            "通讯录列表",
            "读取所有联系人",
            "所有联系人",
            "导出通讯录",
            "不要查联系人",
            "不要查询联系人",
            "别查联系人",
            "编辑联系人",
            "怎么实现",
            "如何实现",
            "怎么设计",
        ).any { it in this } ||
            normalized.contains(Regex("""\b(do\s+not|don't|dont)\s+(?:find|search|look\s+up)\s+(?:my\s+)?contacts?\b""")) ||
            normalized.contains(Regex("""\b(contacts?\s+(permission|permissions|form|page|component|api|screen|tracing|support|provider)|contactscontract|delete\s+contacts?|edit\s+contacts?|export\s+contacts?|all\s+contacts?|contacts?\s+list)\b""")) ||
            normalized.contains(Regex("""\b(how\s+do\s+i|how\s+to|implement|design)\b.*\bcontacts?\b"""))
    }
}

internal object ContactDraftActionParser {
    private val englishPattern =
        Regex("""\b(?:create|add|new)\s+(?:a\s+)?contacts?\b|\bcontacts?\s+draft\b""", RegexOption.IGNORE_CASE)
    private val emailPattern =
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val phonePattern =
        Regex("""(?:\+?\d[\d\s().-]{5,}\d)""")

    fun matches(input: String): Boolean {
        if (input.looksLikeContactDraftNonAction()) return false
        val normalized = input.lowercase()
        val hasTrigger = listOf("新建联系人", "创建联系人", "添加联系人", "联系人草稿")
            .any { it in input } ||
            englishPattern.containsMatchIn(normalized)
        return hasTrigger && cleanedContactName(input).isMeaningfulContactDraftName()
    }

    fun draft(input: String): ActionDraft {
        val parameters = parameters(input)
        return ActionDraft(
            functionName = MobileActionFunctions.CREATE_CONTACT_DRAFT,
            title = "联系人草稿",
            summary = "将打开联系人新建页面。",
            parameters = parameters,
            requiresConfirmation = true,
        )
    }

    private fun parameters(input: String): Map<String, String> {
        val email = emailPattern.find(input)?.value
        val phone = phonePattern.find(input)?.value?.trim()
        val name = cleanedContactName(input)
        return buildMap {
            put("name", name)
            if (!email.isNullOrBlank()) put("email", email)
            if (!phone.isNullOrBlank()) put("phone", phone)
        }
    }

    private fun cleanedContactName(input: String): String =
        cleanedObject(input)
            .replace(Regex("""^(?:新建|创建|添加)(?:一个|一位)?联系人\s*[:：]?\s*"""), "")
            .replace(Regex("""^联系人草稿\s*[:：]?\s*"""), "")
            .replace(Regex("""^(?:create|add|new)\s+(?:a\s+)?contacts?\s*[:：]?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^contacts?\s+draft\s*[:：]?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(emailPattern, "")
            .replace(phonePattern, "")
            .trim()

    private fun String.isMeaningfulContactDraftName(): Boolean {
        val normalized = lowercase()
        return isNotBlank() &&
            normalized !in setOf("contact", "contacts", "联系人", "draft") &&
            this !in setOf("权限", "联系人权限")
    }

    private fun String.looksLikeContactDraftNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
            "不要新建联系人",
            "不要创建联系人",
            "不要添加联系人",
            "别新建联系人",
            "别创建联系人",
            "别添加联系人",
            "联系人权限",
            "通讯录权限",
            "联系人页面",
            "联系人组件",
            "联系人接口",
            "联系人列表",
            "通讯录列表",
            "读取联系人",
            "查询联系人",
            "查联系人",
            "搜索联系人",
            "编辑联系人",
            "删除联系人",
            "导出联系人",
            "所有联系人",
            "全量联系人",
            "怎么实现",
            "如何实现",
            "怎么设计",
        ).any { it in this } ||
            normalized.contains(Regex("""\b(do\s+not|don't|dont)\s+(?:create|add|new)\s+(?:a\s+)?contacts?\b""")) ||
            normalized.contains(Regex("""\b(contacts?\s+(permission|permissions|form|page|component|api|screen|tracing|support|provider|list)|contactscontract|delete\s+contacts?|edit\s+contacts?|export\s+contacts?|all\s+contacts?)\b""")) ||
            normalized.contains(Regex("""\b(how\s+do\s+i|how\s+to|implement|design)\b.*\bcontacts?\b"""))
    }
}

internal object CalendarAvailabilityActionParser {
    private val isoOffsetDateTimePattern =
        Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})""")
    private val englishPattern =
        Regex(
            """\b(calendar\s+availability|calendar\s+free/busy|calendar\s+free busy|free/busy|free busy|am\s+i\s+(?:free|available))\b""",
            RegexOption.IGNORE_CASE,
        )

    fun matches(input: String): Boolean {
        if (input.looksLikeCalendarAvailabilityNonAction()) return false
        val normalized = input.lowercase()
        val hasTrigger = listOf("忙闲", "空闲", "有空", "日历可用", "日历占用").any { it in input } ||
            englishPattern.containsMatchIn(normalized)
        return hasTrigger && parameters(input) != null
    }

    fun draft(input: String): ActionDraft {
        val parameters = parameters(input).orEmpty()
        return ActionDraft(
            functionName = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
            title = "查询日历忙闲",
            summary = "将只读查询本机日历忙闲：${parameters["start"].orEmpty()} 到 ${parameters["end"].orEmpty()}",
            parameters = parameters,
            requiresConfirmation = true,
        )
    }

    private fun parameters(input: String): Map<String, String>? {
        val matches = isoOffsetDateTimePattern.findAll(input).map { it.value }.take(2).toList()
        if (matches.size < 2) return null
        val validation = CalendarAvailabilityQuery.parseWindow(matches[0], matches[1])
        if (validation !is CalendarAvailabilityQueryValidation.Valid) return null
        return mapOf(
            "start" to matches[0],
            "end" to matches[1],
        )
    }

    private fun String.looksLikeCalendarAvailabilityNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
            "日历权限",
            "不要查忙闲",
            "不要查询忙闲",
            "别查忙闲",
            "不要查日历忙闲",
            "别查日历忙闲",
            "读取事件",
            "事件详情",
            "事件标题",
            "日历标题",
            "日历地点",
            "参与人",
            "导出日历",
            "全量日历",
            "忙闲是什么意思",
            "忙闲怎么实现",
            "日历忙闲怎么实现",
            "怎么设计",
            "如何实现",
        ).any { it in this } ||
            normalized.contains(Regex("""\b(do\s+not|don't|dont)\s+(?:check|query|read)\s+(?:calendar\s+)?(?:availability|free/busy|free busy)\b""")) ||
            normalized.contains(Regex("""\b(what\s+is\s+free/busy|calendar\s+availability\s+(api|screen|component|implementation|design|schema|tests?|parser|docs?)|free/busy\s+(api|schema|tests?|parser|docs?)|availability\s+api)\b""")) ||
            normalized.contains(Regex("""\b(event\s+(details?|titles?|locations?|attendees?)|export\s+calendar|all\s+calendar)\b""")) ||
            normalized.contains(Regex("""\b(how\s+do\s+i|how\s+to|implement|design)\b.*\b(calendar\s+availability|free/busy|free busy|availability)\b"""))
    }
}

private fun String.looksLikeDiscussion(): Boolean {
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
        "是什么",
    )
        .any { it in this } ||
        listOf("错误原因", "日志", "算法", "数据结构", "功能测试", "路线图", "导航栏").any { it in this } ||
        normalized.contains(Regex("""\b(how\s+do\s+i|what\s+does|what\s+is|explain|meaning|parser|tests?|listener|handler|schema|stream)\b"""))
}

internal fun String.looksLikeSequentialAction(): Boolean {
    val normalized = lowercase()
    return Regex(""".+(?:然后|接着|随后|之后|再)\s*(?:打开|进入|启动|发|发送|写|建|创建|添加|查询|查|搜索|读取|读|总结|分享|导航|跳转|访问|取消|提醒|设置提醒|返回|后退).*""")
        .containsMatchIn(this) ||
        normalized.contains(
            Regex("""\b(?:and\s+then|then|after\s+that)\b.*\b(?:go\s+back|back|open|launch|search|share|send|create|read|summarize)\b"""),
        )
}

internal fun String.startsWithActionNegation(): Boolean {
    val normalized = lowercase()
    return Regex("""^\s*(?:请|帮我|麻烦|麻烦你)?\s*(?:我\s*)?(?:不想|不需要|不用|不必|不要|别|请勿|请不要|先别|暂时别|不)\s*""")
        .containsMatchIn(this) ||
        normalized.contains(Regex("""^\s*(?:(?:please\s+)?(?:do\s+not|don't|dont|never)|i\s+(?:do\s+not|don't|dont)\s+want\s+to)\b"""))
}

private fun String.looksLikeDeviceSettingsNonAction(): Boolean {
    val normalized = lowercase()
    return startsWithActionNegation() ||
        listOf(
        "不要打开",
        "别打开",
        "不要进入",
        "别进入",
        "不要去",
        "在哪里",
        "在哪",
        "哪里",
        "怎么实现",
        "如何实现",
        "怎么设计",
        "代码",
        "组件",
        "接口",
    ).any { it in this } ||
        normalized.contains(Regex("""\b(do\s+not|don't|dont)\s+(?:open|show|go\s+to|launch)\b""")) ||
        normalized.contains(Regex("""\b(how\s+to|implement|design|api|code|compose)\b"""))
}

internal fun cleanedObject(input: String): String =
    input.trim()
        .removePrefix("请")
        .removePrefix("帮我")
        .trim()
        .ifBlank { input.trim() }
