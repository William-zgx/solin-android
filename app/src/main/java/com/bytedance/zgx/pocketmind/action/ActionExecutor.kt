package com.bytedance.zgx.pocketmind.action

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.background.BackgroundTaskScheduler
import com.bytedance.zgx.pocketmind.background.PeriodicCheckConstraints
import com.bytedance.zgx.pocketmind.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.pocketmind.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.pocketmind.background.ReminderScheduleRequest
import com.bytedance.zgx.pocketmind.device.DeviceControlSessionService
import com.bytedance.zgx.pocketmind.tool.ToolErrorCode
import com.bytedance.zgx.pocketmind.tool.ToolExecutor
import com.bytedance.zgx.pocketmind.tool.MAX_SHARE_TEXT_CHARS
import com.bytedance.zgx.pocketmind.tool.MAX_SHARE_TITLE_CHARS
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import com.bytedance.zgx.pocketmind.tool.failed
import com.bytedance.zgx.pocketmind.tool.succeeded
import java.net.URI
import java.util.Calendar

class ActionExecutor(
    private val context: Context?,
    private val backgroundTaskScheduler: BackgroundTaskScheduler? = null,
    private val canPostReminderNotifications: () -> Boolean = { true },
    private val clipboardTextProvider: (() -> String?)? = null,
    private val activityStarter: ((Intent) -> Boolean)? = null,
    private val externalActivityStarter: ((ExternalActivityLaunch) -> Boolean)? = null,
    private val appLauncherResolver: ((String) -> AppLaunchResolution)? = null,
    private val deviceControlSessionStarter: ((String) -> Unit)? = null,
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
        if (request.toolName == MobileActionFunctions.CONFIGURE_PERIODIC_CHECK) {
            return configurePeriodicCheck(request)
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
                data = request.clipboardContextData(),
            )
        }
        val clipped = text.take(MAX_CLIPBOARD_RESULT_CHARS)
        return request.succeeded(
            summary = "已读取剪贴板文本（${text.length} 字符）",
            data = request.clipboardContextData() + mapOf(
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
        val triggerAtMillis = request.arguments["triggerAtMillis"]?.toLongOrNull()
        if ((delayMinutes == null) == (triggerAtMillis == null)) {
            return request.failed(
                code = ToolErrorCode.InvalidRequest,
                summary = "提醒时间参数无效",
                retryable = false,
            )
        }
        return scheduler.scheduleReminder(
            ReminderScheduleRequest(
                title = title,
                body = body,
                delayMinutes = delayMinutes,
                triggerAtMillis = triggerAtMillis,
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
                        "recoveryToolName" to MobileActionFunctions.CANCEL_REMINDER,
                        "recoveryTaskId" to task.id,
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

    private fun configurePeriodicCheck(request: ToolRequest): ToolResult {
        val enabled = request.arguments["enabled"]?.toBooleanStrictOrNull()
            ?: return request.failed(
                code = ToolErrorCode.InvalidRequest,
                summary = "周期检查启用参数无效",
                retryable = false,
            )
        if (enabled && !canPostReminderNotifications()) {
            return request.failed(
                code = ToolErrorCode.PermissionDenied,
                summary = "需要通知权限才能开启本地提醒周期检查",
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
        val scheduleRequest = request.toPeriodicCheckScheduleRequest(enabled)
            ?: return request.failed(
                code = ToolErrorCode.InvalidRequest,
                summary = "周期检查参数无效",
                retryable = false,
            )

        val result = if (enabled) {
            scheduler.setPeriodicCheckPolicy(scheduleRequest)
        } else {
            scheduler.disablePeriodicCheckPolicy()
        }
        return result.fold(
            onSuccess = { policy ->
                request.succeeded(
                    summary = if (policy.request.enabled) {
                        "已开启本地提醒周期检查：每 ${policy.request.intervalMinutes} 分钟"
                    } else {
                        "已关闭本地提醒周期检查"
                    },
                    data = policy.toPeriodicCheckToolData(request.toolName),
                )
            },
            onFailure = { throwable ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "周期检查配置失败：${throwable.cleanMessage()}",
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
                    val invalidCancellation = throwable is IllegalArgumentException
                    request.failed(
                        code = if (invalidCancellation) ToolErrorCode.InvalidRequest else ToolErrorCode.ExecutionFailed,
                        summary = "后台任务取消失败：${throwable.cleanMessage()}",
                        retryable = !invalidCancellation,
                        data = mapOf("toolName" to request.toolName),
                    )
                },
            )
    }

    private fun ToolRequest.toPeriodicCheckScheduleRequest(enabled: Boolean): PeriodicCheckScheduleRequest? {
        fun longArg(name: String, default: Long): Long =
            arguments[name]?.toLongOrNull() ?: default

        fun booleanArg(name: String, default: Boolean): Boolean? =
            arguments[name]?.toBooleanStrictOrNull() ?: if (name in arguments) null else default

        val requiresBatteryNotLow = booleanArg(
            "requiresBatteryNotLow",
            PeriodicCheckConstraints().requiresBatteryNotLow,
        ) ?: return null
        val requiresCharging = booleanArg(
            "requiresCharging",
            PeriodicCheckConstraints().requiresCharging,
        ) ?: return null

        return PeriodicCheckScheduleRequest(
            enabled = enabled,
            intervalMinutes = longArg(
                "intervalMinutes",
                PeriodicCheckScheduleRequest.DEFAULT_INTERVAL_MINUTES,
            ),
            minNotificationSpacingMinutes = longArg(
                "minNotificationSpacingMinutes",
                PeriodicCheckScheduleRequest.DEFAULT_MIN_NOTIFICATION_SPACING_MINUTES,
            ),
            overdueGraceMinutes = longArg(
                "overdueGraceMinutes",
                PeriodicCheckScheduleRequest.DEFAULT_OVERDUE_GRACE_MINUTES,
            ),
            constraints = PeriodicCheckConstraints(
                requiresBatteryNotLow = requiresBatteryNotLow,
                requiresCharging = requiresCharging,
            ),
        ).normalized()
    }

    private fun PeriodicCheckPolicySummary.toPeriodicCheckToolData(toolName: String): Map<String, String> {
        val normalized = request.normalized()
        return buildMap {
            put("toolName", toolName)
            put("enabled", normalized.enabled.toString())
            put(
                "taskStatus",
                taskStatus?.name ?: if (normalized.enabled) {
                    "Scheduled"
                } else {
                    "Cancelled"
                },
            )
            put("intervalMinutes", normalized.intervalMinutes.toString())
            put("minNotificationSpacingMinutes", normalized.minNotificationSpacingMinutes.toString())
            put("overdueGraceMinutes", normalized.overdueGraceMinutes.toString())
            put("requiresBatteryNotLow", normalized.constraints.requiresBatteryNotLow.toString())
            put("requiresCharging", normalized.constraints.requiresCharging.toString())
            nextAllowedRunAtMillis?.let { put("nextAllowedRunAtMillis", it.toString()) }
            updatedAtMillis?.let { put("updatedAtMillis", it.toString()) }
            if (normalized.enabled) {
                put("recoveryToolName", MobileActionFunctions.CONFIGURE_PERIODIC_CHECK)
                put("recoveryEnabled", false.toString())
            }
        }
    }

    private fun intentsFor(request: ToolRequest): List<Intent>? =
        when (request.toolName) {
            MobileActionFunctions.OPEN_WIFI_SETTINGS ->
                listOf(Intent(Settings.ACTION_WIFI_SETTINGS))

            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS ->
                listOf(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

            MobileActionFunctions.OPEN_SYSTEM_SETTINGS ->
                listOf(Intent(systemSettingsActionFor(request.arguments.getValue("target"))))

            MobileActionFunctions.SEARCH_MAPS ->
                listOf(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=${Uri.encode(request.arguments["query"].orEmpty())}"),
                    ),
                )

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

            MobileActionFunctions.SET_SYSTEM_ALARM ->
                listOf(systemAlarmIntent(request))

            MobileActionFunctions.SET_SYSTEM_TIMER ->
                listOf(systemTimerIntent(request))

            MobileActionFunctions.READ_CLIPBOARD ->
                null

            MobileActionFunctions.SHARE_TEXT ->
                listOf(shareTextIntent(request))

            MobileActionFunctions.OPEN_DEEP_LINK ->
                listOf(
                    Intent(Intent.ACTION_VIEW, Uri.parse(request.arguments.getValue("uri")))
                        .withCategoryIfAvailable(Intent.CATEGORY_BROWSABLE),
                )

            MobileActionFunctions.OPEN_CAMERA ->
                listOf(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))

            MobileActionFunctions.OPEN_APP_BY_NAME ->
                appNameIntentCandidates(request)

            MobileActionFunctions.OPEN_APP_INTENT ->
                appIntentCandidates(request)

            MobileActionFunctions.OPEN_APP_DEEP_TARGET ->
                listOf(appDeepTargetFor(request).buildIntent(request.arguments))

            else -> null
        }

    private fun successSummaryFor(request: ToolRequest): String =
        when (val toolName = request.toolName) {
            MobileActionFunctions.OPEN_WIFI_SETTINGS -> "已打开 Wi-Fi 设置页"
            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS -> "已打开使用情况访问权限设置页"
            MobileActionFunctions.OPEN_SYSTEM_SETTINGS ->
                "已打开${SystemSettingsTargets.pageLabel(request.arguments["target"].orEmpty())}"
            MobileActionFunctions.SEARCH_MAPS -> "已打开地图搜索"
            MobileActionFunctions.COMPOSE_EMAIL -> "已打开邮件草稿页"
            MobileActionFunctions.CREATE_CALENDAR_EVENT -> "已打开日历新建事件页"
            MobileActionFunctions.CREATE_CONTACT_DRAFT -> "已打开联系人草稿页"
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS -> "已打开系统设置页"
            MobileActionFunctions.SCHEDULE_REMINDER -> "已安排后台提醒"
            MobileActionFunctions.SET_SYSTEM_ALARM -> "已打开系统闹钟设置界面"
            MobileActionFunctions.SET_SYSTEM_TIMER -> "已打开系统倒计时设置界面"
            MobileActionFunctions.READ_CLIPBOARD -> "已读取剪贴板"
            MobileActionFunctions.SHARE_TEXT -> "已打开系统分享面板"
            MobileActionFunctions.OPEN_DEEP_LINK -> "已打开深链"
            MobileActionFunctions.OPEN_CAMERA -> "已打开相机"
            MobileActionFunctions.OPEN_APP_BY_NAME -> "已打开应用"
            MobileActionFunctions.OPEN_APP_INTENT -> "已打开应用"
            MobileActionFunctions.OPEN_APP_DEEP_TARGET -> "已打开应用深层目标"
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

            MobileActionFunctions.OPEN_SYSTEM_SETTINGS -> validateSystemSettingsRequest(request)
            MobileActionFunctions.OPEN_APP_BY_NAME -> validateOpenAppByNameRequest(request)
            MobileActionFunctions.OPEN_APP_INTENT -> validateOpenAppIntentRequest(request)
            MobileActionFunctions.OPEN_APP_DEEP_TARGET -> validateOpenAppDeepTargetRequest(request)
            MobileActionFunctions.SHARE_TEXT -> validateShareTextRequest(request)
            MobileActionFunctions.OPEN_CAMERA -> validateEmptyArguments(request, "打开相机不接受参数")
            MobileActionFunctions.SET_SYSTEM_ALARM -> validateSystemAlarmRequest(request)
            MobileActionFunctions.SET_SYSTEM_TIMER -> validateSystemTimerRequest(request)
            else -> null
        }

    private fun validateSystemAlarmRequest(request: ToolRequest): ToolResult? {
        val unknownArguments = request.arguments.keys - setOf("hour", "minutes", "message", "recurrence")
        val hour = request.arguments["hour"]?.toIntOrNull()
        val minutes = request.arguments["minutes"]?.toIntOrNull()
        val recurrence = request.arguments["recurrence"].orEmpty().ifBlank { "once" }
        val message = request.arguments["message"].orEmpty()
        val invalidReason = when {
            unknownArguments.isNotEmpty() -> "系统闹钟不接受参数：${unknownArguments.sorted().joinToString()}"
            hour == null || hour !in 0..23 -> "系统闹钟 hour 必须在 0 到 23 之间"
            minutes == null || minutes !in 0..59 -> "系统闹钟 minutes 必须在 0 到 59 之间"
            recurrence !in setOf("once", "daily") -> "系统闹钟 recurrence 仅支持 once 或 daily"
            message.length > MAX_CLOCK_LABEL_CHARS -> "系统闹钟标签过长"
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

    private fun validateSystemTimerRequest(request: ToolRequest): ToolResult? {
        val unknownArguments = request.arguments.keys - setOf("lengthSeconds", "message")
        val lengthSeconds = request.arguments["lengthSeconds"]?.toIntOrNull()
        val message = request.arguments["message"].orEmpty()
        val invalidReason = when {
            unknownArguments.isNotEmpty() -> "系统倒计时不接受参数：${unknownArguments.sorted().joinToString()}"
            lengthSeconds == null || lengthSeconds !in 1..MAX_TIMER_SECONDS ->
                "系统倒计时时长必须在 1 到 $MAX_TIMER_SECONDS 秒之间"
            message.length > MAX_CLOCK_LABEL_CHARS -> "系统倒计时标签过长"
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

    private fun validateSystemSettingsRequest(request: ToolRequest): ToolResult? {
        val target = request.arguments["target"].orEmpty()
        val unknownArguments = request.arguments.keys - setOf("target")
        val invalidReason = when {
            unknownArguments.isNotEmpty() -> "打开系统设置仅支持 target 参数"
            target !in SystemSettingsTargets.supported -> "系统设置 target 不在允许列表中"
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

    private fun validateEmptyArguments(request: ToolRequest, summary: String): ToolResult? =
        if (request.arguments.isEmpty()) {
            null
        } else {
            request.failed(
                code = ToolErrorCode.InvalidRequest,
                summary = summary,
                retryable = false,
                data = mapOf("toolName" to request.toolName),
            )
        }

    private fun validateShareTextRequest(request: ToolRequest): ToolResult? {
        val unknownArguments = request.arguments.keys - setOf("text", "title")
        val text = request.arguments["text"].orEmpty()
        val title = request.arguments["title"].orEmpty()
        val invalidReason = when {
            unknownArguments.isNotEmpty() -> "分享文本仅支持 text 和 title 参数"
            text.isBlank() -> "分享文本不能为空"
            text.length > MAX_SHARE_TEXT_CHARS -> "分享文本不能超过 $MAX_SHARE_TEXT_CHARS 个字符"
            title.length > MAX_SHARE_TITLE_CHARS -> "分享标题不能超过 $MAX_SHARE_TITLE_CHARS 个字符"
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

    private fun validateOpenAppByNameRequest(request: ToolRequest): ToolResult? {
        val appName = request.arguments["appName"].orEmpty()
        val unknownArguments = request.arguments.keys - setOf("appName")
        val invalidReason = when {
            unknownArguments.isNotEmpty() -> "按名称打开应用仅支持 appName 参数"
            appName.isBlank() -> "应用名称不能为空"
            appName.length > MAX_APP_NAME_CHARS -> "应用名称过长"
            appName.any { it.code < 0x20 } -> "应用名称包含无效字符"
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

    private fun validateOpenAppDeepTargetRequest(request: ToolRequest): ToolResult? {
        val targetId = request.arguments[AppDeepTargets.TARGET_ID_ARGUMENT].orEmpty()
        val target = AppDeepTargets.targetOrNull(targetId)
            ?: return request.failed(
                code = ToolErrorCode.InvalidRequest,
                summary = "应用深层目标 targetId 不在允许列表中",
                retryable = false,
                data = mapOf("toolName" to request.toolName),
            )
        val allowedArguments = setOf(AppDeepTargets.TARGET_ID_ARGUMENT) + target.requiredArguments
        val unknownArguments = request.arguments.keys - allowedArguments
        val packageName = request.arguments[AppDeepTargets.PACKAGE_NAME_ARGUMENT].orEmpty()
        val missingArguments = target.requiredArguments
            .filter { request.arguments[it].isNullOrBlank() }
        val invalidReason = when {
            unknownArguments.isNotEmpty() -> "应用深层目标不接受参数：${unknownArguments.sorted().joinToString()}"
            missingArguments.isNotEmpty() -> "应用深层目标缺少参数：${missingArguments.sorted().joinToString()}"
            AppDeepTargets.PACKAGE_NAME_ARGUMENT in target.requiredArguments &&
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
            ?.withPackageIfAvailable(packageName)
        return listOfNotNull(launchIntent, packageLaunchIntent)
    }

    private fun appNameIntentCandidates(request: ToolRequest): List<Intent> {
        val resolution = resolveLaunchableApp(request.arguments.getValue("appName"))
        val packageName = when (resolution) {
            is AppLaunchResolution.Resolved -> resolution.packageName
            else -> return emptyList()
        }
        return appIntentCandidates(
            request.copy(
                arguments = mapOf("packageName" to packageName),
            ),
        )
    }

    private fun executeExternalActivityWithInjectedStarter(request: ToolRequest): ToolResult? {
        val starter = externalActivityStarter ?: return null
        val launch = externalActivityLaunchFor(request) ?: return null
        val metadata = launch.externalActivityMetadata()
        request.startDeviceControlSessionIfNeeded()
        return when (val result = startInjectedExternalActivity(starter, launch)) {
            ExternalActivityStartResult.Started -> request.succeeded(
                summary = successSummaryFor(request),
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

            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS -> ExternalActivityLaunch(
                toolName = request.toolName,
                action = Settings.ACTION_USAGE_ACCESS_SETTINGS,
            )

            MobileActionFunctions.OPEN_SYSTEM_SETTINGS -> {
                val target = request.arguments.getValue("target")
                ExternalActivityLaunch(
                    toolName = request.toolName,
                    action = systemSettingsActionFor(target),
                    targetId = target,
                )
            }

            MobileActionFunctions.OPEN_CAMERA -> ExternalActivityLaunch(
                toolName = request.toolName,
                action = MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
            )

            MobileActionFunctions.SET_SYSTEM_ALARM -> ExternalActivityLaunch(
                toolName = request.toolName,
                action = AlarmClock.ACTION_SET_ALARM,
            )

            MobileActionFunctions.SET_SYSTEM_TIMER -> ExternalActivityLaunch(
                toolName = request.toolName,
                action = AlarmClock.ACTION_SET_TIMER,
            )

            MobileActionFunctions.OPEN_APP_BY_NAME -> {
                val resolution = resolveLaunchableApp(request.arguments.getValue("appName"))
                val packageName = (resolution as? AppLaunchResolution.Resolved)?.packageName
                ExternalActivityLaunch(
                    toolName = request.toolName,
                    action = Intent.ACTION_MAIN,
                    packageName = packageName,
                )
            }

            MobileActionFunctions.OPEN_APP_INTENT -> ExternalActivityLaunch(
                toolName = request.toolName,
                action = Intent.ACTION_MAIN,
                packageName = request.arguments.getValue("packageName"),
            )

            MobileActionFunctions.OPEN_APP_DEEP_TARGET -> {
                val target = appDeepTargetFor(request)
                ExternalActivityLaunch(
                    toolName = request.toolName,
                    action = target.intentAction,
                    uri = "package:${request.arguments.getValue(AppDeepTargets.PACKAGE_NAME_ARGUMENT)}",
                    packageName = request.arguments.getValue(AppDeepTargets.PACKAGE_NAME_ARGUMENT),
                    targetId = target.id,
                )
            }

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

    private fun systemAlarmIntent(request: ToolRequest): Intent =
        Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, request.arguments.getValue("hour").toInt())
            putExtra(AlarmClock.EXTRA_MINUTES, request.arguments.getValue("minutes").toInt())
            request.arguments["message"]?.takeIf { it.isNotBlank() }?.let { message ->
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
            }
            if (request.arguments["recurrence"] == "daily") {
                putIntegerArrayListExtra(
                    AlarmClock.EXTRA_DAYS,
                    arrayListOf(
                        Calendar.SUNDAY,
                        Calendar.MONDAY,
                        Calendar.TUESDAY,
                        Calendar.WEDNESDAY,
                        Calendar.THURSDAY,
                        Calendar.FRIDAY,
                        Calendar.SATURDAY,
                    ),
                )
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }

    private fun systemTimerIntent(request: ToolRequest): Intent =
        Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, request.arguments.getValue("lengthSeconds").toInt())
            request.arguments["message"]?.takeIf { it.isNotBlank() }?.let { message ->
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }

    private fun executeExternalActivityIntents(
        request: ToolRequest,
        intents: List<Intent>,
    ): ToolResult {
        var lastMetadata: ExternalActivityMetadata? = null
        intents.forEach { intent ->
            val metadata = request.externalActivityMetadata(intent)
            lastMetadata = metadata
            request.startDeviceControlSessionIfNeeded()
            when (val result = startActivity(intent)) {
                ExternalActivityStartResult.Started -> return request.succeeded(
                    summary = successSummaryFor(request),
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

    private fun ToolRequest.startDeviceControlSessionIfNeeded() {
        if (toolName !in DEVICE_CONTROL_SESSION_TOOLS) return
        val reason = deviceControlSessionReason()
        runCatching {
            deviceControlSessionStarter?.invoke(reason)
                ?: context?.let { appContext -> DeviceControlSessionService.start(appContext, reason) }
        }
    }

    private fun ToolRequest.deviceControlSessionReason(): String =
        when (toolName) {
            MobileActionFunctions.OPEN_APP_BY_NAME ->
                "正在打开应用：${arguments["appName"].orEmpty().ifBlank { "目标应用" }}"
            MobileActionFunctions.OPEN_APP_INTENT ->
                "正在打开应用：${arguments["packageName"].orEmpty().ifBlank { "目标应用" }}"
            MobileActionFunctions.OPEN_APP_DEEP_TARGET -> "正在打开应用功能"
            MobileActionFunctions.OPEN_CAMERA -> "正在打开相机"
            MobileActionFunctions.OPEN_WIFI_SETTINGS -> "正在打开 Wi-Fi 设置"
            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS -> "正在打开使用情况访问权限设置"
            MobileActionFunctions.OPEN_SYSTEM_SETTINGS ->
                "正在打开${SystemSettingsTargets.pageLabel(arguments["target"].orEmpty())}"
            MobileActionFunctions.SEARCH_MAPS -> "正在打开地图搜索"
            else -> DeviceControlSessionService.DEFAULT_REASON
        }

    private fun noActivityFoundResult(
        request: ToolRequest,
        metadata: ExternalActivityMetadata,
    ): ToolResult {
        val appNameFailureSummary = if (request.toolName == MobileActionFunctions.OPEN_APP_BY_NAME) {
            appNameResolutionFailureSummary(request)
        } else {
            null
        }
        return request.failed(
            code = ToolErrorCode.NoActivityFound,
            summary = appNameFailureSummary ?: "没有找到可以执行 ${request.toolName} 的应用或系统页面",
            retryable = true,
            data = externalActivityData(request, metadata, COMPLETION_STATE_NOT_STARTED),
        )
    }

    private fun activityExecutionFailedResult(
        request: ToolRequest,
        metadata: ExternalActivityMetadata,
        throwable: Throwable,
    ): ToolResult =
        request.failed(
            code = ToolErrorCode.ExecutionFailed,
            summary = EXTERNAL_ACTIVITY_START_FAILED_SUMMARY,
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
            put("externalOutcomeSource", "Unknown")
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

            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS -> ExternalActivityMetadata(
                targetKind = "SystemSettings",
                intentAction = action,
                safeData = mapOf(
                    "settingsAction" to action,
                    "specialAccess" to "usage_stats",
                ),
            )

            MobileActionFunctions.OPEN_SYSTEM_SETTINGS -> {
                val target = arguments["target"].orEmpty()
                ExternalActivityMetadata(
                    targetKind = "SystemSettings",
                    intentAction = action,
                    safeData = mapOf(
                        "settingsAction" to action,
                        "targetId" to target,
                    ),
                )
            }

            MobileActionFunctions.SEARCH_MAPS -> ExternalActivityMetadata(
                targetKind = "MapSearch",
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

            MobileActionFunctions.OPEN_CAMERA -> ExternalActivityMetadata(
                targetKind = "Camera",
                intentAction = action,
            )

            MobileActionFunctions.SET_SYSTEM_ALARM -> ExternalActivityMetadata(
                targetKind = "SystemAlarm",
                intentAction = action,
            )

            MobileActionFunctions.SET_SYSTEM_TIMER -> ExternalActivityMetadata(
                targetKind = "SystemTimer",
                intentAction = action,
            )

            MobileActionFunctions.OPEN_APP_BY_NAME -> ExternalActivityMetadata(
                targetKind = "AndroidPackage",
                intentAction = action,
                safeData = mapOf("targetPackage" to intent?.`package`.orEmpty()),
            )

            MobileActionFunctions.OPEN_APP_INTENT -> ExternalActivityMetadata(
                targetKind = "AndroidPackage",
                intentAction = action,
                safeData = mapOf("targetPackage" to arguments["packageName"].orEmpty()),
            )

            MobileActionFunctions.OPEN_APP_DEEP_TARGET -> {
                val target = appDeepTargetFor(this)
                ExternalActivityMetadata(
                    targetKind = target.targetKind,
                    intentAction = action,
                    safeData = mapOf(
                        "targetId" to target.id,
                        "targetPackage" to arguments[AppDeepTargets.PACKAGE_NAME_ARGUMENT].orEmpty(),
                    ),
                )
            }

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

            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS -> ExternalActivityMetadata(
                targetKind = "SystemSettings",
                intentAction = action,
                safeData = mapOf(
                    "settingsAction" to action,
                    "specialAccess" to "usage_stats",
                ),
            )

            MobileActionFunctions.OPEN_SYSTEM_SETTINGS -> ExternalActivityMetadata(
                targetKind = "SystemSettings",
                intentAction = action,
                safeData = mapOf(
                    "settingsAction" to action,
                    "targetId" to targetId.orEmpty(),
                ),
            )

            MobileActionFunctions.OPEN_CAMERA -> ExternalActivityMetadata(
                targetKind = "Camera",
                intentAction = action,
            )

            MobileActionFunctions.SET_SYSTEM_ALARM -> ExternalActivityMetadata(
                targetKind = "SystemAlarm",
                intentAction = action,
            )

            MobileActionFunctions.SET_SYSTEM_TIMER -> ExternalActivityMetadata(
                targetKind = "SystemTimer",
                intentAction = action,
            )

            MobileActionFunctions.OPEN_APP_BY_NAME -> ExternalActivityMetadata(
                targetKind = "AndroidPackage",
                intentAction = action,
                safeData = mapOf("targetPackage" to packageName.orEmpty()),
            )

            MobileActionFunctions.OPEN_APP_INTENT -> ExternalActivityMetadata(
                targetKind = "AndroidPackage",
                intentAction = action,
                safeData = mapOf("targetPackage" to packageName.orEmpty()),
            )

            MobileActionFunctions.OPEN_APP_DEEP_TARGET -> ExternalActivityMetadata(
                targetKind = AppDeepTargets.targetOrNull(targetId.orEmpty())?.targetKind ?: "AppDeepTarget",
                intentAction = action,
                safeData = mapOf(
                    "targetId" to targetId.orEmpty(),
                    "targetPackage" to packageName.orEmpty(),
                ),
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
            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS -> Settings.ACTION_USAGE_ACCESS_SETTINGS
            MobileActionFunctions.OPEN_SYSTEM_SETTINGS -> Settings.ACTION_SETTINGS
            MobileActionFunctions.SEARCH_MAPS -> Intent.ACTION_VIEW
            MobileActionFunctions.COMPOSE_EMAIL -> Intent.ACTION_SENDTO
            MobileActionFunctions.CREATE_CALENDAR_EVENT -> Intent.ACTION_INSERT
            MobileActionFunctions.CREATE_CONTACT_DRAFT -> ContactsContract.Intents.Insert.ACTION
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS -> Settings.ACTION_SETTINGS
            MobileActionFunctions.SHARE_TEXT -> Intent.ACTION_CHOOSER
            MobileActionFunctions.OPEN_DEEP_LINK -> Intent.ACTION_VIEW
            MobileActionFunctions.OPEN_CAMERA -> MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
            MobileActionFunctions.SET_SYSTEM_ALARM -> AlarmClock.ACTION_SET_ALARM
            MobileActionFunctions.SET_SYSTEM_TIMER -> AlarmClock.ACTION_SET_TIMER
            MobileActionFunctions.OPEN_APP_BY_NAME -> Intent.ACTION_MAIN
            MobileActionFunctions.OPEN_APP_INTENT -> Intent.ACTION_MAIN
            MobileActionFunctions.OPEN_APP_DEEP_TARGET ->
                AppDeepTargets.requireTarget(AppDeepTargets.APP_DETAILS_SETTINGS_ID).intentAction
            else -> ""
        }

    private fun systemSettingsActionFor(target: String): String =
        when (target) {
            SystemSettingsTargets.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            SystemSettingsTargets.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            SystemSettingsTargets.NOTIFICATION -> "android.settings.NOTIFICATION_SETTINGS"
            SystemSettingsTargets.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
            SystemSettingsTargets.SOUND -> Settings.ACTION_SOUND_SETTINGS
            SystemSettingsTargets.BATTERY_SAVER -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            SystemSettingsTargets.NETWORK -> Settings.ACTION_WIRELESS_SETTINGS
            SystemSettingsTargets.AIRPLANE_MODE -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            SystemSettingsTargets.INPUT_METHOD -> Settings.ACTION_INPUT_METHOD_SETTINGS
            SystemSettingsTargets.ACCESSIBILITY -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

    private fun appDeepTargetFor(request: ToolRequest): AppDeepTarget =
        AppDeepTargets.requireTarget(request.arguments.getValue(AppDeepTargets.TARGET_ID_ARGUMENT))

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

    private fun resolveLaunchableApp(rawAppName: String): AppLaunchResolution {
        appLauncherResolver?.let { resolver -> return resolver(rawAppName) }
        val packageManager = context?.packageManager ?: return AppLaunchResolution.NotFound
        val query = rawAppName.trim()
        if (query.isBlank()) return AppLaunchResolution.NotFound
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val entries = packageManager
            .queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo -> resolveInfo.toLaunchableAppEntry(packageManager) }
            .distinctBy { it.packageName }
        return matchLaunchableApp(query, entries)
    }

    private fun ResolveInfo.toLaunchableAppEntry(packageManager: PackageManager): LaunchableAppEntry? {
        val activityInfo = activityInfo ?: return null
        val packageName = activityInfo.packageName?.takeIf { it.isNotBlank() } ?: return null
        val label = runCatching { loadLabel(packageManager).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: packageName
        return LaunchableAppEntry(label = label, packageName = packageName)
    }

    private fun appNameResolutionFailureSummary(request: ToolRequest): String =
        when (val resolution = resolveLaunchableApp(request.arguments["appName"].orEmpty())) {
            AppLaunchResolution.Ambiguous -> "找到多个同名或相近应用，请换成更完整的应用名后重试。"
            AppLaunchResolution.NotFound -> "没有找到匹配的可启动应用，请确认应用已安装或换成系统桌面显示的应用名。"
            is AppLaunchResolution.Resolved -> "已找到应用 ${resolution.label}，但系统没有可打开的启动页。"
        }

    private companion object {
        const val MAX_CLIPBOARD_RESULT_CHARS = 4_000
        const val MAX_DEEP_LINK_URI_CHARS = 2_048
        const val MAX_APP_NAME_CHARS = 80
        const val MAX_CLOCK_LABEL_CHARS = 120
        const val MAX_TIMER_SECONDS = 24 * 60 * 60
        const val COMPLETION_STATE_EXTERNAL_ACTIVITY_OPENED = "ExternalActivityOpened"
        const val COMPLETION_STATE_NOT_STARTED = "NotStarted"
        const val EXTERNAL_ACTIVITY_START_FAILED_SUMMARY = "外部应用或系统页面启动失败"
        val PACKAGE_NAME_PATTERN = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+$""")
        val DEVICE_CONTROL_SESSION_TOOLS = setOf(
            MobileActionFunctions.OPEN_WIFI_SETTINGS,
            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
            MobileActionFunctions.OPEN_SYSTEM_SETTINGS,
            MobileActionFunctions.SEARCH_MAPS,
            MobileActionFunctions.OPEN_CAMERA,
            MobileActionFunctions.OPEN_APP_BY_NAME,
            MobileActionFunctions.OPEN_APP_INTENT,
            MobileActionFunctions.OPEN_APP_DEEP_TARGET,
        )
    }
}

private fun ToolRequest.clipboardContextData(): Map<String, String> =
    mapOf(
        "toolName" to toolName,
        "privacy" to MessagePrivacy.LocalOnly.name,
        "requiresLocalModel" to true.toString(),
    )

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
    val targetId: String? = null,
)

sealed class AppLaunchResolution {
    data class Resolved(
        val label: String,
        val packageName: String,
    ) : AppLaunchResolution()

    data object NotFound : AppLaunchResolution()
    data object Ambiguous : AppLaunchResolution()
}

data class LaunchableAppEntry(
    val label: String,
    val packageName: String,
)

fun matchLaunchableApp(
    appName: String,
    entries: List<LaunchableAppEntry>,
): AppLaunchResolution {
    val normalizedQuery = appName.normalizedAppLookupKey()
    if (normalizedQuery.isBlank()) return AppLaunchResolution.NotFound
    val aliasedQuery = appLookupAliases[normalizedQuery] ?: normalizedQuery
    val candidates = entries.map { entry ->
        val labelKey = entry.label.normalizedAppLookupKey()
        val packageKey = entry.packageName.normalizedAppLookupKey()
        ResolvedAppCandidate(
            entry = entry,
            labelKey = labelKey,
            packageKey = packageKey,
            aliasKey = appLookupAliases[labelKey],
        )
    }

    fun unique(matches: List<ResolvedAppCandidate>): AppLaunchResolution {
        val distinct = matches.distinctBy { it.entry.packageName }
        return when (distinct.size) {
            0 -> AppLaunchResolution.NotFound
            1 -> distinct.single().entry.toResolution()
            else -> AppLaunchResolution.Ambiguous
        }
    }

    unique(
        candidates.filter { candidate ->
            candidate.labelKey == normalizedQuery ||
                candidate.packageKey == normalizedQuery ||
                candidate.aliasKey == normalizedQuery ||
                candidate.labelKey == aliasedQuery ||
                candidate.packageKey == aliasedQuery
        },
    ).takeIf { it !is AppLaunchResolution.NotFound }?.let { return it }

    unique(
        candidates.filter { candidate ->
            candidate.labelKey.startsWith(normalizedQuery) ||
                candidate.packageKey.startsWith(normalizedQuery) ||
                candidate.labelKey.startsWith(aliasedQuery) ||
                candidate.packageKey.startsWith(aliasedQuery)
        },
    ).takeIf { it !is AppLaunchResolution.NotFound }?.let { return it }

    return unique(
        candidates.filter { candidate ->
            candidate.labelKey.contains(normalizedQuery) ||
                candidate.packageKey.contains(normalizedQuery) ||
                candidate.labelKey.contains(aliasedQuery) ||
                candidate.packageKey.contains(aliasedQuery)
        },
    )
}

private data class ResolvedAppCandidate(
    val entry: LaunchableAppEntry,
    val labelKey: String,
    val packageKey: String,
    val aliasKey: String?,
)

private fun LaunchableAppEntry.toResolution(): AppLaunchResolution.Resolved =
    AppLaunchResolution.Resolved(label = label, packageName = packageName)

private val appLookupAliases = mapOf(
    "taobao" to "淘宝",
    "tb" to "淘宝",
    "pinduoduo" to "拼多多",
    "pdd" to "拼多多",
    "jd" to "京东",
    "jingdong" to "京东",
    "amap" to "高德地图",
    "gaode" to "高德地图",
    "autonavi" to "高德地图",
    "wechat" to "微信",
    "weixin" to "微信",
    "douyin" to "抖音",
    "tik tok" to "抖音",
    "tiktok" to "抖音",
    "xiaohongshu" to "小红书",
    "rednote" to "小红书",
    "bilibili" to "哔哩哔哩",
    "b站" to "哔哩哔哩",
)

private fun String.normalizedAppLookupKey(): String =
    lowercase()
        .replace(Regex("""[\s_\-·.。:：]+"""), "")
        .trim()
