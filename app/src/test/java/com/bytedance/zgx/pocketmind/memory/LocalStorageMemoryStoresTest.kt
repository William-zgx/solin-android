package com.bytedance.zgx.pocketmind.memory

import com.bytedance.zgx.pocketmind.storage.InMemoryLocalDocumentStore
import com.bytedance.zgx.pocketmind.storage.InMemoryLocalVectorIndex
import com.bytedance.zgx.pocketmind.storage.LocalDocument
import com.bytedance.zgx.pocketmind.storage.LocalDocumentQuery
import com.bytedance.zgx.pocketmind.storage.LocalStorageCollections
import com.bytedance.zgx.pocketmind.storage.LocalVectorRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStorageMemoryStoresTest {
    @Test
    fun memoryRepositoryCanRestoreRecordsFromLocalDocumentStore() {
        val documentStore = InMemoryLocalDocumentStore()
        val recordStore = LocalDocumentMemoryRecordStore(
            documentStore = documentStore,
            clockMillis = { 10L },
        )
        val repository = MemoryRepository(recordStore = recordStore)
        repository.indexPreference("pref-brief", "I prefer concise answers")

        val restarted = MemoryRepository(recordStore = recordStore)
        restarted.rebuild(emptyList())

        assertEquals(listOf("pref-brief"), restarted.search("brief replies").map { it.id })
        assertEquals("Preference", documentStore.fetch("memory", "pref-brief")?.type)
    }

    @Test
    fun deletionEventsStoreOnlyRedactedMetadataDocuments() {
        val documentStore = InMemoryLocalDocumentStore()
        val eventStore = LocalDocumentMemoryDeletionEventStore(
            documentStore = documentStore,
            clockMillis = { 20L },
        )

        eventStore.append(
            MemoryDeletionEvent(
                id = "deletion-1",
                recordId = "pref-brief",
                recordType = MemoryRecordType.Preference,
                operation = MemoryDeletionOperation.Forget,
                recordTextHash = "hash-only",
                recordSource = MemoryRecordSource.ExplicitUser,
                recordSensitivity = MemoryRecordSensitivity.Normal,
                conflictKey = "response-length",
                deletedAtMillis = 30L,
            ),
        )

        val document = documentStore.query(LocalDocumentQuery(domain = "memory_deletion_event")).single()
        assertTrue(document.payloadJson.contains("hash-only"))
        assertFalse(document.payloadJson.contains("I prefer concise answers"))
        assertEquals("pref-brief", eventStore.events().single().recordId)
    }

    @Test
    fun vectorEmbeddingStoreUsesLocalVectorIndexAsCanonicalIndex() {
        val vectorIndex = InMemoryLocalVectorIndex()
        val store = LocalVectorMemoryEmbeddingStore(vectorIndex)
        val embedding = PersistedMemoryEmbedding(
            recordId = "pref-brief",
            modelId = "gemma-embedding",
            sourceHash = "hash",
            dimension = LocalStorageCollections.EMBEDDING_DIMENSION,
            vector = vector(0, 1f),
            updatedAtMillis = 40L,
        )

        store.upsert(embedding)

        assertEquals("hash", store.embedding("pref-brief", "gemma-embedding")?.sourceHash)
        store.delete("pref-brief")
        assertEquals(null, store.embedding("pref-brief", "gemma-embedding"))
    }

    @Test
    fun vectorEmbeddingStoreClearsOnlyMemoryDomain() {
        val vectorIndex = InMemoryLocalVectorIndex()
        val store = LocalVectorMemoryEmbeddingStore(vectorIndex)
        store.upsert(
            PersistedMemoryEmbedding(
                recordId = "pref-brief",
                modelId = "gemma-embedding",
                sourceHash = "hash",
                dimension = LocalStorageCollections.EMBEDDING_DIMENSION,
                vector = vector(0, 1f),
                updatedAtMillis = 40L,
            ),
        )
        vectorIndex.upsert(
            LocalVectorRecord(
                domain = "session",
                id = "session-1",
                modelId = "gemma-embedding",
                sourceHash = "hash-session",
                dimension = LocalStorageCollections.EMBEDDING_DIMENSION,
                privacy = "LocalOnly",
                type = "Message",
                updatedAtMillis = 40L,
                embedding = vector(0, 1f),
            ),
        )

        store.clear()

        assertEquals(null, store.embedding("pref-brief", "gemma-embedding"))
        assertEquals("session-1", vectorIndex.fetch("session", "session-1", "gemma-embedding")?.id)
    }

    @Test
    fun deleteRecordAndAppendEventWritesAuditBeforeSoftDelete() {
        val documentStore = InMemoryLocalDocumentStore()
        val calls = mutableListOf<String>()
        val recordStore = RecordingMemoryRecordStore(calls) {
            assertEquals(
                listOf("deletion-1"),
                documentStore.query(LocalDocumentQuery(domain = "memory_deletion_event")).map { it.id },
            )
        }
        val eventStore = LocalDocumentMemoryDeletionEventStore(documentStore, clockMillis = { 2L })
        recordStore.upsert(
            PersistedMemoryRecord(
                id = "pref-brief",
                type = MemoryRecordType.Preference,
                text = "用户偏好：I prefer concise answers",
            ),
        )

        val deleted = eventStore.deleteRecordAndAppendEvent(
            recordStore = recordStore,
            id = "pref-brief",
            event = MemoryDeletionEvent(
                id = "deletion-1",
                recordId = "pref-brief",
                recordType = MemoryRecordType.Preference,
                operation = MemoryDeletionOperation.Forget,
                recordTextHash = "hash-only",
                recordSource = MemoryRecordSource.ExplicitUser,
                recordSensitivity = MemoryRecordSensitivity.Normal,
                deletedAtMillis = 3L,
            ),
        )

        assertTrue(deleted)
        assertEquals(listOf("records", "delete"), calls)
        assertEquals(listOf("deletion-1"), eventStore.events().map { it.id })
    }

    private fun vector(index: Int, value: Float): FloatArray =
        FloatArray(LocalStorageCollections.EMBEDDING_DIMENSION).also { it[index] = value }

    private class RecordingMemoryRecordStore(
        private val calls: MutableList<String>,
        private val onDelete: () -> Unit = {},
    ) : MemoryRecordStore {
        private val records = linkedMapOf<String, PersistedMemoryRecord>()

        override fun records(): List<PersistedMemoryRecord> {
            calls += "records"
            return records.values.toList()
        }

        override fun upsert(record: PersistedMemoryRecord) {
            records[record.id] = record
        }

        override fun delete(id: String): Boolean {
            calls += "delete"
            onDelete()
            return records.remove(id) != null
        }

        override fun clear() {
            calls += "clear"
            records.clear()
        }
    }
}
