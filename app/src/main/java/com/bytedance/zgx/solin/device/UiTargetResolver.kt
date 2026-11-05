package com.bytedance.zgx.solin.device

import java.util.Locale

/** Node-id suffix marking a synthesized trailing-affordance sub-region (id "<parentId>::affordance"). */
private const val AFFORDANCE_NODE_ID_SUFFIX = "::affordance"

enum class UiTargetEvidenceSource(val schemaValue: String, val priority: Int) {
    Accessibility("accessibility", 100),
    Ocr("ocr", 60),
    OcrPlaceholder("ocr_placeholder", 40),
    VisionPlaceholder("vision_placeholder", 40),
}

enum class UiTargetFallbackType(
    val schemaValue: String,
    val priority: Int,
    val requiresEvidence: Boolean,
) {
    None("none", 100, false),
    OcrGrounding("ocr_grounding", 60, true),
    OcrGroundingPlaceholder("ocr_grounding_placeholder", 40, true),
    VisionGroundingPlaceholder("vision_grounding_placeholder", 40, true),
    Coordinate("coordinate", 10, true),
}

enum class UiTargetVerificationSignal(val schemaValue: String) {
    EditableFocusedOrTextAccepted("editable_focused_or_text_accepted"),
    SearchResultEvidence("search_result_evidence"),
    UiMutationOrActionAccepted("ui_mutation_or_action_accepted"),
    None("none"),
}

data class UiTargetExplanationContract(
    val source: UiTargetEvidenceSource,
    val fallbackType: UiTargetFallbackType,
    val expectedVerificationSignal: UiTargetVerificationSignal,
    val requiresAdditionalEvidence: Boolean,
    val reason: String,
)

object UiTargetResolver {
    fun explain(
        snapshot: ScreenStateSnapshot,
        kind: UiTargetKind,
        target: String? = null,
        profile: AppInteractionProfile? = AppInteractionProfiles.forPackage(snapshot.packageName),
    ): UiTargetResolutionEvidence =
        evidence(
            kind = kind,
            target = target,
            packageName = snapshot.packageName,
            candidates = rankedCandidates(snapshot.nodes.map(::GroundingNode), kind, target, profile),
        )

    fun explain(
        observation: ScreenObservation,
        kind: UiTargetKind,
        target: String? = null,
        profile: AppInteractionProfile? = AppInteractionProfiles.forPackage(observation.packageName),
    ): UiTargetResolutionEvidence =
        evidence(
            kind = kind,
            target = target,
            packageName = observation.packageName,
            candidates = rankedCandidates(observation.toGroundingNodes(), kind, target, profile),
        )

    private fun evidence(
        kind: UiTargetKind,
        target: String?,
        packageName: String?,
        candidates: List<UiTargetEvidenceCandidate>,
    ): UiTargetResolutionEvidence {
        val selectableCandidates = candidates.filter { candidate -> candidate.isSelectable(kind) }
        return UiTargetResolutionEvidence(
            kind = kind,
            target = target,
            packageName = packageName,
            selectedNodeId = selectableCandidates.firstOrNull()?.nodeId,
            rankedCandidates = selectableCandidates.ifEmpty { candidates },
            failureKind = if (selectableCandidates.isEmpty()) kind.missingResolutionFailureKind() else null,
        )
    }

    fun kindForTarget(target: String?): UiTargetKind? {
        val normalized = target.normalizedLookupKey()
        return when {
            normalized.isBlank() -> null
            listOf("提交搜索", "搜索按钮", "submitsearch", "searchbutton")
                .any { normalized.contains(it.normalizedLookupKey()) } -> UiTargetKind.SubmitSearch
            listOf(
                "搜索输入框",
                "搜索入口",
                "搜索框",
                "搜索",
                "搜",
                "검색",
                "地址栏",
                "地址",
                "网址",
                "url",
                "omnibox",
                "search",
                "searchentry",
                "searchbox",
                "目的地",
                "去哪儿",
                "搜地点",
                "搜索地点",
                "终点",
            )
                .any { normalized.contains(it.normalizedLookupKey()) } -> UiTargetKind.SearchEntry
            listOf("输入框", "输入", "editable", "textfield")
                .any { normalized.contains(it.normalizedLookupKey()) } -> UiTargetKind.EditableField
            listOf("筛选", "filter").any { normalized.contains(it.normalizedLookupKey()) } -> UiTargetKind.FilterEntry
            else -> null
        }
    }

