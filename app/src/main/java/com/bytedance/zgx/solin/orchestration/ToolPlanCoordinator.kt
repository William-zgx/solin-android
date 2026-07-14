package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCapabilityProfile
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.ModelToolOutputParseResult
import com.bytedance.zgx.solin.safety.SafetyContext
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.skill.SkillModelOutputProgression
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.skill.SkillRuntime
import com.bytedance.zgx.solin.skill.SkillRunProgressor
import com.bytedance.zgx.solin.skill.SkillStep
import com.bytedance.zgx.solin.skill.SkillToolResultProgression
import com.bytedance.zgx.solin.skill.validateStructure
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.tool.ToolPermission
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.isEligibleForParallelBatch
import com.bytedance.zgx.solin.tool.isUnverifiedExternalLaunch
import com.bytedance.zgx.solin.tool.isUserConfirmedCompletedExternalOutcome
import com.bytedance.zgx.solin.tool.rejected

internal const val DEVICE_CONTROL_FAILURE_RECOVERY_REASON = "Device control failure recovery checkpoint."

/**
 * Result of observation → next-tool planning.
 * Hoisted from [AgentLoopRuntime] so the coordinator and runtime share one type.
 */
internal sealed class NextObservationPlan {
    data object None : NextObservationPlan()
    data class Planned(
        val plan: AgentPlan.UseTool,
    ) : NextObservationPlan()

    data class Rejected(
        val reason: String,
    ) : NextObservationPlan()
}

/**
 * Observation → next-tool planning collaborator for [AgentLoopRuntime].
 *
 * Owns deterministic skill progression, continuation-cursor restore, observation replan,
 * device-control failure recovery, and model-inline tool-call planning. Fail-closed
 * safety / budget / skill-auth checks stay compatible with the prior runtime-local path.
 */
