package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.device.AppSearchProgressEvidence
import com.bytedance.zgx.solin.device.ScreenObservation
import com.bytedance.zgx.solin.device.UiTargetEvidenceCandidate
import com.bytedance.zgx.solin.device.UiTargetKind
import com.bytedance.zgx.solin.device.UiTargetResolver
import com.bytedance.zgx.solin.device.normalizedLookupKey
import com.bytedance.zgx.solin.device.screenObservationFromJsonStringOrNull
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_LOCAL_OBSERVATION_ELEMENTS = 10
private const val MAX_LOCAL_OCR_BLOCKS = 8
private const val MAX_LOCAL_TARGET_CANDIDATES = 8
private const val MAX_LOCAL_TARGET_LABELS_PER_MODE = 4
private const val MAX_LOCAL_EVIDENCE_CHARS = 2_000
private const val MAX_LOCAL_DIAGNOSTICS_CHARS = 800

private val localObservationDiagnosticKeys = listOf(
    "actionType",
    "target",
    "direction",
    "status",
    "retryable",
    "failureKind",
    "searchVerificationStatus",
    "searchVerificationEvidence",
    "uiActionOutcome",
    "uiActionOutcomeReason",
    "appSearchProgressStage",
    "verificationSummary",
    "beforeObservationId",
    "afterObservationId",
    "beforeNodeCount",
    "afterNodeCount",
    "beforeActionableNodeCount",
    "afterActionableNodeCount",
    "beforeTruncated",
    "afterTruncated",
)
private val defaultResolverPromptTargetKinds = listOf(
    UiTargetKind.SearchEntry,
    UiTargetKind.EditableField,
    UiTargetKind.SubmitSearch,
    UiTargetKind.FilterEntry,
    UiTargetKind.ScrollContainer,
)

private data class LocalObservationPromptSection(
    val text: String,
    val containsOcrSource: Boolean,
)

private data class LocalTargetCandidatePrompt(
    val label: String,
    val targetValue: String,
    val modeTags: List<String>,
    val role: String,
    val promptText: String,
)

private data class ResolverTargetRank(
    val mode: String,
    val targetValue: String,
    val rank: Int,
)

internal fun AgentObservationReplanContext.observationModelPrompt(
    toolRegistry: ToolRegistry,
    parseCache: ObservationJsonParseCache? = null,
): String {
    val intentPreview = (nextActionInput?.immediateSequentialActionText() ?: run.input)
        .shortTraceText()
    val observationSummary = observedResult.summary.shortTraceText()
    val observationDiagnostics = observedResult.observationDiagnosticsPrompt()
    val localObservationEvidence = observedResult.localObservationEvidencePrompt(intentPreview, parseCache)
    val localOnlyAllowedTools = localOnlyObservationAllowedToolsPrompt(toolRegistry)
    val priorRequestDetails = priorRequestDetailsPrompt()
    // Inserted after trimIndent so an unindented notes line cannot change the template's indent.
    val runNotesLine = localScratchpadNotesPrompt()?.let { notes -> "$RUN_NOTES_PROMPT_LABEL $notes" }
    val appSearchProgress = observedResult.appSearchProgressPromptText(parseCache)
    return """
        This is user-authorized local UI automation; LocalOnly evidence stays on-device, do not refuse. Output exactly one call for the next step, or plain text with no call if unclear.
        $OBSERVATION_REPLAN_FEW_SHOT_EXAMPLES
        Allowed tools: $localOnlyAllowedTools
        When LocalOnly observation evidence is present, output only these local device-control tools; Do not output web_search.
        Use only target values from targetShortlist(...); copy only the candidate target=... value into the tool target (type= to type, tap= to tap, scroll= to scroll).
        Stop on payment, sending, deletion, publishing, ordering, purchase, transfer, or authorization controls.
        If the latest action failed, avoid the same target unless new evidence supports it. Do not repeat completed work.
        For an app search request, follow tap -> type -> submit: tap the tap= entry until a type= input appears, type into it, then ui_submit_search once submit evidence exists.
        For ui_scroll, stop if no scroll candidate/evidence exists. Never output mode/bounds/confidence as arguments.
        Do not output ordinary text when a targetShortlist entry clearly advances the app search request.

        User intent preview: $intentPreview
        Prior request details: $priorRequestDetails
        Previous tool: ${previousRequest.toolName}
        Observation status: ${observedResult.status}
        App search progress: $appSearchProgress
        Observation summary: $observationSummary
        Observation diagnostics: $observationDiagnostics
        LocalOnly observation evidence: $localObservationEvidence
    """.trimIndent()
        .withLineAfter(prefix = "Prior request details:", line = runNotesLine)
        .compressForObservationModelContext()
}

