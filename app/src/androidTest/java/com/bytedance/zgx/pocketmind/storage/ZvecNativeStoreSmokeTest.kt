package com.bytedance.zgx.pocketmind.storage

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ZvecNativeStoreSmokeTest {
    @Test
    fun nativeStoreRoundTripsVectorsThroughZvec() {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "zvec-native-smoke",
        )
        root.deleteRecursively()

        val first = ZvecNativeStore.Record(
            id = "memory-alpha",
            documentId = "doc-alpha",
            text = "alpha zvec native memory",
            vector = axisVector(0),
            metadataJson = """{"kind":"alpha"}""",
        )
        val second = ZvecNativeStore.Record(
            id = "memory-beta",
            documentId = "doc-beta",
            text = "beta zvec native memory",
            vector = axisVector(1),
            metadataJson = """{"kind":"beta"}""",
        )

        val store = ZvecNativeStore.create(root.absolutePath)
        try {
            assertEquals(0, store.lastErrorCode())
            assertTrue(store.upsert(first))
            assertTrue(store.upsert(second))
            assertTrue(store.flush())

            val fetchedFirst = store.fetch(first.id)
            assertNotNull(fetchedFirst)
            assertRecordEquals(first, fetchedFirst)

            val hits = store.query(first.vector, topK = 2)
            assertEquals(first.id, hits.first().record.id)
            assertTrue(hits.first().score <= hits.last().score)

            assertTrue(store.delete(first.id))
            assertNull(store.fetch(first.id))
        } finally {
            store.close()
        }

        val reopened = ZvecNativeStore.open(root.absolutePath)
        try {
            val fetchedSecond = reopened.fetch(second.id)
            assertNotNull(fetchedSecond)
            assertRecordEquals(second, fetchedSecond)
        } finally {
            reopened.close()
            root.deleteRecursively()
        }
    }

    private fun axisVector(index: Int): FloatArray =
        FloatArray(LocalVectorIndexContract.DIMENSIONS).also { it[index] = 1f }

    private fun assertRecordEquals(
        expected: ZvecNativeStore.Record,
        actual: ZvecNativeStore.Record?,
    ) {
        assertNotNull(actual)
        checkNotNull(actual)
        assertEquals(expected.id, actual.id)
        assertEquals(expected.documentId, actual.documentId)
        assertEquals(expected.text, actual.text)
        assertEquals(expected.metadataJson, actual.metadataJson)
        assertArrayEquals(expected.vector, actual.vector, 0.0001f)
    }
}
