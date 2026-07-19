package com.bytedance.zgx.solin.runtime

import com.bytedance.zgx.solin.RemoteModelConfig
import java.net.URI

internal fun RemoteModelConfig.chatCompletionsUrl(): String =
    baseUrl.trimEnd('/').let { normalizedBaseUrl ->
        when {
            normalizedBaseUrl.endsWith("/chat/completions") -> normalizedBaseUrl
            normalizedBaseUrl.endsWith("/v1") -> "$normalizedBaseUrl/chat/completions"
            normalizedBaseUrl.hasNoPathSegment() -> "$normalizedBaseUrl/v1/chat/completions"
            else -> "$normalizedBaseUrl/chat/completions"
        }
    }

internal fun RemoteModelConfig.modelsUrl(): String =
    baseUrl.trimEnd('/').let { normalizedBaseUrl ->
        when {
            normalizedBaseUrl.endsWith("/models") -> normalizedBaseUrl
            normalizedBaseUrl.endsWith("/chat/completions") ->
                normalizedBaseUrl.removeSuffix("/chat/completions") + "/models"
            normalizedBaseUrl.endsWith("/v1") -> "$normalizedBaseUrl/models"
            normalizedBaseUrl.hasNoPathSegment() -> "$normalizedBaseUrl/v1/models"
            else -> "$normalizedBaseUrl/models"
        }
    }

private fun String.hasNoPathSegment(): Boolean =
    runCatching {
        val path = URI(this).rawPath.orEmpty()
        path.isBlank() || path == "/"
    }.getOrDefault(false)
