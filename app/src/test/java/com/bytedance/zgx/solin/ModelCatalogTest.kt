package com.bytedance.zgx.solin

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
            setOf(ModelCapability.Chat, ModelCapability.MemoryEmbedding),
            basicModels.map { it.capability }.toSet(),
        )
        assertTrue(ModelCatalog.optionalModels().any { it.id == MOBILE_ACTION_MODEL_ID })
        assertEquals(setOf(DEFAULT_CHAT_MODEL_ID), ModelCatalog.defaultSetupModelIds())
        assertNull(ModelCatalog.recommendedModelOrNull("unknown-model-id"))
        assertTrue(ModelCatalog.isChatModel(DEFAULT_CHAT_MODEL_ID))
        assertTrue(ModelCatalog.isChatModel("chat-e4b"))
        assertFalse(ModelCatalog.isChatModel(MEMORY_EMBEDDING_MODEL_ID))
        assertFalse(ModelCatalog.isChatModel(MOBILE_ACTION_MODEL_ID))
        assertFalse(ModelCatalog.isChatModel("unknown-model-id"))
        assertEquals(DEFAULT_CHAT_MODEL_ID, ModelCatalog.recommendedChatModelById(MEMORY_EMBEDDING_MODEL_ID).id)
    }

    @Test
    fun modelProfilesSeparateAssetCapabilityFromInputModality() {
        val chatProfile = ModelCatalog.profileForModelId(DEFAULT_CHAT_MODEL_ID)
        val memoryProfile = ModelCatalog.profileForModelId(MEMORY_EMBEDDING_MODEL_ID)
        val textOnlyChatProfile = ModelCatalog.profileFor(
            ModelCatalog.recommendedModelById(DEFAULT_CHAT_MODEL_ID).copy(id = "chat-text-only-test"),
        )
        val remoteVisionProfile =
            RemoteModelConfig(modelName = "vision-model", supportsVisionInput = true).modelProfile()

        assertEquals(ModelCapability.Chat, chatProfile.capability)
        assertEquals(setOf(ModelInputModality.Text, ModelInputModality.Vision), chatProfile.inputModalities)
        assertTrue(ModelFeature.TextGeneration in chatProfile.features)
        assertTrue(ModelFeature.VisionInput in chatProfile.features)
        assertTrue(ModelFeature.MobileActionPlanning in chatProfile.features)
        assertTrue(chatProfile.supportsChatGeneration)
        assertTrue(chatProfile.supportsVisionInput)
        assertFalse(chatProfile.supportsMemoryEmbedding)
        assertTrue(chatProfile.supportsMobileActionPlanning)
        assertFalse(chatProfile.remoteEligible)
        assertFalse(chatProfile.requiresRemoteSendConfirmation)
        assertEquals(LocalModelTokenLimits.MAX_TOTAL_TOKENS, chatProfile.contextWindowTokens)
        assertEquals(setOf(BackendChoice.GPU, BackendChoice.CPU), chatProfile.preferredLocalBackends)

        assertEquals(ModelCapability.MemoryEmbedding, memoryProfile.capability)
        assertEquals(setOf(ModelFeature.MemoryEmbedding), memoryProfile.features)
        assertFalse(memoryProfile.supportsChatGeneration)
        assertFalse(memoryProfile.supportsVisionInput)
        assertTrue(memoryProfile.supportsMemoryEmbedding)
        assertFalse(memoryProfile.supportsMobileActionPlanning)
        assertFalse(memoryProfile.remoteEligible)
        assertFalse(memoryProfile.requiresRemoteSendConfirmation)
        assertNull(memoryProfile.contextWindowTokens)
        assertEquals(setOf(BackendChoice.CPU), memoryProfile.preferredLocalBackends)

        val actionProfile = ModelCatalog.profileForModelId(MOBILE_ACTION_MODEL_ID)
        assertFalse(actionProfile.supportsChatGeneration)
        assertFalse(actionProfile.supportsVisionInput)
        assertFalse(actionProfile.supportsMemoryEmbedding)
        assertTrue(actionProfile.supportsMobileActionPlanning)
        assertFalse(actionProfile.remoteEligible)
        assertFalse(actionProfile.requiresRemoteSendConfirmation)
        assertEquals(setOf(BackendChoice.CPU), actionProfile.preferredLocalBackends)

        assertEquals(ModelCapability.Chat, textOnlyChatProfile.capability)
        assertEquals(setOf(ModelInputModality.Text), textOnlyChatProfile.inputModalities)
        assertFalse(textOnlyChatProfile.supportsVisionInput)
        assertFalse(textOnlyChatProfile.remoteEligible)
        assertFalse(textOnlyChatProfile.requiresRemoteSendConfirmation)
        assertNull(ModelCatalog.profileForModelIdOrNull("unknown-model-id"))

        val customProfile = ModelCatalog.customLocalChatProfile("导入模型")
        assertEquals(CUSTOM_LOCAL_CHAT_PROFILE_ID, customProfile.id)
        assertEquals(ModelCapability.Chat, customProfile.capability)
        assertEquals(ModelBackendKind.LocalLiteRt, customProfile.backendKind)
        assertEquals(setOf(ModelInputModality.Text), customProfile.inputModalities)
        assertEquals(setOf(ModelFeature.TextGeneration), customProfile.features)
        assertTrue(customProfile.supportsChatGeneration)
        assertFalse(customProfile.supportsVisionInput)
        assertFalse(customProfile.supportsMemoryEmbedding)
        assertFalse(customProfile.supportsMobileActionPlanning)
        assertFalse(customProfile.remoteEligible)
        assertFalse(customProfile.requiresRemoteSendConfirmation)
        assertNull(customProfile.contextWindowTokens)
        assertTrue(customProfile.preferredLocalBackends.isEmpty())

        assertEquals(ModelCapability.Chat, remoteVisionProfile.capability)
        assertEquals(setOf(ModelInputModality.Text, ModelInputModality.Vision), remoteVisionProfile.inputModalities)
        assertTrue(remoteVisionProfile.supportsVisionInput)
        assertTrue(remoteVisionProfile.preferredLocalBackends.isEmpty())
        assertTrue(remoteVisionProfile.remoteEligible)
        assertTrue(remoteVisionProfile.requiresRemoteSendConfirmation)
    }

    @Test
    fun remoteModelProfileCanDisableVisionWithoutChangingChatCapability() {
        val profile = RemoteModelConfig(
            modelName = "text-only",
            supportsVisionInput = false,
        ).modelProfile()

        assertEquals(ModelCapability.Chat, profile.capability)
        assertEquals(setOf(ModelInputModality.Text), profile.inputModalities)
        assertFalse(profile.supportsVisionInput)
        assertTrue(profile.remoteEligible)
        assertTrue(profile.requiresRemoteSendConfirmation)
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelProfileRejectsVisionFeatureWithoutVisionModality() {
        ModelProfile(
            id = "bad-vision",
            displayName = "Bad Vision",
            capability = ModelCapability.Chat,
            backendKind = ModelBackendKind.LocalLiteRt,
            inputModalities = setOf(ModelInputModality.Text),
            features = setOf(ModelFeature.TextGeneration, ModelFeature.VisionInput),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelProfileRejectsVisionFeatureForNonChatProfiles() {
        ModelProfile(
            id = "bad-action-vision",
            displayName = "Bad Action Vision",
            capability = ModelCapability.MobileAction,
            backendKind = ModelBackendKind.LocalLiteRt,
            inputModalities = setOf(ModelInputModality.Text, ModelInputModality.Vision),
            features = setOf(ModelFeature.MobileActionPlanning, ModelFeature.VisionInput),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelProfileRejectsRemoteMemoryEmbeddingProfiles() {
        ModelProfile(
            id = "bad-remote-memory",
            displayName = "Bad Remote Memory",
            capability = ModelCapability.MemoryEmbedding,
            backendKind = ModelBackendKind.RemoteOpenAiCompatible,
            inputModalities = setOf(ModelInputModality.Text),
            features = setOf(ModelFeature.MemoryEmbedding),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelProfileRejectsRemoteMobileActionProfiles() {
        ModelProfile(
            id = "bad-remote-action",
            displayName = "Bad Remote Action",
            capability = ModelCapability.MobileAction,
            backendKind = ModelBackendKind.RemoteOpenAiCompatible,
            inputModalities = setOf(ModelInputModality.Text),
            features = setOf(ModelFeature.MobileActionPlanning),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelProfileRejectsLocalBackendsForRemoteProfiles() {
        ModelProfile(
            id = "bad-remote",
            displayName = "Bad Remote",
            capability = ModelCapability.Chat,
            backendKind = ModelBackendKind.RemoteOpenAiCompatible,
            inputModalities = setOf(ModelInputModality.Text),
            features = setOf(ModelFeature.TextGeneration),
            preferredLocalBackends = setOf(BackendChoice.GPU),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelProfileRejectsNonPositiveContextWindow() {
        ModelProfile(
            id = "bad-context",
            displayName = "Bad Context",
            capability = ModelCapability.Chat,
            backendKind = ModelBackendKind.LocalLiteRt,
            inputModalities = setOf(ModelInputModality.Text),
            features = setOf(ModelFeature.TextGeneration),
            tokenBudget = 0,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelProfileRejectsContextWindowForEmbeddingProfiles() {
        ModelProfile(
            id = "bad-embedding-context",
            displayName = "Bad Embedding Context",
            capability = ModelCapability.MemoryEmbedding,
            backendKind = ModelBackendKind.LocalLiteRt,
            inputModalities = setOf(ModelInputModality.Text),
            features = setOf(ModelFeature.MemoryEmbedding),
            tokenBudget = 4096,
            preferredLocalBackends = setOf(BackendChoice.CPU),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelProfileRejectsContextWindowForActionProfiles() {
        ModelProfile(
            id = "bad-action-context",
            displayName = "Bad Action Context",
            capability = ModelCapability.MobileAction,
            backendKind = ModelBackendKind.LocalLiteRt,
            inputModalities = setOf(ModelInputModality.Text),
            features = setOf(ModelFeature.MobileActionPlanning),
            tokenBudget = 4096,
            preferredLocalBackends = setOf(BackendChoice.CPU),
        )
    }

    @Test
    fun sha256Hex_matchesKnownFileContent() {
        val file = File.createTempFile("solin-sha", ".txt")
        try {
            file.writeText("solin", Charsets.UTF_8)

            assertEquals(
                "8719f08f987b6e215966c1f744e8c7efea6cf616dbf811aa010feef97e968140",
                ModelCatalog.sha256Hex(file),
            )
            assertTrue(
                ModelCatalog.matchesExpectedSha256(
                    file,
                    "8719f08f987b6e215966c1f744e8c7efea6cf616dbf811aa010feef97e968140",
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
