package com.bytedance.zgx.pocketmind.action

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

interface ActionPlanner {
    fun isLikelyAction(input: String): Boolean
    fun plan(input: String): ActionPlan
}

class MobileActionPlanner : ActionPlanner {
    override fun isLikelyAction(input: String): Boolean {
        val normalized = input.lowercase()
        return DeviceSettingsActionParser.matches(input) ||
            MapSearchActionParser.matches(input) ||
            EmailDraftActionParser.matches(input) ||
            CalendarDraftActionParser.matches(input) ||
            ContactDraftActionParser.matches(input) ||
            DeepLinkActionParser.matches(input) ||
            AppNavigationActionParser.matches(input) ||
            ShareTextActionParser.matches(input) ||
            ForegroundAppActionParser.matches(input) ||
            RecentNotificationsActionParser.matches(input) ||
            RecentFilesActionParser.matches(input, includeNonMediaKinds = true) ||
            RecentScreenshotOcrActionParser.matches(input) ||
            RecentImageOcrActionParser.matches(input) ||
            CurrentScreenTextActionParser.matches(input) ||
            CalendarAvailabilityActionParser.matches(input) ||
            ContactQueryActionParser.matches(input) ||
            isReminderRequest(input) ||
            isCancelReminderRequest(input) ||
            WebSearchActionParser.matches(input) ||
            (("剪贴板" in input || "clipboard" in normalized) && !input.looksLikeClipboardContextNonAction())
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

    fun parseModelToolOutput(output: String): ModelToolOutputParseResult {
        val trimmed = output.trim()
        if (!trimmed.startsWith("call:")) return ModelToolOutputParseResult.None
        val match = CALL_PATTERN.matchEntire(trimmed)
            ?: return ModelToolOutputParseResult.Rejected(
                toolName = null,
                reason = "Invalid model tool call format",
            )
        val functionName = match.groupValues[1]
        if (functionName !in MobileActionFunctions.supported) {
            return ModelToolOutputParseResult.Rejected(
                toolName = functionName,
                reason = "Unknown tool: $functionName",
            )
        }
        val parameters = parseStrictJsonObject(match.groupValues[2])
            ?: return ModelToolOutputParseResult.Rejected(
                toolName = functionName,
                reason = "Model tool call arguments must be a JSON object with primitive values",
            )
        return ModelToolOutputParseResult.Parsed(functionName.toDraft(parameters))
    }

    private fun inferDraft(input: String): ActionDraft? {
        val normalized = input.lowercase()
        if (input.looksLikeSequentialAction()) return null
        return when {
            DeviceSettingsActionParser.matches(input) ->
                DeviceSettingsActionParser.draft(input)

            MapSearchActionParser.matches(input) ->
                MapSearchActionParser.draft(input)

            EmailDraftActionParser.matches(input) ->
                EmailDraftActionParser.draft(input)

            DeepLinkActionParser.matches(input) ->
                DeepLinkActionParser.draft(input)

            AppNavigationActionParser.matches(input) ->
                AppNavigationActionParser.draft(input)

            isReminderRequest(input) ->
                ReminderActionParser.draft(input)

            ("剪贴板" in input || "clipboard" in normalized) && !input.looksLikeClipboardContextNonAction() ->
                MobileActionFunctions.READ_CLIPBOARD.toDraft(emptyMap())

            ShareTextActionParser.matches(input) ->
                ShareTextActionParser.draft(input)

            ForegroundAppActionParser.matches(input) ->
                ForegroundAppActionParser.draft()

            RecentNotificationsActionParser.matches(input) ->
                RecentNotificationsActionParser.draft(input)

            RecentScreenshotOcrActionParser.matches(input) ->
                RecentScreenshotOcrActionParser.draft(input)

            RecentImageOcrActionParser.matches(input) ->
                RecentImageOcrActionParser.draft(input)

            CurrentScreenTextActionParser.matches(input) ->
                CurrentScreenTextActionParser.draft(input)

            RecentFilesActionParser.matches(input, includeNonMediaKinds = true) ->
                RecentFilesActionParser.draft(input)

            CalendarAvailabilityActionParser.matches(input) ->
                CalendarAvailabilityActionParser.draft(input)

            ContactQueryActionParser.matches(input) ->
                ContactQueryActionParser.draft(input)

            CancelReminderActionParser.matches(input) ->
                CancelReminderActionParser.draft(input)

            CalendarDraftActionParser.matches(input) ->
                CalendarDraftActionParser.draft(input)

            ContactDraftActionParser.matches(input) ->
                ContactDraftActionParser.draft(input)

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

    private fun isCancelReminderRequest(input: String): Boolean =
        CancelReminderActionParser.matches(input)

    private fun parseJsonLikeObject(raw: String): Map<String, String> {
        val content = raw.trim().removePrefix("{").removeSuffix("}")
        if (content.isBlank()) return emptyMap()
        return KEY_VALUE_PATTERN.findAll(content)
            .associate { match ->
                match.groupValues[1] to match.groupValues[2]
            }
    }

    private fun parseStrictJsonObject(raw: String): Map<String, String>? {
        val tokener = JSONTokener(raw)
        val json = runCatching { tokener.nextValue() as? JSONObject }.getOrNull() ?: return null
        if (tokener.nextClean().code != 0) return null
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (json.isNull(key)) return null
            val value = json.get(key)
            result[key] = when (value) {
                is String -> value
                is Number -> value.toString()
                is Boolean -> value.toString()
                is JSONObject, is JSONArray -> return null
                else -> return null
            }
        }
        return result
    }

    private companion object {
        val CALL_PATTERN = Regex("""^call:([a-zA-Z0-9_]+)\s*(\{.*\})$""", RegexOption.DOT_MATCHES_ALL)
        val KEY_VALUE_PATTERN = Regex(""""([^"]+)"\s*:\s*"([^"]*)"""")
    }
}

private fun String.looksLikeClipboardContextNonAction(): Boolean {
    val normalized = lowercase()
    return startsWithActionNegation() ||
        listOf(
            "剪贴板权限",
            "剪贴板接口",
            "剪贴板 API",
            "剪贴板api",
            "剪贴板怎么",
            "如何读取剪贴板",
            "怎么读取剪贴板",
            "剪贴板是什么",
        ).any { it in this } ||
        normalized.contains(Regex("""\b(?:clipboard)\s+(?:permissions?|api|implementation|docs?|documentation|schema|tests?)\b""")) ||
        normalized.contains(Regex("""\b(?:how\s+(?:do|can|to)|what\s+is|explain)\b.*\bclipboard\b"""))
}
