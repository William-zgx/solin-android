package com.bytedance.zgx.pocketmind.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStorageKernelTest {
    @Test
    fun inMemoryKernelReportsOpenAndClosedHealth() {
        val kernel = InMemoryLocalStorageKernel()

        assertFalse(kernel.healthCheck().isReady)
        assertTrue(kernel.open().isReady)

        kernel.close()

        assertFalse(kernel.healthCheck().isReady)
    }

    @Test
    fun collectionNamesStayPinnedForZvecExperiment() {
        assertEquals("pm_kv_v1", LocalStorageCollections.KEY_VALUE)
        assertEquals("pm_docs_v1", LocalStorageCollections.DOCUMENTS)
        assertEquals("pm_vectors_v1", LocalStorageCollections.VECTORS)
        assertEquals(768, LocalStorageCollections.EMBEDDING_DIMENSION)
    }
}
