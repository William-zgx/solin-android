package com.bytedance.zgx.solin.memory

import com.bytedance.zgx.solin.storage.LocalDocument
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyMemoryStorageMigrationTest {
    @Test
    fun malformedSnapshotAbortsBeforeRoomWritesOrPlaintextDeletion() {
        val events = mutableListOf<String>()
        val records = RecordingRecordStore(emptyList(), events)

        assertThrows(JSONException::class.java) {
            retireLegacyMemoryStorage(
                legacyDocumentSnapshot = {
                    events += "snapshot"
                    throw JSONException("malformed")
                },
                records = records,
                deletedRecordIds = emptySet(),
                importEmbeddings = { _, _ -> events += "import-embeddings" },
                clearLegacyDocuments = { events += "clear-documents" },
                deleteLegacyVectors = { events += "delete-vectors" }
            )
        }

        assertEquals(listOf("snapshot"), events)
    }

    @Test
    fun verifiedRecordIdsFeedEmbeddingsBeforePlaintextDeletion() {
        val events = mutableListOf<String>()
        val records = RecordingRecordStore(
            initialRecords = listOf(persistedRecord(id = "existing", text = "same")),
            events = events
        )
        val documents = listOf(
            legacyRecordDocument(id = "existing", text = "same"),
            legacyRecordDocument(id = "new", text = "new text")
        )

        retireLegacyMemoryStorage(
            legacyDocumentSnapshot = { documents },
            records = records,
            deletedRecordIds = emptySet(),
            importEmbeddings = { snapshot, importedRecordIds ->
                assertEquals(documents, snapshot)
                assertEquals(setOf("existing", "new"), importedRecordIds)
                events += "import-embeddings"
            },
            clearLegacyDocuments = { events += "clear-documents" },
            deleteLegacyVectors = { events += "delete-vectors" }
        )

        assertEquals(
            listOf(
                "read-records",
                "upsert:new",
                "read-records",
                "import-embeddings",
                "clear-documents",
                "delete-vectors"
            ),
            events
        )
    }

    @Test
    fun embeddingImportFailureRetainsPlaintextForRetry() {
        val events = mutableListOf<String>()
        val records = RecordingRecordStore(emptyList(), events)

        assertThrows(IllegalStateException::class.java) {
            retireLegacyMemoryStorage(
                legacyDocumentSnapshot = { listOf(legacyRecordDocument(id = "new", text = "new text")) },
                records = records,
                deletedRecordIds = emptySet(),
                importEmbeddings = { _, _ ->
                    events += "import-embeddings"
                    throw IllegalStateException("vector verification failed")
                },
                clearLegacyDocuments = { events += "clear-documents" },
                deleteLegacyVectors = { events += "delete-vectors" }
            )
        }

        assertEquals(
            listOf("read-records", "upsert:new", "read-records", "import-embeddings"),
            events
        )
    }

    private fun legacyRecordDocument(id: String, text: String): LocalDocument = LocalDocument(
        domain = "memory",
        id = id,
        title = "UserFact",
        text = text,
        sensitivity = "Normal",
        type = "UserFact"
    )

    private fun persistedRecord(id: String, text: String): PersistedMemoryRecord = PersistedMemoryRecord(
        id = id,
        type = MemoryRecordType.UserFact,
        text = text
    )

    private class RecordingRecordStore(
        initialRecords: List<PersistedMemoryRecord>,
        private val events: MutableList<String>
    ) : MemoryRecordStore {
        private val stored = initialRecords.associateByTo(linkedMapOf()) { record -> record.id }

        override fun records(): List<PersistedMemoryRecord> {
            events += "read-records"
            return stored.values.toList()
        }

        override fun upsert(record: PersistedMemoryRecord) {
            events += "upsert:${record.id}"
            stored[record.id] = record
        }

        override fun delete(id: String): Boolean = stored.remove(id) != null

        override fun clear() = stored.clear()
    }
}
