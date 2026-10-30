package com.bytedance.zgx.solin.memory

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.storage.LocalDocument
import com.bytedance.zgx.solin.storage.LocalDocumentStore
import com.bytedance.zgx.solin.storage.LocalVectorIndex
import com.bytedance.zgx.solin.storage.LocalVectorIndexContract
import com.bytedance.zgx.solin.storage.LocalVectorRecord
import org.json.JSONObject

/*
 * Memory persistence contracts (records, deletion audit events, embeddings), their no-op
 * defaults and the zvec-backed stores. Split out of MemoryRepository.kt; behaviour unchanged.
 */

data class PersistedMemoryRecord(
    val id: String,
    val type: MemoryRecordType,
    val text: String,
    val source: MemoryRecordSource = MemoryRecordSource.LegacyImport,
    val sensitivity: MemoryRecordSensitivity = MemoryRecordSensitivity.Normal,
    val privacy: MessagePrivacy = MessagePrivacy.LocalOnly,
    val expiresAtMillis: Long? = null,
    val conflictKey: String? = null,
)

enum class MemoryDeletionOperation {
    Forget,
    Clear,
    ConflictReplace,
}

data class MemoryDeletionEvent(
    val id: String,
    val recordId: String,
    val recordType: MemoryRecordType,
    val operation: MemoryDeletionOperation,
    val recordTextHash: String,
    val recordSource: MemoryRecordSource,
    val recordSensitivity: MemoryRecordSensitivity,
    val conflictKey: String? = null,
    val deletedAtMillis: Long,
)

interface MemoryRecordStore {
    fun records(): List<PersistedMemoryRecord>
    fun upsert(record: PersistedMemoryRecord)
    fun delete(id: String): Boolean
    fun clear()
}

interface MemoryDeletionEventStore {
    fun events(): List<MemoryDeletionEvent>
    fun append(event: MemoryDeletionEvent)
    fun deleteRecordAndAppendEvent(
        recordStore: MemoryRecordStore,
        id: String,
        event: MemoryDeletionEvent,
    ): Boolean {
        val deleted = recordStore.delete(id)
        if (deleted) append(event)
        return deleted
    }

    fun clearRecordsAndAppendEvents(
        recordStore: MemoryRecordStore,
        events: List<MemoryDeletionEvent>,
    ) {
        recordStore.clear()
        events.forEach(::append)
    }
}

data class PersistedMemoryEmbedding(
    val recordId: String,
    val modelId: String,
    val sourceHash: String,
    val dimension: Int,
    val vector: FloatArray,
    val updatedAtMillis: Long,
)

interface MemoryEmbeddingStore {
    fun embedding(recordId: String, modelId: String): PersistedMemoryEmbedding?
    fun upsert(embedding: PersistedMemoryEmbedding)
    fun delete(recordId: String)
    fun deleteForModel(modelId: String)
    fun clear()
}

object NoOpMemoryRecordStore : MemoryRecordStore {
    override fun records(): List<PersistedMemoryRecord> = emptyList()
    override fun upsert(record: PersistedMemoryRecord) = Unit
    override fun delete(id: String): Boolean = false
    override fun clear() = Unit
}

object NoOpMemoryEmbeddingStore : MemoryEmbeddingStore {
    override fun embedding(recordId: String, modelId: String): PersistedMemoryEmbedding? = null
    override fun upsert(embedding: PersistedMemoryEmbedding) = Unit
    override fun delete(recordId: String) = Unit
    override fun deleteForModel(modelId: String) = Unit
    override fun clear() = Unit
}

