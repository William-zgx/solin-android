package com.bytedance.zgx.solin.data

import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.RemoteModelConfig
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
                supportsVisionInput = false,
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals("https://api.example.com/v1", settingsStore.remoteConfig.baseUrl)
        assertEquals("model-a", settingsStore.remoteConfig.modelName)
        assertEquals(false, settingsStore.remoteConfig.supportsVisionInput)
        assertEquals("secret", secretStore.loadString("remote_model_api_key").getOrThrow())
    }

    @Test
    fun saveConfigWithBlankApiKeyClearsPreviouslySavedSecret() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        val repository = RemoteModelRepository(settingsStore, secretStore)

        repository.saveConfig(
            RemoteModelConfig(
                baseUrl = "https://api.example.com/v1",
                modelName = "model-a",
                apiKey = "secret",
            ),
        ).getOrThrow()

        repository.saveConfig(
            RemoteModelConfig(
                baseUrl = "https://api.example.com/v1",
                modelName = "model-a",
                apiKey = " ",
            ),
        ).getOrThrow()

        assertEquals("", secretStore.loadString("remote_model_api_key").getOrThrow())
        assertEquals("", repository.loadConfig().apiKey)
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

        override fun selectedModelId(): String? = null
        override fun saveSelectedModelId(modelId: String) = Unit
        override fun activeInstalledModelId(): String? = null
        override fun saveActiveInstalledModelId(modelId: String?) = Unit
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
