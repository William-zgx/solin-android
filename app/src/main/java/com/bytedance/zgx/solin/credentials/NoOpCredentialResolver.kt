package com.bytedance.zgx.solin.credentials

import com.bytedance.zgx.solin.RemoteModelConfig

/** Always returns Credential.none() (empty API key). For tests/local-only runs. */
object NoOpCredentialResolver : CredentialResolver {
    override suspend fun resolve(config: RemoteModelConfig): Credential = Credential.none()
}
