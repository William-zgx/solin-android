package com.bytedance.zgx.pocketmind.data

import com.bytedance.zgx.pocketmind.DEFAULT_CHAT_MODEL_ID
import com.bytedance.zgx.pocketmind.MEMORY_EMBEDDING_MODEL_ID
import com.bytedance.zgx.pocketmind.MOBILE_ACTION_MODEL_ID
import com.bytedance.zgx.pocketmind.ModelCapability
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelRepositoryPathTest {
    @Test
    fun verifiedRecommendedModelPathReturnsMemoryEmbeddingModel() {
        withTempModelFile { file ->
            val models = listOf(
                installedModel(
                    id = MEMORY_EMBEDDING_MODEL_ID,
                    path = file.absolutePath,
                    recommendedModelId = MEMORY_EMBEDDING_MODEL_ID,
                    verificationStatus = ModelVerificationStatus.VerifiedRecommended,
                ),
            )

            assertEquals(file.absolutePath, verifiedRecommendedModelPath(models, ModelCapability.MemoryEmbedding))
        }
    }

    @Test
    fun verifiedRecommendedModelPathIgnoresUnverifiedOrWrongCapabilityModels() {
        withTempModelFile { memoryFile ->
            withTempModelFile { actionFile ->
                val models = listOf(
                    installedModel(
                        id = "memory-legacy",
                        path = memoryFile.absolutePath,
                        recommendedModelId = MEMORY_EMBEDDING_MODEL_ID,
                        verificationStatus = ModelVerificationStatus.LegacyUnverified,
                    ),
                    installedModel(
                        id = MOBILE_ACTION_MODEL_ID,
                        path = actionFile.absolutePath,
                        recommendedModelId = MOBILE_ACTION_MODEL_ID,
                        verificationStatus = ModelVerificationStatus.VerifiedRecommended,
                    ),
                )

                assertNull(verifiedRecommendedModelPath(models, ModelCapability.MemoryEmbedding))
                assertEquals(actionFile.absolutePath, verifiedRecommendedModelPath(models, ModelCapability.MobileAction))
            }
        }
    }

    @Test
    fun verifiedRecommendedModelPathIgnoresMissingFiles() {
        val missingFile = File("/tmp/pocketmind-missing-memory-model.litertlm")
        missingFile.delete()
        val models = listOf(
            installedModel(
                id = MEMORY_EMBEDDING_MODEL_ID,
                path = missingFile.absolutePath,
                recommendedModelId = MEMORY_EMBEDDING_MODEL_ID,
                verificationStatus = ModelVerificationStatus.VerifiedRecommended,
            ),
        )

        assertNull(verifiedRecommendedModelPath(models, ModelCapability.MemoryEmbedding))
    }

    @Test
    fun verifiedRecommendedModelPathIgnoresVerifiedChatForMemoryLookup() {
        withTempModelFile { file ->
            val models = listOf(
                installedModel(
                    id = DEFAULT_CHAT_MODEL_ID,
                    path = file.absolutePath,
                    recommendedModelId = DEFAULT_CHAT_MODEL_ID,
                    verificationStatus = ModelVerificationStatus.VerifiedRecommended,
                ),
            )

            assertNull(verifiedRecommendedModelPath(models, ModelCapability.MemoryEmbedding))
        }
    }

    private fun installedModel(
        id: String,
        path: String,
        recommendedModelId: String?,
        verificationStatus: ModelVerificationStatus,
    ): InstalledModelEntity =
        InstalledModelEntity(
            id = id,
            displayName = id,
            path = path,
            fileBytes = 1L,
            recommendedModelId = recommendedModelId,
            sourceRevision = null,
            verifiedSha256 = null,
            verificationStatus = verificationStatus.name,
        )

    private fun withTempModelFile(block: (File) -> Unit) {
        val file = File.createTempFile("pocketmind-model", ".litertlm")
        try {
            file.writeText("model", Charsets.UTF_8)
            block(file)
        } finally {
            file.delete()
        }
    }
}
