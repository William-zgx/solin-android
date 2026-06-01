package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.ActionPlanKind
import com.bytedance.zgx.pocketmind.action.ActionPlanningRuntime
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolStatus

private const val DEFAULT_MAX_SEQUENTIAL_ACTIONS = 4
private const val SEQUENTIAL_REPLAN_REQUEST_REASON = "Explicit sequential action step planned."

fun interface AgentObservationReplanner {
    fun planNext(context: AgentObservationReplanContext): AgentObservationReplan?
}

data class AgentObservationReplanContext(
    val run: AgentRun,
    val previousRequest: ToolRequest,
    val observedResult: ToolResult,
    val priorRequests: List<ToolRequest>,
    val nextActionInput: String? = null,
)

data class AgentObservationReplan(
    val request: ToolRequest,
    val draft: ActionDraft,
    val plannedByModel: Boolean = false,
    val fallbackReason: String? = null,
) {
    init {
        require(request.toolName == draft.functionName) {
            "Replanned request and draft must target the same tool."
        }
    }
}

object NoOpAgentObservationReplanner : AgentObservationReplanner {
    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? = null
}

class SequentialActionObservationReplanner(
    private val actionPlanningRuntime: ActionPlanningRuntime,
    maxSequentialActions: Int = DEFAULT_MAX_SEQUENTIAL_ACTIONS,
) : AgentObservationReplanner {
    private val sequentialActionLimit = maxSequentialActions.coerceAtLeast(1)

    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? {
        if (context.observedResult.status != ToolStatus.Succeeded) return null
        val completedActionCount = context.priorRequests.size
        if (completedActionCount <= 0 || completedActionCount >= sequentialActionLimit) return null
        val nextInput = context.nextActionInput?.immediateSequentialActionText()
            ?: context.run.input.explicitSequentialActionTextAt(completedActionCount)
            ?: return null
        if (!actionPlanningRuntime.isLikelyAction(nextInput)) return null
        val planningResult = actionPlanningRuntime.plan(nextInput, actionModelPath = null)
        val draft = planningResult.plan.draft ?: return null
        if (planningResult.plan.kind != ActionPlanKind.Draft) return null
        return AgentObservationReplan(
            request = ToolRequest(
                toolName = draft.functionName,
                arguments = draft.parameters,
                reason = SEQUENTIAL_REPLAN_REQUEST_REASON,
            ),
            draft = draft,
            plannedByModel = planningResult.usedModel,
            fallbackReason = planningResult.fallbackReason,
        )
    }

}

internal fun String.explicitNextActionText(): String? =
    explicitSequentialActionTextAt(1)

internal fun String.explicitSequentialActionTextAt(index: Int): String? {
    if (index < 0) return null
    return explicitSequentialActionSegments().getOrNull(index)
}

private fun String.immediateSequentialActionText(): String? =
    (explicitSequentialActionTextAt(0) ?: trimActionText()).takeIf { it.isNotBlank() }

private fun String.explicitSequentialActionSegments(): List<String> {
    val matches = sequenceConnector.findAll(this).toList()
    if (matches.isEmpty()) return emptyList()
    val starts = listOf(0) + matches.map { match -> match.range.last + 1 }
    val ends = matches.map { match -> match.range.first } + length
    return starts.zip(ends)
        .mapNotNull { (start, end) ->
            substring(start.coerceAtMost(length), end.coerceAtMost(length)).trimActionText()
                .takeIf { it.isNotBlank() }
        }
}

private fun String.trimActionText(): String {
    val trimmed = trim(' ', '\t', '\n', '\r', '，', '。', ',', '.', ';', '；', ':', '：')
    return trimmed
        .replace(Regex("""^(?:先\s*|first,?\s+)""", RegexOption.IGNORE_CASE), "")
        .trim(' ', '\t', '\n', '\r', '，', '。', ',', '.', ';', '；', ':', '：')
}

private val sequenceConnector = Regex(
    pattern = """(?i)(?:\b(?:and\s+then|then)\b|然后|接着|随后|之后|再)""",
)
