package com.bytedance.zgx.pocketmind.safety

import com.bytedance.zgx.pocketmind.tool.ConfirmationPolicy
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolPermission
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolSpec

data class SafetyContext(
    val userConfirmed: Boolean,
)

data class SafetyDecision(
    val outcome: SafetyOutcome,
    val reason: String,
)

enum class SafetyOutcome {
    Allow,
    RequireConfirmation,
    Reject,
}

class SafetyPolicy {
    fun evaluate(
        spec: ToolSpec,
        request: ToolRequest,
        context: SafetyContext,
    ): SafetyDecision {
        if (spec.riskLevel.requiresHardConfirmation() && spec.confirmationPolicy != ConfirmationPolicy.Required) {
            return SafetyDecision(
                outcome = SafetyOutcome.Reject,
                reason = "Tool ${request.toolName} has ${spec.riskLevel} risk and must require confirmation.",
            )
        }

        if (
            ToolPermission.SendsTextToExternalApp in spec.permissions &&
            spec.confirmationPolicy == ConfirmationPolicy.NotRequired
        ) {
            return SafetyDecision(
                outcome = SafetyOutcome.Reject,
                reason = "Tool ${request.toolName} sends text to another app and cannot skip confirmation.",
            )
        }

        if (!context.userConfirmed && spec.confirmationPolicy == ConfirmationPolicy.Required) {
            return SafetyDecision(
                outcome = SafetyOutcome.RequireConfirmation,
                reason = "Tool ${request.toolName} requires user confirmation before execution.",
            )
        }

        return SafetyDecision(
            outcome = SafetyOutcome.Allow,
            reason = "Tool ${request.toolName} is allowed by current safety policy.",
        )
    }

    private fun RiskLevel.requiresHardConfirmation(): Boolean =
        this == RiskLevel.HighExternalSend || this == RiskLevel.CriticalDeviceOrPayment
}
