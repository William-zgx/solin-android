package com.bytedance.zgx.pocketmind.data

import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.GenerationParameters
import com.bytedance.zgx.pocketmind.InferenceMode
import com.bytedance.zgx.pocketmind.RemoteModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelRepositoryTest {
    @Test
    fun saveConfigReturnsFailureWhenEncryptedSecretSaveFails() {
        val settingsStore = FakeSettingsStore()
        val repository = RemoteModelRepository(
            settingsStore = settingsStore,
            secretStore = FailingSecretStore,
        )

        val result = repository.saveConfig(
            RemoteModelConfig(
                baseUrl = " https://api.example.com/v1/ ",
                modelName = " model-a ",
                apiKey = "secret",
            ),
        )

        assertTrue(result.isFailure)
        assertEquals(RemoteModelConfig(), settingsStore.remoteConfig)
    }

    @Test
    fun saveConfigStoresNonSecretSettingsOnlyAfterSecretSaveSucceeds() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        val repository = RemoteModelRepository(settingsStore, secretStore)

        val result = repository.saveConfig(
            RemoteModelConfig(
                baseUrl = " https://api.example.com/v1/ ",
                modelName = " model-a ",
                apiKey = "secret",
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals("https://api.example.com/v1", settingsStore.remoteConfig.baseUrl)
        assertEquals("model-a", settingsStore.remoteConfig.modelName)
        assertEquals("secret", secretStore.loadString("remote_model_api_key").getOrThrow())
    }

    private class FakeSettingsStore : SettingsStore {
        var remoteConfig: RemoteModelConfig = RemoteModelConfig()

        override fun isSetupDismissed(): Boolean = false
        override fun markSetupDismissed() = Unit
        override fun isMemoryEnabled(): Boolean = true
        override fun setMemoryEnabled(enabled: Boolean) = Unit
        override fun loadGenerationParameters(): GenerationParameters = GenerationParameters()
        override fun saveGenerationParameters(parameters: GenerationParameters): GenerationParameters = parameters
        override fun loadBackend(): BackendChoice = BackendChoice.GPU
        override fun saveBackend(backend: BackendChoice) = Unit
        override fun loadInferenceMode(): InferenceMode = InferenceMode.Local
        override fun saveInferenceMode(mode: InferenceMode): InferenceMode = mode

        override fun loadRemoteConfig(apiKey: String): RemoteModelConfig =
            remoteConfig.copy(apiKey = apiKey)

        override fun saveRemoteConfig(config: RemoteModelConfig): RemoteModelConfig {
            remoteConfig = config.normalized()
            return remoteConfig
        }
    }

    private object FailingSecretStore : SecretStore {
        override fun loadString(name: String): Result<String> = Result.success("")
        override fun saveString(name: String, value: String): Result<Unit> =
            Result.failure(IllegalStateException("encrypted prefs unavailable"))
    }

    private class InMemorySecretStore : SecretStore {
        private val values = mutableMapOf<String, String>()

        override fun loadString(name: String): Result<String> =
            Result.success(values[name].orEmpty())

        override fun saveString(name: String, value: String): Result<Unit> =
            runCatching {
                if (value.isBlank()) {
                    values.remove(name)
                } else {
                    values[name] = value
                }
            }
    }
}