class ZvecMemoryRecordStore(
    private val documents: LocalDocumentStore,
    private val mirrorStore: MemoryRecordStore = NoOpMemoryRecordStore,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : MemoryRecordStore {
    override fun records(): List<PersistedMemoryRecord> =
        documents.list().mapNotNull { document -> document.toPersistedMemoryRecord() }

    override fun upsert(record: PersistedMemoryRecord) {
        val now = clockMillis()
        val existing = documents.get(record.id)
        documents.upsert(
            record.toLocalDocument(
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now,
            ),
        )
        mirrorStore.upsert(record)
    }

    override fun delete(id: String): Boolean {
        val deletedDocument = documents.delete(id)
        val deletedMirror = mirrorStore.delete(id)
        return deletedDocument || deletedMirror
    }

    override fun clear() {
        documents.list()
            .filter { document -> document.toPersistedMemoryRecord() != null }
            .forEach { document -> documents.delete(document.id) }
        mirrorStore.clear()
    }

    private fun PersistedMemoryRecord.toLocalDocument(
        createdAtMillis: Long,
        updatedAtMillis: Long,
    ): LocalDocument =
        LocalDocument(
            domain = ZVEC_MEMORY_DOMAIN,
            id = id,
            title = type.name,
            text = text,
            sensitivity = sensitivity.name,
            type = type.name,
            sourceHash = memoryRecordTextHash(text),
            payloadJson = memoryRecordPayloadJson(this),
            searchText = text,
            createdAtMillis = createdAtMillis,
            metadataJson = memoryRecordPayloadJson(this),
            privacy = privacy,
            updatedAtMillis = updatedAtMillis,
            expiresAtMillis = expiresAtMillis,
        )

    private fun LocalDocument.toPersistedMemoryRecord(): PersistedMemoryRecord? {
        if (domain != ZVEC_MEMORY_DOMAIN || deletedAtMillis != null) return null
        val recordType = runCatching { MemoryRecordType.valueOf(type) }.getOrNull() ?: return null
        val metadata = metadataJson.jsonObjectOrNull()
        return PersistedMemoryRecord(
            id = id,
            type = recordType,
            text = text,
            source = metadata
                ?.optStringOrNull(ZVEC_MEMORY_SOURCE_KEY)
                ?.let { source -> runCatching { MemoryRecordSource.valueOf(source) }.getOrNull() }
                ?: MemoryRecordSource.LegacyImport,
            sensitivity = runCatching { MemoryRecordSensitivity.valueOf(sensitivity) }
                .getOrDefault(MemoryRecordSensitivity.Normal),
            privacy = privacy,
            expiresAtMillis = expiresAtMillis,
            conflictKey = metadata?.optStringOrNull(ZVEC_MEMORY_CONFLICT_KEY),
        )
    }
}

class ZvecMemoryEmbeddingStore(
    private val documents: LocalDocumentStore,
    private val vectors: LocalVectorIndex,
    private val mirrorStore: MemoryEmbeddingStore = NoOpMemoryEmbeddingStore,
) : MemoryEmbeddingStore {
    override fun embedding(recordId: String, modelId: String): PersistedMemoryEmbedding? =
        vectors.get(zvecMemoryVectorId(recordId, modelId))
            ?.takeIf { record -> record.modelId == modelId && record.documentId == recordId }
            ?.toPersistedMemoryEmbedding()

    override fun upsert(embedding: PersistedMemoryEmbedding) {
        val vectorRecord = embedding.toLocalVectorRecord()
        vectors.upsert(vectorRecord)
        documents.upsert(embedding.toMetadataDocument(vectorRecord.id))
        mirrorStore.upsert(embedding)
    }

    override fun delete(recordId: String) {
        embeddingMetadataDocuments()
            .filter { document -> document.ownerId == recordId }
            .forEach { document ->
                vectors.delete(document.id)
                documents.delete(document.id)
            }
        mirrorStore.delete(recordId)
    }

    override fun deleteForModel(modelId: String) {
        embeddingMetadataDocuments()
            .filter { document -> document.title == modelId }
            .forEach { document ->
                vectors.delete(document.id)
                documents.delete(document.id)
            }
        mirrorStore.deleteForModel(modelId)
    }

    override fun clear() {
        embeddingMetadataDocuments().forEach { document ->
            vectors.delete(document.id)
            documents.delete(document.id)
        }
        mirrorStore.clear()
    }

    private fun PersistedMemoryEmbedding.toLocalVectorRecord(): LocalVectorRecord {
        require(dimension == LocalVectorIndexContract.DIMENSIONS) {
            "Memory embedding dimension must be ${LocalVectorIndexContract.DIMENSIONS}"
        }
        require(vector.size == LocalVectorIndexContract.DIMENSIONS) {
            "Memory embedding vector must have ${LocalVectorIndexContract.DIMENSIONS} dimensions"
        }
        return LocalVectorRecord(
            domain = ZVEC_MEMORY_DOMAIN,
            id = zvecMemoryVectorId(recordId, modelId),
            modelId = modelId,
            sourceHash = sourceHash,
            dimension = dimension,
            type = ZVEC_MEMORY_EMBEDDING_TYPE,
            documentId = recordId,
            text = recordId,
            vector = vector.copyOf(),
            metadataJson = memoryEmbeddingPayloadJson(this),
            privacy = MessagePrivacy.LocalOnly,
            updatedAtMillis = updatedAtMillis,
        )
    }

    private fun PersistedMemoryEmbedding.toMetadataDocument(vectorId: String): LocalDocument =
        LocalDocument(
            domain = ZVEC_MEMORY_DOMAIN,
            id = vectorId,
            ownerId = recordId,
            title = modelId,
            text = recordId,
            sensitivity = MemoryRecordSensitivity.Internal.name,
            type = ZVEC_MEMORY_EMBEDDING_TYPE,
            sourceHash = sourceHash,
            payloadJson = memoryEmbeddingPayloadJson(this),
            searchText = recordId,
            createdAtMillis = updatedAtMillis,
            metadataJson = memoryEmbeddingPayloadJson(this),
            privacy = MessagePrivacy.LocalOnly,
            updatedAtMillis = updatedAtMillis,
        )

    private fun LocalVectorRecord.toPersistedMemoryEmbedding(): PersistedMemoryEmbedding =
        PersistedMemoryEmbedding(
            recordId = documentId,
            modelId = modelId,
            sourceHash = sourceHash,
            dimension = dimension,
            vector = vector.copyOf(),
            updatedAtMillis = updatedAtMillis,
        )

    private fun embeddingMetadataDocuments(): List<LocalDocument> =
        documents.list().filter { document ->
            document.domain == ZVEC_MEMORY_DOMAIN &&
                document.type == ZVEC_MEMORY_EMBEDDING_TYPE &&
                document.deletedAtMillis == null
        }
}

private fun memoryRecordPayloadJson(record: PersistedMemoryRecord): String =
    JSONObject()
        .put(ZVEC_MEMORY_SOURCE_KEY, record.source.name)
        .put(ZVEC_MEMORY_SENSITIVITY_KEY, record.sensitivity.name)
        .apply {
            record.conflictKey?.let { conflictKey -> put(ZVEC_MEMORY_CONFLICT_KEY, conflictKey) }
        }
        .toString()

private fun memoryEmbeddingPayloadJson(embedding: PersistedMemoryEmbedding): String =
    JSONObject()
        .put(ZVEC_MEMORY_RECORD_ID_KEY, embedding.recordId)
        .put(ZVEC_MEMORY_MODEL_ID_KEY, embedding.modelId)
        .put(ZVEC_MEMORY_DIMENSION_KEY, embedding.dimension)
        .toString()

private fun String.jsonObjectOrNull(): JSONObject? =
    runCatching { JSONObject(this) }.getOrNull()

private fun JSONObject.optStringOrNull(key: String): String? =
    optString(key).takeIf { value -> value.isNotBlank() }

private fun zvecMemoryVectorId(recordId: String, modelId: String): String =
    deterministicMemoryRecordId(
        prefix = ZVEC_MEMORY_VECTOR_ID_PREFIX,
        key = "$recordId|$modelId",
    )

private const val ZVEC_MEMORY_DOMAIN = "memory"

private const val ZVEC_MEMORY_EMBEDDING_TYPE = "MemoryEmbedding"

private const val ZVEC_MEMORY_VECTOR_ID_PREFIX = "memory-vector"

private const val ZVEC_MEMORY_SOURCE_KEY = "source"

private const val ZVEC_MEMORY_SENSITIVITY_KEY = "sensitivity"

private const val ZVEC_MEMORY_CONFLICT_KEY = "conflictKey"

private const val ZVEC_MEMORY_RECORD_ID_KEY = "recordId"

private const val ZVEC_MEMORY_MODEL_ID_KEY = "modelId"

private const val ZVEC_MEMORY_DIMENSION_KEY = "dimension"

object NoOpMemoryDeletionEventStore : MemoryDeletionEventStore {
    override fun events(): List<MemoryDeletionEvent> = emptyList()
    override fun append(event: MemoryDeletionEvent) = Unit
}
