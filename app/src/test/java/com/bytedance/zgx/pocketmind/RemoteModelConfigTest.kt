package com.bytedance.zgx.pocketmind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelConfigTest {
    @Test
    fun normalizedTrimsBaseUrlModelNameAndApiKey() {
        val config = RemoteModelConfig(
            baseUrl = " https://api.example.com/v1/ ",
            modelName = " model-a ",
            apiKey = " key ",
        ).normalized()

        assertEquals("https://api.example.com/v1", config.baseUrl)
        assertEquals("model-a", config.modelName)
        assertEquals("key", config.apiKey)
    }

    @Test
    fun isConfiguredRequiresHttpUrlAndModelName() {
        assertTrue(RemoteModelConfig("https://api.example.com/v1", "model-a").isConfigured)
        assertTrue(RemoteModelConfig("http://10.0.2.2:8000/v1", "model-a").isConfigured)
        assertFalse(RemoteModelConfig("ftp://api.example.com/v1", "model-a").isConfigured)
        assertFalse(RemoteModelConfig("https://api.example.com/v1", "").isConfigured)
    }
}
