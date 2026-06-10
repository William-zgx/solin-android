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
        assertFalse(config.supportsVisionInput)
    }

    @Test
    fun normalizedPreservesVisionCapabilitySwitch() {
        val config = RemoteModelConfig(
            baseUrl = " https://api.example.com/v1/ ",
            modelName = " model-a ",
            apiKey = " key ",
            supportsVisionInput = false,
        ).normalized()

        assertFalse(config.supportsVisionInput)
    }

    @Test
    fun normalizedDoesNotPersistTransientCheckingConnectivity() {
        val config = RemoteModelConfig(
            baseUrl = "https://api.example.com/v1",
            modelName = "model-a",
            connectivityStatus = RemoteModelConnectivityStatus.Checking,
        ).normalized()

        assertEquals(RemoteModelConnectivityStatus.Unknown, config.connectivityStatus)
    }

    @Test
    fun knownConnectivityFailureOnlyCoversFailedProbeResults() {
        assertTrue(
            RemoteModelConfig(
                "https://api.example.com/v1",
                "model-a",
                connectivityStatus = RemoteModelConnectivityStatus.AuthenticationFailed,
            ).hasKnownConnectivityFailure,
        )
        assertTrue(
            RemoteModelConfig(
                "https://api.example.com/v1",
                "model-a",
                connectivityStatus = RemoteModelConnectivityStatus.Unreachable,
            ).hasKnownConnectivityFailure,
        )
        assertFalse(
            RemoteModelConfig(
                "https://api.example.com/v1",
                "model-a",
                connectivityStatus = RemoteModelConnectivityStatus.Unknown,
            ).hasKnownConnectivityFailure,
        )
        assertFalse(
            RemoteModelConfig(
                "https://api.example.com/v1",
                "model-a",
                connectivityStatus = RemoteModelConnectivityStatus.Reachable,
            ).hasKnownConnectivityFailure,
        )
    }

    @Test
    fun isConfiguredRequiresHttpUrlAndModelName() {
        assertTrue(RemoteModelConfig("https://api.example.com/v1", "model-a").isConfigured)
        assertTrue(RemoteModelConfig("http://10.0.2.2:8000/v1", "model-a").isConfigured)
        assertTrue(RemoteModelConfig("http://localhost:8000/v1", "model-a").isConfigured)
        assertTrue(RemoteModelConfig("http://127.0.0.1:8000/v1", "model-a").isConfigured)
        assertFalse(RemoteModelConfig("ftp://api.example.com/v1", "model-a").isConfigured)
        assertFalse(RemoteModelConfig("http://api.example.com/v1", "model-a").isConfigured)
        assertFalse(RemoteModelConfig("https:foo", "model-a").isConfigured)
        assertFalse(RemoteModelConfig("https://api.example.com/v1", "").isConfigured)
    }

    @Test
    fun usesLocalInsecureTransportOnlyForLocalDebugHosts() {
        assertTrue(RemoteModelConfig("http://10.0.2.2:8000/v1", "model-a").usesLocalInsecureTransport)
        assertFalse(RemoteModelConfig("https://api.example.com/v1", "model-a").usesLocalInsecureTransport)
        assertFalse(RemoteModelConfig("http://api.example.com/v1", "model-a").usesLocalInsecureTransport)
    }
}
