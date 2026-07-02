package com.bytedance.zgx.solin.action

import com.bytedance.zgx.solin.tool.ToolRegistry
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class MobileActionPlanner(
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val toolRegistry: ToolRegistry = ToolRegistry(),
) {
    fun classifyIntent(input: String): IntentCandidate {
        parseModelOutput(input)?.let { draft ->
            return IntentCandidate(
                toolName = draft.functionName,
                confidence = ActionIntentConfidence.High,
                reason = "model tool call",
            )
        }
        if (input.looksLikeSequentialAction()) {
            return IntentCandidate(
                toolName = null,
                confidence = ActionIntentConfidence.None,
                reason = "sequential input requires Agent observation replanning",
            )
        }
        val draft = inferDraft(input)
        return if (draft != null) {
            IntentCandidate(
                toolName = draft.functionName,
                confidence = ActionIntentConfidence.High,
                reason = "conservative rule matched",
            )
        } else {
            IntentCandidate(
                toolName = null,
                confidence = ActionIntentConfidence.None,
                reason = "no supported explicit action intent",
            )
        }
    }

    fun isLikelyAction(input: String): Boolean {
        return classifyIntent(input).isAction
    }

    fun plan(input: String): ActionPlan =
        parseModelOutput(input)?.let { ActionPlan(ActionPlanKind.Draft, it) }
            ?: inferDraft(input)?.let { ActionPlan(ActionPlanKind.Draft, it) }
            ?: ActionPlan(ActionPlanKind.NoAction)

    fun parseModelOutput(output: String): ActionDraft? {
        val callOutput = output.singleModelToolCallCandidate() ?: return null
        val match = CALL_PATTERN.matchEntire(callOutput) ?: return null
        val functionName = match.groupValues[1]
        if (!toolRegistry.isKnownTool(functionName)) return null
        val rawParameters = match.groupValues[2]
        val parameters = parseStrictJsonObject(rawParameters)
            ?: rawParameters
                .trim()
                .takeIf { value -> value.startsWith("{") && value.endsWith("}") }
                ?.let { value -> parseJsonLikeObject(value) }
            ?: return null
        return functionName.toDraft(parameters)
    }

    fun parseModelToolOutput(output: String): ModelToolOutputParseResult {
        val candidates = output.modelToolCallCandidates()
        if (candidates.isEmpty()) return ModelToolOutputParseResult.None
        if (candidates.size > 1) {
            return ModelToolOutputParseResult.Rejected(
                toolName = null,
                reason = "Multiple model tool calls are not allowed",
            )
        }
        val match = CALL_PATTERN.matchEntire(candidates.single())
            ?: return ModelToolOutputParseResult.Rejected(
                toolName = null,
                reason = "Invalid model tool call format",
            )
        val functionName = match.groupValues[1]
        if (!toolRegistry.isKnownTool(functionName)) {
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

            SystemSettingsActionParser.matches(input) ->
                SystemSettingsActionParser.draft(input)

            MapSearchActionParser.matches(input) ->
                MapSearchActionParser.draft(input)

            EmailDraftActionParser.matches(input) ->
                EmailDraftActionParser.draft(input)

            DeepLinkActionParser.matches(input) ->
                DeepLinkActionParser.draft(input)

            CameraActionParser.matches(input) ->
                CameraActionParser.draft()

            SystemAlarmActionParser.matches(input) ->
                SystemAlarmActionParser.draft(input)

            SystemTimerActionParser.matches(input) ->
                SystemTimerActionParser.draft(input)

            AppNavigationActionParser.matches(input) ->
                AppNavigationActionParser.draft(input)

            ReminderActionParser.matches(input) ->
                ReminderActionParser.draft(input)

            AbsoluteReminderActionParser.matches(input, clockMillis(), zoneId) ->
                AbsoluteReminderActionParser.draft(input, clockMillis(), zoneId)

            PeriodicCheckActionParser.matches(input) ->
                PeriodicCheckActionParser.draft(input)

            BackgroundTasksQueryActionParser.matches(input) ->
                BackgroundTasksQueryActionParser.draft(input)

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

            CurrentScreenshotOcrActionParser.matches(input) ->
                CurrentScreenshotOcrActionParser.draft()

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

    private fun String.toDraft(parameters: Map<String, String>): ActionDraft {
        val normalizedParameters = normalizeModelActionParameters(parameters)
        return ActionDraft(
            functionName = this,
            title = titleFor(this),
            summary = summaryFor(this, normalizedParameters),
            parameters = normalizedParameters,
            requiresConfirmation = this != MobileActionFunctions.WEB_SEARCH,
        )
    }

    private fun String.normalizeModelActionParameters(parameters: Map<String, String>): Map<String, String> {
        if (this !in UI_TARGET_TOOL_NAMES) return parameters
        val target = parameters["target"] ?: return parameters
        val normalizedTarget = target.extractUiTargetArgumentValue(toolName = this)
        if (normalizedTarget == target) return parameters
        return parameters + ("target" to normalizedTarget)
    }

    private fun titleFor(functionName: String): String =
        when (functionName) {
            MobileActionFunctions.OPEN_WIFI_SETTINGS -> "打开 Wi-Fi 设置"
            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS -> "打开使用情况访问权限设置"
            MobileActionFunctions.OPEN_SYSTEM_SETTINGS -> "打开系统设置页"
            MobileActionFunctions.SEARCH_MAPS -> "地图搜索"
            MobileActionFunctions.WEB_SEARCH -> "Web 搜索"
            MobileActionFunctions.COMPOSE_EMAIL -> "邮件草稿"
            MobileActionFunctions.CREATE_CALENDAR_EVENT -> "日程草稿"
            MobileActionFunctions.CREATE_CONTACT_DRAFT -> "联系人草稿"
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS -> "打开手电筒设置"
            MobileActionFunctions.SCHEDULE_REMINDER -> "后台提醒"
            MobileActionFunctions.SET_SYSTEM_ALARM -> "系统闹钟"
            MobileActionFunctions.SET_SYSTEM_TIMER -> "系统倒计时"
            MobileActionFunctions.CONFIGURE_PERIODIC_CHECK -> "周期检查"
            MobileActionFunctions.QUERY_BACKGROUND_TASKS -> "查询后台任务"
            MobileActionFunctions.READ_CLIPBOARD -> "读取剪贴板"
            MobileActionFunctions.SHARE_TEXT -> "系统分享"
            MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY -> "查询日历忙闲"
            MobileActionFunctions.CANCEL_REMINDER -> "取消提醒"
            MobileActionFunctions.OPEN_DEEP_LINK -> "打开深链"
            MobileActionFunctions.OPEN_CAMERA -> "打开相机"
            MobileActionFunctions.OPEN_APP_BY_NAME -> "打开应用"
            MobileActionFunctions.OPEN_APP_INTENT -> "打开应用"
            MobileActionFunctions.OPEN_APP_DEEP_TARGET -> "打开应用深层目标"
            MobileActionFunctions.QUERY_FOREGROUND_APP -> "查询当前前台应用"
            MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS -> "查询最近通知"
            MobileActionFunctions.QUERY_RECENT_FILES -> "查询最近文件"
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR -> "读取最近截图 OCR"
            MobileActionFunctions.READ_RECENT_IMAGE_OCR -> "读取最近图片 OCR"
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT -> "读取当前屏幕文本"
            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR -> "截取当前屏幕 OCR"
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN -> "观察当前屏幕"
            MobileActionFunctions.QUERY_CONTACTS -> "查询联系人"
            else -> "动作草稿"
        }

    private fun summaryFor(functionName: String, parameters: Map<String, String>): String =
        when (functionName) {
            MobileActionFunctions.OPEN_WIFI_SETTINGS -> "将打开系统 Wi-Fi 设置页。"
            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS -> "将打开系统使用情况访问权限设置页，由你手动为Solin授权。"
            MobileActionFunctions.OPEN_SYSTEM_SETTINGS ->
                "将打开${SystemSettingsTargets.pageLabel(parameters["target"].orEmpty())}，由你手动完成后续操作。"
            MobileActionFunctions.SEARCH_MAPS -> "将在地图中搜索：${parameters["query"].orEmpty()}"
            MobileActionFunctions.WEB_SEARCH -> "将使用 Web 搜索工具查询并整理结果：${parameters["query"].orEmpty()}"
            MobileActionFunctions.COMPOSE_EMAIL -> "将打开邮件 App 并填入草稿内容。"
            MobileActionFunctions.CREATE_CALENDAR_EVENT -> "将打开日历新建事件页面。"
            MobileActionFunctions.CREATE_CONTACT_DRAFT -> "将打开联系人新建页面。"
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS -> "将打开系统设置，由你手动确认手电筒相关操作。"
            MobileActionFunctions.SCHEDULE_REMINDER ->
                if (parameters["triggerAtMillis"].isNullOrBlank()) {
                    "将在 ${parameters["delayMinutes"].orEmpty()} 分钟后提醒：${parameters["title"].orEmpty()}"
                } else {
                    "将在指定时间提醒：${parameters["title"].orEmpty()}"
                }
            MobileActionFunctions.SET_SYSTEM_ALARM -> {
                val hour = parameters["hour"].orEmpty()
                val minutes = parameters["minutes"].orEmpty()
                val recurrence = if (parameters["recurrence"] == "daily") "每天" else "下次"
                "将打开系统闹钟界面设置$recurrence ${hour.padStart(2, '0')}:${minutes.padStart(2, '0')} 的闹钟。"
            }
            MobileActionFunctions.SET_SYSTEM_TIMER ->
                "将打开系统计时器界面设置 ${parameters["lengthSeconds"].orEmpty()} 秒倒计时。"
            MobileActionFunctions.CONFIGURE_PERIODIC_CHECK -> {
                if (parameters["enabled"] == "false") {
                    "将关闭本地提醒周期检查。"
                } else {
                    val intervalMinutes = parameters["intervalMinutes"].orEmpty().ifBlank { "360" }
                    "将开启本地提醒周期检查，间隔 $intervalMinutes 分钟。"
                }
            }
            MobileActionFunctions.QUERY_BACKGROUND_TASKS -> {
                val scope = parameters["scope"].orEmpty().ifBlank { "active" }
                val maxCount = parameters["maxCount"]
                backgroundTasksSummary(scope, maxCount)
            }
            MobileActionFunctions.READ_CLIPBOARD -> "将读取当前剪贴板文本。"
            MobileActionFunctions.SHARE_TEXT -> "将打开系统分享面板并填入文本。"
            MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY ->
                "将只读查询本机日历忙闲：${parameters["start"].orEmpty()} 到 ${parameters["end"].orEmpty()}"
            MobileActionFunctions.CANCEL_REMINDER ->
                "将取消提醒任务：${parameters["taskId"].orEmpty()}"
            MobileActionFunctions.OPEN_DEEP_LINK ->
                "将打开深度链接：${parameters["uri"].orEmpty()}"
            MobileActionFunctions.OPEN_CAMERA ->
                "将打开系统相机应用；不会拍照、录像或读取照片。"
            MobileActionFunctions.OPEN_APP_BY_NAME ->
                "将按本机应用名打开：${parameters["appName"].orEmpty()}"
            MobileActionFunctions.OPEN_APP_INTENT ->
                "将打开应用启动页：${parameters["packageName"].orEmpty()}"
            MobileActionFunctions.OPEN_APP_DEEP_TARGET ->
                "将打开应用详情设置：${parameters["packageName"].orEmpty()}"
            MobileActionFunctions.QUERY_FOREGROUND_APP ->
                "将通过 UsageStats 估计当前前台应用（包名与应用名）；不读取屏幕内容或使用历史。"
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
            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR ->
                "将请求 Android MediaProjection 前台同意，单次截取当前屏幕并在本地提取 OCR 文本；不会保存图片、像素、URI、路径或窗口标题。"
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN ->
                "将读取当前屏幕的可访问状态快照；不会读取截图像素或发送远程。"
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

    private fun backgroundTasksSummary(scope: String, maxCount: String?): String {
        val countText = maxCount?.takeIf { it.isNotBlank() }?.let { "最多 $it 条" }.orEmpty()
        return when (scope) {
            "history" -> "将只读查询本地后台任务历史$countText，不返回提醒正文。"
            "policy" -> "将只读查询本地提醒周期检查策略，不返回提醒正文。"
            "all" -> "将只读查询本地后台任务与周期检查策略$countText，不返回提醒正文。"
            else -> "将只读查询本地活动后台任务$countText，不返回提醒正文。"
        }
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

private fun String.singleModelToolCallCandidate(): String? =
    modelToolCallCandidates().singleOrNull()

private fun String.modelToolCallCandidates(): List<String> {
    val normalized = trim().stripSingleMarkdownFence()
    if (normalized.isBlank()) return emptyList()
    if (!normalized.trimStart().startsWith("call:")) return emptyList()
    val callLines = normalized.lines()
        .map { line -> line.trim() }
        .filter { line -> line.startsWith("call:") }
    if (callLines.size > 1) return callLines
    return listOf(normalized.trim())
}

private fun String.stripSingleMarkdownFence(): String {
    val lines = trim().lines()
    if (lines.size < 3) return trim()
    val first = lines.first().trim()
    val last = lines.last().trim()
    if (!first.startsWith("```") || last != "```") return trim()
    return lines.drop(1).dropLast(1).joinToString(separator = "\n").trim()
}

private val UI_TARGET_TOOL_NAMES = setOf(
    MobileActionFunctions.UI_TAP,
    MobileActionFunctions.UI_TYPE_TEXT,
    MobileActionFunctions.UI_SCROLL,
)

private val EXPLICIT_TARGET_ARGUMENT_PATTERN =
    Regex("""(?:^|[{\s,(])target\s*=\s*([^,;}\])]+)""")

internal fun String.extractUiTargetArgumentValue(toolName: String): String {
    val value = trim().trimMatchingQuotes()
    val explicitTarget = EXPLICIT_TARGET_ARGUMENT_PATTERN.find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.cleanExtractedTargetArgument()
    if (!explicitTarget.isNullOrBlank()) return explicitTarget

    return toolName.preferredUiTargetModeKeys()
        .asSequence()
        .mapNotNull { key -> value.extractTargetValueForModeKey(key) }
        .firstOrNull()
        ?: value
}

private fun String.preferredUiTargetModeKeys(): List<String> =
    when (this) {
        MobileActionFunctions.UI_TYPE_TEXT -> listOf("type", "tap", "ocrFallback")
        MobileActionFunctions.UI_TAP -> listOf("tap", "type", "ocrFallback")
        MobileActionFunctions.UI_SCROLL -> listOf("scroll")
        else -> emptyList()
    }

private fun String.extractTargetValueForModeKey(key: String): String? {
    val pattern = Regex("""(?:^|[(,\s])${Regex.escape(key)}\s*=\s*([^,;)\]}]+)""")
    return pattern.find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.substringBefore("|")
        ?.cleanExtractedTargetArgument()
        ?.takeIf { value -> value.isNotBlank() }
}

private fun String.cleanExtractedTargetArgument(): String =
    trim()
        .trimMatchingQuotes()
        .trimEnd('}', ')', ']')
        .trim()
        .trimMatchingQuotes()

private fun String.trimMatchingQuotes(): String {
    val trimmed = trim()
    if (trimmed.length < 2) return trimmed
    val first = trimmed.first()
    val last = trimmed.last()
    return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        trimmed.substring(1, trimmed.length - 1)
    } else {
        trimmed
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
