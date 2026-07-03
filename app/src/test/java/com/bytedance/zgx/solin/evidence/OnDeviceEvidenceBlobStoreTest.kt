package com.bytedance.zgx.solin.evidence

import com.bytedance.zgx.solin.MessagePrivacy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnDeviceEvidenceBlobStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newStore(): OnDeviceEvidenceBlobStore {
        val localRoot = tempFolder.newFolder("evidence-local")
        val remoteRoot = tempFolder.newFolder("evidence-remote")
        return OnDeviceEvidenceBlobStore(localRoot, remoteRoot)
    }

    @Test
    fun `putText then readText round trips`() {
        val store = newStore()
        val text = "hello evidence blob"
        val ref = store.putText(
            text,
            EvidenceSourceType.ToolResult,
            MessagePrivacy.LocalOnly,
        )
        val window = store.readText(ref)
        assertEquals(text, window.text)
        assertEquals(0, window.offset)
        assertEquals(text.length, window.totalChars)
    }

    @Test
    fun `putText twice with same content returns same sha256`() {
        val store = newStore()
        val text = "content-addressed"
        val a = store.putText(text, EvidenceSourceType.ToolResult, MessagePrivacy.LocalOnly)
        val b = store.putText(text, EvidenceSourceType.ToolResult, MessagePrivacy.LocalOnly)
        assertEquals(a.sha256, b.sha256)
        assertEquals(a.uri, b.uri)
    }

    @Test
    fun `putText LocalOnly creates file in localRoot not remoteRoot`() {
        val localRoot = tempFolder.newFolder("evidence-local")
        val remoteRoot = tempFolder.newFolder("evidence-remote")
        val store = OnDeviceEvidenceBlobStore(localRoot, remoteRoot)
        val ref = store.putText("local-only-content", EvidenceSourceType.Memory, MessagePrivacy.LocalOnly)
        assertTrue(localRoot.resolve("${ref.sha256}.bin").exists())
        // remote bin file should not exist (the ref sha256 bin should not appear in remoteRoot)
        val remoteBin = remoteRoot.resolve("${ref.sha256}.bin")
        assertTrue(!remoteBin.exists())
    }

    @Test
    fun `putText RemoteEligible creates file in remoteRoot`() {
        val localRoot = tempFolder.newFolder("evidence-local")
        val remoteRoot = tempFolder.newFolder("evidence-remote")
        val store = OnDeviceEvidenceBlobStore(localRoot, remoteRoot)
        val ref = store.putText("remote-content", EvidenceSourceType.PublicWeb, MessagePrivacy.RemoteEligible)
        assertTrue(remoteRoot.resolve("${ref.sha256}.bin").exists())
        val localBin = localRoot.resolve("${ref.sha256}.bin")
        assertTrue(!localBin.exists())
    }

    @Test
    fun `headTailText returns head and tail and correct omitted for long text`() {
        val store = newStore()
        val longText = "A".repeat(1000)
        val ref = store.putText(longText, EvidenceSourceType.Memory, MessagePrivacy.LocalOnly)
        val result = store.headTailText(ref, headChars = 100, tailChars = 100)
        assertTrue(result.truncated)
        assertTrue(result.text.startsWith("A".repeat(100)))
        assertTrue(result.text.endsWith("A".repeat(100)))
        assertEquals(1000 - 100 - 100, result.omittedChars)
    }

    @Test
    fun `headTailText on short text returns full text omitted zero`() {
        val store = newStore()
        val short = "short"
        val ref = store.putText(short, EvidenceSourceType.Memory, MessagePrivacy.LocalOnly)
        val result = store.headTailText(ref, headChars = 100, tailChars = 100)
        assertEquals(short, result.text)
        assertEquals(0, result.omittedChars)
        assertEquals(false, result.truncated)
    }

    @Test
    fun `gc removes TTL expired blobs`() {
        val store = newStore()
        val ref = store.putText(
            "ephemeral",
            EvidenceSourceType.Memory,
            MessagePrivacy.LocalOnly,
            ttlMs = 1,
        )
        // Wait for TTL to pass
        Thread.sleep(50)
        assertNotNull("Blob should exist before gc", store.readBytes(ref))
        store.gc()
        assertNull("Blob should be evicted after gc", store.readBytes(ref))
    }

    @Test
    fun `readText with offset and limit returns correct window and truncated flag`() {
        val store = newStore()
        val text = "0123456789"
        val ref = store.putText(text, EvidenceSourceType.Memory, MessagePrivacy.LocalOnly)
        val window = store.readText(ref, offset = 2, limit = 3)
        assertEquals("234", window.text)
        assertEquals(2, window.offset)
        assertEquals(true, window.truncated)
        assertEquals(10, window.totalChars)

        val full = store.readText(ref, offset = 0, limit = 100)
        assertEquals(text, full.text)
        assertEquals(false, full.truncated)
    }

    @Test
    fun `readBytes returns null for unknown sha`() {
        val store = newStore()
        val unknown = EvidenceBlobRef.fromSha256(
            sha256 = "a".repeat(64),
            sizeBytes = 0L,
            mimeType = null,
            privacy = MessagePrivacy.LocalOnly,
            sourceType = EvidenceSourceType.Memory,
        )
        assertNull(store.readBytes(unknown))
    }

    @Test
    fun `clear wipes both roots`() {
        val localRoot = tempFolder.newFolder("evidence-local")
        val remoteRoot = tempFolder.newFolder("evidence-remote")
        val store = OnDeviceEvidenceBlobStore(localRoot, remoteRoot)
        val localRef = store.putText("loc", EvidenceSourceType.Memory, MessagePrivacy.LocalOnly)
        val remoteRef = store.putText("rem", EvidenceSourceType.PublicWeb, MessagePrivacy.RemoteEligible)
        assertTrue(localRoot.listFiles()!!.isNotEmpty())
        assertTrue(remoteRoot.listFiles()!!.isNotEmpty())
        store.clear()
        assertTrue(localRoot.listFiles()!!.isEmpty())
        assertTrue(remoteRoot.listFiles()!!.isEmpty())
        assertNull(store.readBytes(localRef))
        assertNull(store.readBytes(remoteRef))
    }

    @Test
    fun `putBytes round trips raw bytes`() {
        val store = newStore()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val ref = store.putBytes(
            bytes,
            mimeType = "application/octet-stream",
            EvidenceSourceType.FilePreview,
            MessagePrivacy.LocalOnly,
        )
        assertArrayEquals(bytes, store.readBytes(ref))
    }
}
