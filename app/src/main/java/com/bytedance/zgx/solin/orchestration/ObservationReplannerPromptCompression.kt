package com.bytedance.zgx.solin.orchestration

private const val MAX_OBSERVATION_MODEL_PROMPT_CHARS = 2_600

/**
 * Labels of prompt lines that carry user-authored text (`run.input`, the next sequential segment of
 * it, or steering text). Same invariant as [DefaultContextCompactor]: compression never shortens or
 * drops these lines; it shrinks the other lines instead, and if the user lines alone exceed
 * `maxChars` the prompt comes out longer than `maxChars` rather than cutting user text.
 */
internal val userTextPromptLabels = listOf("User intent preview:", "用户请求:")

private fun String.isUserTextPromptLine(): Boolean = userTextPromptLabels.any { label -> startsWith(label) }

/**
 * Shrinks an observation-replanner prompt to [maxChars]: per-label extractive compression first,
 * then a priority-ordered line budget. User-text lines ([userTextPromptLabels]) pass through
 * byte-identical; a final recovery check re-inserts any that went missing.
 */
internal fun String.compressForObservationModelContext(
    maxChars: Int = MAX_OBSERVATION_MODEL_PROMPT_CHARS,
): String {
    val normalized = lineSequence()
        .map { line -> line.trimEnd() }
        .joinToString(separator = "\n")
        .trim()
    if (normalized.length <= maxChars) return normalized

    val valueBudgets = ObservationPromptValueBudgets.forPrompt(maxChars)
    val compressedLines = normalized.lines().map { line ->
        when {
            line.startsWith("LocalOnly observation evidence:") ->
                line.compressLabeledPromptValue(
                    label = "LocalOnly observation evidence:",
                    valueBudget = valueBudgets.localEvidence,
                    protectedPatterns = localEvidenceProtectedPromptPatterns,
                    maxListEntries = 4,
                )

            line.startsWith("Observation diagnostics:") ->
                line.compressLabeledPromptValue(
                    label = "Observation diagnostics:",
                    valueBudget = valueBudgets.diagnostics,
                    protectedPatterns = diagnosticProtectedPromptPatterns,
                )

            line.startsWith("Prior request details:") ->
                line.compressLabeledPromptValue(
                    label = "Prior request details:",
                    valueBudget = valueBudgets.priorRequests,
                    protectedPatterns = priorRequestProtectedPromptPatterns,
                )

            line.startsWith("Observation summary:") ->
                line.compressLabeledPromptValue(
                    label = "Observation summary:",
                    valueBudget = valueBudgets.summary,
                    protectedPatterns = emptyList(),
                )

            else -> line
        }
    }
    val compressed = compressedLines.joinToString(separator = "\n")
    if (compressed.length <= maxChars) return compressed.withUserTextLinesFrom(normalized)

    return compressed.lines()
        .prioritizedForObservationPrompt()
        .joinPrioritizedObservationPrompt(maxChars)
        .withUserTextLinesFrom(normalized)
}

/**
 * Recovery check: every user-text line of [source] must appear in [this] byte-identical. An altered
 * copy is removed and each missing line is re-inserted after the nearest preceding [source] line
 * that survived (or first). Returns [this] unchanged when nothing was missing.
 */
internal fun String.withUserTextLinesFrom(source: String): String {
    val sourceLines = source.lines()
    val userLines = sourceLines.filter { line -> line.isUserTextPromptLine() }
    if (userLines.isEmpty()) return this
    val lines = lines()
    val out = lines.filterNot { line -> line.isUserTextPromptLine() && line !in userLines }.toMutableList()
    val unmatched = out.filter { line -> line.isUserTextPromptLine() }.toMutableList()
    val missing = sourceLines.withIndex().filter { (_, line) ->
        line.isUserTextPromptLine() && !unmatched.remove(line)
    }
    if (missing.isEmpty() && out.size == lines.size) return this
    for ((sourceIndex, line) in missing) {
        val anchor = (sourceIndex - 1 downTo 0)
            .map { index -> out.indexOf(sourceLines[index]) }
            .firstOrNull { index -> index >= 0 }
        out.add(anchor?.plus(1) ?: 0, line)
    }
    return out.joinToString(separator = "\n")
}