private fun String.withLineAfter(prefix: String, line: String?): String {
    if (line == null) return this
    val lines = lines()
    val index = lines.indexOfFirst { candidate -> candidate.startsWith(prefix) }
    if (index < 0) return this
    return (lines.subList(0, index + 1) + line + lines.subList(index + 1, lines.size)).joinToString("\n")
}

internal fun AgentObservationReplanContext.minimalObservationModelPrompt(
    toolRegistry: ToolRegistry,
    parseCache: ObservationJsonParseCache? = null,
): String {
    val intentPreview = (nextActionInput?.immediateSequentialActionText() ?: run.input)
        .shortTraceText(maxLength = 120)
    val localOnlyAllowedTools = localOnlyObservationAllowedToolsPrompt(toolRegistry)
    val localEvidence = observedResult.localObservationEvidencePrompt(intentPreview, parseCache)
    val targetShortlists = localEvidence.targetShortlistSummariesForPrompt()
    val targetEvidence = localEvidence.targetEvidenceSummariesForPrompt()
    val targetAnchors = targetShortlists.targetShortlistTargetSummary().orEmpty()
    val appSearchProgress = observedResult.appSearchProgressPromptText(parseCache)
    return """
        本机已获得用户授权执行手机 UI 自动化。只输出一个 call，不解释，不拒绝。
        允许工具: $localOnlyAllowedTools
        用户请求: $intentPreview
        当前进度: $appSearchProgress
        当前屏幕可用目标: $targetShortlists $targetEvidence $targetAnchors
        上一步工具: ${previousRequest.toolName}
        如果是应用内搜索，严格按 tap -> type -> submit：当前只有 tap= 搜索入口、还没有 type= 输入框时先 ui_tap；type= 输入框出现后再 ui_type_text；已输入且有提交证据后 ui_submit_search{}。
        若要点击，输出 call:ui_tap{"target":"候选target"}。
        若要输入搜索词，输出 call:ui_type_text{"target":"候选target","text":"用户要搜索的词"}。
        若要提交搜索，输出 call:ui_submit_search{}。
    """.trimIndent().compressForObservationModelContext(maxChars = 1_200)
}

private fun ToolResult.observationDiagnosticsPrompt(): String {
    val diagnostics = buildList {
        if (status == ToolStatus.Failed) {
            add("resultRetryable=$retryable")
        }
        error?.code?.let { code -> add("errorCode=${code.name}") }
        localObservationDiagnosticKeys.forEach { key ->
            data[key]
                ?.takeIf { value -> value.isNotBlank() }
                ?.shortTraceText(maxLength = 160)
                ?.let { value -> add("$key=$value") }
        }
    }
    return diagnostics
        .joinToString(separator = "; ")
        .ifBlank { "none" }
        .shortTraceText(maxLength = MAX_LOCAL_DIAGNOSTICS_CHARS)
}

internal fun ToolResult.localObservationEvidencePrompt(
    intentPreview: String,
    parseCache: ObservationJsonParseCache? = null,
): String {
    val intentTargetKind = intentPreview.promptResolverTargetKind()
    val observationSections = localObservationJsonKeys.mapNotNull { key ->
        data[key]
            ?.takeIf { value -> value.isNotBlank() }
            ?.toScreenObservationPromptSection(key, intentTargetKind, parseCache)
    }
    val sections = buildList {
        observationSections
            .map { section -> section.text }
            .forEach(::add)
        if (observationSections.none { section -> section.containsOcrSource }) {
            data["ocrBlocksJson"]
                ?.takeIf { value -> value.isNotBlank() }
                ?.toOcrBlocksPromptSection(
                    includeExecutableTargets = executableStandaloneOcrBlocksJson() != null,
                    parseCache = parseCache,
                )
                ?.let(::add)
        }
        data["ocrText"]
            ?.takeIf { value -> value.isNotBlank() }
            ?.shortTraceText(maxLength = 360)
            ?.let { text -> add("ocrText=$text") }
        data["screenText"]
            ?.takeIf { value -> value.isNotBlank() }
            ?.shortTraceText(maxLength = 360)
            ?.let { text -> add("screenText=$text") }
        data["screenObservationDiffSummary"]
            ?.takeIf { value -> value.isNotBlank() }
            ?.shortTraceText(maxLength = 520)
            ?.let { text -> add("screenObservationDiffSummary=$text") }
    }
    return sections.joinToString(separator = " | ")
        .ifBlank { "none" }
        .shortTraceText(maxLength = MAX_LOCAL_EVIDENCE_CHARS)
    }

