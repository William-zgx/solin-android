package com.bytedance.zgx.solin.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.PendingRemoteSendMarker
import com.bytedance.zgx.solin.ReasoningEffort
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.RemoteSendDisclosureKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

private val Context.solinDataStore by preferencesDataStore(name = "solin_settings")

internal fun decodeInferenceMode(raw: String?): InferenceMode =
    runCatching { InferenceMode.valueOf(raw.orEmpty()) }.getOrDefault(InferenceMode.Local)

/** DataStore-backed settings, active-session id and pending remote-send marker, in one `solin_settings` store. */
class PreferenceSettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore, ActiveSessionStore, RemoteSendPendingStore {
    constructor(context: Context) : this(context.applicationContext.solinDataStore)

    override fun isSetupDismissed(): Boolean =
        read(Keys.FIRST_RUN_DISMISSED, false)

    override fun markSetupDismissed() {
        write(Keys.FIRST_RUN_DISMISSED, true)
    }

    fun clearSetupDismissed() {
        write(Keys.FIRST_RUN_DISMISSED, false)
    }

    override fun isMemoryEnabled(): Boolean =
        read(Keys.MEMORY_ENABLED, true)

    override fun setMemoryEnabled(enabled: Boolean) {
        write(Keys.MEMORY_ENABLED, enabled)
    }

    override fun reduceDeviceActionConfirmations(): Boolean =
        read(Keys.REDUCE_DEVICE_ACTION_CONFIRMATIONS, true)

    override fun setReduceDeviceActionConfirmations(enabled: Boolean) {
        write(Keys.REDUCE_DEVICE_ACTION_CONFIRMATIONS, enabled)
    }

    override fun remoteGuiAutomationEnabled(): Boolean =
        read(Keys.REMOTE_GUI_AUTOMATION_ENABLED, false)

    override fun setRemoteGuiAutomationEnabled(enabled: Boolean) {
        write(Keys.REMOTE_GUI_AUTOMATION_ENABLED, enabled)
    }

    override fun loadGenerationParameters(): GenerationParameters {
        val prefs = snapshot()
        return GenerationParameters(
            temperature = prefs[Keys.TEMPERATURE] ?: GenerationParameters.DEFAULT_TEMPERATURE,
            topP = prefs[Keys.TOP_P] ?: GenerationParameters.DEFAULT_TOP_P,
            topK = prefs[Keys.TOP_K] ?: GenerationParameters.DEFAULT_TOP_K,
            reasoningEffort = reasoningEffortFromOrdinal(
                prefs[Keys.REASONING_EFFORT_ORDINAL]
                    ?: GenerationParameters.DEFAULT_REASONING_EFFORT_ORDINAL,
            ),
        ).normalized()
    }

    override fun saveGenerationParameters(parameters: GenerationParameters): GenerationParameters {
        val normalized = parameters.normalized()
        runBlocking {
            dataStore.edit { prefs ->
                prefs[Keys.TEMPERATURE] = normalized.temperature
                prefs[Keys.TOP_P] = normalized.topP
                prefs[Keys.TOP_K] = normalized.topK
                prefs[Keys.REASONING_EFFORT_ORDINAL] = normalized.reasoningEffort.ordinal
            }
        }
        return normalized
    }

    override fun loadInferenceMode(): InferenceMode =
        decodeInferenceMode(read(Keys.INFERENCE_MODE, InferenceMode.Local.name))

    override fun saveInferenceMode(mode: InferenceMode): InferenceMode {
        write(Keys.INFERENCE_MODE, mode.name)
        return mode
    }

    override fun loadBackend(): BackendChoice =
        runCatching {
            BackendChoice.valueOf(read(Keys.BACKEND, BackendChoice.GPU.name))
        }.getOrDefault(BackendChoice.GPU)

    override fun saveBackend(backend: BackendChoice) {
        write(Keys.BACKEND, backend.name)
    }

