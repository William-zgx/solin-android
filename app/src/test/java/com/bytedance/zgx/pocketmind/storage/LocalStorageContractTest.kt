package com.bytedance.zgx.pocketmind.storage

import com.bytedance.zgx.pocketmind.MessagePrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalLocalStorageFake::class)
class LocalStorageContractTest {
    @Test
    fun kernelOpensExpectedCollectionsAndTracksFlushAndClose() {
        val kernel = FakeLocalStorageKernel(clockMillis = tickingClock(100L))

        assertFalse(kernel.health().opened)

        val session = kernel.open()
        kernel.flush()

        assertTrue(kernel.health().opened)
        assertEquals("pm_docs_v1", session.documents.collectionName)
        assertEquals("pm_kv_v1", session.keyValues.collectionName)
        assertEquals("pm_vectors_v1", session.vectors.collectionName)
        assertEquals(
            setOf("pm_kv_v1", "pm_docs_v1", "pm_vectors_v1"),
            kernel.health().collectionNames,
        )
        assertEquals(100L, kernel.health().lastFlushedAtMillis)

        kernel.close()

        assertFalse(kernel.health().opened)
        assertTrue(kernel.health().healthy)
    }

    @Test
    fun contractsExposePinnedZvecSchemaShape() {
        val document = LocalDocument(
            domain = "memory",
            id = "doc-1",
            ownerId = "owner",
            title = "Storage note",
            text = "zvec storage experiment",
            sensitivity = "normal",
            type = "long_term_memory",
            sourceHash = "sha256:doc",
            payloadJson = """{"id":"doc-1"}""",
            searchText = "storage experiment",
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
            expiresAtMillis = 3L,
            deletedAtMillis = null,
        )
        val keyValue = LocalKeyValueEntry(
            namespace = "settings",
            key = "memory",
            valueJson = """{"enabled":true}""",
            updatedAtMillis = 4L,
        )
        val vector = LocalVectorRecord(
            domain = "memory",
            id = "vector-1",
            modelId = "embeddinggemma-300m",
            sourceHash = "sha256:doc",
            dimension = LocalVectorIndexContract.DIMENSIONS,
            type = "long_term_memory",
            documentId = document.id,
            text = document.searchText,
            vector = axisVector(0),
            updatedAtMillis = 5L,
        )

        assertEquals(LocalStorageSchemaVersions.CURRENT, document.schemaVersion)
        assertEquals(LocalStorageSchemaVersions.CURRENT, keyValue.schemaVersion)
        assertEquals(LocalVectorIndexContract.DIMENSIONS, vector.dimension)
        assertEquals("memory", document.domain)
        assertEquals("memory", vector.domain)
        assertEquals("pm_docs_v1", FakeLocalDocumentStore().collectionName)
        assertEquals("pm_kv_v1", FakeLocalKeyValueStore().collectionName)
        assertEquals("pm_vectors_v1", FakeLocalVectorIndex().collectionName)
    }

    @Test
    fun documentStoreUpsertsFetchesAndDeletesDocuments() {
        val store = FakeLocalDocumentStore()
        val document = LocalDocument(
            id = "doc-1",
            title = "Storage note",
            text = "zvec storage experiment",
            metadataJson = """{"source":"test"}""",
            privacy = MessagePrivacy.LocalOnly,
            updatedAtMillis = 1L,
        )

        store.upsert(document)

        assertEquals(document, store.get("doc-1"))
        assertEquals(listOf(document), store.list())
        assertTrue(store.delete("doc-1"))
        assertNull(store.get("doc-1"))
        assertFalse(store.delete("doc-1"))
    }

    @Test
    fun keyValueStoreUpsertsFetchesAndDeletesJsonValuesByNamespaceAndKey() {
        val store = FakeLocalKeyValueStore()
        val entry = LocalKeyValueEntry(
            namespace = "settings",
            key = "memory",
            valueJson = """{"enabled":true}""",
            privacy = MessagePrivacy.RemoteEligible,
            updatedAtMillis = 2L,
        )

        store.upsert(entry)

        assertEquals(entry, store.get(namespace = "settings", key = "memory"))
        assertEquals(listOf(entry), store.list(namespace = "settings"))
        assertTrue(store.delete(namespace = "settings", key = "memory"))
        assertNull(store.get(namespace = "settings", key = "memory"))
        assertFalse(store.delete(namespace = "settings", key = "memory"))
    }

