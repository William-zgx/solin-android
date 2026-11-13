package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.safety.EgressGate
import com.bytedance.zgx.solin.safety.EgressRunContext
import com.bytedance.zgx.solin.safety.EgressTrigger
import com.bytedance.zgx.solin.safety.SafetyDecision
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.tool.PhoneCompositeOperations
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolCapabilityTag
import com.bytedance.zgx.solin.tool.ToolPermission
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolSpec

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
     * A network-reaching step ([ToolSpec.egressTextArguments]) would carry personal or secret data off
     * the device, or runs after this run already read private data (screen text, clipboard,
     * contacts, …) that an injected instruction could smuggle into the query or link.
     */
    SensitiveNetworkQuery,

    /**
     * Text to type that the user did not ask for, after this run read private data. Typing a link
     * into a browser and pressing Enter is a network send that no other check sees.
     */
    TypedTextNotFromUser,

    /**
     * The run drives GUI automation with the remote vision model and no step of it is confirmed
     * yet. The screenshot is captured and sent by that path, so the run's first action must be
     * user-confirmed before any pixels leave — in critical-only mode too, where the remote-GUI
     * opt-in alone used to be treated as the approval.
     */
    RemoteVisionFirstSend,

    /**
     * The step hands text to another app ([ToolPermission.SendsTextToExternalApp]: share, mail,
     * SMS, calendar or contact draft) and that text looks personal or secret, or was composed
     * after this run read private data and is not the user's own wording. An injected instruction
     * could otherwise move private data into another app's draft without a prompt.
     */
    TextHandedToExternalApp,

    /**
     * The on-device planning model proposed this step ([InitialPlanningMode.LocalModelFirst]), so no
     * rule vouched for the turn being an action: a small model also calls tools on requests the rules
     * treat as chat, and its proposal always asks, in either confirmation mode. Provenance comes from
     * the run trace ([AgentTraceQueries.localPlanningModelProposed]), never from the request itself.
     */
    LocalModelProposedStep
}

