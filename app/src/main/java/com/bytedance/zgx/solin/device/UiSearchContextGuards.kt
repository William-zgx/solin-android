package com.bytedance.zgx.solin.device

internal fun ScreenStateSnapshot.hasSearchSubmitContext(): Boolean {
    val profile = AppInteractionProfiles.forPackage(packageName)
    val hasEditable = nodes.any { node -> node.enabled && node.editable }
    val hasSearchEditable = nodes.any { node -> node.isSearchEditable(profile) }
    val hasSearchSubmit = nodes.any { node -> node.isSearchSubmitControl(profile) }
    val hasStrongSearchMarker = nodes.any { node ->
        node.labelForSearchContext().normalizedLookupKey().hasSearchEntryEvidence(profile)
    }
    return hasSearchEditable ||
        (hasEditable && hasSearchSubmit) ||
        (hasStrongSearchMarker && hasSearchSubmit)
}

internal fun ScreenStateSnapshot.hasTargetlessTypingContext(): Boolean =
    nodes.any { node -> node.isSearchEditable(AppInteractionProfiles.forPackage(packageName)) } ||
        hasSearchSubmitContext()

private fun ScreenNode.isSearchEditable(profile: AppInteractionProfile?): Boolean {
    if (!enabled || !editable) return false
    val normalizedLabel = labelForSearchContext().normalizedLookupKey()
    return normalizedLabel.hasSearchEntryEvidence(profile) ||
        looksInputLike(normalizedLabel) && looksSearchLike(normalizedLabel)
}

private fun ScreenNode.isSearchSubmitControl(profile: AppInteractionProfile?): Boolean {
    if (!enabled || editable || !clickable) return false
    val normalizedLabel = labelForSearchContext().normalizedLookupKey()
    if (normalizedLabel.isBlank() || looksNonTextSearchControl(normalizedLabel)) return false
    return looksSearchLike(normalizedLabel) ||
        normalizedLabel == "go" ||
        normalizedLabel == "enter" ||
        normalizedLabel.contains("前往") ||
        profile?.submitHints.orEmpty().any { hint ->
            phraseScore(normalizedLabel, hint.normalizedLookupKey()) != null
        }
}

private fun String.hasSearchEntryEvidence(profile: AppInteractionProfile?): Boolean =
    hasStrongSearchEntryEvidence(this) ||
        profile?.searchEntryHints.orEmpty().any { hint ->
            phraseScore(this, hint.normalizedLookupKey()) != null
        }

private fun ScreenNode.labelForSearchContext(): String =
    listOf(text, contentDescription, id, className)
        .filter { value -> value.isNotBlank() }
        .joinToString(separator = " ")