    private fun rankedCandidates(
        nodes: List<GroundingNode>,
        kind: UiTargetKind,
        target: String?,
        profile: AppInteractionProfile?,
    ): List<UiTargetEvidenceCandidate> {
        val metrics = SnapshotBoundsMetrics.from(nodes.map { node -> node.node })
        val normalizedTarget = target.normalizedLookupKey()
        val scored = nodes
            .mapNotNull { node -> scoreNode(node, kind, normalizedTarget, profile, metrics) }
        // A synthesized trailing-affordance candidate (id "<parent>::affordance", fallbackType
        // OcrGrounding) must never outrank an UNRELATED real accessibility control that is itself
        // selectable for this kind — otherwise an exact text match on a right-30% sub-region could
        // win over a genuine labeled control elsewhere (wrong tap). It MAY still outrank its own
        // parent row (competing with the parent is the affordance's purpose — the sub-region is a
        // more precise target than the whole row). So: demote each synthesized affordance below any
        // selectable real (None) candidate other than its own parent; score breaks ties.
        val selectableRealIds = scored
            .filter { it.fallbackType == UiTargetFallbackType.None && it.isSelectable(kind) }
            .mapNotNull { it.nodeId }
            .toSet()
        fun isDemotedAffordance(candidate: UiTargetEvidenceCandidate): Boolean {
            val nodeId = candidate.nodeId ?: return false
            if (candidate.fallbackType == UiTargetFallbackType.None) return false
            if (!nodeId.endsWith(AFFORDANCE_NODE_ID_SUFFIX)) return false
            val parentId = nodeId.removeSuffix(AFFORDANCE_NODE_ID_SUFFIX)
            return selectableRealIds.any { realId -> realId != parentId }
        }
        return scored.sortedWith(
            compareByDescending<UiTargetEvidenceCandidate> { candidate ->
                if (isDemotedAffordance(candidate)) 0 else 1
            }.thenByDescending { candidate -> candidate.score.finalScore },
        )
    }

    private fun scoreNode(
        groundingNode: GroundingNode,
        kind: UiTargetKind,
        normalizedTarget: String,
        profile: AppInteractionProfile?,
        metrics: SnapshotBoundsMetrics,
    ): UiTargetEvidenceCandidate? {
        val node = groundingNode.node
        val label = groundingNode.labelOverride ?: node.visibleLabel()
        val outcome = scoreTargetCandidate(
            node = node,
            label = label,
            kind = kind,
            normalizedTarget = normalizedTarget,
            profile = profile,
            metrics = metrics,
            fallbackPenalty = groundingNode.fallbackPenalty(),
        ) ?: return null
        val resolvedLabel = label.ifBlank { node.className }
        return UiTargetEvidenceCandidate(
            nodeId = node.id,
            label = resolvedLabel,
            bounds = node.bounds,
            source = groundingNode.source,
            fallbackType = groundingNode.fallbackType,
            clickable = node.clickable,
            editable = node.editable,
            scrollable = node.scrollable,
            enabled = node.enabled,
            matchedProfileHint = outcome.matchedProfileHint,
            score = outcome.score,
            reason = groundingNode.reasonFor(resolvedLabel),
        )
    }
}

// ── The single UI-target scoring core ────────────────────────────────────────────────────────────
//
// There used to be two independent implementations of this arithmetic: this one (reached by
// `UiTargetResolver`, and therefore by the offline `UiAutomatorDumpReplayTest` corpus) and a private
// copy inside `SolinAccessibilityService.NodeCandidate` that is what actually decides where a real
// device gets tapped. The weights, the label-noise curve and the two duplicated minimum-score tables
// had already drifted apart, so the corpus protected the path nobody runs on a phone.
//
// Both callers now go through [scoreTargetCandidate]. Where the two differed, the resolver's numbers
// win — those are the ones the replay corpus pins — except for the deliberately runtime-only
// behaviours, which are called out at their branches below:
//   * the direct text/description evidence table and its continuous label-noise curve, which the
//     resolver had no equivalent for, are preserved verbatim. Note this table is NOT limited to a null
//     [kind]: upstream fell back to it whenever semantic scoring came up empty, whatever the kind;
//   * [effectivelyClickable] keeps the runtime's tap-through-a-clickable-ancestor reach.

/** One scored candidate: the component breakdown plus the profile hint that matched, if any. */
internal data class UiTargetScoringOutcome(
    val score: UiTargetScoreComponents,
    val matchedProfileHint: String?,
)

/**
 * Scores one node against [normalizedTarget] for [kind], or returns null when it is not a candidate.
 *
 * @param label the text the caller wants scored. The resolver passes text+contentDescription; the
 * runtime click path additionally folds in `viewIdResourceName` and the class name, which is how it
 * can still reach an icon-only node whose only search evidence is `…:id/search_bar`.
 * @param kind null means "free-text target with no recognized semantic kind" — a runtime-only mode. Note
 * a non-null kind does not guarantee semantic scoring succeeds; when it comes up empty the node still
 * falls back to the direct text/description table, exactly as a null kind does.
 * @param effectivelyClickable whether a tap on this node lands on something clickable, directly or
 * through an ancestor. A lambda, not a value: on the runtime path answering it means walking the node's
 * parents (binder round-trips) and `findTargetCandidates` scores up to 240 nodes per action, so it must
 * only be asked when it can change the outcome — which is the [UiTargetKind.SubmitSearch] branch alone.
 * Defaults to the node's own flag (the offline resolver has no ancestor to walk).
 * @param fallbackPenalty penalty for OCR/vision-grounded evidence; 0 for real accessibility nodes.
 */
