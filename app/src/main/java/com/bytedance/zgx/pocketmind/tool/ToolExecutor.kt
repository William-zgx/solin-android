package com.bytedance.zgx.pocketmind.tool

import android.provider.Settings
import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.background.BackgroundTaskScheduler
import com.bytedance.zgx.pocketmind.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.pocketmind.background.ScheduledTask
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityProvider
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityQuery
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityQueryValidation
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityReadResult
import com.bytedance.zgx.pocketmind.device.ForegroundAppProvider
import com.bytedance.zgx.pocketmind.device.ForegroundAppReadResult
import com.bytedance.zgx.pocketmind.device.ContactSummaryItem
import com.bytedance.zgx.pocketmind.device.ContactSummaryProvider
import com.bytedance.zgx.pocketmind.device.ContactSummaryReadResult
import com.bytedance.zgx.pocketmind.device.CurrentScreenControlProvider
import com.bytedance.zgx.pocketmind.device.CurrentScreenTextProvider
import com.bytedance.zgx.pocketmind.device.CurrentScreenTextReadResult
import com.bytedance.zgx.pocketmind.device.DEVICE_CONTROL_METADATA_POLICY
import com.bytedance.zgx.pocketmind.device.DEVICE_CONTROL_SOURCE_ACCESSIBILITY
import com.bytedance.zgx.pocketmind.device.NotificationSummaryItem
import com.bytedance.zgx.pocketmind.device.NotificationSummaryProvider
import com.bytedance.zgx.pocketmind.device.NotificationSummaryReadResult
import com.bytedance.zgx.pocketmind.device.RecentFileItem
import com.bytedance.zgx.pocketmind.device.RecentFileProvider
import com.bytedance.zgx.pocketmind.device.RecentFileReadResult
import com.bytedance.zgx.pocketmind.device.RecentImageTextProvider
import com.bytedance.zgx.pocketmind.device.RecentImageTextReadResult
import com.bytedance.zgx.pocketmind.device.AppSearchResultVerifier
import com.bytedance.zgx.pocketmind.device.SearchResultVerification
import com.bytedance.zgx.pocketmind.device.ScreenBounds
import com.bytedance.zgx.pocketmind.device.ScreenNode
import com.bytedance.zgx.pocketmind.device.ScreenStateReadResult
import com.bytedance.zgx.pocketmind.device.ScreenStateSnapshot
import com.bytedance.zgx.pocketmind.device.UiActionFailureKind
import com.bytedance.zgx.pocketmind.device.UiActionReadResult
import com.bytedance.zgx.pocketmind.device.UiActionStatus
import com.bytedance.zgx.pocketmind.device.UiScrollDirection
import com.bytedance.zgx.pocketmind.multimodal.CurrentScreenshotOcrContract
import com.bytedance.zgx.pocketmind.multimodal.CurrentScreenshotOcrProvider
import com.bytedance.zgx.pocketmind.multimodal.CurrentScreenshotOcrReadResult
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_CONTACT_SUMMARY_COUNT = 20
private const val MAX_NOTIFICATION_SUMMARY_COUNT = 20
private const val DEFAULT_BACKGROUND_TASK_QUERY_COUNT = 20
private const val MAX_BACKGROUND_TASK_QUERY_COUNT = 50
private const val BACKGROUND_TASK_METADATA_POLICY = "background_tasks_local_only_no_reminder_body"

interface ToolExecutor {
    fun execute(request: ToolRequest): ToolResult
}

