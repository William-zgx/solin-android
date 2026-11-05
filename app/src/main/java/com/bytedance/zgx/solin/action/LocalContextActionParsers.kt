package com.bytedance.zgx.solin.action

/*
 * Rule parsers for local device-context reads: recent files, screenshot/image OCR,
 * notifications, current-screen OCR and current-screen text.
 */

private val MATCHES_ZH1_REGEX = Regex("""最近\s*\d{0,2}\s*(?:个|份|条|张)?\s*(图片|照片|截图|截屏|视频|音频)""")

private val MATCHES_ZH2_REGEX = Regex("""最近\s*\d{0,2}\s*(?:个|份|条|张)?\s*(文件|文档|下载)""")

private val MATCHES_RECENT_LATEST_REGEX =
    Regex("""\b(recent|latest)\b.*\b(files?|documents?)\b""", RegexOption.IGNORE_CASE)

private val WHITESPACE_RUN_REGEX = Regex("\\s+")

private val PARAMETERS_ZH1_REGEX = Regex("最近(\\d{1,2})条?(?:个|份)?")

private val PARAMETERS_RECENT_LATEST_REGEX = Regex(
    "(?:recent|latest)\\s+(\\d{1,2})\\s+" +
        "(?:files?|documents?|images?|screenshots?|screen\\s+captures?|videos?|audios?|photos?)",
)

private val RECENT_FILE_NON_ACTION_RECENT_LATEST_REGEX = Regex(
    """\b(?:recent|latest|last|most\s+recent)\b.*\b(?:files?|documents?|images?|photos?|pictures?|screenshots?|screen\s+captures?|videos?|audios?)\b""",
    RegexOption.IGNORE_CASE,
)

private val RECENT_FILE_NON_ACTION_FILES_API_REGEX =
    Regex("""\bfiles?\s+(?:api|implementation|docs?|documentation|permissions?|list)\b""")

private val RECENT_FILE_NON_ACTION_DO_NOT_REGEX = Regex("""\b(?:do\s+not|don't|dont|never)\b""")

private val RECENT_FILE_NON_ACTION_HOW_DO_REGEX = Regex(
    """\b(?:how\s+(?:do|can|to)|what\s+is|explain|implement|implementation|api|permissions?|docs?|documentation|code|feature)\b""",
)

private val RECENT_FILE_CONTENT_EXTRACTION_RECENT_LATEST_REGEX =
    Regex("""\b(recent|latest)\b.*\b(images?|photos?|pictures?|screenshots?|screen\s+captures?)\b""")

private val RECENT_FILE_CONTENT_EXTRACTION_DESCRIBE_ANALY_REGEX =
    Regex("""\b(describe|analy[sz]e|what\s+is\s+in|what's\s+in)\b.*\b(images?|photos?|pictures?)\b""")

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
            MATCHES_ZH1_REGEX.containsMatchIn(input)
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
            MATCHES_ZH2_REGEX.containsMatchIn(input) ||
            MATCHES_RECENT_LATEST_REGEX.containsMatchIn(normalized)
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
        val cleaned = input.replace(WHITESPACE_RUN_REGEX, "")
        val countMatch = PARAMETERS_ZH1_REGEX.find(cleaned)
            ?: PARAMETERS_RECENT_LATEST_REGEX.find(normalized)

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
            RECENT_FILE_NON_ACTION_RECENT_LATEST_REGEX.containsMatchIn(normalized)
        val mentionsFileQuery = listOf(
            "查询文件",
            "文件列表",
            "文件 API",
            "文件API",
            "文件接口",
            "文件权限",
        ).any { it in this } ||
            normalized.contains(RECENT_FILE_NON_ACTION_FILES_API_REGEX)
        if (!mentionsRecentFiles && !mentionsFileQuery) return false

        val hasNegativeIntent = listOf("不要", "别", "请勿", "不需要", "不想", "不用", "不必").any { it in this } ||
            normalized.contains(RECENT_FILE_NON_ACTION_DO_NOT_REGEX)
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
                RECENT_FILE_NON_ACTION_HOW_DO_REGEX,
            )
        return hasNegativeIntent || hasDiscussionIntent
    }

    private fun String.looksLikeRecentFileContentExtraction(): Boolean {
        val normalized = lowercase()
        val mentionsRecentMedia = ("最近" in this && listOf("图片", "照片", "截图", "截屏", "相册").any { it in this }) ||
            RECENT_FILE_CONTENT_EXTRACTION_RECENT_LATEST_REGEX.containsMatchIn(normalized)
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
            normalized.contains(RECENT_FILE_CONTENT_EXTRACTION_DESCRIBE_ANALY_REGEX)
        return mentionsRecentMedia && (asksForTextExtraction || asksForVisualUnderstanding)
    }
}

