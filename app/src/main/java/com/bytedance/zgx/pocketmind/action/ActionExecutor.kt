package com.bytedance.zgx.pocketmind.action

import android.app.SearchManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import com.bytedance.zgx.pocketmind.background.BackgroundTaskScheduler
import com.bytedance.zgx.pocketmind.background.ReminderScheduleRequest
import com.bytedance.zgx.pocketmind.tool.ToolErrorCode
import com.bytedance.zgx.pocketmind.tool.ToolExecutor
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import com.bytedance.zgx.pocketmind.tool.failed
import com.bytedance.zgx.pocketmind.tool.succeeded

class ActionExecutor(
    private val context: Context?,
    private val backgroundTaskScheduler: BackgroundTaskScheduler? = null,
    private val canPostReminderNotifications: () -> Boolean = { true },
    private val clipboardTextProvider: (() -> String?)? = null,
    private val activityStarter: ((Intent) -> Boolean)? = null,
) : ToolExecutor {
    fun executeConfirmed(draft: ActionDraft): Boolean {
        val request = ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = draft.summary,
        )
        return execute(request).status == ToolStatus.Succeeded
    }

    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName == MobileActionFunctions.SCHEDULE_REMINDER) {
            return scheduleReminder(request)
        }
        if (request.toolName == MobileActionFunctions.READ_CLIPBOARD) {
            return readClipboard(request)
        }

        val intents = intentsFor(request)
            ?: return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        val executed = intents.any { intent ->
            startActivity(intent)
        }

        return if (executed) {
            request.succeeded(
                summary = successSummaryFor(request.toolName),
                data = mapOf("toolName" to request.toolName),
            )
        } else {
            request.failed(
                code = ToolErrorCode.NoActivityFound,
                summary = "没有找到可以执行 ${request.toolName} 的应用或系统页面",
                retryable = true,
                data = mapOf("toolName" to request.toolName),
            )
        }
    }

    private fun readClipboard(request: ToolRequest): ToolResult {
        val text = (clipboardTextProvider ?: ::readClipboardText).invoke()
            ?.trim()
            .orEmpty()
        if (text.isBlank()) {
            return request.failed(
                code = ToolErrorCode.ExecutionFailed,
                summary = "剪贴板没有可读取的文本",
                retryable = true,
                data = mapOf("toolName" to request.toolName),
            )
        }
        val clipped = text.take(MAX_CLIPBOARD_RESULT_CHARS)
        return request.succeeded(
            summary = "已读取剪贴板文本（${text.length} 字符）",
            data = mapOf(
                "toolName" to request.toolName,
                "text" to clipped,
                "truncated" to (text.length > clipped.length).toString(),
            ),
        )
    }

    private fun scheduleReminder(request: ToolRequest): ToolResult {
        if (!canPostReminderNotifications()) {
            return request.failed(
                code = ToolErrorCode.PermissionDenied,
                summary = "需要通知权限才能安排后台提醒",
                retryable = true,
                data = mapOf("toolName" to request.toolName),
            )
        }
        val scheduler = backgroundTaskScheduler
            ?: return request.failed(
                code = ToolErrorCode.ExecutionFailed,
                summary = "后台任务调度器不可用",
                retryable = true,
            )
        val title = request.arguments["title"].orEmpty()
        val body = request.arguments["body"].orEmpty()
        val delayMinutes = request.arguments["delayMinutes"]?.toLongOrNull()
            ?: return request.failed(
                code = ToolErrorCode.InvalidRequest,
                summary = "提醒时间参数无效",
                retryable = false,
            )
        return scheduler.scheduleReminder(
            ReminderScheduleRequest(
                title = title,
                body = body,
                delayMinutes = delayMinutes,
            ),
        ).fold(
            onSuccess = { task ->
                request.succeeded(
                    summary = "已安排 ${task.triggerAtMillis} 触发的后台提醒",
                    data = mapOf(
                        "toolName" to request.toolName,
                        "taskId" to task.id,
                        "triggerAtMillis" to task.triggerAtMillis.toString(),
                    ),
                )
            },
            onFailure = { throwable ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "后台提醒安排失败：${throwable.cleanMessage()}",
                    retryable = true,
                    data = mapOf("toolName" to request.toolName),
                )
            },
        )
    }

    private fun intentsFor(request: ToolRequest): List<Intent>? =
        when (request.toolName) {
            MobileActionFunctions.OPEN_WIFI_SETTINGS ->
                listOf(Intent(Settings.ACTION_WIFI_SETTINGS))

            MobileActionFunctions.SEARCH_MAPS ->
                listOf(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=${Uri.encode(request.arguments["query"].orEmpty())}"),
                    ),
                )

            MobileActionFunctions.WEB_SEARCH ->
                webSearchIntents(request.arguments["query"].orEmpty())

            MobileActionFunctions.COMPOSE_EMAIL ->
                listOf(
                    Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:")
                        putExtra(Intent.EXTRA_SUBJECT, request.arguments["subject"].orEmpty())
                        putExtra(Intent.EXTRA_TEXT, request.arguments["body"].orEmpty())
                    },
                )

            MobileActionFunctions.CREATE_CALENDAR_EVENT ->
                listOf(
                    Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        putExtra(CalendarContract.Events.TITLE, request.arguments["title"].orEmpty())
                        putExtra(CalendarContract.Events.DESCRIPTION, request.arguments["description"].orEmpty())
                    },
                )

            MobileActionFunctions.CREATE_CONTACT_DRAFT ->
                listOf(
                    Intent(ContactsContract.Intents.Insert.ACTION).apply {
                        type = ContactsContract.RawContacts.CONTENT_TYPE
                        putExtra(ContactsContract.Intents.Insert.NAME, request.arguments["name"].orEmpty())
                        putExtra(ContactsContract.Intents.Insert.EMAIL, request.arguments["email"].orEmpty())
                        putExtra(ContactsContract.Intents.Insert.PHONE, request.arguments["phone"].orEmpty())
                    },
                )

            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS ->
                listOf(Intent(Settings.ACTION_SETTINGS))

            MobileActionFunctions.SCHEDULE_REMINDER ->
                null

            MobileActionFunctions.READ_CLIPBOARD ->
                null

            MobileActionFunctions.SHARE_TEXT ->
                listOf(shareTextIntent(request))

            else -> null
        }

    private fun webSearchIntents(query: String): List<Intent> =
        listOf(
            Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
            },
            Intent(
                Intent.ACTION_VIEW,
                Uri.Builder()
                    .scheme("https")
                    .authority("www.google.com")
                    .path("search")
                    .appendQueryParameter("q", query)
                    .build(),
            ),
        )

    private fun successSummaryFor(toolName: String): String =
        when (toolName) {
            MobileActionFunctions.OPEN_WIFI_SETTINGS -> "已打开 Wi-Fi 设置页"
            MobileActionFunctions.SEARCH_MAPS -> "已打开地图搜索"
            MobileActionFunctions.WEB_SEARCH -> "已打开网页搜索"
            MobileActionFunctions.COMPOSE_EMAIL -> "已打开邮件草稿页"
            MobileActionFunctions.CREATE_CALENDAR_EVENT -> "已打开日历新建事件页"
            MobileActionFunctions.CREATE_CONTACT_DRAFT -> "已打开联系人草稿页"
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS -> "已打开系统设置页"
            MobileActionFunctions.SCHEDULE_REMINDER -> "已安排后台提醒"
            MobileActionFunctions.READ_CLIPBOARD -> "已读取剪贴板"
            MobileActionFunctions.SHARE_TEXT -> "已打开系统分享面板"
            else -> "工具已执行"
        }

    private fun Throwable.cleanMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    private fun shareTextIntent(request: ToolRequest): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, request.arguments["text"].orEmpty())
            request.arguments["title"]?.takeIf { it.isNotBlank() }?.let { title ->
                putExtra(Intent.EXTRA_TITLE, title)
            }
        }
        return Intent.createChooser(
            sendIntent,
            request.arguments["title"].orEmpty().ifBlank { "分享文本" },
        )
    }

    private fun startActivity(intent: Intent): Boolean {
        activityStarter?.let { starter ->
            return runCatching { starter(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.getOrDefault(false)
        }
        val appContext = context ?: return false
        return runCatching {
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private fun readClipboardText(): String? {
        val appContext = context ?: return null
        val clipboard = appContext.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        return clip.getItemAt(0).coerceToText(appContext)?.toString()
    }

    private companion object {
        const val MAX_CLIPBOARD_RESULT_CHARS = 4_000
    }
}