private data class ObservationPromptValueBudgets(
    val localEvidence: Int,
    val diagnostics: Int,
    val priorRequests: Int,
    val summary: Int,
) {
    companion object {
        fun forPrompt(maxChars: Int): ObservationPromptValueBudgets =
            ObservationPromptValueBudgets(
                localEvidence = (maxChars * 0.44).toInt().coerceIn(720, 1_600),
                diagnostics = (maxChars * 0.10).toInt().coerceIn(120, 360),
                priorRequests = (maxChars * 0.08).toInt().coerceIn(96, 260),
                summary = (maxChars * 0.06).toInt().coerceIn(80, 180),
            )
    }
}

private val localEvidenceProtectedPromptPatterns = listOf(
    Regex("""targetShortlist\([^)]*\)"""),
    Regex("""screenObservationDiffSummary=[^|]+"""),
    Regex("""addedText=[^;|]+"""),
    Regex("""addedActionable=[^;|]+"""),
    Regex("""afterScreenObservationJson\(id=[^)]+\)"""),
    Regex("""screenObservationJson\(id=[^)]+\)"""),
)

private val diagnosticProtectedPromptPatterns = listOf(
    Regex("""resultRetryable=[^;|]+"""),
    Regex("""failureKind=[^;|]+"""),
    Regex("""actionType=[^;|]+"""),
    Regex("""uiActionOutcome=[^;|]+"""),
    Regex("""uiActionOutcomeReason=[^;|]+"""),
    Regex("""appSearchProgressStage=[^;|]+"""),
    Regex("""target=[^;|]+"""),
    Regex("""errorCode=[^;|]+"""),
)

private val priorRequestProtectedPromptPatterns = listOf(
    Regex("""ui_[a-z_]+\{[^}]*target=[^}]*\}(\[[^\]]*\])?"""),
    Regex("""observe_current_screen\{[^}]*\}(\[[^\]]*\])?"""),
)

private fun String.compressLabeledPromptValue(
    label: String,
    valueBudget: Int,
    protectedPatterns: List<Regex>,
    maxListEntries: Int = 3,
): String {
    val value = removePrefix(label).trim()
    if (value.length <= valueBudget) return this
    val compactValue = value
        .compressPromptLists("elements", maxOf(2, maxListEntries / 2))
        .compressPromptLists("targets", maxListEntries)
    val shortlistTargetSummary = compactValue.targetShortlistTargetSummary()
    val targetEvidenceSummary = compactValue.targetEvidenceSummariesForPrompt()
        .takeUnless { summary -> summary == "none" }
    val compactValueWithoutDuplicatedEvidence = if (targetEvidenceSummary != null) {
        compactValue.replaceFirst(Regex("""\s*targetEvidence\([^)]*\)"""), "")
    } else {
        compactValue
    }
    val compressibleValue = listOfNotNull(
        shortlistTargetSummary,
        targetEvidenceSummary,
        compactValueWithoutDuplicatedEvidence,
    )
        .joinToString(separator = " ")
    val queryAwareProtectedPatterns = compressibleValue.targetShortlistTargetValuePatterns() + protectedPatterns
    return "$label ${compressibleValue.extractivePromptCompress(valueBudget, queryAwareProtectedPatterns)}"
}

private fun String.compressPromptLists(
    listName: String,
    maxEntries: Int,
): String =
    replace(Regex("""$listName=\[([^]]*)]""")) { match ->
        val entries = match.groupValues[1]
            .split("; ")
            .filter { entry -> entry.isNotBlank() }
        if (entries.size <= maxEntries) {
            match.value
        } else {
            val kept = entries.take(maxEntries).joinToString(separator = "; ")
            "$listName=[$kept; ...+${entries.size - maxEntries}]"
        }
    }

private fun String.targetShortlistTargetValuePatterns(): List<Regex> {
    return targetShortlistTargetValues()
        .map { targetValue -> Regex("""target=${Regex.escape(targetValue)}""") }
}

