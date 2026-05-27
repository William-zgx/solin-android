package com.bytedance.zgx.pocketmind.data

import android.content.Context
import com.bytedance.zgx.pocketmind.InferenceMode
import com.bytedance.zgx.pocketmind.RemoteModelConfig

private const val PREFS_NAME = "pocketmind"
private const val PREF_INFERENCE_MODE = "inference_mode"
private const val PREF_REMOTE_BASE_URL = "remote_model_base_url"
private const val PREF_REMOTE_MODEL_NAME = "remote_model_name"
private const val PREF_REMOTE_API_KEY = "remote_model_api_key"

class RemoteModelRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadMode(): InferenceMode =
        runCatching { InferenceMode.valueOf(prefs.getString(PREF_INFERENCE_MODE, null).orEmpty()) }
            .getOrDefault(InferenceMode.Local)

    fun saveMode(mode: InferenceMode): InferenceMode {
        prefs.edit()
            .putString(PREF_INFERENCE_MODE, mode.name)
            .apply()
        return mode
    }

    fun loadConfig(): RemoteModelConfig =
        RemoteModelConfig(
            baseUrl = prefs.getString(PREF_REMOTE_BASE_URL, "").orEmpty(),
            modelName = prefs.getString(PREF_REMOTE_MODEL_NAME, "").orEmpty(),
            apiKey = prefs.getString(PREF_REMOTE_API_KEY, "").orEmpty(),
        ).normalized()

    fun saveConfig(config: RemoteModelConfig): RemoteModelConfig {
        val normalized = config.normalized()
        prefs.edit()
            .putString(PREF_REMOTE_BASE_URL, normalized.baseUrl)
            .putString(PREF_REMOTE_MODEL_NAME, normalized.modelName)
            .putString(PREF_REMOTE_API_KEY, normalized.apiKey)
            .apply()
        return normalized
    }
}
