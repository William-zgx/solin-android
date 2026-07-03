package com.bytedance.zgx.solin.credentials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialTest {
    @Test
    fun apiKey_authorizationHeader_isBearerPlusKey() {
        assertEquals("Bearer xyz", Credential.ApiKey("xyz").authorizationHeader)
    }

    @Test
    fun apiKey_isNeverExpired() {
        assertFalse(Credential.ApiKey("xyz").isExpired)
        assertFalse(Credential.ApiKey("").isExpired)
    }

    @Test
    fun oauth_withoutExpiresAt_isNotExpired() {
        val oauth = Credential.OAuth(accessToken = "tok", expiresAtEpochMillis = null)
        assertFalse(oauth.isExpired)
    }

    @Test
    fun oauth_withPastExpiresAt_isExpired() {
        val past = System.currentTimeMillis() - 60_000L
        val oauth = Credential.OAuth(accessToken = "tok", expiresAtEpochMillis = past)
        assertTrue(oauth.isExpired)
    }

    @Test
    fun oauth_authorizationHeader_respectsTokenType() {
        val defaultType = Credential.OAuth(accessToken = "tok")
        assertEquals("Bearer tok", defaultType.authorizationHeader)
        val customType = Credential.OAuth(accessToken = "tok", tokenType = "Basic")
        assertEquals("Basic tok", customType.authorizationHeader)
    }

    @Test
    fun none_returnsApiKeyWithEmptyKey() {
        val c = Credential.none()
        assertTrue(c is Credential.ApiKey)
        assertEquals("Bearer ", c.authorizationHeader)
    }
}
