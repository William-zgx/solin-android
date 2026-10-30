package com.bytedance.zgx.solin.memory

import com.bytedance.zgx.solin.MessagePrivacy

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

object NoOpMemoryDeletionEventStore : MemoryDeletionEventStore {
    override fun events(): List<MemoryDeletionEvent> = emptyList()
    override fun append(event: MemoryDeletionEvent) = Unit
}
