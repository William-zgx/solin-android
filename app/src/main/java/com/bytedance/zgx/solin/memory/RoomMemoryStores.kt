package com.bytedance.zgx.solin.memory

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.common.persistedEnumOrNull
import com.bytedance.zgx.solin.data.MemoryDeletionEventDao
import com.bytedance.zgx.solin.data.MemoryDeletionEventEntity
import com.bytedance.zgx.solin.data.MemoryDeletionTransactionDao
import com.bytedance.zgx.solin.data.MemoryEmbeddingDao
import com.bytedance.zgx.solin.data.MemoryEmbeddingEntity
import com.bytedance.zgx.solin.data.MemoryRecordDao
import com.bytedance.zgx.solin.data.MemoryRecordEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Room-backed memory stores. Split out of MemoryRepository.kt; behaviour unchanged.
 */

class RoomMemoryRecordStore(
    private val dao: MemoryRecordDao,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) : MemoryRecordStore {
    override fun records(): List<PersistedMemoryRecord> = dao.records().mapNotNull { entity ->
        val type = persistedEnumOrNull<MemoryRecordType>(entity.type) ?: return@mapNotNull null
        PersistedMemoryRecord(
            id = entity.id,
            type = type,
            text = entity.text,
            source = persistedEnumOrNull<MemoryRecordSource>(entity.source) ?: MemoryRecordSource.LegacyImport,
            sensitivity = persistedEnumOrNull<MemoryRecordSensitivity>(entity.sensitivity)
                ?: MemoryRecordSensitivity.Normal,
            privacy = persistedEnumOrNull<MessagePrivacy>(entity.privacy) ?: MessagePrivacy.LocalOnly,
            expiresAtMillis = entity.expiresAtMillis,
            conflictKey = entity.conflictKey?.takeIf { it.isNotBlank() }
        )
    }

    override fun upsert(record: PersistedMemoryRecord) {
        val now = clockMillis()
        val existing = dao.record(record.id)
        dao.upsert(
            MemoryRecordEntity(
                id = record.id,
                type = record.type.name,
                text = record.text,
                source = record.source.name,
                sensitivity = record.sensitivity.name,
                privacy = record.privacy.name,
                expiresAtMillis = record.expiresAtMillis,
                conflictKey = record.conflictKey,
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now
            )
        )
    }

    override fun delete(id: String): Boolean = dao.delete(id) > 0

    override fun clear() {
        dao.deleteAll()
    }
}

class RoomMemoryDeletionEventStore(
    private val dao: MemoryDeletionEventDao,
    private val transactionDao: MemoryDeletionTransactionDao? = null
) : MemoryDeletionEventStore {
    override fun events(): List<MemoryDeletionEvent> = dao.events().mapNotNull { entity ->
        val recordType = persistedEnumOrNull<MemoryRecordType>(entity.recordType) ?: return@mapNotNull null
        val operation = persistedEnumOrNull<MemoryDeletionOperation>(entity.operation) ?: return@mapNotNull null
        MemoryDeletionEvent(
            id = entity.id,
            recordId = entity.recordId,
            recordType = recordType,
            operation = operation,
            recordTextHash = entity.recordTextHash,
            recordSource = persistedEnumOrNull<MemoryRecordSource>(entity.recordSource)
                ?: MemoryRecordSource.LegacyImport,
            recordSensitivity = persistedEnumOrNull<MemoryRecordSensitivity>(entity.recordSensitivity)
                ?: MemoryRecordSensitivity.Normal,
            conflictKey = entity.conflictKey?.takeIf { it.isNotBlank() },
            deletedAtMillis = entity.deletedAtMillis
        )
    }

    override fun append(event: MemoryDeletionEvent) {
        dao.insert(event.toEntity())
    }

    override fun deleteRecordAndAppendEvent(
        recordStore: MemoryRecordStore,
        id: String,
        event: MemoryDeletionEvent
    ): Boolean {
        val transactionDao = transactionDao
        if (transactionDao != null) {
            return transactionDao.deleteRecordAndAppendEvent(id, event.toEntity()) > 0
        }
        return super<MemoryDeletionEventStore>.deleteRecordAndAppendEvent(recordStore, id, event)
    }

    override fun clearRecordsAndAppendEvents(
        recordStore: MemoryRecordStore,
        events: List<MemoryDeletionEvent>
    ) {
        val transactionDao = transactionDao
        if (transactionDao != null) {
            transactionDao.clearRecordsAndAppendEvents(events.map { event -> event.toEntity() })
        } else {
            super<MemoryDeletionEventStore>.clearRecordsAndAppendEvents(recordStore, events)
        }
    }

    private fun MemoryDeletionEvent.toEntity(): MemoryDeletionEventEntity = MemoryDeletionEventEntity(
        id = id,
        recordId = recordId,
        recordType = recordType.name,
        operation = operation.name,
        recordTextHash = recordTextHash,
        recordSource = recordSource.name,
        recordSensitivity = recordSensitivity.name,
        conflictKey = conflictKey,
        deletedAtMillis = deletedAtMillis
    )
}

class RoomMemoryEmbeddingStore(
    private val dao: MemoryEmbeddingDao
) : MemoryEmbeddingStore {
    override fun embedding(recordId: String, modelId: String): PersistedMemoryEmbedding? = dao.embedding(recordId, modelId)?.toPersisted()

    override fun upsert(embedding: PersistedMemoryEmbedding) {
        dao.upsert(embedding.toEntity())
    }

    override fun delete(recordId: String) {
        dao.delete(recordId)
    }

    override fun deleteForModel(modelId: String) {
        dao.deleteForModel(modelId)
    }

    override fun clear() {
        dao.deleteAll()
    }

    private fun MemoryEmbeddingEntity.toPersisted(): PersistedMemoryEmbedding? {
        val vector = vectorBlob.decodeFloatVector(dimension) ?: return null
        return PersistedMemoryEmbedding(
            recordId = recordId,
            modelId = modelId,
            sourceHash = sourceHash,
            dimension = dimension,
            vector = vector,
            updatedAtMillis = updatedAtMillis
        )
    }

    private fun PersistedMemoryEmbedding.toEntity(): MemoryEmbeddingEntity = MemoryEmbeddingEntity(
        recordId = recordId,
        modelId = modelId,
        sourceHash = sourceHash,
        dimension = dimension,
        vectorBlob = vector.encodeFloatVector(),
        updatedAtMillis = updatedAtMillis
    )

    private fun FloatArray.encodeFloatVector(): ByteArray {
        val buffer = ByteBuffer.allocate(size * java.lang.Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
        forEach(buffer::putFloat)
        return buffer.array()
    }

    private fun ByteArray.decodeFloatVector(dimension: Int): FloatArray? {
        if (dimension <= 0 || size != dimension * java.lang.Float.BYTES) return null
        val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimension) { buffer.float }
    }
}
