package com.bytedance.zgx.solin.credentials

import com.bytedance.zgx.solin.RemoteModelConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NoOpCredentialResolverTest {
    @Test
    fun alwaysReturnsNone_regardlessOfConfig() = runTest {
        assertEquals(Credential.none(), NoOpCredentialResolver.resolve(RemoteModelConfig()))
        assertEquals(Credential.none(),
            NoOpCredentialResolver.resolve(RemoteModelConfig(apiKey = "some-key")))
        assertEquals(Credential.none(),
            NoOpCredentialResolver.resolve(RemoteModelConfig(apiKey = " ", modelName = "x")))
    }
}