    @Test
    fun vectorIndexRejectsNon768DimensionVectorsForUpsertAndSearch() {
        val index = FakeLocalVectorIndex()
        val badVector = FloatArray(LocalVectorIndexContract.DIMENSIONS - 1)

        assertFailsWithMessage("768") {
            index.upsert(
                LocalVectorRecord(
                    id = "bad",
                    documentId = "doc",
                    text = "wrong dimension",
                    vector = badVector,
                ),
            )
        }

        assertFailsWithMessage("768") {
            index.search(
                LocalVectorQuery(
                    text = "wrong dimension",
                    vector = badVector,
                ),
            )
        }
    }

    @Test
    fun vectorRecordEqualityUsesVectorContents() {
        val left = vectorRecord(id = "same", text = "same text", vector = axisVector(0))
        val right = vectorRecord(id = "same", text = "same text", vector = axisVector(0))

        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
    }

    @Test
    fun vectorSearchRanksHybridCosineAndLexicalMatchesAheadOfSingleSignalMatches() {
        val index = FakeLocalVectorIndex()
        index.upsert(vectorRecord(id = "semantic-only", text = "calendar reminder", vector = axisVector(0)))
        index.upsert(vectorRecord(id = "lexical-only", text = "zvec storage", vector = axisVector(1)))
        index.upsert(vectorRecord(id = "hybrid", text = "zvec storage foundation", vector = weightedVector(0.8f, 0.6f)))

        val hits = index.search(
            LocalVectorQuery(
                text = "zvec storage",
                vector = axisVector(0),
                topK = 3,
            ),
        )

        assertEquals(listOf("hybrid", "semantic-only", "lexical-only"), hits.map { it.record.id })
        assertTrue(hits.first().cosineScore > 0f)
        assertTrue(hits.first().lexicalScore > 0f)
        assertTrue(hits.first().score > hits[1].score)
    }

    @Test
    fun remoteReadScopeFiltersLocalOnlyDocumentsKeyValuesAndVectors() {
        val session = FakeLocalStorageKernel().open()
        session.documents.upsert(
            LocalDocument(
                id = "local-doc",
                title = "Private",
                text = "private passport",
                privacy = MessagePrivacy.LocalOnly,
            ),
        )
        session.documents.upsert(
            LocalDocument(
                id = "remote-doc",
                title = "Public",
                text = "public release note",
                privacy = MessagePrivacy.RemoteEligible,
            ),
        )
        session.keyValues.upsert(
            LocalKeyValueEntry(
                namespace = "prefs",
                key = "local",
                valueJson = """{"secret":true}""",
                privacy = MessagePrivacy.LocalOnly,
            ),
        )
        session.keyValues.upsert(
            LocalKeyValueEntry(
                namespace = "prefs",
                key = "remote",
                valueJson = """{"safe":true}""",
                privacy = MessagePrivacy.RemoteEligible,
            ),
        )
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

        assertNotNull(session.documents.get("local-doc", readScope = LocalStorageReadScope.LocalContext))
        assertEquals(setOf("local-doc", "remote-doc"), session.documents.list().map { it.id }.toSet())
        assertEquals(setOf("local", "remote"), session.keyValues.list("prefs").map { it.key }.toSet())
        assertEquals(
            setOf("local-vector", "remote-vector"),
            session.vectors.search(LocalVectorQuery(text = "public private", vector = axisVector(0))).map { it.record.id }.toSet(),
        )

        assertNull(session.documents.get("local-doc", readScope = LocalStorageReadScope.RemoteSend))
        assertEquals(listOf("remote-doc"), session.documents.list(LocalStorageReadScope.RemoteSend).map { it.id })
        assertEquals(listOf("remote"), session.keyValues.list("prefs", LocalStorageReadScope.RemoteSend).map { it.key })
        assertEquals(
            listOf("remote-vector"),
            session.vectors.search(
                LocalVectorQuery(
                    text = "public private",
                    vector = axisVector(0),
                    readScope = LocalStorageReadScope.RemoteSend,
                ),
            ).map { it.record.id },
        )
    }

    private fun vectorRecord(
        id: String,
        text: String,
        vector: FloatArray,
        privacy: MessagePrivacy = MessagePrivacy.LocalOnly,
    ): LocalVectorRecord =
        LocalVectorRecord(
            id = id,
            documentId = "doc-$id",
            text = text,
            vector = vector,
            privacy = privacy,
        )

    private fun axisVector(index: Int): FloatArray =
        FloatArray(LocalVectorIndexContract.DIMENSIONS).also { it[index] = 1f }

    private fun weightedVector(first: Float, second: Float): FloatArray =
        FloatArray(LocalVectorIndexContract.DIMENSIONS).also {
            it[0] = first
            it[1] = second
        }

    private fun tickingClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }

    private fun assertFailsWithMessage(expectedMessagePart: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure?.message.orEmpty().contains(expectedMessagePart))
    }
}
