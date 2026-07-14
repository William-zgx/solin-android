package com.bytedance.zgx.solin.orchestration

import android.util.Log
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCapabilityProfile
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.safety.SafetyContext
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.skill.SkillRuntime
import com.bytedance.zgx.solin.skill.SkillStep
import com.bytedance.zgx.solin.skill.authorizationContractHash
import com.bytedance.zgx.solin.skill.validateStructure
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.rejected

private const val TAG = "InitialToolPlanner"

/**
 * Initial (pre-observation) tool planning collaborator for [AgentLoopRuntime].
 *
 * Owns rule-first / local-skill-before-remote initial planning, sequential-segment
 * planning, skill-plan validation, and remote-tool draft construction. Fail-closed
 * skill authorization and safety checks stay compatible with the prior runtime-local path.
 */
internal class InitialToolPlanner(
    private val toolRegistry: ToolRegistry,
    private val skillRuntime: SkillRuntime,
    private val safetyPolicy: SafetyPolicy,
    private val actionPlanningRuntime: ActionPlanningRuntime,
    private val traceStore: AgentTraceStore,
) {
    fun planToolIfSupported(
        input: String,
        installedCapabilityProfiles: List<ModelCapabilityProfile>,
        actionModelPath: String?,
    ): AgentPlan? =
        planToolForInput(
            input = input,
            installedCapabilityProfiles = installedCapabilityProfiles,
            actionModelPath = actionModelPath,
            allowDirectSkillPlan = true,
            allowMultiStepSkillPlan = true,
        ) ?: input.initialSequentialActionInput()?.let { firstActionInput ->
            planInitialSequentialSegment(
                input = firstActionInput,
                installedCapabilityProfiles = installedCapabilityProfiles,
                actionModelPath = actionModelPath,
            )
        }

    fun planLocalOnlySkillBeforeRemote(input: String): AgentPlan? {
        val skillPlan = runCatching { skillRuntime.plan(input) }
            .getOrElse { throwable ->
                Log.w(
                    TAG,
                    "skillRuntime.plan failed, treating as no local-skill match: ${throwable.message}",
                )
                null
            } ?: return null
        return runCatching { buildInitialToolPlanFromSkill(skillPlan) }
            .getOrElse { throwable ->
                Log.w(
                    TAG,
                    "buildInitialToolPlanFromSkill failed, falling through to answer: ${throwable.message}",
                )
                null
            }
    }

    fun planInitialSequentialSegment(
        input: String,
        installedCapabilityProfiles: List<ModelCapabilityProfile> = emptyList(),
        actionModelPath: String?,
    ): AgentPlan? =
        planCompositeSkillForInitialSequentialSegment(input)
            ?: planToolForInput(
                input = input,
                installedCapabilityProfiles = installedCapabilityProfiles,
                actionModelPath = actionModelPath,
                allowDirectSkillPlan = false,
                allowMultiStepSkillPlan = false,
            )

    fun planCompositeSkillForInitialSequentialSegment(input: String): AgentPlan? {
        val skillPlan = skillRuntime.plan(input) ?: return null
        if (skillPlan.isSingleToolStepPlan()) return null
        return buildInitialToolPlanFromSkill(skillPlan)
    }

    fun nextSequentialSegmentInput(
        run: AgentRun,
        completedSegmentCount: Int,
    ): String? =
        traceStore.nextActionInput(run.id)?.immediateSequentialActionText()
            ?: run.input.explicitSequentialActionTextAt(completedSegmentCount)

    fun planToolForInput(
        input: String,
        installedCapabilityProfiles: List<ModelCapabilityProfile>,
        actionModelPath: String?,
        allowDirectSkillPlan: Boolean,
        allowMultiStepSkillPlan: Boolean,
    ): AgentPlan? {
        if (allowDirectSkillPlan) {
            skillRuntime.plan(input)?.let { skillPlan ->
                if (!allowMultiStepSkillPlan && !skillPlan.isSingleToolStepPlan()) return null
                return buildInitialToolPlanFromSkill(skillPlan)
            }
        }
        val intent = actionPlanningRuntime.classifyIntent(input)
        if (!intent.isAction || !intent.confidence.isActionableForAgentPlan()) return null
        val result = actionPlanningRuntime.plan(input, actionModelPath)
        if (
            result.usedModel &&
            !hasMobileActionPlanningModel(
                installedCapabilityProfiles = installedCapabilityProfiles,
            )
        ) {
            return AgentPlan.MissingModel(ModelCapability.MobileAction)
        }
        val draft = result.plan.draft
        if (result.plan.kind != ActionPlanKind.Draft || draft == null) return null
        if (!allowMultiStepSkillPlan && draft.functionName.requiresLocalModelBeforeSequentialTail()) return null
        val request = ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = draft.summary,
        )
        val skillPlan = skillRuntime.plan(input, draft, request)
        if (!allowMultiStepSkillPlan && skillPlan != null && !skillPlan.isSingleToolStepPlan()) return null
        return buildInitialToolPlan(
            request = request,
            draft = draft,
            plannedByModel = result.usedModel,
            fallbackReason = result.fallbackReason,
            skillPlan = skillPlan,
        )
    }

    fun hasMobileActionPlanningModel(
        installedCapabilityProfiles: List<ModelCapabilityProfile>,
    ): Boolean =
        installedCapabilityProfiles.any { profile -> profile.supportsMobileActionPlanning }

    fun draftForRemoteToolRequest(request: ToolRequest): ActionDraft {
        val spec = toolRegistry.specFor(request.toolName)
        return ActionDraft(
            functionName = request.toolName,
            title = spec?.title ?: request.toolName,
            summary = spec?.title?.let { title -> "$title · 远程模型请求" } ?: "远程模型请求执行 ${request.toolName}",
            parameters = request.arguments,
            requiresConfirmation = spec?.confirmationPolicy == ConfirmationPolicy.Required,
            sensitivityReason = request.sensitivityReason,
        )
    }

    fun buildInitialToolPlanFromSkill(skillPlan: SkillPlan): AgentPlan? {
        val toolStep = skillPlan.steps.firstOrNull() as? SkillStep.ToolStep
            ?: return null
        if (toolStep.dependsOn.isNotEmpty()) return null
        val validation = skillPlan.validateStructure(toolRegistry)
        if (!validation.isValid) {
            return AgentPlan.RejectedTool(
                toolStep.request.rejected("Invalid skill plan: ${validation.errors.joinToString()}"),
            )
        }
        return buildInitialToolPlan(
            request = toolStep.request,
            draft = toolStep.draft,
            plannedByModel = false,
            fallbackReason = "skill-first",
            skillPlan = skillPlan,
        )
    }

    fun buildInitialToolPlan(
        request: ToolRequest,
        draft: ActionDraft,
        plannedByModel: Boolean,
        fallbackReason: String?,
        skillPlan: SkillPlan?,
    ): AgentPlan {
        invalidSkillPlanRejection(request, skillPlan)?.let { rejection ->
            return AgentPlan.RejectedTool(rejection)
        }
        val rejection = toolRegistry.validate(request)
        if (rejection != null) return AgentPlan.RejectedTool(rejection)
        val spec = toolRegistry.specFor(request.toolName)
            ?: return AgentPlan.RejectedTool(
                request.rejected("Unknown tool: ${request.toolName}"),
            )
        val safetyDecision = safetyPolicy.evaluate(spec, request, SafetyContext(userConfirmed = false))
        if (safetyDecision.outcome == SafetyOutcome.Reject) {
            return AgentPlan.RejectedTool(request.rejected(safetyDecision.reason))
        }
        return AgentPlan.UseTool(
            request = request,
            draft = draft.withSafetyDecision(safetyDecision),
            plannedByModel = plannedByModel,
            fallbackReason = fallbackReason,
            skillRequest = skillPlan?.request,
            skillPlan = skillPlan,
            safetyDecision = safetyDecision,
        )
    }

    fun invalidSkillPlanRejection(
        request: ToolRequest,
        skillPlan: SkillPlan?,
    ): ToolResult? {
        val reason = invalidSkillPlanReason(skillPlan) ?: return null
        return request.rejected(reason)
    }

    fun invalidSkillPlanReason(skillPlan: SkillPlan?): String? {
        skillPlan ?: return null
        unauthorizedSkillManifestReason(skillPlan)?.let { reason -> return reason }
        val validation = skillPlan.validateStructure(toolRegistry)
        return if (validation.isValid) {
            null
        } else {
            "Invalid skill plan: ${validation.errors.joinToString()}"
        }
    }

    fun unauthorizedSkillManifestReason(skillPlan: SkillPlan): String? {
        val currentManifest = skillRuntime.manifests()
            .firstOrNull { manifest -> manifest.id == skillPlan.manifest.id }
            ?: return "Skill manifest is not authorized by current runtime: ${skillPlan.manifest.id}"
        if (currentManifest.version != skillPlan.manifest.version) {
            return "Skill manifest version changed: ${skillPlan.manifest.id}"
        }
        if (currentManifest.authorizationContractHash() != skillPlan.manifest.authorizationContractHash()) {
            return "Skill manifest changed: ${skillPlan.manifest.id}"
        }
        return null
    }

    private fun String.initialSequentialActionInput(): String? =
        explicitSequentialActionTextAt(0)
            ?.takeIf { explicitSequentialActionTextAt(1) != null }
}
