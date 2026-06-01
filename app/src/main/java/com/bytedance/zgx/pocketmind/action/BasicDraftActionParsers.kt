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

internal object DeviceSettingsActionParser {
    private val wifiPattern =
        Regex("""(?i)\b(?:wi[-\s]?fi|wifi|wlan|wireless)\b""")
    private val englishWifiSettingsPattern =
        Regex("""\b(?:(?:open|show|go\s+to|launch)\s+(?:the\s+)?(?:wi[-\s]?fi|wifi|wlan|wireless)\s+(?:settings?|preferences?)|(?:wi[-\s]?fi|wifi|wlan|wireless)\s+(?:settings?|preferences?))\b""", RegexOption.IGNORE_CASE)
    private val englishFlashlightSettingsPattern =
        Regex("""\b(?:(?:open|show|go\s+to|launch)\s+(?:the\s+)?(?:flashlight|torch)\s+(?:settings?|preferences?)|(?:flashlight|torch)\s+(?:settings?|preferences?))\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean = targetTool(input) != null

    fun draft(input: String): ActionDraft {
        val toolName = targetTool(input) ?: MobileActionFunctions.OPEN_WIFI_SETTINGS
        return when (toolName) {
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
        return when {
            input.requestsWifiSettings() -> MobileActionFunctions.OPEN_WIFI_SETTINGS
            input.requestsFlashlightSettings() -> MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS
            else -> null
        }
    }

    private fun String.requestsWifiSettings(): Boolean {
        val normalized = lowercase()
        val referencesWifi = wifiPattern.containsMatchIn(normalized) || "无线" in this
        val referencesSettings = "设置" in this ||
            Regex("""\b(settings?|preferences?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        return referencesWifi && referencesSettings && (
            listOf("打开", "进入", "跳转", "前往", "去", "设置").any { it in this } ||
                englishWifiSettingsPattern.containsMatchIn(normalized)
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
}

internal object WebSearchActionParser {
    private val englishPattern =
        Regex("""\b(web\s+search|search\s+the\s+web|search\s+online|look\s+up|google|bing)\b""", RegexOption.IGNORE_CASE)
    private val discussionPrefixPattern =
        Regex("""^\s*(what\s+is|what\s+does|explain|how\s+do\s+i|how\s+to)\b""", RegexOption.IGNORE_CASE)
    private val negativeEnglishPattern =
        Regex("""\b(do\s+not|don't|dont)\s+(?:search|look\s+up|google|bing)\b""", RegexOption.IGNORE_CASE)

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
        if (!hasExplicitChineseTrigger && !hasEnglishTrigger) return false
        return strippedWebSearchQuery(input).isMeaningfulSearchQuery()
    }

    fun draft(input: String): ActionDraft {
        val query = cleanedWebSearchQuery(input)
        return ActionDraft(
            functionName = MobileActionFunctions.WEB_SEARCH,
            title = "Web 搜索",
            summary = "将在浏览器中搜索：$query",
            parameters = mapOf("query" to query),
            requiresConfirmation = true,
        )
    }

    private fun cleanedWebSearchQuery(input: String): String {
        val stripped = strippedWebSearchQuery(input)
        return stripped.ifBlank { cleanedObject(input) }
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

    private fun String.looksLikeWebSearchNonAction(): Boolean {
        val normalized = lowercase()
        return "不要搜索" in this ||
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
        return mentionsRecentMedia && asksForTextExtraction
    }
}

private fun String.looksLikeDiscussion(): Boolean {
    val normalized = lowercase()
    return listOf("什么意思", "什么含义", "怎么说", "如何表达", "解释", "怎么理解", "怎么写", "是什么")
        .any { it in this } ||
        listOf("错误原因", "日志", "算法", "数据结构", "功能测试", "路线图", "导航栏").any { it in this } ||
        normalized.contains(Regex("""\b(how\s+do\s+i|what\s+does|what\s+is|explain|meaning|parser|tests?|listener|handler|schema|stream)\b"""))
}

private fun String.looksLikeDeviceSettingsNonAction(): Boolean {
    val normalized = lowercase()
    return listOf(
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

private fun cleanedObject(input: String): String =
    input.trim()
        .removePrefix("请")
        .removePrefix("帮我")
        .trim()
        .ifBlank { input.trim() }
