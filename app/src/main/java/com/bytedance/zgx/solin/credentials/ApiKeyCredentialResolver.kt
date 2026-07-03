package com.bytedance.zgx.solin.credentials

import com.bytedance.zgx.solin.RemoteModelConfig

/**
 * Resolver that returns the apiKey already present on RemoteModelConfig
 * (the legacy Settings/EncryptedSecretStore path). Used as the default so
 * existing installations need no migration.
 */
object ApiKeyCredentialResolver : CredentialResolver {
    override suspend fun resolve(config: RemoteModelConfig): Credential =
        if (config.apiKey.isBlank()) Credential.none() else Credential.ApiKey(config.apiKey)
}
