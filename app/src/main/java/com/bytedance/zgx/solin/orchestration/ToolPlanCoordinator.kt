package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCapabilityProfile
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.ModelToolOutputParseResult
import com.bytedance.zgx.solin.device.screenObservationFromJsonStringOrNull
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

internal const val POST_LAUNCH_IN_APP_CONTINUATION_REASON = "Post-launch in-app continuation checkpoint."

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
    private val traceQueries: AgentTraceQueries,
    private val safetyPolicy: SafetyPolicy,
    private val runBudget: AgentRunBudget,
    private val host: Host,
) {
    /**
     * Runtime-owned lookups and side effects the coordinator must not re-implement.
     * Keeps confirmation-bypass policy and skill-auth validation on the runtime facade.
     */
    interface Host {
        fun invalidSkillPlanReason(skillPlan: SkillPlan?): String?
        fun invalidSkillPlanRejection(request: ToolRequest, skillPlan: SkillPlan?): ToolResult?
        fun toolRequestsFor(runId: String): List<ToolRequest>
        fun toolRequestFor(runId: String, requestId: String): ToolRequest?
        fun plannedSequentialSegmentCount(runId: String): Int
        fun nextSequentialSegmentInput(run: AgentRun, completedSegmentCount: Int): String?
        fun hasMobileActionPlanningModel(installedCapabilityProfiles: List<ModelCapabilityProfile>): Boolean
        fun installedCapabilityProfiles(runId: String): List<ModelCapabilityProfile>
        /**
         * True when this run may drive the in-app loop with the remote vision model instead of a
         * local action model (inferenceMode==Remote AND remote-GUI opt-in AND vision support). OR-ed
         * with [hasMobileActionPlanningModel] at every loop gate so the fail-closed MissingModel
         * rejection passes on the remote-vision path.
         */
        fun remoteGuiDrivingEnabled(runId: String): Boolean
        /**
         * True when this run has at least one user-confirmed tool step. Threaded into the observation
         * replan context so the remote-vision screenshot egress only fires after the user's first
         * confirmation (see [AgentObservationReplanContext.runHasUserConfirmedStep]).
         */
        fun runHasUserConfirmedStep(runId: String): Boolean
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
        traceQueries.latestSkillPlan(run.id)?.let { skillPlan ->
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
                screenObservationHistory = screenObservationFingerprints(run.id),
                inheritedExpectedForegroundPackage = result.launchedTargetPackageOrNull(),
                runHasUserConfirmedStep = host.runHasUserConfirmedStep(run.id),
            ),
        ) ?: return NextObservationPlan.None
        if (
            replan.plannedByModel &&
            !canDriveInAppLoop(run.id)
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
        traceQueries.latestSkillPlan(run.id)?.let { skillPlan ->
            if (
                !skillPlan.isModelDrivenAppSearchSkill() &&
                !(skillPlan.isSingleToolStepPlan() && traceQueries.latestSkillPlan(run.id) { it.isModelDrivenAppSearchSkill() } != null)
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
                screenObservationHistory = screenObservationFingerprints(run.id),
                inheritedExpectedForegroundPackage = result.launchedTargetPackageOrNull(),
                runHasUserConfirmedStep = host.runHasUserConfirmedStep(run.id),
            ),
        ) ?: return null
        if (
            replan.plannedByModel &&
            !canDriveInAppLoop(run.id)
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
        // Carry the expected foreground package (if the failed step had one) onto the recovery step
        // so the bounded settle rounds verify the launched target is foreground instead of blindly
        // waiting — otherwise a slow cold start keeps failing the same package-blind way.
        val expectedPackage = request.arguments["expectedPackageName"]?.takeIf { it.isNotBlank() }
            ?: request.arguments["targetPackageName"]?.takeIf { it.isNotBlank() }
        val arguments = when (nextToolName) {
            MobileActionFunctions.UI_WAIT -> mapOf("timeoutMillis" to "500")
            else -> mapOf(
                "maxTextChars" to "2000",
                "maxNodes" to "50",
            )
        } + (expectedPackage?.let { mapOf("expectedPackageName" to it) } ?: emptyMap())
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

    /**
     * After a confirmed open-app launch that carried a `followUpIntent`, synthesize an
     * `observe_current_screen` step so the run can autonomously continue INSIDE the just-opened
     * app (observe → local-model replan → tap/type/submit/scroll) instead of parking at
     * AwaitingExternalOutcome. Returns null (caller keeps the old open-then-stop behavior) unless:
     *  - the launch request carried a non-blank `followUpIntent`, AND
     *  - a local mobile-action planning model is installed.
     *
     * The synthesized observe is LocalOnly. The subsequent replan uses `run.input` (which already
     * contains the follow-up intent) to plan the first in-app action, and every downstream UI action
     * still passes dangerousUiActionPreflight + expectedForegroundPackagePreflight + counts toward
     * the 5-step checkpoint budget. The expected foreground package (resolved from the launch) is
     * threaded onto the observe so the continuation fails closed if the target app isn't foreground.
     */
    /**
     * The just-launched target package, resolved ONLY from an unverified-external-launch result
     * (open_app success), using the same keys as [planPostLaunchInAppContinuation]. Returns null for
     * any other result so a stale package is never carried across unrelated steps. Threaded into
     * [AgentObservationReplanContext.inheritedExpectedForegroundPackage] so a REMOTE-planned
     * continuation (open_app_by_name with only appName, no followUpIntent) can still fail-close its
     * downstream tap/type on the correct package even though no request-level key reached the replan.
     */
    private fun ToolResult.launchedTargetPackageOrNull(): String? {
        if (!isUnverifiedExternalLaunch()) return null
        return data["targetPackage"]?.takeIf { it.isNotBlank() }
            ?: data["packageName"]?.takeIf { it.isNotBlank() }
            ?: data["targetPackageName"]?.takeIf { it.isNotBlank() }
    }

    fun planPostLaunchInAppContinuation(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): NextObservationPlan? {
        if (!result.isUnverifiedExternalLaunch()) return null
        val followUpIntent = request.arguments["followUpIntent"]?.takeIf { it.isNotBlank() } ?: return null
        if (!canDriveInAppLoop(run.id)) return null
        // Best-effort continuation: if the step budget is already exhausted, do NOT call the
        // side-effecting buildNextToolPlan (which would append ModelPlanned/ToolRejected/Failed trace
        // steps + audit for a plan the caller then discards via `as? Planned`, leaving phantom
        // rejected/failed steps on a run that actually completes as open-then-stop). Bail early so the
        // caller falls through cleanly to the normal Complete/AwaitingExternalOutcome outcome.
        if (runBudget.toolStepBudgetExceeded(run.id) || runBudget.runDeadlineExceeded(run.id)) return null
        // open_app success results emit the package under "targetPackage" (ActionExecutor
        // externalActivityData -> safeData). Older/injected-starter paths use "packageName" /
        // "targetPackageName", so fall back to those. Reading the wrong key silently disables the
        // fail-closed foreground-package binding threaded onto the continuation below.
        val expectedPackage = result.data["targetPackage"]?.takeIf { it.isNotBlank() }
            ?: result.data["packageName"]?.takeIf { it.isNotBlank() }
            ?: result.data["targetPackageName"]?.takeIf { it.isNotBlank() }
        val arguments = buildMap {
            put("maxTextChars", "2000")
            put("maxNodes", "50")
            if (expectedPackage != null) put("expectedPackageName", expectedPackage)
        }
        val draft = ActionDraft(
            functionName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            title = "观察已打开的应用",
            summary = "已打开应用，将观察当前屏幕以便在应用内继续执行：$followUpIntent；不读取截图像素或发送远程。",
            parameters = arguments,
        )
        val observeRequest = ToolRequest(
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            arguments = arguments,
            reason = POST_LAUNCH_IN_APP_CONTINUATION_REASON,
        )
        val skillPlan = skillRuntime.plan(run.input, draft, observeRequest)
        return buildNextToolPlan(
            runId = run.id,
            request = observeRequest,
            draft = draft,
            plannedByModel = false,
            fallbackReason = "post-launch in-app continuation",
            skillPlan = skillPlan,
        )
    }

    /**
     * Builds the ordered screen-observation fingerprint history for [runId] from observed trace
     * steps (oldest first), for [DeadLoopDetectionReplanner] Rule 2. Each fingerprint carries only
     * non-sensitive metadata: the [textSummary] is a stable hash digest of the screen text, never
     * the raw text. Prefers the after-observation of each step, falling back to the primary
     * observation JSON, so post-action screens drive same-screen detection.
     */
    private fun screenObservationFingerprints(runId: String): List<ScreenObservationFingerprint> =
        traceStore.steps(runId)
            .filterIsInstance<AgentStep.ToolObserved>()
            .mapNotNull { step -> step.result.toScreenObservationFingerprintOrNull() }

    private fun ToolResult.toScreenObservationFingerprintOrNull(): ScreenObservationFingerprint? {
        val rawJson = data["afterScreenObservationJson"]
            ?: data["screenObservationJson"]
            ?: return null
        val observation = screenObservationFromJsonStringOrNull(rawJson) ?: return null
        val textDigest = observation.elements
            .asSequence()
            .map { element -> element.text }
            .filter { text -> text.isNotBlank() }
            .joinToString("\n")
            .hashCode()
            .toString()
        return ScreenObservationFingerprint(
            packageName = observation.packageName,
            textSummary = textDigest,
            elementCount = observation.elements.size,
            capturedAtMillis = observation.capturedAtMillis,
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
        val skillPlan = traceQueries.latestSkillPlan(run.id) ?: return null
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
        val skillPlan = traceQueries.latestSkillPlan(run.id) ?: return null
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
                traceQueries.latestSkillPlan(run.id).allowsModelDrivenAppSearchLocalObservation(run.id)
        }

    private fun SkillPlan?.allowsModelDrivenAppSearchLocalObservation(runId: String): Boolean {
        val plan = this ?: return true
        return plan.isModelDrivenAppSearchSkill() ||
            (plan.isSingleToolStepPlan() && traceQueries.latestSkillPlan(runId) { it.isModelDrivenAppSearchSkill() } != null)
    }

    fun planNextToolStepFromCurrentSkill(
        run: AgentRun,
        result: ToolResult,
    ): NextObservationPlan? {
        val skillPlan = traceQueries.latestSkillPlan(run.id) ?: return null
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
        val skillPlan = traceQueries.latestSkillPlan(run.id)
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
            !canDriveInAppLoop(run.id)
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
        // Both budgets, not just the step count: a run that keeps replanning tools without ever
        // reaching observeModelResult (where the wall-clock guard also lives) would otherwise have
        // no time bound at all, since local generation has no timeout of its own.
        if (runBudget.toolStepBudgetExceeded(runId) || runBudget.runDeadlineExceeded(runId)) {
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

    /**
     * The in-app loop's mobile-action gate: satisfied by a local action-planning model OR by the
     * remote-vision GUI path being enabled for this run. OR-ing the two here keeps every gate site
     * consistent — a local model drives in Local/Auto mode, the remote vision model drives in the
     * opt-in Remote path, and with neither the loop still fails closed to MissingModel.
     */
    private fun canDriveInAppLoop(runId: String): Boolean =
        host.hasMobileActionPlanningModel(host.installedCapabilityProfiles(runId)) ||
            host.remoteGuiDrivingEnabled(runId)

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
        // Attribute the failure to whichever budget actually tripped: reporting "step budget
        // exceeded" for a run that ran out of wall-clock time sends whoever reads the trace or the
        // assistant-facing summary looking for a step-count problem that isn't there. The step-count
        // hint is only meaningful for the step budget, so it is appended only in that branch.
        val hintReason = if (runBudget.toolStepBudgetExceeded(runId)) {
            runBudget.augmentReasonWithStepBudgetHint(runId, TOOL_STEP_BUDGET_EXCEEDED_REASON)
        } else {
            RUN_WALL_CLOCK_DEADLINE_EXCEEDED_REASON
        }
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