internal fun scoreTargetCandidate(
    node: ScreenNode,
    label: String,
    kind: UiTargetKind?,
    normalizedTarget: String,
    profile: AppInteractionProfile?,
    metrics: SnapshotBoundsMetrics,
    effectivelyClickable: () -> Boolean = { node.clickable },
    fallbackPenalty: Int = 0,
): UiTargetScoringOutcome? {
    if (kind == UiTargetKind.SubmitSearch && node.editable) return null
    val normalizedLabel = label.normalizedLookupKey()
    if (kind == UiTargetKind.SubmitSearch && looksNonTextSearchControl(normalizedLabel)) return null
    val profileHint = kind?.let { resolvedKind ->
        profileHints(resolvedKind, profile)
            .mapNotNull { hint ->
                val score = phraseScore(normalizedLabel, hint.normalizedLookupKey()) ?: 0
                if (score > 0) ProfileHintScore(hint = hint, score = score) else null
            }
            .maxByOrNull { score -> score.score }
    }
    val hintScore = profileHint?.score ?: 0
    val targetScore = if (kind == null) 0 else phraseScore(normalizedLabel, normalizedTarget) ?: 0
    val semanticScore = when (kind) {
        UiTargetKind.SearchEntry -> searchEntryScore(node, normalizedLabel)
        UiTargetKind.EditableField -> if (node.editable) 650 else 0
        UiTargetKind.SubmitSearch ->
            submitSearchScore(node, normalizedLabel, effectivelyClickable, hintScore > 0)

        UiTargetKind.FilterEntry ->
            phraseScore(normalizedLabel, "筛选") ?: phraseScore(normalizedLabel, "filter") ?: 0

        UiTargetKind.ResultItem -> targetScore + hintScore
        UiTargetKind.ScrollContainer -> if (node.scrollable) 700 else 0
        // Free-text mode: no semantic evidence exists, so the direct text/description table below is
        // the whole story. (Every other kind can also land there, when its own terms all score 0.)
        null -> 0
    }
    // The semantic CORE is what decides which evidence table applies: the kind-specific score plus the
    // app-profile hint. Deliberately excludes [targetScore] — a bare phrase overlap with the target text
    // is not semantic evidence that this node IS a search box / submit button / editable field.
    val semanticCore = semanticScore + hintScore
    val usesDirectTextEvidence = semanticCore <= 0
    val evidenceScore = if (usesDirectTextEvidence) {
        // Semantic scoring found nothing (or there is no semantic kind at all) — fall back to the direct
        // text/description table. This fallback is NOT conditional on `kind == null`: upstream reached it
        // whenever `semanticTargetMatchScore` returned null, for every kind. Gating it on a null kind
        // instead cost the common case `ui_tap(target="输入")` on a comment bar or an address bar — kind
        // resolves to EditableField/SearchEntry, every semantic term scores 0 because the node is not
        // `editable`, and the node became unreachable even though its own text literally contains the
        // target.
        //
        // It deliberately scores `text` and `contentDescription` as separate fields rather than through
        // the joined label, because that is the only thing that separates "this node IS the thing you
        // named" from "this node mentions it somewhere in a long row of text" when there is no semantic
        // evidence to lean on.
        directTextTargetScore(node, normalizedLabel, normalizedTarget) ?: return null
    } else {
        (semanticCore + targetScore).takeIf { it > 0 } ?: return null
    }
    val actionability = node.actionabilityScore()
    val position = node.positionScore(kind, metrics)
    // Two independent subtractions, mirroring upstream. The camera/voice/scan demotion must NOT live
    // inside [targetRiskPenalty]: that function returns early for `editable` nodes, and SearchEntry /
    // EditableField — the only two kinds this penalty applies to — are precisely the kinds whose real
    // candidates are usually editable, so folding it in there silenced it almost everywhere.
    val riskPenalty = node.targetRiskPenalty(kind, normalizedLabel, profile, metrics) +
        negativeSemanticPenalty(kind, normalizedLabel)
    val noisePenalty = labelNoisePenalty(kind, normalizedLabel, usesDirectTextEvidence)
    val score = evidenceScore + actionability + position - (riskPenalty + noisePenalty + fallbackPenalty)
    if (score <= 0) return null
    return UiTargetScoringOutcome(
        score = UiTargetScoreComponents(
            semanticScore = semanticScore,
            profileHintScore = hintScore,
            targetTextScore = targetScore,
            actionabilityScore = actionability,
            positionScore = position,
            riskPenalty = riskPenalty,
            noisePenalty = noisePenalty,
            fallbackPenalty = fallbackPenalty,
            finalScore = score,
        ),
        matchedProfileHint = profileHint?.hint,
    )
}

