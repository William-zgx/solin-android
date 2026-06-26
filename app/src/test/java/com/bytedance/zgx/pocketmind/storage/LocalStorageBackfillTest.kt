package com.bytedance.zgx.pocketmind.storage

import com.bytedance.zgx.pocketmind.MessagePrivacy
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalLocalStorageFake::class)
class LocalStorageBackfillTest {
    @Test
    fun roomMemoryBackfillResumesFromLastDomainAndLastIdWithoutDuplicateWrites() {
        val stateStore = LocalStorageMigrationStateStore(FakeLocalKeyValueStore())
        val vectors = RecordingVectorIndex()
        val source = InMemoryRoomMemoryBackfillSource(
            listOf(
                backfillRecord(domain = "memory", id = "001"),
                backfillRecord(domain = "memory", id = "002"),
                backfillRecord(domain = "memory", id = "003"),
                backfillRecord(domain = "memory", id = "004"),
            ),
        )

        LocalStorageRoomMemoryBackfill(
            source = source,
            vectors = vectors,
            stateStore = stateStore,
            clockMillis = tickingClock(10L, 20L, 30L),
        ).runBatch(limit = 2)

        assertEquals(listOf("memory:001", "memory:002"), vectors.upsertedKeys)
        assertEquals(
            LocalStorageBackfillState(
                status = LocalStorageBackfillStatus.InProgress,
                lastDomain = "memory",
                lastId = "002",
                updatedAtMillis = 20L,
            ),
            stateStore.load(),
        )

        LocalStorageRoomMemoryBackfill(
            source = source,
            vectors = vectors,
            stateStore = stateStore,
            clockMillis = tickingClock(40L, 50L, 60L),
        ).runBatch(limit = 10)

        assertEquals(
            listOf("memory:001", "memory:002", "memory:003", "memory:004"),
            vectors.upsertedKeys,
        )
        assertEquals(
            LocalStorageBackfillState(
                status = LocalStorageBackfillStatus.Completed,
                lastDomain = "memory",
                lastId = "004",
                updatedAtMillis = 60L,
            ),
            stateStore.load(),
        )

        LocalStorageRoomMemoryBackfill(
            source = source,
            vectors = vectors,
            stateStore = stateStore,
        ).runBatch(limit = 10)

        assertEquals(
            listOf("memory:001", "memory:002", "memory:003", "memory:004"),
            vectors.upsertedKeys,
        )
    }

    private class RecordingVectorIndex : LocalVectorIndex {
        val upsertedKeys = mutableListOf<String>()
        private val records = linkedMapOf<String, LocalVectorRecord>()

        override fun upsert(record: LocalVectorRecord): LocalVectorRecord {
            upsertedKeys += "${record.domain}:${record.id}"
            records[record.id] = record
            return record
        }

        override fun get(id: String, readScope: LocalStorageReadScope): LocalVectorRecord? =
            records[id]

        override fun delete(id: String): Boolean =
            records.remove(id) != null

        override fun search(query: LocalVectorQuery): List<LocalVectorHit> =
            emptyList()
    }

    private fun backfillRecord(domain: String, id: String): LocalStorageBackfillRecord =
        LocalStorageBackfillRecord(
            domain = domain,
            id = id,
            documentId = "doc-$id",
            text = "memory $id",
            vector = axisVector(id.last().digitToInt()),
            privacy = MessagePrivacy.LocalOnly,
            updatedAtMillis = id.toLong(),
        )

    private fun axisVector(index: Int): FloatArray =
        FloatArray(LocalVectorIndexContract.DIMENSIONS).also { it[index] = 1f }

    private fun tickingClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }
}
