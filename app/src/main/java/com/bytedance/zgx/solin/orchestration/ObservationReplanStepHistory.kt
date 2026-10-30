package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus

/**
 * Step history rendering for the LOCAL observation replanner prompt.
 *
 * Two inputs feed the "Prior request details" / "Run notes" prompt lines:
 *  - per-step outcome tokens (`ok` / `no_change` / `failed:<kind>`) derived from the trace, so the
 *    prompt rule "if the latest action failed, avoid the same target" has the evidence it needs;
 *  - the per-run scratchpad notes ([com.bytedance.zgx.solin.memory.PerRunScratchpad]).
 *
 * Privacy: outcome tokens are value-free enum-like strings (never raw screen text). Scratchpad notes
 * are LocalOnly — they may quote screen content — and are only ever handed to replanners that
 * implement [LocalOnlyNotesObservationReplanner] (the local action model). Every other replanner,
 * notably the remote-vision one, receives a context with the notes stripped
 * ([scopedForReplanner]); unknown replanners fail closed to "no notes".
 */
internal const val MAX_PRIOR_REQUEST_DETAILS = 4
internal const val MAX_PRIOR_REQUEST_DETAILS_CHARS = 360
internal const val MAX_SCRATCHPAD_NOTES_PROMPT_CHARS = 240
private const val MAX_SCRATCHPAD_NOTE_CHARS = 80
private const val MAX_OUTCOME_KIND_CHARS = 32
internal const val RUN_NOTES_PROMPT_LABEL = "Run notes (LocalOnly):"

/**
 * Marker for observation replanners whose prompt never leaves the device. Only these receive
 * [AgentObservationReplanContext.localOnlyScratchpadNotes]; see [scopedForReplanner].
 */
interface LocalOnlyNotesObservationReplanner : AgentObservationReplanner

/**
 * The context a given [replanner] may see. Composites re-scope per delegate, local-only replanners
 * see everything, and every other replanner (including remote-vision) gets the notes stripped.
 */
internal fun AgentObservationReplanContext.scopedForReplanner(
    replanner: AgentObservationReplanner,
): AgentObservationReplanContext =
    when (replanner) {
        is CompositeAgentObservationReplanner,
        is LocalOnlyNotesObservationReplanner -> this
        else -> withoutLocalOnlyScratchpadNotes()
    }

internal fun AgentObservationReplanContext.withoutLocalOnlyScratchpadNotes(): AgentObservationReplanContext =
    if (localOnlyScratchpadNotes.isEmpty()) this else copy(localOnlyScratchpadNotes = emptyList())

/**
 * Value-free outcome token for one observed tool step. Unknown/unexpected states fail towards
 * `failed:*` so the model is never told a step succeeded when it did not.
 */
internal fun ToolResult.replanStepOutcomeToken(): String =
    when (status) {
        ToolStatus.Succeeded -> when (data["uiActionOutcome"]) {
            "no_change" -> "no_change"
            "wrong_surface", "blocked" -> "failed:${data["uiActionOutcome"]}"
            else -> "ok"
        }
        ToolStatus.Failed -> "failed:" + (
            data["failureKind"]?.outcomeKindToken()
                ?: error?.code?.name?.outcomeKindToken()
                ?: "error"
            )
        ToolStatus.Rejected -> "failed:rejected"
        ToolStatus.Cancelled -> "failed:cancelled"
    }

/** Latest outcome token per request id, from observed and rejected trace steps (oldest first). */
internal fun List<AgentStep>.replanStepOutcomesByRequestId(): Map<String, String> {
    val outcomes = LinkedHashMap<String, String>()
    forEach { step ->
        when (step) {
            is AgentStep.ToolObserved -> outcomes[step.result.requestId] = step.result.replanStepOutcomeToken()
            is AgentStep.ToolRejected -> outcomes[step.result.requestId] = "failed:rejected"
            else -> Unit
        }
    }
    return outcomes
}

