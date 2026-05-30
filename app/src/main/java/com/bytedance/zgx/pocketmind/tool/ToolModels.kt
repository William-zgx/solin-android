package com.bytedance.zgx.pocketmind.tool

import java.util.UUID

data class ToolSpec(
    val name: String,
    val title: String,
    val description: String,
    val inputSchemaJson: String,
    val capability: ToolCapability,
    val riskLevel: RiskLevel = RiskLevel.MediumDraftOrNavigation,
    val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.Required,
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
    val userVisible: Boolean = true,
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

enum class ConfirmationPolicy {
    Required,
    Optional,
    NotRequired,
}

enum class ToolCapability {
    DeviceSettings,
    ExternalNavigation,
    WebSearch,
    ExternalDraft,
}
