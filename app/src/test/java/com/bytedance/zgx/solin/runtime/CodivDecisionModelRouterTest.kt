package com.bytedance.zgx.solin.runtime

import com.bytedance.zgx.solin.audit.RemoteSendAuditEvent
import com.bytedance.zgx.solin.audit.RemoteSendAuditSink
import com.bytedance.zgx.solin.orchestration.DecisionModelVerdict
import com.bytedance.zgx.solin.orchestration.RunPlacement
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodivDecisionModelRouterTest {
    @Test
    fun sendsOnlyTheRequestTextAndOneChoiceQuestionWithTheKey() {
        MockWebServer().use { server ->
            server.enqueue(ok(answer(choice = "remote", local = 0.04, remote = 0.96, confidence = 0.96)))
            server.start()
            val audits = mutableListOf<RemoteSendAuditEvent>()
            val router = CodivDecisionModelRouter(
                credential = { "test-key" },
                auditSink = object : RemoteSendAuditSink {
                    override fun record(event: RemoteSendAuditEvent) { audits += event }
                },
                baseUrl = server.url("/v1").toString(),
            )

            assertEquals(DecisionModelVerdict.Decided(RunPlacement.Remote, 0.96), router.classify("写一份旅行计划"))

            val request = server.takeRequest()
            assertEquals("/v1/systemone", request.url.encodedPath)
            assertEquals("Bearer test-key", request.headers["Authorization"])
            val body = JSONObject(request.body!!.utf8())
            assertEquals(setOf("model", "state", "questions"), body.keys().asSequence().toSet())
            assertEquals(CODIV_DECISION_MODEL, body.getString("model"))
            assertEquals("写一份旅行计划", body.getJSONObject("state").getString("request"))
            assertEquals(setOf("request"), body.getJSONObject("state").keys().asSequence().toSet())
            assertEquals("choice", body.getJSONObject("questions").getJSONObject("placement").getString("type"))
            val audit = audits.single()
            assertFalse("audit must not carry the text", audit.summary.contains("旅行计划"))
            assertFalse("audit must not carry the key", audit.summary.contains("test-key"))
        }
    }

    @Test
    fun noKeyMeansNoRequest() {
        MockWebServer().use { server ->
            server.start()
            val router = CodivDecisionModelRouter(credential = { " " }, baseUrl = server.url("/v1").toString())
            assertEquals(DecisionModelVerdict.Unavailable("not_configured"), router.classify("你好"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun httpErrorsAreUnavailable() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(401).build())
            server.start()
            val router = CodivDecisionModelRouter(credential = { "k" }, baseUrl = server.url("/v1").toString())
            assertEquals(DecisionModelVerdict.Unavailable("http_401"), router.classify("你好"))
        }
    }

    @Test
    fun repliesOutsideTheContractAreRejected() {
        assertEquals(DecisionModelVerdict.Decided(RunPlacement.Local, 0.91), parseDecisionResponse(answer("local", 0.91, 0.09, 0.91)))
        listOf(
            "not json" to "not_json",
            """{"model":"m"}""" to "no_answers",
            answer("cloud", 0.5, 0.5, 0.9) to "choice_outside_criteria",
            answer("local", 0.7, 0.7, 0.9) to "probabilities_invalid",
            answer("local", 0.9, 0.1, 1.2) to "confidence_invalid",
            """{"answers":{"placement":{"type":"score","choice":"local"}}}""" to "type_mismatch",
            """{"answers":{"other":{"type":"choice"}}}""" to "answers_mismatch",
            """{"answers":{"placement":{"type":"choice","choice":"local","probabilities":{"local":1.0},"confidence":0.9}}}""" to
                "probabilities_mismatch",
        ).forEach { (raw, reason) ->
            assertEquals(raw, DecisionModelVerdict.Unavailable(reason), parseDecisionResponse(raw))
        }
    }

    @Test
    fun longTextIsCappedBeforeItLeaves() {
        val body = decisionRequestBody("a".repeat(5_000))
        assertTrue(body.getJSONObject("state").getString("request").length <= 2_000)
    }

    private fun ok(body: String) =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private fun answer(choice: String, local: Double, remote: Double, confidence: Double) =
        """{"model":"openjev-0.1","answers":{"placement":{"type":"choice","choice":"$choice",""" +
            """"probabilities":{"local":$local,"remote":$remote},"confidence":$confidence}}}"""
}
