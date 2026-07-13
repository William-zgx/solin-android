package com.bytedance.zgx.solin.memory

import com.bytedance.zgx.solin.storage.LocalDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMemoryStorageRetirementTest {
    @Test
    fun orphanedAndTombstonedEmbeddingMetadataDoesNotRequireLegacyVectors() {
        val imports = retainedLegacyEmbeddingImports(
            documents = listOf(
                embeddingDocument(
                    id = "orphan-vector",
                    recordId = "missing-record",
                    modelId = "embedding-model",
                ),
                embeddingDocument(
                    id = "tombstoned-vector",
                    recordId = "deleted-record",
                    modelId = "embedding-model",
                ),
                embeddingDocument(
                    id = "deleted-metadata-vector",
                    recordId = "active-record",
                    modelId = "embedding-model",
                    deletedAtMillis = 1L,
                ),
            ),
            existingRecordIds = setOf("active-record", "deleted-record"),
            deletedRecordIds = setOf("deleted-record"),
        )

        assertTrue(imports.isEmpty())
    }

    @Test
    fun retainedParentRecordKeepsEmbeddingImportAndResolvesMetadataFallbacks() {
        val document = embeddingDocument(
            id = "active-vector",
            recordId = "active-record",
            modelId = "embedding-model",
            ownerId = "",
            title = "",
        )

        val imports = retainedLegacyEmbeddingImports(
            documents = listOf(document),
            existingRecordIds = setOf("active-record"),
            deletedRecordIds = emptySet(),
        )

        assertEquals(
            listOf(
                LegacyEmbeddingImport(
                    document = document,
                    recordId = "active-record",
                    modelId = "embedding-model",
                ),
            ),
            imports,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun retainedParentRecordRejectsInvalidEmbeddingMetadata() {
        retainedLegacyEmbeddingImports(
            documents = listOf(
                embeddingDocument(
                    id = "active-vector",
                    recordId = "active-record",
                    modelId = "",
                    ownerId = "active-record",
                    title = "",
                ),
            ),
            existingRecordIds = setOf("active-record"),
            deletedRecordIds = emptySet(),
        )
    }

    private fun embeddingDocument(
        id: String,
        recordId: String,
        modelId: String,
        ownerId: String = recordId,
        title: String = modelId,
        deletedAtMillis: Long? = null,
    ): LocalDocument =
        LocalDocument(
            domain = "memory",
            id = id,
            ownerId = ownerId,
            title = title,
            text = recordId,
            type = "MemoryEmbedding",
            metadataJson = """{"recordId":"$recordId","modelId":"$modelId"}""",
            deletedAtMillis = deletedAtMillis,
        )
}