class RoutingToolExecutor(
    private val calendarAvailabilityProvider: CalendarAvailabilityProvider,
    private val foregroundAppProvider: ForegroundAppProvider,
    private val contactSummaryProvider: ContactSummaryProvider,
    private val notificationSummaryProvider: NotificationSummaryProvider,
    private val recentFileProvider: RecentFileProvider,
    private val webSearchProvider: WebSearchProvider,
    private val delegate: ToolExecutor,
    private val backgroundTaskScheduler: BackgroundTaskScheduler? = null,
    private val recentImageTextProvider: RecentImageTextProvider? = null,
    private val currentScreenTextProvider: CurrentScreenTextProvider? = null,
    private val currentScreenshotOcrProvider: CurrentScreenshotOcrProvider? = null,
    private val currentScreenControlProvider: CurrentScreenControlProvider? = null,
    private val toolRegistry: ToolRegistry = ToolRegistry(),
) : ToolExecutor {
    private val calendarAvailabilityToolExecutor =
        CalendarAvailabilityToolExecutor(calendarAvailabilityProvider)
    private val foregroundAppToolExecutor = ForegroundAppToolExecutor(foregroundAppProvider)
    private val contactSummaryToolExecutor = ContactSummaryToolExecutor(contactSummaryProvider)
    private val notificationSummaryToolExecutor =
        NotificationSummaryToolExecutor(notificationSummaryProvider)
    private val recentFilesToolExecutor = RecentFilesToolExecutor(recentFileProvider)
    private val webSearchToolExecutor = WebSearchToolExecutor(webSearchProvider)
    private val backgroundTasksToolExecutor =
        backgroundTaskScheduler?.let(::BackgroundTasksToolExecutor)
    private val recentScreenshotOcrToolExecutor =
        recentImageTextProvider?.let(::RecentScreenshotOcrToolExecutor)
    private val currentScreenTextToolExecutor =
        currentScreenTextProvider?.let(::CurrentScreenTextToolExecutor)
    private val currentScreenshotOcrToolExecutor =
        CurrentScreenshotOcrToolExecutor(currentScreenshotOcrProvider)
    private val deviceControlToolExecutor =
        DeviceControlToolExecutor(currentScreenControlProvider)

    override fun execute(request: ToolRequest): ToolResult {
        if (request.isDeviceControlTool()) return deviceControlToolExecutor.execute(request)
        return when (request.toolName) {
            MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY ->
                calendarAvailabilityToolExecutor.execute(request)
            MobileActionFunctions.QUERY_FOREGROUND_APP ->
                foregroundAppToolExecutor.execute(request)
            MobileActionFunctions.QUERY_CONTACTS ->
                contactSummaryToolExecutor.execute(request)
            MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS ->
                notificationSummaryToolExecutor.execute(request)
            MobileActionFunctions.QUERY_RECENT_FILES ->
                recentFilesToolExecutor.execute(request)
            MobileActionFunctions.WEB_SEARCH ->
                webSearchToolExecutor.execute(request)
            MobileActionFunctions.QUERY_BACKGROUND_TASKS ->
                backgroundTasksToolExecutor?.execute(request)
                    ?: request.failed(
                        code = ToolErrorCode.ExecutionFailed,
                        summary = "后台任务本地存储不可用",
                        retryable = true,
                        data = request.localOnlyData(),
                    )
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            MobileActionFunctions.READ_RECENT_IMAGE_OCR ->
                recentScreenshotOcrToolExecutor?.execute(request)
                    ?: request.failed(
                        code = ToolErrorCode.ExecutionFailed,
                        summary = "图片 OCR 服务不可用",
                        retryable = true,
                        data = request.localOnlyData(),
                    )

            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT ->
                currentScreenTextToolExecutor?.execute(request)
                    ?: request.failed(
                        code = ToolErrorCode.ExecutionFailed,
                        summary = "当前屏幕文本服务不可用",
                        retryable = true,
                        data = request.localOnlyData(),
                    )

            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR ->
                currentScreenshotOcrToolExecutor.execute(request)

            else -> delegate.execute(request)
        }
    }

    private fun ToolRequest.isDeviceControlTool(): Boolean =
        toolRegistry.specFor(toolName)?.capability == ToolCapability.DeviceControl
}

class WebSearchToolExecutor(
    private val provider: WebSearchProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.WEB_SEARCH) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }
        val query = request.arguments["query"].orEmpty().trim()
        if (query.isBlank()) {
            return request.failed(
                code = ToolErrorCode.InvalidRequest,
                summary = "搜索词不能为空",
                retryable = false,
                data = mapOf("toolName" to request.toolName),
            )
        }
        val searchMode = WebSearchMode.fromSchemaValue(request.arguments["searchMode"])
            ?: return request.failed(
                code = ToolErrorCode.InvalidRequest,
                summary = "不支持的搜索模式：${request.arguments["searchMode"]}",
                retryable = false,
                data = mapOf("toolName" to request.toolName),
            )
        val freshnessArgument = request.arguments["freshness"]
        val freshness = WebSearchFreshness.fromSchemaValue(freshnessArgument)
            ?: if (freshnessArgument.isNullOrBlank()) {
                inferWebSearchFreshness(
                    query = query,
                    searchMode = searchMode,
                )
            } else {
                return request.failed(
                    code = ToolErrorCode.InvalidRequest,
                    summary = "不支持的搜索时效：$freshnessArgument",
                    retryable = false,
                    data = mapOf("toolName" to request.toolName),
                )
            }
        val maxResultsArgument = request.arguments["maxResults"]
        val maxResults = maxResultsArgument?.toIntOrNull()
            ?: if (maxResultsArgument.isNullOrBlank()) {
                3
            } else {
                return request.failed(
                    code = ToolErrorCode.InvalidRequest,
                    summary = "搜索结果数量必须是整数：$maxResultsArgument",
                    retryable = false,
                    data = mapOf("toolName" to request.toolName),
                )
            }
        if (maxResults !in 1..5) {
            return request.failed(
                code = ToolErrorCode.InvalidRequest,
                summary = "搜索结果数量必须在 1 到 5 之间：$maxResults",
                retryable = false,
                data = mapOf("toolName" to request.toolName),
            )
        }
        val searchRequest = WebSearchRequest(
            query = query,
            searchMode = searchMode,
            freshness = freshness,
            maxResults = maxResults,
        )
        return when (val result = provider.search(searchRequest)) {
            is WebSearchReadResult.Available ->
                request.succeeded(
                    summary = "已完成 Web 搜索：${result.summaryText}",
                    data = mapOf(
                        "toolName" to request.toolName,
                        "privacy" to MessagePrivacy.RemoteEligible.name,
                        "requiresLocalModel" to false.toString(),
                        "query" to result.query,
                        "source" to result.source,
                        "searchMode" to result.searchMode.schemaValue,
                        "retrievedAt" to result.retrievedAt.toString(),
                        "freshness" to result.freshness.schemaValue,
                        "maxResults" to result.maxResults.toString(),
                        "summaryText" to result.summaryText,
                        "resultsJson" to result.resultsJson,
                    ),
                )

            is WebSearchReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = result.reason,
                    retryable = true,
                    data = mapOf("toolName" to request.toolName),
                )
        }
    }
}

