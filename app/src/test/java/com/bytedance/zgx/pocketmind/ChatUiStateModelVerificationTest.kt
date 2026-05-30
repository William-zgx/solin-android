package com.bytedance.zgx.pocketmind

import com.bytedance.zgx.pocketmind.data.ModelVerificationStatus
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
    fun unverifiedRecommendedModelsDoNotCountAsInstalledOrCapabilities() {
        val state = ChatUiState(
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
        assertTrue(state.installedCapabilities.isEmpty())
    }

    @Test
    fun customImportedModelStillCountsAsChatCapability() {
        val state = ChatUiState(
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