/**
 * Score used by the on-device click path (`SolinAccessibilityService`), or null when the node is not a
 * usable target for [target].
 *
 * Wraps [scoreTargetCandidate] with the two things only the runtime knows: the [UiTargetKind] derived
 * from a free-text target, and the fact that geometry is expressed relative to the active window root
 * rather than to the union of a collected snapshot's node bounds.
 */
internal fun runtimeTargetMatchScore(
    node: ScreenNode,
    label: String,
    target: String,
    profile: AppInteractionProfile?,
    rootBounds: ScreenBounds?,
    effectivelyClickable: () -> Boolean = { node.clickable },
): Int? = runtimeTargetMatchScore(
    node = node,
    label = label,
    query = RuntimeTargetQuery(target, rootBounds),
    profile = profile,
    effectivelyClickable = effectivelyClickable,
)

/**
 * The per-target inputs of [runtimeTargetMatchScore], derived once per tree walk instead of once per
 * scored node: they depend only on the target text and the window root, never on the node.
 */
internal class RuntimeTargetQuery(val target: String, rootBounds: ScreenBounds?) {
    val normalizedTarget: String = target.normalizedLookupKey()
    val kind: UiTargetKind? = UiTargetResolver.kindForTarget(target)
    val metrics: SnapshotBoundsMetrics = SnapshotBoundsMetrics.fromRootBounds(rootBounds)
}

internal fun runtimeTargetMatchScore(
    node: ScreenNode,
    label: String,
    query: RuntimeTargetQuery,
    profile: AppInteractionProfile?,
    effectivelyClickable: () -> Boolean = { node.clickable },
): Int? {
    if (!node.enabled) return null
    if (query.normalizedTarget.isBlank()) return null
    val kind = query.kind
    val outcome = scoreTargetCandidate(
        node = node,
        label = label,
        kind = kind,
        normalizedTarget = query.normalizedTarget,
        profile = profile,
        metrics = query.metrics,
        effectivelyClickable = effectivelyClickable,
    ) ?: return null
    return outcome.score.finalScore.takeIf { it >= kind.minimumTargetScore() }
}

/** Actionability contribution, exposed so the runtime's node-id direct hit can add the same bonus. */
internal fun screenNodeActionabilityScore(node: ScreenNode): Int = node.actionabilityScore()

/**
 * The label the runtime click path scores a node by.
 *
 * Wider than the resolver's text+contentDescription on purpose: `viewIdResourceName` and the class name
 * are frequently the ONLY search evidence an icon-only control carries (`…:id/search_bar`,
 * `android.widget.EditText`). Kept here rather than in the service so a JVM test can build the exact
 * same label from a UIAutomator dump and replay the real click-side ranking.
 */
internal fun runtimeNodeSearchLabel(
    text: String?,
    contentDescription: String?,
    viewIdResourceName: String?,
    className: String?,
): String =
    listOfNotNull(
        text?.takeIf { it.isNotBlank() },
        contentDescription?.takeIf { it.isNotBlank() },
        viewIdResourceName?.takeIf { it.isNotBlank() },
        className?.takeIf { it.isNotBlank() },
    ).joinToString(" ")

private fun profileHints(kind: UiTargetKind, profile: AppInteractionProfile?): Set<String> =
    when (kind) {
        UiTargetKind.SearchEntry -> profile?.searchEntryHints.orEmpty()
        UiTargetKind.SubmitSearch -> profile?.submitHints.orEmpty()
        UiTargetKind.ResultItem -> profile?.resultHints.orEmpty()
        else -> emptySet()
    }

private fun searchEntryScore(node: ScreenNode, normalizedLabel: String): Int {
    var score = 0
    val hasSearchEvidence = looksSearchLike(normalizedLabel)
    val hasInputEvidence = looksInputLike(normalizedLabel)
    if (node.editable && (hasSearchEvidence || hasInputEvidence)) {
        score += 750
    } else if (node.editable) {
        score += 220
    }
    if (hasStrongSearchEntryEvidence(normalizedLabel)) score += 680
    if (hasSearchEvidence) score += if (node.editable) 520 else 300
    if (hasInputEvidence) score += 180
    if (normalizedLabel == "搜索" && !node.editable) score -= 260
    return score
}

private fun submitSearchScore(
    node: ScreenNode,
    normalizedLabel: String,
    effectivelyClickable: () -> Boolean,
    hasProfileSubmitHint: Boolean,
): Int {
    if (node.editable || looksNonTextSearchControl(normalizedLabel)) return 0
    // Label evidence is checked BEFORE the clickable question so the (potentially expensive) ancestor
    // walk is skipped for the overwhelming majority of nodes, which are not submit-like at all.
    val labelScore = when {
        looksSearchSubmitLike(normalizedLabel) -> 700
        hasProfileSubmitHint -> 260
        else -> return 0
    }
    return if (effectivelyClickable()) labelScore else 0
}

