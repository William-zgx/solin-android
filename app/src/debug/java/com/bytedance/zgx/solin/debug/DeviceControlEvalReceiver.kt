package com.bytedance.zgx.solin.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bytedance.zgx.solin.action.ActionExecutor
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.AndroidCurrentScreenControlProvider
import com.bytedance.zgx.solin.device.AppSearchResultVerifier
import com.bytedance.zgx.solin.device.DeviceControlSessionService
import com.bytedance.zgx.solin.device.SolinAccessibilityService
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.ScreenStateSnapshot
import com.bytedance.zgx.solin.device.UiActionReadResult
import com.bytedance.zgx.solin.device.UiActionExecutionResult
import com.bytedance.zgx.solin.device.UiActionFailureKind
import com.bytedance.zgx.solin.device.UiScrollDirection
import com.bytedance.zgx.solin.device.UiTargetKind
import com.bytedance.zgx.solin.device.UiTargetResolver
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
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
                            SolinAccessibilityService.showControlProgress(reason)
                            listOf(
                                "command=${command.cleanValue()}",
                                "resultType=available",
                                "status=${if (started) "Succeeded" else "Failed"}",
                                "summary=${if (started) "已启动手机控制会话" else "手机控制会话启动失败"}",
                            )
                        }

                        COMMAND_STOP_CONTROL_SESSION -> {
                            val stopped = DeviceControlSessionService.stop(appContext)
                            SolinAccessibilityService.hideControlProgress()
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
                        ).toLines(
                            command = command,
                            target = intent.getStringExtra(EXTRA_TARGET),
                        )

                        COMMAND_TYPE_TEXT -> provider.typeText(
                            text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
                            target = intent.getStringExtra(EXTRA_TARGET),
                            timeoutMillis = intent.timeoutMillis(),
                        ).toLines(
                            command = command,
                            target = intent.getStringExtra(EXTRA_TARGET),
                        )

                        COMMAND_SUBMIT_SEARCH -> provider.submitSearch(
                            timeoutMillis = intent.timeoutMillis(),
                        ).toLines(command)

                        COMMAND_SCROLL -> provider.scroll(
                            direction = UiScrollDirection.fromSchemaValue(
                                intent.getStringExtra(EXTRA_DIRECTION),
                            ) ?: UiScrollDirection.Down,
                            target = intent.getStringExtra(EXTRA_TARGET),
                            timeoutMillis = intent.timeoutMillis(),
                        ).toLines(
                            command = command,
                            target = intent.getStringExtra(EXTRA_TARGET),
                        )

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
        target: String? = null,
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
                result.targetResolutionLines(command = command, target = target) +
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

    private fun UiActionExecutionResult.targetResolutionLines(
        command: String,
        target: String?,
    ): List<String> {
        val kind = resolutionKindFor(command, target) ?: return emptyList()
        val snapshot = before ?: return listOf(
            "targetResolution.available=false",
            "targetResolution.kind=${kind.schemaValue}",
            "targetResolution.target=${target.orEmpty().cleanValue()}",
            "targetResolution.reason=missing_before_snapshot",
        )
        val evidence = UiTargetResolver.explain(
            snapshot = snapshot,
            kind = kind,
            target = target,
        )
        val archivedCandidates = evidence.rankedCandidates.take(MAX_TARGET_RESOLUTION_CANDIDATES)
        return listOf(
            "targetResolution.available=true",
            "targetResolution.kind=${evidence.kind.schemaValue}",
            "targetResolution.target=${evidence.target.orEmpty().cleanValue()}",
            "targetResolution.packageName=${evidence.packageName.orEmpty().cleanValue()}",
            "targetResolution.selectedNodeId=${evidence.selectedNodeId.orEmpty().cleanValue()}",
            "targetResolution.failureKind=${evidence.failureKind?.schemaValue.orEmpty()}",
            "targetResolution.candidateCount=${evidence.rankedCandidates.size}",
            "targetResolution.candidateTotalCount=${evidence.rankedCandidates.size}",
            "targetResolution.archivedCandidateCount=${archivedCandidates.size}",
            "targetResolution.candidatesJson=${DeviceControlEvalResultFormatter.candidatesJson(archivedCandidates)}",
        )
    }

    private fun resolutionKindFor(
        command: String,
        target: String?,
    ): UiTargetKind? =
        when (command) {
            COMMAND_TAP -> UiTargetResolver.kindForTarget(target)
            COMMAND_TYPE_TEXT -> UiTargetKind.EditableField
            COMMAND_SUBMIT_SEARCH -> UiTargetKind.SubmitSearch
            COMMAND_SCROLL -> UiTargetResolver.kindForTarget(target) ?: UiTargetKind.ScrollContainer
            else -> null
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
        DeviceControlEvalResultFormatter.cleanValue(this)

    private fun String.resultFileName(): String =
        DeviceControlEvalResultFormatter.resultFileName(this)

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
        const val LEGACY_RESULT_FILE_NAME = "device_control_eval_result.properties"
        const val DEFAULT_TIMEOUT_MILLIS = 1_500L
        const val MAX_TARGET_RESOLUTION_CANDIDATES = 5
    }
}