internal fun String.targetShortlistTargetSummary(): String? {
    val values = targetShortlistTargetValues()
    if (values.isEmpty()) return null
    return values.joinToString(prefix = "shortlistTargets=[", postfix = "]", separator = "|") { value ->
        "target=$value"
    }
}

internal fun String.targetShortlistTargetValues(): List<String> {
    val shortlist = Regex("""targetShortlist\(([^)]*)\)""")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?: return emptyList()
    return shortlist
        .split(",", "|")
        .mapNotNull { part -> part.substringAfter("=", part).trim().takeIf { it.isNotBlank() } }
        .distinct()
}

internal fun String.targetShortlistSummariesForPrompt(): String =
    distinctPromptMatches(Regex("""targetShortlist\([^)]*\)"""))

internal fun String.targetEvidenceSummariesForPrompt(): String =
    distinctPromptMatches(Regex("""targetEvidence\([^)]*\)"""))

private fun String.distinctPromptMatches(pattern: Regex): String =
    pattern
        .findAll(this)
        .map { match -> match.value }
        .distinct()
        .joinToString(separator = " ")
        .ifBlank { "none" }

private fun String.extractivePromptCompress(
    maxChars: Int,
    protectedPatterns: List<Regex>,
): String {
    if (length <= maxChars) return this
    val protectedSnippets = protectedPatterns
        .flatMap { pattern -> pattern.findAll(this).map { match -> match.value } }
        .distinct()
    val protectedText = protectedSnippets.joinToString(separator = " | ")
        .takeIf { value -> value.isNotBlank() }
    val reserved = protectedText?.length?.plus(12) ?: 0
    val headBudget = (maxChars - reserved).coerceAtLeast(maxChars / 3)
    val head = take(headBudget.coerceAtMost(maxChars)).trimEnd()
    val joined = buildString {
        append(head)
        protectedText?.let { text ->
            append(" ... ")
            append(text)
        }
    }
    return if (joined.length <= maxChars) {
        joined
    } else {
        joined.take(maxChars - 3).trimEnd() + "..."
    }
}

private fun List<String>.prioritizedForObservationPrompt(): List<String> {
    val requiredPrefixes = listOf(
        "This is user-authorized",
        "Examples:",
        "Allowed tools:",
        "When LocalOnly observation evidence",
        "Use only target values",
        "Stop on",
        "If the latest action failed",
        "For an app search request",
        "For ui_scroll",
        "Do not output ordinary text",
        "User intent preview:",
        "Prior request details:",
        RUN_NOTES_PROMPT_LABEL,
        "Previous tool:",
        "Observation status:",
        "App search progress:",
        "Observation diagnostics:",
        "LocalOnly observation evidence:",
    )
    return filter { line ->
        line.isBlank() || line.isUserTextPromptLine() || requiredPrefixes.any { prefix -> line.startsWith(prefix) }
    }
}

private fun List<String>.joinPrioritizedObservationPrompt(maxChars: Int): String {
    val localEvidenceLine = firstOrNull { line -> line.startsWith("LocalOnly observation evidence:") }
    val diagnosticsLine = firstOrNull { line -> line.startsWith("Observation diagnostics:") }
    val baseLines = filterNot { line ->
        line.startsWith("LocalOnly observation evidence:") ||
            line.startsWith("Observation diagnostics:")
    }.prioritizedBaseObservationLines()
    val localEvidenceBudget = (maxChars * 0.42).toInt().coerceIn(320, 900)
    val diagnosticsBudget = (maxChars * 0.12).toInt().coerceIn(90, 220)
    val diagnosticsText = diagnosticsLine
        ?.extractivePromptCompress(diagnosticsBudget, diagnosticProtectedPromptPatterns)
    val localEvidenceText = localEvidenceLine
        ?.extractivePromptCompress(
            localEvidenceBudget,
            localEvidenceLine.targetShortlistTargetValuePatterns() + localEvidenceProtectedPromptPatterns,
        )
    // Base lines get whatever the evidence/diagnostics lines did not actually use (never less than
    // the old fixed split), so short diagnostics such as "none" stop starving the rule lines.
    val reservedChars = listOfNotNull(diagnosticsText, localEvidenceText).sumOf { text -> text.length + 1 }
    val baseBudget = (maxChars - reservedChars).coerceAtLeast(maxChars / 3)
    val parts = buildList {
        baseLines.joinPromptLinesWithinBudget(baseBudget)
            .takeIf { text -> text.isNotBlank() }
            ?.let(::add)
        diagnosticsText?.let(::add)
        localEvidenceText?.let(::add)
    }
    return parts.joinToString(separator = "\n").capPreservingUserTextLines(maxChars)
}

