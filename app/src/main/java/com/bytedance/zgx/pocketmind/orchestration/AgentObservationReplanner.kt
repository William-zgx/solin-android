package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.ActionIntentConfidence
import com.bytedance.zgx.pocketmind.action.ActionPlanKind
import com.bytedance.zgx.pocketmind.action.ActionPlanningRuntime
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.audit.ToolAuditSummaryRedactor
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import com.bytedance.zgx.pocketmind.tool.ToolRegistry

private const val DEFAULT_MAX_SEQUENTIAL_ACTIONS = 4
private const val DEFAULT_MAX_MODEL_OBSERVATION_REPLANS = 1
private const val SEQUENTIAL_REPLAN_REQUEST_REASON = "Explicit sequential action step planned."
private const val MODEL_OBSERVATION_REPLAN_REQUEST_REASON = "Observation model step planned."
private const val MODEL_OBSERVATION_REPLAN_FALLBACK_REASON = "observation model replan"

private val SEQUENTIAL_LOCAL_CONTINUATION_TOOL_NAMES = setOf(
    MobileActionFunctions.READ_CLIPBOARD,
    MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
    MobileActionFunctions.READ_RECENT_IMAGE_OCR,
    MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
    MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
    MobileActionFunctions.QUERY_BACKGROUND_TASKS,
)

fun interface AgentObservationReplanner {
    fun planNext(context: AgentObservationReplanContext): AgentObservationReplan?
}

data class AgentObservationReplanContext(
    val run: AgentRun,
    val previousRequest: ToolRequest,
    val observedResult: ToolResult,
    val priorRequests: List<ToolRequest>,
    val nextActionInput: String? = null,
    val completedSegmentCount: Int = priorRequests.size,
)