/**
 * App-search progress prompt text, reusing the invocation-local typed [ScreenObservation] parse via
 * [parseCache] when present so the stage classification does not re-parse observation JSON. Falls
 * back to the default [AppSearchProgressEvidence.fromData] parser when no cache is threaded.
 */
internal fun ToolResult.appSearchProgressPromptText(parseCache: ObservationJsonParseCache?): String =
    if (parseCache == null) {
        AppSearchProgressEvidence.fromData(data)
    } else {
        AppSearchProgressEvidence.fromData(data) { rawJson -> parseCache.screenObservationOrNull(rawJson) }
    }.toPromptText()

internal fun AgentObservationReplanContext.localOnlyObservationAllowedToolsPrompt(toolRegistry: ToolRegistry): String =
    observationReplanToolSignatures(toolRegistry)

/** True when the latest observation carries LocalOnly evidence, so only device-control tools pass. */
internal fun AgentObservationReplanContext.isLocalOnlyGatedObservation(toolRegistry: ToolRegistry): Boolean =
    observedResult.hasLocalOnlyObservationEvidence(toolRegistry.specFor(previousRequest.toolName))

private fun String.toScreenObservationPromptSection(
    key: String,
    intentTargetKind: UiTargetKind?,
    parseCache: ObservationJsonParseCache? = null,
): LocalObservationPromptSection? =
    runCatching {
        val json = JSONObject(this)
        val elements = parseCache?.observationElements(this)
            ?: (json.optJSONArray("elements") ?: JSONArray())
        val selectedElements = elements.selectedObservationElementsForPrompt()
        val elementSummaries = selectedElements
            .map { element -> element.toObservationElementPromptText() }
        val fusedTargetCandidates = elements.ocrGroundedAccessibilityTargetCandidates()
        val resolverTargetRanks = (
            parseCache?.screenObservationOrNull(this)
                ?: screenObservationFromJsonStringOrNull(this)
            )
            .resolverTargetRanks(intentTargetKind)
        val targetCandidates = (
            fusedTargetCandidates +
                selectedElements.mapNotNull { element -> element.toTargetCandidatePrompt() }
            )
            .distinctBy { candidate -> candidate.promptText }
            .rankedByResolver(resolverTargetRanks)
            .take(MAX_LOCAL_TARGET_CANDIDATES)
        val targetSummaries = targetCandidates
            .map { candidate -> candidate.promptText }
        val targetShortlist = targetCandidates.targetShortlistPromptText()
        val targetEvidence = targetCandidates.targetEvidencePromptText()
        val text = buildString {
            append(key)
            append("(id=")
            append(json.optString("observationId", "unknown"))
            append(",package=")
            append(json.optNullableString("packageName") ?: "unknown")
            append(",sources=")
            append(json.optJSONArray("sources")?.joinCompact() ?: "none")
            append(",sourceCounts=")
            append(json.optJSONObject("sourceCounts")?.joinCompact() ?: "none")
            append(",elements=")
            append(json.optInt("elementCount", elements.length()))
            append(",truncated=")
            append(json.optBoolean("truncated", false))
            append(")")
            targetShortlist?.let { summary ->
                append(" targetShortlist(")
                append(summary)
                append(")")
            }
            targetEvidence?.let { summary ->
                append(" targetEvidence(")
                append(summary)
                append(")")
            }
            if (elementSummaries.isNotEmpty()) {
                append(" elements=[")
                append(elementSummaries.joinToString(separator = "; "))
                append("]")
            }
            if (targetSummaries.isNotEmpty()) {
                append(" targets=[")
                append(targetSummaries.joinToString(separator = "; "))
                append("]")
            }
        }
        LocalObservationPromptSection(
            text = text,
            containsOcrSource = json.containsOcrObservation(elements),
        )
    }.getOrNull()

