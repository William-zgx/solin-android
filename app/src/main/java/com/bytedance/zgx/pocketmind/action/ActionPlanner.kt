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
            "使用情况",
            "usage access",
            "usage stats",
            "地图",
            "导航",
            "邮件",
            "日程",
            "联系人",
            "手电筒",
            "剪贴板",
            "分享",
            "前台",
            "当前应用",
            "当前 app",
            "当前app",
            "app",
            "应用",
            "文件",
            "最近文件",
            "文件列表",
            "图片",
            "截图文字",
            "ocr",
            "OCR",
            "当前屏幕",
            "当前界面",
            "屏幕文字",
            "screen text",
            "current screen",
            "视频",
            "音频",
            "文档",
            "通知",
            "通知栏",
            "忙闲",
            "空闲",
            "有空",
            "calendar",
            "email",
            "map",
            "contact",
            "clipboard",
            "share",
            "availability",
            "free/busy",
            "取消",
            "取消提醒",
            "撤销",
            "链接",
            "网址",
            "网页",
            "打开应用",
            "启动应用",
        ).any { it in normalized } ||
            isReminderRequest(input) ||
            isCancelReminderRequest(input) ||
            isUsageAccessSettingsRequest(input) ||
            WebSearchActionParser.matches(input)
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
            DeviceSettingsActionParser.matches(input) ->
                DeviceSettingsActionParser.draft(input)

            isUsageAccessSettingsRequest(input) ->
                MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS.toDraft(emptyMap())

            MapSearchActionParser.matches(input) ->
                MapSearchActionParser.draft(input)

            EmailDraftActionParser.matches(input) ->
                EmailDraftActionParser.draft(input)

            DeepLinkActionParser.matches(input) ->
                DeepLinkActionParser.draft(input)

            isOpenAppDeepTargetRequest(input) ->
                MobileActionFunctions.OPEN_APP_DEEP_TARGET.toDraft(openAppDeepTargetParameters(input))

            isOpenAppIntentRequest(input) ->
                MobileActionFunctions.OPEN_APP_INTENT.toDraft(openAppIntentParameters(input))

            isReminderRequest(input) ->
                ReminderActionParser.draft(input)

            "剪贴板" in input || "clipboard" in normalized ->
                MobileActionFunctions.READ_CLIPBOARD.toDraft(emptyMap())

            ShareTextActionParser.matches(input) ->
                ShareTextActionParser.draft(input)

            isForegroundAppRequest(input) ->
                MobileActionFunctions.QUERY_FOREGROUND_APP.toDraft(emptyMap())

            isRecentNotificationRequest(input) ->
                MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS.toDraft(recentNotificationParameters(input))

            isRecentScreenshotOcrRequest(input) ->
                MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR.toDraft(recentScreenshotOcrParameters(input))

            isRecentImageOcrRequest(input) ->
                MobileActionFunctions.READ_RECENT_IMAGE_OCR.toDraft(recentImageOcrParameters(input))

            isCurrentScreenTextRequest(input) ->
                MobileActionFunctions.READ_CURRENT_SCREEN_TEXT.toDraft(currentScreenTextParameters(input))

            RecentFilesActionParser.matches(input, includeNonMediaKinds = true) ->
                RecentFilesActionParser.draft(input)

            isCalendarAvailabilityRequest(input) && calendarWindowParameters != null ->
                MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY.toDraft(calendarWindowParameters)

            isContactQueryRequest(input) ->
                MobileActionFunctions.QUERY_CONTACTS.toDraft(contactQueryParameters(input))

            isCancelReminderRequest(input) ->
                MobileActionFunctions.CANCEL_REMINDER.toDraft(cancelReminderParameters(input))

            CalendarDraftActionParser.matches(input) ->
                CalendarDraftActionParser.draft(input)

            "联系人" in input || "contact" in normalized ->
                MobileActionFunctions.CREATE_CONTACT_DRAFT.toDraft(mapOf("name" to cleanedObject(input)))

            WebSearchActionParser.matches(input) ->
                WebSearchActionParser.draft(input)

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
            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS -> "打开使用情况访问权限设置"
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
            MobileActionFunctions.CANCEL_REMINDER -> "取消提醒"
            MobileActionFunctions.OPEN_DEEP_LINK -> "打开深链"
            MobileActionFunctions.OPEN_APP_INTENT -> "打开应用"
            MobileActionFunctions.OPEN_APP_DEEP_TARGET -> "打开应用深层目标"
            MobileActionFunctions.QUERY_FOREGROUND_APP -> "查询当前前台应用"
            MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS -> "查询最近通知"
            MobileActionFunctions.QUERY_RECENT_FILES -> "查询最近文件"
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR -> "读取最近截图 OCR"
            MobileActionFunctions.READ_RECENT_IMAGE_OCR -> "读取最近图片 OCR"
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT -> "读取当前屏幕文本"
            MobileActionFunctions.QUERY_CONTACTS -> "查询联系人"
            else -> "动作草稿"
        }

    private fun summaryFor(functionName: String, parameters: Map<String, String>): String =
        when (functionName) {
            MobileActionFunctions.OPEN_WIFI_SETTINGS -> "将打开系统 Wi-Fi 设置页。"
            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS -> "将打开系统使用情况访问权限设置页，由你手动为 PocketMind 授权。"
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
            MobileActionFunctions.CANCEL_REMINDER ->
                "将取消提醒任务：${parameters["taskId"].orEmpty()}"
            MobileActionFunctions.OPEN_DEEP_LINK ->
                "将打开深度链接：${parameters["uri"].orEmpty()}"
            MobileActionFunctions.OPEN_APP_INTENT ->
                "将打开应用启动页：${parameters["packageName"].orEmpty()}"
            MobileActionFunctions.OPEN_APP_DEEP_TARGET ->
                "将打开应用详情设置：${parameters["packageName"].orEmpty()}"
            MobileActionFunctions.QUERY_FOREGROUND_APP ->
                "将读取当前前台应用信息（包名与应用名）。"
            MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS -> {
                val maxCount = parameters["maxCount"]
                if (maxCount.isNullOrBlank()) {
                    "将读取当前应用最近通知的摘要。"
                } else {
                    "将读取当前应用最近 ${maxCount} 条通知的摘要。"
                }
            }
            MobileActionFunctions.QUERY_RECENT_FILES -> {
                val maxCount = parameters["maxCount"]
                val kind = parameters["kind"].orEmpty().ifBlank { "all" }
                recentFilesSummary(kind, maxCount)
            }
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR ->
                "将读取最近 1 张截图的像素并在本地提取 OCR 文本；不会保存图片、URI 或路径。"
            MobileActionFunctions.READ_RECENT_IMAGE_OCR -> {
                val maxCount = parameters["maxCount"].orEmpty().ifBlank { "3" }
                "将扫描最近 ${maxCount} 张图片并在本地提取第一条 OCR 文本；不会保存图片、URI 或路径。"
            }
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT -> {
                val maxChars = parameters["maxChars"].orEmpty().ifBlank { "2000" }
                "将读取当前屏幕的可访问文本快照（最多 ${maxChars} 字符）；不会读取截图、像素、坐标或完整节点树。"
            }
            MobileActionFunctions.QUERY_CONTACTS -> {
                val maxCount = parameters["maxCount"]
                val query = parameters["query"].orEmpty().ifBlank { "联系人" }
                if (maxCount.isNullOrBlank()) {
                    "将按“$query”查询联系人。"
                } else {
                    "将按“$query”查询最多 ${maxCount} 个联系人。"
                }
            }
            else -> "将打开系统页面完成这个动作。"
        }

    private fun cleanedObject(input: String): String =
        input.trim()
            .removePrefix("请")
            .removePrefix("帮我")
            .trim()
            .ifBlank { input.trim() }

    private fun recentFilesSummary(kind: String, maxCount: String?): String {
        val label = recentFileKindLabel(kind)
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

    private fun recentFileKindLabel(kind: String): String =
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

    private fun isReminderRequest(input: String): Boolean =
        ReminderActionParser.matches(input)

    private fun isCalendarAvailabilityRequest(input: String): Boolean {
        val normalized = input.lowercase()
        return listOf("忙闲", "空闲", "有空", "日历可用", "日历占用")
            .any { it in input } ||
            Regex("""\b(availability|free/busy|free busy|available|calendar availability)\b""")
                .containsMatchIn(normalized)
    }

    private fun isCancelReminderRequest(input: String): Boolean {
        return ("取消" in input || "撤销" in input) &&
            TASK_ID_PATTERN.containsMatchIn(input)
    }

    private fun isForegroundAppRequest(input: String): Boolean {
        val normalized = input.lowercase()
        return listOf(
            "前台",
            "当前应用",
            "current app",
            "active app",
        ).any { it in input } ||
            Regex("\\b(foreground|current\\s+app)\\b").containsMatchIn(normalized)
    }

    private fun isUsageAccessSettingsRequest(input: String): Boolean {
        val normalized = input.lowercase()
        return listOf(
            "使用情况访问权限",
            "应用使用情况权限",
            "查看应用使用情况",
            "usage access",
            "usage stats permission",
        ).any { it in normalized } &&
            listOf("打开", "设置", "开启", "授权", "open", "settings", "grant").any { it in normalized }
    }

    private fun isRecentNotificationRequest(input: String): Boolean {
        val normalized = input.lowercase()
        return listOf(
            "最近通知",
            "最近的通知",
            "通知列表",
            "通知摘要",
            "通知栏",
            "最近通知栏",
        ).any { it in input } || Regex("\\b(notifications?|notification)\\b").containsMatchIn(normalized)
            || Regex("""最近\d{1,2}条""").containsMatchIn(normalized.replace(" ", ""))
    }

    private fun isRecentScreenshotOcrRequest(input: String): Boolean {
        val normalized = input.lowercase()
        val mentionsRecentScreenshot = ("最近" in input && ("截图" in input || "截屏" in input)) ||
            Regex("""\b(recent|latest)\b.*\b(screenshots?|screen\s+captures?)\b""")
                .containsMatchIn(normalized)
        val asksForTextExtraction = listOf(
            "识别",
            "提取",
            "摘录",
            "读取文字",
            "文字",
            "文本",
            "ocr",
        ).any { marker -> marker in normalized }
        return mentionsRecentScreenshot && asksForTextExtraction
    }

    private fun isRecentImageOcrRequest(input: String): Boolean {
        val normalized = input.lowercase()
        val mentionsRecentImage = ("最近" in input && ("图片" in input || "照片" in input || "相册" in input)) ||
            Regex("""\b(recent|latest)\b.*\b(images?|photos?|pictures?)\b""")
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
        return mentionsRecentImage && asksForTextExtraction
    }

    private fun isCurrentScreenTextRequest(input: String): Boolean {
        val normalized = input.lowercase()
        val mentionsCurrentScreen = listOf(
            "当前屏幕",
            "当前界面",
            "现在屏幕",
            "屏幕内容",
            "屏幕文字",
            "屏幕文本",
            "这个界面",
            "这页",
            "页面内容",
        ).any { marker -> marker in input } ||
            Regex("""\b(current|active|this)\s+(screen|page|window|view)\b""")
                .containsMatchIn(normalized)
        val asksForAccessibleText = listOf(
            "文字",
            "文本",
            "内容",
            "读取",
            "读一下",
            "总结",
            "摘要",
            "提取",
            "识别",
            "text",
            "summarize",
            "summary",
            "read",
            "extract",
        ).any { marker -> marker in normalized }
        val asksForVisualOnly = listOf(
            "截图",
            "截屏",
            "拍屏",
            "像素",
            "图片",
            "照片",
            "ocr",
            "screenshot",
            "screen capture",
            "image",
            "photo",
            "pixel",
        ).any { marker -> marker in normalized }
        return mentionsCurrentScreen && asksForAccessibleText && !asksForVisualOnly
    }

    private fun isContactQueryRequest(input: String): Boolean {
        val normalized = input.lowercase()
        return listOf(
            "查询联系人",
            "查联系人",
            "查找联系人",
            "搜索联系人",
            "找联系人",
            "联系人查询",
        ).any { it in input } ||
            Regex("""\b(contact|contacts)\b""").containsMatchIn(normalized) &&
                Regex("""(查询|查找|搜索|找|find|search)""").containsMatchIn(normalized)
    }

    private fun recentNotificationParameters(input: String): Map<String, String> {
        val cleaned = input.replace(Regex("\\s+"), "")
        val match = Regex("最近(\\d{1,2})条(消息|通知|讯息)?").find(cleaned)
            ?: Regex("notification|notifications?\\s*(?:recent|last)\\s+(\\d{1,2})").find(input.lowercase())
        val rawCount = match?.groupValues?.getOrNull(1) ?: return emptyMap()
        val maxCount = rawCount.toIntOrNull() ?: return emptyMap()
        return if (maxCount <= 0) emptyMap() else mapOf("maxCount" to maxCount.toString())
    }

    private fun recentScreenshotOcrParameters(input: String): Map<String, String> =
        if (Regex("""(?:最近|latest|recent)\s*1\s*(?:张|个)?""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            mapOf("maxCount" to "1")
        } else {
            emptyMap()
        }

    private fun recentImageOcrParameters(input: String): Map<String, String> =
        mapOf("maxCount" to (recentCountFrom(input)?.coerceIn(1, 3) ?: 3).toString())

    private fun currentScreenTextParameters(input: String): Map<String, String> =
        mapOf("maxChars" to (maxCharsFrom(input)?.coerceIn(1, 4000) ?: 2000).toString())

    private fun recentCountFrom(input: String): Int? {
        val normalized = input.lowercase()
        val cleaned = input.replace(Regex("\\s+"), "")
        return Regex("最近(\\d{1,2})(?:个|张|条|份)?").find(cleaned)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?:recent|latest)\s+(\d{1,2})""")
                .find(normalized)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun maxCharsFrom(input: String): Int? {
        val normalized = input.lowercase()
        val cleaned = input.replace(Regex("\\s+"), "")
        return Regex("""最多(\d{1,5})字""").find(cleaned)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?:max|limit)\s*(\d{1,5})\s*(?:chars?|characters?)""")
                .find(normalized)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun contactQueryParameters(input: String): Map<String, String> {
        val cleaned = cleanedObject(input)
        val normalizedForCount = cleaned.replace(Regex("\\s+"), "")
        val maxMatch = Regex("""(?:前|最多)\s*(\d{1,2})\s*(个|位|条)""")
            .find(normalizedForCount)
        val maxCount = maxMatch?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.toString()
        val query = cleaned
            .replace(
                Regex(
                    """^请?\s*(?:帮我\s*)?(?:查询|查找|查|搜索|找)\s*联系人\s*""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(
                Regex("""\b(contact|contacts)\b""", RegexOption.IGNORE_CASE),
                "",
            )
            .replace(
                Regex(
                    """(?:^|[\s，,;；]*)?(?:前|最多)\s*\d{1,2}\s*(?:个|位|条)""",
                ),
                "",
            )
            .replace(Regex("""\bcontacts?\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { "联系人" }

        return if (maxCount == null) {
            mapOf("query" to query)
        } else {
            mapOf(
                "query" to query,
                "maxCount" to maxCount,
            )
        }
    }

    private fun isOpenAppIntentRequest(input: String): Boolean {
        if (URI_SCHEME_PATTERN.containsMatchIn(input)) return false
        val hasTrigger = APP_INTENT_TRIGGER_PATTERN.containsMatchIn(input)
        val hasKnownPackage = knownPackageFromInput(input) != null
        val hasPackageLike = APP_INTENT_PACKAGE_PATTERN.containsMatchIn(input)
        return hasTrigger && (hasKnownPackage || hasPackageLike)
    }

    private fun isOpenAppDeepTargetRequest(input: String): Boolean {
        if (URI_SCHEME_PATTERN.containsMatchIn(input)) return false
        val hasTrigger = APP_INTENT_TRIGGER_PATTERN.containsMatchIn(input)
        val hasKnownPackage = knownPackageFromInput(input) != null
        val hasPackageLike = APP_INTENT_PACKAGE_PATTERN.containsMatchIn(input)
        return hasTrigger && isAppDetailsSettingsTarget(input) && (hasKnownPackage || hasPackageLike)
    }

    private fun openAppIntentParameters(input: String): Map<String, String> {
        val packageName = knownPackageFromInput(input)
            ?: APP_INTENT_PACKAGE_PATTERN.find(input)?.value
            ?: return emptyMap()
        return mapOf("packageName" to packageName)
    }

    private fun openAppDeepTargetParameters(input: String): Map<String, String> {
        val packageName = knownPackageFromInput(input)
            ?: APP_INTENT_PACKAGE_PATTERN.find(input)?.value
            ?: return emptyMap()
        return mapOf(
            AppDeepTargets.TARGET_ID_ARGUMENT to AppDeepTargets.APP_DETAILS_SETTINGS_ID,
            AppDeepTargets.PACKAGE_NAME_ARGUMENT to packageName,
        )
    }

    private fun knownPackageFromInput(input: String): String? {
        val normalized = input.lowercase()
        return KNOWN_APP_PACKAGES.entries.firstNotNullOfOrNull { (alias, packageName) ->
            if (normalized.contains(alias.lowercase())) packageName else null
        }
    }

    private fun isAppDetailsSettingsTarget(input: String): Boolean {
        val normalized = input.lowercase()
        return listOf(
            "应用详情",
            "应用信息",
            "应用设置",
            "app info",
            "app settings",
            "application details",
        ).any { it in normalized }
    }

    private fun cancelReminderParameters(input: String): Map<String, String> {
        val taskId = TASK_ID_PATTERN.find(input)?.value.orEmpty()
        return mapOf("taskId" to taskId)
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
        val ISO_OFFSET_DATE_TIME_PATTERN =
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})""")
        val TASK_ID_PATTERN = Regex("task-[A-Za-z0-9_-]+")
        val APP_INTENT_TRIGGER_PATTERN = Regex("""(打开|打开并|启动|启动并)\s*(?:应用|app|应用程序)?""")
        val APP_INTENT_PACKAGE_PATTERN = Regex("""\b[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+\b""")
        val URI_SCHEME_PATTERN = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://""")
        val KNOWN_APP_PACKAGES = mapOf(
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
            "美团" to "com.sankuai.meituan",
            "meituan" to "com.sankuai.meituan",
        )
    }
}
