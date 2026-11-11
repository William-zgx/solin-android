package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.evidence.EvidenceBlobStore
import com.bytedance.zgx.solin.evidence.EvidenceBounds
import com.bytedance.zgx.solin.evidence.EvidenceSourceType
import com.bytedance.zgx.solin.evidence.NoOpEvidenceBlobStore

/**
 * Bounds tool output independently of routing and observation.
 *
 * Oversized inline text is truncated and, when a real store is configured, spilled into the
 * content-addressed [evidenceBlobStore]. Evidence remains [MessagePrivacy.LocalOnly] unless the
 * composition root explicitly supplies another reviewed label; unknown or private evidence must
 * never become remotely eligible as a side effect of this decorator.
 */
class EvidenceBoundingToolExecutor(
    private val delegate: ToolExecutor,
    private val evidenceBlobStore: EvidenceBlobStore = NoOpEvidenceBlobStore,
    private val maxSummaryChars: Int = EvidenceBlobStore.MAX_INLINE_CHARS,
    private val maxDataValueChars: Int = EvidenceBlobStore.MAX_INLINE_CHARS,
    private val evidencePrivacy: MessagePrivacy = MessagePrivacy.LocalOnly
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult = bound(delegate.execute(request))

    override fun executeBatch(requests: List<ToolRequest>): List<ToolResult> = delegate.executeBatch(requests).mapIndexed { index, result ->
        val request = requests.getOrNull(index)
        if (request == null) {
            bound(result)
        } else {
            runCatching { bound(result) }
                .getOrElse { throwable -> request.batchThrowableResult(throwable, concurrent = false) }
        }
    }

    private fun bound(result: ToolResult): ToolResult {
        fun headTail(text: String, maxChars: Int) = EvidenceBounds.headTail(
            text = text,
            maxChars = maxChars,
            sourceType = EvidenceSourceType.ToolResult,
            privacy = evidencePrivacy,
            store = evidenceBlobStore
        )

        val summary = headTail(result.summary, maxSummaryChars)
        val truncatedData = result.data.mapValues { (_, value) -> headTail(value, maxDataValueChars) }
            .filterValues { bounded -> bounded.truncated }
        if (!summary.truncated && truncatedData.isEmpty()) return result
        val newRefs = listOfNotNull(summary.ref.takeIf { summary.truncated }) +
            truncatedData.values.mapNotNull { bounded -> bounded.ref }
        return result.copy(
            summary = if (summary.truncated) summary.text else result.summary,
            data = result.data + truncatedData.mapValues { (_, bounded) -> bounded.text },
            overflowRefs = result.overflowRefs + newRefs
        )
    }
}
