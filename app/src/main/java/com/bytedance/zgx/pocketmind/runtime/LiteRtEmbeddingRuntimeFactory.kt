package com.bytedance.zgx.pocketmind.runtime

import com.bytedance.zgx.pocketmind.memory.EmbeddingRuntime

object LiteRtEmbeddingRuntimeFactory {
    const val UNSUPPORTED_REASON: String =
        "litertlm-android 0.12.0 does not expose a public embedding vector API"

    fun create(modelPath: String): EmbeddingRuntime? {
        val normalizedPath = modelPath.trim()
        if (normalizedPath.isBlank()) return null
        return UnsupportedLiteRtEmbeddingRuntime
    }
}

private object UnsupportedLiteRtEmbeddingRuntime : EmbeddingRuntime {
    override val supportsSemanticRecall: Boolean = false

    override fun embed(text: String): FloatArray =
        throw UnsupportedOperationException(LiteRtEmbeddingRuntimeFactory.UNSUPPORTED_REASON)
}