private val MENTIONS_RECENT_SCREENSHOT_FOR_OCR_REGEX = Regex("""最近1(?:张|个)?(?:截图|截屏)""")

private val REQUESTED_SCREENSHOT_COUNT_REGEX = Regex("""最近(\d{1,2})(?:张|个)?(?:截图|截屏)""")

private val RECENT_SCREENSHOT_OCR_NON_ACTION_ZH1_REGEX =
    Regex("""最近[二两三四五六七八九十百千万多几]+(?:张|个)?(?:截图|截屏)""")

private val RECENT_SCREENSHOT_OCR_NON_ACTION_DO_NOT_REGEX =
    Regex("""\b(do\s+not|don't|dont)\s+(?:read|extract|recognize|scan)\b.*\b(?:screenshot|screen\s+capture)\b""")

private val RECENT_SCREENSHOT_OCR_NON_ACTION_SCREENSHOT_SCREEN_REGEX =
    Regex("""\b(?:screenshot|screen\s+capture)\s+ocr\s+(?:api|implementation|architecture|design|schema|tests?|parser|docs?|permissions?)\b""")

private val RECENT_SCREENSHOT_OCR_NON_ACTION_WHAT_IS_REGEX =
    Regex("""\b(?:what\s+is|explain|describe|meaning|how\s+do\s+i|how\s+to|implement|design)\b.*\b(?:screenshot|screen\s+capture)\s+ocr\b""")

private val RECENT_SCREENSHOT_OCR_NON_ACTION_ALL_MULTIPLE_REGEX =
    Regex("""\b(?:all|multiple|many)\s+(?:screenshots?|screen\s+captures?)\b""")

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
        val cleaned = replace(WHITESPACE_RUN_REGEX, "")
        return ("最近" in this && ("截图" in this || "截屏" in this)) ||
            MENTIONS_RECENT_SCREENSHOT_FOR_OCR_REGEX.containsMatchIn(cleaned) ||
            englishSingularScreenshotPattern.containsMatchIn(normalized) ||
            englishCountPattern.containsMatchIn(normalized)
    }

    private fun String.asksForOcrTextExtraction(): Boolean =
        asksForOcrTextExtraction(sourceNounPattern = """(?:screenshot|screen\s+capture)""")

    private fun requestedScreenshotCount(input: String): Int? {
        val normalized = input.lowercase()
        val cleaned = input.replace(WHITESPACE_RUN_REGEX, "")
        return REQUESTED_SCREENSHOT_COUNT_REGEX.find(cleaned)
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
            RECENT_SCREENSHOT_OCR_NON_ACTION_ZH1_REGEX.containsMatchIn(this) ||
            normalized.contains(RECENT_SCREENSHOT_OCR_NON_ACTION_DO_NOT_REGEX) ||
            normalized.contains(RECENT_SCREENSHOT_OCR_NON_ACTION_SCREENSHOT_SCREEN_REGEX) ||
            normalized.contains(RECENT_SCREENSHOT_OCR_NON_ACTION_WHAT_IS_REGEX) ||
            normalized.contains(RECENT_SCREENSHOT_OCR_NON_ACTION_ALL_MULTIPLE_REGEX)
    }
}

