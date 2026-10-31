package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.safety.SafetyDecision
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest

private const val LOW_RISK_APP_CONTROL_UI_ACTION_CHECKPOINT_LIMIT = 5

/**
 * Why a planned tool still needs the user's approval when only critical actions are confirmed.
 * Everything not listed here runs without a confirmation prompt.
 */
enum class CriticalApprovalReason {
    /** Not in the registry: fail closed. */
    UnknownTool,

    /** Sends data out or moves money ([RiskLevel.HighExternalSend] / [RiskLevel.CriticalDeviceOrPayment]). */
    ExternalSendOrPayment,

    /** The planning model marked the step as sensitive (e.g. "涉及支付"). */
    ModelFlaggedSensitive,

    /**
     * A network-reaching step ([networkReachingArgument]) would carry personal or secret data off
     * the device, or runs after this run already read private data (screen text, clipboard,
     * contacts, …) that an injected instruction could smuggle into the query or link.
     */
    SensitiveNetworkQuery,
}

/** Tools that reach the network or an external app with free text, and the argument carrying it. */
private val networkReachingArgument: Map<String, String> = mapOf(
    MobileActionFunctions.WEB_SEARCH to "query",
    MobileActionFunctions.SEARCH_MAPS to "query",
    MobileActionFunctions.OPEN_DEEP_LINK to "uri",
)

/**
 * Decides when a planned tool may skip the user-confirmation prompt.
 *
 * - Critical-only mode (the user setting, on by default): every step runs without a prompt unless
 *   [criticalApprovalReason] names a reason. Critical on-screen controls (pay, send, delete, publish,
 *   order, transfer, authorize) are never tapped by the agent at all — the device-control executor
 *   stops before them and the user taps them — so they need no prompt here.
 * - Confirm-every-step mode: a low-risk app-control skill may continue after the user confirmed
 *   one of its steps, until [LOW_RISK_APP_CONTROL_UI_ACTION_CHECKPOINT_LIMIT] checkpointed UI
 *   actions have been requested.
 *
 * Only a [SafetyOutcome.RequireConfirmation] decision is ever relaxed; rejections stay rejections.
 */
