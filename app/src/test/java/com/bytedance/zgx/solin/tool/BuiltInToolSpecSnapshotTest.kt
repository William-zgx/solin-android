package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.action.MobileActionFunctions
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the safety-relevant surface of every built-in [ToolSpec], in registration order.
 *
 * WHY: the built-in spec table is moved between files during refactors, and the list order
 * feeds prompts and the capability matrix. Risk level, confirmation policy, tags, private
 * output keys, continuation policy and permissions are fail-closed safety semantics
 * (AGENTS.md: do not weaken SafetyPolicy / ToolSpec semantics). Any change to this snapshot
 * must be an intentional, reviewed policy change — never a side effect of moving code.
 *
 * `contentHash` covers title, description and both JSON schemas so a pure move that
 * accidentally edits prompt-facing text is caught too.
 */
class BuiltInToolSpecSnapshotTest {
    @Test
    fun builtInToolSpecSafetySemanticsAreStable() {
        val actual = defaultBuiltInToolRegistry.specs().joinToString(separator = "\n") { it.snapshotLine() }
        assertSnapshot(EXPECTED_SNAPSHOT, actual)
    }

    @Test
    fun uiTapConfirmationBypassForProbeTargetsIsStable() {
        val actual = uiTapProbeTargets.joinToString(separator = "\n") { target ->
            val skippable = defaultBuiltInToolRegistry.isLowRiskDeviceActionConfirmationSkippable(
                ToolRequest(
                    toolName = MobileActionFunctions.UI_TAP,
                    arguments = mapOf("target" to target),
                ),
            )
            "$target -> ${if (skippable) "skip" else "confirm"}"
        }
        assertSnapshot(EXPECTED_UI_TAP_PROBES, actual)
    }

    /** On mismatch the full actual text is printed between markers so it can be reviewed and copied. */
    private fun assertSnapshot(expected: String, actual: String) {
        assertEquals(
            "Snapshot changed. Full actual:\nACTUAL_BEGIN\n${actual.trim()}\nACTUAL_END\n",
            expected.trim(),
            actual.trim(),
        )
    }

    private fun ToolSpec.snapshotLine(): String =
        listOf(
            name,
            "capability=$capability",
            "risk=$riskLevel",
            "confirm=$confirmationPolicy",
            "continuation=$resultContinuationPolicy",
            "exec=$executionMode",
            "tags=${tags.map { it.name }.sorted()}",
            "private=${privateOutputKeys.sorted()}",
            "permissions=${permissions.map { it.name }.sorted()}",
            "pending=${pendingArgumentAllowlist.sorted()}",
            "redacted=$redactedResultSummary",
            "runtime=${androidRuntimePermissions.map { "${it.kind}:${it.argumentName}" }}",
            "exactlyOneOf=${exactlyOneOf.map { it.sorted() }}",
            "hint=${planningPromptHint != null}",
            "contentHash=${contentHash()}",
        ).joinToString(" | ")

