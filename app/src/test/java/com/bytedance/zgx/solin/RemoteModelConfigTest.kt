package com.bytedance.zgx.solin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun capabilityDefaultsFailClosedAndContextWindowNormalizes() {
        val defaults = RemoteModelConfig()

        assertFalse(defaults.supportsToolCalls)
        assertNull(defaults.contextWindowTokens)
        assertNull(RemoteModelConfig(contextWindowTokens = 0).normalized().contextWindowTokens)
        assertNull(RemoteModelConfig(contextWindowTokens = -1).normalized().contextWindowTokens)
        assertEquals(8_192, RemoteModelConfig(contextWindowTokens = 8_192).normalized().contextWindowTokens)
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

    @Test
    fun modelProfileCarriesRemoteSendBoundary() {
        val profile = RemoteModelConfig(
            baseUrl = "https://api.example.com/v1",
            modelName = "model-a",
        ).modelProfile()

        assertTrue(profile.remoteEligible)
        assertTrue(profile.requiresRemoteSendConfirmation)
        assertFalse(profile.supportsVisionInput)
    }

    @Test
    fun modelProfileProjectsContextWithoutInventingToolFeature() {
        val profile = RemoteModelConfig(
            modelName = "model-a",
            supportsToolCalls = true,
            contextWindowTokens = 16_384,
        ).modelProfile()

        assertEquals(16_384, profile.tokenBudget)
        assertEquals(setOf(ModelFeature.TextGeneration), profile.features)
    }

    @Test
    fun connectivitySnapshotUsesHalfOpenElapsedRealtimeWindow() {
        val snapshot = RemoteConnectivitySnapshot(
            configRevision = "revision-1",
            status = RemoteModelConnectivityStatus.Reachable,
            checkedAtElapsedRealtimeMs = 10_000L,
        )

        assertFalse(snapshot.isFresh(9_999L))
        assertTrue(snapshot.isFresh(10_000L))
        assertTrue(snapshot.isFresh(69_999L))
        assertFalse(snapshot.isFresh(70_000L))
    }

    @Test
    fun connectivityTargetIncludesCapabilityDeclarations() {
        val config = RemoteModelConfig(
            baseUrl = "https://api.example.com/v1",
            modelName = "model-a",
            apiKey = "secret",
            supportsVisionInput = false,
            supportsToolCalls = false,
            contextWindowTokens = 8_192,
        )

        assertTrue(config.hasSameConnectivityTarget(config.copy()))
        assertFalse(config.hasSameConnectivityTarget(config.copy(supportsVisionInput = true)))
        assertFalse(config.hasSameConnectivityTarget(config.copy(supportsToolCalls = true)))
        assertFalse(config.hasSameConnectivityTarget(config.copy(contextWindowTokens = 16_384)))
    }
}