private fun JSONArray.selectedObservationElementsForPrompt(): List<JSONObject> {
    val indexed = indexedObjects()
    val selectedIndices = linkedSetOf<Int>()

    fun includeMatching(limit: Int, predicate: (JSONObject) -> Boolean) {
        indexed
            .asSequence()
            .filter { (_, element) -> predicate(element) }
            .take(limit)
            .forEach { (index, _) -> selectedIndices += index }
    }

    fun includeOcrCandidates(limit: Int) {
        indexed
            .asSequence()
            .filter { (_, element) -> element.optString("source") == "ocr" }
            .filter { (_, element) -> element.optString("text").normalizedLookupKey().isNotBlank() }
            .filter { (_, element) -> element.optJSONObject("bounds") != null }
            .sortedWith(
                compareBy<Pair<Int, JSONObject>> { (_, element) -> element.ocrPromptRolePriority() }
                    .thenBy { (index, _) -> index },
            )
            .distinctBy { (_, element) -> element.optString("text").normalizedLookupKey() }
            .take(limit)
            .forEach { (index, _) -> selectedIndices += index }
    }

    includeMatching(limit = 6) { element ->
        element.optString("source") == "accessibility" && element.hasActionableModeForPrompt()
    }
    includeOcrCandidates(limit = 4)
    includeMatching(limit = 6) { element -> element.optString("source") == "accessibility" }
    indexed.forEach { (index, _) ->
        if (selectedIndices.size < MAX_LOCAL_OBSERVATION_ELEMENTS) {
            selectedIndices += index
        }
    }
    return selectedIndices
        .take(MAX_LOCAL_OBSERVATION_ELEMENTS)
        .mapNotNull(::optJSONObject)
}

private fun JSONObject.ocrPromptRolePriority(): Int =
    when (optString("role")) {
        "ocr_element" -> 0
        "ocr_line" -> 1
        "ocr_block" -> 2
        else -> 3
    }

private fun JSONObject.toObservationElementPromptText(): String {
    val clickability = optJSONObject("clickability")
    val flags = buildList {
        if (clickability?.optBoolean("clickable", false) == true) add("clickable")
        if (clickability?.optBoolean("editable", false) == true) add("editable")
        if (clickability?.optBoolean("scrollable", false) == true) add("scrollable")
        if (clickability?.optBoolean("enabled", true) == false) add("disabled")
    }.joinToString(separator = "+").ifBlank { "static" }
    val text = optString("text")
        .takeIf { value -> value.isNotBlank() }
        ?.shortTraceText(maxLength = 72)
        ?: "no_text"
    val bounds = optJSONObject("bounds")?.boundsPromptText()
    return buildString {
        append(optString("id", "unknown"))
        append("{")
        append(optString("source", "unknown"))
        append("/")
        append(optString("role", "unknown"))
        append(",text=")
        append(text)
        bounds?.let { value ->
            append(",bounds=")
            append(value)
        }
        append(",")
        append(flags)
        append(",confidence=")
        append(optDouble("confidence", 0.0).formatPromptScore())
        append("}")
    }
}

private fun JSONObject.toTargetCandidatePrompt(): LocalTargetCandidatePrompt? {
    val text = optString("text")
        .takeIf { value -> value.isNotBlank() }
        ?.shortTraceText(maxLength = 72)
        ?: return null
    val clickability = optJSONObject("clickability")
    if (clickability?.optBoolean("enabled", true) == false) return null
    val source = optString("source", "unknown")
    val role = optString("role", "unknown")
    if (source == "ocr" && optJSONObject("bounds") == null) return null
    val modeTags = buildList {
        addAll(clickability.clickabilityModeTags())
        if (source == "ocr") add("ocrFallback")
    }
    if (modeTags.isEmpty()) return null
    val targetValue = optString("id")
        .takeIf { value -> value.isNotBlank() }
        ?.shortTraceText(maxLength = 72)
        ?: text
    val bounds = optJSONObject("bounds")?.boundsPromptText()
    val promptText = buildString {
        append(text)
        append("{target=")
        append(targetValue)
        append(",modeTags=")
        append(modeTags.joinToString(separator = "+"))
        append(",source=")
        append(source)
        append(",role=")
        append(role)
        bounds?.let { value ->
            append(",bounds=")
            append(value)
        }
        append(",confidence=")
        append(optDouble("confidence", 0.0).formatPromptScore())
        append("}")
    }
    return LocalTargetCandidatePrompt(
        label = text,
        targetValue = targetValue,
        modeTags = modeTags,
        role = role,
        promptText = promptText,
    )
}

