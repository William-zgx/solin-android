package com.bytedance.zgx.solin.orchestration

import android.util.Log
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCapabilityProfile
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionIntentConfidence
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.IntentRoutingDecision
import com.bytedance.zgx.solin.action.IntentRoutingPath
import com.bytedance.zgx.solin.logging.solinW
import com.bytedance.zgx.solin.safety.SafetyContext
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.skill.SkillRuntime
import com.bytedance.zgx.solin.skill.SkillStep
import com.bytedance.zgx.solin.skill.authorizationContractHash
import com.bytedance.zgx.solin.skill.validateStructure
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolRetriever
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.isFirstStepPlanningCandidate
import com.bytedance.zgx.solin.tool.rejected

private const val TAG = "InitialToolPlanner"

/** fallbackReason of a tool the local planning model proposed; it routes as [IntentRoutingPath.ModelToolCall]. */
private const val LOCAL_MODEL_TOOL_CALL = "local model tool call"

/**
 * The prompt-only choice a local planning model makes to decline every tool. It is never
 * registered, so the output parser rejects it as an unknown tool and nothing can execute it.
 */
internal const val LOCAL_MODEL_NO_TOOL_CHOICE = "answer"

private val noToolChoiceSpec = ToolSpec(
    name = LOCAL_MODEL_NO_TOOL_CHOICE,
    title = "直接回答",
    description = "请求不需要手机动作（提问、闲聊、解释、翻译）时选它，由助手直接用文字回答。",
    inputSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}""",
    capability = ToolCapability.Orchestration,
    riskLevel = RiskLevel.LowReadOnly,
    confirmationPolicy = ConfirmationPolicy.NotRequired,
)

/**
 * Initial (pre-observation) tool planning collaborator for [AgentLoopRuntime].
 *
 * Owns rule-first / local-model-first / local-skill-before-remote initial planning, sequential-segment
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
    /**
     * [InitialPlanningMode.LocalModelFirst]: a verified on-device model may choose the first tool
     * from a retrieved shortlist, and its choice passes the same registry, safety and confirmation
     * gates as a rule draft. Every failure yields exactly the [InitialPlanningMode.RuleFirst] plan:
     * no verified planning model, an empty shortlist, a model error, unparseable output, a tool
     * outside the shortlist, invalid arguments, or a registry or safety rejection.
     */
    fun planLocalModelFirst(
        runId: String,
        input: String,
        installedCapabilityProfiles: List<ModelCapabilityProfile>,
        actionModelPath: String?,
        options: AgentRunOptions,
    ): AgentPlan? {
        val planRuleFirst = {
            planToolIfSupported(input, installedCapabilityProfiles, actionModelPath, options.remoteGuiDrivingEnabled)
        }
        val modelPath = options.localPlanningModelPath
            ?.takeIf { options.localModelPlansTools() && hasMobileActionPlanningModel(installedCapabilityProfiles) }
            ?: return planRuleFirst()
        val strategy = options.localPlanningStrategy
        if (strategy == LocalPlanningStrategy.ModelFirstWithRuleFastPath) {
            planRuleFirst()?.let { rulePlan -> return rulePlan }
        }
        val proposal = proposeLocalModelTool(input, modelPath)
        recordLocalModelProposal(runId, strategy, proposal)
        return when (proposal.outcome) {
            LocalModelOutcome.Accepted -> proposal.plan
            LocalModelOutcome.NoTool -> null
            // The fast path already ran the rules and they planned nothing.
            LocalModelOutcome.Failed -> if (strategy == LocalPlanningStrategy.ModelFirst) planRuleFirst() else null
        }
    }

    /**
     * One model call over the retrieved tools. A multi-step request is planned one segment at a
     * time, the way the rule planner does, so the model only sees the first segment.
     */
    private fun proposeLocalModelTool(input: String, modelPath: String): LocalModelProposal {
        val segment = input.initialSequentialActionInput()
        val modelInput = segment ?: input
        val retrieved = ToolRetriever.retrieve(
            request = modelInput,
            candidates = toolRegistry.specs().filter { spec ->
                spec.isFirstStepPlanningCandidate() && spec.name != LOCAL_MODEL_NO_TOOL_CHOICE
            },
        )
        val retrievedNames = retrieved.map { spec -> spec.name }
        fun failed(reason: String, toolName: String? = null, output: String? = null) =
            LocalModelProposal(LocalModelOutcome.Failed, reason, retrievedNames, toolName, output)
        // Offering only the no-tool choice cannot produce a plan, so the model is not called.
        if (retrieved.isEmpty()) return failed("local_model_no_tool_retrieved")
        val result = runCatching {
            actionPlanningRuntime.plan(modelInput, modelPath, retrieved + noToolChoiceSpec)
        }.getOrElse { throwable ->
            solinW(TAG, "local planning model failed: ${throwable.javaClass.simpleName}")
            return failed("local_model_planning_error")
        }
        val output = result.modelOutputPreview.localModelOutputShape()
        // A failed model attempt still carries the rule planner's draft; only a model draft counts.
        val draft = result.plan.draft?.takeIf { result.usedModel && result.plan.kind == ActionPlanKind.Draft }
        if (draft == null || draft.functionName == LOCAL_MODEL_NO_TOOL_CHOICE) {
            return if (draft != null || result.choseNoTool()) {
                LocalModelProposal(LocalModelOutcome.NoTool, "local_model_chose_no_tool", retrievedNames, output = output)
            } else {
                failed(result.modelFailureReason.localModelFailureReason(), output = output)
            }
        }
        val toolName = draft.functionName
        if (toolName !in retrievedNames) return failed("local_model_tool_not_retrieved", toolName, output)
        if (segment != null && toolName.requiresLocalModelBeforeSequentialTail(toolRegistry)) {
            return failed("local_model_tool_needs_local_tail", toolName, output)
        }
        val request = ToolRequest(
            toolName = toolName,
            arguments = draft.parameters,
            reason = draft.summary,
            sensitivityReason = draft.sensitivityReason,
        )
        val skillPlan = skillRuntime.plan(modelInput, draft, request)
        if (segment != null && skillPlan != null && !skillPlan.isSingleToolStepPlan()) {
            return failed("local_model_tool_needs_local_tail", toolName, output)
        }
        val plan = buildInitialToolPlan(
            request = request,
            draft = draft,
            plannedByModel = true,
            fallbackReason = LOCAL_MODEL_TOOL_CALL,
            skillPlan = skillPlan,
        ) as? AgentPlan.UseTool ?: return failed("local_model_tool_rejected", toolName, output)
        return LocalModelProposal(LocalModelOutcome.Accepted, "local_model_tool_accepted", retrievedNames, toolName, output, plan)
    }

    /**
     * Records one [IntentRoutingPath.ModelToolCall] decision per local planning attempt so the
     * model's share of initial planning can be measured. Its reasons are provenance tags: outcome,
     * strategy, the retrieved tools and the shape of the output. Argument values are never recorded.
     */
    private fun recordLocalModelProposal(runId: String, strategy: LocalPlanningStrategy, proposal: LocalModelProposal) {
        val run = traceStore.run(runId)?.takeIf { run -> run.input.isNotBlank() } ?: return
        val accepted = proposal.outcome == LocalModelOutcome.Accepted
        val decision = IntentRoutingDecision(
            input = run.input,
            selectedPath = IntentRoutingPath.ModelToolCall,
            selectedToolName = proposal.toolName,
            priority = IntentRoutingPath.ModelToolCall.routingPriority(),
            accepted = accepted,
            confidence = if (accepted) ActionIntentConfidence.High else ActionIntentConfidence.None,
            // One short tag per entry: persisted trace reasons are cut at 80 characters.
            rejectionReasons = listOfNotNull(
                proposal.reason,
                "strategy=${strategy.name}",
                "retrieved_count=${proposal.retrievedToolNames.size}",
                proposal.output?.let { shape -> "output=$shape" },
            ) + proposal.retrievedToolNames.map { name -> "retrieved=$name" },
        )
        traceStore.appendStep(runId, AgentStep.IntentRouted(decision))
    }

    fun planToolIfSupported(
        input: String,
        installedCapabilityProfiles: List<ModelCapabilityProfile>,
        actionModelPath: String?,
        remoteGuiDrivingEnabled: Boolean = false,
    ): AgentPlan? =
        planToolForInput(
            input = input,
            installedCapabilityProfiles = installedCapabilityProfiles,
            actionModelPath = actionModelPath,
            allowDirectSkillPlan = true,
            allowMultiStepSkillPlan = true,
            remoteGuiDrivingEnabled = remoteGuiDrivingEnabled,
        ) ?: input.initialSequentialActionInput()?.let { firstActionInput ->
            planInitialSequentialSegment(
                input = firstActionInput,
                installedCapabilityProfiles = installedCapabilityProfiles,
                actionModelPath = actionModelPath,
                remoteGuiDrivingEnabled = remoteGuiDrivingEnabled,
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
        remoteGuiDrivingEnabled: Boolean = false,
    ): AgentPlan? =
        planCompositeSkillForInitialSequentialSegment(input)
            ?: planToolForInput(
                input = input,
                installedCapabilityProfiles = installedCapabilityProfiles,
                actionModelPath = actionModelPath,
                allowDirectSkillPlan = false,
                allowMultiStepSkillPlan = false,
                remoteGuiDrivingEnabled = remoteGuiDrivingEnabled,
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
        remoteGuiDrivingEnabled: Boolean = false,
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
            !remoteGuiDrivingEnabled &&
            !hasMobileActionPlanningModel(
                installedCapabilityProfiles = installedCapabilityProfiles,
            )
        ) {
            return AgentPlan.MissingModel(ModelCapability.MobileAction)
        }
        val draft = result.plan.draft
        if (result.plan.kind != ActionPlanKind.Draft || draft == null) return null
        if (
            !allowMultiStepSkillPlan &&
            draft.functionName.requiresLocalModelBeforeSequentialTail(toolRegistry)
        ) {
            return null
        }
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

private enum class LocalModelOutcome { Accepted, NoTool, Failed }

private data class LocalModelProposal(
    val outcome: LocalModelOutcome,
    val reason: String,
    val retrievedToolNames: List<String>,
    val toolName: String? = null,
    val output: String? = null,
    val plan: AgentPlan.UseTool? = null,
)

private val modelCallHead = Regex("""call:([A-Za-z0-9_]+)""")
private val modelCallArgumentKey = Regex(""""([A-Za-z_][A-Za-z0-9_]*)"\s*:""")

/** `call:name{key,...}`, `text(length)` or `none`: what the model emitted, without argument values. */
private fun String?.localModelOutputShape(): String {
    val preview = this?.trim().orEmpty()
    if (preview.isEmpty()) return "none"
    val call = modelCallHead.find(preview) ?: return "text(${preview.length})"
    val keys = modelCallArgumentKey.findAll(preview.substring(call.range.last + 1))
        .map { match -> match.groupValues[1] }
        .distinct()
        .take(MAX_TRACED_ARGUMENT_KEYS)
        .joinToString(",")
    return "call:${call.groupValues[1]}{$keys}"
}

private const val MAX_TRACED_ARGUMENT_KEYS = 6

/**
 * The no-tool choice is never registered, so the parser rejects it as an unknown tool; the output
 * preview is checked too for a runtime that does not report the parser's reason.
 */
private fun ActionPlanningResult.choseNoTool(): Boolean {
    if (modelFailureReason == "Unknown tool: $LOCAL_MODEL_NO_TOOL_CHOICE") return true
    val calls = modelCallHead.findAll(modelOutputPreview.orEmpty()).toList()
    return calls.size == 1 && calls.single().groupValues[1] == LOCAL_MODEL_NO_TOOL_CHOICE
}

/** Maps the runtime's parse failure onto a stable trace slug. */
private fun String?.localModelFailureReason(): String =
    when {
        this == null -> "local_model_no_draft"
        this == "blank_model_output" -> "local_model_blank_output"
        this == "missing_call_tool_output" -> "local_model_no_call_output"
        startsWith("Unknown tool") -> "local_model_unknown_tool"
        startsWith("Multiple model tool calls") -> "local_model_multiple_calls"
        startsWith("Model tool call arguments") -> "local_model_invalid_arguments"
        else -> "local_model_unparsed_output"
    }
