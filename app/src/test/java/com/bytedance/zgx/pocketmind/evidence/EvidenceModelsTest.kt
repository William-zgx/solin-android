package com.bytedance.zgx.pocketmind.evidence

import com.bytedance.zgx.pocketmind.MessagePrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceModelsTest {
    @Test
    fun receiptSummaryCountsEvidenceWithoutRawText() {
        val summary = listOf(
            EvidenceCard(
                id = "memory:1",
                sourceType = EvidenceSourceType.Memory,
                privacy = MessagePrivacy.LocalOnly,
                requiresLocalModel = true,
                text = "private preference text",
                quality = EvidenceQuality(EvidenceQualityLevel.High),
            ),
            EvidenceCard(
                id = "file:1",
                sourceType = EvidenceSourceType.FilePreview,
                privacy = MessagePrivacy.LocalOnly,
                requiresLocalModel = true,
                text = "bad",
                quality = EvidenceQuality(EvidenceQualityLevel.Low, listOf("short_text")),
                truncated = true,
            ),
        ).toEvidenceReceiptSummary()

        assertEquals(2, summary.evidenceCardCount)
        assertEquals(2, summary.localOnlyEvidenceCardCount)
        assertEquals(1, summary.truncatedEvidenceCardCount)
        assertEquals(1, summary.lowQualityEvidenceCardCount)
        assertEquals(listOf(EvidenceSourceType.Memory, EvidenceSourceType.FilePreview), summary.sourceTypes)
        assertTrue(summary.toString().contains("evidenceCardCount"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun localOnlyEvidenceMustRequireLocalModel() {
        EvidenceCard(
            id = "bad",
            sourceType = EvidenceSourceType.ToolResult,
            privacy = MessagePrivacy.LocalOnly,
            requiresLocalModel = false,
            text = "private",
        )
    }
}
