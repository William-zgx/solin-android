package com.bytedance.zgx.solin.data

private const val PREF_HUGGING_FACE_ACCESS_TOKEN = "hugging_face_access_token"

interface HuggingFaceAuthStore {
    fun hasAccessToken(): Boolean
    fun authorizationHeader(): String?
    fun saveAccessToken(token: String): Result<Unit>
    fun clearAccessToken(): Result<Unit>
}

class HuggingFaceAuthRepository(
    private val secretStore: SecretStore,
) : HuggingFaceAuthStore {
    override fun hasAccessToken(): Boolean =
        loadAccessToken().isNotBlank()

    override fun authorizationHeader(): String? =
        loadAccessToken()
            .takeIf { it.isNotBlank() }
            ?.let { token -> "Bearer $token" }

    override fun saveAccessToken(token: String): Result<Unit> =
        runCatching {
            val normalized = token.normalizedHuggingFaceToken()
            require(normalized.length >= MIN_HUGGING_FACE_TOKEN_LENGTH) {
                "请输入 Hugging Face read token"
            }
            secretStore.saveString(PREF_HUGGING_FACE_ACCESS_TOKEN, normalized).getOrThrow()
        }

    override fun clearAccessToken(): Result<Unit> =
        secretStore.saveString(PREF_HUGGING_FACE_ACCESS_TOKEN, "")

    private fun loadAccessToken(): String =
        secretStore.loadString(PREF_HUGGING_FACE_ACCESS_TOKEN)
            .getOrDefault("")
            .normalizedHuggingFaceToken()

    private companion object {
        const val MIN_HUGGING_FACE_TOKEN_LENGTH = 8
    }
}

object NoOpHuggingFaceAuthStore : HuggingFaceAuthStore {
    override fun hasAccessToken(): Boolean = false
    override fun authorizationHeader(): String? = null
    override fun saveAccessToken(token: String): Result<Unit> =
        Result.failure(IllegalStateException("Hugging Face 授权不可用"))

    override fun clearAccessToken(): Result<Unit> = Result.success(Unit)
}

private fun String.normalizedHuggingFaceToken(): String =
    trim()
        .removePrefix("Bearer ")
        .removePrefix("bearer ")
        .trim()
