package com.bytedance.zgx.pocketmind.action

import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityQuery
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityQueryValidation

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
        return listOf(
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
        return listOf(
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
            summary = "将读取当前前台应用信息（包名与应用名）。",
            parameters = emptyMap(),
            requiresConfirmation = true,
        )

    private fun String.looksLikeForegroundAppNonAction(): Boolean {
        val normalized = lowercase()
        return listOf(
            "前台服务",
            "前台任务",
            "前台进程",
            "后台前台",
            "架构",
            "代码",
            "模块",
            "组件",
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
            """\b(?:recent|latest|last)\s+(?:\d{1,2}\s+)?(?:current\s+app|this\s+app|pocketmind)\s+notifications?\b|\b(?:current\s+app|this\s+app|pocketmind)\s+(?:(?:recent|latest|last)\s+)?(?:\d{1,2}\s+)?notifications?\b|\b(?:recent|latest|last)\s+(?:\d{1,2}\s+)?notifications?\s+(?:for|from)\s+(?:current\s+app|this\s+app|pocketmind)\b""",
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
            (input.contains("PocketMind", ignoreCase = true) && "通知" in input && ("最近" in input || "摘要" in input))
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
            ?: Regex("""(?:current\s+app|this\s+app|pocketmind)\s+(?:recent|latest|last)\s+(\d{1,2})\s+notifications?""")
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
        return listOf(
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
            "通知列表怎么",
            "通知栏",
            "怎么实现",
            "如何实现",
            "怎么设计",
        ).any { it in this } ||
            normalized.contains(Regex("""\b(all|global|system|other\s+apps?)\s+notifications?\b""")) ||
            normalized.contains(Regex("""\b(notification\s+(permission|channel|system|architecture|api|listener|bar|drawer)|push\s+notifications?)\b""")) ||
            normalized.contains(Regex("""\b(how\s+do\s+i|how\s+to|implement|design)\b.*\bnotifications?\b"""))
    }
}

internal object CurrentScreenTextActionParser {
    private val currentScreenPattern =
        Regex("""\b(current|active|this)\s+(screen|page|window|view)\b""", RegexOption.IGNORE_CASE)
    private val currentVisibleTextPattern =
        Regex("""\bcurrent\s+(?:visible|accessibility|accessible)\s+text\b""", RegexOption.IGNORE_CASE)
    private val maxCharsPattern =
        Regex("""(?:max|limit)\s*(\d{1,5})\s*(?:chars?|characters?)""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        if (input.looksLikeCurrentScreenTextNonAction()) return false
        val normalized = input.lowercase()
        val mentionsCurrentScreen = listOf(
            "当前屏幕",
            "当前界面",
            "现在屏幕",
            "屏幕内容",
            "屏幕文字",
            "屏幕文本",
            "这个界面",
        ).any { marker -> marker in input } ||
            currentScreenPattern.containsMatchIn(normalized) ||
            currentVisibleTextPattern.containsMatchIn(normalized)
        val asksForAccessibleText = listOf(
            "文字",
            "文本",
            "内容",
            "读取",
            "读一下",
            "总结",
            "摘要",
            "提取",
            "text",
            "summarize",
            "summary",
            "read",
            "extract",
        ).any { marker -> marker in normalized }
        return mentionsCurrentScreen && asksForAccessibleText
    }

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
        return listOf(
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
            "无障碍权限",
            "无障碍文本权限",
        ).any { it in this } ||
            normalized.contains(Regex("""\b(do\s+not|don't|dont)\s+(?:read|extract|summarize)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view)\b""")) ||
            normalized.contains(Regex("""\b(?:current|active|this)\s+(?:screen|page|window|view)\s+(?:screenshots?|screen\s+captures?|images?|photos?|pixels?|ocr|api|implementation|architecture|design|schema|tests?|parser|docs?|permissions?|state)\b""")) ||
            normalized.contains(Regex("""\b(?:current|active|this)\s+(?:screen|page|window|view)\s+text\s+(?:api|implementation|architecture|design|schema|tests?|parser|docs?|permissions?|state)\b""")) ||
            normalized.contains(Regex("""\b(?:screenshots?|screen\s+captures?|images?|photos?|pixels?|ocr)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view)\b""")) ||
            normalized.contains(Regex("""\b(?:what\s+is|explain|describe|meaning)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view|screen\s+text)\b""")) ||
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
        return listOf(
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
        return listOf(
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
