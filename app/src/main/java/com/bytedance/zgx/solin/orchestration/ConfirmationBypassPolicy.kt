package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.safety.SafetyDecision
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import java.text.Normalizer

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

    /**
     * Text to type that the user did not ask for, after this run read private data. Typing a link
     * into a browser and pressing Enter is a network send that no other check sees.
     */
    TypedTextNotFromUser,
}

/** Tools that reach the network or an external app with free text, and the argument carrying it. */
private val networkReachingArgument: Map<String, String> = mapOf(
    MobileActionFunctions.WEB_SEARCH to "query",
    MobileActionFunctions.SEARCH_MAPS to "query",
    MobileActionFunctions.OPEN_DEEP_LINK to "uri",
)

private fun CriticalApprovalReason.confirmationReason(toolName: String): String = when (this) {
    CriticalApprovalReason.UnknownTool -> "Tool $toolName is not registered."
    CriticalApprovalReason.ExternalSendOrPayment -> "Tool $toolName sends data out or moves money."
    CriticalApprovalReason.ModelFlaggedSensitive -> "The model flagged this $toolName step as sensitive."
    CriticalApprovalReason.SensitiveNetworkQuery ->
        "This network step carries sensitive text or follows a read of private data in the same run."
    CriticalApprovalReason.TypedTextNotFromUser ->
        "This text was not in the user's request and follows a read of private data in the same run."
}

/** [text] split for [ConfirmationBypassPolicy]'s typed-text check, after NFKC and lowercasing. */
private class UserTextPieces(val asciiTokens: Set<String>, val otherChars: Set<Char>)

private val asciiLetterOrDigitRun = Regex("[a-z]+|[0-9]+")

private fun userTextPieces(text: String): UserTextPieces {
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
    return UserTextPieces(
        asciiTokens = asciiLetterOrDigitRun.findAll(normalized).map { it.value }.toSet(),
        otherChars = normalized.filter { it.code > 0x7F && it.isLetterOrDigit() }.toSet(),
    )
}

/**
 * Decides when a planned tool may skip the user-confirmation prompt.
 *
 * - Critical-only mode (the user setting, on by default): every step runs without a prompt unless
 *   [criticalApprovalReason] names a reason. Critical on-screen controls (pay, send, delete, publish,
 *   order, transfer, authorize) are never tapped by the agent at all — the device-control executor
 *   stops before them and the user taps them — so they need no prompt here.
 * - Confirm-every-step mode: a low-risk app-control skill may continue after the user confirmed
 *   one of its steps, until [LOW_RISK_APP_CONTROL_UI_ACTION_CHECKPOINT_LIMIT] checkpointed UI
 *   actions have been requested, but never past a [criticalApprovalReason].
 *
 * Only a [SafetyOutcome.RequireConfirmation] decision without a [criticalApprovalReason] is ever
 * relaxed; rejections stay rejections. In either mode an [SafetyOutcome.Allow] plan with a
 * [criticalApprovalReason] is raised to [SafetyOutcome.RequireConfirmation].
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
            request.toolName == MobileActionFunctions.UI_TYPE_TEXT &&
                runReadPrivateData(runId, before = request.id) &&
                !isTextFromUser(runId, request.arguments["text"].orEmpty()) ->
                CriticalApprovalReason.TypedTextNotFromUser
            else -> null
        }
    }

    /**
     * Whether an earlier step of [runId] requested a tool that reads private device data.
     * `calculate` marks its output private but reads nothing from the device.
     */
    private fun runReadPrivateData(runId: String, before: String): Boolean =
        traceQueries.toolRequestsFor(runId)
            .takeWhile { request -> request.id != before }
            .any { request ->
                request.toolName != MobileActionFunctions.CALCULATE &&
                    toolRegistry.privateOutputKeysFor(request.toolName).isNotEmpty()
            }

    /**
     * [text] is made only of pieces the user supplied, so it cannot carry new private content:
     * every ASCII letter run and digit run of [text] is a whole token of the run input (so a phone
     * number or address must appear in full, and a single digit never matches inside a longer
     * number), and every other letter or digit, e.g. a CJK character, appears somewhere in the
     * input. Case, punctuation and whitespace are ignored, so "上海酒店" counts for
     * "在携程搜索上海的酒店" and a reordered query still counts.
     */
    private fun isTextFromUser(runId: String, text: String): Boolean {
        val input = userTextPieces(traceStore.run(runId)?.input.orEmpty())
        val typed = userTextPieces(text)
        return input.asciiTokens.containsAll(typed.asciiTokens) && input.otherChars.containsAll(typed.otherChars)
    }

    fun withLowRiskAppControlContinuationBypass(
        runId: String,
        plan: AgentPlan.UseTool,
        skillPlan: SkillPlan?,
    ): AgentPlan.UseTool = plan.withLowRiskAppControlContinuationBypassForRun(runId, skillPlan)

    private fun AgentPlan.UseTool.withConfirmationBypassForRun(runId: String): AgentPlan.UseTool {
        val criticalReason = criticalApprovalReason(runId, request)
        // SafetyPolicy allows ConfirmationPolicy.NotRequired tools (web_search, …) even when the
        // model flags them or they follow a private read, so any critical reason raises an Allow
        // plan here, in either mode.
        if (safetyDecision.outcome == SafetyOutcome.Allow && criticalReason != null) {
            return copy(
                draft = draft.copy(requiresConfirmation = true),
                safetyDecision = SafetyDecision(
                    outcome = SafetyOutcome.RequireConfirmation,
                    reason = criticalReason.confirmationReason(request.toolName),
                ),
            )
        }
        if (safetyDecision.outcome != SafetyOutcome.RequireConfirmation) return this
        if (!isCriticalOnlyConfirmationEnabled(runId) || criticalReason != null) return this
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
        // Confirm-every-step must never be looser than critical-only.
        if (criticalApprovalReason(runId, request) != null) return this
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
