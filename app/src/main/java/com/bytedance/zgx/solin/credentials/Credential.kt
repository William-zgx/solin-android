package com.bytedance.zgx.solin.credentials

/**
 * A resolved credential for a remote model/MCP call. Either a static API key
 * or a short-lived OAuth bearer with optional refresh metadata.
 */
sealed class Credential {
    abstract val authorizationHeader: String // "Bearer <token>" fully formed
    abstract val isExpired: Boolean

    /** Static API key; never expires. */
    data class ApiKey(val apiKey: String) : Credential() {
        override val authorizationHeader: String get() = "Bearer $apiKey"
        override val isExpired: Boolean get() = false
    }

    /**
     * OAuth 2.0 bearer token with optional refresh. Refresh is NOT performed
     * automatically by CredentialResolver; callers invoke refreshWith(refresher)
     * if isExpired is true and a refresher is registered. Skeleton for future
     * OAuth providers (Google/Azure/OpenAI-compatible).
     */
    data class OAuth(
        val accessToken: String,
        val refreshToken: String? = null,
        val expiresAtEpochMillis: Long? = null,
        val tokenType: String = "Bearer",
        val scopes: Set<String> = emptySet(),
    ) : Credential() {
        override val authorizationHeader: String get() = "$tokenType $accessToken"
        override val isExpired: Boolean
            get() = expiresAtEpochMillis?.let { it <= System.currentTimeMillis() } ?: false
    }

    companion object {
        fun none(): Credential = ApiKey("")
    }
}
