package com.bytedance.zgx.pocketmind.runtime

import android.content.Context
import com.bytedance.zgx.pocketmind.MEMORY_EMBEDDING_MODEL_ID
import com.bytedance.zgx.pocketmind.ModelCatalog
import com.bytedance.zgx.pocketmind.memory.EmbeddingRuntime
import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GemmaEmbeddingModel
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

private const val EMBEDDING_TIMEOUT_SECONDS: Long = 30L

object TfliteTextEmbeddingRuntimeFactory {
    const val RUNTIME_ID: String = "google-ai-edge-localagents-gemma-embedding"
    const val EMBEDDING_DIMENSION: Int = 768

    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, modelPath: String): EmbeddingRuntime? {
        val modelFile = File(modelPath.trim())
        if (!modelFile.isFile) return null
        val catalogModel = ModelCatalog.recommendedModelOrNull(MEMORY_EMBEDDING_MODEL_ID) ?: return null
        val tokenizerFile = catalogModel.companionFiles
            .firstOrNull()
            ?.let { companion -> ModelCatalog.recommendedModelCompanionFile(modelFile, companion) }
            ?: return null
        if (!tokenizerFile.isFile) return null
        return LocalAgentsGemmaEmbeddingRuntime(
            modelPath = modelFile.absolutePath,
            tokenizerPath = tokenizerFile.absolutePath,
        )
    }
}

private class LocalAgentsGemmaEmbeddingRuntime(
    modelPath: String,
    tokenizerPath: String,
) : EmbeddingRuntime {
    private val embedder = GemmaEmbeddingModel(
        modelPath,
        tokenizerPath,
        false,
    )

    override val runtimeId: String = TfliteTextEmbeddingRuntimeFactory.RUNTIME_ID
    override val modelId: String = MEMORY_EMBEDDING_MODEL_ID
    override val dimension: Int = TfliteTextEmbeddingRuntimeFactory.EMBEDDING_DIMENSION
    override val backend: String = "cpu"
    override val supportsSemanticRecall: Boolean = true

    override fun embed(text: String): FloatArray =
        embedder.getEmbeddings(embeddingRequest(listOf(text)))
            .get(EMBEDDING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .map { value -> value.toFloat() }
            .toFloatArray()
            .normalized()

    override fun embedBatch(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return embedder.getBatchEmbeddings(embeddingRequest(texts))
            .get(EMBEDDING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .map { vector ->
                vector.map { value -> value.toFloat() }
                    .toFloatArray()
                    .normalized()
            }
    }

    private fun embeddingRequest(texts: List<String>): EmbeddingRequest<String> =
        EmbeddingRequest.create(
            texts.map { text ->
                EmbedData.create(
                    text,
                    EmbedData.TaskType.SEMANTIC_SIMILARITY,
                    false,
                )
            },
        )

    private fun FloatArray.normalized(): FloatArray {
        if (size != dimension) {
            error("Embedding dimension mismatch: expected $dimension, got $size")
        }
        val norm = sqrt(sumOf { value -> (value * value).toDouble() }).toFloat()
        if (norm <= 0f || norm.isNaN() || norm.isInfinite()) {
            error("Embedding vector has invalid norm")
        }
        return FloatArray(size) { index -> this[index] / norm }
    }
}