class ValidatingToolExecutor(
    private val delegate: ToolExecutor,
    private val registry: ToolRegistry = ToolRegistry(),
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        registry.validate(request)?.let { rejection -> return rejection }
        val result = runCatching {
            delegate.execute(request)
        }.getOrElse { throwable ->
            request.failed(
                code = ToolErrorCode.ExecutionFailed,
                summary = "Tool execution failed before completion: ${throwable.cleanMessage()}",
                retryable = true,
                data = request.toolExecutionContext(),
            )
        }.withToolExecutionContext(request.toolName)
        return registry.validateResult(request, result) ?: result
    }
}

class CalendarAvailabilityToolExecutor(
    private val provider: CalendarAvailabilityProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        return when (
            val validation = CalendarAvailabilityQuery.parseWindow(
                startIso = request.arguments["start"],
                endIso = request.arguments["end"],
            )
        ) {
            is CalendarAvailabilityQueryValidation.Invalid ->
                request.failed(
                    code = ToolErrorCode.InvalidRequest,
                    summary = validation.reason,
                    retryable = false,
                    data = request.localOnlyData(),
                )

            is CalendarAvailabilityQueryValidation.Valid ->
                executeValidated(request, validation)
        }
    }

    private fun executeValidated(
        request: ToolRequest,
        validation: CalendarAvailabilityQueryValidation.Valid,
    ): ToolResult =
        when (val result = provider.queryAvailability(validation.window)) {
            is CalendarAvailabilityReadResult.Available ->
                request.succeeded(
                    summary = "已查询日历忙闲：${result.snapshot.busyBlockCount} 个忙碌时段，" +
                        "${result.snapshot.freeBlockCount} 个空闲时段（仅包含时间区间）。",
                    data = request.localOnlyData() + mapOf(
                        "start" to CalendarAvailabilityQuery.formatInstant(result.snapshot.window.start),
                        "end" to CalendarAvailabilityQuery.formatInstant(result.snapshot.window.end),
                        "busyBlockCount" to result.snapshot.busyBlockCount.toString(),
                        "freeBlockCount" to result.snapshot.freeBlockCount.toString(),
                        "blocksJson" to result.snapshot.blocks.toJsonString(),
                    ),
                )

            CalendarAvailabilityReadResult.MissingPermission ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "需要日历读取权限才能查询忙闲区间",
                    retryable = true,
                    data = request.localOnlyData(),
                )

            is CalendarAvailabilityReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "日历忙闲查询失败：${result.reason}",
                    retryable = true,
                    data = request.localOnlyData(),
                )
        }
}

class ForegroundAppToolExecutor(
    private val provider: ForegroundAppProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.QUERY_FOREGROUND_APP) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        return when (val result = provider.currentForegroundApp()) {
            is ForegroundAppReadResult.Available ->
                request.succeeded(
                    summary = "当前前台应用估计：${result.appInfo.appLabel}",
                    data = request.localOnlyData() + mapOf(
                        "source" to result.appInfo.source,
                        "confidence" to result.appInfo.confidence,
                        "packageName" to result.appInfo.packageName,
                        "appLabel" to result.appInfo.appLabel,
                        "lastTimeUsedMillis" to result.appInfo.lastTimeUsedMillis.toString(),
                    ),
                )

            is ForegroundAppReadResult.PermissionDenied ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "需要“查看应用使用情况”权限来查询前台应用",
                    retryable = true,
                    data = request.localOnlyData() + mapOf(
                        "specialAccess" to "usage_stats",
                        "settingsAction" to Settings.ACTION_USAGE_ACCESS_SETTINGS,
                        "recoveryToolName" to MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
                    ),
                )

            is ForegroundAppReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "查询前台应用失败：${result.reason}",
                    retryable = true,
                    data = request.localOnlyData(),
                )
        }
    }
}

class ContactSummaryToolExecutor(
    private val provider: ContactSummaryProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.QUERY_CONTACTS) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        val query = request.arguments["query"]?.trim().orEmpty()
        val maxCount = (request.arguments["maxCount"]?.trim()?.toIntOrNull() ?: 5)
            .coerceIn(1, MAX_CONTACT_SUMMARY_COUNT)
        return when (val result = provider.queryContacts(query, maxCount)) {
            is ContactSummaryReadResult.Available ->
                request.succeeded(
                    summary = "已查询到 ${result.items.size} 个联系人。",
                    data = request.localOnlyData() + mapOf(
                        "query" to query,
                        "maxCount" to maxCount.toString(),
                        "contactCount" to result.items.size.toString(),
                        "contactsJson" to result.items.toContactsJsonString(),
                    ),
                )

            is ContactSummaryReadResult.PermissionDenied ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "未授权“读取联系人”权限，无法查询联系人",
                    retryable = true,
                    data = request.localOnlyData(),
                )

            is ContactSummaryReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "联系人查询失败：${result.reason}",
                    retryable = true,
                    data = request.localOnlyData(),
                )
        }
    }
}

class NotificationSummaryToolExecutor(
    private val provider: NotificationSummaryProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        val maxCount = (request.arguments["maxCount"]?.trim()?.toIntOrNull() ?: 5)
            .coerceIn(1, MAX_NOTIFICATION_SUMMARY_COUNT)
        return when (val result = provider.recentNotifications(maxCount)) {
            is NotificationSummaryReadResult.Available ->
                request.succeeded(
                    summary = "已读取 ${result.items.size} 条最近通知。",
                    data = request.localOnlyData() + mapOf(
                        "maxCount" to maxCount.toString(),
                        "notificationCount" to result.items.size.toString(),
                        "notificationsJson" to result.items.toJsonString(),
                    ),
                )

            is NotificationSummaryReadResult.PermissionDenied ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "未开启应用通知权限，无法读取通知摘要",
                    retryable = true,
                    data = request.localOnlyData(),
                )

            is NotificationSummaryReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "通知摘要查询失败：${result.reason}",
                    retryable = true,
                    data = request.localOnlyData(),
                )
        }
    }

    private fun List<NotificationSummaryItem>.toJsonString(): String {
        val notificationsArray = JSONArray()
        forEach { item ->
            notificationsArray.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("isOngoing", item.isOngoing)
                    .put("postTimeMillis", item.postTimeMillis),
            )
        }
        return notificationsArray.toString()
    }
}