private fun CriticalApprovalReason.confirmationReason(toolName: String): String = when (this) {
    CriticalApprovalReason.UnknownTool -> "Tool $toolName is not registered."
    CriticalApprovalReason.ExternalSendOrPayment -> "Tool $toolName sends data out or moves money."
    CriticalApprovalReason.ModelFlaggedSensitive -> "The model flagged this $toolName step as sensitive."
    CriticalApprovalReason.SensitiveNetworkQuery ->
        "This network step carries sensitive text or follows a read of private data in the same run."
    CriticalApprovalReason.TypedTextNotFromUser ->
        "This text was not in the user's request and follows a read of private data in the same run."
    CriticalApprovalReason.RemoteVisionFirstSend ->
        "This is the first step of a remote-vision run; confirming it also approves sending this run's screenshots."
    CriticalApprovalReason.TextHandedToExternalApp ->
        "This step hands text to another app, and the text is sensitive or was composed after a read of private data."
    CriticalApprovalReason.LocalModelProposedStep -> "这一步由端侧模型提出，请确认"
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
    /** True when this run drives GUI automation with the remote vision model (screenshot egress). */
    private val isRemoteVisionDrivingEnabled: (runId: String) -> Boolean = { false }
) {
    private val egressGate = EgressGate(safetyPolicy::containsSensitivePersonalOrSecretContent)

    fun withConfirmationBypass(runId: String, plan: AgentPlan.UseTool): AgentPlan.UseTool = plan.withConfirmationBypassForRun(runId)

    /** True when [request] runs without asking in this run (critical-only mode, no critical reason). */
    fun isAutoApproved(runId: String, request: ToolRequest): Boolean = isCriticalOnlyConfirmationEnabled(runId) && criticalApprovalReason(runId, request) == null

    /** T2/T5: a composite approval never approves its children or supplies fresh user intent. */
    fun canAuthorizeCompositeChild(runId: String, parent: ToolRequest, child: ToolRequest): Boolean {
        val run = traceStore.run(runId) ?: return false
        if (run.state != AgentRunState.ExecutingTool || traceStore.stepHistoryDegraded(runId)) return false
        val allowed = PhoneCompositeOperations.childToolsFor(parent.toolName)
        if (allowed.isEmpty()) return false
        if (traceQueries.latestExecutableRequestId(runId) != parent.id ||
            traceQueries.toolRequestFor(runId, parent.id) != parent
        ) {
            return false
        }
        val sequence = child.id.removePrefix("${parent.id}:child:").toIntOrNull()
        if (!child.id.startsWith("${parent.id}:child:") || sequence == null || sequence !in 1..32) return false
        if (child.toolName !in allowed || toolRegistry.validate(child) != null) return false
        if (parent.sensitivityReason != null && child.sensitivityReason != parent.sensitivityReason) return false
        val expectedPackage = parent.arguments["expectedPackageName"]
        if (!expectedPackage.isNullOrBlank() && child.arguments["expectedPackageName"] != expectedPackage) return false
        return isAutoApproved(runId, child)
    }

    /** A RequireConfirmation decision when [request] is critical in [runId], otherwise null. */
    fun criticalConfirmationDecision(runId: String, request: ToolRequest): SafetyDecision? = criticalApprovalReason(runId, request)?.let { reason ->
        SafetyDecision(
            outcome = SafetyOutcome.RequireConfirmation,
            reason = reason.confirmationReason(request.toolName)
        )
    }

    fun criticalApprovalReason(runId: String, request: ToolRequest): CriticalApprovalReason? {
        val spec = toolRegistry.specFor(request.toolName) ?: return CriticalApprovalReason.UnknownTool
        if (spec.riskLevel == RiskLevel.HighExternalSend || spec.riskLevel == RiskLevel.CriticalDeviceOrPayment) {
            return CriticalApprovalReason.ExternalSendOrPayment
        }
        if (request.sensitivityReason != null) return CriticalApprovalReason.ModelFlaggedSensitive
        val context by lazy {
            if (spec.egressTextArguments.isNotEmpty() || request.toolName == MobileActionFunctions.UI_TYPE_TEXT) {
                val privateRead = runReadPrivateData(runId, before = request.id)
                EgressRunContext(
                    privateReadBefore = privateRead,
                    userInput = if (privateRead) traceStore.run(runId)?.input.orEmpty() else ""
                )
            } else {
                EgressRunContext(privateReadBefore = false, userInput = "")
            }
        }
        val triggers = egressGate.evaluate(
            spec = spec,
            request = request,
            typedText = request.arguments["text"].takeIf { request.toolName == MobileActionFunctions.UI_TYPE_TEXT },
            context = { context }
        )
        triggers.firstOrNull { it != EgressTrigger.TextHandedToExternalApp }?.let { trigger ->
            return trigger.approvalReason()
        }
        if (isRemoteVisionFirstSend(runId, request, spec)) return CriticalApprovalReason.RemoteVisionFirstSend
        if (EgressTrigger.TextHandedToExternalApp in triggers) {
            return EgressTrigger.TextHandedToExternalApp.approvalReason()
        }
        return if (traceQueries.localPlanningModelProposed(runId, request.id)) {
            CriticalApprovalReason.LocalModelProposedStep
        } else {
            null
        }
    }

    private fun EgressTrigger.approvalReason(): CriticalApprovalReason = when (this) {
        EgressTrigger.SensitiveNetworkQuery -> CriticalApprovalReason.SensitiveNetworkQuery
        EgressTrigger.TypedTextNotFromUser -> CriticalApprovalReason.TypedTextNotFromUser
        EgressTrigger.TextHandedToExternalApp -> CriticalApprovalReason.TextHandedToExternalApp
    }

    /**
     * A remote-vision run must confirm one step before its first screenshot leaves the device
     * ([RemoteVisionObservationReplanner] gates on a confirmed step). Critical-only mode otherwise
     * prompts for nothing, so the run's first in-app action is raised to a confirmation here;
     * confirming it is the run's screenshot approval. Once any step is confirmed the gate opens.
     */
    private fun isRemoteVisionFirstSend(
        runId: String,
        request: ToolRequest,
        spec: ToolSpec
    ): Boolean = isRemoteVisionDrivingEnabled(runId) &&
        // ToolCapabilityTag.StartsInAppLoop: the app launches the observe→act loop then drives.
        // search_maps / open_deep_link launch another app and end the loop, so they do not count.
        (spec.capability == ToolCapability.DeviceControl || ToolCapabilityTag.StartsInAppLoop in spec.tags) &&
        traceStore.steps(runId).none { step -> step is AgentStep.UserConfirmed }

    /**
     * Whether an earlier step of [runId] requested a tool that reads private device data.
     * [ToolCapability.LocalCompute] tools mark their output private but read nothing from the device.
     */
    private fun runReadPrivateData(runId: String, before: String): Boolean = traceQueries.toolRequestsFor(runId)
        .takeWhile { request -> request.id != before }
        .any { request ->
            toolRegistry.specFor(request.toolName)?.capability != ToolCapability.LocalCompute &&
                toolRegistry.privateOutputKeysFor(request.toolName).isNotEmpty()
        }

    fun withLowRiskAppControlContinuationBypass(
        runId: String,
        plan: AgentPlan.UseTool,
        skillPlan: SkillPlan?
    ): AgentPlan.UseTool = plan.withLowRiskAppControlContinuationBypassForRun(runId, skillPlan)

    private fun AgentPlan.UseTool.withConfirmationBypassForRun(runId: String): AgentPlan.UseTool {
        val criticalDecision = criticalConfirmationDecision(runId, request)
        // SafetyPolicy allows ConfirmationPolicy.NotRequired tools (web_search, …) even when the
        // model flags them or they follow a private read, so any critical reason raises an Allow
        // plan here, in either mode.
        if (safetyDecision.outcome == SafetyOutcome.Allow && criticalDecision != null) {
            return copy(
                draft = draft.copy(requiresConfirmation = true),
                safetyDecision = criticalDecision
            )
        }
        if (safetyDecision.outcome != SafetyOutcome.RequireConfirmation) return this
        if (!isCriticalOnlyConfirmationEnabled(runId) || criticalDecision != null) return this
        val bypassDecision = SafetyDecision(
            outcome = SafetyOutcome.Allow,
            reason = "Only critical actions are confirmed; ${request.toolName} is not critical."
        )
        return copy(
            draft = draft.copy(requiresConfirmation = false),
            safetyDecision = bypassDecision
        )
    }

    private fun AgentPlan.UseTool.withLowRiskAppControlContinuationBypassForRun(
        runId: String,
        skillPlan: SkillPlan?
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
            reason = "Low-risk app control continuation was allowed after the user's initial confirmation."
        )
        return copy(
            draft = draft.copy(requiresConfirmation = false),
            safetyDecision = bypassDecision
        )
    }

    private fun lowRiskAppControlSkillPlanForRun(runId: String, preferred: SkillPlan?): SkillPlan? = preferred?.takeIf { plan -> plan.isLowRiskAppControlSkill() }
        ?: traceQueries.latestSkillPlan(runId) { plan -> plan.isLowRiskAppControlSkill() }

    private fun AgentPlan.UseTool.exceedsLowRiskAppControlCheckpointForRun(
        runId: String,
        activeSkillPlan: SkillPlan? = skillPlan ?: traceQueries.latestSkillPlan(runId)
    ): Boolean {
        val plan = activeSkillPlan?.takeIf { it.isLowRiskAppControlSkill() } ?: return false
        return request.isLowRiskAppControlContinuationTool() &&
            request.isCheckpointedUiActionTool() &&
            plan.requestedCheckpointedUiActionCount(runId) >= LOW_RISK_APP_CONTROL_UI_ACTION_CHECKPOINT_LIMIT
    }

    private fun ToolRequest.isLowRiskAppControlContinuationTool(): Boolean = toolRegistry.isLowRiskAppControlContinuationTool(this)

    private fun ToolRequest.isCheckpointedUiActionTool(): Boolean = toolRegistry.isCheckpointedUiActionTool(toolName)

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
