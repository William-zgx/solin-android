package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.orchestration.collab.CloudPlannerRequest
import java.io.IOException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LocalLiteRtMilestonePlannerModelTest {
    @Test
    fun sendsSystemInstructionAndExactUserRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("""{"capabilities":[]}""").build())
            server.enqueue(MockResponse.Builder().code(200).body(chatResponse(SEQUENTIAL_PLAN)).build())
            server.start()
            val adapter = LocalLiteRtMilestonePlannerModel(OllamaClient(server.url("/").toString()), MODEL)
            val rendered = CloudPlannerRequest.render(REQUEST).text

            val result = adapter.complete("planner instruction", rendered)

            assertEquals(SEQUENTIAL_PLAN, result)
            server.takeRequest()
            val body = JSONObject(server.takeRequest().body!!.utf8())
            val messages = body.getJSONArray("messages")
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            assertEquals("planner instruction", messages.getJSONObject(0).getString("content"))
            assertEquals("user", messages.getJSONObject(1).getString("role"))
            assertEquals(rendered, messages.getJSONObject(1).getString("content"))
            assertEquals(1_024, body.getJSONObject("options").getInt("num_predict"))
        }
    }

    @Test
    fun shimErrorBecomesTransportFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("""{"capabilities":[]}""").build())
            repeat(2) {
                server.enqueue(MockResponse.Builder().code(200).body("""{"error":"synthetic failure"}""").build())
            }
            server.start()
            val adapter = LocalLiteRtMilestonePlannerModel(OllamaClient(server.url("/").toString()), MODEL)

            try {
                adapter.complete("planner instruction", CloudPlannerRequest.render(REQUEST).text)
                fail("expected transport failure")
            } catch (_: IOException) {
            }
        }
    }

    private fun chatResponse(text: String): String = JSONObject()
        .put("message", JSONObject().put("role", "assistant").put("content", text))
        .toString()

    private companion object {
        const val MODEL = "litert/gemma-4-e2b"
        const val REQUEST = "在淘宝搜索牛奶"
        const val SEQUENTIAL_PLAN =
            """{"version":1,"milestones":[{"intent":"search_in_app","app":"com.taobao.taobao","query":"牛奶"}]}"""
    }
}