private val MENTIONS_RECENT_IMAGE_FOR_OCR_REGEX = Regex("""最近[一二两三123]?(?:张|个)?(?:图片|照片)""")

private val REQUESTED_IMAGE_COUNT_ZH1_REGEX = Regex("""最近(\d{1,2})(?:张|个)?(?:图片|照片)""")

private val REQUESTED_IMAGE_COUNT_ZH2_REGEX = Regex("""最近([一二两三四五六七八九十百千万多几]+)(?:张|个)?(?:图片|照片)""")

private val RECENT_IMAGE_OCR_NON_ACTION_DO_NOT_REGEX =
    Regex("""\b(do\s+not|don't|dont)\s+(?:read|extract|recognize|scan)\b.*\b(?:images?|photos?|pictures?)\b""")

private val RECENT_IMAGE_OCR_NON_ACTION_IMAGE_PHOTO_REGEX =
    Regex("""\b(?:image|photo|picture)\s+ocr\s+(?:api|implementation|architecture|design|schema|tests?|parser|docs?|permissions?)\b""")

private val RECENT_IMAGE_OCR_NON_ACTION_WHAT_IS_REGEX =
    Regex("""\b(?:what\s+is|explain|describe|meaning|how\s+do\s+i|how\s+to|implement|design)\b.*\b(?:image|photo|picture)\s+ocr\b""")

private val RECENT_IMAGE_OCR_NON_ACTION_ALL_MULTIPLE_REGEX =
    Regex("""\b(?:all|multiple|many)\s+(?:images?|photos?|pictures?)\b""")

private val RECENT_IMAGE_OCR_NON_ACTION_DESCRIBE_ANALY_REGEX =
    Regex("""\b(?:describe|analy[sz]e|what\s+is\s+in|what's\s+in)\b.*\b(?:images?|photos?|pictures?)\b""")

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
        val cleaned = replace(WHITESPACE_RUN_REGEX, "")
        return ("最近" in this && listOf("图片", "照片", "相册").any { it in this }) ||
            MENTIONS_RECENT_IMAGE_FOR_OCR_REGEX.containsMatchIn(cleaned) ||
            englishRecentImagePattern.containsMatchIn(normalized)
    }

    private fun String.asksForOcrTextExtraction(): Boolean =
        asksForOcrTextExtraction(sourceNounPattern = """(?:images?|photos?|pictures?)""")

    private fun requestedImageCount(input: String): Int? {
        val normalized = input.lowercase()
        val cleaned = input.replace(WHITESPACE_RUN_REGEX, "")
        REQUESTED_IMAGE_COUNT_ZH1_REGEX.find(cleaned)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }
        REQUESTED_IMAGE_COUNT_ZH2_REGEX.find(cleaned)
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
            normalized.contains(RECENT_IMAGE_OCR_NON_ACTION_DO_NOT_REGEX) ||
            normalized.contains(RECENT_IMAGE_OCR_NON_ACTION_IMAGE_PHOTO_REGEX) ||
            normalized.contains(RECENT_IMAGE_OCR_NON_ACTION_WHAT_IS_REGEX) ||
            normalized.contains(RECENT_IMAGE_OCR_NON_ACTION_ALL_MULTIPLE_REGEX) ||
            normalized.contains(RECENT_IMAGE_OCR_NON_ACTION_DESCRIBE_ANALY_REGEX)
    }
}

private val MATCHES_APP_REGEX =
    Regex("""(?:当前应用|当前\s*app|本应用)?\s*最近\s*\d{1,2}\s*条?\s*(?:消息|通知|讯息)""")

private val PARAMETERS_ZH2_REGEX = Regex("""最近(\d{1,2})条?(?:消息|通知|讯息)?""")

private val PARAMETERS_RECENT_LATEST_2_REGEX =
    Regex("""(?:recent|latest|last)\s+(\d{1,2})\s+(?:current\s+app|this\s+app|app)?\s*notifications?""")

