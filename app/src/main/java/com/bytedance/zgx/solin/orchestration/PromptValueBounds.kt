package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.evidence.EvidenceBlobStore
import com.bytedance.zgx.solin.tool.ToolResult

/**
 * Bounds a value interpolated into a continuation prompt (head/tail with overflow stored locally).
 * Overflow is always tagged LocalOnly, whatever prompt the value lands in.
 */
internal fun String.boundedPromptValue(
    evidenceBlobStore: EvidenceBlobStore,
    maxLength: Int = 2_000,
): String =
    com.bytedance.zgx.solin.evidence.EvidenceBounds.headTail(
        text = this,
        maxChars = maxLength,
        sourceType = com.bytedance.zgx.solin.evidence.EvidenceSourceType.ToolResult,
        privacy = com.bytedance.zgx.solin.MessagePrivacy.LocalOnly,
        store = evidenceBlobStore,
    ).text

/** "key: value" lines for every result data entry, sorted by key, each value bounded. */
internal fun ToolResult.promptDataBlock(evidenceBlobStore: EvidenceBlobStore): String =
    data.entries
        .sortedBy { (key, _) -> key }
        .joinToString(separator = "\n") { (key, value) ->
            "$key: ${value.boundedPromptValue(evidenceBlobStore)}"
        }
        .ifBlank { "无" }