internal fun AgentObservationReplanContext.priorRequestDetailsPrompt(): String =
    priorRequests
        .takeLast(MAX_PRIOR_REQUEST_DETAILS)
        .map { request -> request.toPriorRequestDetailPrompt(outcomeFor(request)) }
        .joinToString(separator = " -> ")
        .ifBlank { "none" }
        .shortTraceText(maxLength = MAX_PRIOR_REQUEST_DETAILS_CHARS)

/**
 * LocalOnly scratchpad notes, newest kept first when the cap bites, rendered oldest-first.
 * Null when there are none so the prompt line is omitted entirely.
 */
internal fun AgentObservationReplanContext.localScratchpadNotesPrompt(): String? {
    val rendered = localOnlyScratchpadNotes
        .mapNotNull { note -> note.takeIf(String::isNotBlank)?.shortTraceText(MAX_SCRATCHPAD_NOTE_CHARS) }
    if (rendered.isEmpty()) return null
    val kept = ArrayDeque<String>()
    var used = 0
    for (note in rendered.asReversed()) {
        val cost = note.length + if (kept.isEmpty()) 0 else 3
        if (used + cost > MAX_SCRATCHPAD_NOTES_PROMPT_CHARS) break
        kept.addFirst(note)
        used += cost
    }
    if (kept.isEmpty()) return null
    return kept.joinToString(separator = " | ")
}

private val scratchpadNoteLine = Regex("""^\d+\. (.*)$""")

/**
 * Recovers the individual notes from [com.bytedance.zgx.solin.memory.PerRunScratchpad.formatForPrompt]
 * (`N. content` lines after a header). Multi-line notes are re-joined; header and truncation-marker
 * lines are dropped. Null/blank input yields no notes.
 */
internal fun scratchpadNotesFromPromptBlock(block: String?): List<String> {
    if (block.isNullOrBlank()) return emptyList()
    val notes = mutableListOf<StringBuilder>()
    block.lineSequence().forEach { line ->
        val match = scratchpadNoteLine.matchEntire(line)
        when {
            match != null -> notes += StringBuilder(match.groupValues[1])
            notes.isEmpty() || line.isBlank() || line.startsWith("…[") -> Unit
            else -> notes.last().append(' ').append(line.trim())
        }
    }
    return notes.map { note -> note.toString().trim() }.filter(String::isNotBlank)
}

private fun AgentObservationReplanContext.outcomeFor(request: ToolRequest): String? =
    if (request.id == observedResult.requestId) {
        observedResult.replanStepOutcomeToken()
    } else {
        priorStepOutcomes[request.id]
    }

private fun String.outcomeKindToken(): String? =
    trim()
        .replace(Regex("[^A-Za-z0-9_]"), "")
        .take(MAX_OUTCOME_KIND_CHARS)
        .takeIf(String::isNotBlank)

private fun ToolRequest.toPriorRequestDetailPrompt(outcome: String?): String {
    val details = buildList {
        arguments["target"]
            ?.takeIf { value -> value.isNotBlank() }
            ?.shortTraceText(maxLength = 96)
            ?.let { value -> add("target=$value") }
        arguments["direction"]
            ?.takeIf { value -> value.isNotBlank() }
            ?.shortTraceText(maxLength = 32)
            ?.let { value -> add("direction=$value") }
        arguments["captureMode"]
            ?.takeIf { value -> value.isNotBlank() }
            ?.shortTraceText(maxLength = 48)
            ?.let { value -> add("captureMode=$value") }
        arguments["text"]
            ?.takeIf { value -> value.isNotBlank() }
            ?.let { value -> add("textChars=${value.length}") }
        arguments["timeoutMillis"]
            ?.takeIf { value -> value.isNotBlank() }
            ?.shortTraceText(maxLength = 16)
            ?.let { value -> add("timeoutMillis=$value") }
    }.joinToString(separator = ",")
    val call = if (details.isBlank()) toolName else "$toolName{$details}"
    return if (outcome == null) call else "$call[$outcome]"
}