private val PARAMETERS_CURRENT_APP_REGEX =
    Regex("""(?:current\s+app|this\s+app|solin)\s+(?:recent|latest|last)\s+(\d{1,2})\s+notifications?""")

private val NOTIFICATION_NON_ACTION_ALL_GLOBAL_REGEX =
    Regex("""\b(all|global|system|other\s+apps?)\s+notifications?\b""")

private val NOTIFICATION_NON_ACTION_NOTIFICATIONS_PERMISSION_REGEX =
    Regex("""\b(notifications?\s+(permission|permissions|channel|system|architecture|api|implementation|docs?|documentation|listener|bar|drawer)|push\s+notifications?)\b""")

private val NOTIFICATION_NON_ACTION_HOW_DO_REGEX =
    Regex("""\b(how\s+do\s+i|how\s+to|implement|design)\b.*\bnotifications?\b""")

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
            MATCHES_APP_REGEX.containsMatchIn(input) ||
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
        val cleaned = input.replace(WHITESPACE_RUN_REGEX, "")
        val normalized = input.lowercase()
        val match = PARAMETERS_ZH2_REGEX.find(cleaned)
            ?: PARAMETERS_RECENT_LATEST_2_REGEX.find(normalized)
            ?: PARAMETERS_CURRENT_APP_REGEX.find(normalized)
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
            normalized.contains(NOTIFICATION_NON_ACTION_ALL_GLOBAL_REGEX) ||
            normalized.contains(NOTIFICATION_NON_ACTION_NOTIFICATIONS_PERMISSION_REGEX) ||
            normalized.contains(NOTIFICATION_NON_ACTION_HOW_DO_REGEX)
    }
}

private val HAS_CURRENT_SCREEN_REFERENCE_REGEX =
    Regex("""\b(?:current|active|this)\s+(?:screen|window|display)\b""")

private val HAS_SCREENSHOT_OR_OCR_CAPTURE_MARKER_REGEX =
    Regex("""\b(?:screenshot|screen\s+capture)\b""", RegexOption.IGNORE_CASE)

private val CURRENT_SCREENSHOT_OCR_NON_ACTION_HOW_DO_REGEX = Regex(
    """\b(?:how\s+(?:do|can|to)|what\s+is|explain|implement|implementation|api|permissions?|docs?|documentation|code|architecture|design)\b.*\b(?:current|active|this)\s+(?:screen|window|display|screenshot|screen\s+capture|ocr)\b""",
)

private val CURRENT_SCREENSHOT_OCR_NON_ACTION_CURRENT_ACTIVE_REGEX = Regex(
    """\b(?:current|active|this)\s+(?:screen|window|display)\b.*\b(?:ocr|screenshot|screen\s+capture)\b.*\b(?:api|implementation|permissions?|docs?|documentation|code|architecture|design)\b""",
)

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
            HAS_CURRENT_SCREEN_REFERENCE_REGEX.containsMatchIn(normalized)

    private fun String.hasScreenshotOrOcrCaptureMarker(normalized: String): Boolean =
        listOf("截图", "截屏", "拍屏").any { marker -> marker in this } ||
            "ocr" in normalized ||
            HAS_SCREENSHOT_OR_OCR_CAPTURE_MARKER_REGEX.containsMatchIn(normalized)

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
                CURRENT_SCREENSHOT_OCR_NON_ACTION_HOW_DO_REGEX,
            ) ||
            normalized.contains(
                CURRENT_SCREENSHOT_OCR_NON_ACTION_CURRENT_ACTIVE_REGEX,
            )
    }
}

private val MAX_CHARS_REGEX = Regex("""最多(\d{1,5})字""")

private val CURRENT_SCREEN_TEXT_NON_ACTION_DO_NOT_REGEX =
    Regex("""\b(do\s+not|don't|dont)\s+(?:read|extract|summarize)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view)\b""")

