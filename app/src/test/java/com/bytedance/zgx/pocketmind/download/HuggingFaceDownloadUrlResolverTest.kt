package com.bytedance.zgx.pocketmind.download

import com.bytedance.zgx.pocketmind.data.ModelDownloadSource
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceDownloadUrlResolverTest {
    @Test
    fun prepareKeepsNonGatedUrlWithoutAuthorization() {
        val source = source(downloadUrl = "https://models.example.com/model.litertlm")
        val resolver = HuggingFaceDownloadUrlResolver(OkHttpClient())

        val prepared = resolver.prepare(source) { error("authorization should not be requested") }.getOrThrow()

        assertEquals(source.downloadUrl, prepared.url)
        assertNull(prepared.authorizationHeader)
    }

    @Test
    fun prepareResolvesGatedUrlWithAuthorizationAndDropsHeaderForSignedUrl() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", server.url("/signed/model.tflite").toString())
                    .build(),
            )
            server.enqueue(MockResponse.Builder().code(200).build())
            val source = source(
                downloadUrl = server.url("/resolve/model.tflite").toString(),
                requiresAuthorization = true,
            )
            val resolver = HuggingFaceDownloadUrlResolver(OkHttpClient())

            val prepared = resolver.prepare(source) { "Bearer hf_test" }.getOrThrow()

            assertEquals(server.url("/signed/model.tflite").toString(), prepared.url)
            assertNull(prepared.authorizationHeader)
            assertEquals("Bearer hf_test", server.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun prepareFailsGatedUrlWithActionableUnauthorizedMessage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(401).build())
            server.start()
            val source = source(
                downloadUrl = server.url("/resolve/model.tflite").toString(),
                requiresAuthorization = true,
            )
            val resolver = HuggingFaceDownloadUrlResolver(OkHttpClient())

            val failure = resolver.prepare(source) { "Bearer hf_old" }.exceptionOrNull()

            requireNotNull(failure)
            assertTrue(failure.message.orEmpty().contains("授权无效"))
        }
    }

    private fun source(
        downloadUrl: String,
        requiresAuthorization: Boolean = false,
    ): ModelDownloadSource =
        ModelDownloadSource(
            title = "Test model",
            fileName = "model.tflite",
            downloadUrl = downloadUrl,
            expectedBytes = 1L,
            expectedSha256 = null,
            modelId = "test-model",
            requiresHuggingFaceAuthorization = requiresAuthorization,
        )
}