/** Target mode tags implied by a `clickability` object, in prompt order: type, tap, scroll. */
private fun JSONObject?.clickabilityModeTags(): List<String> {
    val clickability = this
    return buildList {
        if (clickability?.optBoolean("editable", false) == true) add("type")
        if (clickability?.optBoolean("clickable", false) == true) add("tap")
        if (clickability?.optBoolean("scrollable", false) == true) add("scroll")
    }
}

private fun JSONArray.ocrGroundedAccessibilityTargetCandidates(): List<LocalTargetCandidatePrompt> {
    val allElements = objects()
    val ocrElements = allElements
        .filter { element ->
            element.optString("source") == "ocr" &&
                element.optString("text").isNotBlank() &&
                element.optJSONObject("bounds") != null
        }
    if (ocrElements.isEmpty()) return emptyList()
    return allElements
        .asSequence()
        .filter { element ->
            element.optString("source") == "accessibility" &&
                element.optString("text").isBlank() &&
                element.hasActionableModeForPrompt() &&
                element.optJSONObject("bounds") != null
        }
        .mapNotNull { element -> element.toOcrGroundedAccessibilityTargetCandidate(ocrElements) }
        .distinctBy { candidate -> candidate.targetValue }
        .take(MAX_LOCAL_TARGET_CANDIDATES)
        .toList()
}

private fun JSONObject.toOcrGroundedAccessibilityTargetCandidate(
    ocrElements: List<JSONObject>,
): LocalTargetCandidatePrompt? {
    val bounds = optJSONObject("bounds") ?: return null
    val ocrText = ocrElements
        .asSequence()
        .filter { element -> bounds.containsOcrGroundingElementBounds(element.optJSONObject("bounds")) }
        .sortedBy { element -> element.optJSONObject("bounds")?.optInt("top", Int.MAX_VALUE) ?: Int.MAX_VALUE }
        .map { element -> element.optString("text").trim() }
        .filter { text -> text.isNotBlank() }
        .distinctBy { text -> text.normalizedLookupKey() }
        .take(2)
        .joinToString(" ")
        .takeIf { text -> text.isNotBlank() }
        ?.shortTraceText(maxLength = 72)
        ?: return null
    val targetValue = optString("id")
        .takeIf { value -> value.isNotBlank() }
        ?.shortTraceText(maxLength = 72)
        ?: return null
    val clickability = optJSONObject("clickability")
    val role = optString("role", "unknown")
    val modeTags = clickability.clickabilityModeTags()
    if (modeTags.isEmpty()) return null
    val promptText = buildString {
        append(ocrText)
        append("{target=")
        append(targetValue)
        append(",modeTags=")
        append(modeTags.joinToString(separator = "+"))
        append(",source=accessibility+ocr,role=")
        append(role)
        append(",bounds=")
        append(bounds.boundsPromptText())
        append(",confidence=")
        append(optDouble("confidence", 1.0).formatPromptScore())
        append("}")
    }
    return LocalTargetCandidatePrompt(
        label = ocrText,
        targetValue = targetValue,
        modeTags = modeTags,
        role = role,
        promptText = promptText,
    )
}

private fun List<LocalTargetCandidatePrompt>.targetShortlistPromptText(): String? {
    val supportedModeTags = listOf("type", "tap", "scroll", "ocrFallback")
    return supportedModeTags
        .mapNotNull { mode ->
            val targetValues = asSequence()
                .filter { candidate -> mode in candidate.modeTags }
                .map { candidate -> candidate.targetValue }
                .distinct()
                .take(MAX_LOCAL_TARGET_LABELS_PER_MODE)
                .toList()
            targetValues
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = "|")
                ?.let { joinedTargets -> "$mode=$joinedTargets" }
        }
        .joinToString(separator = ",")
        .takeIf { value -> value.isNotBlank() }
}

