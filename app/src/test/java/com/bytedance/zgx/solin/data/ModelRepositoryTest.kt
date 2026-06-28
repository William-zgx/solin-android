package com.bytedance.zgx.solin.data

import com.bytedance.zgx.solin.MEMORY_EMBEDDING_MODEL_ID
import com.bytedance.zgx.solin.ModelCatalog
import java.io.File
import java.io.IOException
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

    private fun withTempModelDir(block: (File) -> Unit) {
        val dir = File.createTempFile("solin-model-dir", "")
        dir.delete()
        dir.mkdirs()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun assertNoTemporaryModels(modelDir: File) {
        val tempFiles = modelDir.listFiles { file ->
            file.isFile && file.extension == "tmp"
        }?.toList().orEmpty()
        assertTrue("Expected no temporary model files, found $tempFiles", tempFiles.isEmpty())
    }
}