    override fun activeSessionId(): String? =
        read(Keys.ACTIVE_SESSION_ID, "").takeIf { it.isNotBlank() }

    override fun saveActiveSessionId(sessionId: String) {
        write(Keys.ACTIVE_SESSION_ID, sessionId)
    }

    override fun selectedModelId(): String? =
        read(Keys.SELECTED_MODEL_ID, "").takeIf { it.isNotBlank() }

    override fun saveSelectedModelId(modelId: String) {
        write(Keys.SELECTED_MODEL_ID, modelId)
    }

    override fun activeInstalledModelId(): String? =
        read(Keys.ACTIVE_INSTALLED_MODEL_ID, "").takeIf { it.isNotBlank() }

    override fun saveActiveInstalledModelId(modelId: String?) {
        write(Keys.ACTIVE_INSTALLED_MODEL_ID, modelId.orEmpty())
    }

    override fun loadRemoteConfig(apiKey: String): RemoteModelConfig {
        val prefs = snapshot()
        return RemoteModelConfig(
            baseUrl = prefs[Keys.REMOTE_BASE_URL] ?: "",
            modelName = prefs[Keys.REMOTE_MODEL_NAME] ?: "",
            apiKey = apiKey,
            supportsVisionInput = prefs[Keys.REMOTE_SUPPORTS_VISION_INPUT] ?: false,
            supportsToolCalls = prefs[Keys.REMOTE_SUPPORTS_TOOL_CALLS] ?: false,
            contextWindowTokens = (prefs[Keys.REMOTE_CONTEXT_WINDOW_TOKENS] ?: 0).takeIf { it > 0 },
            profileRevision = prefs[Keys.REMOTE_PROFILE_REVISION] ?: "",
            connectivityStatus = RemoteModelConnectivityStatus.Unknown,
        ).normalized()
    }

    override fun saveRemoteConfig(config: RemoteModelConfig): RemoteModelConfig {
        val normalized = config.normalized()
        runBlocking {
            dataStore.edit { prefs ->
                prefs[Keys.REMOTE_BASE_URL] = normalized.baseUrl
                prefs[Keys.REMOTE_MODEL_NAME] = normalized.modelName
                prefs[Keys.REMOTE_SUPPORTS_VISION_INPUT] = normalized.supportsVisionInput
                prefs[Keys.REMOTE_SUPPORTS_TOOL_CALLS] = normalized.supportsToolCalls
                normalized.contextWindowTokens?.let { value ->
                    prefs[Keys.REMOTE_CONTEXT_WINDOW_TOKENS] = value
                } ?: prefs.remove(Keys.REMOTE_CONTEXT_WINDOW_TOKENS)
                prefs[Keys.REMOTE_PROFILE_REVISION] = normalized.profileRevision
                prefs.remove(Keys.REMOTE_CONNECTIVITY_STATUS)
            }
        }
        return normalized.copy(connectivityStatus = RemoteModelConnectivityStatus.Unknown)
    }

    override fun savePendingRemoteSend(marker: PendingRemoteSendMarker) {
        write(
            Keys.PENDING_REMOTE_SEND_MARKER,
            JSONObject()
                .put("kind", marker.kind.name)
                .put("remoteModelName", marker.remoteModelName)
                .put("remoteHistoryCount", marker.remoteHistoryCount)
                .put("localOnlyHistoryFilteredCount", marker.localOnlyHistoryFilteredCount)
                .put("imageAttachmentCount", marker.imageAttachmentCount)
                .put("protectedSourceCount", marker.protectedSourceCount)
                .put("runId", marker.runId.orEmpty())
                .put("remoteProfileRevision", marker.remoteProfileRevision.orEmpty())
                .put("createdAtMillis", marker.createdAtMillis)
                .toString(),
        )
    }

