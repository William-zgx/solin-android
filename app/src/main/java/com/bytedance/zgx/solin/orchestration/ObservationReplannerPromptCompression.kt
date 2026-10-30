package com.bytedance.zgx.solin.orchestration

private const val MAX_OBSERVATION_MODEL_PROMPT_CHARS = 2_600

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

            line.startsWith("User intent preview:") ->
                line.compressLabeledPromptValue(
                    label = "User intent preview:",
                    valueBudget = valueBudgets.intent,
                    protectedPatterns = emptyList(),
                )

            else -> line
        }
    }
    val compressed = compressedLines.joinToString(separator = "\n")
    if (compressed.length <= maxChars) return compressed

    return compressed.lines()
        .prioritizedForObservationPrompt()
        .joinPrioritizedObservationPrompt(maxChars)
}

private data class ObservationPromptValueBudgets(
    val localEvidence: Int,
    val diagnostics: Int,
    val priorRequests: Int,
    val summary: Int,
    val intent: Int,
) {
    companion object {
        fun forPrompt(maxChars: Int): ObservationPromptValueBudgets =
            ObservationPromptValueBudgets(
                localEvidence = (maxChars * 0.44).toInt().coerceIn(720, 1_600),
                diagnostics = (maxChars * 0.10).toInt().coerceIn(120, 360),
                priorRequests = (maxChars * 0.08).toInt().coerceIn(96, 260),
                summary = (maxChars * 0.06).toInt().coerceIn(80, 180),
                intent = (maxChars * 0.06).toInt().coerceIn(80, 180),
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
    Regex("""ui_[a-z_]+\{[^}]*target=[^}]*\}"""),
    Regex("""observe_current_screen\{[^}]*\}"""),
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

private fun String.targetShortlistTargetValues(): List<String> {
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
    Regex("""targetShortlist\([^)]*\)""")
        .findAll(this)
        .map { match -> match.value }
        .distinct()
        .joinToString(separator = " ")
        .ifBlank { "none" }

internal fun String.targetEvidenceSummariesForPrompt(): String =
    Regex("""targetEvidence\([^)]*\)""")
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
        "Output exactly one call",
        "Decide if",
        "When LocalOnly observation evidence",
        "Allowed LocalOnly tools",
        "Do not output web_search",
        "When LocalOnly evidence includes",
        "Use only target values",
        "Never output mode",
        "Prefer a type-tagged target",
        "For an app search request",
        "If only tap=",
        "Do not output ordinary text",
        "For ui_scroll",
        "If the latest action failed",
        "Stop on",
        "User intent preview:",
        "Prior request details:",
        "Previous tool:",
        "Observation status:",
        "App search progress:",
        "Observation diagnostics:",
        "LocalOnly observation evidence:",
    )
    return filter { line ->
        line.isBlank() || requiredPrefixes.any { prefix -> line.startsWith(prefix) }
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
    val baseBudget = (maxChars - localEvidenceBudget - diagnosticsBudget - 2).coerceAtLeast(maxChars / 3)
    val parts = buildList {
        baseLines.joinPromptLinesWithinBudget(baseBudget)
            .takeIf { text -> text.isNotBlank() }
            ?.let(::add)
        diagnosticsLine
            ?.extractivePromptCompress(diagnosticsBudget, diagnosticProtectedPromptPatterns)
            ?.let(::add)
        localEvidenceLine
            ?.extractivePromptCompress(
                localEvidenceBudget,
                localEvidenceLine.targetShortlistTargetValuePatterns() + localEvidenceProtectedPromptPatterns,
            )
            ?.let(::add)
    }
    val joined = parts.joinToString(separator = "\n")
    return if (joined.length <= maxChars) joined else joined.take(maxChars - 3).trimEnd() + "..."
}

private fun List<String>.joinPromptLinesWithinBudget(maxChars: Int): String {
    val accepted = mutableListOf<String>()
    var used = 0
    for (line in filter { value -> value.isNotBlank() }) {
        val candidate = if (line.length > 180) {
            line.extractivePromptCompress(
                maxChars = 180,
                protectedPatterns = listOf(Regex("""targetShortlist\([^)]*\)""")),
            )
        } else {
            line
        }
        val nextUsed = used + candidate.length + if (accepted.isEmpty()) 0 else 1
        if (nextUsed > maxChars) continue
        accepted += candidate
        used = nextUsed
    }
    return accepted.joinToString(separator = "\n")
}

private fun List<String>.prioritizedBaseObservationLines(): List<String> {
    val priorityPrefixes = listOf(
        "This is user-authorized",
        "Output exactly one call",
        "User intent preview:",
        "Previous tool:",
        "Observation status:",
        "App search progress:",
        "Prior request details:",
        "Use only target values",
        "For an app search request",
        "When LocalOnly observation evidence",
        "Allowed LocalOnly tools",
        "Do not output web_search",
        "When LocalOnly evidence includes",
        "Never output mode",
        "Stop on",
        "For ui_scroll",
        "Prefer a type-tagged target",
        "Do not output ordinary text",
        "If only tap=",
        "If the latest action failed",
        "Decide if",
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