internal class ToolPlanCoordinator(
    private val toolRegistry: ToolRegistry,
    private val skillRuntime: SkillRuntime,
    private val skillProgressor: SkillRunProgressor,
    private val observationReplanner: AgentObservationReplanner,
    private val actionPlanningRuntime: ActionPlanningRuntime,
    private val traceStore: AgentTraceStore,
    private val safetyPolicy: SafetyPolicy,
    private val runBudget: AgentRunBudget,
    private val host: Host,
) {
    /**
     * Runtime-owned lookups and side effects the coordinator must not re-implement.
     * Keeps confirmation-bypass policy and skill-auth validation on the runtime facade.
     */
    interface Host {
        fun latestSkillPlan(runId: String): SkillPlan?
        fun latestModelDrivenAppSearchSkillPlan(runId: String): SkillPlan?
        fun invalidSkillPlanReason(skillPlan: SkillPlan?): String?
        fun invalidSkillPlanRejection(request: ToolRequest, skillPlan: SkillPlan?): ToolResult?
        fun toolRequestsFor(runId: String): List<ToolRequest>
        fun toolRequestFor(runId: String, requestId: String): ToolRequest?
        fun plannedSequentialSegmentCount(runId: String): Int
        fun nextSequentialSegmentInput(run: AgentRun, completedSegmentCount: Int): String?
        fun hasMobileActionPlanningModel(installedCapabilityProfiles: List<ModelCapabilityProfile>): Boolean
        fun installedCapabilityProfiles(runId: String): List<ModelCapabilityProfile>
        fun valueFreeCompletedStepFrontiers(runId: String): Set<String>
        fun withConfirmationBypass(runId: String, plan: AgentPlan.UseTool): AgentPlan.UseTool
        fun withLowRiskAppControlContinuationBypass(
            runId: String,
            plan: AgentPlan.UseTool,
            skillPlan: SkillPlan?,
        ): AgentPlan.UseTool
        fun auditRejectedTool(runId: String, result: ToolResult)
    }

    fun planNextToolAfterObservation(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): NextObservationPlan {
        host.latestSkillPlan(run.id)?.let { skillPlan ->
            host.invalidSkillPlanReason(skillPlan)?.let { reason ->
                val requestedRequestIds = host.toolRequestsFor(run.id).mapTo(mutableSetOf()) { toolRequest ->
                    toolRequest.id
                }
                return rejectNextToolPlan(
                    run.id,
                    requestForSkillProgressionRejection(skillPlan, requestedRequestIds, reason),
                )
            }
        }
        planNextToolStepFromCurrentSkill(run, result)?.let { nextPlan ->
            return nextPlan
        }
        val priorRequests = host.toolRequestsFor(run.id)
        val completedSegmentCount = host.plannedSequentialSegmentCount(run.id)
        planNextToolFromContinuationCursor(run, request)?.let { nextPlan ->
            return nextPlan
        }
        planNextCompositeSkillSegment(
            runId = run.id,
            input = host.nextSequentialSegmentInput(run, completedSegmentCount),
        )?.let { nextPlan ->
            return nextPlan
        }
        val replan = observationReplanner.planNext(
            AgentObservationReplanContext(
                run = run,
                previousRequest = request,
                observedResult = result,
                priorRequests = priorRequests,
                nextActionInput = traceStore.nextActionInput(run.id),
                completedSegmentCount = completedSegmentCount,
            ),
        ) ?: return NextObservationPlan.None
        if (
            replan.plannedByModel &&
            !host.hasMobileActionPlanningModel(
                installedCapabilityProfiles = host.installedCapabilityProfiles(run.id),
            )
        ) {
            return failMissingModelNextPlan(run.id, AgentPlan.MissingModel(ModelCapability.MobileAction))
        }
        val skillPlan = skillRuntime.plan(replan.input ?: run.input, replan.draft, replan.request)
        return buildNextToolPlan(
            runId = run.id,
            request = replan.request,
            draft = replan.draft,
            plannedByModel = replan.plannedByModel,
            fallbackReason = replan.fallbackReason,
            skillPlan = skillPlan,
        )
    }

    fun planModelDeviceControlRecoveryAfterFailure(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): NextObservationPlan? {
        host.latestSkillPlan(run.id)?.let { skillPlan ->
            if (
                !skillPlan.isModelDrivenAppSearchSkill() &&
                !(skillPlan.isSingleToolStepPlan() && host.latestModelDrivenAppSearchSkillPlan(run.id) != null)
            ) {
                return null
            }
        }
        if (!result.hasLocalObservationEvidenceForPlanning()) return null
        val priorRequests = host.toolRequestsFor(run.id)
        val completedSegmentCount = host.plannedSequentialSegmentCount(run.id)
        val replan = observationReplanner.planNext(
            AgentObservationReplanContext(
                run = run,
                previousRequest = request,
                observedResult = result,
                priorRequests = priorRequests,
                nextActionInput = traceStore.nextActionInput(run.id),
                completedSegmentCount = completedSegmentCount,
            ),
        ) ?: return null
        if (
            replan.plannedByModel &&
            !host.hasMobileActionPlanningModel(
                installedCapabilityProfiles = host.installedCapabilityProfiles(run.id),
            )
        ) {
            return failMissingModelNextPlan(run.id, AgentPlan.MissingModel(ModelCapability.MobileAction))
        }
        val skillPlan = skillRuntime.plan(replan.input ?: run.input, replan.draft, replan.request)
        return buildNextToolPlan(
            runId = run.id,
            request = replan.request,
            draft = replan.draft,
            plannedByModel = replan.plannedByModel,
            fallbackReason = replan.fallbackReason,
            skillPlan = skillPlan,
        )
    }

    fun planSafeDeviceControlRecoveryAfterFailure(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): NextObservationPlan {
        val recoveryCount = host.toolRequestsFor(run.id).count { prior ->
            prior.reason == DEVICE_CONTROL_FAILURE_RECOVERY_REASON
        }
        if (recoveryCount >= 2) return NextObservationPlan.None
        val failureKind = result.data["failureKind"].orEmpty()
        if (failureKind == "permission_missing") return NextObservationPlan.None
        val nextToolName = when (request.toolName) {
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN -> MobileActionFunctions.UI_WAIT
            MobileActionFunctions.UI_WAIT -> MobileActionFunctions.OBSERVE_CURRENT_SCREEN
            else -> MobileActionFunctions.OBSERVE_CURRENT_SCREEN
        }
        val arguments = when (nextToolName) {
            MobileActionFunctions.UI_WAIT -> mapOf("timeoutMillis" to "500")
            else -> mapOf(
                "maxTextChars" to "2000",
                "maxNodes" to "50",
            )
        }
        val title = when (nextToolName) {
            MobileActionFunctions.UI_WAIT -> "等待屏幕稳定"
            else -> "重新观察当前屏幕"
        }
        val draft = ActionDraft(
            functionName = nextToolName,
            title = title,
            summary = "上一屏幕控制动作失败，将先$title 以便重新规划；不会读取截图像素或发送远程。",
            parameters = arguments,
        )
        val recoveryRequest = ToolRequest(
            toolName = nextToolName,
            arguments = arguments,
            reason = DEVICE_CONTROL_FAILURE_RECOVERY_REASON,
        )
        val skillPlan = skillRuntime.plan(run.input, draft, recoveryRequest)
        return buildNextToolPlan(
            runId = run.id,
            request = recoveryRequest,
            draft = draft,
            plannedByModel = false,
            fallbackReason = "device control failure recovery",
            skillPlan = skillPlan,
        )
    }

    fun planNextToolFromContinuationCursor(
        run: AgentRun,
        request: ToolRequest,
    ): NextObservationPlan? {
        val cursor = traceStore.continuationCursor(run.id) ?: return null
        if (cursor.sourceRequestId != request.id) return null
        val completedSegmentCount = host.plannedSequentialSegmentCount(run.id)
        if (cursor.completedSegmentCount != completedSegmentCount) {
            return rejectNextToolPlan(
                run.id,
                cursor.request.rejected("Restored continuation cursor does not match the Agent trace."),
            )
        }
        return buildNextToolPlan(
            runId = run.id,
            request = cursor.request,
            draft = cursor.draft,
            plannedByModel = cursor.plannedByModel,
            fallbackReason = cursor.fallbackReason,
            skillPlan = cursor.skillPlan,
        )
    }

    fun recordContinuationCursorForUnverifiedExternalLaunch(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ) {
        if (!result.isUnverifiedExternalLaunch()) return
        val cursor = traceStore.continuationCursor(run.id) ?: return
        if (cursor.sourceRequestId != request.id) return
        if (cursor.completedSegmentCount != host.plannedSequentialSegmentCount(run.id)) return
        val alreadyRecorded = traceStore.steps(run.id).any { step ->
            step is AgentStep.ContinuationCursorRecorded &&
                step.cursor.sourceRequestId == cursor.sourceRequestId
        }
        if (alreadyRecorded) return
        traceStore.appendStep(run.id, AgentStep.ContinuationCursorRecorded(cursor))
    }

    fun planNextOpenAppUiSearchStepAfterUnverifiedLaunch(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): NextObservationPlan? {
        if (!result.isUnverifiedExternalLaunch()) return null
        if (!toolRegistry.isOpenAppLaunchTool(request.toolName)) return null
        val skillPlan = host.latestSkillPlan(run.id) ?: return null
        if (!skillPlan.manifest.continuesAfterUnverifiedOpenAppLaunch) return null
        val requestBelongsToSkill = skillPlan.steps
            .filterIsInstance<SkillStep.ToolStep>()
            .any { step -> step.request.id == request.id && step.request.toolName == request.toolName }
        if (!requestBelongsToSkill) return null
        val nextPlan = planNextToolStepFromCurrentSkill(run, result) ?: return null
        return nextPlan.takeIf { plan ->
            plan !is NextObservationPlan.Planned ||
                toolRegistry.isLowRiskAppControlContinuationTool(plan.plan.request)
        }
    }

    fun planNextLowRiskAppControlSkillStepBeforeContinuation(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): NextObservationPlan? {
        if (!toolRegistry.isLowRiskAppControlContinuationTool(request)) return null
        val skillPlan = host.latestSkillPlan(run.id) ?: return null
        if (!skillPlan.isLowRiskAppControlSkill()) return null
        if (!skillPlan.hasToolStep(request)) return null
        host.invalidSkillPlanReason(skillPlan)?.let { reason ->
            val requestedRequestIds = host.toolRequestsFor(run.id).mapTo(mutableSetOf()) { toolRequest ->
                toolRequest.id
            }
            return rejectNextToolPlan(
                run.id,
                requestForSkillProgressionRejection(skillPlan, requestedRequestIds, reason),
            )
        }
        val nextPlan = planNextToolStepFromCurrentSkill(run, result) ?: return null
        return nextPlan.takeIf { plan ->
            plan !is NextObservationPlan.Planned ||
                toolRegistry.isLowRiskAppControlContinuationTool(plan.plan.request) ||
                plan.plan.requiresUserConfirmation()
        }
    }

    fun planNextCompositeSkillSegmentBeforeContinuation(
        run: AgentRun,
        canPlanNextToolBeforeModel: Boolean,
        blocksSequentialTail: Boolean,
    ): NextObservationPlan? {
        if (canPlanNextToolBeforeModel) return null
        if (blocksSequentialTail) return null
        val completedSegmentCount = host.plannedSequentialSegmentCount(run.id)
        return planNextCompositeSkillSegment(
            runId = run.id,
            input = host.nextSequentialSegmentInput(run, completedSegmentCount),
        )
    }

    fun canPlanNextToolAfterObservation(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): Boolean {
        if (!startsExternalActivity(request)) return true
        return result.isUserConfirmedCompletedExternalOutcome() ||
            (
                toolRegistry.isLowRiskDeviceActionConfirmationSkippable(request) &&
                    traceStore.continuationCursor(run.id)?.sourceRequestId == request.id
            )
    }

    fun startsExternalActivity(request: ToolRequest): Boolean =
        toolRegistry.specFor(request.toolName)?.permissions?.contains(ToolPermission.StartsExternalActivity) == true

    fun isDeviceControlTool(request: ToolRequest): Boolean =
        toolRegistry.specFor(request.toolName)?.capability == ToolCapability.DeviceControl

    fun canPlanLocalToolFromCurrentScreenObservation(request: ToolRequest, run: AgentRun): Boolean =
        when (request.toolName) {
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR -> true

            else -> isDeviceControlTool(request) &&
                host.latestSkillPlan(run.id).allowsModelDrivenAppSearchLocalObservation(run.id)
        }

    private fun SkillPlan?.allowsModelDrivenAppSearchLocalObservation(runId: String): Boolean {
        val plan = this ?: return true
        return plan.isModelDrivenAppSearchSkill() ||
            (plan.isSingleToolStepPlan() && host.latestModelDrivenAppSearchSkillPlan(runId) != null)
    }

    fun planNextToolStepFromCurrentSkill(
        run: AgentRun,
        result: ToolResult,
    ): NextObservationPlan? {
        val skillPlan = host.latestSkillPlan(run.id) ?: return null
        val requestedRequestIds = host.toolRequestsFor(run.id).mapTo(mutableSetOf()) { request -> request.id }
        return when (val progression = skillProgressor.nextToolAfterToolResult(
            skillPlan = skillPlan,
            requestedRequestIds = requestedRequestIds,
            result = result,
            satisfiedStepIds = host.valueFreeCompletedStepFrontiers(run.id),
        )) {
            SkillToolResultProgression.None -> null
            is SkillToolResultProgression.Rejected ->
                rejectNextToolPlan(
                    run.id,
                    requestForSkillProgressionRejection(skillPlan, requestedRequestIds, progression.reason),
                )
            is SkillToolResultProgression.BoundTool ->
                buildNextToolPlan(
                    runId = run.id,
                    request = progression.request,
                    draft = progression.draft,
                    plannedByModel = false,
                    fallbackReason = "skill tool step",
                    skillPlan = skillPlan,
                )
        }
    }

    fun planNextToolAfterModelResult(
        run: AgentRun,
        text: String,
        allowInlineToolCalls: Boolean = true,
    ): NextObservationPlan {
        val skillPlan = host.latestSkillPlan(run.id)
            ?: return if (allowInlineToolCalls) {
                planExplicitToolCallAfterModelResult(run, text)
            } else {
                NextObservationPlan.None
            }
        val requestedRequestIds = host.toolRequestsFor(run.id).mapTo(mutableSetOf()) { request -> request.id }
        host.invalidSkillPlanReason(skillPlan)?.let { reason ->
            return rejectNextToolPlan(
                run.id,
                requestForSkillProgressionRejection(skillPlan, requestedRequestIds, reason),
            )
        }
        return when (val progression = skillProgressor.nextToolAfterModelOutput(
            skillPlan = skillPlan,
            requestedRequestIds = requestedRequestIds,
            modelOutput = text,
        )) {
            SkillModelOutputProgression.None -> NextObservationPlan.None
            is SkillModelOutputProgression.Rejected ->
                rejectNextToolPlan(
                    run.id,
                    requestForSkillProgressionRejection(skillPlan, requestedRequestIds, progression.reason),
                )
            is SkillModelOutputProgression.BoundTool ->
                buildNextToolPlan(
                    runId = run.id,
                    request = progression.request,
                    draft = progression.draft,
                    plannedByModel = false,
                    fallbackReason = "skill model step",
                    skillPlan = skillPlan,
                )
        }
    }

    fun planExplicitToolCallAfterModelResult(
        run: AgentRun,
        text: String,
    ): NextObservationPlan {
        val draft = when (val parsed = actionPlanningRuntime.parseModelToolOutput(text)) {
            ModelToolOutputParseResult.None -> return NextObservationPlan.None
            is ModelToolOutputParseResult.Parsed -> parsed.draft
            is ModelToolOutputParseResult.Rejected -> {
                val rejectedRequest = ToolRequest(
                    toolName = parsed.toolName ?: "model_tool_call",
                    reason = "local model tool call",
                )
                return rejectNextToolPlan(run.id, rejectedRequest.rejected(parsed.reason))
            }
        }
        val request = ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = "local model tool call",
            sensitivityReason = draft.sensitivityReason,
        )
        if (
            request.requiresMobileActionProfileForInlineToolCall() &&
            !host.hasMobileActionPlanningModel(host.installedCapabilityProfiles(run.id))
        ) {
            return failMissingModelNextPlan(run.id, AgentPlan.MissingModel(ModelCapability.MobileAction))
        }
        val skillPlan = skillRuntime.plan(run.input, draft, request)
        return buildNextToolPlan(
            runId = run.id,
            request = request,
            draft = draft,
            plannedByModel = true,
            fallbackReason = "local model tool call",
            skillPlan = skillPlan,
        )
    }

    fun planNextCompositeSkillSegment(
        runId: String,
        input: String?,
    ): NextObservationPlan? {
        val skillPlan = input?.let { segmentInput -> skillRuntime.plan(segmentInput) } ?: return null
        if (skillPlan.isSingleToolStepPlan()) return null
        val toolStep = skillPlan.steps.firstOrNull() as? SkillStep.ToolStep
            ?: return null
        if (toolStep.dependsOn.isNotEmpty()) return null
        val validation = skillPlan.validateStructure(toolRegistry)
        if (!validation.isValid) {
            return rejectNextToolPlan(
                runId,
                toolStep.request.rejected("Invalid skill plan: ${validation.errors.joinToString()}"),
            )
        }
        return buildNextToolPlan(
            runId = runId,
            request = toolStep.request,
            draft = toolStep.draft,
            plannedByModel = false,
            fallbackReason = "skill-first",
            skillPlan = skillPlan,
        )
    }

    private fun requestForSkillProgressionRejection(
        skillPlan: SkillPlan,
        requestedRequestIds: Set<String>,
        reason: String,
    ): ToolResult {
        val rejectedRequest = skillPlan.steps
            .filterIsInstance<SkillStep.ToolStep>()
            .firstOrNull { step -> step.request.id !in requestedRequestIds }
            ?.request
            ?: ToolRequest(
                toolName = skillPlan.manifest.requiredTools.firstOrNull().orEmpty().ifBlank { "skill" },
                reason = skillPlan.request.reason,
            )
        return rejectedRequest.rejected(reason)
    }

    fun buildNextToolPlan(
        runId: String,
        request: ToolRequest,
        draft: ActionDraft,
        plannedByModel: Boolean,
        fallbackReason: String?,
        skillPlan: SkillPlan?,
    ): NextObservationPlan {
        if (runBudget.toolStepBudgetExceeded(runId)) {
            return failNextPlanBudget(runId, request)
        }
        if (host.toolRequestFor(runId, request.id) != null) {
            return rejectNextToolPlan(
                runId,
                request.rejected("Replanned tool request id already exists: ${request.id}"),
            )
        }
        host.invalidSkillPlanRejection(request, skillPlan)?.let { rejection ->
            return rejectNextToolPlan(runId, rejection)
        }
        val rejection = toolRegistry.validate(request)
        if (rejection != null) {
            return rejectNextToolPlan(runId, rejection)
        }
        val spec = toolRegistry.specFor(request.toolName) ?: run {
            val rejected = request.rejected("Unknown tool: ${request.toolName}")
            return rejectNextToolPlan(runId, rejected)
        }
        val safetyDecision = safetyPolicy.evaluate(spec, request, SafetyContext(userConfirmed = false))
        if (safetyDecision.outcome == SafetyOutcome.Reject) {
            val rejected = request.rejected(safetyDecision.reason)
            traceStore.appendStep(runId, AgentStep.SafetyChecked(safetyDecision))
            return rejectNextToolPlan(runId, rejected)
        }
        val plan = AgentPlan.UseTool(
            request = request,
            draft = draft.withSafetyDecision(safetyDecision),
            plannedByModel = plannedByModel,
            fallbackReason = fallbackReason,
            skillRequest = skillPlan?.request,
            skillPlan = skillPlan,
            safetyDecision = safetyDecision,
        ).let { built ->
            host.withConfirmationBypass(runId, built)
        }.let { bypassed ->
            host.withLowRiskAppControlContinuationBypass(runId, bypassed, skillPlan)
        }
        return NextObservationPlan.Planned(
            plan,
        )
    }

    private fun failMissingModelNextPlan(
        runId: String,
        plan: AgentPlan.MissingModel,
    ): NextObservationPlan {
        val reason = "Missing model capability ${plan.capability.name}."
        traceStore.appendStep(runId, AgentStep.ModelPlanned(plan))
        traceStore.appendStep(runId, AgentStep.Failed(reason))
        return NextObservationPlan.Rejected(reason)
    }

    fun rejectNextToolPlan(runId: String, result: ToolResult): NextObservationPlan {
        traceStore.appendStep(runId, AgentStep.ModelPlanned(AgentPlan.RejectedTool(result)))
        traceStore.appendStep(runId, AgentStep.ToolRejected(result))
        host.auditRejectedTool(runId, result)
        return NextObservationPlan.Rejected(result.summary)
    }

    private fun failNextPlanBudget(
        runId: String,
        request: ToolRequest,
    ): NextObservationPlan {
        val hintReason = runBudget.augmentReasonWithStepBudgetHint(runId, TOOL_STEP_BUDGET_EXCEEDED_REASON)
        val rejected = request.rejected(hintReason)
        traceStore.appendStep(runId, AgentStep.ModelPlanned(AgentPlan.RejectedTool(rejected)))
        traceStore.appendStep(runId, AgentStep.ToolRejected(rejected))
        host.auditRejectedTool(runId, rejected)
        traceStore.appendStep(runId, AgentStep.Failed(rejected.summary))
        return NextObservationPlan.Rejected(rejected.summary)
    }

    private fun ToolRequest.requiresMobileActionProfileForInlineToolCall(): Boolean =
        toolRegistry.specFor(toolName)?.isEligibleForParallelBatch() == false
}

