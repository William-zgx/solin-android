package com.bytedance.zgx.solin.data

import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.testing.FakeSettingsStore
import com.bytedance.zgx.solin.testing.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelRepositoryApiKeyValidationTest {
    private fun config(key: String) =
        RemoteModelConfig("https://api.example.com/v1", "model-a", apiKey = key)

    @Test
    fun saveConfigRejectsInvalidKeysWithoutStoringOrLeakingThem() {
        listOf("sk-1\n", "sk-\r\nX: 1", "sk-​z", "sk-é").forEach { key ->
            val settings = FakeSettingsStore()
            val repository = RemoteModelRepository(settings, InMemorySecretStore())
            val result = repository.saveConfig(config(key))
            assertTrue(result.isFailure)
            assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("sk-"))
            assertEquals(RemoteModelConfig(), settings.remoteConfig)
        }
    }

    @Test
    fun saveConfigWithoutApiKeyRejectsInvalidKeyInRequest() {
        val repository = RemoteModelRepository(FakeSettingsStore(), InMemorySecretStore())
        assertTrue(repository.saveConfigWithoutApiKey(config("sk-1\n")).isFailure)
    }

    @Test
    fun saveConfigStillTrimsSurroundingSpaces() {
        val repository = RemoteModelRepository(FakeSettingsStore(), InMemorySecretStore())
        assertEquals("sk-1", repository.saveConfig(config("  sk-1  ")).getOrThrow().apiKey)
    }
}
