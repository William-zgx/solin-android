package com.bytedance.zgx.pocketmind.device

import java.util.Locale

object AppInteractionProfiles {
    val profiles: List<AppInteractionProfile> = listOf(
        AppInteractionProfile(
            appNameAliases = setOf("淘宝", "taobao", "tb"),
            packageNames = setOf("com.taobao.taobao"),
            searchEntryHints = setOf("搜索", "搜一搜", "搜索商品", "搜索发现", "搜索宝贝", "搜索宝贝和店铺", "淘宝搜索"),
            submitHints = setOf("搜索", "搜一下"),
            resultHints = setOf("综合", "销量", "筛选"),
        ),
        AppInteractionProfile(
            appNameAliases = setOf("拼多多", "pinduoduo", "pdd"),
            packageNames = setOf("com.xunmeng.pinduoduo"),
            searchEntryHints = setOf("搜索", "搜索商品", "多多搜索", "搜"),
            submitHints = setOf("搜索", "搜一下"),
            resultHints = setOf("综合", "销量", "筛选", "百亿补贴"),
        ),
        AppInteractionProfile(
            appNameAliases = setOf("高德", "高德地图", "amap", "gaode", "autonavi"),
            packageNames = setOf("com.autonavi.minimap"),
            searchEntryHints = setOf("搜索", "搜地点", "目的地", "去哪儿", "你要去哪儿", "查找地点", "公交地铁"),
            submitHints = setOf("搜索", "确定", "去这里"),
            resultHints = setOf("路线", "导航", "到这去", "查看地图", "展开列表"),
        ),
        AppInteractionProfile(
            appNameAliases = setOf("地图", "google maps", "maps"),
            packageNames = setOf("com.google.android.apps.maps"),
            searchEntryHints = setOf("搜索", "search", "搜索地点", "search here", "where to", "目的地"),
            submitHints = setOf("搜索", "search", "directions"),
            resultHints = setOf("路线", "directions", "start", "reviews", "photos"),
        ),
        AppInteractionProfile(
            appNameAliases = setOf("京东", "jd", "jingdong"),
            packageNames = setOf("com.jingdong.app.mall"),
            searchEntryHints = setOf("搜索", "搜索商品", "搜一搜", "搜索京东", "搜索京东商品", "搜索京东商品店铺", "搜索好物"),
            submitHints = setOf("搜索"),
            resultHints = setOf("综合", "销量", "筛选", "京东物流"),
        ),
        AppInteractionProfile(
            appNameAliases = setOf("浏览器", "browser", "网页", "web", "chrome", "谷歌浏览器", "google", "谷歌"),
            packageNames = setOf(
                "com.android.chrome",
                "com.android.browser",
                "com.quark.browser",
                "com.UCMobile",
                "com.google.android.googlequicksearchbox",
            ),
            searchEntryHints = setOf(
                "搜索",
                "搜",
                "검색",
                "地址",
                "地址栏",
                "网址",
                "url",
                "omnibox",
                "输入网址",
                "搜索或输入网址",
                "请输入搜索词或网址",
                "搜索词或网址",
            ),
            submitHints = setOf("搜索", "검색", "前往", "转到", "search"),
            resultHints = setOf("搜索结果", "검색결과", "网页", "相关搜索"),
        ),
    )

    fun forPackage(packageName: String?): AppInteractionProfile? =
        packageName?.takeIf { it.isNotBlank() }?.let { packageValue ->
            profiles.firstOrNull { profile -> packageValue in profile.packageNames }
        }

    fun forAppName(appName: String?): AppInteractionProfile? {
        val normalized = appName.normalizedLookupKey()
        if (normalized.isBlank()) return null
        return profiles.firstOrNull { profile ->
            profile.appNameAliases.any { alias -> alias.normalizedLookupKey() == normalized }
        }
    }
}