internal fun ToolResult.hasLocalObservationEvidenceForPlanning(): Boolean =
    listOf(
        "screenObservationJson",
        "beforeScreenObservationJson",
        "afterScreenObservationJson",
        "screenObservationDiffSummary",
        "ocrBlocksJson",
        "ocrText",
        "screenText",
    ).any { key -> data[key]?.isNotBlank() == true }

internal fun ToolResult.isPermissionFailure(): Boolean =
    error?.code == ToolErrorCode.PermissionDenied ||
        data["failureKind"] == "permission_missing"

internal fun ToolResult.isForegroundPackageGateFailure(): Boolean =
    data["failureKind"] == "app_not_foreground"

internal fun ToolResult.isVerifiedSearchResult(): Boolean =
    data["searchVerificationStatus"] == "verified"

internal fun SkillPlan.isSingleToolStepPlan(): Boolean =
    steps.singleOrNull() is SkillStep.ToolStep

internal fun SkillPlan.isLowRiskAppControlSkill(): Boolean =
    manifest.lowRiskAppControlEligible

internal fun SkillPlan.isModelDrivenAppSearchSkill(): Boolean =
    manifest.id == BuiltInSkillRuntime.MODEL_DRIVEN_CURRENT_APP_UI_SEARCH_SKILL ||
        manifest.id == BuiltInSkillRuntime.MODEL_DRIVEN_OPEN_APP_UI_SEARCH_SKILL

internal fun SkillPlan.hasToolStep(request: ToolRequest): Boolean =
    steps.filterIsInstance<SkillStep.ToolStep>()
        .any { step -> step.request.id == request.id && step.request.toolName == request.toolName }