internal class ConfirmationBypassPolicy(
    private val toolRegistry: ToolRegistry,
    private val traceStore: AgentTraceStore,
    private val traceQueries: AgentTraceQueries,
    private val isCriticalOnlyConfirmationEnabled: (runId: String) -> Boolean,
    private val safetyPolicy: SafetyPolicy = SafetyPolicy(),
) {
    fun withConfirmationBypass(runId: String, plan: AgentPlan.UseTool): AgentPlan.UseTool =
        plan.withConfirmationBypassForRun(runId)

    /** True when [request] runs without asking in this run (critical-only mode, no critical reason). */
    fun isAutoApproved(runId: String, request: ToolRequest): Boolean =
        isCriticalOnlyConfirmationEnabled(runId) && criticalApprovalReason(runId, request) == null

    fun criticalApprovalReason(runId: String, request: ToolRequest): CriticalApprovalReason? {
        val spec = toolRegistry.specFor(request.toolName) ?: return CriticalApprovalReason.UnknownTool
        val networkArgument = networkReachingArgument[request.toolName]
        return when {
            spec.riskLevel == RiskLevel.HighExternalSend || spec.riskLevel == RiskLevel.CriticalDeviceOrPayment ->
                CriticalApprovalReason.ExternalSendOrPayment
            request.sensitivityReason != null -> CriticalApprovalReason.ModelFlaggedSensitive
            networkArgument != null && (
                safetyPolicy.containsSensitivePersonalOrSecretContent(request.arguments[networkArgument].orEmpty()) ||
                    runReadPrivateData(runId, before = request.id)
                ) -> CriticalApprovalReason.SensitiveNetworkQuery
            else -> null
        }
    }

    /** Whether an earlier step of [runId] requested a tool whose output is private (LocalOnly). */
    private fun runReadPrivateData(runId: String, before: String): Boolean =
        traceQueries.toolRequestsFor(runId)
            .takeWhile { request -> request.id != before }
            .any { request -> toolRegistry.privateOutputKeysFor(request.toolName).isNotEmpty() }

    fun withLowRiskAppControlContinuationBypass(
        runId: String,
        plan: AgentPlan.UseTool,
        skillPlan: SkillPlan?,
    ): AgentPlan.UseTool = plan.withLowRiskAppControlContinuationBypassForRun(runId, skillPlan)

    private fun AgentPlan.UseTool.withConfirmationBypassForRun(runId: String): AgentPlan.UseTool {
        if (safetyDecision.outcome != SafetyOutcome.RequireConfirmation) return this
        if (!isAutoApproved(runId, request)) return this
        val bypassDecision = SafetyDecision(
            outcome = SafetyOutcome.Allow,
            reason = "Only critical actions are confirmed; ${request.toolName} is not critical.",
        )
        return copy(
            draft = draft.copy(requiresConfirmation = false),
            safetyDecision = bypassDecision,
        )
    }

    private fun AgentPlan.UseTool.withLowRiskAppControlContinuationBypassForRun(
        runId: String,
        skillPlan: SkillPlan?,
    ): AgentPlan.UseTool {
        val activeSkillPlan = lowRiskAppControlSkillPlanForRun(runId, skillPlan ?: this.skillPlan) ?: return this
        if (!request.isLowRiskAppControlContinuationTool()) return this
        if (safetyDecision.outcome != SafetyOutcome.RequireConfirmation) return this
        if (!activeSkillPlan.hasConfirmedToolStep(runId)) return this
        if (exceedsLowRiskAppControlCheckpointForRun(runId, activeSkillPlan)) return this
        val bypassDecision = SafetyDecision(
            outcome = SafetyOutcome.Allow,
            reason = "Low-risk app control continuation was allowed after the user's initial confirmation.",
        )
        return copy(
            draft = draft.copy(requiresConfirmation = false),
            safetyDecision = bypassDecision,
        )
    }

    private fun lowRiskAppControlSkillPlanForRun(runId: String, preferred: SkillPlan?): SkillPlan? =
        preferred?.takeIf { plan -> plan.isLowRiskAppControlSkill() }
            ?: traceQueries.latestSkillPlan(runId) { plan -> plan.isLowRiskAppControlSkill() }

    private fun AgentPlan.UseTool.exceedsLowRiskAppControlCheckpointForRun(
        runId: String,
        activeSkillPlan: SkillPlan? = skillPlan ?: traceQueries.latestSkillPlan(runId),
    ): Boolean {
        val plan = activeSkillPlan?.takeIf { it.isLowRiskAppControlSkill() } ?: return false
        return request.isLowRiskAppControlContinuationTool() &&
            request.isCheckpointedUiActionTool() &&
            plan.requestedCheckpointedUiActionCount(runId) >= LOW_RISK_APP_CONTROL_UI_ACTION_CHECKPOINT_LIMIT
    }

    private fun ToolRequest.isLowRiskAppControlContinuationTool(): Boolean =
        toolRegistry.isLowRiskAppControlContinuationTool(this)

    private fun ToolRequest.isCheckpointedUiActionTool(): Boolean =
        toolRegistry.isCheckpointedUiActionTool(toolName)

    private fun SkillPlan.hasConfirmedToolStep(runId: String): Boolean {
        val stepRequestIds = toolStepRequestIds()
        return traceStore.steps(runId).any { step ->
            step is AgentStep.UserConfirmed && step.requestId in stepRequestIds
        }
    }

    private fun SkillPlan.requestedCheckpointedUiActionCount(runId: String): Int {
        val stepRequestIds = toolStepRequestIds()
        return traceQueries.toolRequestsFor(runId).count { request ->
            request.id in stepRequestIds && request.isCheckpointedUiActionTool()
        }
    }
}
