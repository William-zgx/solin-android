package com.bytedance.zgx.solin.runtime

import com.google.ai.edge.localagents.rag.models.proto.EmbedText
import com.google.protobuf.GeneratedMessageLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingRuntimeDependencyTest {
    @Test
    fun localAgentsGeneratedProtoHasJavaliteRuntime() {
        val message = EmbedText.newBuilder()
            .setText("Solin embedding dependency probe")
            .build()

        assertEquals("Solin embedding dependency probe", message.text)
        assertTrue(message is GeneratedMessageLite<*, *>)
    }
}
