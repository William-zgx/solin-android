package com.bytedance.zgx.solin.evidence

import com.bytedance.zgx.solin.MessagePrivacy
import java.util.Locale

/**
 * Bounding helpers that choose between inline prefix-truncation (legacy) and
 * head/tail-with-blob-ref when an EvidenceBlobStore is supplied.
 */
object EvidenceBounds {
    private const val MARKER_TEMPLATE = "\n… (%d chars omitted; full content at %s) …\n"
    private const val MIN_MARKER_OVERHEAD = 80

    fun headTail(
        text: String,
        maxChars: Int,
        sourceType: EvidenceSourceType,
        privacy: MessagePrivacy,
        store: EvidenceBlobStore? = null,
    ): HeadTailResult {
        if (text.length <= maxChars) {
            return HeadTailResult(text, ref = null, truncated = false, omittedChars = 0)
        }
        // Treat NoOpEvidenceBlobStore as "no store" to avoid emitting fake ev:// URIs
        // into prompts in tests/default configuration.
        val effectiveStore = if (store == null || store is NoOpEvidenceBlobStore) null else store
        if (effectiveStore == null || maxChars < 40) {
            val keep = maxOf(0, maxChars - 3)
            val truncated = text.take(keep).trimEnd() + "..."
            return HeadTailResult(
                truncated,
                ref = null,
                truncated = true,
                omittedChars = text.length - keep,
            )
        }
        val tailBudget = maxChars / 3
        val headBudget = maxChars - tailBudget - MIN_MARKER_OVERHEAD
        if (headBudget <= 0 || tailBudget <= 0) {
            val keep = maxOf(0, maxChars - 3)
            return HeadTailResult(
                text.take(keep).trimEnd() + "...",
                ref = null,
                truncated = true,
                omittedChars = text.length - keep,
            )
        }
        val ref = effectiveStore.putText(text, sourceType, privacy)
        val head = text.take(headBudget)
        val tail = text.takeLast(tailBudget)
        val omitted = text.length - headBudget - tailBudget
        val marker = String.format(Locale.ROOT, MARKER_TEMPLATE, omitted, ref.uri)
        return HeadTailResult(head + marker + tail, ref, truncated = true, omittedChars = omitted)
    }
}