private fun List<LocalTargetCandidatePrompt>.targetEvidencePromptText(): String? =
    take(MAX_LOCAL_TARGET_LABELS_PER_MODE)
        .joinToString(separator = "; ") { candidate ->
            buildString {
                append("id=")
                append(candidate.targetValue.shortTraceText(maxLength = 96))
                append(",text=")
                append(candidate.label.shortTraceText(maxLength = 48))
                append(",mode=")
                append(candidate.modeTags.joinToString(separator = "+"))
                append(",role=")
                append(candidate.role.shortTraceText(maxLength = 32))
            }
        }
        .takeIf { value -> value.isNotBlank() }

private fun List<LocalTargetCandidatePrompt>.rankedByResolver(
    resolverTargetRanks: List<ResolverTargetRank>,
): List<LocalTargetCandidatePrompt> {
    if (resolverTargetRanks.isEmpty()) return this
    val rankByModeAndTarget = mutableMapOf<String, Int>()
    resolverTargetRanks.forEach { rank ->
        val key = "${rank.mode}:${rank.targetValue.normalizedLookupKey()}"
        rankByModeAndTarget[key] = minOf(rankByModeAndTarget[key] ?: Int.MAX_VALUE, rank.rank)
    }
    return withIndex()
        .sortedWith(
            compareBy<IndexedValue<LocalTargetCandidatePrompt>> { indexed ->
                indexed.value.bestResolverRank(rankByModeAndTarget)
            }.thenBy { indexed -> indexed.index },
        )
        .map { indexed -> indexed.value }
}

private fun LocalTargetCandidatePrompt.bestResolverRank(rankByModeAndTarget: Map<String, Int>): Int =
    modeTags.minOfOrNull { mode ->
        rankByModeAndTarget["$mode:${targetValue.normalizedLookupKey()}"]
            ?: rankByModeAndTarget["$mode:${label.normalizedLookupKey()}"]
            ?: Int.MAX_VALUE
    } ?: Int.MAX_VALUE

private fun String?.promptResolverTargetKind(): UiTargetKind? {
    val resolverKind = UiTargetResolver.kindForTarget(this)
    if (resolverKind != null) return resolverKind
    val normalized = normalizedLookupKey()
    if (normalized.isBlank()) return null
    return if (
        listOf("滚动", "滑动", "下滑", "上滑", "scroll", "swipe").any { marker ->
            normalized.contains(marker.normalizedLookupKey())
        }
    ) {
        UiTargetKind.ScrollContainer
    } else {
        null
    }
}

private fun ScreenObservation?.resolverTargetRanks(intentTargetKind: UiTargetKind?): List<ResolverTargetRank> =
    this?.let { observation ->
        val rankedKinds = defaultResolverPromptTargetKinds.prioritizing(intentTargetKind)
        rankedKinds.flatMapIndexed { kindIndex, kind ->
            UiTargetResolver.explain(observation, kind)
                .rankedCandidates
                .take(MAX_LOCAL_TARGET_LABELS_PER_MODE)
                .flatMapIndexed { candidateIndex, candidate ->
                    candidate.toResolverTargetRanks(
                        kind = kind,
                        rank = (kindIndex * MAX_LOCAL_TARGET_LABELS_PER_MODE) + candidateIndex,
                    )
                }
        }
    }.orEmpty()

private fun List<UiTargetKind>.prioritizing(kind: UiTargetKind?): List<UiTargetKind> =
    if (kind == null || kind !in this) {
        this
    } else {
        listOf(kind) + filterNot { candidate -> candidate == kind }
    }