class RecentFilesToolExecutor(
    private val provider: RecentFileProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.QUERY_RECENT_FILES) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        val kind = request.arguments["kind"]?.trim().orEmpty().ifBlank { "all" }
        val maxCount = request.arguments["maxCount"]?.trim()?.toIntOrNull() ?: 5
        return when (val result = provider.recentFiles(kind, maxCount)) {
            is RecentFileReadResult.Available ->
                request.succeeded(
                    summary = "已读取 ${result.items.size} 个最近文件。",
                    data = request.localOnlyData() + mapOf(
                        "kind" to kind.ifBlank { "all" },
                        "maxCount" to maxCount.toString(),
                        "mediaAccessScope" to result.mediaAccessScope,
                        "fileCount" to result.items.size.toString(),
                        "filesJson" to result.items.toRecentFilesJsonString(),
                    ),
                )

            is RecentFileReadResult.PermissionDenied ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "无法读取最近文件：${result.reason}",
                    retryable = result.retryable,
                    data = request.localOnlyData(),
                )

            is RecentFileReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "最近文件查询失败：${result.reason}",
                    retryable = true,
                    data = request.localOnlyData(),
                )
        }
    }
}

class BackgroundTasksToolExecutor(
    private val scheduler: BackgroundTaskScheduler,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.QUERY_BACKGROUND_TASKS) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        val scope = request.arguments["scope"]?.trim()?.lowercase().orEmpty().ifBlank { "active" }
        val maxCount = (request.arguments["maxCount"]?.trim()?.toIntOrNull() ?: DEFAULT_BACKGROUND_TASK_QUERY_COUNT)
            .coerceIn(1, MAX_BACKGROUND_TASK_QUERY_COUNT)

        val activeTasks = if (scope == "active" || scope == "all") {
            scheduler.scheduledTasks(maxCount)
        } else {
            emptyList()
        }
        val historyTasks = if (scope == "history" || scope == "all") {
            scheduler.recentTasks(maxCount)
        } else {
            emptyList()
        }
        val policy = if (scope == "policy" || scope == "all") {
            scheduler.periodicCheckPolicy()
        } else {
            null
        }

        val includedTasks = JSONArray().apply {
            activeTasks.appendToBackgroundTasksJson(scope = "active", target = this)
            historyTasks.appendToBackgroundTasksJson(scope = "history", target = this)
        }
        val summary = when (scope) {
            "history" -> "已读取 ${historyTasks.size} 条后台任务历史元数据。"
            "policy" -> "已读取本地提醒周期检查策略。"
            "all" -> "已读取 ${activeTasks.size} 个活动后台任务元数据、${historyTasks.size} 条历史元数据与周期检查策略。"
            else -> "已读取 ${activeTasks.size} 个活动后台任务元数据。"
        }
        return request.succeeded(
            summary = summary,
            data = buildMap {
                putAll(request.localOnlyData())
                put("scope", scope)
                put("source", "local_store")
                put("maxCount", maxCount.toString())
                put("metadataPolicy", BACKGROUND_TASK_METADATA_POLICY)
                put("rawPayloadIncluded", false.toString())
                if (scope == "active" || scope == "all") {
                    put("activeTaskCount", activeTasks.size.toString())
                }
                if (scope == "history" || scope == "all") {
                    put("historyTaskCount", historyTasks.size.toString())
                }
                if (includedTasks.length() > 0 || scope != "policy") {
                    put("tasksJson", includedTasks.toString())
                }
                policy?.let { put("policyJson", it.toJsonString()) }
            },
        )
    }
}

