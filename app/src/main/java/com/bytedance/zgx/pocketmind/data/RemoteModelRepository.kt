package com.bytedance.zgx.pocketmind.data

import android.content.Context
import com.bytedance.zgx.pocketmind.InferenceMode
import com.bytedance.zgx.pocketmind.RemoteModelConfig

private const val LEGACY_PREFS_NAME = "pocketmind"
private const val PREF_REMOTE_API_KEY = "remote_model_api_key"

class RemoteModelRepository(
    private val settingsStore: SettingsStore,
    private val secretStore: SecretStore,
    context: Context? = null,
) {
    constructor(context: Context) : this(
        PreferenceSettingsStore(context),
        EncryptedSecretStore(context),
        context,
    )

    private val legacyPrefs = context?.applicationContext?.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    fun loadMode(): InferenceMode =
        settingsStore.loadInferenceMode()

    fun saveMode(mode: InferenceMode): InferenceMode =
        settingsStore.saveInferenceMode(mode)

    fun loadConfig(): RemoteModelConfig =
        settingsStore.loadRemoteConfig(loadApiKey().getOrDefault(""))

    fun saveConfig(config: RemoteModelConfig): Result<RemoteModelConfig> =
        runCatching {
            val normalized = config.normalized()
            secretStore.saveString(PREF_REMOTE_API_KEY, normalized.apiKey).getOrThrow()
            settingsStore.saveRemoteConfig(normalized)
            legacyPrefs?.edit()?.remove(PREF_REMOTE_API_KEY)?.apply()
            normalized
        }

    private fun loadApiKey(): Result<String> {
        val plaintextLegacy = legacyPrefs?.getString(PREF_REMOTE_API_KEY, "").orEmpty()
        if (plaintextLegacy.isNotBlank()) {
            val saved = secretStore.saveString(PREF_REMOTE_API_KEY, plaintextLegacy)
            if (saved.isSuccess) {
                legacyPrefs?.edit()?.remove(PREF_REMOTE_API_KEY)?.apply()
            }
            return saved.map { plaintextLegacy }
        }
        return secretStore.loadString(PREF_REMOTE_API_KEY)
    }
}
