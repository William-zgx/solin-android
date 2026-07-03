package com.bytedance.zgx.solin.credentials

import com.bytedance.zgx.solin.RemoteModelConfig

/**
 * Supplies a fresh Credential for each remote/MCP call. Invoked per-send so
 * short-lived OAuth tokens can be refreshed transparently. Local (LiteRt)
 * path never calls this.
 */
fun interface CredentialResolver {
    suspend fun resolve(config: RemoteModelConfig): Credential
}