/**
 * Direct text/description evidence table, preserved from the runtime click path. Reached whenever
 * semantic scoring produced nothing, for ANY [UiTargetKind] as well as for a free-text target with no
 * kind at all. Exact field matches must dominate incidental mentions inside a long joined label, so the
 * gap between 900 (this node's own text IS the target) and 300 (the target appears somewhere in the
 * label, possibly via the class name) is load-bearing, not decorative.
 *
 * Returns null for a blank target. Without that guard `"".contains(x)` and `description == ""` make every
 * text-less node an exact 900 match, which would hand a target-less `resolve(kind)` call a
 * semantics-free winner and break the fail-closed contract (`resolve` with no target must find nothing
 * unless real semantic evidence exists).
 */
private fun directTextTargetScore(
    node: ScreenNode,
    normalizedLabel: String,
    normalizedTarget: String,
): Int? {
    if (normalizedTarget.isBlank()) return null
    val text = node.text.normalizedLookupKey()
    val description = node.contentDescription.normalizedLookupKey()
    return when {
        text == normalizedTarget || description == normalizedTarget -> 900
        normalizedLabel == normalizedTarget -> 850
        text.contains(normalizedTarget) || description.contains(normalizedTarget) -> 650
        normalizedLabel.contains(normalizedTarget) -> 300
        else -> null
    }
}

fun UiTargetResolutionEvidence.explanationContract(): UiTargetExplanationContract {
    val selectedCandidate = rankedCandidates.firstOrNull { candidate -> candidate.nodeId == selectedNodeId }
    return kind.explanationContract(
        selected = selectedCandidate != null,
        source = selectedCandidate?.source ?: UiTargetEvidenceSource.Accessibility,
        fallbackType = selectedCandidate?.fallbackType ?: UiTargetFallbackType.None,
        reason = selectedCandidate?.reason
            ?: failureKind?.let { failure -> "failed:${failure.schemaValue}" }
            ?: "no_accessibility_candidate",
    )
}

private fun UiTargetKind.explanationContract(
    selected: Boolean,
    source: UiTargetEvidenceSource,
    fallbackType: UiTargetFallbackType,
    reason: String,
): UiTargetExplanationContract {
    return UiTargetExplanationContract(
        source = source,
        fallbackType = fallbackType,
        expectedVerificationSignal = if (selected) expectedVerificationSignal() else UiTargetVerificationSignal.None,
        requiresAdditionalEvidence = fallbackType.requiresEvidence,
        reason = reason,
    )
}

private fun UiTargetKind.expectedVerificationSignal(): UiTargetVerificationSignal =
    when (this) {
        UiTargetKind.SearchEntry,
        UiTargetKind.EditableField -> UiTargetVerificationSignal.EditableFocusedOrTextAccepted
        UiTargetKind.SubmitSearch -> UiTargetVerificationSignal.SearchResultEvidence
        UiTargetKind.FilterEntry,
        UiTargetKind.ResultItem,
        UiTargetKind.ScrollContainer -> UiTargetVerificationSignal.UiMutationOrActionAccepted
    }

private fun ScreenNode.actionabilityScore(): Int {
    if (!enabled) return 0
    var score = 0
    if (clickable) score += 120
    if (editable) score += 180
    if (scrollable) score += 80
    return score
}

private fun ScreenNode.positionScore(kind: UiTargetKind?, metrics: SnapshotBoundsMetrics): Int {
    val boundsValue = bounds ?: return 0
    val topRatio = metrics.topRatio(boundsValue) ?: return 0
    val widthRatio = metrics.widthRatio(boundsValue) ?: return 0
    val heightRatio = metrics.heightRatio(boundsValue)
    return when (kind) {
        UiTargetKind.SearchEntry,
        UiTargetKind.EditableField -> {
            var score = 0
            if (topRatio <= 0.25f) score += 140
            if (widthRatio >= 0.35f && heightRatio >= 0.02f && heightRatio <= 0.14f) score += 140
            if (topRatio >= 0.65f) score -= 180
            score
        }

        UiTargetKind.SubmitSearch -> if (topRatio <= 0.30f) 80 else 0
        else -> 0
    }
}

