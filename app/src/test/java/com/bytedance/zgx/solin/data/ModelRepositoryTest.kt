package com.bytedance.zgx.solin.data

import com.bytedance.zgx.solin.HIGH_QUALITY_CHAT_MODEL_ID
import com.bytedance.zgx.solin.MEMORY_EMBEDDING_MODEL_ID
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.RecommendedModel
import com.bytedance.zgx.solin.RecommendedModelCompanionFile
import com.bytedance.zgx.solin.SetupTier
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRepositoryTest {
    @Test
    fun customDownloadSourceAcceptsHttpsPublicUrl() {
        val source = createCustomModelDownloadSource(
            " https://models.example.com/releases/solin-chat.litertlm?token=abc ",
        )

        assertNotNull(source)
        requireNotNull(source)
        assertEquals("自定义模型", source.title)
        assertEquals("solin-chat.litertlm", source.fileName)
        assertEquals("https://models.example.com/releases/solin-chat.litertlm?token=abc", source.downloadUrl)
        assertEquals(null, source.expectedSha256)
        assertEquals(null, source.modelId)
    }

    @Test
    fun persistedPendingDownloadSourceStripsSensitiveQueryAndFragment() {
        val source = createCustomModelDownloadSource(
            "https://models.example.com/releases/solin-chat.litertlm?token=abc&expires=123#signed",
        )
        requireNotNull(source)

        val persisted = source.toPersistedJson().toString()
        val restored = modelDownloadSourceFromPersistedJson(persisted)

        assertFalse(persisted.contains("token=abc"))
        assertFalse(persisted.contains("expires=123"))
        assertFalse(persisted.contains("signed"))
        requireNotNull(restored)
        assertEquals("https://models.example.com/releases/solin-chat.litertlm", restored.downloadUrl)
        assertEquals(source.fileName, restored.fileName)
        assertEquals(source.expectedSha256, restored.expectedSha256)
    }

    @Test
    fun restoredPendingDownloadSourceAlsoStripsLegacyPersistedQuery() {
        val legacyJson = """
            {
              "title": "自定义模型",
              "fileName": "solin-chat.litertlm",
              "downloadUrl": "https://models.example.com/releases/solin-chat.litertlm?token=abc#signed",
              "expectedBytes": null,
              "expectedSha256": null,
              "modelId": null,
              "registerInstalledModel": true,
              "requiresHuggingFaceAuthorization": false
            }
        """.trimIndent()

        val restored = modelDownloadSourceFromPersistedJson(legacyJson)

        requireNotNull(restored)
        assertEquals("https://models.example.com/releases/solin-chat.litertlm", restored.downloadUrl)
        val sanitized = sanitizePersistedDownloadSourceJson(legacyJson)
        requireNotNull(sanitized)
        assertFalse(sanitized.contains("token=abc"))
        assertFalse(sanitized.contains("signed"))
    }

    @Test
    fun customDownloadSourceAllowsHttpOnlyForLocalDebugHosts() {
        val localHosts = listOf(
            "http://localhost:8000/model.litertlm",
            "http://127.0.0.1:8000/model.litertlm",
            "http://[::1]:8000/model.litertlm",
            "http://10.0.2.2:8000/model.litertlm",
        )

        localHosts.forEach { url ->
            assertNotNull(url, createCustomModelDownloadSource(url))
        }
    }

    @Test
    fun customDownloadSourceRejectsPlainHttpPublicUrl() {
        assertNull(createCustomModelDownloadSource("http://models.example.com/model.litertlm"))
        assertNull(createCustomModelDownloadSource("http://192.168.1.12/model.litertlm"))
        assertNull(createCustomModelDownloadSource("http://example.com/model.bin"))
    }

    @Test
    fun customDownloadSourceRejectsMalformedUnsupportedOrCredentialUrls() {
        assertNull(createCustomModelDownloadSource(""))
        assertNull(createCustomModelDownloadSource("https:foo"))
        assertNull(createCustomModelDownloadSource("ftp://models.example.com/model.litertlm"))
        assertNull(createCustomModelDownloadSource("https://user:pass@models.example.com/model.litertlm"))
    }

    @Test
    fun customDownloadSourceRejectsHttpsNonLiteRtLmPath() {
        assertNull(createCustomModelDownloadSource("https://models.example.com/model.bin"))
        assertNull(createCustomModelDownloadSource("https://models.example.com/model.gguf"))
        assertNull(createCustomModelDownloadSource("https://models.example.com/download/"))
        assertNull(createCustomModelDownloadSource("https://models.example.com"))
    }

    @Test
    fun modelDownloadSourceVerifiedSha256RejectsWrongSize() {
        withTempModelFile("model") { file ->
            val source = ModelDownloadSource(
                title = "测试模型",
                fileName = file.name,
                downloadUrl = "https://models.example.com/model.litertlm",
                expectedBytes = file.length() + 1L,
                expectedSha256 = null,
                modelId = null,
            )

            val result = source.verifiedSha256(file)

            assertTrue(result.isFailure)
            assertEquals("模型文件大小不匹配", result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun modelDownloadSourceVerifiedSha256RejectsHashMismatch() {
        withTempModelFile("model") { file ->
            val source = ModelDownloadSource(
                title = "测试模型",
                fileName = file.name,
                downloadUrl = "https://models.example.com/model.litertlm",
                expectedBytes = file.length(),
                expectedSha256 = "0".repeat(64),
                modelId = null,
            )

            val result = source.verifiedSha256(file)

            assertTrue(result.isFailure)
            assertEquals("模型校验失败，请重新下载", result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun modelDownloadSourceVerifiedSha256AcceptsMatchingCustomFileWithoutHash() {
        withTempModelFile("model") { file ->
            val source = ModelDownloadSource(
                title = "测试模型",
                fileName = file.name,
                downloadUrl = "https://models.example.com/model.litertlm",
                expectedBytes = file.length(),
                expectedSha256 = null,
                modelId = null,
            )

            val result = source.verifiedSha256(file)

            assertTrue(result.isSuccess)
            assertNull(result.getOrThrow())
        }
    }

    @Test
    fun recommendedE4bUsesItsExactSizeAsTransferLimit() {
        val model = ModelCatalog.recommendedModelOrNull(HIGH_QUALITY_CHAT_MODEL_ID)
        requireNotNull(model)

        val source = ModelDownloadSource.recommended(model)

        assertEquals(3_659_530_240L, source.maximumTransferBytes())
        assertEquals(model.byteSize, source.maximumTransferBytes())
        assertTrue(source.maximumTransferBytes() < CUSTOM_MODEL_MAX_BYTES)
    }

    @Test
    fun customModelUsesEightGibAbsoluteTransferLimit() {
        val source = createCustomModelDownloadSource(
            "https://models.example.com/releases/custom.litertlm",
        )
        requireNotNull(source)

        assertEquals(8L * 1024L * 1024L * 1024L, CUSTOM_MODEL_MAX_BYTES)
        assertEquals(CUSTOM_MODEL_MAX_BYTES, source.maximumTransferBytes())
    }

    @Test
    fun boundedStreamCopyRejectsUnknownSizeBeforeWritingPastLimit() {
        val output = ByteArrayOutputStream()
        val copiedProgress = mutableListOf<Long>()

        val failure = runCatching {
            copyStreamWithByteLimit(
                input = ChunkedByteInputStream(ByteArray(6), chunkBytes = 2),
                output = output,
                maximumBytes = 5L,
                onBytesCopied = copiedProgress::add,
            )
        }

        assertTrue(failure.exceptionOrNull() is ModelTransferSizeLimitExceededException)
        assertEquals(4, output.size())
        assertEquals(listOf(2L, 4L), copiedProgress)
    }

    @Test
    fun currentRecommendedFileVerificationRejectsChangedBytesWithSameSizeAndTimestamp() {
        withTempModelDir { modelDir ->
            val primary = File(modelDir, "tiny-chat.litertlm").apply {
                writeText("abc", Charsets.UTF_8)
            }
            val fixedTimestamp = 1_700_000_000_000L
            primary.setLastModified(fixedTimestamp)
            val model = testRecommendedModel(
                fileName = primary.name,
                byteSize = primary.length(),
                sha256Hex = ModelCatalog.sha256Hex(primary),
            )

            assertTrue(currentFileMatchesRecommendedModel(primary, model))

            primary.writeText("abd", Charsets.UTF_8)
            primary.setLastModified(fixedTimestamp)

            assertEquals(model.byteSize, primary.length())
            assertEquals(fixedTimestamp, primary.lastModified())
            assertFalse(currentFileMatchesRecommendedModel(primary, model))
        }
    }

    @Test
    fun currentRecommendedFileVerificationRequiresCompanionSha() {
        withTempModelDir { modelDir ->
            val primary = File(modelDir, "tiny-memory.tflite").apply {
                writeText("primary", Charsets.UTF_8)
            }
            val companion = File(modelDir, "tokenizer.model").apply {
                writeText("tokenizer", Charsets.UTF_8)
            }
            val model = testRecommendedModel(
                fileName = primary.name,
                byteSize = primary.length(),
                sha256Hex = ModelCatalog.sha256Hex(primary),
                capability = ModelCapability.MemoryEmbedding,
                companionFiles = listOf(
                    RecommendedModelCompanionFile(
                        fileName = companion.name,
                        byteSize = companion.length(),
                        sha256Hex = ModelCatalog.sha256Hex(companion),
                        downloadUrl = "https://models.example.com/${companion.name}",
                    ),
                ),
            )

            assertTrue(currentFileMatchesRecommendedModel(primary, model))

            companion.writeText("bad-token", Charsets.UTF_8)

            assertFalse(currentFileMatchesRecommendedModel(primary, model))
        }
    }

    @Test
    fun completeRecommendedBundleShapeChecksSizesWithoutRehashingStableFiles() {
        withTempModelDir { modelDir ->
            val primary = File(modelDir, "tiny-memory.tflite").apply {
                writeText("primary", Charsets.UTF_8)
            }
            val companion = File(modelDir, "tokenizer.model").apply {
                writeText("tokenizer", Charsets.UTF_8)
            }
            val model = testRecommendedModel(
                fileName = primary.name,
                byteSize = primary.length(),
                sha256Hex = "0".repeat(64),
                capability = ModelCapability.MemoryEmbedding,
                companionFiles = listOf(
                    RecommendedModelCompanionFile(
                        fileName = companion.name,
                        byteSize = companion.length(),
                        sha256Hex = "f".repeat(64),
                        downloadUrl = "https://models.example.com/${companion.name}",
                    ),
                ),
            )

            assertTrue(hasCompleteRecommendedBundleShape(primary, model))
            assertFalse(currentFileMatchesRecommendedModel(primary, model))

            companion.writeText("short", Charsets.UTF_8)

            assertFalse(hasCompleteRecommendedBundleShape(primary, model))
        }
    }

    @Test
    fun downloadedRecommendedModelReplacesStaleIncompleteDatabaseEntry() {
        withTempModelDir { modelDir ->
            val downloaded = File(modelDir, "tiny-chat.litertlm").apply {
                writeText("complete")
            }
            val model = testRecommendedModel(
                fileName = downloaded.name,
                byteSize = downloaded.length(),
                sha256Hex = ModelCatalog.sha256Hex(downloaded),
            )
            val stale = InstalledModelEntity(
                id = model.id,
                displayName = model.shortName,
                path = File(modelDir, "missing.litertlm").absolutePath,
                fileBytes = 0L,
                recommendedModelId = model.id,
                sourceRevision = "old-revision",
                verifiedSha256 = "0".repeat(64),
                verificationStatus = ModelVerificationStatus.FailedVerification.name,
            )

            val reconciled = reconcileDownloadedRecommendedModel(stale, downloaded, model)

            requireNotNull(reconciled)
            assertEquals(downloaded.absolutePath, reconciled.path)
            assertEquals(downloaded.length(), reconciled.fileBytes)
            assertEquals(model.sourceRevision, reconciled.sourceRevision)
            assertNull(reconciled.verifiedSha256)
            assertEquals(ModelVerificationStatus.LegacyUnverified.name, reconciled.verificationStatus)
        }
    }

    @Test
    fun downloadedRecommendedModelDoesNotReplaceCompleteExistingEntryOrAcceptPartialFile() {
        withTempModelDir { modelDir ->
            val downloaded = File(modelDir, "tiny-chat.litertlm").apply {
                writeText("complete")
            }
            val existingFile = File(modelDir, "existing.litertlm").apply {
                writeText("existing")
            }
            val model = testRecommendedModel(
                fileName = downloaded.name,
                byteSize = downloaded.length(),
                sha256Hex = ModelCatalog.sha256Hex(downloaded),
            )
            val completeExisting = InstalledModelEntity(
                id = model.id,
                displayName = model.shortName,
                path = existingFile.absolutePath,
                fileBytes = model.byteSize,
                recommendedModelId = model.id,
                sourceRevision = model.sourceRevision,
                verifiedSha256 = model.sha256Hex,
                verificationStatus = ModelVerificationStatus.VerifiedRecommended.name,
            )

            assertNull(reconcileDownloadedRecommendedModel(completeExisting, downloaded, model))

            downloaded.writeText("partial")
            assertNull(reconcileDownloadedRecommendedModel(null, downloaded, model))
        }
    }

    @Test
    fun recommendedRegistrationStatusRequiresCompleteCompanionBundle() {
        withTempModelDir { modelDir ->
            val primary = File(modelDir, "tiny-memory.tflite").apply {
                writeText("primary", Charsets.UTF_8)
            }
            val missingCompanion = File(modelDir, "tokenizer.model")
            val model = testRecommendedModel(
                fileName = primary.name,
                byteSize = primary.length(),
                sha256Hex = ModelCatalog.sha256Hex(primary),
                capability = ModelCapability.MemoryEmbedding,
                companionFiles = listOf(
                    RecommendedModelCompanionFile(
                        fileName = missingCompanion.name,
                        byteSize = 9L,
                        sha256Hex = "0".repeat(64),
                        downloadUrl = "https://models.example.com/${missingCompanion.name}",
                    ),
                ),
            )

            assertEquals(
                ModelVerificationStatus.FailedVerification,
                recommendedRegistrationVerificationStatus(
                    file = primary,
                    model = model,
                    verifiedSha256 = model.sha256Hex,
                    requestedStatus = ModelVerificationStatus.VerifiedRecommended,
                ),
            )

            missingCompanion.writeText("tokenizer", Charsets.UTF_8)
            val completeModel = model.copy(
                companionFiles = listOf(
                    model.companionFiles.single().copy(
                        byteSize = missingCompanion.length(),
                        sha256Hex = ModelCatalog.sha256Hex(missingCompanion),
                    ),
                ),
            )

            assertEquals(
                ModelVerificationStatus.VerifiedRecommended,
                recommendedRegistrationVerificationStatus(
                    file = primary,
                    model = completeModel,
                    verifiedSha256 = completeModel.sha256Hex,
                    requestedStatus = ModelVerificationStatus.VerifiedRecommended,
                ),
            )
        }
    }

    @Test
    fun modelDownloadSourceUsesFileNameWhenRecommendedModelIdIsUnknown() {
        withTempModelFile("model") { file ->
            val source = ModelDownloadSource(
                title = "未知模型",
                fileName = file.name,
                downloadUrl = "https://models.example.com/${file.name}",
                expectedBytes = file.length(),
                expectedSha256 = null,
                modelId = "unknown-model-id",
            )

            assertEquals(file.nameWithoutExtension, source.installedDisplayName(file))
        }
    }

    @Test
    fun recommendedMemoryModelExpandsToAuthenticatedEmbeddingGemmaBundleSources() {
        val memoryModel = ModelCatalog.recommendedModelOrNull(MEMORY_EMBEDDING_MODEL_ID)
        requireNotNull(memoryModel)

        val sources = ModelDownloadSource.recommendedBundle(memoryModel)

        assertEquals(2, sources.size)
        assertEquals("embeddinggemma-300M_seq256_mixed-precision.tflite", sources[0].fileName)
        assertEquals(true, sources[0].registerInstalledModel)
        assertEquals(MEMORY_EMBEDDING_MODEL_ID, sources[0].modelId)
        assertTrue(sources[0].requiresHuggingFaceAuthorization)
        assertTrue(sources[0].downloadUrl.startsWith("https://huggingface.co/litert-community/embeddinggemma-300m/"))
        assertEquals("sentencepiece.model", sources[1].fileName)
        assertEquals(false, sources[1].registerInstalledModel)
        assertEquals(MEMORY_EMBEDDING_MODEL_ID, sources[1].modelId)
        assertTrue(sources[1].requiresHuggingFaceAuthorization)
    }

    @Test
    fun recommendedMemoryModelRequiresTokenizerCompanionFile() {
        val memoryModel = ModelCatalog.recommendedModelOrNull(MEMORY_EMBEDDING_MODEL_ID)
        requireNotNull(memoryModel)
        withTempModelDir { modelDir ->
            val primary = File(modelDir, memoryModel.fileName).apply {
                RandomAccessFile(this, "rw").use { file ->
                    file.setLength(memoryModel.byteSize)
                }
            }

            assertEquals(listOf("sentencepiece.model"), memoryModel.companionFiles.map { it.fileName })
            assertFalse(ModelCatalog.hasCompleteCompanionFiles(primary, memoryModel))
        }
    }

    @Test
    fun acceptedModelNamesIncludeMemoryModelFilesButCustomDownloadsRemainLiteRtLmOnly() {
        assertTrue(ModelCatalog.isAcceptedModelName("memory.tflite"))
        assertTrue(ModelCatalog.isAcceptedModelName("chat.litertlm"))
        assertNull(createCustomModelDownloadSource("https://models.example.com/memory.tflite"))
    }

    @Test
    fun importModelFileRejectsNonLiteRtLmDisplayNameBeforeCopy() {
        withTempModelDir { modelDir ->
            var copyCalled = false

            val failure = runCatching {
                importModelFileToModelDir(
                    modelDir = modelDir,
                    displayName = "custom-model.bin",
                    sourceSizeBytes = 5L,
                    usableSpaceBytes = 10L,
                    copyToTemp = {
                        copyCalled = true
                    },
                )
            }

            assertTrue(failure.isFailure)
            assertEquals("请选择 .litertlm 模型文件", failure.exceptionOrNull()?.message)
            assertFalse(copyCalled)
            assertNoTemporaryModels(modelDir)
            assertFalse(File(modelDir, "custom-model.bin.litertlm").exists())
        }
    }

    @Test
    fun importModelFileFailsStoragePreflightBeforeCopy() {
        withTempModelDir { modelDir ->
            var copyCalled = false

            val failure = runCatching {
                importModelFileToModelDir(
                    modelDir = modelDir,
                    displayName = "custom-model.litertlm",
                    sourceSizeBytes = 11L,
                    usableSpaceBytes = 10L,
                    copyToTemp = {
                        copyCalled = true
                    },
                )
            }

            assertTrue(failure.isFailure)
            assertEquals("存储空间不足，导入需要 11.0 B", failure.exceptionOrNull()?.message)
            assertFalse(copyCalled)
            assertNoTemporaryModels(modelDir)
            assertFalse(File(modelDir, "custom-model.litertlm").exists())
        }
    }

    @Test
    fun importModelFileRejectsAdvertisedOversizeBeforeCopy() {
        withTempModelDir { modelDir ->
            var copyCalled = false

            val failure = runCatching {
                importModelFileToModelDir(
                    modelDir = modelDir,
                    displayName = "custom-model.litertlm",
                    sourceSizeBytes = 6L,
                    usableSpaceBytes = 10L,
                    maximumBytes = 5L,
                    copyToTemp = {
                        copyCalled = true
                    },
                )
            }

            assertTrue(failure.exceptionOrNull() is ModelTransferSizeLimitExceededException)
            assertFalse(copyCalled)
            assertNoTemporaryModels(modelDir)
            assertFalse(File(modelDir, "custom-model.litertlm").exists())
        }
    }

    @Test
    fun importModelFileRejectsFalseSmallSizeAndDeletesTmp() {
        withTempModelDir { modelDir ->
            val failure = runCatching {
                importModelFileToModelDir(
                    modelDir = modelDir,
                    displayName = "custom-model.litertlm",
                    sourceSizeBytes = 1L,
                    usableSpaceBytes = 10L,
                    maximumBytes = 5L,
                    copyToTemp = { tempTarget ->
                        ChunkedByteInputStream(ByteArray(6), chunkBytes = 2).use { input ->
                            tempTarget.outputStream().use { output ->
                                copyStreamWithByteLimit(input, output, maximumBytes = 5L)
                            }
                        }
                    },
                )
            }

            assertTrue(failure.exceptionOrNull() is ModelTransferSizeLimitExceededException)
            assertNoTemporaryModels(modelDir)
            assertFalse(File(modelDir, "custom-model.litertlm").exists())
        }
    }

    @Test
    fun importModelFileRejectsEmptyCopiedFileAndDeletesTmp() {
        withTempModelDir { modelDir ->
            val failure = runCatching {
                importModelFileToModelDir(
                    modelDir = modelDir,
                    displayName = "custom-model.litertlm",
                    sourceSizeBytes = 0L,
                    usableSpaceBytes = 10L,
                    copyToTemp = {
                        // Leave the temp file empty to simulate a zero-byte picker result.
                    },
                )
            }

            assertTrue(failure.isFailure)
            assertEquals("模型文件为空", failure.exceptionOrNull()?.message)
            assertNoTemporaryModels(modelDir)
            assertFalse(File(modelDir, "custom-model.litertlm").exists())
        }
    }

    @Test
    fun importModelFileDeletesTmpWhenCopyFails() {
        withTempModelDir { modelDir ->
            val failure = runCatching {
                importModelFileToModelDir(
                    modelDir = modelDir,
                    displayName = "custom-model.litertlm",
                    sourceSizeBytes = 5L,
                    usableSpaceBytes = 10L,
                    copyToTemp = { tempTarget ->
                        tempTarget.writeText("partial", Charsets.UTF_8)
                        throw IOException("copy failed")
                    },
                )
            }

            assertTrue(failure.isFailure)
            assertEquals("copy failed", failure.exceptionOrNull()?.message)
            assertNoTemporaryModels(modelDir)
            assertFalse(File(modelDir, "custom-model.litertlm").exists())
        }
    }

    @Test
    fun importModelFileMovesCopiedFileAsUnverifiedCustomRegistration() {
        withTempModelDir { modelDir ->
            val imported = importModelFileToModelDir(
                modelDir = modelDir,
                displayName = "custom-model.litertlm",
                sourceSizeBytes = 5L,
                usableSpaceBytes = 10L,
                copyToTemp = { tempTarget ->
                    tempTarget.writeText("model", Charsets.UTF_8)
                },
            )

            val target = File(imported.path)
            assertTrue(target.exists())
            assertEquals("model", target.readText(Charsets.UTF_8))
            assertEquals("custom-model", imported.displayName)
            assertEquals(null, imported.recommendedModelId)
            assertEquals(ModelVerificationStatus.UnverifiedCustom, imported.verificationStatus)
            assertNoTemporaryModels(modelDir)
        }
    }

    private fun withTempModelFile(content: String, block: (File) -> Unit) {
        val file = File.createTempFile("solin-model", ".litertlm")
        try {
            file.writeText(content, Charsets.UTF_8)
            block(file)
        } finally {
            file.delete()
        }
    }

    private class ChunkedByteInputStream(
        private val bytes: ByteArray,
        private val chunkBytes: Int,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int =
            if (position >= bytes.size) {
                -1
            } else {
                bytes[position++].toInt() and 0xff
            }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(chunkBytes, length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }

    private fun assertNoTemporaryModels(modelDir: File) {
        val tempFiles = modelDir.listFiles { file ->
            file.isFile && file.extension == "tmp"
        }?.toList().orEmpty()
        assertTrue("Expected no temporary model files, found $tempFiles", tempFiles.isEmpty())
    }

    private fun testRecommendedModel(
        fileName: String,
        byteSize: Long,
        sha256Hex: String,
        capability: ModelCapability = ModelCapability.Chat,
        companionFiles: List<RecommendedModelCompanionFile> = emptyList(),
    ): RecommendedModel =
        RecommendedModel(
            id = "test-${fileName.hashCode()}",
            displayName = "测试模型",
            shortName = "测试模型",
            fileName = fileName,
            byteSize = byteSize,
            sourceRevision = "test-revision",
            sha256Hex = sha256Hex,
            repositoryUrl = "https://models.example.com/repo",
            downloadUrl = "https://models.example.com/$fileName",
            deviceHint = "test",
            capability = capability,
            setupTier = SetupTier.BasicRecommended,
            companionFiles = companionFiles,
        )
}
