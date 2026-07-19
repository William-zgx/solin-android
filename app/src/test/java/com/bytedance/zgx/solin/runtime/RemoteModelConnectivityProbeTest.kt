package com.bytedance.zgx.solin.runtime

import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.RemoteModelConfig
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class RemoteModelConnectivityProbeTest {
    @Test
    fun checkAddsV1ModelsWhenBaseUrlIsServiceRoot() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"data":[]}""")
                    .build(),
            )
            server.start()
            val probe = OkHttpRemoteModelConnectivityProbe()

            val status = probe.check(
                RemoteModelConfig(
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    modelName = "model-a",
                ),
            )

            assertEquals(RemoteModelConnectivityStatus.Reachable, status)
            assertEquals("/v1/models", server.takeRequest().target)
        }
    }

    @Test
    fun coroutineCancellationCancelsTheInFlightHttpCall() = runBlocking {
        val call = BlockingCall()
        val probe = OkHttpRemoteModelConnectivityProbe(callFactory = Call.Factory { call })
        val job = launch(Dispatchers.Default) {
            probe.check(
                RemoteModelConfig(
                    baseUrl = "https://api.example.com/v1",
                    modelName = "model-a",
                ),
            )
        }

        assertTrue(call.started.await(5, TimeUnit.SECONDS))
        try {
            job.cancel()
            assertTrue(call.isCanceled())
        } finally {
            call.release.countDown()
            job.cancelAndJoin()
        }
    }

    private class BlockingCall : Call {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        private var cancelled = false

        override fun request(): Request = Request.Builder()
            .url("https://api.example.com/v1/models")
            .build()

        override fun execute(): Response {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw IOException("released")
        }

        override fun enqueue(responseCallback: Callback) {
            started.countDown()
        }

        override fun cancel() {
            cancelled = true
        }

        override fun isExecuted(): Boolean = started.count == 0L

        override fun isCanceled(): Boolean = cancelled

        override fun timeout(): Timeout = Timeout.NONE

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()

        override fun clone(): Call = this
    }
}
