package com.bytedance.zgx.pocketmind.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZvecDocumentStoreFakeTest {
    @Test
    fun keyValueStoreIsNamespaced() {
        val store = InMemoryLocalKeyValueStore()

        store.put(LocalKeyValueEntry("settings", "backend", "\"GPU\"", 1L))
        store.put(LocalKeyValueEntry("session", "backend", "\"local\"", 2L))

        assertEquals("\"GPU\"", store.get("settings", "backend")?.valueJson)
        assertEquals(listOf("backend"), store.entries("session").map { it.key })
    }

    @Test
    fun documentStoreQueriesAndSoftDeletesByDomain() {
        val store = InMemoryLocalDocumentStore()
        store.upsert(
            document(
                domain = "memory",
                id = "pref-1",
                type = "Preference",
                searchText = "prefer brief replies",
                updatedAtMillis = 10L,
            ),
        )
        store.upsert(
            document(
                domain = "session",
                id = "msg-1",
                type = "Message",
                searchText = "brief meeting note",
                updatedAtMillis = 20L,
            ),
        )

        val hits = store.query(LocalDocumentQuery(domain = "memory", textQuery = "brief"))

        assertEquals(listOf("pref-1"), hits.map { it.id })

        assertTrue(store.delete("memory", "pref-1", deletedAtMillis = 30L))
        assertNull(store.fetch("memory", "pref-1"))
        assertEquals(emptyList<LocalDocument>(), store.query(LocalDocumentQuery(domain = "memory")))
        assertEquals(
            listOf("pref-1"),
            store.query(LocalDocumentQuery(domain = "memory", includeDeleted = true)).map { it.id },
        )
    }

    private fun document(
        domain: String,
        id: String,
        type: String,
        searchText: String,
        updatedAtMillis: Long,
    ): LocalDocument =
        LocalDocument(
            domain = domain,
            id = id,
            ownerId = null,
            privacy = "LocalOnly",
            sensitivity = "Normal",
            type = type,
            sourceHash = "hash-$id",
            payloadJson = "{}",
            searchText = searchText,
            createdAtMillis = 1L,
            updatedAtMillis = updatedAtMillis,
        )
}
