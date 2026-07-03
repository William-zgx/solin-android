package com.bytedance.zgx.solin.evidence

import com.bytedance.zgx.solin.MessagePrivacy

/**
 * Content-addressed reference to a blob in EvidenceBlobStore.
 * uri is always "ev://<sha256>" where sha256 is lowercase hex.
 */
data class EvidenceBlobRef(
    val uri: String,
    val sha256: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val privacy: MessagePrivacy,
    val sourceType: EvidenceSourceType,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val ttlUntilMillis: Long? = null,
) {
    init {
        require(SHA256_HEX.matches(sha256)) { "sha256 must be 64 lowercase hex chars, got '$sha256'" }
        require(uri == "$URI_SCHEME$sha256") { "uri must be $URI_SCHEME<sha256>, got '$uri'" }
        require(sizeBytes >= 0) { "sizeBytes must be non-negative, got $sizeBytes" }
    }

    companion object {
        const val URI_SCHEME = "ev://"
        val SHA256_HEX: Regex = Regex("^[a-f0-9]{64}$")

        fun fromSha256(
            sha256: String,
            sizeBytes: Long,
            mimeType: String?,
            privacy: MessagePrivacy,
            sourceType: EvidenceSourceType,
            ttlUntilMillis: Long? = null,
        ): EvidenceBlobRef =
            EvidenceBlobRef(
                uri = "$URI_SCHEME$sha256",
                sha256 = sha256,
                sizeBytes = sizeBytes,
                mimeType = mimeType,
                privacy = privacy,
                sourceType = sourceType,
                createdAtMillis = System.currentTimeMillis(),
                ttlUntilMillis = ttlUntilMillis,
            )
    }
}

/** Window into a text blob returned by readText. */
data class TextWindow(
    val text: String,
    val offset: Int,
    val truncated: Boolean,
    val totalChars: Int,
)

/** Result of EvidenceBounds.headTail: either inline text or text + ev:// ref. */
data class HeadTailResult(
    val text: String,
    val ref: EvidenceBlobRef?,
    val truncated: Boolean,
    val omittedChars: Int,
)