class RecentScreenshotOcrToolExecutor(
    private val provider: RecentImageTextProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        val config = recentImageOcrToolConfigFor(request.toolName) ?: return request.failed(
            code = ToolErrorCode.UnknownTool,
            summary = "Unknown tool: ${request.toolName}",
            retryable = false,
        )

        val maxCount = (request.arguments["maxCount"]?.trim()?.toIntOrNull() ?: config.defaultMaxCount)
            .coerceIn(1, config.maxMaxCount)
        return when (val result = provider.extractRecentImageText(kind = config.kind, maxCount = maxCount)) {
            is RecentImageTextReadResult.Available -> {
                val item = result.item
                if (item == null) {
                    request.succeeded(
                        summary = "未能在最近 ${result.scannedCount} 张${config.sourceLabel}中识别出文字。",
                        data = request.localOnlyData() + mapOf(
                            "source" to config.kind,
                            "maxCount" to maxCount.toString(),
                            "scannedCount" to result.scannedCount.toString(),
                            "mediaAccessScope" to result.mediaAccessScope,
                            "ocrTextIncluded" to false.toString(),
                            "rawPayloadIncluded" to false.toString(),
                            "metadataPolicy" to "no_uri_path_or_pixels_persisted",
                        ),
                    )
                } else {
                    request.succeeded(
                        summary = "已从最近${config.sourceLabel}提取 ${item.text.length} 个字符的本地 OCR 摘录。",
                        data = request.localOnlyData() + mapOf(
                            "source" to config.kind,
                            "maxCount" to maxCount.toString(),
                            "scannedCount" to result.scannedCount.toString(),
                            "mediaAccessScope" to result.mediaAccessScope,
                            "ocrText" to item.text,
                            "truncated" to item.truncated.toString(),
                            "ocrTextIncluded" to true.toString(),
                            "rawPayloadIncluded" to false.toString(),
                            "metadataPolicy" to "ocr_text_local_only_no_uri_path_or_pixels_persisted",
                        ),
                    )
                }
            }

            is RecentImageTextReadResult.PermissionDenied ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "未授权“读取图片”权限，无法识别最近${config.sourceLabel}文字",
                    retryable = true,
                    data = request.localOnlyData(),
                )

            is RecentImageTextReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "最近${config.sourceLabel} OCR 失败",
                    retryable = true,
                    data = request.localOnlyData(),
                )
        }
    }

    private data class RecentImageOcrToolConfig(
        val kind: String,
        val sourceLabel: String,
        val defaultMaxCount: Int,
        val maxMaxCount: Int,
    )

    private fun recentImageOcrToolConfigFor(toolName: String): RecentImageOcrToolConfig? =
        when (toolName) {
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR -> RecentImageOcrToolConfig(
                kind = "screenshots",
                sourceLabel = "截图",
                defaultMaxCount = 1,
                maxMaxCount = 1,
            )

            MobileActionFunctions.READ_RECENT_IMAGE_OCR -> RecentImageOcrToolConfig(
                kind = "images",
                sourceLabel = "图片",
                defaultMaxCount = 3,
                maxMaxCount = 3,
            )

            else -> null
        }
}

class CurrentScreenTextToolExecutor(
    private val provider: CurrentScreenTextProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.READ_CURRENT_SCREEN_TEXT) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        val maxChars = (request.arguments["maxChars"]?.trim()?.toIntOrNull() ?: DEFAULT_MAX_SCREEN_TEXT_CHARS)
            .coerceIn(1, MAX_SCREEN_TEXT_CHARS)
        return when (val result = provider.currentScreenText(maxChars)) {
            is CurrentScreenTextReadResult.Available -> {
                val snapshot = result.snapshot
                if (snapshot.text.isBlank()) {
                    request.succeeded(
                        summary = "当前屏幕未暴露可访问文本。",
                        data = request.localOnlyData() + mapOf(
                            "source" to "accessibility_active_window",
                            "maxChars" to maxChars.toString(),
                            "capturedAtMillis" to snapshot.capturedAtMillis.toString(),
                            "nodeCount" to snapshot.nodeCount.toString(),
                            "truncated" to snapshot.truncated.toString(),
                            "screenTextIncluded" to false.toString(),
                            "rawTreeIncluded" to false.toString(),
                            "metadataPolicy" to "accessibility_text_local_only_no_node_ids_bounds_or_hierarchy_persisted",
                        ) + snapshot.packageNameData() + snapshot.structureSummaryData(),
                    )
                } else {
                    request.succeeded(
                        summary = "已读取当前屏幕 ${snapshot.text.length} 个字符的可访问文本快照。",
                        data = request.localOnlyData() + mapOf(
                            "source" to "accessibility_active_window",
                            "maxChars" to maxChars.toString(),
                            "capturedAtMillis" to snapshot.capturedAtMillis.toString(),
                            "nodeCount" to snapshot.nodeCount.toString(),
                            "screenText" to snapshot.text,
                            "truncated" to snapshot.truncated.toString(),
                            "screenTextIncluded" to true.toString(),
                            "rawTreeIncluded" to false.toString(),
                            "metadataPolicy" to "accessibility_text_local_only_no_node_ids_bounds_or_hierarchy_persisted",
                        ) + snapshot.packageNameData() + snapshot.structureSummaryData(),
                    )
                }
            }

            is CurrentScreenTextReadResult.PermissionDenied ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "需要开启 PocketMind 无障碍服务才能读取当前屏幕文本",
                    retryable = true,
                    data = request.localOnlyData() + mapOf(
                        "specialAccess" to "accessibility_screen_text",
                        "settingsAction" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
                    ),
                )

            is CurrentScreenTextReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "当前屏幕文本读取失败",
                    retryable = true,
                    data = request.localOnlyData(),
                )
        }
    }

    private fun com.bytedance.zgx.pocketmind.device.CurrentScreenTextSnapshot.packageNameData(): Map<String, String> =
        packageName?.takeIf { it.isNotBlank() }?.let { mapOf("packageName" to it) }.orEmpty()

    private fun com.bytedance.zgx.pocketmind.device.CurrentScreenTextSnapshot.structureSummaryData(): Map<String, String> {
        val included = structureSummaryIncluded && structureSummary.isNotBlank()
        return mapOf("structureSummaryIncluded" to included.toString()) +
            structureSummary.takeIf { included }?.let { mapOf("structureSummary" to it) }.orEmpty()
    }

    private companion object {
        const val DEFAULT_MAX_SCREEN_TEXT_CHARS = 2_000
        const val MAX_SCREEN_TEXT_CHARS = 4_000
    }
}