private fun ScreenNode.targetRiskPenalty(
    kind: UiTargetKind?,
    normalizedLabel: String,
    profile: AppInteractionProfile?,
    metrics: SnapshotBoundsMetrics,
): Int {
    if (kind == UiTargetKind.ResultItem) return 0
    var penalty = 0
    if (!enabled && kind?.requiresPreciseTarget() == true) penalty += 520
    if (editable) return penalty
    if (kind?.requiresPreciseTarget() == true) {
        if (kind == UiTargetKind.SearchEntry && isBrowserResultSearchBarLabel(normalizedLabel)) return penalty
        val areaRatio = metrics.areaRatio(bounds)
        val heightRatio = metrics.heightRatio(bounds)
        val widthRatio = metrics.widthRatio(bounds) ?: 0f
        penalty += when {
            areaRatio >= 0.35f || heightRatio >= 0.55f -> 820
            areaRatio >= 0.20f || heightRatio >= 0.38f -> 460
            areaRatio >= 0.12f -> 180
            else -> 0
        }
        if (
            kind == UiTargetKind.SearchEntry &&
            !clickable &&
            !editable &&
            widthRatio >= 0.90f &&
            (normalizedLabel == "搜索栏" || normalizedLabel.startsWith("搜索栏"))
        ) {
            penalty += 820
        }
        if (scrollable) penalty += 380
        if (looksResultOrCommerceContainer(normalizedLabel, profile)) penalty += 360
    }
    return penalty
}

/**
 * Demotion for a node whose label advertises a camera / voice / scan affordance, or generic feed bait,
 * when we are looking for a text-input target.
 *
 * A separate subtraction rather than a branch inside [targetRiskPenalty], and the separation is the whole
 * point: [targetRiskPenalty] returns early for `editable` nodes (its remaining work is oversized-container
 * geometry, which is meaningless for a real input field). But SearchEntry / EditableField are exactly the
 * kinds whose genuine candidates ARE editable, so a penalty folded in behind that early return would be
 * skipped for almost every node it exists to punish — e.g. two editable fields labelled "搜索宝贝 拍照" and
 * "搜索宝贝" would tie, and a document-order tie-break could send `type_text` into the camera instead of
 * the keyboard.
 *
 * Applies only to the two text-input kinds: on other kinds these words are legitimate content (a
 * ResultItem may well be a 图片 card, and "拍照" is a perfectly good SubmitSearch-adjacent label).
 */
private fun negativeSemanticPenalty(kind: UiTargetKind?, normalizedLabel: String): Int {
    if (kind != UiTargetKind.SearchEntry && kind != UiTargetKind.EditableField) return 0
    var penalty = 0
    if (
        normalizedLabel.contains("拍照") ||
        normalizedLabel.contains("拍立淘") ||
        normalizedLabel.contains("拍照搜") ||
        normalizedLabel.contains("相机") ||
        normalizedLabel.contains("扫一扫") ||
        normalizedLabel.contains("语音") ||
        normalizedLabel.contains("图片") ||
        normalizedLabel.contains("找同款")
    ) {
        penalty += 520
    }
    if (
        normalizedLabel.contains("商品图片") ||
        normalizedLabel.contains("推荐") ||
        normalizedLabel.contains("猜你喜欢")
    ) {
        penalty += 260
    }
    return penalty
}

/**
 * Penalty for a long, noisy label — a target that "matched" only because it is a whole row of text.
 *
 * Which curve applies follows the evidence table that scored the node, not the kind. The direct
 * text/description table (see [directTextTargetScore]) is paired with the runtime's original continuous
 * curve, because that is what it was tuned against upstream; the semantic path uses the stepped curve the
 * offline replay corpus pins. The two are close in shape, but a node admitted by the direct table would be
 * over-penalized by the stepped one (a 62-char address-bar label costs 1 point on the continuous curve
 * versus 150 on the stepped one) — enough to push a legitimately matched control back under its floor.
 */
private fun labelNoisePenalty(
    kind: UiTargetKind?,
    normalizedLabel: String,
    usesDirectTextEvidence: Boolean,
): Int {
    if (kind == null || usesDirectTextEvidence) return (normalizedLabel.length / 32).coerceAtMost(50)
    if (!kind.requiresPreciseTarget()) return 0
    return when {
        normalizedLabel.length >= 96 -> 260
        normalizedLabel.length >= 56 -> 150
        normalizedLabel.length >= 32 -> 70
        else -> 0
    }
}

/**
 * The single minimum-score table. Was duplicated verbatim as `UiTargetKind.minimumConfidence` here and
 * `minimumRuntimeScore` in `SolinAccessibilityService`; a null [UiTargetKind] is the runtime's
 * free-text mode, which has no precision floor beyond "scored at all".
 */
internal fun UiTargetKind?.minimumTargetScore(): Int =
    when (this) {
        UiTargetKind.SearchEntry -> 560
        UiTargetKind.EditableField -> 600
        UiTargetKind.SubmitSearch -> 650
        UiTargetKind.FilterEntry -> 430
        UiTargetKind.ScrollContainer -> 650
        UiTargetKind.ResultItem,
        null -> 1
    }

internal fun UiTargetKind.requiresPreciseTarget(): Boolean =
    this == UiTargetKind.SearchEntry ||
        this == UiTargetKind.EditableField ||
        this == UiTargetKind.SubmitSearch ||
        this == UiTargetKind.FilterEntry

