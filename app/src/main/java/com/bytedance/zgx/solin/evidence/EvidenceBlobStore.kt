package com.bytedance.zgx.solin.evidence

import com.bytedance.zgx.solin.MessagePrivacy

/**
 * Content-addressed overflow storage for large evidence payloads that would
 * otherwise blow the prompt budget. Blobs are referenced by "ev://<sha256>" URIs
 * and are NEVER exposed to the model as a callable tool — they are an on-device
 * implementation detail.
 */
interface EvidenceBlobStore {
    fun putText(
        text: String,
        sourceType: EvidenceSourceType,
        privacy: MessagePrivacy = MessagePrivacy.LocalOnly,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): EvidenceBlobRef

    fun putBytes(
        bytes: ByteArray,
        mimeType: String?,
        sourceType: EvidenceSourceType,
        privacy: MessagePrivacy = MessagePrivacy.LocalOnly,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): EvidenceBlobRef

    fun readText(ref: EvidenceBlobRef, offset: Int = 0, limit: Int = MAX_INLINE_CHARS): TextWindow
    fun readBytes(ref: EvidenceBlobRef): ByteArray?
    fun headTailText(ref: EvidenceBlobRef, headChars: Int, tailChars: Int): HeadTailResult
    fun gc()
    fun clear()

    companion object {
        const val DEFAULT_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000
        const val MAX_TOTAL_BYTES: Long = 64L * 1024 * 1024
        const val MAX_INLINE_CHARS: Int = 4_000
    }
}
