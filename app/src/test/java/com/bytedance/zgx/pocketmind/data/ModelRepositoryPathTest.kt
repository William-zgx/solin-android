package com.bytedance.zgx.pocketmind.data

import com.bytedance.zgx.pocketmind.DEFAULT_CHAT_MODEL_ID
import com.bytedance.zgx.pocketmind.MEMORY_EMBEDDING_MODEL_ID
import com.bytedance.zgx.pocketmind.MOBILE_ACTION_MODEL_ID
import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.ModelCatalog
import com.bytedance.zgx.pocketmind.RecommendedModel
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
                    withVerifiedCatalogEvidence = true,
                ),
            )

            assertEquals(file.absolutePath, verifiedPath(models, ModelCapability.MemoryEmbedding))
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
                        withVerifiedCatalogEvidence = true,
                    ),
                )

                assertNull(verifiedPath(models, ModelCapability.MemoryEmbedding))
                assertEquals(actionFile.absolutePath, verifiedPath(models, ModelCapability.MobileAction))
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
                withVerifiedCatalogEvidence = true,
            ),
        )

        assertNull(verifiedPath(models, ModelCapability.MemoryEmbedding))
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
                    withVerifiedCatalogEvidence = true,
                ),
            )

            assertNull(verifiedPath(models, ModelCapability.MemoryEmbedding))
        }
    }

    @Test
    fun verifiedRecommendedModelPathRejectsCustomModelSpoofingMemoryEmbeddingAsset() {
        withTempModelFile { file ->
            val memoryModel = ModelCatalog.recommendedModelById(MEMORY_EMBEDDING_MODEL_ID)
            val models = listOf(
                installedModel(
                    id = "local-spoofed-memory",
                    path = file.absolutePath,
                    recommendedModelId = MEMORY_EMBEDDING_MODEL_ID,
                    verificationStatus = ModelVerificationStatus.VerifiedRecommended,
                    displayName = memoryModel.shortName,
                ),
            )

            assertNull(verifiedPath(models, ModelCapability.MemoryEmbedding))
        }
    }

    @Test
    fun verifiedRecommendedModelPathRejectsReplacedFileDespitePersistedEvidence() {
        withTempModelFile { file ->
            val models = listOf(
                installedModel(
                    id = MEMORY_EMBEDDING_MODEL_ID,
                    path = file.absolutePath,
                    recommendedModelId = MEMORY_EMBEDDING_MODEL_ID,
                    verificationStatus = ModelVerificationStatus.VerifiedRecommended,
                    withVerifiedCatalogEvidence = true,
                ),
            )

            assertNull(
                verifiedPath(
                    models = models,
                    capability = ModelCapability.MemoryEmbedding,
                    currentFileVerifier = { _, _ -> false },
                ),
            )
        }
    }

    @Test
    fun verifiedRecommendedModelPathRejectsPartialPersistedEvidence() {
        withTempModelFile { file ->
            val verified = installedModel(
                id = MEMORY_EMBEDDING_MODEL_ID,
                path = file.absolutePath,
                recommendedModelId = MEMORY_EMBEDDING_MODEL_ID,
                verificationStatus = ModelVerificationStatus.VerifiedRecommended,
                withVerifiedCatalogEvidence = true,
            )
            val cases = listOf(
                verified.copy(fileBytes = verified.fileBytes - 1L),
                verified.copy(sourceRevision = null),
                verified.copy(verifiedSha256 = null),
            )

            cases.forEach { model ->
                assertNull(verifiedPath(listOf(model), ModelCapability.MemoryEmbedding))
            }
        }
    }

    @Test
    fun verifiedRecommendedModelPathRejectsUnknownRecommendedModelId() {
        withTempModelFile { file ->
            val models = listOf(
                InstalledModelEntity(
                    id = "unknown-recommended",
                    displayName = "Unknown",
                    path = file.absolutePath,
                    fileBytes = 1L,
                    recommendedModelId = "unknown-model-id",
                    sourceRevision = "revision",
                    verifiedSha256 = "sha",
                    verificationStatus = ModelVerificationStatus.VerifiedRecommended.name,
                ),
            )

            assertNull(verifiedPath(models, ModelCapability.MemoryEmbedding))
        }
    }

    @Test
    fun activeChatCandidateRequiresChatGenerationProfile() {
        val verifiedChat = installedModel(
            id = DEFAULT_CHAT_MODEL_ID,
            path = "/tmp/chat.litertlm",
            recommendedModelId = DEFAULT_CHAT_MODEL_ID,
            verificationStatus = ModelVerificationStatus.VerifiedRecommended,
            withVerifiedCatalogEvidence = true,
        )
        val verifiedMemory = installedModel(
            id = MEMORY_EMBEDDING_MODEL_ID,
            path = "/tmp/memory.tflite",
            recommendedModelId = MEMORY_EMBEDDING_MODEL_ID,
            verificationStatus = ModelVerificationStatus.VerifiedRecommended,
            withVerifiedCatalogEvidence = true,
        )
        val customChat = installedModel(
            id = "custom-chat",
            path = "/tmp/custom.litertlm",
            recommendedModelId = null,
            verificationStatus = ModelVerificationStatus.UnverifiedCustom,
        )

        assertTrue(installedModelCanBecomeActiveChatModel(verifiedChat))
        assertFalse(installedModelCanBecomeActiveChatModel(verifiedMemory))
        assertTrue(installedModelCanBecomeActiveChatModel(customChat))
    }

    @Test
    fun canDeleteInstalledModelFileAllowsOnlyManagedLiteRtLmFiles() {
        withTempModelDir { internalRoot ->
            withTempModelDir { externalRoot ->
                withTempModelDir { outsideRoot ->
                    val internalModel = File(internalRoot, "chat.litertlm").apply { writeText("model") }
                    val externalModel = File(externalRoot, "downloaded.litertlm").apply { writeText("model") }
                    val outsideModel = File(outsideRoot, "outside.litertlm").apply { writeText("model") }
                    val nonModelFile = File(internalRoot, "notes.txt").apply { writeText("text") }
                    val roots = listOf(internalRoot, externalRoot)

                    assertTrue(canDeleteInstalledModelFile(internalModel, roots))
                    assertTrue(canDeleteInstalledModelFile(externalModel, roots))
                    assertFalse(canDeleteInstalledModelFile(outsideModel, roots))
                    assertFalse(canDeleteInstalledModelFile(nonModelFile, roots))
                    assertFalse(canDeleteInstalledModelFile(internalRoot, roots))
                }
            }
        }
    }

    private fun installedModel(
        id: String,
        path: String,
        recommendedModelId: String?,
        verificationStatus: ModelVerificationStatus,
        displayName: String = id,
        withVerifiedCatalogEvidence: Boolean = false,
    ): InstalledModelEntity =
        ModelCatalog.recommendedModelById(recommendedModelId).let { catalogModel ->
            val hasCatalogEvidence = withVerifiedCatalogEvidence && recommendedModelId != null
            InstalledModelEntity(
                id = id,
                displayName = displayName,
                path = path,
                fileBytes = if (hasCatalogEvidence) catalogModel.byteSize else 1L,
                recommendedModelId = recommendedModelId,
                sourceRevision = if (hasCatalogEvidence) catalogModel.sourceRevision else null,
                verifiedSha256 = if (hasCatalogEvidence) catalogModel.sha256Hex else null,
                verificationStatus = verificationStatus.name,
            )
        }

    private fun verifiedPath(
        models: List<InstalledModelEntity>,
        capability: ModelCapability,
        currentFileVerifier: (InstalledModelEntity, RecommendedModel) -> Boolean =
            { entity, _ -> File(entity.path).exists() },
    ): String? =
        verifiedRecommendedModelPath(
            models = models,
            capability = capability,
            currentFileVerifier = currentFileVerifier,
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

    private fun withTempModelDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("pocketmind-model-root").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
