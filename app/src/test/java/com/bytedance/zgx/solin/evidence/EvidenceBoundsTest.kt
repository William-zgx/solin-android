package com.bytedance.zgx.solin.evidence

import com.bytedance.zgx.solin.MessagePrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EvidenceBoundsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `short text below maxChars returned as-is`() {
        val result = EvidenceBounds.headTail(
            "hi",
            maxChars = 100,
            EvidenceSourceType.Memory,
            MessagePrivacy.LocalOnly,
            store = null,
        )
        assertEquals("hi", result.text)
        assertNull(result.ref)
        assertEquals(false, result.truncated)
        assertEquals(0, result.omittedChars)
    }

    @Test
    fun `long text with null store falls back to prefix with dots`() {
        val long = "x".repeat(1000)
        val result = EvidenceBounds.headTail(
            long,
            maxChars = 100,
            EvidenceSourceType.Memory,
            MessagePrivacy.LocalOnly,
            store = null,
        )
        assertTrue(result.text.endsWith("..."))
        assertNull(result.ref)
        assertEquals(true, result.truncated)
        assertTrue(result.omittedChars > 0)
        // text length should be roughly maxChars (minus ellipsis + trimEnd)
        assertTrue(result.text.length <= 100)
    }

    @Test
    fun `long text with NoOp store falls back to prefix no fake ev URIs`() {
        val long = "y".repeat(1000)
        val result = EvidenceBounds.headTail(
            long,
            maxChars = 200,
            EvidenceSourceType.Memory,
            MessagePrivacy.LocalOnly,
            store = NoOpEvidenceBlobStore,
        )
        assertNull(result.ref)
        assertTrue(!result.text.contains("ev://"))
        assertTrue(result.text.endsWith("..."))
    }

    @Test
    fun `long text with OnDeviceEvidenceBlobStore returns ev ref and head-tail`() {
        val localRoot = tempFolder.newFolder("ev-loc")
        val remoteRoot = tempFolder.newFolder("ev-rem")
        val store = OnDeviceEvidenceBlobStore(localRoot, remoteRoot)
        val long = "z".repeat(5000)
        val result = EvidenceBounds.headTail(
            long,
            maxChars = 400,
            EvidenceSourceType.ToolResult,
            MessagePrivacy.LocalOnly,
            store = store,
        )
        assertNotNull(result.ref)
        assertTrue(result.text.contains("ev://"))
        assertEquals(true, result.truncated)
    }

    @Test
    fun `omittedChars equals input length minus headChars minus tailChars`() {
        val localRoot = tempFolder.newFolder("ev-loc")
        val remoteRoot = tempFolder.newFolder("ev-rem")
        val store = OnDeviceEvidenceBlobStore(localRoot, remoteRoot)
        val inputLen = 5000
        val long = "a".repeat(inputLen)
        val maxChars = 400
        val result = EvidenceBounds.headTail(
            long,
            maxChars = maxChars,
            EvidenceSourceType.ToolResult,
            MessagePrivacy.LocalOnly,
            store = store,
        )
        // The body of the result (excluding the marker) should be head+tail;
        // omittedChars is exactly inputLen - headBudget - tailBudget.
        val expectedTail = maxChars / 3
        val expectedHead = maxChars - expectedTail - 80
        assertEquals(inputLen - expectedHead - expectedTail, result.omittedChars)
    }

    @Test
    fun `result text length is roughly maxChars`() {
        val localRoot = tempFolder.newFolder("ev-loc")
        val remoteRoot = tempFolder.newFolder("ev-rem")
        val store = OnDeviceEvidenceBlobStore(localRoot, remoteRoot)
        val long = "b".repeat(5000)
        val maxChars = 500
        val result = EvidenceBounds.headTail(
            long,
            maxChars = maxChars,
            EvidenceSourceType.ToolResult,
            MessagePrivacy.LocalOnly,
            store = store,
        )
        // The result text includes marker overhead (which contains the ev:// URI);
        // allow reasonable variance around maxChars.
        assertTrue(
            "text length ${result.text.length} too far from maxChars=$maxChars",
            result.text.length in (maxChars - 50)..(maxChars + 300),
        )
    }
}