// ponytail: OCR/vision are contract placeholders until real grounding is wired.
enum class UiTargetEvidenceSource(val schemaValue: String, val priority: Int) {
    Accessibility("accessibility", 100),
    OcrPlaceholder("ocr_placeholder", 40),
    VisionPlaceholder("vision_placeholder", 40),
}

enum class UiTargetFallbackType(
    val schemaValue: String,
    val priority: Int,
    val requiresEvidence: Boolean,
) {
    None("none", 100, false),
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
    fun resolve(
        snapshot: ScreenStateSnapshot,
        kind: UiTargetKind,
        target: String? = null,
        profile: AppInteractionProfile? = AppInteractionProfiles.forPackage(snapshot.packageName),
    ): UiResolvedTarget? =
        resolveAll(
            snapshot = snapshot,
            kind = kind,
            target = target,
            profile = profile,
        ).firstOrNull()

    fun resolveAll(
        snapshot: ScreenStateSnapshot,
        kind: UiTargetKind,
        target: String? = null,
        profile: AppInteractionProfile? = AppInteractionProfiles.forPackage(snapshot.packageName),
    ): List<UiResolvedTarget> {
        return rankedCandidates(
            snapshot = snapshot,
            kind = kind,
            target = target,
            profile = profile,
            includeDiagnostics = false,
        ).map { candidate ->
            UiResolvedTarget(
                kind = kind,
                nodeId = candidate.nodeId,
                bounds = candidate.bounds,
                confidence = candidate.score.finalScore,
                reason = candidate.reason,
            )
        }
    }

    fun explain(
        snapshot: ScreenStateSnapshot,
        kind: UiTargetKind,
        target: String? = null,
        profile: AppInteractionProfile? = AppInteractionProfiles.forPackage(snapshot.packageName),
    ): UiTargetResolutionEvidence {
        val candidates = rankedCandidates(
            snapshot = snapshot,
            kind = kind,
            target = target,
            profile = profile,
            includeDiagnostics = true,
        )
        val selectableCandidates = candidates.filter { candidate -> candidate.isSelectable(kind) }
        val evidenceCandidates = selectableCandidates.takeIf { it.isNotEmpty() } ?: candidates
        return UiTargetResolutionEvidence(
            kind = kind,
            target = target,
            packageName = snapshot.packageName,
            selectedNodeId = selectableCandidates.firstOrNull()?.nodeId,
            rankedCandidates = evidenceCandidates,
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
        snapshot: ScreenStateSnapshot,
        kind: UiTargetKind,
        target: String?,
        profile: AppInteractionProfile?,
        includeDiagnostics: Boolean,
    ): List<UiTargetEvidenceCandidate> {
        val metrics = SnapshotBoundsMetrics.from(snapshot.nodes)
        return snapshot.nodes
            .mapNotNull { node -> scoreNode(node, kind, target, profile, metrics, includeDiagnostics) }
            .sortedByDescending { candidate -> candidate.score.finalScore }
    }

    private fun scoreNode(
        node: ScreenNode,
        kind: UiTargetKind,
        target: String?,
        profile: AppInteractionProfile?,
        metrics: SnapshotBoundsMetrics,
        includeDiagnostics: Boolean,
    ): UiTargetEvidenceCandidate? {
        if (kind == UiTargetKind.SubmitSearch && node.editable) return null
        val label = node.visibleLabel()
        val normalizedLabel = label.normalizedLookupKey()
        if (kind == UiTargetKind.SubmitSearch && looksNonTextSearchControl(normalizedLabel)) return null
        val normalizedTarget = target.normalizedLookupKey()
        val profileHints = when (kind) {
            UiTargetKind.SearchEntry -> profile?.searchEntryHints.orEmpty()
            UiTargetKind.SubmitSearch -> profile?.submitHints.orEmpty()
            UiTargetKind.ResultItem -> profile?.resultHints.orEmpty()
            else -> emptySet()
        }
        val profileHint = profileHints
            .mapNotNull { hint ->
                val score = phraseScore(normalizedLabel, hint.normalizedLookupKey()) ?: 0
                if (score > 0) ProfileHintScore(hint = hint, score = score) else null
            }
            .maxByOrNull { score -> score.score }
        val hintScore = profileHint?.score ?: 0
        val targetScore = phraseScore(normalizedLabel, normalizedTarget) ?: 0
        val semanticScore = when (kind) {
            UiTargetKind.SearchEntry -> searchEntryScore(node, normalizedLabel)
            UiTargetKind.EditableField -> if (node.editable) 650 else 0
            UiTargetKind.SubmitSearch -> submitSearchScore(node, normalizedLabel, hintScore > 0)
            UiTargetKind.FilterEntry -> phraseScore(normalizedLabel, "筛选") ?: phraseScore(normalizedLabel, "filter") ?: 0
            UiTargetKind.ResultItem -> targetScore + hintScore
            UiTargetKind.ScrollContainer -> if (node.scrollable) 700 else 0
        }
        val evidenceScore = semanticScore + hintScore + targetScore
        if (evidenceScore <= 0) return null
        val actionability = node.actionabilityScore()
        val position = node.positionScore(kind, metrics)
        val riskPenalty = node.targetRiskPenalty(kind, normalizedLabel, profile, metrics)
        val noisePenalty = labelNoisePenalty(kind, normalizedLabel)
        val penalty = riskPenalty + noisePenalty
        val score = evidenceScore + actionability + position - penalty
        if (score <= 0) return null
        if (!includeDiagnostics && !node.isSelectable(kind, score)) return null
        val resolvedLabel = label.ifBlank { node.className }
        return UiTargetEvidenceCandidate(
            nodeId = node.id,
            label = resolvedLabel,
            bounds = node.bounds,
            clickable = node.clickable,
            editable = node.editable,
            scrollable = node.scrollable,
            enabled = node.enabled,
            matchedProfileHint = profileHint?.hint,
            score = UiTargetScoreComponents(
                semanticScore = semanticScore,
                profileHintScore = hintScore,
                targetTextScore = targetScore,
                actionabilityScore = actionability,
                positionScore = position,
                riskPenalty = riskPenalty,
                noisePenalty = noisePenalty,
                finalScore = score,
            ),
            reason = "matched $resolvedLabel",
        )
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

    private fun submitSearchScore(node: ScreenNode, normalizedLabel: String, hasProfileSubmitHint: Boolean): Int {
        var score = 0
        if (!node.editable && node.clickable && !looksNonTextSearchControl(normalizedLabel) && looksSearchSubmitLike(normalizedLabel)) {
            score += 700
        } else if (!node.editable && node.clickable && !looksNonTextSearchControl(normalizedLabel) && hasProfileSubmitHint) {
            score += 260
        }
        return score
    }
}

fun UiTargetResolutionEvidence.explanationContract(): UiTargetExplanationContract {
    val selectedCandidate = rankedCandidates.firstOrNull { candidate -> candidate.nodeId == selectedNodeId }
    return kind.explanationContract(
        selected = selectedCandidate != null,
        reason = selectedCandidate?.reason
            ?: failureKind?.let { failure -> "failed:${failure.schemaValue}" }
            ?: "no_accessibility_candidate",
    )
}

fun UiResolvedTarget.explanationContract(): UiTargetExplanationContract =
    kind.explanationContract(selected = true, reason = reason)

private fun UiTargetKind.explanationContract(
    selected: Boolean,
    reason: String,
): UiTargetExplanationContract {
    val fallbackType = UiTargetFallbackType.None
    return UiTargetExplanationContract(
        source = UiTargetEvidenceSource.Accessibility,
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

object AppSearchResultVerifier {
    fun verify(
        before: ScreenStateSnapshot?,
        after: ScreenStateSnapshot?,
        query: String,
        expectedPackageName: String? = null,
        expectedAppName: String? = null,
    ): SearchResultVerification {
        val snapshot = after ?: return SearchResultVerification(
            verified = false,
            summary = "无法验证搜索结果：动作后没有可访问屏幕状态。",
            failureKind = UiActionFailureKind.PageChanged,
            evidence = "missing_after_snapshot",
        )
        val expectedPackage = expectedPackageName?.trim()?.takeIf { it.isNotBlank() }
        if (expectedPackage != null && snapshot.packageName != expectedPackage) {
            return SearchResultVerification(
                verified = false,
                summary = "无法验证搜索结果：目标应用未保持在前台。",
                failureKind = UiActionFailureKind.AppNotForeground,
                evidence = "expected_package_mismatch",
            )
        }
        val normalizedQuery = query.normalizedLookupKey()
        if (normalizedQuery.isBlank()) {
            return SearchResultVerification(
                verified = false,
                summary = "无法验证搜索结果：搜索关键词为空。",
                failureKind = UiActionFailureKind.ResultNotVerified,
                evidence = "blank_query",
            )
        }
        val profile = AppInteractionProfiles.forPackage(snapshot.packageName)
            ?: AppInteractionProfiles.forAppName(expectedAppName)
        val pageChanged = before == null || before.searchVerificationSignature() != snapshot.searchVerificationSignature()
        val newQueryEvidence = snapshot.newNonEditableQueryEvidenceSince(before, normalizedQuery)
        val hasNonEditableQueryEvidence = snapshot.nonEditableVisibleLabelsContaining(normalizedQuery).isNotEmpty()
        val resultHintCount = profile?.resultHints.orEmpty().count { hint ->
            snapshot.containsVisibleTextNormalized(hint.normalizedLookupKey())
        }
        if (newQueryEvidence) {
            return SearchResultVerification(
                verified = true,
                summary = "搜索结果验证通过：页面出现新的非输入框关键词证据。",
                evidence = if (before == null) "query_visible" else "query_visible_after_change",
            )
        }
        if (hasNonEditableQueryEvidence && resultHintCount > 0) {
            return SearchResultVerification(
                verified = true,
                summary = "搜索结果验证通过：当前页面同时包含关键词和结果页特征。",
                evidence = "query_visible_with_result_hint",
            )
        }
        if (pageChanged && resultHintCount >= 2) {
            return SearchResultVerification(
                verified = true,
                summary = "搜索结果验证通过：页面已变化并出现多个结果页特征。",
                evidence = "result_hints_visible",
            )
        }
        return SearchResultVerification(
            verified = false,
            summary = "未能验证搜索结果：页面没有出现关键词或可识别的结果页特征。",
            failureKind = UiActionFailureKind.ResultNotVerified,
            evidence = if (pageChanged) "page_changed_without_result_evidence" else "page_not_changed",
        )
    }
}

private fun ScreenNode.actionabilityScore(): Int {
    if (!enabled) return 0
    var score = 0
    if (clickable) score += 120
    if (editable) score += 180
    if (scrollable) score += 80
    return score
}

private fun ScreenNode.positionScore(kind: UiTargetKind, metrics: SnapshotBoundsMetrics): Int {
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
    kind: UiTargetKind,
    normalizedLabel: String,
    profile: AppInteractionProfile?,
    metrics: SnapshotBoundsMetrics,
): Int {
    if (kind == UiTargetKind.ResultItem) return 0
    var penalty = 0
    if (!enabled && kind.requiresPreciseTarget()) penalty += 520
    if (editable) return penalty
    if (kind.requiresPreciseTarget()) {
        val areaRatio = metrics.areaRatio(bounds)
        val heightRatio = metrics.heightRatio(bounds)
        penalty += when {
            areaRatio >= 0.35f || heightRatio >= 0.55f -> 820
            areaRatio >= 0.20f || heightRatio >= 0.38f -> 460
            areaRatio >= 0.12f -> 180
            else -> 0
        }
        if (scrollable) penalty += 380
        if (looksResultOrCommerceContainer(normalizedLabel, profile)) penalty += 360
        if (kind == UiTargetKind.SearchEntry || kind == UiTargetKind.EditableField) {
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
        }
    }
    return penalty
}

private fun labelNoisePenalty(kind: UiTargetKind, normalizedLabel: String): Int {
    if (!kind.requiresPreciseTarget()) return 0
    return when {
        normalizedLabel.length >= 96 -> 260
        normalizedLabel.length >= 56 -> 150
        normalizedLabel.length >= 32 -> 70
        else -> 0
    }
}

private fun UiTargetKind.minimumConfidence(): Int =
    when (this) {
        UiTargetKind.SearchEntry -> 560
        UiTargetKind.EditableField -> 600
        UiTargetKind.SubmitSearch -> 650
        UiTargetKind.FilterEntry -> 430
        UiTargetKind.ScrollContainer -> 650
        UiTargetKind.ResultItem -> 1
    }

internal fun UiTargetKind.requiresPreciseTarget(): Boolean =
    this == UiTargetKind.SearchEntry ||
        this == UiTargetKind.EditableField ||
        this == UiTargetKind.SubmitSearch ||
        this == UiTargetKind.FilterEntry

private fun UiTargetEvidenceCandidate.isSelectable(kind: UiTargetKind): Boolean =
    enabled && score.finalScore >= kind.minimumConfidence()

private fun ScreenNode.isSelectable(kind: UiTargetKind, score: Int): Boolean =
    enabled && score >= kind.minimumConfidence()

private fun UiTargetKind.missingResolutionFailureKind(): UiActionFailureKind =
    when (this) {
        UiTargetKind.SearchEntry -> UiActionFailureKind.SearchEntryNotFound
        UiTargetKind.EditableField -> UiActionFailureKind.EditableNotFound
        UiTargetKind.SubmitSearch -> UiActionFailureKind.SubmitNotFound
        else -> UiActionFailureKind.NodeNotFound
    }

private data class ProfileHintScore(
    val hint: String,
    val score: Int,
)

private data class SnapshotBoundsMetrics(
    val width: Int,
    val height: Int,
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
        return safeBounds.top.toFloat() / height.toFloat()
    }

    private fun isViewportLike(): Boolean =
        width > 0 && height > 0 && height >= width / 2

    companion object {
        fun from(nodes: List<ScreenNode>): SnapshotBoundsMetrics {
            val bounded = nodes.mapNotNull { node -> node.bounds }
            val width = bounded.maxOfOrNull { bounds -> bounds.right } ?: 0
            val height = bounded.maxOfOrNull { bounds -> bounds.bottom } ?: 0
            return SnapshotBoundsMetrics(width = width, height = height)
        }
    }
}

internal fun phraseScore(label: String, phrase: String): Int? {
    if (phrase.isBlank()) return null
    return when {
        label == phrase -> 450
        label.startsWith(phrase) -> 360
        label.contains(phrase) -> 260
        phrase.contains(label) && label.length >= 2 -> 160
        else -> null
    }
}

internal fun looksSearchLike(normalized: String): Boolean =
    listOf("搜索", "搜", "검색", "search", "查找", "查询").any { normalized.contains(it.normalizedLookupKey()) }

internal fun hasStrongSearchEntryEvidence(normalized: String): Boolean =
    listOf(
        "搜索栏",
        "搜索框",
        "搜索商品",
        "搜索发现",
        "搜索宝贝",
        "搜索京东",
        "搜索好物",
        "搜索输入",
        "输入文字",
        "输入关键词",
        "请输入搜索词",
        "地址栏",
        "网址",
        "目的地",
        "去哪儿",
        "搜地点",
        "公交地铁",
        "搜索词或网址",
        "searchbox",
        "searchfield",
        "omnibox",
    ).any { normalized.contains(it.normalizedLookupKey()) }

internal fun looksSearchSubmitLike(normalized: String): Boolean =
    looksSearchLike(normalized) ||
        listOf("确定", "完成", "前往", "enter").any { normalized.contains(it.normalizedLookupKey()) } ||
        normalized == "go"

internal fun looksNonTextSearchControl(normalized: String): Boolean =
    listOf("语音", "拍照", "相机", "图片", "扫一扫", "扫码", "voice", "camera", "image")
        .any { normalized.contains(it.normalizedLookupKey()) }

internal fun looksInputLike(normalized: String): Boolean =
    listOf("输入", "地址", "网址", "url", "omnibox", "关键词", "商品", "目的地", "宝贝", "店铺", "input", "edit", "keyword")
        .any { normalized.contains(it.normalizedLookupKey()) }

internal fun looksResultOrCommerceContainer(
    normalizedLabel: String,
    profile: AppInteractionProfile? = null,
): Boolean {
    if (normalizedLabel.isBlank()) return false
    val profileHintMatches = profile?.resultHints.orEmpty().count { hint ->
        normalizedLabel.contains(hint.normalizedLookupKey())
    }
    val genericMarkers = listOf(
        "综合",
        "销量",
        "筛选",
        "商品列表",
        "旗舰店",
        "已售",
        "月销",
        "评价",
        "领券",
        "加购",
        "购买",
        "¥",
        "￥",
    ).count { marker -> normalizedLabel.contains(marker.normalizedLookupKey()) }
    return profileHintMatches >= 2 ||
        genericMarkers >= 3 ||
        (normalizedLabel.contains("综合") && normalizedLabel.contains("销量")) ||
        normalizedLabel.contains("商品列表")
}

internal fun ScreenBounds.width(): Int =
    (right - left).coerceAtLeast(0)

internal fun ScreenBounds.height(): Int =
    (bottom - top).coerceAtLeast(0)

private fun ScreenNode.visibleLabel(): String =
    listOf(text, contentDescription)
        .filter { it.isNotBlank() }
        .joinToString(" ")

private fun ScreenStateSnapshot.containsVisibleTextNormalized(needle: String): Boolean {
    if (needle.isBlank()) return false
    return textSummary.normalizedLookupKey().contains(needle) ||
        nodes.any { node -> node.visibleLabel().normalizedLookupKey().contains(needle) }
}

private fun ScreenStateSnapshot.containsNormalizedOutsideEditable(needle: String): Boolean {
    if (needle.isBlank()) return false
    return nonEditableVisibleLabelsContaining(needle).isNotEmpty()
}

private fun ScreenStateSnapshot.newNonEditableQueryEvidenceSince(
    before: ScreenStateSnapshot?,
    needle: String,
): Boolean {
    if (needle.isBlank()) return false
    val beforeLabels = before?.nonEditableVisibleLabelsContaining(needle).orEmpty()
    return nonEditableVisibleLabelsContaining(needle).any { label -> label !in beforeLabels }
}

private fun ScreenStateSnapshot.nonEditableVisibleLabelsContaining(needle: String): Set<String> {
    if (needle.isBlank()) return emptySet()
    return nodes
        .asSequence()
        .filterNot { node -> node.editable }
        .map { node -> node.visibleLabel().normalizedLookupKey() }
        .filter { label -> label.contains(needle) }
        .toSet()
}

private fun ScreenStateSnapshot.searchVerificationSignature(): String =
    listOf(
        packageName.orEmpty(),
        nodeCount.toString(),
        actionableNodeCount.toString(),
        nodes.take(24).joinToString("|") { node ->
            listOf(
                node.text,
                node.contentDescription,
                node.className,
                node.bounds?.let { bounds ->
                    "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
                }.orEmpty(),
                node.clickable.toString(),
                node.editable.toString(),
                node.scrollable.toString(),
            ).joinToString(":")
        },
    ).joinToString("||")

internal fun String?.normalizedLookupKey(): String =
    orEmpty()
        .lowercase(Locale.ROOT)
        .replace(Regex("""[\s\p{Punct}，。！？、：；（）【】《》“”‘’·]+"""), "")
