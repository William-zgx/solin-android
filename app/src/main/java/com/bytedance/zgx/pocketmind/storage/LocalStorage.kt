package com.bytedance.zgx.pocketmind.storage

import java.io.Closeable
import java.io.File

object LocalStorageCollections {
    const val KEY_VALUE = "pm_kv_v1"
    const val DOCUMENTS = "pm_docs_v1"
    const val VECTORS = "pm_vectors_v1"
    const val SCHEMA_VERSION = 1
    const val EMBEDDING_DIMENSION = 768
}

enum class LocalStorageHealth {
    Ready,
    Unavailable,
}

data class LocalStorageStatus(
    val health: LocalStorageHealth,
    val rootDir: File,
    val detail: String = "",
) {
    val isReady: Boolean
        get() = health == LocalStorageHealth.Ready
}

interface LocalStorageKernel : Closeable {
    val rootDir: File
    fun open(): LocalStorageStatus
    fun healthCheck(): LocalStorageStatus
    fun flush()
}

data class LocalKeyValueEntry(
    val namespace: String,
    val key: String,
    val valueJson: String,
    val updatedAtMillis: Long,
    val schemaVersion: Int = LocalStorageCollections.SCHEMA_VERSION,
) {
    init {
        require(namespace.isNotBlank()) { "namespace must not be blank" }
        require(key.isNotBlank()) { "key must not be blank" }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
    }
}

interface LocalKeyValueStore {
    fun get(namespace: String, key: String): LocalKeyValueEntry?
    fun put(entry: LocalKeyValueEntry)
    fun delete(namespace: String, key: String): Boolean
    fun entries(namespace: String): List<LocalKeyValueEntry>
}

data class LocalDocument(
    val domain: String,
    val id: String,
    val ownerId: String?,
    val privacy: String,
    val sensitivity: String,
    val type: String,
    val sourceHash: String,
    val payloadJson: String,
    val searchText: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val expiresAtMillis: Long? = null,
    val deletedAtMillis: Long? = null,
    val schemaVersion: Int = LocalStorageCollections.SCHEMA_VERSION,
) {
    init {
        require(domain.isNotBlank()) { "domain must not be blank" }
        require(id.isNotBlank()) { "id must not be blank" }
        require(privacy.isNotBlank()) { "privacy must not be blank" }
        require(sensitivity.isNotBlank()) { "sensitivity must not be blank" }
        require(type.isNotBlank()) { "type must not be blank" }
        require(sourceHash.isNotBlank()) { "sourceHash must not be blank" }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
    }
}

data class LocalDocumentQuery(
    val domain: String? = null,
    val ownerId: String? = null,
    val type: String? = null,
    val textQuery: String? = null,
    val includeDeleted: Boolean = false,
    val limit: Int = Int.MAX_VALUE,
)

interface LocalDocumentStore {
    fun upsert(document: LocalDocument)
    fun fetch(domain: String, id: String): LocalDocument?
    fun query(query: LocalDocumentQuery = LocalDocumentQuery()): List<LocalDocument>
    fun delete(domain: String, id: String, deletedAtMillis: Long): Boolean
}

data class LocalVectorRecord(
    val domain: String,
    val id: String,
    val modelId: String,
    val sourceHash: String,
    val dimension: Int,
    val privacy: String,
    val type: String,
    val updatedAtMillis: Long,
    val embedding: FloatArray,
) {
    init {
        require(domain.isNotBlank()) { "domain must not be blank" }
        require(id.isNotBlank()) { "id must not be blank" }
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(sourceHash.isNotBlank()) { "sourceHash must not be blank" }
        require(dimension == LocalStorageCollections.EMBEDDING_DIMENSION) {
            "dimension must be ${LocalStorageCollections.EMBEDDING_DIMENSION}"
        }
        require(embedding.size == dimension) { "embedding size must match dimension" }
        require(privacy.isNotBlank()) { "privacy must not be blank" }
        require(type.isNotBlank()) { "type must not be blank" }
    }
}

data class LocalVectorQuery(
    val domain: String? = null,
    val modelId: String? = null,
    val type: String? = null,
    val embedding: FloatArray,
    val topK: Int = 10,
) {
    init {
        require(embedding.size == LocalStorageCollections.EMBEDDING_DIMENSION) {
            "query embedding must be ${LocalStorageCollections.EMBEDDING_DIMENSION} dimensions"
        }
    }
}

data class LocalVectorHit(
    val record: LocalVectorRecord,
    val score: Float,
)

interface LocalVectorIndex {
    fun upsert(record: LocalVectorRecord)
    fun fetch(domain: String, id: String, modelId: String): LocalVectorRecord?
    fun query(query: LocalVectorQuery): List<LocalVectorHit>
    fun delete(domain: String, id: String, modelId: String? = null): Boolean
    fun deleteForModel(modelId: String, domain: String? = null): Int
    fun clear(domain: String? = null): Int
}
