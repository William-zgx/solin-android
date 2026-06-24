package com.bytedance.zgx.pocketmind.memory

import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.storage.LocalDocument
import com.bytedance.zgx.pocketmind.storage.LocalDocumentQuery
import com.bytedance.zgx.pocketmind.storage.LocalDocumentStore
import com.bytedance.zgx.pocketmind.storage.LocalVectorIndex
import com.bytedance.zgx.pocketmind.storage.LocalVectorRecord
import java.security.MessageDigest
import org.json.JSONObject

class LocalDocumentMemoryRecordStore(
    private val documentStore: LocalDocumentStore,
    private val mirrorStore: MemoryRecordStore? = null,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : MemoryRecordStore {
    override fun records(): List<PersistedMemoryRecord> =
        documentStore.query(LocalDocumentQuery(domain = DOMAIN_MEMORY))
            .mapNotNull(::toMemoryRecord)

    override fun upsert(record: PersistedMemoryRecord) {
        val now = clockMillis()
        val existing = documentStore.fetch(DOMAIN_MEMORY, record.id)
        documentStore.upsert(record.toDocument(existing?.createdAtMillis ?: now, now))
        mirrorStore?.upsert(record)
    }

    override fun delete(id: String): Boolean {
        val deletedCanonical = documentStore.delete(DOMAIN_MEMORY, id, clockMillis())
        val deletedMirror = mirrorStore?.delete(id) ?: false
        return deletedCanonical || deletedMirror
    }

    override fun clear() {
        val now = clockMillis()
        documentStore.query(LocalDocumentQuery(domain = DOMAIN_MEMORY))
            .forEach { document -> documentStore.delete(document.domain, document.id, now) }
        mirrorStore?.clear()
    }

    private fun PersistedMemoryRecord.toDocument(createdAtMillis: Long, updatedAtMillis: Long): LocalDocument {
        val payload = JSONObject()
            .put("type", type.name)
            .put("text", text)
            .put("source", source.name)
            .put("sensitivity", sensitivity.name)
            .put("privacy", privacy.name)
            .put("expiresAtMillis", expiresAtMillis)
            .put("conflictKey", conflictKey)
        return LocalDocument(
            domain = DOMAIN_MEMORY,
            id = id,
            ownerId = null,
            privacy = privacy.name,
            sensitivity = sensitivity.name,
            type = type.name,
            sourceHash = sha256(text),
            payloadJson = payload.toString(),
            searchText = text,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            expiresAtMillis = expiresAtMillis,
        )
    }

    private fun toMemoryRecord(document: LocalDocument): PersistedMemoryRecord? =
        runCatching {
            val payload = JSONObject(document.payloadJson)
            PersistedMemoryRecord(
                id = document.id,
                type = MemoryRecordType.valueOf(payload.optString("type", document.type)),
                text = payload.getString("text"),
                source = MemoryRecordSource.valueOf(
                    payload.optString("source", MemoryRecordSource.LegacyImport.name),
                ),
                sensitivity = MemoryRecordSensitivity.valueOf(
                    payload.optString("sensitivity", MemoryRecordSensitivity.Normal.name),
                ),
                privacy = MessagePrivacy.valueOf(
                    payload.optString("privacy", MessagePrivacy.LocalOnly.name),
                ),
                expiresAtMillis = payload.optLongOrNull("expiresAtMillis"),
                conflictKey = payload.optString("conflictKey").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
}

class LocalDocumentMemoryDeletionEventStore(
    private val documentStore: LocalDocumentStore,
    private val mirrorStore: MemoryDeletionEventStore? = null,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : MemoryDeletionEventStore {
    override fun events(): List<MemoryDeletionEvent> =
        documentStore.query(LocalDocumentQuery(domain = DOMAIN_MEMORY_DELETION_EVENT))
            .mapNotNull(::toDeletionEvent)

    override fun append(event: MemoryDeletionEvent) {
        val now = clockMillis()
        val payload = JSONObject()
            .put("recordId", event.recordId)
            .put("recordType", event.recordType.name)
            .put("operation", event.operation.name)
            .put("recordTextHash", event.recordTextHash)
            .put("recordSource", event.recordSource.name)
            .put("recordSensitivity", event.recordSensitivity.name)
            .put("conflictKey", event.conflictKey)
            .put("deletedAtMillis", event.deletedAtMillis)
        documentStore.upsert(
            LocalDocument(
                domain = DOMAIN_MEMORY_DELETION_EVENT,
                id = event.id,
                ownerId = null,
                privacy = MessagePrivacy.LocalOnly.name,
                sensitivity = MemoryRecordSensitivity.Internal.name,
                type = "MemoryDeletionEvent",
                sourceHash = event.recordTextHash,
                payloadJson = payload.toString(),
                searchText = "${event.recordId} ${event.operation.name}",
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
        mirrorStore?.append(event)
    }

    override fun deleteRecordAndAppendEvent(
        recordStore: MemoryRecordStore,
        id: String,
        event: MemoryDeletionEvent,
    ): Boolean {
        if (recordStore.records().none { it.id == id }) return false
        append(event)
        return recordStore.delete(id)
    }

    override fun clearRecordsAndAppendEvents(
        recordStore: MemoryRecordStore,
        events: List<MemoryDeletionEvent>,
    ) {
        events.forEach(::append)
        recordStore.clear()
    }

    private fun toDeletionEvent(document: LocalDocument): MemoryDeletionEvent? =
        runCatching {
            val payload = JSONObject(document.payloadJson)
            MemoryDeletionEvent(
                id = document.id,
                recordId = payload.getString("recordId"),
                recordType = MemoryRecordType.valueOf(payload.getString("recordType")),
                operation = MemoryDeletionOperation.valueOf(payload.getString("operation")),
                recordTextHash = payload.getString("recordTextHash"),
                recordSource = MemoryRecordSource.valueOf(payload.getString("recordSource")),
                recordSensitivity = MemoryRecordSensitivity.valueOf(payload.getString("recordSensitivity")),
                conflictKey = payload.optString("conflictKey").takeIf { it.isNotBlank() },
                deletedAtMillis = payload.getLong("deletedAtMillis"),
            )
        }.getOrNull()
}

class LocalVectorMemoryEmbeddingStore(
    private val vectorIndex: LocalVectorIndex,
    private val mirrorStore: MemoryEmbeddingStore? = null,
) : MemoryEmbeddingStore {
    override fun embedding(recordId: String, modelId: String): PersistedMemoryEmbedding? =
        vectorIndex.fetch(DOMAIN_MEMORY, recordId, modelId)?.toPersisted()
            ?: mirrorStore?.embedding(recordId, modelId)

    override fun upsert(embedding: PersistedMemoryEmbedding) {
        vectorIndex.upsert(embedding.toVectorRecord())
        mirrorStore?.upsert(embedding)
    }

    override fun delete(recordId: String) {
        vectorIndex.delete(DOMAIN_MEMORY, recordId)
        mirrorStore?.delete(recordId)
    }

    override fun deleteForModel(modelId: String) {
        vectorIndex.deleteForModel(modelId, domain = DOMAIN_MEMORY)
        mirrorStore?.deleteForModel(modelId)
    }

    override fun clear() {
        vectorIndex.clear(domain = DOMAIN_MEMORY)
        mirrorStore?.clear()
    }

    private fun PersistedMemoryEmbedding.toVectorRecord(): LocalVectorRecord =
        LocalVectorRecord(
            domain = DOMAIN_MEMORY,
            id = recordId,
            modelId = modelId,
            sourceHash = sourceHash,
            dimension = dimension,
            privacy = MessagePrivacy.LocalOnly.name,
            type = "MemoryEmbedding",
            updatedAtMillis = updatedAtMillis,
            embedding = vector,
        )

    private fun LocalVectorRecord.toPersisted(): PersistedMemoryEmbedding =
        PersistedMemoryEmbedding(
            recordId = id,
            modelId = modelId,
            sourceHash = sourceHash,
            dimension = dimension,
            vector = embedding,
            updatedAtMillis = updatedAtMillis,
        )
}

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (isNull(name)) null else optLong(name)

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

private const val DOMAIN_MEMORY = "memory"
private const val DOMAIN_MEMORY_DELETION_EVENT = "memory_deletion_event"
