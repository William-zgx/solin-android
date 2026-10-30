package com.bytedance.zgx.solin.testing

import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.data.SecretStore
import com.bytedance.zgx.solin.data.SettingsStore

/**
 * [SettingsStore] that only stores the remote config. Like the production store it never
 * persists the API key or a connectivity status.
 */
class FakeSettingsStore : SettingsStore {
    @Volatile
    var remoteConfig: RemoteModelConfig = RemoteModelConfig()

    @Volatile
    var failWrites: Boolean = false
    var saveCount: Int = 0

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

    @Synchronized
    override fun loadRemoteConfig(apiKey: String): RemoteModelConfig = remoteConfig.copy(apiKey = apiKey)

    @Synchronized
    override fun saveRemoteConfig(config: RemoteModelConfig): RemoteModelConfig {
        saveCount += 1
        check(!failWrites) { "settings unavailable" }
        remoteConfig = config.normalized().copy(apiKey = "", connectivityStatus = RemoteModelConnectivityStatus.Unknown)
        return remoteConfig
    }

    override fun selectedModelId(): String? = null
    override fun saveSelectedModelId(modelId: String) = Unit
    override fun activeInstalledModelId(): String? = null
    override fun saveActiveInstalledModelId(modelId: String?) = Unit
}

/** [SecretStore] backed by a map, with injectable read/write failures keyed by name or call number. */
class InMemorySecretStore(
    var failWrites: Boolean = false,
) : SecretStore {
    private val values = mutableMapOf<String, String>()
    var failReads: Boolean = false
    val failReadNames = mutableSetOf<String>()
    val failOnReadNumbers = mutableSetOf<Int>()
    val failOnWriteNumbers = mutableSetOf<Int>()

    /** Write numbers that store the value and then still report failure. */
    val failAfterWriteOnWriteNumbers = mutableSetOf<Int>()
    val writeNames = mutableListOf<String>()
    var readCount: Int = 0
        private set
    var writeCount: Int = 0
        private set

    @Synchronized
    override fun loadString(name: String): Result<String> =
        runCatching {
            readCount += 1
            check(!failReads && name !in failReadNames && readCount !in failOnReadNumbers) {
                "encrypted prefs unavailable"
            }
            values[name].orEmpty()
        }

    @Synchronized
    override fun saveString(name: String, value: String): Result<Unit> =
        runCatching {
            writeCount += 1
            writeNames += name
            check(!failWrites && writeCount !in failOnWriteNumbers) { "encrypted prefs unavailable" }
            if (value.isBlank()) values.remove(name) else values[name] = value
            check(writeCount !in failAfterWriteOnWriteNumbers) { "encrypted prefs write result unavailable" }
        }
}
