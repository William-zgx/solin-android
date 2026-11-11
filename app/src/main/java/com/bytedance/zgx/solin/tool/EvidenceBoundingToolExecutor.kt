package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.evidence.EvidenceBlobStore
import com.bytedance.zgx.solin.evidence.EvidenceBounds
import com.bytedance.zgx.solin.evidence.EvidenceSourceType
import com.bytedance.zgx.solin.evidence.HeadTailResult
import com.bytedance.zgx.solin.evidence.NoOpEvidenceBlobStore
import org.json.JSONArray
import org.json.JSONObject

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

    private fun headTail(text: String, maxChars: Int) = EvidenceBounds.headTail(
        text = text,
        maxChars = maxChars,
        sourceType = EvidenceSourceType.ToolResult,
        privacy = evidencePrivacy,
        store = evidenceBlobStore
    )

    /** Keeps structured tool contracts parseable while still bounding ordinary text. */
    private fun boundDataValue(text: String, maxChars: Int): HeadTailResult {
        if (text.length <= maxChars) return HeadTailResult(text, ref = null, truncated = false, omittedChars = 0)
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && runCatching { JSONObject(trimmed) }.isSuccess) {
            // A generic object subset can drop contract-required keys. Keep it intact and let the
            // prompt layer apply its own presentation bounds instead of corrupting the tool result.
            return HeadTailResult(text, ref = null, truncated = false, omittedChars = 0)
        }
        val bounded = headTail(text, maxChars)
        val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return bounded
        val inline = inlineJsonArrayPrefix(array, maxChars)
        return HeadTailResult(
            text = inline,
            ref = bounded.ref,
            truncated = true,
            omittedChars = text.length - inline.length
        )
    }

    /** Retains only whole array elements, stopping before the inline budget would be exceeded. */
    private fun inlineJsonArrayPrefix(array: JSONArray, maxChars: Int): String {
        val elements = mutableListOf<String>()
        var contentLength = 0
        for (index in 0 until array.length()) {
            val wrappedElement = JSONArray().put(array.get(index)).toString()
            val element = wrappedElement.substring(1, wrappedElement.length - 1)
            val addedLength = element.length + if (elements.isEmpty()) 0 else 1
            if (2 + contentLength + addedLength > maxChars) break
            elements += element
            contentLength += addedLength
        }
        return elements.joinToString(separator = ",", prefix = "[", postfix = "]")
    }

    private fun bound(result: ToolResult): ToolResult {
        val summary = headTail(result.summary, maxSummaryChars)
        val truncatedData = result.data.mapValues { (_, value) -> boundDataValue(value, maxDataValueChars) }
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
