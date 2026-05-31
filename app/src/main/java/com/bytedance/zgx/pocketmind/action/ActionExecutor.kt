package com.bytedance.zgx.pocketmind.action

import android.app.SearchManager
import android.content.ActivityNotFoundException
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
import java.net.URI

class ActionExecutor(
    private val context: Context?,
    private val backgroundTaskScheduler: BackgroundTaskScheduler? = null,
    private val canPostReminderNotifications: () -> Boolean = { true },
    private val clipboardTextProvider: (() -> String?)? = null,
    private val activityStarter: ((Intent) -> Boolean)? = null,
    private val externalActivityStarter: ((ExternalActivityLaunch) -> Boolean)? = null,
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
        if (request.toolName == MobileActionFunctions.CANCEL_REMINDER) {
            return cancelReminder(request)
        }
        if (request.toolName == MobileActionFunctions.READ_CLIPBOARD) {
            return readClipboard(request)
        }
        validateExternalIntentRequest(request)?.let { failure -> return failure }
        executeExternalActivityWithInjectedStarter(request)?.let { result -> return result }

        val intents = intentsFor(request)
            ?: return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        return executeExternalActivityIntents(request, intents)
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
                        "taskStatus" to task.status.name,
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

    private fun cancelReminder(request: ToolRequest): ToolResult {
        val taskId = request.arguments["taskId"].orEmpty().trim()
        if (taskId.isBlank()) {
            return request.failed(
                code = ToolErrorCode.InvalidRequest,
                summary = "提醒任务 id 不能为空",
                retryable = false,
            )
        }
        val scheduler = backgroundTaskScheduler
            ?: return request.failed(
                code = ToolErrorCode.ExecutionFailed,
                summary = "后台任务调度器不可用",
                retryable = true,
            )
        return scheduler.cancelScheduledTask(taskId)
            .fold(
                onSuccess = {
                    request.succeeded(
                        summary = "已取消后台任务：$taskId",
                        data = mapOf(
                            "toolName" to request.toolName,
                            "taskId" to taskId,
                            "taskStatus" to "Cancelled",
                        ),
                    )
                },
                onFailure = { throwable ->
                    request.failed(
                        code = ToolErrorCode.ExecutionFailed,
                        summary = "后台任务取消失败：${throwable.cleanMessage()}",
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

            MobileActionFunctions.OPEN_DEEP_LINK ->
                listOf(
                    Intent(Intent.ACTION_VIEW, Uri.parse(request.arguments.getValue("uri")))
                        .withCategoryIfAvailable(Intent.CATEGORY_BROWSABLE),
                )

            MobileActionFunctions.OPEN_APP_INTENT ->
                appIntentCandidates(request)

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
            MobileActionFunctions.OPEN_DEEP_LINK -> "已打开深链"
            MobileActionFunctions.OPEN_APP_INTENT -> "已打开应用"
            else -> "工具已执行"
        }

    private fun validateExternalIntentRequest(request: ToolRequest): ToolResult? =
        when (request.toolName) {
            MobileActionFunctions.OPEN_DEEP_LINK -> {
                val uri = request.arguments["uri"].orEmpty()
                val unknownArguments = request.arguments.keys - setOf("uri")
                if (unknownArguments.isNotEmpty()) {
                    request.failed(
                        code = ToolErrorCode.InvalidRequest,
                        summary = "打开深链仅支持 uri 参数",
                        retryable = false,
                        data = mapOf("toolName" to request.toolName),
                    )
                } else if (!isAllowedExternalUri(uri)) {
                    request.failed(
                        code = ToolErrorCode.InvalidRequest,
                        summary = "深链 URI 只支持安全的 https 链接",
                        retryable = false,
                        data = mapOf("toolName" to request.toolName),
                    )
                } else {
                    null
                }
            }

            MobileActionFunctions.OPEN_APP_INTENT -> validateOpenAppIntentRequest(request)
            else -> null
        }

    private fun validateOpenAppIntentRequest(request: ToolRequest): ToolResult? {
        val packageName = request.arguments["packageName"].orEmpty()
        val unknownArguments = request.arguments.keys - setOf("packageName")
        val invalidReason = when {
            unknownArguments.isNotEmpty() -> "打开应用 Intent 仅支持 packageName 参数"
            !PACKAGE_NAME_PATTERN.matches(packageName) -> "应用包名无效"
            else -> null
        }
        return invalidReason?.let { reason ->
            request.failed(
                code = ToolErrorCode.InvalidRequest,
                summary = reason,
                retryable = false,
                data = mapOf("toolName" to request.toolName),
            )
        }
    }

    private fun appIntentCandidates(request: ToolRequest): List<Intent> {
        val packageName = request.arguments.getValue("packageName")
        val packageLaunchIntent = Intent(Intent.ACTION_MAIN).apply {
            withPackageIfAvailable(packageName)
        }.withCategoryIfAvailable(Intent.CATEGORY_LAUNCHER)
        val launchIntent = context
            ?.packageManager
            ?.getLaunchIntentForPackage(packageName)
        return listOfNotNull(launchIntent, packageLaunchIntent)
    }

    private fun executeExternalActivityWithInjectedStarter(request: ToolRequest): ToolResult? {
        val starter = externalActivityStarter ?: return null
        val launch = externalActivityLaunchFor(request) ?: return null
        val metadata = launch.externalActivityMetadata()
        return when (val result = startInjectedExternalActivity(starter, launch)) {
            ExternalActivityStartResult.Started -> request.succeeded(
                summary = successSummaryFor(request.toolName),
                data = externalActivityData(request, metadata, COMPLETION_STATE_EXTERNAL_ACTIVITY_OPENED),
            )

            ExternalActivityStartResult.ActivityNotFound -> noActivityFoundResult(request, metadata)
            is ExternalActivityStartResult.Failed -> activityExecutionFailedResult(request, metadata, result.throwable)
        }
    }

    private fun externalActivityLaunchFor(request: ToolRequest): ExternalActivityLaunch? =
        when (request.toolName) {
            MobileActionFunctions.OPEN_DEEP_LINK -> ExternalActivityLaunch(
                toolName = request.toolName,
                action = Intent.ACTION_VIEW,
                uri = request.arguments.getValue("uri"),
            )

            MobileActionFunctions.OPEN_APP_INTENT -> ExternalActivityLaunch(
                toolName = request.toolName,
                action = Intent.ACTION_MAIN,
                packageName = request.arguments.getValue("packageName"),
            )

            MobileActionFunctions.SHARE_TEXT -> ExternalActivityLaunch(
                toolName = request.toolName,
                action = Intent.ACTION_CHOOSER,
            )

            else -> null
        }

    private fun isAllowedExternalUri(raw: String): Boolean {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.port in setOf(-1, 443) &&
            raw.length <= MAX_DEEP_LINK_URI_CHARS &&
            raw.none { character -> character.isWhitespace() || character.code < 0x20 }
    }

    private fun Intent.withCategoryIfAvailable(category: String): Intent =
        apply { runCatching { addCategory(category) } }

    private fun Intent.withPackageIfAvailable(packageName: String): Intent =
        apply { runCatching { setPackage(packageName) } }

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

    private fun executeExternalActivityIntents(
        request: ToolRequest,
        intents: List<Intent>,
    ): ToolResult {
        var lastMetadata: ExternalActivityMetadata? = null
        intents.forEach { intent ->
            val metadata = request.externalActivityMetadata(intent)
            lastMetadata = metadata
            when (val result = startActivity(intent)) {
                ExternalActivityStartResult.Started -> return request.succeeded(
                    summary = successSummaryFor(request.toolName),
                    data = externalActivityData(request, metadata, COMPLETION_STATE_EXTERNAL_ACTIVITY_OPENED),
                )

                ExternalActivityStartResult.ActivityNotFound -> Unit
                is ExternalActivityStartResult.Failed -> return activityExecutionFailedResult(
                    request = request,
                    metadata = metadata,
                    throwable = result.throwable,
                )
            }
        }

        return noActivityFoundResult(
            request = request,
            metadata = lastMetadata ?: request.externalActivityMetadata(null),
        )
    }

    private fun noActivityFoundResult(
        request: ToolRequest,
        metadata: ExternalActivityMetadata,
    ): ToolResult =
        request.failed(
            code = ToolErrorCode.NoActivityFound,
            summary = "没有找到可以执行 ${request.toolName} 的应用或系统页面",
            retryable = true,
            data = externalActivityData(request, metadata, COMPLETION_STATE_NOT_STARTED),
        )

    private fun activityExecutionFailedResult(
        request: ToolRequest,
        metadata: ExternalActivityMetadata,
        throwable: Throwable,
    ): ToolResult =
        request.failed(
            code = ToolErrorCode.ExecutionFailed,
            summary = "执行 ${request.toolName} 失败：${throwable.cleanMessage()}",
            retryable = true,
            data = externalActivityData(
                request = request,
                metadata = metadata,
                completionState = COMPLETION_STATE_NOT_STARTED,
                extra = mapOf("exceptionType" to throwable::class.java.simpleName),
            ),
        )

    private fun externalActivityData(
        request: ToolRequest,
        metadata: ExternalActivityMetadata,
        completionState: String,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> =
        buildMap {
            put("toolName", request.toolName)
            put("completionState", completionState)
            put("completionVerified", "false")
            put("externalOutcome", "Unknown")
            put("targetKind", metadata.targetKind)
            put("intentAction", metadata.intentAction)
            put("metadataPolicy", "AllowlistedCompletionMetadata")
            put("rawPayloadIncluded", "false")
            metadata.safeData.forEach { (key, value) ->
                if (value.isNotBlank()) put(key, value)
            }
            extra.forEach { (key, value) ->
                if (value.isNotBlank()) put(key, value)
            }
        }

    private fun ToolRequest.externalActivityMetadata(intent: Intent?): ExternalActivityMetadata {
        val action = intent?.action.orEmpty().ifBlank { defaultIntentActionFor(toolName) }
        return when (toolName) {
            MobileActionFunctions.OPEN_WIFI_SETTINGS -> ExternalActivityMetadata(
                targetKind = "SystemSettings",
                intentAction = action,
                safeData = mapOf("settingsAction" to action),
            )

            MobileActionFunctions.SEARCH_MAPS -> ExternalActivityMetadata(
                targetKind = "MapSearch",
                intentAction = action,
                safeData = intent.safeUriMetadata(),
            )

            MobileActionFunctions.WEB_SEARCH -> ExternalActivityMetadata(
                targetKind = "WebSearch",
                intentAction = action,
                safeData = intent.safeUriMetadata(),
            )

            MobileActionFunctions.COMPOSE_EMAIL -> ExternalActivityMetadata(
                targetKind = "EmailDraft",
                intentAction = action,
                safeData = intent.safeUriMetadata(),
            )

            MobileActionFunctions.CREATE_CALENDAR_EVENT -> ExternalActivityMetadata(
                targetKind = "CalendarEventDraft",
                intentAction = action,
                safeData = intent.safeUriMetadata(),
            )

            MobileActionFunctions.CREATE_CONTACT_DRAFT -> ExternalActivityMetadata(
                targetKind = "ContactDraft",
                intentAction = action,
                safeData = intent.safeMimeTypeMetadata(),
            )

            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS -> ExternalActivityMetadata(
                targetKind = "SystemSettings",
                intentAction = action,
                safeData = mapOf("settingsAction" to action),
            )

            MobileActionFunctions.SHARE_TEXT -> ExternalActivityMetadata(
                targetKind = "ShareSheet",
                intentAction = action,
                safeData = mapOf("payloadMimeType" to "text/plain"),
            )

            MobileActionFunctions.OPEN_DEEP_LINK -> ExternalActivityMetadata(
                targetKind = "HttpsUri",
                intentAction = action,
                safeData = intent.safeUriMetadata(),
            )

            MobileActionFunctions.OPEN_APP_INTENT -> ExternalActivityMetadata(
                targetKind = "AndroidPackage",
                intentAction = action,
                safeData = mapOf("targetPackage" to arguments["packageName"].orEmpty()),
            )

            else -> ExternalActivityMetadata(
                targetKind = "ExternalActivity",
                intentAction = action,
            )
        }
    }

    private fun ExternalActivityLaunch.externalActivityMetadata(): ExternalActivityMetadata {
        val action = action.ifBlank { defaultIntentActionFor(toolName) }
        return when (toolName) {
            MobileActionFunctions.OPEN_DEEP_LINK -> ExternalActivityMetadata(
                targetKind = "HttpsUri",
                intentAction = action,
                safeData = uri.safeUriMetadata(),
            )

            MobileActionFunctions.OPEN_APP_INTENT -> ExternalActivityMetadata(
                targetKind = "AndroidPackage",
                intentAction = action,
                safeData = mapOf("targetPackage" to packageName.orEmpty()),
            )

            MobileActionFunctions.SHARE_TEXT -> ExternalActivityMetadata(
                targetKind = "ShareSheet",
                intentAction = action,
                safeData = mapOf("payloadMimeType" to "text/plain"),
            )

            else -> ExternalActivityMetadata(
                targetKind = "ExternalActivity",
                intentAction = action,
            )
        }
    }

    private fun Intent?.safeUriMetadata(): Map<String, String> =
        this?.data?.let { uri ->
            buildMap {
                uri.scheme?.let { put("targetUriScheme", it) }
                uri.host?.let { put("targetUriHost", it) }
                if (uri.port != -1) put("targetUriPort", uri.port.toString())
            }
        }.orEmpty()

    private fun Intent?.safeMimeTypeMetadata(): Map<String, String> =
        this?.type?.let { mimeType -> mapOf("payloadMimeType" to mimeType) }.orEmpty()

    private fun String?.safeUriMetadata(): Map<String, String> {
        val uri = runCatching { this?.trim()?.takeIf { it.isNotBlank() }?.let(::URI) }.getOrNull()
            ?: return emptyMap()
        return buildMap {
            uri.scheme?.let { put("targetUriScheme", it) }
            uri.host?.let { put("targetUriHost", it) }
            if (uri.port != -1) put("targetUriPort", uri.port.toString())
        }
    }

    private fun defaultIntentActionFor(toolName: String): String =
        when (toolName) {
            MobileActionFunctions.OPEN_WIFI_SETTINGS -> Settings.ACTION_WIFI_SETTINGS
            MobileActionFunctions.SEARCH_MAPS -> Intent.ACTION_VIEW
            MobileActionFunctions.WEB_SEARCH -> Intent.ACTION_WEB_SEARCH
            MobileActionFunctions.COMPOSE_EMAIL -> Intent.ACTION_SENDTO
            MobileActionFunctions.CREATE_CALENDAR_EVENT -> Intent.ACTION_INSERT
            MobileActionFunctions.CREATE_CONTACT_DRAFT -> ContactsContract.Intents.Insert.ACTION
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS -> Settings.ACTION_SETTINGS
            MobileActionFunctions.SHARE_TEXT -> Intent.ACTION_CHOOSER
            MobileActionFunctions.OPEN_DEEP_LINK -> Intent.ACTION_VIEW
            MobileActionFunctions.OPEN_APP_INTENT -> Intent.ACTION_MAIN
            else -> ""
        }

    private fun startInjectedExternalActivity(
        starter: (ExternalActivityLaunch) -> Boolean,
        launch: ExternalActivityLaunch,
    ): ExternalActivityStartResult =
        try {
            if (starter(launch)) {
                ExternalActivityStartResult.Started
            } else {
                ExternalActivityStartResult.ActivityNotFound
            }
        } catch (_: ActivityNotFoundException) {
            ExternalActivityStartResult.ActivityNotFound
        } catch (throwable: Throwable) {
            ExternalActivityStartResult.Failed(throwable)
        }

    private fun startActivity(intent: Intent): ExternalActivityStartResult {
        activityStarter?.let { starter ->
            val launchIntent = runCatching { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }.getOrDefault(intent)
            return try {
                if (starter(launchIntent)) {
                    ExternalActivityStartResult.Started
                } else {
                    ExternalActivityStartResult.ActivityNotFound
                }
            } catch (_: ActivityNotFoundException) {
                ExternalActivityStartResult.ActivityNotFound
            } catch (throwable: Throwable) {
                ExternalActivityStartResult.Failed(throwable)
            }
        }
        val appContext = context ?: return ExternalActivityStartResult.ActivityNotFound
        return try {
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ExternalActivityStartResult.Started
        } catch (_: ActivityNotFoundException) {
            ExternalActivityStartResult.ActivityNotFound
        } catch (throwable: Throwable) {
            ExternalActivityStartResult.Failed(throwable)
        }
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
        const val MAX_DEEP_LINK_URI_CHARS = 2_048
        const val COMPLETION_STATE_EXTERNAL_ACTIVITY_OPENED = "ExternalActivityOpened"
        const val COMPLETION_STATE_NOT_STARTED = "NotStarted"
        val PACKAGE_NAME_PATTERN = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+$""")
    }
}

private data class ExternalActivityMetadata(
    val targetKind: String,
    val intentAction: String,
    val safeData: Map<String, String> = emptyMap(),
)

private sealed class ExternalActivityStartResult {
    data object Started : ExternalActivityStartResult()
    data object ActivityNotFound : ExternalActivityStartResult()
    data class Failed(val throwable: Throwable) : ExternalActivityStartResult()
}

data class ExternalActivityLaunch(
    val toolName: String,
    val action: String,
    val uri: String? = null,
    val packageName: String? = null,
)
