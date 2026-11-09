package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.runtime.LocalOneShotCancellation
import com.bytedance.zgx.solin.runtime.LocalOneShotRequest
import com.bytedance.zgx.solin.runtime.LocalOneShotResult
import com.bytedance.zgx.solin.runtime.LocalOneShotUnavailableReason
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtShimLocalEngineTest {
    @Test
    fun mapsMessagesProductionSamplingAndOutputCap() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body(chatResponse("""{"action":true,"confidence":0.9}""")).build())
            server.start()
            val engine = LiteRtShimLocalEngine(MODEL_PATH, server.url("/").toString())

            val result = engine.generateOneShot(
                LocalOneShotRequest(
                    modelPath = MODEL_PATH,
                    systemInstruction = "system exactly",
                    prompt = "user exactly",
                    maxOutputTokens = 64,
                ),
            )

            assertEquals("""{"action":true,"confidence":0.9}""", (result as LocalOneShotResult.Generated).text)
            val body = JSONObject(server.takeRequest().body!!.utf8())
            assertEquals("litert/gemma-4-e2b", body.getString("model"))
            assertEquals(false, body.getBoolean("stream"))
            val messages = body.getJSONArray("messages")
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            assertEquals("system exactly", messages.getJSONObject(0).getString("content"))
            assertEquals("user", messages.getJSONObject(1).getString("role"))
            assertEquals("user exactly", messages.getJSONObject(1).getString("content"))
            val options = body.getJSONObject("options")
            assertEquals(0.0, options.getDouble("temperature"), 0.0)
            assertEquals(1, options.getInt("top_k"))
            assertEquals(0.1, options.getDouble("top_p"), 0.0)
            assertEquals(0, options.getInt("seed"))
            assertEquals(64, options.getInt("num_predict"))
            assertEquals(64, engine.calls.single().metrics.getInt("maxOutputTokens"))
            assertEquals(JSONObject.NULL, engine.calls.single().metrics.get("error"))
        }
    }

    @Test
    fun mapsHttpAndEngineFailuresWithoutThrowing() {
        MockWebServer().use { server ->
            server.start()
            val engine = LiteRtShimLocalEngine(MODEL_PATH, server.url("/").toString())
            val cases = listOf(
                MockResponse.Builder().code(404).body("missing").build() to LocalOneShotUnavailableReason.ModelNotLoaded,
                MockResponse.Builder().code(409).body("busy").build() to LocalOneShotUnavailableReason.Busy,
                MockResponse.Builder().code(501).body("unsupported").build() to LocalOneShotUnavailableReason.Unsupported,
                MockResponse.Builder().code(500).body("failed").build() to LocalOneShotUnavailableReason.Failed,
                MockResponse.Builder().code(200).body("""{"error":"litert_error:send_message failed"}""").build()
                    to LocalOneShotUnavailableReason.Failed,
            )
            cases.forEach { (response, expected) ->
                server.enqueue(response)
                val result = engine.generateOneShot(request())
                assertEquals(expected, (result as LocalOneShotResult.Unavailable).reason)
            }
            assertEquals("http_500", engine.calls[3].metrics.getString("error"))
            assertTrue(engine.calls[4].metrics.getString("error").startsWith("litert_error:"))
        }
    }

    @Test
    fun wrongModelAndPreCancelledCallDoNotReachHttp() {
        MockWebServer().use { server ->
            server.start()
            val engine = LiteRtShimLocalEngine(MODEL_PATH, server.url("/").toString())
            val wrong = engine.generateOneShot(request(modelPath = "/tmp/other.litertlm"))
            assertEquals(
                LocalOneShotUnavailableReason.ModelNotLoaded,
                (wrong as LocalOneShotResult.Unavailable).reason,
            )
            val cancellation = LocalOneShotCancellation().apply { cancel() }
            val cancelled = engine.generateOneShot(request(cancellation = cancellation))
            assertEquals(
                LocalOneShotUnavailableReason.Cancelled,
                (cancelled as LocalOneShotResult.Unavailable).reason,
            )
            assertEquals(0, server.requestCount)
            assertTrue(engine.calls[1].metrics.getString("error").isNotBlank())
        }
    }

    @Test
    fun cancellationCancelsAnInflightHttpCall() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .bodyDelay(5, TimeUnit.SECONDS)
                    .body(chatResponse("too late"))
                    .build(),
            )
            server.start()
            val engine = LiteRtShimLocalEngine(MODEL_PATH, server.url("/").toString())
            val cancellation = LocalOneShotCancellation()
            val arrived = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val result = AtomicReference<LocalOneShotResult>()
            val thread = Thread {
                arrived.countDown()
                result.set(engine.generateOneShot(request(cancellation = cancellation)))
                finished.countDown()
            }
            thread.start()
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            server.takeRequest(5, TimeUnit.SECONDS)
            cancellation.cancel()

            assertTrue("HTTP call did not return after cancellation", finished.await(5, TimeUnit.SECONDS))
            assertEquals(
                LocalOneShotUnavailableReason.Cancelled,
                (result.get() as LocalOneShotResult.Unavailable).reason,
            )
            assertTrue(engine.calls.single().metrics.getString("error").isNotBlank())
        }
    }

    private fun request(
        modelPath: String = MODEL_PATH,
        cancellation: LocalOneShotCancellation = LocalOneShotCancellation(),
    ) = LocalOneShotRequest(
        modelPath = modelPath,
        systemInstruction = "system",
        prompt = "prompt",
        cancellation = cancellation,
    )

    private fun chatResponse(text: String): String =
        JSONObject()
            .put("message", JSONObject().put("role", "assistant").put("content", text))
            .put("done", true)
            .put("done_reason", "stop")
            .toString()

    private companion object {
        const val MODEL_PATH = "/models/gemma-4-E2B-it.litertlm"
    }
}
