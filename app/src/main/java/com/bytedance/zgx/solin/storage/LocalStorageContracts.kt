package com.bytedance.zgx.solin.storage

import com.bytedance.zgx.solin.MessagePrivacy

/*
 * Read-only model of the retired plaintext memory storage (SharedPreferences documents plus a zvec
 * vector index). Production reads it exactly once, in LegacyMemoryStorageRetirement, to import into
 * encrypted Room and then delete it. Nothing here is ever sent off-device.
 */

object LocalVectorIndexContract {
    const val DIMENSIONS = 768
}

data class LocalDocument(
    val domain: String = "default",
    val id: String,
    val ownerId: String = "",
    val title: String,
    val text: String,
    val sensitivity: String = "",
    val type: String = "",
    val sourceHash: String = "",
    val metadataJson: String = "{}",
    val privacy: MessagePrivacy = MessagePrivacy.LocalOnly,
    val updatedAtMillis: Long = 0L,
    val expiresAtMillis: Long? = null,
    val deletedAtMillis: Long? = null,
) {
    init {
        require(domain.isNotBlank()) { "Document domain must not be blank" }
        require(id.isNotBlank()) { "Document id must not be blank" }
    }
}

/** A vector read from the legacy zvec index; [dimension] is the one recorded in its metadata. */
class LegacyVector(
    val dimension: Int,
    val vector: FloatArray,
) {
    init {
        require(dimension == LocalVectorIndexContract.DIMENSIONS) {
            "Vector dimension must be ${LocalVectorIndexContract.DIMENSIONS}"
        }
    }
}
