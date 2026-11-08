package com.bytedance.zgx.solin.action

import com.bytedance.zgx.solin.runtime.DedicatedLiteRtEngine
import com.bytedance.zgx.solin.runtime.FakeLiteRtFactory
import com.bytedance.zgx.solin.runtime.FakeReply
import com.bytedance.zgx.solin.runtime.LocalAccelerator
import com.bytedance.zgx.solin.runtime.LocalCallMetrics
import com.bytedance.zgx.solin.runtime.LocalEngine
import com.bytedance.zgx.solin.runtime.LocalEngineCapabilities
import com.bytedance.zgx.solin.runtime.LocalEngineSource
import com.bytedance.zgx.solin.runtime.LocalOneShotRequest
import com.bytedance.zgx.solin.runtime.LocalOneShotResult
import com.bytedance.zgx.solin.runtime.LocalOneShotUnavailableReason
import com.bytedance.zgx.solin.runtime.LocalModelRuntimeCapabilities
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolProvider
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolSpec
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPlannerEngineRoutingTest {
    private val factory = FakeLiteRtFactory().apply { reply = { FakeReply(MODEL_CALL) } }
    private val shared = ScriptedSharedEngine()
    private val fallback = DedicatedLiteRtEngine(Files.createTempDirectory("planner-routing").toFile(), factory)

    @Test
    fun sharedEngineServesThePlanAndReleasesTheFallbackEngine() {
        val runtime = planner(sharedEngine = shared)
        shared.next = LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.ModelNotLoaded)
        runtime.plan("请求", MODEL)
        assertEquals(0, factory.engines.single().closeCount)

        shared.next = generated(MODEL_CALL, LocalEngineSource.SharedChatEngine)
        val result = runtime.plan("请求", MODEL)

        assertTrue(result.usedModel)
        assertEquals(TOOL_NAME, result.plan.draft?.functionName)
        assertEquals(LocalEngineSource.SharedChatEngine, result.modelMetrics?.source)
        assertEquals("the fallback copy is released once chat serves the model", 1, factory.engines.single().closeCount)
        val request = shared.requests.last()
        assertEquals(MODEL, request.modelPath)
        assertEquals("你是手机动作规划器。只能输出 call:函数名 {\"参数名\":\"值\"}，不解释。", request.systemInstruction)
        assertFalse(request.systemInstruction.contains("call:function"))
        assertFalse(request.prompt.contains("call:function"))
        assertFalse(request.prompt.contains("""{"arg":"value"}"""))
        assertTrue(request.prompt.contains("- $TOOL_NAME "))
    }

    @Test
    fun sameModelAsChatUsesTheSharedEngineWithoutCreatingASecondEngine() {
        shared.next = generated(MODEL_CALL, LocalEngineSource.SharedChatEngine)

        val result = planner(sharedEngine = shared).plan("请求", MODEL)

        assertTrue(result.usedModel)
        assertEquals(LocalEngineSource.SharedChatEngine, result.modelMetrics?.source)
        assertTrue("no second Engine for a model chat already holds", factory.engines.isEmpty())
    }

    @Test
    fun sharedEngineServingAnotherModelLeavesTheFallbackModelLoaded() {
        val runtime = planner(sharedEngine = shared)
        shared.next = LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.ModelNotLoaded)
        runtime.plan("请求", OTHER_MODEL)

        shared.next = generated(MODEL_CALL, LocalEngineSource.SharedChatEngine)
        runtime.plan("请求", MODEL)

        assertEquals(OTHER_MODEL, factory.engines.single().modelPath)
        assertEquals("the fallback keeps a model chat does not serve", 0, factory.engines.single().closeCount)
    }

    @Test
    fun missingSharedModelFallsBackToThePlannersOwnCpuEngine() {
        shared.next = LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.ModelNotLoaded)

        val result = planner(sharedEngine = shared).plan("请求", MODEL)

        assertTrue(result.usedModel)
        assertEquals(LocalEngineSource.DedicatedEngine, result.modelMetrics?.source)
        assertEquals(listOf<LocalModelRuntimeCapabilities?>(null), factory.requestedCapabilities.toList())
    }

    @Test
    fun plannerWithoutASharedEngineUsesItsOwnEngine() {
        val result = planner(sharedEngine = null).plan("请求", MODEL)

        assertTrue(result.usedModel)
        assertEquals(LocalEngineSource.DedicatedEngine, result.modelMetrics?.source)
    }

    @Test
    fun busyOrFailingSharedEngineFailsClosedToTheRulePlanner() {
        val runtime = planner(sharedEngine = shared)
        listOf(
            LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.Busy, "chat holds the engine") to
                "local_engine_busy:chat holds the engine",
            LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.Failed, "boom") to "local_engine_failed:boom",
            LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.Cancelled, "interrupted") to
                "local_engine_cancelled:interrupted",
        ).forEach { (unavailable, expectedReason) ->
            shared.next = unavailable

            val result = runtime.plan("请求", MODEL)

            assertFalse(result.usedModel)
            assertTrue(result.modelAttempted)
            assertEquals(expectedReason, result.modelFailureReason)
        }
        assertTrue("a mapped model is never loaded a second time", factory.engines.isEmpty())
    }

    @Test
    fun multipleCallsOnOneLineFailClosedBeforeDraftAcceptance() {
        shared.next = generated("$MODEL_CALL $MODEL_CALL", LocalEngineSource.SharedChatEngine)

        val result = planner(shared).plan("请求", MODEL)

        assertFalse(result.usedModel)
        assertTrue(result.modelAttempted)
        assertEquals("Model tool call arguments must be a JSON object with primitive values", result.modelFailureReason)
        assertEquals(ActionPlanKind.NoAction, result.plan.kind)
    }

    @Test
    fun actionIntentGateUsesTheLabJsonPromptAndOpensAtThreshold() {
        shared.next = generated("""{"action":true,"confidence":0.9}""", LocalEngineSource.SharedChatEngine)

        val result = planner(sharedEngine = shared).gateActionIntent("打开微信", MODEL)

        assertTrue(result.open)
        assertEquals("local_model_action_gate_open", result.reason)
        val request = shared.requests.single()
        assertEquals(MODEL, request.modelPath)
        assertEquals("""{"request":"打开微信"}""", request.prompt)
        assertEquals(64, request.maxOutputTokens)
        assertFalse(request.cancellation.isCancelled)
        assertEquals(
            "You decide whether a phone assistant must perform a phone action for the user's request. " +
                "action = true: the user asks the assistant to do something on the phone now, such as opening an app, " +
                "setting an alarm, timer or reminder, drafting a message, email, contact or calendar event, " +
                "calling, searching the web or a map, or changing a setting. " +
                "action = false: chat, a question the assistant can answer in text, an explanation, a translation, " +
                "or a request that tells the assistant not to do something.\n\n" +
                "The user message is a JSON object whose \"request\" field is the request. " +
                "Reply with one JSON object only: {\"action\": true or false, \"confidence\": a number from 0 to 1}.",
            request.systemInstruction,
        )
    }

    @Test
    fun actionIntentGateClosesForNoActionLowConfidenceAndInvalidJson() {
        val cases = listOf(
            """{"action":false,"confidence":1.0}""" to "local_model_action_gate_no_action",
            """{"action":true,"confidence":0.89}""" to "local_model_action_gate_low_confidence",
            """{"action":true}""" to "local_model_action_gate_invalid_output",
            """{"action":"true","confidence":1.0}""" to "local_model_action_gate_invalid_output",
            """{"action":true,"confidence":1.0,"extra":0}""" to "local_model_action_gate_invalid_output",
            """{"action":true,"confidence":1.1}""" to "local_model_action_gate_invalid_output",
            "```json\n{\"action\":true,\"confidence\":1.0}\n```" to "local_model_action_gate_invalid_output",
            """{"action":true,"confidence":1.0} trailing""" to "local_model_action_gate_invalid_output",
            "not json" to "local_model_action_gate_invalid_output",
        )

        cases.forEach { (output, expectedReason) ->
            shared.next = generated(output, LocalEngineSource.SharedChatEngine)

            val result = planner(sharedEngine = shared).gateActionIntent("请求", MODEL)

            assertFalse(output, result.open)
            assertEquals(output, expectedReason, result.reason)
        }
    }

    @Test
    fun actionIntentGateBusyAndUnavailableEngineFailClosedWithoutLoadingAnotherCopy() {
        listOf(
            LocalOneShotUnavailableReason.Busy to "local_model_action_gate_busy",
            LocalOneShotUnavailableReason.Failed to "local_model_action_gate_failed",
        ).forEach { (unavailableReason, expectedReason) ->
            shared.next = LocalOneShotResult.Unavailable(unavailableReason, "private engine detail")

            val result = planner(sharedEngine = shared).gateActionIntent("请求", MODEL)

            assertFalse(result.open)
            assertEquals(expectedReason, result.reason)
            assertFalse(result.reason.contains("private engine detail"))
        }
        assertTrue(factory.engines.isEmpty())
    }

    @Test
    fun closingThePlannerReleasesItsFallbackEngine() {
        val runtime = planner(sharedEngine = null)
        runtime.plan("请求", MODEL)

        runtime.close()

        assertEquals(1, factory.engines.single().closeCount)
    }

    private fun planner(sharedEngine: LocalEngine?) =
        HybridActionPlanningRuntime(fallbackEngine = fallback, toolRegistry = registry, sharedEngine = sharedEngine)

    private fun generated(text: String, source: LocalEngineSource) =
        LocalOneShotResult.Generated(
            text = text,
            metrics = LocalCallMetrics(source, LocalAccelerator.CPU, null, 5, 10, null, null),
        )

    private class ScriptedSharedEngine : LocalEngine {
        val requests = mutableListOf<LocalOneShotRequest>()
        var next: LocalOneShotResult = LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.ModelNotLoaded)

        override fun engineCapabilities() = LocalEngineCapabilities("fake", setOf(LocalAccelerator.CPU), 8_192)

        override fun generateOneShot(request: LocalOneShotRequest): LocalOneShotResult {
            requests += request
            return next
        }
    }

    private companion object {
        const val MODEL = "/tmp/planner.litertlm"
        const val OTHER_MODEL = "/tmp/planner-270m.litertlm"
        const val TOOL_NAME = "hybrid_registry_tool"
        const val MODEL_CALL = "call:hybrid_registry_tool{}"

        val registry = ToolRegistry(
            ToolProvider {
                listOf(
                    ToolSpec(
                        name = TOOL_NAME,
                        title = "Hybrid registry tool",
                        description = "Hybrid registry tool",
                        inputSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}""",
                        capability = ToolCapability.DeviceContext,
                    ),
                )
            },
        )
    }
}
