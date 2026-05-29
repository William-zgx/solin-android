package com.bytedance.zgx.pocketmind

import android.app.DownloadManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun acceptedModelName_requiresLiteRtLmExtensionCaseInsensitive() {
        assertTrue(ModelCatalog.isAcceptedModelName("chat-model.litertlm"))
        assertTrue(ModelCatalog.isAcceptedModelName("MODEL.LITERTLM"))

        assertFalse(ModelCatalog.isAcceptedModelName("model.bin"))
        assertFalse(ModelCatalog.isAcceptedModelName("chat-model.litertlm.tmp"))
    }

    @Test
    fun sanitizeModelName_keepsExtensionAndRemovesPathUnsafeCharacters() {
        assertEquals(
            "my_chat_model.litertlm",
            ModelCatalog.sanitizeModelName("my chat/model.litertlm"),
        )
        assertEquals(
            "chat-model.litertlm",
            ModelCatalog.sanitizeModelName("   "),
        )
        assertEquals(
            "model.bin.litertlm",
            ModelCatalog.sanitizeModelName("model.bin"),
        )
    }

    @Test
    fun completeRecommendedModel_requiresExactPublishedByteSize() {
        assertTrue(ModelCatalog.isCompleteRecommendedModel(DEFAULT_CHAT_MODEL_BYTES))

        assertFalse(ModelCatalog.isCompleteRecommendedModel(DEFAULT_CHAT_MODEL_BYTES - 1L))
        assertFalse(ModelCatalog.isCompleteRecommendedModel(DEFAULT_CHAT_MODEL_BYTES + 1L))
    }

    @Test
    fun recommendedModels_areGroupedByCapabilityAndSetupTier() {
        val defaultChat = ModelCatalog.recommendedModelById(DEFAULT_CHAT_MODEL_ID)
        val optionalChat = ModelCatalog.recommendedModelById("chat-e4b")
        val basicModels = ModelCatalog.basicSetupModels()

        assertEquals(ModelCapability.Chat, defaultChat.capability)
        assertEquals(SetupTier.BasicRecommended, defaultChat.setupTier)
        assertEquals(2_588_147_712L, defaultChat.byteSize)
        assertEquals("a4a831c060880f3733135ad22f10e0e9f758f45d", defaultChat.sourceRevision)
        assertEquals("181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", defaultChat.sha256Hex)
        assertFalse(defaultChat.downloadUrl.contains("/resolve/main/"))

        assertEquals(ModelCapability.Chat, optionalChat.capability)
        assertEquals(SetupTier.OptionalChat, optionalChat.setupTier)
        assertTrue(ModelCatalog.isCompleteRecommendedModel(3_659_530_240L, optionalChat))

        assertEquals(
            setOf(ModelCapability.Chat, ModelCapability.MemoryEmbedding, ModelCapability.MobileAction),
            basicModels.map { it.capability }.toSet(),
        )
        assertEquals(setOf(DEFAULT_CHAT_MODEL_ID), ModelCatalog.defaultSetupModelIds())
    }

    @Test
    fun sha256Hex_matchesKnownFileContent() {
        val file = File.createTempFile("pocketmind-sha", ".txt")
        try {
            file.writeText("pocketmind", Charsets.UTF_8)

            assertEquals(
                "84968002f8dabc8fae6052f1616ffdeb668bc4f2616d2bc22fbc43e7db06212a",
                ModelCatalog.sha256Hex(file),
            )
            assertTrue(
                ModelCatalog.matchesExpectedSha256(
                    file,
                    "84968002f8dabc8fae6052f1616ffdeb668bc4f2616d2bc22fbc43e7db06212a",
                ),
            )
            assertFalse(ModelCatalog.matchesExpectedSha256(file, "bad"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun progressPercent_handlesUnknownOverflowAndNormalProgress() {
        assertNull(ModelCatalog.progressPercent(100L, 0L))
        assertEquals(0, ModelCatalog.progressPercent(-10L, 100L))
        assertEquals(33, ModelCatalog.progressPercent(1L, 3L))
        assertEquals(100, ModelCatalog.progressPercent(150L, 100L))
    }

    @Test
    fun hasEnoughSpace_usesInclusiveBoundary() {
        assertFalse(ModelCatalog.hasEnoughSpace(DEFAULT_CHAT_MODEL_BYTES - 1L))
        assertTrue(ModelCatalog.hasEnoughSpace(DEFAULT_CHAT_MODEL_BYTES))
        assertTrue(ModelCatalog.hasEnoughSpace(DEFAULT_CHAT_MODEL_BYTES + 1L))
    }

    @Test
    fun formatBytes_usesReadableBinaryUnits() {
        assertEquals("0 B", ModelCatalog.formatBytes(0L))
        assertEquals("1.0 KB", ModelCatalog.formatBytes(1024L))
        assertEquals("1.5 MB", ModelCatalog.formatBytes(1_572_864L))
        assertEquals("2.4 GB", ModelCatalog.formatBytes(DEFAULT_CHAT_MODEL_BYTES))
    }

    @Test
    fun downloadStatusText_explainsWifiAndSpaceFailures() {
        assertEquals(
            "下载暂停：等待 Wi-Fi",
            ModelCatalog.downloadStatusText(
                DownloadManager.STATUS_PAUSED,
                DownloadManager.PAUSED_QUEUED_FOR_WIFI,
            ),
        )
        assertEquals(
            "下载失败：存储空间不足",
            ModelCatalog.downloadStatusText(
                DownloadManager.STATUS_FAILED,
                DownloadManager.ERROR_INSUFFICIENT_SPACE,
            ),
        )
    }
}
