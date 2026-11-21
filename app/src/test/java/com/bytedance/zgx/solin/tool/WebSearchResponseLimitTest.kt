package com.bytedance.zgx.solin.tool

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WebSearchResponseLimitTest {
    @Test
    fun readBoundedTextFailsOnceLimitIsExceeded() {
        val ok = "a".repeat(100).toResponseBody()
        assertEquals(100, ok.readBoundedText(100).length)
        val failure = runCatching { "a".repeat(101).toResponseBody().readBoundedText(100) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(failure!!.message.orEmpty().contains("aaa"))
    }

    @Test
    fun gzipBombIsLimitedOnDecompressedSize() {
        val raw = Buffer()
        GzipSink(raw).buffer().use { it.writeUtf8("a".repeat(MAX_WEB_SEARCH_RESPONSE_BYTES.toInt() + 1024)) }
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(200).addHeader("Content-Encoding", "gzip").body(raw).build(),
            )
            server.enqueue(
                MockResponse.Builder().code(200).addHeader("Content-Encoding", "gzip").body(raw.clone()).build(),
            )
            server.start()
            assertTrue(raw.size < 64 * 1024)
            val provider = OkHttpWebSearchProvider(
                client = OkHttpClient(),
                instantAnswerBaseUrl = server.url("/instant").toString(),
                htmlSearchBaseUrl = server.url("/html").toString(),
                liteSearchBaseUrl = server.url("/lite").toString(),
                clock = Clock.fixed(Instant.parse("2026-07-02T10:00:00Z"), ZoneOffset.UTC),
            )
            val result = provider.search(WebSearchRequest(query = "kotlin coroutine guide"))
            assertFalse(result is WebSearchReadResult.Available)
        }
    }

    @Test
    fun boundedTextCountsCodePointsAndDoesNotSplitSurrogatePairs() {
        val text = "ab\uD83D\uDE00cd"
        assertEquals("ab\uD83D\uDE00...", text.boundedText(3))
        assertEquals("ab\uD83D\uDE00c...", text.boundedText(4))
        assertEquals(text, text.boundedText(5))
        assertEquals("abc", "abc".boundedText(3))
    }

    @Test
    fun readBoundedTextHonorsUtf16Bom() {
        val bytes = Buffer().writeByte(0xFE).writeByte(0xFF).write("h\u00e9llo".toByteArray(Charsets.UTF_16BE)).readByteString()
        val body = bytes.toResponseBody("text/html; charset=utf-8".toMediaType())
        assertEquals("h\u00e9llo", body.readBoundedText(100))
    }
}
