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
            isWebSearchRequest(input)
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

            isUsageAccessSettingsRequest(input) ->
                MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS.toDraft(emptyMap())

            "地图" in input || "导航" in input || "map" in normalized ->
                MobileActionFunctions.SEARCH_MAPS.toDraft(mapOf("query" to cleanedObject(input)))

            "邮件" in input || "email" in normalized || "mail" in normalized ->
                MobileActionFunctions.COMPOSE_EMAIL.toDraft(mapOf("body" to cleanedObject(input)))

            isOpenDeepLinkRequest(input) ->
                MobileActionFunctions.OPEN_DEEP_LINK.toDraft(mapOf("uri" to extractUri(input)))

            isOpenAppDeepTargetRequest(input) ->
                MobileActionFunctions.OPEN_APP_DEEP_TARGET.toDraft(openAppDeepTargetParameters(input))

            isOpenAppIntentRequest(input) ->
                MobileActionFunctions.OPEN_APP_INTENT.toDraft(openAppIntentParameters(input))

            isReminderRequest(input) ->
                ReminderActionParser.draft(input)

            "剪贴板" in input || "clipboard" in normalized ->
                MobileActionFunctions.READ_CLIPBOARD.toDraft(emptyMap())

            isShareTextRequest(input) ->
                MobileActionFunctions.SHARE_TEXT.toDraft(shareTextParameters(input))

            isForegroundAppRequest(input) ->
                MobileActionFunctions.QUERY_FOREGROUND_APP.toDraft(emptyMap())

            isRecentNotificationRequest(input) ->
                MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS.toDraft(recentNotificationParameters(input))

            isRecentFilesRequest(input) ->
                MobileActionFunctions.QUERY_RECENT_FILES.toDraft(recentFilesParameters(input))

            isCalendarAvailabilityRequest(input) && calendarWindowParameters != null ->
                MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY.toDraft(calendarWindowParameters)

            isContactQueryRequest(input) ->
                MobileActionFunctions.QUERY_CONTACTS.toDraft(contactQueryParameters(input))

            isCancelReminderRequest(input) ->
                MobileActionFunctions.CANCEL_REMINDER.toDraft(cancelReminderParameters(input))

            "日程" in input || "calendar" in normalized ->
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
                    "将读取最近未读通知的摘要。"
                } else {
                    "将读取最近 ${maxCount} 条通知的摘要。"
                }
            }
            MobileActionFunctions.QUERY_RECENT_FILES -> {
                val maxCount = parameters["maxCount"]
                val kind = parameters["kind"].orEmpty().ifBlank { "全部" }
                if (maxCount.isNullOrBlank()) {
                    "将读取最近的${kind}文件。"
                } else {
                    "将读取最近 ${maxCount} 个${kind}文件。"
                }
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
        ReminderActionParser.matches(input)

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

    private fun isRecentFilesRequest(input: String): Boolean {
        val normalized = input.lowercase()
        return listOf(
            "最近文件",
            "最近文档",
            "最近图片",
            "最近截图",
            "最近截屏",
            "最近视频",
            "最近音频",
            "最近下载",
            "查询文件",
            "文件列表",
            "最近文件列表",
        ).any { it in input } ||
            Regex("""最近\s*\d{0,2}\s*(?:个|份|条|张)?\s*(文件|文档|图片|照片|截图|截屏|视频|音频|下载)""")
                .containsMatchIn(input) ||
            Regex(
                """\b(recent|latest)\b.*\b(files?|documents?|images?|screenshots?|screen\s+captures?|videos?|audios?|photos?)\b""",
            )
                .containsMatchIn(normalized)
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

    private fun recentFilesParameters(input: String): Map<String, String> {
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
            "图片" in input || "image" in normalized || "photo" in normalized -> "images"
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

    private fun isOpenDeepLinkRequest(input: String): Boolean {
        return DEEP_LINK_PATTERN.containsMatchIn(input)
    }

    private fun isOpenAppIntentRequest(input: String): Boolean {
        val hasTrigger = APP_INTENT_TRIGGER_PATTERN.containsMatchIn(input)
        val hasKnownPackage = knownPackageFromInput(input) != null
        val hasPackageLike = APP_INTENT_PACKAGE_PATTERN.containsMatchIn(input)
        return hasTrigger && (hasKnownPackage || hasPackageLike)
    }

    private fun isOpenAppDeepTargetRequest(input: String): Boolean {
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

    private fun cleanUri(raw: String): String =
        raw.trim().trimEnd(*TRAILING_URI_PUNCTUATION.toCharArray())

    private fun extractUri(input: String): String {
        val directMatch = DEEP_LINK_PATTERN.find(input)?.value
        if (directMatch != null) {
            return cleanUri(directMatch)
        }
        return cleanedObject(input)
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
        val DEEP_LINK_PATTERN = Regex("""\bhttps://\S+""", RegexOption.IGNORE_CASE)
        val APP_INTENT_TRIGGER_PATTERN = Regex("""(打开|打开并|启动|启动并)\s*(?:应用|app|应用程序)?""")
        val APP_INTENT_PACKAGE_PATTERN = Regex("""\b[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+\b""")
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
        val TRAILING_URI_PUNCTUATION = ".,;:!?)]}。！）；】"
    }
}
