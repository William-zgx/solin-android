package com.bytedance.zgx.solin.device

/*
 * Verifies that an in-app search actually produced results for the query, plus the snapshot
 * helpers it relies on. Split out of UiTargetResolver.kt; behaviour unchanged.
 */

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
        val hasEditableQueryEvidence = snapshot.editableVisibleLabelsContaining(normalizedQuery).isNotEmpty()
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
        if (hasEditableQueryEvidence && resultHintCount >= 2) {
            return SearchResultVerification(
                verified = true,
                summary = "搜索结果验证通过：当前搜索框保留关键词且页面包含多个结果页特征。",
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

internal fun ScreenNode.visibleLabel(): String =
    listOf(text, contentDescription)
        .filter { it.isNotBlank() }
        .joinToString(" ")

private fun ScreenStateSnapshot.containsVisibleTextNormalized(needle: String): Boolean {
    if (needle.isBlank()) return false
    return textSummary.normalizedLookupKey().contains(needle) ||
        nodes.any { node -> node.visibleLabel().normalizedLookupKey().contains(needle) }
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

private fun ScreenStateSnapshot.editableVisibleLabelsContaining(needle: String): Set<String> {
    if (needle.isBlank()) return emptySet()
    return nodes
        .asSequence()
        .filter { node -> node.editable }
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