private val CURRENT_SCREEN_TEXT_NON_ACTION_CURRENT_ACTIVE_REGEX =
    Regex("""\b(?:current|active|this)\s+(?:screen|page|window|view)\s+(?:screenshots?|screen\s+captures?|images?|photos?|pixels?|ocr|api|implementation|architecture|design|schema|tests?|parser|docs?|permissions?|state)\b""")

private val CURRENT_SCREEN_TEXT_NON_ACTION_CURRENT_ACTIVE_2_REGEX =
    Regex("""\b(?:current|active|this)\s+(?:screen|page|window|view)\s+text\s+(?:api|implementation|architecture|design|schema|tests?|parser|docs?|permissions?|state)\b""")

private val CURRENT_SCREEN_TEXT_NON_ACTION_SCREENSHOTS_SCREEN_REGEX =
    Regex("""\b(?:screenshots?|screen\s+captures?|images?|photos?|pixels?|ocr)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view)\b""")

private val CURRENT_SCREEN_TEXT_NON_ACTION_WHAT_IS_REGEX =
    Regex("""\b(?:what\s+is|explain|describe|meaning)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view|screen\s+text)\b""")

private val CURRENT_SCREEN_TEXT_NON_ACTION_WHAT_IS_2_REGEX =
    Regex("""\b(?:what\s+is|explain|describe|meaning)\b.*\b(?:screen|visible|accessibility|accessible)\s+text\b""")

private val CURRENT_SCREEN_TEXT_NON_ACTION_HOW_DO_REGEX =
    Regex("""\b(?:how\s+do\s+i|how\s+to|implement|design)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view|screen\s+text)\b""")

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

    fun matches(input: String): Boolean =
        !input.looksLikeCurrentScreenTextNonAction() && referencesCurrentScreenText(input)

    /** True when [input] explicitly names the current screen's (accessible) text. */
    fun referencesCurrentScreenText(input: String): Boolean {
        val normalized = input.lowercase()
        return chineseScreenTextPattern.containsMatchIn(input) ||
            chineseAccessibleTextPattern.containsMatchIn(input) ||
            currentScreenTextPattern.containsMatchIn(normalized) ||
            visibleOrAccessibleTextPattern.containsMatchIn(normalized)
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
        val cleaned = input.replace(WHITESPACE_RUN_REGEX, "")
        return MAX_CHARS_REGEX.find(cleaned)
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
            normalized.contains(CURRENT_SCREEN_TEXT_NON_ACTION_DO_NOT_REGEX) ||
            normalized.contains(CURRENT_SCREEN_TEXT_NON_ACTION_CURRENT_ACTIVE_REGEX) ||
            normalized.contains(CURRENT_SCREEN_TEXT_NON_ACTION_CURRENT_ACTIVE_2_REGEX) ||
            normalized.contains(CURRENT_SCREEN_TEXT_NON_ACTION_SCREENSHOTS_SCREEN_REGEX) ||
            normalized.contains(CURRENT_SCREEN_TEXT_NON_ACTION_WHAT_IS_REGEX) ||
            normalized.contains(CURRENT_SCREEN_TEXT_NON_ACTION_WHAT_IS_2_REGEX) ||
            normalized.contains(CURRENT_SCREEN_TEXT_NON_ACTION_HOW_DO_REGEX)
    }
}

private val ASKS_FOR_OCR_TEXT_EXTRACTION_REGEX =
    Regex("""\b(read|extract|recognize|scan)\b.*\btext\b""")

/**
 * True when the request asks to extract text (OCR) from a [sourceNounPattern] source, e.g.
 * "识别文字" or "extract the text from my screenshot". Shared by the recent-screenshot and
 * recent-image OCR parsers, which differ only in the source noun.
 */
private fun String.asksForOcrTextExtraction(sourceNounPattern: String): Boolean {
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
        normalized.contains(ASKS_FOR_OCR_TEXT_EXTRACTION_REGEX) ||
        normalized.contains(Regex("""\btext\b.*\b(?:from|in)\b.*\b""" + sourceNounPattern + """\b"""))
}