private fun UiTargetEvidenceCandidate.toResolverTargetRanks(
    kind: UiTargetKind,
    rank: Int,
): List<ResolverTargetRank> {
    val targetValue = nodeId?.takeIf { value -> value.isNotBlank() } ?: label
    val modes = when (kind) {
        UiTargetKind.SearchEntry -> buildList {
            if (editable) add("type")
            if (clickable || !editable) add("tap")
            if (source.schemaValue == "ocr") add("ocrFallback")
        }

        UiTargetKind.EditableField -> buildList {
            add("type")
            if (clickable) add("tap")
        }

        UiTargetKind.SubmitSearch,
        UiTargetKind.FilterEntry -> listOf("tap")

        UiTargetKind.ScrollContainer -> listOf("scroll")
        UiTargetKind.ResultItem -> emptyList()
    }
    return modes
        .distinct()
        .flatMap { mode ->
            listOf(
                ResolverTargetRank(mode = mode, targetValue = targetValue, rank = rank),
                ResolverTargetRank(mode = mode, targetValue = label, rank = rank),
            )
        }
}

private fun String.toOcrBlocksPromptSection(
    includeExecutableTargets: Boolean,
    parseCache: ObservationJsonParseCache? = null,
): String? =
    runCatching {
        val blocks = parseCache?.ocrBlocksArrayOrNull(this) ?: JSONArray(this)
        val selectedBlocks = blocks.indexedObjects(limit = MAX_LOCAL_OCR_BLOCKS)
        val summaries = selectedBlocks
            .mapNotNull { (index, block) -> block.toOcrBlockPromptText(index) }
        val targetCandidates = if (includeExecutableTargets) {
            selectedBlocks
                .flatMap { (index, block) -> block.toOcrTargetCandidatePrompts(index) }
                .distinctBy { candidate -> candidate.promptText }
                .take(MAX_LOCAL_TARGET_CANDIDATES)
        } else {
            emptyList()
        }
        buildString {
            append("ocrBlocks(count=")
            append(blocks.length())
            append(")")
            targetCandidates.targetShortlistPromptText()?.let { summary ->
                append(" targetShortlist(")
                append(summary)
                append(")")
            }
            targetCandidates.targetEvidencePromptText()?.let { summary ->
                append(" targetEvidence(")
                append(summary)
                append(")")
            }
            if (summaries.isNotEmpty()) {
                append("=[")
                append(summaries.joinToString(separator = "; "))
                append("]")
            }
            if (targetCandidates.isNotEmpty()) {
                append(" targets=[")
                append(targetCandidates.joinToString(separator = "; ") { candidate -> candidate.promptText })
                append("]")
            }
        }
    }.getOrNull()

private fun JSONObject.toOcrTargetCandidatePrompts(blockIndex: Int): List<LocalTargetCandidatePrompt> =
    toOcrTargetEvidencePrompts(blockIndex).map { evidence -> evidence.toOcrTargetCandidatePrompt() }

private fun OcrTargetEvidencePrompt.toOcrTargetCandidatePrompt(): LocalTargetCandidatePrompt {
    val text = text.shortTraceText(maxLength = 72)
    val targetValue = id.shortTraceText(maxLength = 96)
    val promptText = buildString {
        append(text)
        append("{target=")
        append(targetValue)
        append(",modeTags=ocrFallback,source=ocr,role=")
        append(role)
        append(",bounds=")
        append(bounds)
        append("}")
    }
    return LocalTargetCandidatePrompt(
        label = text,
        targetValue = targetValue,
        modeTags = listOf("ocrFallback"),
        role = role,
        promptText = promptText,
    )
}

private fun JSONObject.toOcrBlockPromptText(index: Int): String {
    val text = optString("text")
        .shortTraceText(maxLength = 72)
        .ifBlank { "no_text" }
    val bounds = optJSONObject("bounds")?.boundsPromptText()
    return buildString {
        append("block")
        append(index)
        append("{text=")
        append(text)
        bounds?.let { value ->
            append(",bounds=")
            append(value)
        }
        append("}")
    }
}

private fun JSONObject.containsOcrObservation(elements: JSONArray): Boolean =
    optJSONObject("sourceCounts")?.optInt("ocr", 0)?.let { count -> count > 0 } == true ||
        optJSONArray("sources")?.containsString("ocr") == true ||
        (0 until elements.length()).any { index ->
            elements.optJSONObject(index)?.optString("source") == "ocr"
        }

private fun Double.formatPromptScore(): String =
    String.format(java.util.Locale.US, "%.2f", this)