    private fun ToolSpec.contentHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(title, description, inputSchemaJson, outputSchemaJson, planningPromptHint.orEmpty(), androidRuntimePermissions.joinToString { it.rationale.orEmpty() })
            .forEach { part ->
                digest.update(part.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
        return digest.digest().take(6).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val uiTapProbeTargets = listOf(
            "搜索",
            "返回",
            "设置",
            "发送",
            "删除",
            "确认",
            "提交",
            "提交订单",
            "立即购买",
            "允许",
            "授权",
            "同意",
            "同意并继续",
            "Send",
            "Confirm",
            "Order",
            "Search",
        )

        const val EXPECTED_UI_TAP_PROBES = """
搜索 -> skip
返回 -> skip
设置 -> skip
发送 -> confirm
删除 -> confirm
确认 -> confirm
提交 -> confirm
提交订单 -> confirm
立即购买 -> confirm
允许 -> confirm
授权 -> confirm
同意 -> confirm
同意并继续 -> confirm
Send -> confirm
Confirm -> confirm
Order -> confirm
Search -> skip
"""

        const val EXPECTED_SNAPSHOT = """
open_wifi_settings | capability=DeviceSettings | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[DeviceControlSession, LowRiskDeviceAction, RestoredExternalOutcomePopupSkippable] | private=[] | permissions=[StartsExternalActivity] | pending=[] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=e2b8b03d2781
open_usage_access_settings | capability=DeviceSettings | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[DeviceControlSession] | private=[] | permissions=[StartsExternalActivity] | pending=[] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=4e9609de1784
open_system_settings | capability=DeviceSettings | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[ConditionalLowRiskDeviceAction, ConditionalRestoredExternalOutcomePopupSkippable, DeviceControlSession] | private=[] | permissions=[StartsExternalActivity] | pending=[target] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=56aa391c9ce9
search_maps | capability=ExternalNavigation | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[DeviceControlSession, RestoredExternalOutcomePopupSkippable] | private=[] | permissions=[StartsExternalActivity] | pending=[] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=b260e5a5cd96
web_search | capability=WebSearch | risk=LowReadOnly | confirm=NotRequired | continuation=PublicEvidence | exec=ConcurrentWhenIndependent | tags=[] | private=[] | permissions=[] | pending=[] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=b5738dcad961
compose_email | capability=ExternalDraft | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[] | private=[] | permissions=[SendsTextToExternalApp, StartsExternalActivity] | pending=[] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=4b73a4643d0d
create_calendar_event | capability=ExternalDraft | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[] | private=[] | permissions=[SendsTextToExternalApp, StartsExternalActivity] | pending=[] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=d94cf2165c33
create_contact_draft | capability=ExternalDraft | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[] | private=[] | permissions=[SendsTextToExternalApp, StartsExternalActivity] | pending=[] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=166ad2650aef
query_contacts | capability=DeviceContext | risk=LowReadOnly | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[] | private=[contactCount, contactsJson, query] | permissions=[ReadsContacts, ReadsDeviceContext, RequiresAndroidRuntimePermission] | pending=[] | redacted=已读取联系人摘要 | runtime=[ReadContacts:null] | exactlyOneOf=[] | hint=false | contentHash=a4467ec0e760
open_flashlight_settings | capability=DeviceSettings | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[] | private=[] | permissions=[StartsExternalActivity] | pending=[] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=b23d9c4e341e
schedule_reminder | capability=BackgroundTask | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[] | private=[] | permissions=[PostsNotification, RequiresAndroidRuntimePermission, SchedulesBackgroundWork] | pending=[] | redacted=null | runtime=[PostNotifications:null] | exactlyOneOf=[[delayMinutes, triggerAtMillis]] | hint=true | contentHash=8b72cde47f44
set_system_alarm | capability=ExternalDraft | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[] | private=[] | permissions=[StartsExternalActivity] | pending=[hour, message, minutes, recurrence] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=02f895f35a93
set_system_timer | capability=ExternalDraft | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[] | private=[] | permissions=[StartsExternalActivity] | pending=[lengthSeconds, message] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=bbb28f87e633
configure_periodic_check | capability=BackgroundTask | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[BackgroundSkillAllowed] | private=[] | permissions=[PostsNotification, RequiresAndroidRuntimePermission, SchedulesBackgroundWork] | pending=[enabled, intervalMinutes, minNotificationSpacingMinutes, overdueGraceMinutes, requiresBatteryNotLow, requiresCharging] | redacted=null | runtime=[PostNotifications:null] | exactlyOneOf=[] | hint=false | contentHash=431b3dfbefc4
query_background_tasks | capability=BackgroundTask | risk=LowReadOnly | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[BackgroundSkillAllowed, SequentialLocalContinuation] | private=[activeTaskCount, historyTaskCount, policyJson, tasksJson] | permissions=[ReadsDeviceContext] | pending=[maxCount, scope] | redacted=已读取后台任务摘要 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=ec8d10c3e2f3
read_clipboard | capability=DeviceContext | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[SequentialLocalContinuation] | private=[text] | permissions=[ReadsClipboard, ReadsDeviceContext] | pending=[] | redacted=已读取剪贴板文本 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=28effd9b381e
share_text | capability=ExternalShare | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[] | private=[] | permissions=[SendsTextToExternalApp, StartsExternalActivity] | pending=[] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=f1809f3858eb
open_deep_link | capability=ExternalNavigation | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[] | private=[] | permissions=[StartsExternalActivity] | pending=[] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=75d7700c1253
open_camera | capability=ExternalNavigation | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[DeviceControlSession, LowRiskDeviceAction, RestoredExternalOutcomePopupSkippable] | private=[] | permissions=[StartsExternalActivity] | pending=[] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=7e8a12091974
open_app_by_name | capability=ExternalNavigation | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[DeviceControlSession, LowRiskDeviceAction, OpenAppLaunch, RestoredExternalOutcomePopupSkippable] | private=[] | permissions=[StartsExternalActivity] | pending=[appName, followUpIntent] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=78cd1475f20b
open_app_intent | capability=ExternalNavigation | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[DeviceControlSession, LowRiskDeviceAction, OpenAppLaunch, RestoredExternalOutcomePopupSkippable] | private=[] | permissions=[StartsExternalActivity] | pending=[packageName] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=2dfaa32a62f8
open_app_deep_target | capability=ExternalNavigation | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[DeviceControlSession] | private=[] | permissions=[StartsExternalActivity] | pending=[packageName, targetId] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=38eb5ebb0236
query_calendar_availability | capability=DeviceContext | risk=LowReadOnly | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[] | private=[blocksJson, busyBlockCount, end, freeBlockCount, start] | permissions=[ReadsCalendar, ReadsDeviceContext, RequiresAndroidRuntimePermission] | pending=[end, start] | redacted=已读取日历忙闲摘要 | runtime=[ReadCalendar:null] | exactlyOneOf=[] | hint=false | contentHash=a5f6d0432ad7
query_foreground_app | capability=DeviceContext | risk=LowReadOnly | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[UsageStatsSpecialAccess] | private=[appLabel, lastTimeUsedMillis, packageName] | permissions=[ReadsDeviceContext] | pending=[] | redacted=已读取当前前台应用 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=adad720249a0
query_recent_notifications | capability=DeviceContext | risk=LowReadOnly | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[] | private=[notificationCount, notificationsJson] | permissions=[ReadsDeviceContext] | pending=[maxCount] | redacted=已读取近期通知摘要 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=dbd74f90dad2
query_recent_files | capability=DeviceContext | risk=LowReadOnly | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[] | private=[fileCount, filesJson] | permissions=[ReadsDeviceContext, ReadsFiles, RequiresAndroidRuntimePermission] | pending=[kind, maxCount] | redacted=已读取最近文件摘要 | runtime=[RecentFiles:kind] | exactlyOneOf=[] | hint=false | contentHash=b0e4e4b430a3
read_recent_screenshot_ocr | capability=DeviceContext | risk=MediumDraftOrNavigation | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[SequentialLocalContinuation] | private=[ocrText] | permissions=[ReadsDeviceContext, ReadsFiles, RequiresAndroidRuntimePermission] | pending=[maxCount] | redacted=已读取最近截图 OCR 摘录 | runtime=[RecentImages:null] | exactlyOneOf=[] | hint=false | contentHash=0e9918f66880
read_recent_image_ocr | capability=DeviceContext | risk=MediumDraftOrNavigation | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[SequentialLocalContinuation] | private=[ocrText] | permissions=[ReadsDeviceContext, ReadsFiles, RequiresAndroidRuntimePermission] | pending=[maxCount] | redacted=已读取最近图片 OCR 摘录 | runtime=[RecentImages:null] | exactlyOneOf=[] | hint=false | contentHash=cc976171b0d9
read_current_screen_text | capability=DeviceContext | risk=MediumDraftOrNavigation | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[AccessibilityScreenTextSpecialAccess, SequentialLocalContinuation] | private=[capturedAtMillis, nodeCount, packageName, screenText, structureSummary] | permissions=[ReadsAccessibilityText, ReadsDeviceContext] | pending=[maxChars] | redacted=已读取当前屏幕可访问文本快照 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=2f65ab0b7e89
capture_current_screenshot_ocr | capability=DeviceContext | risk=MediumDraftOrNavigation | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[SequentialLocalContinuation] | private=[ocrBlocksJson, ocrText, screenObservationJson] | permissions=[ReadsAccessibilityText, ReadsDeviceContext, RequiresMediaProjectionConsent] | pending=[captureMode] | redacted=已读取当前屏幕截图 OCR 摘录 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=dab6b8c60634
observe_current_screen | capability=DeviceControl | risk=MediumDraftOrNavigation | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[AccessibilityDeviceControlSpecialAccess, LowRiskAppControlContinuation, LowRiskDeviceAction, SequentialLocalContinuation] | private=[actionableNodeCount, capturedAtMillis, nodeCount, nodesJson, observationId, packageName, screenHeightPx, screenObservationJson, screenWidthPx, screenshotPerception, textSummary] | permissions=[ReadsAccessibilityText, ReadsDeviceContext] | pending=[maxNodes, maxTextChars] | redacted=已观察当前屏幕状态 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=ec02a651d0b8
ui_tap | capability=DeviceControl | risk=MediumDraftOrNavigation | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[AccessibilityDeviceControlSpecialAccess, CheckpointedUiAction, ConditionalLowRiskDeviceAction, LowRiskAppControlContinuation, SequentialLocalContinuation] | private=[afterActionableNodeCount, afterCapturedAtMillis, afterNodeCount, afterNodesJson, afterObservationId, afterPackageName, afterScreenHeightPx, afterScreenObservationJson, afterScreenWidthPx, afterScreenshotPerception, afterTextSummary, afterTruncated, appSearchProgressStage, beforeActionableNodeCount, beforeCapturedAtMillis, beforeNodeCount, beforeNodesJson, beforeObservationId, beforePackageName, beforeScreenHeightPx, beforeScreenObservationJson, beforeScreenWidthPx, beforeScreenshotPerception, beforeTextSummary, beforeTruncated, screenHeightPx, screenObservationDiffSummary, screenWidthPx, searchVerificationEvidence, searchVerificationStatus, summary, target, uiActionOutcome, uiActionOutcomeReason, verificationSummary] | permissions=[PerformsAccessibilityGesture, ReadsAccessibilityText, ReadsDeviceContext] | pending=[expectedPackageName, target, targetPackageName, targetX, targetY, timeoutMillis] | redacted=已执行屏幕点击动作 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=7be6eb7c2116
ui_type_text | capability=DeviceControl | risk=MediumDraftOrNavigation | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[AccessibilityDeviceControlSpecialAccess, CheckpointedUiAction, LowRiskAppControlContinuation, LowRiskDeviceAction, SequentialLocalContinuation] | private=[afterActionableNodeCount, afterCapturedAtMillis, afterNodeCount, afterNodesJson, afterObservationId, afterPackageName, afterScreenHeightPx, afterScreenObservationJson, afterScreenWidthPx, afterScreenshotPerception, afterTextSummary, afterTruncated, appSearchProgressStage, beforeActionableNodeCount, beforeCapturedAtMillis, beforeNodeCount, beforeNodesJson, beforeObservationId, beforePackageName, beforeScreenHeightPx, beforeScreenObservationJson, beforeScreenWidthPx, beforeScreenshotPerception, beforeTextSummary, beforeTruncated, screenHeightPx, screenObservationDiffSummary, screenWidthPx, searchVerificationEvidence, searchVerificationStatus, summary, target, uiActionOutcome, uiActionOutcomeReason, verificationSummary] | permissions=[PerformsAccessibilityGesture, ReadsAccessibilityText, ReadsDeviceContext] | pending=[allowClipboardPasteFallback, expectedPackageName, target, targetPackageName, text, timeoutMillis] | redacted=已执行屏幕输入动作 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=bb77b559445d
ui_submit_search | capability=DeviceControl | risk=MediumDraftOrNavigation | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[AccessibilityDeviceControlSpecialAccess, CheckpointedUiAction, LowRiskAppControlContinuation, LowRiskDeviceAction] | private=[afterActionableNodeCount, afterCapturedAtMillis, afterNodeCount, afterNodesJson, afterObservationId, afterPackageName, afterScreenHeightPx, afterScreenObservationJson, afterScreenWidthPx, afterScreenshotPerception, afterTextSummary, afterTruncated, appSearchProgressStage, beforeActionableNodeCount, beforeCapturedAtMillis, beforeNodeCount, beforeNodesJson, beforeObservationId, beforePackageName, beforeScreenHeightPx, beforeScreenObservationJson, beforeScreenWidthPx, beforeScreenshotPerception, beforeTextSummary, beforeTruncated, screenHeightPx, screenObservationDiffSummary, screenWidthPx, searchVerificationEvidence, searchVerificationStatus, summary, target, uiActionOutcome, uiActionOutcomeReason, verificationSummary] | permissions=[PerformsAccessibilityGesture, ReadsAccessibilityText, ReadsDeviceContext] | pending=[expectedPackageName, targetPackageName, timeoutMillis] | redacted=已执行搜索提交动作 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=cfb0ec20173f
ui_scroll | capability=DeviceControl | risk=MediumDraftOrNavigation | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[AccessibilityDeviceControlSpecialAccess, CheckpointedUiAction, LowRiskAppControlContinuation, LowRiskDeviceAction, SequentialLocalContinuation] | private=[afterActionableNodeCount, afterCapturedAtMillis, afterNodeCount, afterNodesJson, afterObservationId, afterPackageName, afterScreenHeightPx, afterScreenObservationJson, afterScreenWidthPx, afterScreenshotPerception, afterTextSummary, afterTruncated, appSearchProgressStage, beforeActionableNodeCount, beforeCapturedAtMillis, beforeNodeCount, beforeNodesJson, beforeObservationId, beforePackageName, beforeScreenHeightPx, beforeScreenObservationJson, beforeScreenWidthPx, beforeScreenshotPerception, beforeTextSummary, beforeTruncated, screenHeightPx, screenObservationDiffSummary, screenWidthPx, searchVerificationEvidence, searchVerificationStatus, summary, target, uiActionOutcome, uiActionOutcomeReason, verificationSummary] | permissions=[PerformsAccessibilityGesture, ReadsAccessibilityText, ReadsDeviceContext] | pending=[direction, expectedPackageName, target, targetPackageName, timeoutMillis] | redacted=已执行屏幕滚动动作 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=41e285c3d16a
ui_press_back | capability=DeviceControl | risk=MediumDraftOrNavigation | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[AccessibilityDeviceControlSpecialAccess, CheckpointedUiAction, LowRiskAppControlContinuation, LowRiskDeviceAction, SequentialLocalContinuation] | private=[afterActionableNodeCount, afterCapturedAtMillis, afterNodeCount, afterNodesJson, afterObservationId, afterPackageName, afterScreenHeightPx, afterScreenObservationJson, afterScreenWidthPx, afterScreenshotPerception, afterTextSummary, afterTruncated, appSearchProgressStage, beforeActionableNodeCount, beforeCapturedAtMillis, beforeNodeCount, beforeNodesJson, beforeObservationId, beforePackageName, beforeScreenHeightPx, beforeScreenObservationJson, beforeScreenWidthPx, beforeScreenshotPerception, beforeTextSummary, beforeTruncated, screenHeightPx, screenObservationDiffSummary, screenWidthPx, searchVerificationEvidence, searchVerificationStatus, summary, target, uiActionOutcome, uiActionOutcomeReason, verificationSummary] | permissions=[PerformsAccessibilityGesture, ReadsAccessibilityText, ReadsDeviceContext] | pending=[timeoutMillis] | redacted=已执行系统返回动作 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=ebe49714f66f
ui_wait | capability=DeviceControl | risk=MediumDraftOrNavigation | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[AccessibilityDeviceControlSpecialAccess, LowRiskAppControlContinuation, LowRiskDeviceAction, SequentialLocalContinuation] | private=[afterActionableNodeCount, afterCapturedAtMillis, afterNodeCount, afterNodesJson, afterObservationId, afterPackageName, afterScreenHeightPx, afterScreenObservationJson, afterScreenWidthPx, afterScreenshotPerception, afterTextSummary, afterTruncated, appSearchProgressStage, beforeActionableNodeCount, beforeCapturedAtMillis, beforeNodeCount, beforeNodesJson, beforeObservationId, beforePackageName, beforeScreenHeightPx, beforeScreenObservationJson, beforeScreenWidthPx, beforeScreenshotPerception, beforeTextSummary, beforeTruncated, screenHeightPx, screenObservationDiffSummary, screenWidthPx, searchVerificationEvidence, searchVerificationStatus, summary, target, uiActionOutcome, uiActionOutcomeReason, verificationSummary] | permissions=[PerformsAccessibilityGesture, ReadsAccessibilityText, ReadsDeviceContext] | pending=[expectedAppName, expectedPackageName, targetPackageName, timeoutMillis, verifySearchQuery] | redacted=已等待并重新观察当前屏幕 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=19c36889deba
ui_swipe | capability=DeviceControl | risk=MediumDraftOrNavigation | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[AccessibilityDeviceControlSpecialAccess, CheckpointedUiAction, LowRiskAppControlContinuation, LowRiskDeviceAction, SequentialLocalContinuation] | private=[afterActionableNodeCount, afterCapturedAtMillis, afterNodeCount, afterNodesJson, afterObservationId, afterPackageName, afterScreenHeightPx, afterScreenObservationJson, afterScreenWidthPx, afterScreenshotPerception, afterTextSummary, afterTruncated, appSearchProgressStage, beforeActionableNodeCount, beforeCapturedAtMillis, beforeNodeCount, beforeNodesJson, beforeObservationId, beforePackageName, beforeScreenHeightPx, beforeScreenObservationJson, beforeScreenWidthPx, beforeScreenshotPerception, beforeTextSummary, beforeTruncated, screenHeightPx, screenObservationDiffSummary, screenWidthPx, searchVerificationEvidence, searchVerificationStatus, summary, target, uiActionOutcome, uiActionOutcomeReason, verificationSummary] | permissions=[PerformsAccessibilityGesture, ReadsAccessibilityText, ReadsDeviceContext] | pending=[durationMillis, endXNorm, endYNorm, expectedPackageName, startXNorm, startYNorm, targetPackageName, timeoutMillis] | redacted=已执行屏幕滑动动作 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=524b748bacd3
ui_long_press | capability=DeviceControl | risk=MediumDraftOrNavigation | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[AccessibilityDeviceControlSpecialAccess, CheckpointedUiAction, LowRiskAppControlContinuation, LowRiskDeviceAction, SequentialLocalContinuation] | private=[afterActionableNodeCount, afterCapturedAtMillis, afterNodeCount, afterNodesJson, afterObservationId, afterPackageName, afterScreenHeightPx, afterScreenObservationJson, afterScreenWidthPx, afterScreenshotPerception, afterTextSummary, afterTruncated, appSearchProgressStage, beforeActionableNodeCount, beforeCapturedAtMillis, beforeNodeCount, beforeNodesJson, beforeObservationId, beforePackageName, beforeScreenHeightPx, beforeScreenObservationJson, beforeScreenWidthPx, beforeScreenshotPerception, beforeTextSummary, beforeTruncated, screenHeightPx, screenObservationDiffSummary, screenWidthPx, searchVerificationEvidence, searchVerificationStatus, summary, target, uiActionOutcome, uiActionOutcomeReason, verificationSummary] | permissions=[PerformsAccessibilityGesture, ReadsAccessibilityText, ReadsDeviceContext] | pending=[expectedPackageName, holdMillis, targetPackageName, timeoutMillis, xNorm, yNorm] | redacted=已执行屏幕长按动作 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=a7c583417df9
ui_press_key | capability=DeviceControl | risk=MediumDraftOrNavigation | confirm=Required | continuation=LocalEvidence | exec=Sequential | tags=[AccessibilityDeviceControlSpecialAccess, CheckpointedUiAction, LowRiskAppControlContinuation, LowRiskDeviceAction, SequentialLocalContinuation] | private=[afterActionableNodeCount, afterCapturedAtMillis, afterNodeCount, afterNodesJson, afterObservationId, afterPackageName, afterScreenHeightPx, afterScreenObservationJson, afterScreenWidthPx, afterScreenshotPerception, afterTextSummary, afterTruncated, appSearchProgressStage, beforeActionableNodeCount, beforeCapturedAtMillis, beforeNodeCount, beforeNodesJson, beforeObservationId, beforePackageName, beforeScreenHeightPx, beforeScreenObservationJson, beforeScreenWidthPx, beforeScreenshotPerception, beforeTextSummary, beforeTruncated, screenHeightPx, screenObservationDiffSummary, screenWidthPx, searchVerificationEvidence, searchVerificationStatus, summary, target, uiActionOutcome, uiActionOutcomeReason, verificationSummary] | permissions=[PerformsAccessibilityGesture, ReadsAccessibilityText, ReadsDeviceContext] | pending=[expectedPackageName, key, targetPackageName, timeoutMillis] | redacted=已执行系统按键动作 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=34564830f8c0
cancel_reminder | capability=BackgroundTask | risk=MediumDraftOrNavigation | confirm=Required | continuation=None | exec=Sequential | tags=[] | private=[] | permissions=[SchedulesBackgroundWork] | pending=[taskId] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=608d6b288953
ask_user | capability=DeviceContext | risk=LowReadOnly | confirm=NotRequired | continuation=LocalEvidence | exec=Sequential | tags=[UxInteraction] | private=[] | permissions=[] | pending=[] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=92a02c54a244
note | capability=Orchestration | risk=LowReadOnly | confirm=NotRequired | continuation=LocalEvidence | exec=Sequential | tags=[Planning] | private=[content] | permissions=[] | pending=[] | redacted=已记录笔记 | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=e9386e3e72aa
finish | capability=Orchestration | risk=LowReadOnly | confirm=NotRequired | continuation=None | exec=Sequential | tags=[Planning] | private=[] | permissions=[] | pending=[] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=f2846e24963a
take_over | capability=Orchestration | risk=LowReadOnly | confirm=NotRequired | continuation=LocalEvidence | exec=Sequential | tags=[Planning, UxInteraction] | private=[] | permissions=[] | pending=[] | redacted=null | runtime=[] | exactlyOneOf=[] | hint=false | contentHash=3083f6ba8293
"""
    }
}
