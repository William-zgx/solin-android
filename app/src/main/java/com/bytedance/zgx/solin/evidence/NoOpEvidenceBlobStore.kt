package com.bytedance.zgx.solin.evidence

import com.bytedance.zgx.solin.MessagePrivacy
import java.nio.charset.StandardCharsets

/**
 * Stand-in store that does NOT persist content. Used as the default constructor
 * argument for AgentLoopRuntime so tests and legacy callers don't touch disk.
 * All read methods return empty/null; put methods return a dummy ref pointing at
 * zero-sha (callers must never rely on these refs being resolvable).
 */
object NoOpEvidenceBlobStore : EvidenceBlobStore {
    private val DUMMY_SHA = "0".repeat(64)

    override fun putText(
        text: String,
        sourceType: EvidenceSourceType,
        privacy: MessagePrivacy,
        ttlMs: Long,
    ): EvidenceBlobRef =
        EvidenceBlobRef.fromSha256(
            DUMMY_SHA,
            text.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            "text/plain; charset=utf-8",
            privacy,
            sourceType,
            null,
        )

    override fun putBytes(
        bytes: ByteArray,
        mimeType: String?,
        sourceType: EvidenceSourceType,
        privacy: MessagePrivacy,
        ttlMs: Long,
    ): EvidenceBlobRef =
        EvidenceBlobRef.fromSha256(DUMMY_SHA, bytes.size.toLong(), mimeType, privacy, sourceType, null)

    override fun readText(ref: EvidenceBlobRef, offset: Int, limit: Int): TextWindow =
        TextWindow("", 0, truncated = true, totalChars = 0)

    override fun readBytes(ref: EvidenceBlobRef): ByteArray? = null

    override fun headTailText(ref: EvidenceBlobRef, headChars: Int, tailChars: Int): HeadTailResult =
        HeadTailResult("", ref, truncated = true, omittedChars = 0)

    override fun gc() {}

    override fun clear() {}
}
