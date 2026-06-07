package com.bytedance.zgx.pocketmind.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.GenerationParameters
import com.bytedance.zgx.pocketmind.InferenceMode
import com.bytedance.zgx.pocketmind.RemoteModelConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.pocketMindDataStore by preferencesDataStore(name = "pocketmind_settings")

class PreferenceSettingsStore(context: Context) : SettingsStore, ActiveSessionStore {
    private val dataStore = context.applicationContext.pocketMindDataStore

    override fun isSetupDismissed(): Boolean =
        readBoolean(Keys.FIRST_RUN_DISMISSED, false)

    override fun markSetupDismissed() {
        writeBoolean(Keys.FIRST_RUN_DISMISSED, true)
    }

    fun setSetupDismissedForTesting(dismissed: Boolean) {
        writeBoolean(Keys.FIRST_RUN_DISMISSED, dismissed)
    }

    override fun isMemoryEnabled(): Boolean =
        readBoolean(Keys.MEMORY_ENABLED, true)

    override fun setMemoryEnabled(enabled: Boolean) {
        writeBoolean(Keys.MEMORY_ENABLED, enabled)
    }

    override fun loadGenerationParameters(): GenerationParameters =
        GenerationParameters(
            temperature = readFloat(Keys.TEMPERATURE, GenerationParameters.DEFAULT_TEMPERATURE),
            topP = readFloat(Keys.TOP_P, GenerationParameters.DEFAULT_TOP_P),
            topK = readInt(Keys.TOP_K, GenerationParameters.DEFAULT_TOP_K),
        ).normalized()

    override fun saveGenerationParameters(parameters: GenerationParameters): GenerationParameters {
        val normalized = parameters.normalized()
        runBlocking {
            dataStore.edit { prefs ->
                prefs[Keys.TEMPERATURE] = normalized.temperature
                prefs[Keys.TOP_P] = normalized.topP
                prefs[Keys.TOP_K] = normalized.topK
            }
        }
        return normalized
    }

    override fun loadInferenceMode(): InferenceMode =
        runCatching {
            InferenceMode.valueOf(readString(Keys.INFERENCE_MODE, InferenceMode.Local.name))
        }.getOrDefault(InferenceMode.Local)

    override fun saveInferenceMode(mode: InferenceMode): InferenceMode {
        writeString(Keys.INFERENCE_MODE, mode.name)
        return mode
    }

    override fun loadBackend(): BackendChoice =
        runCatching {
            BackendChoice.valueOf(readString(Keys.BACKEND, BackendChoice.GPU.name))
        }.getOrDefault(BackendChoice.GPU)

    override fun saveBackend(backend: BackendChoice) {
        writeString(Keys.BACKEND, backend.name)
    }

    override fun activeSessionId(): String? =
        readString(Keys.ACTIVE_SESSION_ID, "").takeIf { it.isNotBlank() }

    override fun saveActiveSessionId(sessionId: String) {
        writeString(Keys.ACTIVE_SESSION_ID, sessionId)
    }

    fun selectedModelId(): String? =
        readString(Keys.SELECTED_MODEL_ID, "").takeIf { it.isNotBlank() }

    fun saveSelectedModelId(modelId: String) {
        writeString(Keys.SELECTED_MODEL_ID, modelId)
    }

    fun activeInstalledModelId(): String? =
        readString(Keys.ACTIVE_INSTALLED_MODEL_ID, "").takeIf { it.isNotBlank() }

    fun saveActiveInstalledModelId(modelId: String?) {
        writeString(Keys.ACTIVE_INSTALLED_MODEL_ID, modelId.orEmpty())
    }

    override fun loadRemoteConfig(apiKey: String): RemoteModelConfig =
        RemoteModelConfig(
            baseUrl = readString(Keys.REMOTE_BASE_URL, ""),
            modelName = readString(Keys.REMOTE_MODEL_NAME, ""),
            apiKey = apiKey,
            supportsVisionInput = readBoolean(Keys.REMOTE_SUPPORTS_VISION_INPUT, false),
        ).normalized()

    override fun saveRemoteConfig(config: RemoteModelConfig): RemoteModelConfig {
        val normalized = config.normalized()
        runBlocking {
            dataStore.edit { prefs ->
                prefs[Keys.REMOTE_BASE_URL] = normalized.baseUrl
                prefs[Keys.REMOTE_MODEL_NAME] = normalized.modelName
                prefs[Keys.REMOTE_SUPPORTS_VISION_INPUT] = normalized.supportsVisionInput
            }
        }
        return normalized
    }

    fun migrationVersion(): Int =
        readInt(Keys.MIGRATION_VERSION, 0)

    fun setMigrationVersion(version: Int) {
        writeInt(Keys.MIGRATION_VERSION, version)
    }

    private fun readBoolean(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, default: Boolean): Boolean =
        runBlocking { dataStore.data.first()[key] ?: default }

    private fun writeBoolean(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        runBlocking { dataStore.edit { it[key] = value } }
    }

    private fun readFloat(key: androidx.datastore.preferences.core.Preferences.Key<Float>, default: Float): Float =
        runBlocking { dataStore.data.first()[key] ?: default }

    private fun readInt(key: androidx.datastore.preferences.core.Preferences.Key<Int>, default: Int): Int =
        runBlocking { dataStore.data.first()[key] ?: default }

    private fun writeInt(key: androidx.datastore.preferences.core.Preferences.Key<Int>, value: Int) {
        runBlocking { dataStore.edit { it[key] = value } }
    }

    private fun readString(key: androidx.datastore.preferences.core.Preferences.Key<String>, default: String): String =
        runBlocking { dataStore.data.first()[key] ?: default }

    private fun writeString(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        runBlocking { dataStore.edit { it[key] = value } }
    }

    private object Keys {
        val MIGRATION_VERSION = intPreferencesKey("migration_version")
        val FIRST_RUN_DISMISSED = booleanPreferencesKey("first_run_setup_dismissed")
        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        val TEMPERATURE = floatPreferencesKey("generation_temperature")
        val TOP_P = floatPreferencesKey("generation_top_p")
        val TOP_K = intPreferencesKey("generation_top_k")
        val INFERENCE_MODE = stringPreferencesKey("inference_mode")
        val BACKEND = stringPreferencesKey("backend")
        val REMOTE_BASE_URL = stringPreferencesKey("remote_model_base_url")
        val REMOTE_MODEL_NAME = stringPreferencesKey("remote_model_name")
        val REMOTE_SUPPORTS_VISION_INPUT = booleanPreferencesKey("remote_model_supports_vision_input")
        val ACTIVE_SESSION_ID = stringPreferencesKey("active_session_id")
        val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
        val ACTIVE_INSTALLED_MODEL_ID = stringPreferencesKey("active_installed_model_id")
    }
}