/**
 * Hard cap with "..." like before, except that a user-text line is never cut or dropped: when the
 * plain cut would reach one, non-user lines are shortened or skipped instead, and the result may
 * exceed [maxChars] only by user text. Shared with prompts outside the observation replanner
 * ([correctiveObservationModelPrompt]) that also carry user-authored text.
 */
internal fun String.capPreservingUserTextLines(maxChars: Int): String {
    if (length <= maxChars) return this
    val lines = lines()
    val userLines = lines.filter { line -> line.isUserTextPromptLine() }
    val plainCut = take(maxChars - 3).trimEnd() + "..."
    val plainCutLines = plainCut.lines()
    if (userLines.all { line -> line in plainCutLines }) return plainCut
    var pendingUserChars = userLines.sumOf { line -> line.length + 1 }
    var nonUserCut = false
    val out = StringBuilder()
    for (line in lines) {
        val separator = if (out.isEmpty()) "" else "\n"
        if (line.isUserTextPromptLine()) {
            pendingUserChars -= line.length + 1
            out.append(separator).append(line)
            continue
        }
        if (nonUserCut) continue
        val room = maxChars - out.length - separator.length - pendingUserChars
        if (line.length <= room) {
            out.append(separator).append(line)
        } else {
            nonUserCut = true
            if (room > 3) out.append(separator).append(line.take(room - 3).trimEnd()).append("...")
        }
    }
    return out.toString()
}

private fun List<String>.joinPromptLinesWithinBudget(maxChars: Int): String {
    val lines = filter { value -> value.isNotBlank() }
    val accepted = mutableListOf<String>()
    var used = 0
    // User-text lines are always accepted whole; their room is reserved before lower-priority lines.
    var pendingUserChars = lines.filter { line -> line.isUserTextPromptLine() }.sumOf { line -> line.length + 1 }
    for (line in lines) {
        if (line.isUserTextPromptLine()) {
            pendingUserChars -= line.length + 1
            used += line.length + if (accepted.isEmpty()) 0 else 1
            accepted += line
            continue
        }
        // The tool-signature line is bounded by construction and must never be cut mid-list: a
        // truncated tool list would silently hide allowed tools from the model.
        val candidate = if (line.length > 180 && !line.startsWith("Allowed tools:")) {
            line.extractivePromptCompress(
                maxChars = 180,
                protectedPatterns = listOf(Regex("""targetShortlist\([^)]*\)""")),
            )
        } else {
            line
        }
        val nextUsed = used + candidate.length + if (accepted.isEmpty()) 0 else 1
        if (nextUsed + pendingUserChars > maxChars) continue
        accepted += candidate
        used = nextUsed
    }
    return accepted.joinToString(separator = "\n")
}

private fun List<String>.prioritizedBaseObservationLines(): List<String> {
    val priorityPrefixes = listOf(
        "This is user-authorized",
        "Examples:",
        "User intent preview:",
        "用户请求:",
        "Previous tool:",
        "Observation status:",
        "App search progress:",
        "Prior request details:",
        "Allowed tools:",
        "When LocalOnly observation evidence",
        "Use only target values",
        "Stop on",
        "If the latest action failed",
        "For an app search request",
        "For ui_scroll",
        "Do not output ordinary text",
        RUN_NOTES_PROMPT_LABEL,
    )
    return withIndex()
        .sortedWith(
            compareBy<IndexedValue<String>> { indexed ->
                priorityPrefixes.indexOfFirst { prefix -> indexed.value.startsWith(prefix) }
                    .takeIf { index -> index >= 0 }
                    ?: Int.MAX_VALUE
            }.thenBy { indexed -> indexed.index },
        )
        .map { indexed -> indexed.value }
}
