package com.bytedance.zgx.pocketmind.tool

import java.util.UUID

const val TOOL_NAME_ONLY_OUTPUT_SCHEMA_JSON =
    """{"type":"object","required":["toolName"],"properties":{"toolName":{"type":"string","minLength":1}},"additionalProperties":false}"""

const val MAX_SHARE_TEXT_CHARS = 4_000
const val MAX_SHARE_TITLE_CHARS = 120

data class ToolSpec(
    val name: String,
    val title: String,
    val description: String,
    val inputSchemaJson: String,
    val outputSchemaJson: String = TOOL_NAME_ONLY_OUTPUT_SCHEMA_JSON,
    val capability: ToolCapability,
    val permissions: Set<ToolPermission> = emptySet(),
    val riskLevel: RiskLevel = RiskLevel.MediumDraftOrNavigation,
    val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.Required,
    val pendingArgumentAllowlist: Set<String> = emptySet(),
    val privateOutputKeys: Set<String> = emptySet(),
    val redactedResultSummary: String? = null,
    val resultContinuationPolicy: ToolResultContinuationPolicy = ToolResultContinuationPolicy.None,
)

data class ToolRequest(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val arguments: Map<String, String> = emptyMap(),
    val reason: String = "",
)

data class ToolResult(
    val requestId: String,
    val status: ToolStatus,
    val summary: String,
    val data: Map<String, String> = emptyMap(),
    val error: ToolError? = null,
    val retryable: Boolean = false,
    val userVisible: Boolean = true,
)

const val UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX =
    "已打开外部界面，但无法确认目标应用中的后续操作是否完成"
const val EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX =
    "外部动作结果已由用户确认"

fun ToolResult.isUnverifiedExternalLaunch(): Boolean =
    status == ToolStatus.Succeeded &&
        data["completionState"] == "ExternalActivityOpened" &&
        data["completionVerified"] == "false" &&
        data["externalOutcome"] == "Unknown" &&
        data["externalOutcomeSource"] == "Unknown"

fun ToolResult.isUserConfirmedCompletedExternalOutcome(): Boolean =
    status == ToolStatus.Succeeded &&
        data["completionState"] == "ExternalActivityOpened" &&
        data["completionVerified"] == "true" &&
        data["externalOutcome"] == "Completed" &&
        data["externalOutcomeSource"] == "UserConfirmed"

fun ToolResult.unverifiedExternalLaunchSummary(): String =
    "$UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX：$summary"

data class ToolError(
    val code: ToolErrorCode,
    val message: String,
)

enum class ToolStatus {
    Succeeded,
    Failed,
    Rejected,
    Cancelled,
}

enum class RiskLevel {
    LowReadOnly,
    MediumDraftOrNavigation,
    HighExternalSend,
    CriticalDeviceOrPayment,
}

enum class ToolPermission {
    None,
    StartsExternalActivity,
    SendsTextToExternalApp,
    ReadsDeviceContext,
    ReadsClipboard,
    ReadsCalendar,
    ReadsContacts,
    ReadsFiles,
    ReadsAccessibilityText,
    RequiresAndroidRuntimePermission,
    RequiresMediaProjectionConsent,
    SchedulesBackgroundWork,
    PostsNotification,
}

enum class ConfirmationPolicy {
    Required,
    Optional,
    NotRequired,
}

enum class ToolResultContinuationPolicy {
    None,
    PublicEvidence,
    LocalEvidence,
}

enum class ToolCapability {
    DeviceSettings,
    ExternalNavigation,
    WebSearch,
    ExternalDraft,
    BackgroundTask,
    DeviceContext,
    ExternalShare,
}

private val publicEvidenceBatchDisallowedPermissions = setOf(
    ToolPermission.StartsExternalActivity,
    ToolPermission.SendsTextToExternalApp,
    ToolPermission.ReadsDeviceContext,
    ToolPermission.ReadsClipboard,
    ToolPermission.ReadsCalendar,
    ToolPermission.ReadsContacts,
    ToolPermission.ReadsFiles,
    ToolPermission.ReadsAccessibilityText,
    ToolPermission.RequiresAndroidRuntimePermission,
    ToolPermission.RequiresMediaProjectionConsent,
    ToolPermission.SchedulesBackgroundWork,
    ToolPermission.PostsNotification,
)

fun ToolSpec.isPublicEvidenceBatchEligible(): Boolean =
    resultContinuationPolicy == ToolResultContinuationPolicy.PublicEvidence &&
        confirmationPolicy == ConfirmationPolicy.NotRequired &&
        riskLevel == RiskLevel.LowReadOnly &&
        privateOutputKeys.isEmpty() &&
        permissions.none { permission -> permission in publicEvidenceBatchDisallowedPermissions }

private val remoteModelPlanningCapabilities = setOf(
    ToolCapability.DeviceSettings,
    ToolCapability.ExternalNavigation,
    ToolCapability.ExternalDraft,
    ToolCapability.ExternalShare,
    ToolCapability.BackgroundTask,
)

private val remoteModelPlanningDisallowedPermissions = setOf(
    ToolPermission.ReadsDeviceContext,
    ToolPermission.ReadsClipboard,
    ToolPermission.ReadsCalendar,
    ToolPermission.ReadsContacts,
    ToolPermission.ReadsFiles,
    ToolPermission.ReadsAccessibilityText,
    ToolPermission.RequiresMediaProjectionConsent,
)

fun ToolSpec.isRemoteModelPlanningEligible(): Boolean =
    isPublicEvidenceBatchEligible() ||
        (
            privateOutputKeys.isEmpty() &&
                resultContinuationPolicy != ToolResultContinuationPolicy.LocalEvidence &&
                riskLevel != RiskLevel.CriticalDeviceOrPayment &&
                confirmationPolicy == ConfirmationPolicy.Required &&
                capability in remoteModelPlanningCapabilities &&
                permissions.none { permission -> permission in remoteModelPlanningDisallowedPermissions }
            )

fun ToolRequest.succeeded(
    summary: String,
    data: Map<String, String> = emptyMap(),
): ToolResult =
    ToolResult(
        requestId = id,
        status = ToolStatus.Succeeded,
        summary = summary,
        data = data,
    )

fun ToolRequest.failed(
    code: ToolErrorCode,
    summary: String,
    retryable: Boolean = true,
    data: Map<String, String> = emptyMap(),
): ToolResult =
    ToolResult(
        requestId = id,
        status = ToolStatus.Failed,
        summary = summary,
        data = data,
        error = ToolError(code, summary),
        retryable = retryable,
    )

fun ToolRequest.rejected(
    summary: String,
    data: Map<String, String> = mapOf("toolName" to toolName),
): ToolResult =
    ToolResult(
        requestId = id,
        status = ToolStatus.Rejected,
        summary = summary,
        data = data,
        error = ToolError(ToolErrorCode.InvalidRequest, summary),
        retryable = false,
    )

fun ToolRequest.cancelled(
    summary: String,
    data: Map<String, String> = mapOf("toolName" to toolName),
): ToolResult =
    ToolResult(
        requestId = id,
        status = ToolStatus.Cancelled,
        summary = summary,
        data = data,
        error = ToolError(ToolErrorCode.UserCancelled, summary),
        retryable = false,
    )

enum class ToolErrorCode {
    UnknownTool,
    InvalidRequest,
    InvalidResult,
    MissingArgument,
    PermissionDenied,
    NoActivityFound,
    ExecutionFailed,
    UserCancelled,
}
