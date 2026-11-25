package com.bytedance.zgx.solin.tool

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.GzipSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun gzipBodyIsLimitedOnDecompressedSizeWithAnExplicitFailure() {
        MockWebServer().use { server ->
            server.enqueue(gzipResponse("a".repeat(MAX_WEB_SEARCH_RESPONSE_BYTES.toInt() + 1024)))
            server.start()
            OkHttpClient().newCall(Request.Builder().url(server.url("/")).build()).execute().use { response ->
                val failure = runCatching { response.body.readBoundedText(MAX_WEB_SEARCH_RESPONSE_BYTES) }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertEquals("Response too large", failure!!.message)
            }
        }
    }

    @Test
    fun validHtmlOverTheLimitIsRejectedWhileTheSameShapeUnderTheLimitIsAvailable() {
        fun search(padding: Int): WebSearchReadResult = MockWebServer().use { server ->
            val html = """<html><body><div class="result"><h2><a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Fk">Kotlin guide</a></h2>""" +
                """<a class="result__snippet">Kotlin coroutine dispatcher guide</a></div><!--${"x".repeat(padding)}--></body></html>"""
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("{}").build())
            server.enqueue(gzipResponse(html, "text/html; charset=utf-8"))
            server.start()
            OkHttpWebSearchProvider(
                client = OkHttpClient(),
                instantAnswerBaseUrl = server.url("/instant").toString(),
                htmlSearchBaseUrl = server.url("/html").toString(),
                liteSearchBaseUrl = server.url("/lite").toString(),
                clock = Clock.fixed(Instant.parse("2026-07-02T10:00:00Z"), ZoneOffset.UTC)
            ).search(WebSearchRequest(query = "kotlin coroutine dispatcher guide"))
        }
        assertTrue(search(1_000) is WebSearchReadResult.Available)
        assertFalse(search(MAX_WEB_SEARCH_RESPONSE_BYTES.toInt() + 1024) is WebSearchReadResult.Available)
    }

    @Test
    fun declaredContentLengthOverTheLimitFailsBeforeReading() {
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = 101L
            override fun source(): BufferedSource = throw AssertionError("body must not be read")
        }
        val failure = runCatching { body.readBoundedText(100) }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test
    fun readBoundedTextHonorsUtf32Bom() {
        val be = Buffer().write("0000feff".decodeHex()).write("h\u00e9llo".toByteArray(Charsets.UTF_32BE)).readByteString()
        val le = Buffer().write("fffe0000".decodeHex()).write("h\u00e9llo".toByteArray(Charsets.UTF_32LE)).readByteString()
        assertEquals("h\u00e9llo", be.toResponseBody("text/html; charset=utf-8".toMediaType()).readBoundedText(100))
        assertEquals("h\u00e9llo", le.toResponseBody("text/html; charset=utf-8".toMediaType()).readBoundedText(100))
    }

    private fun gzipResponse(text: String, contentType: String = "text/plain"): MockResponse {
        val raw = Buffer()
        GzipSink(raw).buffer().use { it.writeUtf8(text) }
        return MockResponse.Builder().code(200).addHeader("Content-Type", contentType)
            .addHeader("Content-Encoding", "gzip").body(raw).build()
    }

    @Test
    fun boundedTextBoundsUtf16LengthAndDoesNotSplitSurrogatePairs() {
        val text = "ab\uD83D\uDE00cd"
        assertEquals("ab...", text.boundedText(3))
        assertEquals("ab\uD83D\uDE00...", text.boundedText(4))
        assertEquals("ab\uD83D\uDE00c...", text.boundedText(5))
        assertEquals(text, text.boundedText(6))
        assertEquals("abc", "abc".boundedText(3))

        val emojiHeavy = "\uD83D\uDE00".repeat(1_200).boundedText(1_200)
        assertTrue("UTF-16 length ${emojiHeavy.length} must stay within schema maxLength 1203", emojiHeavy.length <= 1_203)
        assertFalse(Character.isHighSurrogate(emojiHeavy.removeSuffix("...").last()))
    }

    @Test
    fun oversizedQueryFallbackEvidenceJsonStaysValidAndWithinSchemaLimit() {
        val longQuery = "q".repeat(5_000)
        val request = WebSearchRequest(query = longQuery)
        val json = request.evidenceJson(
            retrievedAt = Instant.parse("2026-07-02T10:00:00Z"),
            sources = org.json.JSONArray(),
            results = org.json.JSONArray()
        )
        assertTrue("fallback evidence JSON length ${json.length} must stay <= 4003", json.length <= 4_003)
        val parsed = org.json.JSONObject(json)
        assertTrue(parsed.getString("query").endsWith("..."))
    }

    @Test
    fun readBoundedTextHonorsUtf16Bom() {
        val bytes = Buffer().writeByte(0xFE).writeByte(0xFF).write("h\u00e9llo".toByteArray(Charsets.UTF_16BE)).readByteString()
        val body = bytes.toResponseBody("text/html; charset=utf-8".toMediaType())
        assertEquals("h\u00e9llo", body.readBoundedText(100))
    }
}