class DeviceControlToolExecutor(
    private val provider: CurrentScreenControlProvider?,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        val controlProvider = provider
            ?: return request.failed(
                code = ToolErrorCode.ExecutionFailed,
                summary = "当前屏幕控制服务不可用",
                retryable = true,
                data = request.deviceControlBaseData(),
            )
        return when (request.toolName) {
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN -> executeObserve(request, controlProvider)
            MobileActionFunctions.UI_TAP -> executeTap(request, controlProvider)
            MobileActionFunctions.UI_TYPE_TEXT -> executeTypeText(request, controlProvider)
            MobileActionFunctions.UI_SUBMIT_SEARCH -> executeSubmitSearch(request, controlProvider)
            MobileActionFunctions.UI_SCROLL -> executeScroll(request, controlProvider)
            MobileActionFunctions.UI_PRESS_BACK -> executePressBack(request, controlProvider)
            MobileActionFunctions.UI_WAIT -> executeWait(request, controlProvider)
            else -> request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }
    }

    private fun executeObserve(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        val maxTextChars = request.arguments["maxTextChars"]?.toIntOrNull() ?: DEFAULT_MAX_SCREEN_STATE_TEXT_CHARS
        val maxNodes = request.arguments["maxNodes"]?.toIntOrNull() ?: DEFAULT_MAX_SCREEN_STATE_NODES
        return when (val result = provider.observeCurrentScreen(maxTextChars = maxTextChars, maxNodes = maxNodes)) {
            is ScreenStateReadResult.Available ->
                request.succeeded(
                    summary = result.snapshot.observationSummary(),
                    data = request.deviceControlBaseData() + result.snapshot.toObservationData(
                        requestedMaxTextChars = maxTextChars,
                        requestedMaxNodes = maxNodes,
                    ),
                )

            is ScreenStateReadResult.PermissionDenied ->
                request.deviceControlPermissionDenied(result.reason)

            is ScreenStateReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = result.reason,
                    retryable = true,
                    data = request.deviceControlBaseData() + mapOf(
                        "failureKind" to result.failureKind.schemaValue,
                    ),
                )
        }
    }

    private fun executeTap(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult =
        actionResult(
            request = request,
            actionType = "tap",
            target = request.arguments["target"].orEmpty(),
            result = provider.tap(
                target = request.arguments["target"].orEmpty(),
                timeoutMillis = request.timeoutMillis(),
            ),
        )

    private fun executeTypeText(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult =
        actionResult(
            request = request,
            actionType = "type_text",
            target = request.arguments["target"].orEmpty(),
            result = provider.typeText(
                text = request.arguments["text"].orEmpty(),
                target = request.arguments["target"],
                timeoutMillis = request.timeoutMillis(),
            ),
        )

    private fun executeSubmitSearch(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult =
        actionResult(
            request = request,
            actionType = "submit_search",
            target = "",
            result = provider.submitSearch(
                timeoutMillis = request.timeoutMillis(),
            ),
        )

    private fun executeScroll(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        val direction = UiScrollDirection.fromSchemaValue(request.arguments["direction"])
            ?: return request.failed(
                code = ToolErrorCode.InvalidRequest,
                summary = "不支持的滚动方向：${request.arguments["direction"]}",
                retryable = false,
                data = request.deviceControlBaseData(),
            )
        return actionResult(
            request = request,
            actionType = "scroll",
            target = request.arguments["target"].orEmpty(),
            result = provider.scroll(
                direction = direction,
                target = request.arguments["target"],
                timeoutMillis = request.timeoutMillis(),
            ),
            extraData = mapOf("direction" to direction.schemaValue),
        )
    }

    private fun executePressBack(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult =
        actionResult(
            request = request,
            actionType = "press_back",
            target = "",
            result = provider.pressBack(timeoutMillis = request.timeoutMillis()),
        )

    private fun executeWait(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        val result = provider.waitForScreen(timeoutMillis = request.timeoutMillis())
        val verification = result.searchVerificationFor(request)
        return actionResult(
            request = request,
            actionType = "wait",
            target = "",
            result = result.withSearchVerification(verification),
            extraData = verification.toData(),
        )
    }

    private fun actionResult(
        request: ToolRequest,
        actionType: String,
        target: String,
        result: UiActionReadResult,
        extraData: Map<String, String> = emptyMap(),
    ): ToolResult =
        when (result) {
            is UiActionReadResult.Available -> {
                val execution = result.result
                val after = execution.after
                val data = request.deviceControlBaseData() +
                    mapOf(
                        "actionType" to actionType,
                        "status" to execution.status.schemaValue(),
                        "retryable" to execution.retryable.toString(),
                        "summary" to execution.summary,
                        "beforeObservationId" to execution.before?.id.orEmpty(),
                        "afterObservationId" to after?.id.orEmpty(),
                        "verificationSummary" to (after?.observationSummary() ?: "动作后未能读取屏幕状态"),
                    ) +
                    target.takeIf { it.isNotBlank() }?.let { mapOf("target" to it) }.orEmpty() +
                    execution.failureKind?.let { mapOf("failureKind" to it.schemaValue) }.orEmpty() +
                    after?.toAfterObservationData().orEmpty() +
                    extraData
                if (execution.status == UiActionStatus.Succeeded) {
                    request.succeeded(
                        summary = execution.summary,
                        data = data,
                    )
                } else {
                    request.failed(
                        code = ToolErrorCode.ExecutionFailed,
                        summary = execution.summary,
                        retryable = execution.retryable,
                        data = data,
                    )
                }
            }

            is UiActionReadResult.PermissionDenied ->
                request.deviceControlPermissionDenied(result.reason)

            is UiActionReadResult.Failed ->
                request.failed(
                    code = if (result.failureKind == UiActionFailureKind.PermissionMissing) {
                        ToolErrorCode.PermissionDenied
                    } else {
                        ToolErrorCode.ExecutionFailed
                    },
                    summary = result.reason,
                    retryable = result.retryable,
                    data = request.deviceControlBaseData() + mapOf(
                        "actionType" to actionType,
                        "status" to UiActionStatus.Failed.schemaValue(),
                        "retryable" to result.retryable.toString(),
                        "summary" to result.reason,
                        "failureKind" to result.failureKind.schemaValue,
                    ),
                )
        }

    private fun ToolRequest.timeoutMillis(): Long =
        arguments["timeoutMillis"]?.trim()?.toLongOrNull() ?: DEFAULT_UI_ACTION_TIMEOUT_MILLIS

    private fun UiActionReadResult.searchVerificationFor(request: ToolRequest): SearchResultVerification? {
        val query = request.arguments["verifySearchQuery"]?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val execution = (this as? UiActionReadResult.Available)?.result ?: return null
        return AppSearchResultVerifier.verify(
            before = execution.before,
            after = execution.after,
            query = query,
            expectedPackageName = request.arguments["expectedPackageName"],
            expectedAppName = request.arguments["expectedAppName"],
        )
    }

    private fun UiActionReadResult.withSearchVerification(
        verification: SearchResultVerification?,
    ): UiActionReadResult {
        verification ?: return this
        if (this !is UiActionReadResult.Available) return this
        if (result.status != UiActionStatus.Succeeded) return this
        if (verification.verified) {
            return copy(
                result = result.copy(
                    summary = verification.summary,
                ),
            )
        }
        return copy(
            result = result.copy(
                status = UiActionStatus.Failed,
                summary = verification.summary,
                retryable = true,
                failureKind = verification.failureKind ?: UiActionFailureKind.ResultNotVerified,
            ),
        )
    }

    private fun SearchResultVerification?.toData(): Map<String, String> =
        this?.let { verification ->
            mapOf(
                "searchVerificationStatus" to if (verification.verified) "verified" else "not_verified",
                "searchVerificationEvidence" to verification.evidence,
            )
        }.orEmpty()

    private fun ToolRequest.deviceControlPermissionDenied(reason: String): ToolResult =
        failed(
            code = ToolErrorCode.PermissionDenied,
            summary = reason.ifBlank { "需要开启 PocketMind 无障碍服务才能控制当前屏幕" },
            retryable = true,
            data = deviceControlBaseData() + mapOf(
                "specialAccess" to SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL,
                "settingsAction" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
                "failureKind" to UiActionFailureKind.PermissionMissing.schemaValue,
            ),
        )

    private fun ToolRequest.deviceControlBaseData(): Map<String, String> =
        localOnlyData() + mapOf(
            "source" to DEVICE_CONTROL_SOURCE_ACCESSIBILITY,
            "metadataPolicy" to DEVICE_CONTROL_METADATA_POLICY,
        )

    private fun ScreenStateSnapshot.toObservationData(
        requestedMaxTextChars: Int,
        requestedMaxNodes: Int,
    ): Map<String, String> =
        mapOf(
            "observationId" to id,
            "capturedAtMillis" to capturedAtMillis.toString(),
            "nodeCount" to nodeCount.toString(),
            "actionableNodeCount" to actionableNodeCount.toString(),
            "textSummary" to textSummary,
            "truncated" to truncated.toString(),
            "nodesJson" to nodes.toScreenNodesJsonString(),
            "maxTextChars" to requestedMaxTextChars.toString(),
            "maxNodes" to requestedMaxNodes.toString(),
        ) + packageName?.takeIf { it.isNotBlank() }?.let { mapOf("packageName" to it) }.orEmpty()

    private fun ScreenStateSnapshot.toAfterObservationData(): Map<String, String> =
        mapOf(
            "afterPackageName" to packageName.orEmpty(),
            "afterCapturedAtMillis" to capturedAtMillis.toString(),
            "afterNodeCount" to nodeCount.toString(),
            "afterActionableNodeCount" to actionableNodeCount.toString(),
            "afterTextSummary" to textSummary,
            "afterTruncated" to truncated.toString(),
            "afterNodesJson" to nodes.toScreenNodesJsonString(),
        )

    private fun ScreenStateSnapshot.observationSummary(): String {
        val packagePart = packageName?.takeIf { it.isNotBlank() }?.let { "包名 $it，" }.orEmpty()
        return "已观察当前屏幕：${packagePart}${nodeCount} 个节点，${actionableNodeCount} 个可交互节点。"
    }

    private fun UiActionStatus.schemaValue(): String =
        when (this) {
            UiActionStatus.Succeeded -> "succeeded"
            UiActionStatus.Failed -> "failed"
        }

    private companion object {
        const val DEFAULT_MAX_SCREEN_STATE_TEXT_CHARS = 2_000
        const val DEFAULT_MAX_SCREEN_STATE_NODES = 50
        const val DEFAULT_UI_ACTION_TIMEOUT_MILLIS = 1_000L
    }
}

class CurrentScreenshotOcrToolExecutor(
    private val provider: CurrentScreenshotOcrProvider?,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        return when (val result = provider?.captureCurrentScreenshotOcr(request.id)) {
            null ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "当前屏幕截图 OCR 服务不可用",
                    retryable = true,
                    data = request.localOnlyData(),
                )

            CurrentScreenshotOcrReadResult.MissingConsent ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "需要完成 Android MediaProjection 前台同意后，才能单次截取当前屏幕并本地提取 OCR 文本。",
                    retryable = false,
                    data = request.localOnlyData() + mapOf(
                        "specialAccess" to CurrentScreenshotOcrContract.CONSENT_REASON,
                    ),
                )

            is CurrentScreenshotOcrReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = result.reason,
                    retryable = true,
                    data = request.currentScreenshotOcrBaseData(),
                )

            is CurrentScreenshotOcrReadResult.Available -> {
                val ocrText = result.text?.takeIf { it.isNotBlank() }
                request.succeeded(
                    summary = if (ocrText == null) {
                        "已完成当前屏幕单次 OCR，未识别到可用文字。"
                    } else {
                        "已从当前屏幕单次截图提取 ${ocrText.length} 个字符的本地 OCR 摘录。"
                    },
                    data = request.currentScreenshotOcrBaseData() +
                        mapOf(
                            "truncated" to result.truncated.toString(),
                            "ocrTextIncluded" to (ocrText != null).toString(),
                        ) +
                        ocrText?.let { mapOf("ocrText" to it) }.orEmpty(),
                )
            }
        }
    }
}

