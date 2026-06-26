package com.bytedance.zgx.pocketmind.runtime

import androidx.test.core.app.ApplicationProvider
import com.bytedance.zgx.pocketmind.MEMORY_EMBEDDING_MODEL_ID
import com.bytedance.zgx.pocketmind.ModelCatalog
import com.bytedance.zgx.pocketmind.data.PocketMindDatabase
import com.bytedance.zgx.pocketmind.memory.EmbeddingRuntime
import java.io.File
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

class TfliteTextEmbeddingRuntimeDeviceTest {
    @Test
    fun installedMemoryEmbeddingModelProducesSemanticVector() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val installed = PocketMindDatabase.get(context)
            .modelDao()
            .model(MEMORY_EMBEDDING_MODEL_ID)
        assumeNotNull("memory embedding model is not recorded as installed", installed)

        val modelFile = File(installed!!.path)
        val catalogModel = ModelCatalog.recommendedModelOrNull(MEMORY_EMBEDDING_MODEL_ID)!!
        val tokenizerFile = ModelCatalog.recommendedModelCompanionFile(
            modelFile,
            catalogModel.companionFiles.single(),
        )
        assertTrue("model file missing: ${modelFile.absolutePath}", modelFile.isFile)
        assertTrue("tokenizer file missing: ${tokenizerFile.absolutePath}", tokenizerFile.isFile)

        val runtime: EmbeddingRuntime? = try {
            TfliteTextEmbeddingRuntimeFactory.create(context, modelFile.absolutePath)
        } catch (error: Throwable) {
            throw AssertionError("embedding runtime create failed", error)
        }
        val nonNullRuntime = runtime ?: throw AssertionError("embedding runtime factory returned null")
        try {
            val vector = nonNullRuntime.embed("PocketMind semantic memory probe")
            assertEquals(TfliteTextEmbeddingRuntimeFactory.EMBEDDING_DIMENSION, vector.size)
            assertTrue(
                "embedding vector contains invalid values",
                vector.none { value -> value.isNaN() || value.isInfinite() },
            )
            val norm = sqrt(vector.sumOf { value -> (value * value).toDouble() })
            assertTrue("embedding vector norm is invalid: $norm", norm in 0.95..1.05)
        } catch (error: Throwable) {
            throw AssertionError("embedding runtime probe failed", error)
        } finally {
            nonNullRuntime.close()
        }
    }
}
