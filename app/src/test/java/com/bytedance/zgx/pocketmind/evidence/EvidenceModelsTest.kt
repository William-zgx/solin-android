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

    @Test
    fun failedDeviceVerificationArtifactRequiresFailedTargetAndReason() {
        val missingTarget = expectIllegalArgument {
            DeviceVerificationArtifact(
                id = "device-failure",
                status = VerificationStatus.Failed,
                serial = "fb6272c",
                apiLevel = 36,
                abi = "arm64-v8a",
                testCount = 56,
                reason = "instrumentation-timeout",
            )
        }
        assertTrue(missingTarget.message.orEmpty().contains("failedTarget"))

        val missingReason = expectIllegalArgument {
            DeviceVerificationArtifact(
                id = "device-failure",
                status = VerificationStatus.Failed,
                serial = "fb6272c",
                apiLevel = 36,
                abi = "arm64-v8a",
                testCount = 56,
                failedTarget = "instrumentation",
            )
        }
        assertTrue(missingReason.message.orEmpty().contains("reason"))
    }

    @Test
    fun passedDeviceVerificationArtifactRejectsFailureMetadata() {
        val failure = expectIllegalArgument {
            DeviceVerificationArtifact(
                id = "physical-api36-smoke",
                status = VerificationStatus.Passed,
                serial = "fb6272c",
                apiLevel = 36,
                abi = "arm64-v8a",
                testCount = 56,
                failedTarget = "instrumentation",
                reason = "instrumentation-timeout",
            )
        }

        assertTrue(failure.message.orEmpty().contains("failure metadata"))
    }

    @Test
    fun deviceVerificationArtifactRejectsInvalidArtifactSha() {
        val failure = expectIllegalArgument {
            DeviceVerificationArtifact(
                id = "physical-api36-smoke",
                status = VerificationStatus.Passed,
                serial = "fb6272c",
                apiLevel = 36,
                abi = "arm64-v8a",
                testCount = 56,
                artifactSha256 = "not-a-sha",
            )
        }

        assertTrue(failure.message.orEmpty().contains("SHA-256"))
    }

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException =
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (throwable: IllegalArgumentException) {
            throwable
        }
}