private fun ToolRequest.currentScreenshotOcrBaseData(): Map<String, String> =
    localOnlyData() + mapOf(
        "source" to CurrentScreenshotOcrContract.SOURCE,
        "captureMode" to CurrentScreenshotOcrContract.CAPTURE_MODE,
        "metadataPolicy" to CurrentScreenshotOcrContract.OUTPUT_METADATA_POLICY,
        "rawPayloadIncluded" to false.toString(),
    )

private fun List<ContactSummaryItem>.toContactsJsonString(): String {
    val contactsArray = JSONArray()
    forEach { item ->
        contactsArray.put(
            JSONObject()
                .put("name", item.name)
                .put("phone", item.phone),
        )
    }
    return contactsArray.toString()
}

private fun List<RecentFileItem>.toRecentFilesJsonString(): String {
    val filesArray = JSONArray()
    forEach { item ->
        filesArray.put(
            JSONObject()
                .put("name", item.name)
                .put("mimeType", item.mimeType)
                .put("kind", item.kind)
                .put("sizeBytes", item.sizeBytes)
                .put("lastModifiedMillis", item.lastModifiedMillis),
        )
    }
    return filesArray.toString()
}

private fun List<ScreenNode>.toScreenNodesJsonString(): String {
    val nodesArray = JSONArray()
    forEach { node ->
        nodesArray.put(
            JSONObject()
                .put("id", node.id)
                .put("text", node.text)
                .put("contentDescription", node.contentDescription)
                .put("className", node.className)
                .put("bounds", node.bounds?.toJsonObject())
                .put("clickable", node.clickable)
                .put("editable", node.editable)
                .put("scrollable", node.scrollable)
                .put("enabled", node.enabled),
        )
    }
    return nodesArray.toString()
}

