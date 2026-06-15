package com.bytedance.zgx.pocketmind.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bytedance.zgx.pocketmind.action.ActionExecutor
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.device.AndroidCurrentScreenControlProvider
import com.bytedance.zgx.pocketmind.device.AppSearchResultVerifier
import com.bytedance.zgx.pocketmind.device.DeviceControlSessionService
import com.bytedance.zgx.pocketmind.device.PocketMindAccessibilityService
import com.bytedance.zgx.pocketmind.device.ScreenStateReadResult
import com.bytedance.zgx.pocketmind.device.ScreenStateSnapshot
import com.bytedance.zgx.pocketmind.device.UiActionReadResult
import com.bytedance.zgx.pocketmind.device.UiScrollDirection
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult
import java.io.File

class DeviceControlEvalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: "missing-${System.currentTimeMillis()}"
                val provider = AndroidCurrentScreenControlProvider()
                val commandLines = try {
                    when (command) {
                        COMMAND_START_CONTROL_SESSION -> {
                            val reason = intent.getStringExtra(EXTRA_TEXT)
                                ?.takeIf { it.isNotBlank() }
                                ?: DeviceControlSessionService.DEFAULT_REASON
                            val started = DeviceControlSessionService.start(appContext, reason)
                            PocketMindAccessibilityService.showControlProgress(reason)
                            listOf(
                                "command=${command.cleanValue()}",
                                "resultType=available",
                                "status=${if (started) "Succeeded" else "Failed"}",
                                "summary=${if (started) "已启动手机控制会话" else "手机控制会话启动失败"}",
                            )
                        }

                        COMMAND_STOP_CONTROL_SESSION -> {
                            val stopped = DeviceControlSessionService.stop(appContext)
                            PocketMindAccessibilityService.hideControlProgress()
                            listOf(
                                "command=${command.cleanValue()}",
                                "resultType=available",
                                "status=${if (stopped) "Succeeded" else "Failed"}",
                                "summary=${if (stopped) "已停止手机控制会话" else "手机控制会话停止失败"}",
                            )
                        }

                        COMMAND_OPEN_APP_BY_NAME -> ActionExecutor(appContext).execute(
                            ToolRequest(
                                toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
                                arguments = mapOf(
                                    "appName" to intent.getStringExtra(EXTRA_APP_NAME).orEmpty(),
                                ),
                                reason = "Debug eval open app by name.",
                            ),
                        ).toLines(command)

                        COMMAND_OBSERVE -> provider.observeCurrentScreen(
                            maxTextChars = 4_000,
                            maxNodes = 120,
                        ).toLines(command)

                        COMMAND_TAP -> provider.tap(
                            target = intent.getStringExtra(EXTRA_TARGET).orEmpty(),
                            timeoutMillis = intent.timeoutMillis(),
                        ).toLines(command)

                        COMMAND_TYPE_TEXT -> provider.typeText(
                            text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
                            target = intent.getStringExtra(EXTRA_TARGET),
                            timeoutMillis = intent.timeoutMillis(),
                        ).toLines(command)

                        COMMAND_SUBMIT_SEARCH -> provider.submitSearch(
                            timeoutMillis = intent.timeoutMillis(),
                        ).toLines(command)

                        COMMAND_SCROLL -> provider.scroll(
                            direction = UiScrollDirection.fromSchemaValue(
                                intent.getStringExtra(EXTRA_DIRECTION),
                            ) ?: UiScrollDirection.Down,
                            target = intent.getStringExtra(EXTRA_TARGET),
                            timeoutMillis = intent.timeoutMillis(),
                        ).toLines(command)

                        COMMAND_BACK -> provider.pressBack(
                            timeoutMillis = intent.timeoutMillis(),
                        ).toLines(command)

                        COMMAND_WAIT -> provider.waitForScreen(
                            timeoutMillis = intent.timeoutMillis(),
                        ).toLines(
                            command = command,
                            verifySearchQuery = intent.getStringExtra(EXTRA_VERIFY_SEARCH_QUERY),
                            expectedPackageName = intent.getStringExtra(EXTRA_EXPECTED_PACKAGE_NAME),
                            expectedAppName = intent.getStringExtra(EXTRA_EXPECTED_APP_NAME),
                        )

                        else -> listOf(
                            "command=${command.cleanValue()}",
                            "resultType=failed",
                            "reason=unknown_command",
                        )
                    }
                } catch (throwable: Throwable) {
                    listOf(
                        "command=${command.cleanValue()}",
                        "resultType=failed",
                        "reason=${throwable.toResultReason()}",
                    )
                }
                val resultText = (listOf("requestId=${requestId.cleanValue()}") + commandLines)
                    .joinToString(separator = "\n", postfix = "\n")
                File(appContext.filesDir, requestId.resultFileName()).writeText(resultText)
                // Keep a latest-result file for existing manual probes; eval scripts use request files.
                File(appContext.filesDir, LEGACY_RESULT_FILE_NAME).writeText(resultText)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun Intent.timeoutMillis(): Long =
        getLongExtra(EXTRA_TIMEOUT_MILLIS, DEFAULT_TIMEOUT_MILLIS)
            .coerceIn(100L, 10_000L)

    private fun ScreenStateReadResult.toLines(command: String): List<String> =
        when (this) {
            is ScreenStateReadResult.Available -> listOf(
                "command=${command.cleanValue()}",
                "resultType=available",
            ) + snapshot.toLines(prefix = "")

            is ScreenStateReadResult.PermissionDenied -> listOf(
                "command=${command.cleanValue()}",
                "resultType=permission_denied",
                "reason=${reason.cleanValue()}",
            )

            is ScreenStateReadResult.Failed -> listOf(
                "command=${command.cleanValue()}",
                "resultType=failed",
                "reason=${reason.cleanValue()}",
            )
        }

    private fun UiActionReadResult.toLines(
        command: String,
        verifySearchQuery: String? = null,
        expectedPackageName: String? = null,
        expectedAppName: String? = null,
    ): List<String> =
        when (this) {
            is UiActionReadResult.Available -> listOf(
                "command=${command.cleanValue()}",
                "resultType=available",
                "status=${result.status.name}",
                "summary=${result.summary.cleanValue()}",
                "retryable=${result.retryable}",
                "failureKind=${result.failureKind?.name.orEmpty()}",
                "beforeObservationId=${result.before?.id.orEmpty()}",
                "afterObservationId=${result.after?.id.orEmpty()}",
            ) +
                searchVerificationLines(
                    verifySearchQuery = verifySearchQuery,
                    expectedPackageName = expectedPackageName,
                    expectedAppName = expectedAppName,
                    result = this,
                ) +
                result.after.toLines(prefix = "after.")

            is UiActionReadResult.PermissionDenied -> listOf(
                "command=${command.cleanValue()}",
                "resultType=permission_denied",
                "reason=${reason.cleanValue()}",
            )

            is UiActionReadResult.Failed -> listOf(
                "command=${command.cleanValue()}",
                "resultType=failed",
                "reason=${reason.cleanValue()}",
                "retryable=$retryable",
                "failureKind=${failureKind.name}",
            )
        }

    private fun searchVerificationLines(
        verifySearchQuery: String?,
        expectedPackageName: String?,
        expectedAppName: String?,
        result: UiActionReadResult.Available,
    ): List<String> {
        val query = verifySearchQuery?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val verification = AppSearchResultVerifier.verify(
            before = result.result.before,
            after = result.result.after,
            query = query,
            expectedPackageName = expectedPackageName,
            expectedAppName = expectedAppName,
        )
        return listOf(
            "searchVerificationStatus=${if (verification.verified) "verified" else "not_verified"}",
            "searchVerificationEvidence=${verification.evidence.cleanValue()}",
            "searchVerificationSummary=${verification.summary.cleanValue()}",
        )
    }

    private fun ScreenStateSnapshot?.toLines(prefix: String): List<String> {
        if (this == null) {
            return listOf("${prefix}snapshot=false")
        }
        return listOf(
            "${prefix}snapshot=true",
            "${prefix}observationId=${id.cleanValue()}",
            "${prefix}packageName=${packageName.orEmpty().cleanValue()}",
            "${prefix}nodeCount=$nodeCount",
            "${prefix}actionableNodeCount=$actionableNodeCount",
            "${prefix}hasBounds=${nodes.any { node -> node.bounds != null }}",
            "${prefix}hasClickable=${nodes.any { node -> node.clickable }}",
            "${prefix}hasEditable=${nodes.any { node -> node.editable }}",
            "${prefix}hasScrollable=${nodes.any { node -> node.scrollable }}",
            "${prefix}truncated=$truncated",
            "${prefix}textSummary=${textSummary.cleanValue()}",
        )
    }

    private fun ToolResult.toLines(command: String): List<String> =
        listOf(
            "command=${command.cleanValue()}",
            "resultType=tool_result",
            "status=${status.name}",
            "summary=${summary.cleanValue()}",
            "retryable=$retryable",
            "errorCode=${error?.code?.name.orEmpty()}",
            "errorMessage=${error?.message?.cleanValue().orEmpty()}",
        ) + data.entries
            .sortedBy { it.key }
            .map { (key, value) -> "data.${key.cleanValue()}=${value.cleanValue()}" }

    private fun String.cleanValue(): String =
        replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\u0000', ' ')
            .take(MAX_RESULT_VALUE_CHARS)

    private fun String.resultFileName(): String =
        "$RESULT_FILE_PREFIX${fileToken()}.properties"

    private fun String.fileToken(): String =
        map { char ->
            if (
                char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char in '0'..'9' ||
                char == '_' ||
                char == '-' ||
                char == '.'
            ) {
                char
            } else {
                '_'
            }
        }
            .joinToString(separator = "")
            .take(MAX_RESULT_ID_CHARS)
            .ifBlank { "missing" }

    private fun Throwable.toResultReason(): String =
        "${javaClass.simpleName}:${message.orEmpty()}".cleanValue()

    private companion object {
        const val EXTRA_COMMAND = "command"
        const val EXTRA_TARGET = "target"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_TIMEOUT_MILLIS = "timeoutMillis"
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_VERIFY_SEARCH_QUERY = "verifySearchQuery"
        const val EXTRA_EXPECTED_PACKAGE_NAME = "expectedPackageName"
        const val EXTRA_EXPECTED_APP_NAME = "expectedAppName"
        const val EXTRA_APP_NAME = "appName"
        const val COMMAND_START_CONTROL_SESSION = "start_control_session"
        const val COMMAND_STOP_CONTROL_SESSION = "stop_control_session"
        const val COMMAND_OPEN_APP_BY_NAME = "open_app_by_name"
        const val COMMAND_OBSERVE = "observe"
        const val COMMAND_TAP = "tap"
        const val COMMAND_TYPE_TEXT = "type_text"
        const val COMMAND_SUBMIT_SEARCH = "submit_search"
        const val COMMAND_SCROLL = "scroll"
        const val COMMAND_BACK = "back"
        const val COMMAND_WAIT = "wait"
        const val RESULT_FILE_PREFIX = "device_control_eval_result_"
        const val LEGACY_RESULT_FILE_NAME = "device_control_eval_result.properties"
        const val DEFAULT_TIMEOUT_MILLIS = 1_500L
        const val MAX_RESULT_VALUE_CHARS = 8_000
        const val MAX_RESULT_ID_CHARS = 120
    }
}
