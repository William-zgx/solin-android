package com.bytedance.zgx.solin

import com.bytedance.zgx.solin.data.ModelVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUiStateModelVerificationTest {
    @Test
    fun verifiedRecommendedModelCountsAsInstalledAndCapability() {
        val state = ChatUiState(
            installedModels = listOf(
                installedModel(
                    recommendedModelId = DEFAULT_CHAT_MODEL_ID,
                    verificationStatus = ModelVerificationStatus.VerifiedRecommended,
                ),
            ),
        )

        assertTrue(state.isModelInstalled(DEFAULT_CHAT_MODEL_ID))
        assertEquals(setOf(ModelCapability.Chat), state.installedCapabilities)
    }

    @Test
    fun activeVerifiedRecommendedVisionModelReportsLocalVisionSupport() {
        val state = ChatUiState(
            activeInstalledModelId = DEFAULT_CHAT_MODEL_ID,
            installedModels = listOf(
                installedModel(
                    id = DEFAULT_CHAT_MODEL_ID,
                    recommendedModelId = DEFAULT_CHAT_MODEL_ID,
                    verificationStatus = ModelVerificationStatus.VerifiedRecommended,
                ),
            ),
        )

        assertEquals(DEFAULT_CHAT_MODEL_ID, state.activeLocalCapabilityProfile?.id)
        assertEquals(
            setOf(ModelInputModality.Text, ModelInputModality.Vision),
            state.activeLocalCapabilityProfile?.inputModalities,
        )
        assertTrue(state.activeLocalCapabilityProfile?.supportsVisionInput == true)
        assertTrue(state.activeLocalModelSupportsVisionInput)
    }

    @Test
    fun unverifiedRecommendedModelsDoNotCountAsInstalledOrCapabilities() {
        val state = ChatUiState(
            activeInstalledModelId = "legacy-chat",
            installedModels = listOf(
                installedModel(
                    id = "legacy-chat",
                    recommendedModelId = DEFAULT_CHAT_MODEL_ID,
                    verificationStatus = ModelVerificationStatus.LegacyUnverified,
                ),
                installedModel(
                    id = "failed-memory",
                    recommendedModelId = MEMORY_EMBEDDING_MODEL_ID,
                    verificationStatus = ModelVerificationStatus.FailedVerification,
                ),
            ),
        )

        assertFalse(state.isModelInstalled(DEFAULT_CHAT_MODEL_ID))
        assertFalse(state.isModelInstalled(MEMORY_EMBEDDING_MODEL_ID))
        assertFalse(state.activeLocalModelSupportsVisionInput)
        assertTrue(state.installedCapabilities.isEmpty())
    }

    @Test
    fun unknownRecommendedModelIdDoesNotCountAsInstalledOrCapabilities() {
        val state = ChatUiState(
            activeInstalledModelId = "unknown",
            installedModels = listOf(
                installedModel(
                    id = "unknown",
                    recommendedModelId = "unknown-model-id",
                    verificationStatus = ModelVerificationStatus.VerifiedRecommended,
                ),
            ),
        )

        assertFalse(state.isModelInstalled("unknown-model-id"))
        assertEquals(null, state.activeLocalCapabilityProfile)
        assertFalse(state.activeLocalModelSupportsVisionInput)
        assertTrue(state.installedCapabilities.isEmpty())
    }

    @Test
    fun customImportedModelStillCountsAsChatCapability() {
        val state = ChatUiState(
            activeInstalledModelId = "custom-chat",
            installedModels = listOf(
                installedModel(
                    id = "custom-chat",
                    recommendedModelId = null,
                    verificationStatus = ModelVerificationStatus.UnverifiedCustom,
                ),
            ),
        )

        assertFalse(state.isModelInstalled(DEFAULT_CHAT_MODEL_ID))
        assertEquals(setOf(ModelCapability.Chat), state.installedCapabilities)
        assertEquals(CUSTOM_LOCAL_CHAT_PROFILE_ID, state.activeLocalCapabilityProfile?.id)
        assertEquals(setOf(ModelInputModality.Text), state.activeLocalCapabilityProfile?.inputModalities)
        assertFalse(state.activeLocalModelSupportsVisionInput)
    }

    @Test
    fun remoteModeConfigDoesNotCreateLocalCapabilitiesAndStillRequiresRemoteConfirmation() {
        val state = ChatUiState(
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = RemoteModelConfig(
                baseUrl = "https://api.example.test/v1",
                modelName = "remote-vision",
                supportsVisionInput = true,
            ),
        )
        val remoteProfile = state.remoteModelConfig.modelProfile()

        assertTrue(remoteProfile.remoteEligible)
        assertTrue(remoteProfile.requiresRemoteSendConfirmation)
        assertFalse(remoteProfile.supportsMemoryEmbedding)
        assertFalse(remoteProfile.supportsMobileActionPlanning)
        assertEquals(null, state.activeLocalCapabilityProfile)
        assertFalse(state.activeLocalModelSupportsVisionInput)
        assertTrue(state.installedCapabilities.isEmpty())
    }

    private fun installedModel(
        id: String = "model",
        recommendedModelId: String?,
        verificationStatus: ModelVerificationStatus,
    ): InstalledModelSummary =
        InstalledModelSummary(
            id = id,
            displayName = id,
            path = "/tmp/$id.litertlm",
            fileBytes = 1L,
            recommendedModelId = recommendedModelId,
            verificationStatus = verificationStatus,
        )
}