private fun ScreenBounds.toJsonObject(): JSONObject =
    JSONObject()
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)

private fun List<ScheduledTask>.appendToBackgroundTasksJson(
    scope: String,
    target: JSONArray,
) {
    forEach { task ->
        target.put(
            JSONObject()
                .put("scope", scope)
                .put("id", task.id)
                .put("type", task.type.name)
                .put("status", task.status.name)
                .put("triggerAtMillis", task.triggerAtMillis)
                .put("createdAtMillis", task.createdAtMillis)
                .put("updatedAtMillis", task.updatedAtMillis),
        )
    }
}

private fun PeriodicCheckPolicySummary.toJsonString(): String {
    val normalized = request.normalized()
    return JSONObject()
        .put("enabled", normalized.enabled)
        .put("intervalMinutes", normalized.intervalMinutes)
        .put("minNotificationSpacingMinutes", normalized.minNotificationSpacingMinutes)
        .put("overdueGraceMinutes", normalized.overdueGraceMinutes)
        .put("requiresBatteryNotLow", normalized.constraints.requiresBatteryNotLow)
        .put("requiresCharging", normalized.constraints.requiresCharging)
        .put("taskStatus", taskStatus?.name)
        .put("nextAllowedRunAtMillis", nextAllowedRunAtMillis)
        .put("updatedAtMillis", updatedAtMillis)
        .put("lastRunSummaryIncluded", false)
        .toString()
}

private fun ToolRequest.localOnlyData(): Map<String, String> =
    mapOf(
        "toolName" to toolName,
        "privacy" to MessagePrivacy.LocalOnly.name,
        "requiresLocalModel" to true.toString(),
    )

private fun List<com.bytedance.zgx.pocketmind.device.CalendarAvailabilityBlock>.toJsonString(): String {
    val blocksArray = JSONArray()
    forEach { block ->
        blocksArray.put(
            JSONObject()
                .put("status", block.status.wireValue)
                .put("start", CalendarAvailabilityQuery.formatInstant(block.start))
                .put("end", CalendarAvailabilityQuery.formatInstant(block.end)),
        )
    }
    return blocksArray.toString()
}

private fun ToolRequest.toolExecutionContext(): Map<String, String> =
    mapOf("toolName" to toolName)

private fun ToolResult.withToolExecutionContext(toolName: String): ToolResult {
    return if (data["toolName"] == toolName) this else copy(data = data + ("toolName" to toolName))
}

private fun Throwable.cleanMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
