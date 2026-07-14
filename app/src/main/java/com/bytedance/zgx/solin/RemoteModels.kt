package com.bytedance.zgx.solin

import java.net.URI

enum class InferenceMode {
    Local,
    Remote,
}

enum class RemoteModelConnectivityStatus(val label: String) {
    Unknown("未测试"),
    Checking("测试中"),
    Reachable("可达"),
    AuthenticationFailed("鉴权失败"),
    Unreachable("不可达"),
}

data class RemoteModelConfig(
    val baseUrl: String = "",
    val modelName: String = "",
    val apiKey: String = "",
    // Fail-closed: only enable vision when the model is explicitly declared vision-capable.
    // Defaulting to false prevents sending images to a remote model whose capability is unknown.
    val supportsVisionInput: Boolean = false,
    val connectivityStatus: RemoteModelConnectivityStatus = RemoteModelConnectivityStatus.Unknown,
) {
    val isConfigured: Boolean
        get() = modelName.isNotBlank() && hasAllowedTransport()

    val hasKnownConnectivityFailure: Boolean
        get() = connectivityStatus == RemoteModelConnectivityStatus.AuthenticationFailed ||
            connectivityStatus == RemoteModelConnectivityStatus.Unreachable

    val usesLocalInsecureTransport: Boolean
        get() = parsedUri()?.let { uri ->
            uri.scheme.equals("http", ignoreCase = true) && uri.host.isLocalDebugHost()
        } == true

    fun normalized(): RemoteModelConfig =
        copy(
            baseUrl = baseUrl.trim().trimEnd('/'),
            modelName = modelName.trim(),
            apiKey = apiKey.trim(),
            supportsVisionInput = supportsVisionInput,
            connectivityStatus = if (connectivityStatus == RemoteModelConnectivityStatus.Checking) {
                RemoteModelConnectivityStatus.Unknown
            } else {
                connectivityStatus
            },
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

fun RemoteModelConfig.modelProfile(): ModelProfile =
    normalized().let { normalized ->
        ModelCatalog.remoteVisionProfile.copy(
            id = "remote-openai-compatible-${normalized.modelName.ifBlank { "unconfigured" }}",
            displayName = normalized.modelName.ifBlank { "远程模型" },
            inputModalities = if (normalized.supportsVisionInput) {
                setOf(ModelInputModality.Text, ModelInputModality.Vision)
            } else {
                setOf(ModelInputModality.Text)
            },
            features = if (normalized.supportsVisionInput) {
                setOf(ModelFeature.TextGeneration, ModelFeature.VisionInput)
            } else {
                setOf(ModelFeature.TextGeneration)
            },
        )
    }

internal fun String?.isLocalDebugHost(): Boolean =
    this == "localhost" ||
        this == "127.0.0.1" ||
        this == "::1" ||
        this == "[::1]" ||
        this == "10.0.2.2"

fun InferenceMode.label(): String =
    when (this) {
        InferenceMode.Local -> "本地"
        InferenceMode.Remote -> "远程"
    }

internal fun RemoteModelConfig.destinationHostLabel(): String {
    val normalized = normalized()
    val uri = runCatching { URI(normalized.baseUrl) }.getOrNull()
    val host = uri?.host?.takeIf { it.isNotBlank() }
        ?: normalized.baseUrl.ifBlank { "未配置" }
    val port = uri?.port?.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
    return "$host$port"
}

internal fun RemoteModelConfig.hasSameConnectivityTarget(other: RemoteModelConfig): Boolean {
    val left = normalized()
    val right = other.normalized()
    return left.baseUrl == right.baseUrl &&
        left.modelName == right.modelName &&
        left.apiKey == right.apiKey
}