    override fun consumePendingRemoteSend(): PendingRemoteSendMarker? {
        val raw = read(Keys.PENDING_REMOTE_SEND_MARKER, "")
        if (raw.isBlank()) return null
        clearPendingRemoteSend()
        return runCatching {
            val json = JSONObject(raw)
            PendingRemoteSendMarker(
                kind = RemoteSendDisclosureKind.valueOf(json.getString("kind")),
                remoteModelName = json.optString("remoteModelName"),
                remoteHistoryCount = json.optInt("remoteHistoryCount"),
                localOnlyHistoryFilteredCount = json.optInt("localOnlyHistoryFilteredCount"),
                imageAttachmentCount = json.optInt("imageAttachmentCount"),
                protectedSourceCount = json.optInt("protectedSourceCount"),
                runId = json.optString("runId").takeIf { it.isNotBlank() },
                remoteProfileRevision = json.optString("remoteProfileRevision").takeIf { it.isNotBlank() },
                createdAtMillis = json.optLong("createdAtMillis"),
            )
        }.getOrNull()
    }

    override fun clearPendingRemoteSend() {
        write(Keys.PENDING_REMOTE_SEND_MARKER, "")
    }

    fun migrationVersion(): Int =
        read(Keys.MIGRATION_VERSION, 0)

    fun setMigrationVersion(version: Int) {
        write(Keys.MIGRATION_VERSION, version)
    }

    /** One consistent DataStore snapshot; multi-key loaders read every key from a single one. */
    private fun snapshot(): Preferences =
        runBlocking { dataStore.data.first() }

    private fun <T> read(key: Preferences.Key<T>, default: T): T =
        snapshot()[key] ?: default

    private fun <T> write(key: Preferences.Key<T>, value: T) {
        runBlocking { dataStore.edit { it[key] = value } }
    }

    private object Keys {
        val MIGRATION_VERSION = intPreferencesKey("migration_version")
        val FIRST_RUN_DISMISSED = booleanPreferencesKey("first_run_setup_dismissed")
        val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        val REDUCE_DEVICE_ACTION_CONFIRMATIONS = booleanPreferencesKey("reduce_device_action_confirmations")
        val REMOTE_GUI_AUTOMATION_ENABLED = booleanPreferencesKey("remote_gui_automation_enabled")
        val TEMPERATURE = floatPreferencesKey("generation_temperature")
        val TOP_P = floatPreferencesKey("generation_top_p")
        val TOP_K = intPreferencesKey("generation_top_k")
        val REASONING_EFFORT_ORDINAL = intPreferencesKey("generation_reasoning_effort_ordinal")
        val INFERENCE_MODE = stringPreferencesKey("inference_mode")
        val BACKEND = stringPreferencesKey("backend")
        val REMOTE_BASE_URL = stringPreferencesKey("remote_model_base_url")
        val REMOTE_MODEL_NAME = stringPreferencesKey("remote_model_name")
        val REMOTE_SUPPORTS_VISION_INPUT = booleanPreferencesKey("remote_model_supports_vision_input")
        val REMOTE_SUPPORTS_TOOL_CALLS = booleanPreferencesKey("remote_model_supports_tool_calls")
        val REMOTE_CONTEXT_WINDOW_TOKENS = intPreferencesKey("remote_model_context_window_tokens")
        val REMOTE_PROFILE_REVISION = stringPreferencesKey("remote_model_profile_revision")
        val REMOTE_CONNECTIVITY_STATUS = stringPreferencesKey("remote_model_connectivity_status")
        val PENDING_REMOTE_SEND_MARKER = stringPreferencesKey("pending_remote_send_marker")
        val ACTIVE_SESSION_ID = stringPreferencesKey("active_session_id")
        val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
        val ACTIVE_INSTALLED_MODEL_ID = stringPreferencesKey("active_installed_model_id")
    }
}

private fun reasoningEffortFromOrdinal(ordinal: Int): ReasoningEffort {
    val values = enumValues<ReasoningEffort>()
    return if (ordinal in values.indices) values[ordinal] else ReasoningEffort.Off
}