data class AgentObservationReplan(
    val request: ToolRequest,
    val draft: ActionDraft,
    val plannedByModel: Boolean = false,
    val fallbackReason: String? = null,
    val input: String? = null,
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

class CompositeAgentObservationReplanner(
    vararg delegates: AgentObservationReplanner,
) : AgentObservationReplanner {
    private val delegates = delegates.toList()

    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? =
        delegates.firstNotNullOfOrNull { delegate -> delegate.planNext(context) }
}

class ModelObservationReplanner(
    private val actionPlanningRuntime: ActionPlanningRuntime,
    private val actionModelPathProvider: () -> String? = { null },
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    maxModelReplans: Int = DEFAULT_MAX_MODEL_OBSERVATION_REPLANS,
) : AgentObservationReplanner {
    private val modelReplanLimit = maxModelReplans.coerceAtLeast(0)

    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? {
        if (context.observedResult.status != ToolStatus.Succeeded) return null
        if (modelReplanLimit == 0) return null
        if (context.modelObservationReplanCount() >= modelReplanLimit) return null
        val actionModelPath = actionModelPathProvider()?.takeIf { path -> path.isNotBlank() } ?: return null
        val planningResult = actionPlanningRuntime.plan(
            input = context.observationModelPrompt(toolRegistry),
            actionModelPath = actionModelPath,
        )
        if (!planningResult.usedModel) return null
        if (planningResult.plan.kind != ActionPlanKind.Draft) return null
        val draft = planningResult.plan.draft ?: return null
        return AgentObservationReplan(
            request = ToolRequest(
                toolName = draft.functionName,
                arguments = draft.parameters,
                reason = MODEL_OBSERVATION_REPLAN_REQUEST_REASON,
            ),
            draft = draft,
            plannedByModel = true,
            fallbackReason = MODEL_OBSERVATION_REPLAN_FALLBACK_REASON,
        )
    }
}

class SequentialActionObservationReplanner(
    private val actionPlanningRuntime: ActionPlanningRuntime,
    maxSequentialActions: Int = DEFAULT_MAX_SEQUENTIAL_ACTIONS,
) : AgentObservationReplanner {
    private val sequentialActionLimit = maxSequentialActions.coerceAtLeast(1)

    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? {
        if (context.observedResult.status != ToolStatus.Succeeded) return null
        val completedSegmentCount = context.completedSegmentCount
        if (completedSegmentCount <= 0 || completedSegmentCount >= sequentialActionLimit) return null
        val nextInput = context.nextActionInput?.immediateSequentialActionText()
            ?: context.run.input.explicitSequentialActionTextAt(completedSegmentCount)
            ?: return null
        val intent = actionPlanningRuntime.classifyIntent(nextInput)
        if (!intent.isAction || !intent.confidence.isActionableForSequentialReplan()) return null
        val planningResult = actionPlanningRuntime.plan(nextInput, actionModelPath = null)
        val draft = planningResult.plan.draft ?: return null
        if (planningResult.plan.kind != ActionPlanKind.Draft) return null
        if (
            draft.functionName.requiresLocalModelBeforeSequentialTail() &&
            context.run.input.explicitSequentialActionTextAt(completedSegmentCount + 1) != null
        ) {
            return null
        }
        return AgentObservationReplan(
            request = ToolRequest(
                toolName = draft.functionName,
                arguments = draft.parameters,
                reason = SEQUENTIAL_REPLAN_REQUEST_REASON,
            ),
            draft = draft,
            plannedByModel = planningResult.usedModel,
            fallbackReason = planningResult.fallbackReason,
            input = nextInput,
        )
    }

}

internal fun ActionIntentConfidence.isActionableForAgentPlan(): Boolean =
    this == ActionIntentConfidence.Medium || this == ActionIntentConfidence.High

internal fun ActionIntentConfidence.isActionableForSequentialReplan(): Boolean =
    this == ActionIntentConfidence.High

private fun AgentObservationReplanContext.modelObservationReplanCount(): Int =
    priorRequests.count { request -> request.reason == MODEL_OBSERVATION_REPLAN_REQUEST_REASON }

private fun AgentObservationReplanContext.observationModelPrompt(toolRegistry: ToolRegistry): String {
    val privateOutputKeys = toolRegistry.privateOutputKeysFor(previousRequest.toolName)
    val publicDataKeys = observedResult.data.keys
        .filterNot { key -> key in privateOutputKeys }
        .sorted()
        .joinToString()
        .ifBlank { "none" }
    val omittedPrivateKeys = privateOutputKeys
        .filter { key -> key in observedResult.data }
        .sorted()
        .joinToString()
        .ifBlank { "none" }
    val priorTools = priorRequests
        .joinToString(separator = " -> ") { request -> request.toolName }
        .ifBlank { "none" }
    val previousArgumentKeys = previousRequest.arguments.keys
        .sorted()
        .joinToString()
        .ifBlank { "none" }
    val intentPreview = (nextActionInput?.immediateSequentialActionText() ?: run.input)
        .safeObservationPromptText()
    val observationSummary = observedResult.summary.safeObservationPromptText()
    return """
        Decide whether the user's request needs exactly one more mobile tool after the latest observation.
        Output a tool call only when the next action is clearly required; otherwise output ordinary text with no call.
        Do not repeat a tool that has already satisfied the request.

        User intent preview: $intentPreview
        Prior tools: $priorTools
        Previous tool: ${previousRequest.toolName}
        Previous argument keys: $previousArgumentKeys
        Observation status: ${observedResult.status}
        Observation summary: $observationSummary
        Observation public data keys: $publicDataKeys
        Observation private data keys omitted: $omittedPrivateKeys
        Completed segment count: $completedSegmentCount
    """.trimIndent()
}

private fun String.safeObservationPromptText(maxLength: Int = 160): String {
    val compact = lineSequence()
        .joinToString(" ") { line -> line.trim() }
        .replace(Regex("\\s+"), " ")
        .trim()
    val redacted = ToolAuditSummaryRedactor.redact(compact)
    return if (redacted.length <= maxLength) redacted else redacted.take(maxLength - 3) + "..."
}

internal fun String.explicitNextActionText(): String? =
    explicitSequentialActionTextAt(1)

internal fun String.explicitSequentialActionTextAt(index: Int): String? {
    if (index < 0) return null
    return explicitSequentialActionSegments().getOrNull(index)
}

internal fun String.requiresLocalModelBeforeSequentialTail(): Boolean =
    this in SEQUENTIAL_LOCAL_CONTINUATION_TOOL_NAMES

internal fun String.immediateSequentialActionText(): String? =
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
