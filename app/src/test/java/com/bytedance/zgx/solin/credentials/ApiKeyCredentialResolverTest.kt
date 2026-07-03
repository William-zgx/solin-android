package com.bytedance.zgx.solin.credentials

import com.bytedance.zgx.solin.RemoteModelConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyCredentialResolverTest {
    @Test
    fun resolvesConfigApiKey_toApiKeyCredential() = runTest {
        val config = RemoteModelConfig(apiKey = "secret-123")
        val cred = ApiKeyCredentialResolver.resolve(config)
        assertTrue(cred is Credential.ApiKey)
        assertEquals("secret-123", (cred as Credential.ApiKey).apiKey)
        assertEquals("Bearer secret-123", cred.authorizationHeader)
    }

    @Test
    fun resolvesBlankApiKey_toNone() = runTest {
        val blank = RemoteModelConfig(apiKey = "   ")
        assertEquals(Credential.none(), ApiKeyCredentialResolver.resolve(blank))
        val empty = RemoteModelConfig(apiKey = "")
        assertEquals(Credential.none(), ApiKeyCredentialResolver.resolve(empty))
    }
}
