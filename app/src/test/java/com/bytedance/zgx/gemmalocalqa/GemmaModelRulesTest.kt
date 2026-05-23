package com.bytedance.zgx.gemmalocalqa

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaModelRulesTest {
    @Test
    fun acceptedModelName_requiresLiteRtLmExtensionCaseInsensitive() {
        assertTrue(GemmaModelRules.isAcceptedModelName("gemma-4-E2B-it.litertlm"))
        assertTrue(GemmaModelRules.isAcceptedModelName("GEMMA.LITERTLM"))

        assertFalse(GemmaModelRules.isAcceptedModelName("gemma.bin"))
        assertFalse(GemmaModelRules.isAcceptedModelName("gemma-4-E2B-it.litertlm.tmp"))
    }

    @Test
    fun sanitizeModelName_keepsExtensionAndRemovesPathUnsafeCharacters() {
        assertEquals(
            "my_gemma_model.litertlm",
            GemmaModelRules.sanitizeModelName("my gemma/model.litertlm"),
        )
        assertEquals(
            "gemma-4-e2b.litertlm",
            GemmaModelRules.sanitizeModelName("   "),
        )
        assertEquals(
            "model.bin.litertlm",
            GemmaModelRules.sanitizeModelName("model.bin"),
        )
    }

    @Test
    fun completeRecommendedModel_requiresExactPublishedByteSize() {
        assertTrue(GemmaModelRules.isCompleteRecommendedModel(GEMMA_RECOMMENDED_MODEL_BYTES))

        assertFalse(GemmaModelRules.isCompleteRecommendedModel(GEMMA_RECOMMENDED_MODEL_BYTES - 1L))
        assertFalse(GemmaModelRules.isCompleteRecommendedModel(GEMMA_RECOMMENDED_MODEL_BYTES + 1L))
    }

    @Test
    fun progressPercent_handlesUnknownOverflowAndNormalProgress() {
        assertNull(GemmaModelRules.progressPercent(100L, 0L))
        assertEquals(0, GemmaModelRules.progressPercent(-10L, 100L))
        assertEquals(33, GemmaModelRules.progressPercent(1L, 3L))
        assertEquals(100, GemmaModelRules.progressPercent(150L, 100L))
    }

    @Test
    fun hasEnoughSpace_usesInclusiveBoundary() {
        assertFalse(GemmaModelRules.hasEnoughSpace(GEMMA_RECOMMENDED_MODEL_BYTES - 1L))
        assertTrue(GemmaModelRules.hasEnoughSpace(GEMMA_RECOMMENDED_MODEL_BYTES))
        assertTrue(GemmaModelRules.hasEnoughSpace(GEMMA_RECOMMENDED_MODEL_BYTES + 1L))
    }

    @Test
    fun formatBytes_usesReadableBinaryUnits() {
        assertEquals("0 B", GemmaModelRules.formatBytes(0L))
        assertEquals("1.0 KB", GemmaModelRules.formatBytes(1024L))
        assertEquals("1.5 MB", GemmaModelRules.formatBytes(1_572_864L))
        assertEquals("2.4 GB", GemmaModelRules.formatBytes(GEMMA_RECOMMENDED_MODEL_BYTES))
    }

    @Test
    fun downloadStatusText_explainsWifiAndSpaceFailures() {
        assertEquals(
            "下载暂停：等待 Wi-Fi",
            GemmaModelRules.downloadStatusText(
                DownloadManager.STATUS_PAUSED,
                DownloadManager.PAUSED_QUEUED_FOR_WIFI,
            ),
        )
        assertEquals(
            "下载失败：存储空间不足",
            GemmaModelRules.downloadStatusText(
                DownloadManager.STATUS_FAILED,
                DownloadManager.ERROR_INSUFFICIENT_SPACE,
            ),
        )
    }
}
