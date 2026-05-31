package com.bytedance.zgx.pocketmind.tool

import android.provider.Settings
import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityProvider
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityQuery
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityQueryValidation
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityReadResult
import com.bytedance.zgx.pocketmind.device.ForegroundAppProvider
import com.bytedance.zgx.pocketmind.device.ForegroundAppReadResult
import com.bytedance.zgx.pocketmind.device.ContactSummaryItem
import com.bytedance.zgx.pocketmind.device.ContactSummaryProvider
import com.bytedance.zgx.pocketmind.device.ContactSummaryReadResult
import com.bytedance.zgx.pocketmind.device.NotificationSummaryItem
import com.bytedance.zgx.pocketmind.device.NotificationSummaryProvider
import com.bytedance.zgx.pocketmind.device.NotificationSummaryReadResult
import com.bytedance.zgx.pocketmind.device.RecentFileItem
import com.bytedance.zgx.pocketmind.device.RecentFileProvider
import com.bytedance.zgx.pocketmind.device.RecentFileReadResult
import com.bytedance.zgx.pocketmind.device.RecentImageTextProvider
import com.bytedance.zgx.pocketmind.device.RecentImageTextReadResult
import org.json.JSONArray
import org.json.JSONObject

interface ToolExecutor {
    fun execute(request: ToolRequest): ToolResult
}

class RoutingToolExecutor(
    private val calendarAvailabilityProvider: CalendarAvailabilityProvider,
    private val foregroundAppProvider: ForegroundAppProvider,
    private val contactSummaryProvider: ContactSummaryProvider,
    private val notificationSummaryProvider: NotificationSummaryProvider,
    private val recentFileProvider: RecentFileProvider,
    private val delegate: ToolExecutor,
    private val recentImageTextProvider: RecentImageTextProvider? = null,
) : ToolExecutor {
    private val calendarAvailabilityToolExecutor =
        CalendarAvailabilityToolExecutor(calendarAvailabilityProvider)
    private val foregroundAppToolExecutor = ForegroundAppToolExecutor(foregroundAppProvider)
    private val contactSummaryToolExecutor = ContactSummaryToolExecutor(contactSummaryProvider)
    private val notificationSummaryToolExecutor =
        NotificationSummaryToolExecutor(notificationSummaryProvider)
    private val recentFilesToolExecutor = RecentFilesToolExecutor(recentFileProvider)
    private val recentScreenshotOcrToolExecutor =
        recentImageTextProvider?.let(::RecentScreenshotOcrToolExecutor)

    override fun execute(request: ToolRequest): ToolResult =
        when (request.toolName) {
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
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR ->
                recentScreenshotOcrToolExecutor?.execute(request)
                    ?: request.failed(
                        code = ToolErrorCode.ExecutionFailed,
                        summary = "截图 OCR 服务不可用",
                        retryable = true,
                        data = request.localOnlyData(),
                    )

            else -> delegate.execute(request)
        }
}

class ValidatingToolExecutor(
    private val delegate: ToolExecutor,
    private val registry: ToolRegistry = ToolRegistry(),
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult =
        registry.validate(request)
            ?: runCatching {
                delegate.execute(request)
            }.getOrElse { throwable ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "Tool execution failed before completion: ${throwable.cleanMessage()}",
                    retryable = true,
                    data = request.toolExecutionContext(),
                )
            }.withToolExecutionContext(request.toolName)
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
                    summary = "当前前台应用：${result.appInfo.appLabel}",
                    data = request.localOnlyData() + mapOf(
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
        val maxCount = request.arguments["maxCount"]?.trim()?.toIntOrNull() ?: 5
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

        val maxCount = request.arguments["maxCount"]?.trim()?.toIntOrNull() ?: 5
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

class RecentScreenshotOcrToolExecutor(
    private val provider: RecentImageTextProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        val maxCount = (request.arguments["maxCount"]?.trim()?.toIntOrNull() ?: 1).coerceIn(1, 1)
        return when (val result = provider.extractRecentImageText(kind = "screenshots", maxCount = maxCount)) {
            is RecentImageTextReadResult.Available -> {
                val item = result.item
                if (item == null) {
                    request.succeeded(
                        summary = "未能在最近 ${result.scannedCount} 张截图中识别出文字。",
                        data = request.localOnlyData() + mapOf(
                            "source" to "screenshots",
                            "maxCount" to maxCount.toString(),
                            "scannedCount" to result.scannedCount.toString(),
                            "ocrTextIncluded" to false.toString(),
                            "rawPayloadIncluded" to false.toString(),
                            "metadataPolicy" to "no_uri_path_or_pixels_persisted",
                        ),
                    )
                } else {
                    request.succeeded(
                        summary = "已从最近截图提取 ${item.text.length} 个字符的本地 OCR 摘录。",
                        data = request.localOnlyData() + mapOf(
                            "source" to "screenshots",
                            "maxCount" to maxCount.toString(),
                            "scannedCount" to result.scannedCount.toString(),
                            "name" to item.name,
                            "mimeType" to item.mimeType,
                            "kind" to item.kind,
                            "sizeBytes" to item.sizeBytes.toString(),
                            "lastModifiedMillis" to item.lastModifiedMillis.toString(),
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
                    summary = "未授权“读取图片”权限，无法识别最近截图文字",
                    retryable = true,
                    data = request.localOnlyData(),
                )

            is RecentImageTextReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "最近截图 OCR 失败",
                    retryable = true,
                    data = request.localOnlyData(),
                )
        }
    }
}

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
