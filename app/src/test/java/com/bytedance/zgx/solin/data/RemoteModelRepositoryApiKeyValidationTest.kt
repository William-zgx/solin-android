package com.bytedance.zgx.solin.data

import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.testing.FakeSettingsStore
import com.bytedance.zgx.solin.testing.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelRepositoryApiKeyValidationTest {
    private fun config(key: String) = RemoteModelConfig("https://api.example.com/v1", "model-a", apiKey = key)

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
    fun saveConfigWithoutApiKeyRejectsInvalidPersistedKeyAndWritesNothing() {
        val settings = FakeSettingsStore()
        val secrets = InMemorySecretStore()
        secrets.saveString("remote_model_api_key", "sk-1\r\nX: 1").getOrThrow()
        val writesBefore = secrets.writeCount
        val repository = RemoteModelRepository(settings, secrets)

        val result = repository.saveConfigWithoutApiKey(config(""))

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("sk-"))
        assertEquals(writesBefore, secrets.writeCount)
        assertEquals(0, settings.saveCount)
    }

    @Test
    fun saveConfigStillTrimsSurroundingSpaces() {
        val repository = RemoteModelRepository(FakeSettingsStore(), InMemorySecretStore())
        assertEquals("sk-1", repository.saveConfig(config("  sk-1  ")).getOrThrow().apiKey)
    }
}
