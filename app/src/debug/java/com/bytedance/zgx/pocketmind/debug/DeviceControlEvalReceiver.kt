package com.bytedance.zgx.pocketmind.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bytedance.zgx.pocketmind.device.AndroidCurrentScreenControlProvider
import com.bytedance.zgx.pocketmind.device.ScreenStateReadResult
import com.bytedance.zgx.pocketmind.device.ScreenStateSnapshot
import com.bytedance.zgx.pocketmind.device.UiActionReadResult
import com.bytedance.zgx.pocketmind.device.UiScrollDirection
import java.io.File

class DeviceControlEvalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
                val provider = AndroidCurrentScreenControlProvider()
                val commandLines = when (command) {
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
                    ).toLines(command)

                    else -> listOf(
                        "command=${command.cleanValue()}",
                        "resultType=failed",
                        "reason=unknown_command",
                    )
                }
                File(appContext.filesDir, RESULT_FILE_NAME).writeText(
                    (listOf("requestId=${requestId.cleanValue()}") + commandLines)
                        .joinToString(separator = "\n", postfix = "\n"),
                )
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

    private fun UiActionReadResult.toLines(command: String): List<String> =
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
            ) + result.after.toLines(prefix = "after.")

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

    private fun String.cleanValue(): String =
        replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\u0000', ' ')
            .take(MAX_RESULT_VALUE_CHARS)

    private companion object {
        const val EXTRA_COMMAND = "command"
        const val EXTRA_TARGET = "target"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_TIMEOUT_MILLIS = "timeoutMillis"
        const val EXTRA_REQUEST_ID = "requestId"
        const val COMMAND_OBSERVE = "observe"
        const val COMMAND_TAP = "tap"
        const val COMMAND_TYPE_TEXT = "type_text"
        const val COMMAND_SCROLL = "scroll"
        const val COMMAND_BACK = "back"
        const val COMMAND_WAIT = "wait"
        const val RESULT_FILE_NAME = "device_control_eval_result.properties"
        const val DEFAULT_TIMEOUT_MILLIS = 1_500L
        const val MAX_RESULT_VALUE_CHARS = 8_000
    }
}
