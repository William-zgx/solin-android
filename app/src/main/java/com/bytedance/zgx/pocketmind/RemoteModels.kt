package com.bytedance.zgx.pocketmind

enum class InferenceMode {
    Local,
    Remote,
}

data class RemoteModelConfig(
    val baseUrl: String = "",
    val modelName: String = "",
    val apiKey: String = "",
) {
    val isConfigured: Boolean
        get() = (baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) &&
            modelName.isNotBlank()

    fun normalized(): RemoteModelConfig =
        copy(
            baseUrl = baseUrl.trim().trimEnd('/'),
            modelName = modelName.trim(),
            apiKey = apiKey.trim(),
        )
}

fun InferenceMode.label(): String =
    when (this) {
        InferenceMode.Local -> "本地"
        InferenceMode.Remote -> "远程"
    }
