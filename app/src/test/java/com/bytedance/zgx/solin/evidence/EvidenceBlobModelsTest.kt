package com.bytedance.zgx.solin.evidence

import com.bytedance.zgx.solin.MessagePrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceBlobModelsTest {

    private val validSha = "a".repeat(64)

    @Test(expected = IllegalArgumentException::class)
    fun `ref with malformed sha256 too short throws`() {
        EvidenceBlobRef(
            uri = "ev://${"a".repeat(10)}",
            sha256 = "a".repeat(10),
            sizeBytes = 10L,
            privacy = MessagePrivacy.LocalOnly,
            sourceType = EvidenceSourceType.Memory,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ref with non-hex sha256 throws`() {
        val nonHex = "g".repeat(64)
        EvidenceBlobRef(
            uri = "ev://$nonHex",
            sha256 = nonHex,
            sizeBytes = 10L,
            privacy = MessagePrivacy.LocalOnly,
            sourceType = EvidenceSourceType.Memory,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ref with uri sha mismatch throws`() {
        val otherSha = "b".repeat(64)
        EvidenceBlobRef(
            uri = "ev://$otherSha",
            sha256 = validSha,
            sizeBytes = 10L,
            privacy = MessagePrivacy.LocalOnly,
            sourceType = EvidenceSourceType.Memory,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ref with negative sizeBytes throws`() {
        EvidenceBlobRef(
            uri = "ev://$validSha",
            sha256 = validSha,
            sizeBytes = -1L,
            privacy = MessagePrivacy.LocalOnly,
            sourceType = EvidenceSourceType.Memory,
        )
    }

    @Test
    fun `valid ref constructs without error and uri matches ev scheme`() {
        val ref = EvidenceBlobRef(
            uri = "ev://$validSha",
            sha256 = validSha,
            sizeBytes = 42L,
            privacy = MessagePrivacy.LocalOnly,
            sourceType = EvidenceSourceType.Memory,
        )
        assertEquals("ev://$validSha", ref.uri)
        assertTrue(ref.uri.startsWith("ev://"))
        assertEquals(42L, ref.sizeBytes)
    }

    @Test
    fun `fromSha256 builds correct ref`() {
        val ref = EvidenceBlobRef.fromSha256(
            sha256 = validSha,
            sizeBytes = 123L,
            mimeType = "text/plain",
            privacy = MessagePrivacy.RemoteEligible,
            sourceType = EvidenceSourceType.ToolResult,
        )
        assertEquals("ev://$validSha", ref.uri)
        assertEquals(validSha, ref.sha256)
        assertEquals(123L, ref.sizeBytes)
        assertEquals("text/plain", ref.mimeType)
        assertEquals(MessagePrivacy.RemoteEligible, ref.privacy)
        assertEquals(EvidenceSourceType.ToolResult, ref.sourceType)
    }

    @Test
    fun `sizeBytes 0 is accepted`() {
        val ref = EvidenceBlobRef(
            uri = "ev://$validSha",
            sha256 = validSha,
            sizeBytes = 0L,
            privacy = MessagePrivacy.LocalOnly,
            sourceType = EvidenceSourceType.Memory,
        )
        assertEquals(0L, ref.sizeBytes)
    }
}
