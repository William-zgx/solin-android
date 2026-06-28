package com.bytedance.zgx.solin.storage

import com.bytedance.zgx.solin.MessagePrivacy

object LocalStorageCollections {
    const val KEY_VALUES = "pm_kv_v1"
    const val DOCUMENTS = "pm_docs_v1"
    const val VECTORS = "pm_vectors_v1"

    val all: Set<String> = setOf(KEY_VALUES, DOCUMENTS, VECTORS)
}

object LocalVectorIndexContract {
    const val DIMENSIONS = 768
}

object LocalStorageSchemaVersions {
    const val CURRENT = 1
}

@RequiresOptIn(
    message = "Fake local storage is for contract tests and experiments; do not wire it as production storage.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalLocalStorageFake

enum class LocalStorageReadScope {
    LocalContext,
    RemoteSend,
}

data class LocalStorageHealth(
    val opened: Boolean,
    val healthy: Boolean,
    val collectionNames: Set<String>,
    val lastFlushedAtMillis: Long? = null,
)

data class LocalStorageSession(
    val documents: LocalDocumentStore,
    val keyValues: LocalKeyValueStore,
    val vectors: LocalVectorIndex,
)

interface LocalStorageKernel {
    fun health(): LocalStorageHealth
    fun open(): LocalStorageSession
    fun flush()
    fun close()
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
    val payloadJson: String = "{}",
    val searchText: String = text,
    val createdAtMillis: Long = 0L,
    val metadataJson: String = "{}",
    val privacy: MessagePrivacy = MessagePrivacy.LocalOnly,
    val updatedAtMillis: Long = 0L,
    val expiresAtMillis: Long? = null,
    val deletedAtMillis: Long? = null,
    val schemaVersion: Int = LocalStorageSchemaVersions.CURRENT,
) {
    init {
        require(domain.isNotBlank()) { "Document domain must not be blank" }
        require(id.isNotBlank()) { "Document id must not be blank" }
    }
}

interface LocalDocumentStore {
    val collectionName: String
        get() = LocalStorageCollections.DOCUMENTS

    fun upsert(document: LocalDocument): LocalDocument
    fun get(id: String, readScope: LocalStorageReadScope = LocalStorageReadScope.LocalContext): LocalDocument?
    fun list(readScope: LocalStorageReadScope = LocalStorageReadScope.LocalContext): List<LocalDocument>
    fun delete(id: String): Boolean
}

data class LocalKeyValueEntry(
    val namespace: String,
    val key: String,
    val valueJson: String,
    val privacy: MessagePrivacy = MessagePrivacy.LocalOnly,
    val updatedAtMillis: Long = 0L,
    val schemaVersion: Int = LocalStorageSchemaVersions.CURRENT,
) {
    init {
        require(namespace.isNotBlank()) { "Key-value namespace must not be blank" }
        require(key.isNotBlank()) { "Key-value key must not be blank" }
        require(valueJson.isNotBlank()) { "Key-value JSON must not be blank" }
    }
}

interface LocalKeyValueStore {
    val collectionName: String
        get() = LocalStorageCollections.KEY_VALUES

    fun upsert(entry: LocalKeyValueEntry): LocalKeyValueEntry
    fun get(
        namespace: String,
        key: String,
        readScope: LocalStorageReadScope = LocalStorageReadScope.LocalContext,
    ): LocalKeyValueEntry?
    fun list(namespace: String, readScope: LocalStorageReadScope = LocalStorageReadScope.LocalContext): List<LocalKeyValueEntry>
    fun delete(namespace: String, key: String): Boolean
}

data class LocalVectorRecord(
    val domain: String = "default",
    val id: String,
    val modelId: String = "",
    val sourceHash: String = "",
    val dimension: Int = LocalVectorIndexContract.DIMENSIONS,
    val type: String = "",
    val documentId: String,
    val text: String,
    val vector: FloatArray,
    val metadataJson: String = "{}",
    val privacy: MessagePrivacy = MessagePrivacy.LocalOnly,
    val updatedAtMillis: Long = 0L,
) {
    init {
        require(domain.isNotBlank()) { "Vector domain must not be blank" }
        require(id.isNotBlank()) { "Vector id must not be blank" }
        require(documentId.isNotBlank()) { "Vector document id must not be blank" }
        require(dimension == LocalVectorIndexContract.DIMENSIONS) {
            "Vector dimension must be ${LocalVectorIndexContract.DIMENSIONS}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocalVectorRecord) return false
        return domain == other.domain &&
            id == other.id &&
            modelId == other.modelId &&
            sourceHash == other.sourceHash &&
            dimension == other.dimension &&
            type == other.type &&
            documentId == other.documentId &&
            text == other.text &&
            vector.contentEquals(other.vector) &&
            metadataJson == other.metadataJson &&
            privacy == other.privacy &&
            updatedAtMillis == other.updatedAtMillis
    }

    override fun hashCode(): Int {
        var result = domain.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + sourceHash.hashCode()
        result = 31 * result + dimension
        result = 31 * result + type.hashCode()
        result = 31 * result + documentId.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + metadataJson.hashCode()
        result = 31 * result + privacy.hashCode()
        result = 31 * result + updatedAtMillis.hashCode()
        return result
    }
}

data class LocalVectorQuery(
    val text: String,
    val vector: FloatArray,
    val topK: Int = 5,
    val readScope: LocalStorageReadScope = LocalStorageReadScope.LocalContext,
) {
    init {
        require(topK > 0) { "Vector topK must be positive" }
    }
}

data class LocalVectorHit(
    val record: LocalVectorRecord,
    val score: Float,
    val cosineScore: Float,
    val lexicalScore: Float,
)

interface LocalVectorIndex {
    val collectionName: String
        get() = LocalStorageCollections.VECTORS
    val dimensions: Int
        get() = LocalVectorIndexContract.DIMENSIONS

    fun upsert(record: LocalVectorRecord): LocalVectorRecord
    fun get(id: String, readScope: LocalStorageReadScope = LocalStorageReadScope.LocalContext): LocalVectorRecord?
    fun delete(id: String): Boolean
    fun search(query: LocalVectorQuery): List<LocalVectorHit>
}

internal fun MessagePrivacy.isReadableFor(scope: LocalStorageReadScope): Boolean =
    scope == LocalStorageReadScope.LocalContext || this == MessagePrivacy.RemoteEligible
