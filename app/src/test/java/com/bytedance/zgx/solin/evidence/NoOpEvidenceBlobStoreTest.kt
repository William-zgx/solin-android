package com.bytedance.zgx.solin.evidence

import com.bytedance.zgx.solin.MessagePrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoOpEvidenceBlobStoreTest {

    @Test
    fun `putText returns a dummy ref without throwing`() {
        val ref = NoOpEvidenceBlobStore.putText(
            "anything",
            EvidenceSourceType.Memory,
            MessagePrivacy.LocalOnly,
        )
        assertNotNull(ref)
        assertTrue(ref.uri.startsWith("ev://"))
        assertEquals(64, ref.sha256.length)
    }

    @Test
    fun `readText returns empty TextWindow with truncated true`() {
        val ref = NoOpEvidenceBlobStore.putText("x", EvidenceSourceType.Memory, MessagePrivacy.LocalOnly)
        val window = NoOpEvidenceBlobStore.readText(ref)
        assertEquals("", window.text)
        assertEquals(true, window.truncated)
        assertEquals(0, window.totalChars)
    }

    @Test
    fun `readBytes returns null`() {
        val ref = NoOpEvidenceBlobStore.putText("x", EvidenceSourceType.Memory, MessagePrivacy.LocalOnly)
        assertNull(NoOpEvidenceBlobStore.readBytes(ref))
    }

    @Test
    fun `gc and clear do not throw`() {
        NoOpEvidenceBlobStore.gc()
        NoOpEvidenceBlobStore.clear()
    }
}
