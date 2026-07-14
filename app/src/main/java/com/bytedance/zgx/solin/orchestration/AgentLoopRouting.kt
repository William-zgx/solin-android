package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionIntentConfidence
import com.bytedance.zgx.solin.action.IntentRoutingDecision
import com.bytedance.zgx.solin.action.IntentRoutingPath
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.runtime.estimateLocalRuntimeTokens
import com.bytedance.zgx.solin.safety.SafetyDecision
import com.bytedance.zgx.solin.safety.SafetyOutcome
import org.json.JSONArray

fun AgentLoopResult.toAssistantRoute(): AssistantRoute =
    when (val planned = plan) {
        is AgentPlan.Answer -> AssistantRoute.Chat(
            runId = run.id,
            promptForModel = planned.promptForModel,
            memoryHits = planned.memoryHits,
            deviceContext = planned.deviceContext,
        )

        is AgentPlan.UseTool -> AssistantRoute.Action(
            runId = run.id,
            toolRequest = planned.request,
            draft = planned.draft,
            plannedByModel = planned.plannedByModel,
            fallbackReason = planned.fallbackReason,
            skillId = planned.skillRequest?.skillId,
            requiresUserConfirmation = planned.requiresUserConfirmation(),
        )

        is AgentPlan.RejectedTool -> AssistantRoute.ToolRejected(planned.result.summary)

        is AgentPlan.MissingModel -> AssistantRoute.MissingModel(planned.capability)
    }

internal fun AgentPlan.UseTool.requiresUserConfirmation(): Boolean =
    safetyDecision.outcome == SafetyOutcome.RequireConfirmation

internal fun AgentPlan.UseTool.toIntentRoutingDecision(input: String): IntentRoutingDecision {
    val path = routingPath()
    return IntentRoutingDecision(
        input = input,
        selectedPath = path,
        selectedToolName = request.toolName,
        selectedSkillId = skillRequest?.skillId,
        priority = path.routingPriority(),
        accepted = true,
        confidence = ActionIntentConfidence.High,
        rejectionReasons = emptyList(),
        requiresConfirmation = requiresUserConfirmation(),
    )
}

internal fun AgentPlan.UseTool.routingPath(): IntentRoutingPath =
    when (fallbackReason) {
        "skill-first",
        "skill model step" -> IntentRoutingPath.SkillFirst
        "remote tool call",
        "remote tool batch" -> IntentRoutingPath.RemoteToolPlanning
        "local model tool call" -> IntentRoutingPath.ModelToolCall
        else -> IntentRoutingPath.ActionPlanner
    }

internal fun AgentTraceStore.appendRejectedRoutingDecision(
    runId: String,
    path: IntentRoutingPath,
    toolName: String?,
    reason: String,
) {
    val run = run(runId) ?: return
    appendStep(
        runId,
        AgentStep.IntentRouted(
            IntentRoutingDecision(
                input = run.input,
                selectedPath = path,
                selectedToolName = toolName?.takeIf { it.isNotBlank() },
                selectedSkillId = null,
                priority = path.routingPriority(),
                accepted = false,
                confidence = ActionIntentConfidence.None,
                rejectionReasons = listOf(reason.toRoutingReasonSlug()),
                requiresConfirmation = null,
            ),
        ),
    )
}

internal fun IntentRoutingPath.routingPriority(): Int =
    when (this) {
        IntentRoutingPath.SkillFirst -> 100
        IntentRoutingPath.ActionPlanner -> 80
        IntentRoutingPath.RemoteToolPlanning -> 70
        IntentRoutingPath.ModelToolCall -> 60
        IntentRoutingPath.NoAction -> 0
    }

internal fun String.toRoutingReasonSlug(): String =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "tool_rejected" }

internal fun ActionDraft.withSafetyDecision(safetyDecision: SafetyDecision): ActionDraft =
    if (safetyDecision.outcome == SafetyOutcome.RequireConfirmation && !requiresConfirmation) {
        copy(requiresConfirmation = true)
    } else {
        this
    }

internal fun AgentPlan.UseTool.nextExecutionState(): AgentRunState =
    when {
        request.toolName == MobileActionFunctions.ASK_USER -> AgentRunState.AwaitingUserAnswer
        requiresUserConfirmation() -> AgentRunState.AwaitingUserConfirmation
        else -> AgentRunState.ExecutingTool
    }

/**
 * Parse the `choices` argument from a ToolRequest whose arguments map is `Map<String, String>`.
 * Accepts either a JSON-array string (the typical shape produced by model tool calls that
 * serialize structured args as JSON strings) or a single non-empty string treated as a single
 * choice. Returns an empty list when the argument is absent or unparseable.
 */
internal fun parseAskUserChoices(arguments: Map<String, String>): List<String> {
    val raw = arguments["choices"]?.takeIf { it.isNotBlank() } ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { idx ->
            arr.optString(idx).takeIf { !it.isNullOrBlank() }
        }
    }.getOrElse {
        // Fall back to treating a single non-JSON string as a singleton choice.
        listOf(raw)
    }
}

/**
 * Rough token estimator used by compaction call sites that don't supply a model-specific one.
 * Reuses the LiteRt chars/4 heuristic (already imported) — accurate enough for preflight
 * compaction gating even against remote models (both over- and under-estimates are safe: the
 * compactor is conservative, and the post-send retry path catches actual overflows).
 */
internal fun estimateTokensApproximate(messages: List<ChatMessage>): Int =
    messages.sumOf { estimateLocalRuntimeTokens(it.text) } + messages.size * 4
