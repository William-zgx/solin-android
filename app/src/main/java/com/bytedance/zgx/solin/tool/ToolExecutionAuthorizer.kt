package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.safety.SafetyContext
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy

fun interface ToolExecutionAuthorizer {
    fun authorize(request: ToolRequest, userConfirmed: Boolean): ToolResult?
}

class SafetyPolicyToolExecutionAuthorizer(
    private val registry: ToolRegistry,
    private val safetyPolicy: SafetyPolicy = SafetyPolicy(),
) : ToolExecutionAuthorizer {
    override fun authorize(request: ToolRequest, userConfirmed: Boolean): ToolResult? {
        val spec = registry.specFor(request.toolName)
            ?: return request.rejected("Tool ${request.toolName} is not registered for execution.")
        if (spec.riskLevel != RiskLevel.LowReadOnly && spec.confirmationPolicy != ConfirmationPolicy.Required) {
            return request.rejected("Tool ${request.toolName} has ${spec.riskLevel} risk and must require confirmation.")
        }
        val decision = safetyPolicy.evaluate(spec, request, SafetyContext(userConfirmed))
        return when (decision.outcome) {
            SafetyOutcome.Allow -> null
            SafetyOutcome.RequireConfirmation,
            SafetyOutcome.Reject,
            -> request.rejected(decision.reason)
        }
    }
}
