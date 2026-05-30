package com.bytedance.zgx.pocketmind

import java.net.URI

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
        get() = modelName.isNotBlank() && hasAllowedTransport()

    val usesLocalInsecureTransport: Boolean
        get() = parsedUri()?.let { uri ->
            uri.scheme.equals("http", ignoreCase = true) && uri.host.isLocalDebugHost()
        } == true

    fun normalized(): RemoteModelConfig =
        copy(
            baseUrl = baseUrl.trim().trimEnd('/'),
            modelName = modelName.trim(),
            apiKey = apiKey.trim(),
        )

    private fun hasAllowedTransport(): Boolean {
        val uri = parsedUri() ?: return false
        return when (uri.scheme?.lowercase()) {
            "https" -> uri.host?.isNotBlank() == true
            "http" -> uri.host.isLocalDebugHost()
            else -> false
        }
    }

    private fun parsedUri(): URI? =
        runCatching { URI(baseUrl.trim()) }.getOrNull()
}

private fun String?.isLocalDebugHost(): Boolean =
    this == "localhost" ||
        this == "127.0.0.1" ||
        this == "::1" ||
        this == "10.0.2.2"

fun InferenceMode.label(): String =
    when (this) {
        InferenceMode.Local -> "本地"
        InferenceMode.Remote -> "远程"
    }
