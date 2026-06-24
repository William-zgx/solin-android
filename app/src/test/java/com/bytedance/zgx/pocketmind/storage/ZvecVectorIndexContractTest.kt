package com.bytedance.zgx.pocketmind.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class ZvecVectorIndexContractTest {
    @Test(expected = IllegalArgumentException::class)
    fun vectorRecordsRequirePinnedEmbeddingDimension() {
        LocalVectorRecord(
            domain = "memory",
            id = "pref-1",
            modelId = "gemma-embedding",
            sourceHash = "hash",
            dimension = 3,
            privacy = "LocalOnly",
            type = "Preference",
            updatedAtMillis = 1L,
            embedding = floatArrayOf(1f, 0f, 0f),
        )
    }

    @Test
    fun vectorIndexReturnsHighestCosineMatchAndDeletesByModel() {
        val index = InMemoryLocalVectorIndex()
        index.upsert(record("pref-brief", 0, 1f))
        index.upsert(record("pref-language", 1, 1f))
        index.upsert(record("session-brief", 0, 1f, domain = "session"))

        val hits = index.query(
            LocalVectorQuery(
                domain = "memory",
                modelId = "gemma-embedding",
                embedding = vector(0, 1f),
                topK = 1,
            ),
        )

        assertEquals(listOf("pref-brief"), hits.map { it.record.id })
        assertEquals(2, index.deleteForModel("gemma-embedding", domain = "memory"))
        assertEquals("session-brief", index.query(LocalVectorQuery(domain = "session", embedding = vector(0, 1f))).single().record.id)
        assertEquals(
            emptyList<LocalVectorHit>(),
            index.query(LocalVectorQuery(domain = "memory", embedding = vector(0, 1f))),
        )
    }

    @Test
    fun vectorIndexUsesCosineAndDefensiveCopiesEmbeddings() {
        val index = InMemoryLocalVectorIndex()
        val stored = vector(0, 1f, secondIndex = 1, secondValue = 10f)
        index.upsert(record("large-magnitude-bad-angle", 0, 1f, secondIndex = 1, secondValue = 10f, embedding = stored))
        index.upsert(record("best-angle", 0, 1f))
        stored[0] = 0f

        val firstHit = index.query(LocalVectorQuery(embedding = vector(0, 1f), topK = 1)).single()
        firstHit.record.embedding[0] = 0f

        assertEquals("best-angle", firstHit.record.id)
        val storedValue = index.fetch("memory", "large-magnitude-bad-angle", "gemma-embedding")!!.embedding[0]
        assertEquals(1f, storedValue, 0.0001f)
    }

    private fun record(
        id: String,
        index: Int,
        value: Float,
        domain: String = "memory",
        secondIndex: Int? = null,
        secondValue: Float = 0f,
        embedding: FloatArray = vector(index, value, secondIndex, secondValue),
    ): LocalVectorRecord =
        LocalVectorRecord(
            domain = domain,
            id = id,
            modelId = "gemma-embedding",
            sourceHash = "hash-$id",
            dimension = LocalStorageCollections.EMBEDDING_DIMENSION,
            privacy = "LocalOnly",
            type = "Preference",
            updatedAtMillis = 1L,
            embedding = embedding,
        )

    private fun vector(
        index: Int,
        value: Float,
        secondIndex: Int? = null,
        secondValue: Float = 0f,
    ): FloatArray =
        FloatArray(LocalStorageCollections.EMBEDDING_DIMENSION).also {
            it[index] = value
            if (secondIndex != null) it[secondIndex] = secondValue
        }
}
