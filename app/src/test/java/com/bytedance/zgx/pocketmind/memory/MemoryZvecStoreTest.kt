package com.bytedance.zgx.pocketmind.memory

import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.storage.ExperimentalLocalStorageFake
import com.bytedance.zgx.pocketmind.storage.FakeLocalDocumentStore
import com.bytedance.zgx.pocketmind.storage.FakeLocalVectorIndex
import com.bytedance.zgx.pocketmind.storage.LocalStorageReadScope
import com.bytedance.zgx.pocketmind.storage.LocalVectorIndexContract
import com.bytedance.zgx.pocketmind.storage.LocalVectorQuery
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalLocalStorageFake::class)
class MemoryZvecStoreTest {
    @Test
    fun zvecMemoryRecordStoreRoundTripsLongTermRecordThroughLocalDocuments() {
        val documents = FakeLocalDocumentStore()
        val store = ZvecMemoryRecordStore(
            documents = documents,
            clockMillis = { 1234L },
        )
        val record = PersistedMemoryRecord(
            id = "pref-1",
            type = MemoryRecordType.Preference,
            text = "用户偏好：回答尽量简洁",
            source = MemoryRecordSource.ExplicitUser,
            sensitivity = MemoryRecordSensitivity.Normal,
            privacy = MessagePrivacy.LocalOnly,
            expiresAtMillis = 9999L,
            conflictKey = "response-length",
        )

        store.upsert(record)

        assertEquals(listOf(record), store.records())
        val document = requireNotNull(documents.get("pref-1"))
        assertEquals("memory", document.domain)
        assertEquals("用户偏好：回答尽量简洁", document.text)
        assertEquals(MessagePrivacy.LocalOnly, document.privacy)
        assertEquals(1234L, document.updatedAtMillis)
    }

    @Test
    fun zvecMemoryEmbeddingStoreRoundTripsEmbeddingThroughLocalVectors() {
        val documents = FakeLocalDocumentStore()
        val vectors = FakeLocalVectorIndex()
        val store = ZvecMemoryEmbeddingStore(
            documents = documents,
            vectors = vectors,
        )
        val vector = axisVector(0)
        val embedding = PersistedMemoryEmbedding(
            recordId = "pref-1",
            modelId = "embeddinggemma-300m",
            sourceHash = "sha256:pref-1",
            dimension = LocalVectorIndexContract.DIMENSIONS,
            vector = vector,
            updatedAtMillis = 2345L,
        )

        store.upsert(embedding)

        val restored = requireNotNull(store.embedding("pref-1", "embeddinggemma-300m"))
        assertEquals("pref-1", restored.recordId)
        assertEquals("embeddinggemma-300m", restored.modelId)
        assertEquals("sha256:pref-1", restored.sourceHash)
        assertEquals(LocalVectorIndexContract.DIMENSIONS, restored.dimension)
        assertEquals(2345L, restored.updatedAtMillis)
        assertArrayEquals(vector, restored.vector, 0.0f)
        assertEquals(1, documents.list().size)
    }

    @Test
    fun memoryRepositoryDeleteRemovesZvecDocumentAndVector() {
        val documents = FakeLocalDocumentStore()
        val vectors = FakeLocalVectorIndex()
        val recordStore = ZvecMemoryRecordStore(documents = documents)
        val embeddingStore = ZvecMemoryEmbeddingStore(
            documents = documents,
            vectors = vectors,
        )
        val repository = MemoryRepository(
            recordStore = recordStore,
            embeddingStore = embeddingStore,
            semanticRuntimeFactory = { ZvecTestEmbeddingRuntime() },
        )
        repository.indexPreference("pref-1", "I prefer concise answers")
        repository.useMemoryModel("/verified/memory.tflite")

        assertNotNull(documents.get("pref-1"))
        assertNotNull(embeddingStore.embedding("pref-1", "zvec-test-model"))

        assertTrue(repository.forget("pref-1"))

        assertNull(documents.get("pref-1"))
        assertTrue(documents.list().isEmpty())
        assertNull(embeddingStore.embedding("pref-1", "zvec-test-model"))
    }

    @Test
    fun zvecMemoryStoresKeepLocalOnlyRecordsOutOfRemoteSendScope() {
        val documents = FakeLocalDocumentStore()
        val vectors = FakeLocalVectorIndex()
        ZvecMemoryRecordStore(documents = documents).upsert(
            PersistedMemoryRecord(
                id = "fact-1",
                type = MemoryRecordType.UserFact,
                text = "用户事实：my rcode is xb83",
                source = MemoryRecordSource.ExplicitUser,
                sensitivity = MemoryRecordSensitivity.Sensitive,
                privacy = MessagePrivacy.LocalOnly,
            ),
        )
        ZvecMemoryEmbeddingStore(
            documents = documents,
            vectors = vectors,
        ).upsert(
            PersistedMemoryEmbedding(
                recordId = "fact-1",
                modelId = "embeddinggemma-300m",
                sourceHash = "sha256:fact-1",
                dimension = LocalVectorIndexContract.DIMENSIONS,
                vector = axisVector(0),
                updatedAtMillis = 3456L,
            ),
        )

        assertNotNull(documents.get("fact-1", readScope = LocalStorageReadScope.LocalContext))
        assertNull(documents.get("fact-1", readScope = LocalStorageReadScope.RemoteSend))
        assertTrue(documents.list(LocalStorageReadScope.RemoteSend).isEmpty())
        assertTrue(
            vectors.search(
                LocalVectorQuery(
                    text = "rcode xb83",
                    vector = axisVector(0),
                    readScope = LocalStorageReadScope.RemoteSend,
                ),
            ).isEmpty(),
        )
    }

    private class ZvecTestEmbeddingRuntime : EmbeddingRuntime {
        override val modelId: String = "zvec-test-model"
        override val dimension: Int = LocalVectorIndexContract.DIMENSIONS
        override val supportsSemanticRecall: Boolean = true
        override val semanticScoreThreshold: Float = 0.9f

        override fun embed(text: String): FloatArray {
            val lower = text.lowercase()
            return when {
                "concise" in lower -> axisVector(0)
                "compressed" in lower -> axisVector(0)
                else -> axisVector(1)
            }
        }
    }

    private companion object {
        fun axisVector(index: Int): FloatArray =
            FloatArray(LocalVectorIndexContract.DIMENSIONS).also { it[index] = 1f }
    }
}