private fun UiTargetEvidenceCandidate.isSelectable(kind: UiTargetKind): Boolean =
    enabled && score.finalScore >= kind.minimumTargetScore()

private fun UiTargetKind.missingResolutionFailureKind(): UiActionFailureKind =
    when (this) {
        UiTargetKind.SearchEntry -> UiActionFailureKind.SearchEntryNotFound
        UiTargetKind.EditableField -> UiActionFailureKind.EditableNotFound
        UiTargetKind.SubmitSearch -> UiActionFailureKind.SubmitNotFound
        else -> UiActionFailureKind.NodeNotFound
    }

private data class GroundingNode(
    val node: ScreenNode,
    val source: UiTargetEvidenceSource = UiTargetEvidenceSource.Accessibility,
    val fallbackType: UiTargetFallbackType = UiTargetFallbackType.None,
    val labelOverride: String? = null,
) {
    fun fallbackPenalty(): Int =
        when (fallbackType) {
            UiTargetFallbackType.None -> 0
            UiTargetFallbackType.OcrGrounding ->
                if (node.clickable || node.editable || node.scrollable) 80 else 240
            else -> 320
        }

    fun reasonFor(label: String): String =
        if (fallbackType == UiTargetFallbackType.OcrGrounding) {
            "matched OCR-grounded $label"
        } else {
            "matched $label"
        }
}

private fun ScreenObservation.toGroundingNodes(): List<GroundingNode> {
    val ocrElements = elements
        .filter { element ->
            element.source == UiTargetEvidenceSource.Ocr.schemaValue &&
                element.text.isNotBlank() &&
                element.bounds != null
        }
    val accessibilityNodes = elements
        .filter { element -> element.source == UiTargetEvidenceSource.Accessibility.schemaValue }
        .map { element ->
            val ocrLabel = if (element.text.isBlank()) {
                element.overlappingOcrLabel(ocrElements)
            } else {
                null
            }
            GroundingNode(
                node = element.toScreenNode(textOverride = ocrLabel),
                source = if (ocrLabel != null) UiTargetEvidenceSource.Ocr else UiTargetEvidenceSource.Accessibility,
                fallbackType = if (ocrLabel != null) UiTargetFallbackType.OcrGrounding else UiTargetFallbackType.None,
                labelOverride = ocrLabel,
            )
        }
    val ocrNodes = ocrElements.map { element ->
        GroundingNode(
            node = element.toScreenNode(textOverride = null),
            source = UiTargetEvidenceSource.Ocr,
            fallbackType = UiTargetFallbackType.OcrGrounding,
        )
    }
    val trailingAffordanceNodes = accessibilityNodes.mapNotNull { grounding ->
        grounding.node.trailingAffordanceGroundingNode()
    }
    return accessibilityNodes + ocrNodes + trailingAffordanceNodes
}

/**
 * If a text row ends with a trailing affordance token (展开/更多/查看全部/…), synthesize a distinct
 * actionable grounding node covering the right ~30% of the row so that affordance can be targeted
 * independently of the row's informational text. Carries the OCR-grounding fallback penalty so it
 * never outranks a real labeled control. Returns null when there is no such token or no bounds.
 */
private fun ScreenNode.trailingAffordanceGroundingNode(): GroundingNode? {
    if (!clickable) return null
    val boundsValue = bounds ?: return null
    val trimmed = text.trim()
    val marker = TRAILING_AFFORDANCE_MARKERS.firstOrNull { token ->
        trimmed.endsWith(token, ignoreCase = true) && !trimmed.equals(token, ignoreCase = true)
    } ?: return null
    val width = boundsValue.right - boundsValue.left
    if (width <= 0) return null
    val affordanceLeft = boundsValue.left + (width * 7) / 10
    val affordanceBounds = ScreenBounds(
        left = affordanceLeft,
        top = boundsValue.top,
        right = boundsValue.right,
        bottom = boundsValue.bottom,
    )
    return GroundingNode(
        node = ScreenNode(
            id = "$id$AFFORDANCE_NODE_ID_SUFFIX",
            text = marker,
            contentDescription = "",
            className = "observation.affordance",
            bounds = affordanceBounds,
            clickable = true,
            editable = false,
            scrollable = false,
            enabled = enabled,
        ),
        source = UiTargetEvidenceSource.Ocr,
        fallbackType = UiTargetFallbackType.OcrGrounding,
        labelOverride = marker,
    )
}

private fun ObservationElement.overlappingOcrLabel(ocrElements: List<ObservationElement>): String? {
    val boundsValue = bounds ?: return null
    return ocrElements
        .asSequence()
        .filter { element -> element.bounds?.let(boundsValue::containsOcrGroundingBounds) == true }
        .sortedBy { element -> element.bounds?.top ?: Int.MAX_VALUE }
        .map { element -> element.text.trim() }
        .filter { text -> text.isNotBlank() }
        .distinct()
        .take(3)
        .joinToString(" ")
        .takeIf { text -> text.isNotBlank() }
}

