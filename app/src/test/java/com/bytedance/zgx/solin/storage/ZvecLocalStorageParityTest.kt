package com.bytedance.zgx.solin.storage

import com.bytedance.zgx.solin.MessagePrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZvecLocalStorageParityTest {
    @Test
    fun documentStoreMatchesFakeContract() {
        val fake = exerciseDocuments(FakeLocalStorageKernel().open().documents)
        val zvec = exerciseDocuments(zvecKernel().open().documents)

        assertEquals(fake, zvec)
    }

    @Test
    fun keyValueStoreMatchesFakeContract() {
        val fake = exerciseKeyValues(FakeLocalStorageKernel().open().keyValues)
        val zvec = exerciseKeyValues(zvecKernel().open().keyValues)

        assertEquals(fake, zvec)
    }

    @Test
    fun vectorIndexMatchesFakeContract() {
        val fake = exerciseVectors(FakeLocalStorageKernel().open().vectors)
        val zvec = exerciseVectors(zvecKernel().open().vectors)

        assertEquals(fake, zvec)
    }

    @Test
    fun remoteSendFilteringMatchesFakeContract() {
        val fake = exerciseRemoteSendFiltering(FakeLocalStorageKernel().open())
        val zvec = exerciseRemoteSendFiltering(zvecKernel().open())

        assertEquals(
            RemoteSendSnapshot(
                documentIds = listOf("remote-doc"),
                keyValueKeys = listOf("remote"),
                vectorIds = listOf("remote-vector"),
            ),
            zvec,
        )
        assertEquals(fake, zvec)
    }

    @Test
    fun kernelHealthTracksOpenFlushAndCloseThroughAdapter() {
        val kernel = zvecKernel(clockMillis = tickingClock(10L, 20L))

        assertEquals(
            LocalStorageHealth(
                opened = false,
                healthy = true,
                collectionNames = LocalStorageCollections.all,
                lastFlushedAtMillis = null,
            ),
            kernel.health(),
        )

        kernel.open()
        kernel.flush()

        assertEquals(
            LocalStorageHealth(
                opened = true,
                healthy = true,
                collectionNames = LocalStorageCollections.all,
                lastFlushedAtMillis = 10L,
            ),
            kernel.health(),
        )

        kernel.close()

        assertEquals(false, kernel.health().opened)
        assertEquals(10L, kernel.health().lastFlushedAtMillis)
    }

    private fun exerciseDocuments(store: LocalDocumentStore): DocumentSnapshot {
        val first = localDocument("doc-1", title = "First", privacy = MessagePrivacy.LocalOnly)
        val remote = localDocument("doc-2", title = "Remote", privacy = MessagePrivacy.RemoteEligible)
        val updated = first.copy(title = "First updated", updatedAtMillis = 3L)

        store.upsert(first)
        store.upsert(remote)
        store.upsert(updated)

        val beforeDelete = store.get("doc-1")
        val localList = store.list().map { it.id to it.title }
        val remoteList = store.list(LocalStorageReadScope.RemoteSend).map { it.id }
        val deleted = store.delete("doc-1")
        val missingDeleted = store.delete("doc-1")

        return DocumentSnapshot(
            fetched = beforeDelete,
            localList = localList,
            remoteList = remoteList,
            deleted = deleted,
            missingDeleted = missingDeleted,
            afterDelete = store.get("doc-1"),
        )
    }

    private fun exerciseKeyValues(store: LocalKeyValueStore): KeyValueSnapshot {
        val first = keyValue("prefs", "theme", """{"value":"light"}""", MessagePrivacy.LocalOnly)
        val remote = keyValue("prefs", "sync", """{"enabled":true}""", MessagePrivacy.RemoteEligible)
        val otherNamespace = keyValue("other", "theme", """{"value":"dark"}""", MessagePrivacy.RemoteEligible)
        val updated = first.copy(valueJson = """{"value":"system"}""", updatedAtMillis = 4L)

        store.upsert(first)
        store.upsert(remote)
        store.upsert(otherNamespace)
        store.upsert(updated)

        val beforeDelete = store.get("prefs", "theme")
        val prefsList = store.list("prefs").map { it.namespace to it.key to it.valueJson }
        val remoteList = store.list("prefs", LocalStorageReadScope.RemoteSend).map { it.key }
        val otherList = store.list("other").map { it.key }
        val deleted = store.delete("prefs", "theme")
        val missingDeleted = store.delete("prefs", "theme")

        return KeyValueSnapshot(
            fetched = beforeDelete,
            prefsList = prefsList,
            remoteList = remoteList,
            otherList = otherList,
            deleted = deleted,
            missingDeleted = missingDeleted,
            afterDelete = store.get("prefs", "theme"),
        )
    }

    private fun exerciseVectors(index: LocalVectorIndex): VectorSnapshot {
        val mutableVector = axisVector(0)
        val semanticOnly = vectorRecord("semantic-only", "calendar reminder", mutableVector)
        val lexicalOnly = vectorRecord("lexical-only", "zvec storage", axisVector(1))
        val hybrid = vectorRecord("hybrid", "zvec storage foundation", weightedVector(0.8f, 0.6f))

        index.upsert(semanticOnly)
        mutableVector[0] = 0f
        index.upsert(lexicalOnly)
        index.upsert(hybrid)

        val stored = index.get("semantic-only")
        assertEquals(1f, stored?.vector?.get(0))
        stored?.vector?.set(0, 0f)
        assertEquals(1f, index.get("semantic-only")?.vector?.get(0))

        val hits = index.search(
            LocalVectorQuery(
                text = "zvec storage",
                vector = axisVector(0),
                topK = 3,
            ),
        )
        val deleted = index.delete("lexical-only")
        val missingDeleted = index.delete("lexical-only")

        return VectorSnapshot(
            dimensions = index.dimensions,
            fetched = index.get("semantic-only"),
            hitIds = hits.map { it.record.id },
            hitScores = hits.map { rounded(it.score) },
            deleted = deleted,
            missingDeleted = missingDeleted,
            afterDelete = index.get("lexical-only"),
        )
    }

    private fun exerciseRemoteSendFiltering(session: LocalStorageSession): RemoteSendSnapshot {
        session.documents.upsert(localDocument("local-doc", "Private", MessagePrivacy.LocalOnly))
        session.documents.upsert(localDocument("remote-doc", "Public", MessagePrivacy.RemoteEligible))
        session.keyValues.upsert(keyValue("prefs", "local", """{"secret":true}""", MessagePrivacy.LocalOnly))
        session.keyValues.upsert(keyValue("prefs", "remote", """{"safe":true}""", MessagePrivacy.RemoteEligible))
        session.vectors.upsert(
            vectorRecord(
                id = "local-vector",
                text = "passport private",
                vector = axisVector(0),
                privacy = MessagePrivacy.LocalOnly,
            ),
        )
        session.vectors.upsert(
            vectorRecord(
                id = "remote-vector",
                text = "release public",
                vector = axisVector(0),
                privacy = MessagePrivacy.RemoteEligible,
            ),
        )

        assertNull(session.documents.get("local-doc", LocalStorageReadScope.RemoteSend))
        assertTrue(session.keyValues.get("prefs", "local", LocalStorageReadScope.RemoteSend) == null)
        assertNull(session.vectors.get("local-vector", LocalStorageReadScope.RemoteSend))

        return RemoteSendSnapshot(
            documentIds = session.documents.list(LocalStorageReadScope.RemoteSend).map { it.id },
            keyValueKeys = session.keyValues.list("prefs", LocalStorageReadScope.RemoteSend).map { it.key },
            vectorIds = session.vectors.search(
                LocalVectorQuery(
                    text = "public private",
                    vector = axisVector(0),
                    readScope = LocalStorageReadScope.RemoteSend,
                ),
            ).map { it.record.id },
        )
    }

    private fun zvecKernel(clockMillis: () -> Long = tickingClock(1L)): LocalStorageKernel =
        ZvecLocalStorageKernel(
            adapter = FakeZvecNativeAdapter(),
            clockMillis = clockMillis,
        )

    private fun localDocument(
        id: String,
        title: String,
        privacy: MessagePrivacy,
    ): LocalDocument =
        LocalDocument(
            domain = "memory",
            id = id,
            ownerId = "owner",
            title = title,
            text = "$title text",
            searchText = "$title text",
            privacy = privacy,
            updatedAtMillis = 1L,
        )

    private fun keyValue(
        namespace: String,
        key: String,
        valueJson: String,
        privacy: MessagePrivacy,
    ): LocalKeyValueEntry =
        LocalKeyValueEntry(
            namespace = namespace,
            key = key,
            valueJson = valueJson,
            privacy = privacy,
            updatedAtMillis = 2L,
        )

    private fun vectorRecord(
        id: String,
        text: String,
        vector: FloatArray,
        privacy: MessagePrivacy = MessagePrivacy.LocalOnly,
    ): LocalVectorRecord =
        LocalVectorRecord(
            domain = "memory",
            id = id,
            modelId = "embeddinggemma-300m",
            sourceHash = "sha256:$id",
            type = "long_term_memory",
            documentId = "doc-$id",
            text = text,
            vector = vector,
            privacy = privacy,
            updatedAtMillis = 3L,
        )

    private fun axisVector(index: Int): FloatArray =
        FloatArray(LocalVectorIndexContract.DIMENSIONS).also { it[index] = 1f }

    private fun weightedVector(first: Float, second: Float): FloatArray =
        FloatArray(LocalVectorIndexContract.DIMENSIONS).also {
            it[0] = first
            it[1] = second
        }

    private fun rounded(value: Float): Int =
        (value * 10_000).toInt()

    private fun tickingClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }

    private data class DocumentSnapshot(
        val fetched: LocalDocument?,
        val localList: List<Pair<String, String>>,
        val remoteList: List<String>,
        val deleted: Boolean,
        val missingDeleted: Boolean,
        val afterDelete: LocalDocument?,
    )

    private data class KeyValueSnapshot(
        val fetched: LocalKeyValueEntry?,
        val prefsList: List<Pair<Pair<String, String>, String>>,
        val remoteList: List<String>,
        val otherList: List<String>,
        val deleted: Boolean,
        val missingDeleted: Boolean,
        val afterDelete: LocalKeyValueEntry?,
    )

    private data class VectorSnapshot(
        val dimensions: Int,
        val fetched: LocalVectorRecord?,
        val hitIds: List<String>,
        val hitScores: List<Int>,
        val deleted: Boolean,
        val missingDeleted: Boolean,
        val afterDelete: LocalVectorRecord?,
    )

    private data class RemoteSendSnapshot(
        val documentIds: List<String>,
        val keyValueKeys: List<String>,
        val vectorIds: List<String>,
    )
}
