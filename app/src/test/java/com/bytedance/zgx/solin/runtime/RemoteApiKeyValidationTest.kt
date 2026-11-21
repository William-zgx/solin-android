package com.bytedance.zgx.solin.runtime

import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteApiKeyValidationTest {
    private val badKeys = listOf(
        "sk-a\r\nX-Evil: 1",
        "sk-a\nb",
        "sk-\u0000a",
        "sk-\u0007a",
        "sk-é",
        "﻿sk-a",
        "sk-​a",
        "sk-\u007Fa",
        "sk-a\tb",
        "sk-1\n",
        "\r\nsk-1",
        "sk-1\t",
    )

    private fun config(key: String, baseUrl: String = "https://api.example.com/v1") =
        RemoteModelConfig(baseUrl, "model-a", apiKey = key)

    @Test
    fun invalidKeysAreRejectedNotSanitized() {
        badKeys.forEach { key ->
            val cfg = config(key)
            assertFalse(cfg.hasValidApiKey)
            assertFalse(cfg.isConfigured)
            assertFalse(cfg.normalized().hasValidApiKey)
        }
    }

    @Test
    fun ordinarySurroundingWhitespaceIsTrimmedAndEmptyKeyIsAllowed() {
        assertTrue(config("  sk-1  ").isConfigured)
        assertEquals("sk-1", config("  sk-1  ").normalized().apiKey)
        assertTrue(config("").isConfigured)
    }

    @Test
    fun chatSendsNoRequestForInvalidKeyAndErrorOmitsSecret() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val runtime = OkHttpRemoteChatRuntime(OkHttpClient())
            val baseUrl = server.url("/v1").toString().trimEnd('/')
            badKeys.forEach { key ->
                val failure = runCatching {
                    runtime.send("hi", emptyList(), GenerationParameters(), config(key, baseUrl)).toList()
                }.exceptionOrNull()
                requireNotNull(failure)
                assertFalse(failure.message.orEmpty().contains(key.trim()))
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun probeSendsNoRequestForInvalidKey() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val baseUrl = server.url("/v1").toString().trimEnd('/')
            badKeys.forEach { key ->
                val status = OkHttpRemoteModelConnectivityProbe().check(config(key, baseUrl))
                assertEquals(RemoteModelConnectivityStatus.AuthenticationFailed, status)
            }
            assertEquals(0, server.requestCount)
        }
    }
}