private fun ObservationElement.toScreenNode(textOverride: String?): ScreenNode =
    ScreenNode(
        id = id,
        text = textOverride ?: text,
        contentDescription = "",
        className = if (source == UiTargetEvidenceSource.Ocr.schemaValue) {
            "ocr.$role"
        } else {
            "observation.$role"
        },
        bounds = bounds,
        clickable = clickability.clickable,
        editable = clickability.editable,
        scrollable = clickability.scrollable,
        enabled = clickability.enabled,
    )

private data class ProfileHintScore(
    val hint: String,
    val score: Int,
)

/**
 * Viewport geometry a candidate's bounds are judged against.
 *
 * Two ways in, because the two callers have different notions of "the screen". The offline resolver only
 * sees a collected snapshot and *infers* the viewport from the union of its node bounds — which is why
 * it needs [viewportTrusted]`=false` and the [isViewportLike] sanity check: a snapshot whose widest node
 * is a horizontal strip is not a screen, and ratios derived from it would be nonsense. The runtime click
 * path instead holds the active window root, whose bounds ARE the viewport by definition, so it trusts
 * them — including in landscape, where `height >= width / 2` is false and the heuristic would otherwise
 * zero out both the position bonus AND the oversized-container risk penalty (dropping a penalty is the
 * wrong direction, so the runtime must not inherit that guard).
 *
 * [origin] keeps [topRatio] honest for the runtime: a window that does not start at y=0 (split screen, a
 * dialog) would otherwise report every one of its own children as "far down the screen" and lose the
 * top-of-window search-entry bonus.
 */
internal data class SnapshotBoundsMetrics(
    val width: Int,
    val height: Int,
    val origin: ScreenBounds? = null,
    val viewportTrusted: Boolean = false,
) {
    fun areaRatio(bounds: ScreenBounds?): Float {
        if (!isViewportLike()) return 0f
        val safeBounds = bounds ?: return 0f
        val screenArea = width.toLong() * height.toLong()
        if (screenArea <= 0L) return 0f
        val nodeArea = safeBounds.width().toLong() * safeBounds.height().toLong()
        return nodeArea.toFloat() / screenArea.toFloat()
    }

    fun heightRatio(bounds: ScreenBounds?): Float {
        if (!isViewportLike()) return 0f
        val safeBounds = bounds ?: return 0f
        if (height <= 0) return 0f
        return safeBounds.height().toFloat() / height.toFloat()
    }

    fun widthRatio(bounds: ScreenBounds?): Float? {
        if (!isViewportLike()) return null
        val safeBounds = bounds ?: return null
        if (width <= 0) return null
        return safeBounds.width().toFloat() / width.toFloat()
    }

    fun topRatio(bounds: ScreenBounds?): Float? {
        if (!isViewportLike()) return null
        val safeBounds = bounds ?: return null
        if (height <= 0) return null
        return (safeBounds.top - (origin?.top ?: 0)).toFloat() / height.toFloat()
    }

    private fun isViewportLike(): Boolean =
        width > 0 && height > 0 && (viewportTrusted || height >= width / 2)

    companion object {
        fun from(nodes: List<ScreenNode>): SnapshotBoundsMetrics {
            val bounded = nodes.mapNotNull { node -> node.bounds }
            val width = bounded.maxOfOrNull { bounds -> bounds.right } ?: 0
            val height = bounded.maxOfOrNull { bounds -> bounds.bottom } ?: 0
            return SnapshotBoundsMetrics(width = width, height = height)
        }

        /** Metrics for the runtime click path, derived from the active window root's own bounds. */
        fun fromRootBounds(rootBounds: ScreenBounds?): SnapshotBoundsMetrics {
            val safeBounds = rootBounds ?: return SnapshotBoundsMetrics(width = 0, height = 0)
            return SnapshotBoundsMetrics(
                width = safeBounds.width(),
                height = safeBounds.height(),
                origin = safeBounds,
                viewportTrusted = true,
            )
        }
    }
}

internal fun ScreenBounds.width(): Int =
    (right - left).coerceAtLeast(0)

internal fun ScreenBounds.height(): Int =
    (bottom - top).coerceAtLeast(0)

/**
 * Separator characters stripped by [normalizedLookupKey].
 *
 * Hoisted to a file-level val because [normalizedLookupKey] is called on the per-node, per-candidate
 * scoring path (100+ call sites across the repository); compiling this pattern on every call was
 * pure waste. The pattern itself is unchanged.
 */
private val NORMALIZED_LOOKUP_SEPARATOR_REGEX =
    Regex("""[\s\p{Punct}，。！？、：；（）【】《》“”‘’·]+""")

internal fun String?.normalizedLookupKey(): String =
    orEmpty()
        .lowercase(Locale.ROOT)
        .replace(NORMALIZED_LOOKUP_SEPARATOR_REGEX, "")
