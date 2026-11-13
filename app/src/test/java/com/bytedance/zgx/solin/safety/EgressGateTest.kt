package com.bytedance.zgx.solin.safety

import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolPermission
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EgressGateTest {
    private val policy = SafetyPolicy()
    private val gate = EgressGate(policy::containsSensitivePersonalOrSecretContent)

    @Test
    fun networkRequiresSensitiveTextOrPrivateReadEvenWhenNoTextWasSupplied() {
        val spec = spec()
        assertEquals(
            setOf(EgressTrigger.SensitiveNetworkQuery),
            gate.evaluate(spec, request(args = mapOf("query" to "alice@example.com")), context = { EgressRunContext(false, "") })
        )
        val triggers = gate.evaluate(spec, request(), context = { EgressRunContext(true, "user") })
        assertEquals(setOf(EgressTrigger.SensitiveNetworkQuery), triggers)
        assertTrue(gate.evaluate(spec, request(args = mapOf("query" to "weather")), context = { EgressRunContext(false, "") }).isEmpty())
    }

    @Test
    fun typedTextNeedsPrivateReadAndNewContent() {
        val spec = spec(egressArgs = emptySet())
        val triggers = gate.evaluate(
            spec,
            request(),
            typedText = "new private text",
            context = { EgressRunContext(true, "original request") }
        )
        assertEquals(setOf(EgressTrigger.TypedTextNotFromUser), triggers)
        assertTrue(gate.evaluate(spec, request(), typedText = "sensitive looking text", context = { EgressRunContext(false, "") }).isEmpty())
        assertTrue(
            gate.evaluate(
                spec,
                request(),
                typedText = "上海酒店",
                context = { EgressRunContext(true, "在携程搜索上海的酒店") }
            ).isEmpty()
        )
    }

    @Test
    fun externalDraftUsesSensitiveContentTaintAndUserWording() {
        val spec = spec(externalApp = true, egressArgs = setOf("body", "subject"))
        assertEquals(
            setOf(EgressTrigger.TextHandedToExternalApp),
            gate.evaluate(spec, request(toolName = "share_text", args = mapOf("body" to "alice@example.com")), context = { EgressRunContext(false, "") })
        )
        assertTrue(
            gate.evaluate(
                spec,
                request(toolName = "share_text", args = mapOf("body" to "上海酒店")),
                context = { EgressRunContext(true, "在携程搜索上海的酒店") }
            ).isEmpty()
        )
        val triggers = gate.evaluate(
            spec,
            request(toolName = "share_text", args = mapOf("body" to "上海酒店", "subject" to "new")),
            context = { EgressRunContext(true, "在携程搜索上海的酒店") }
        )
        assertEquals(setOf(EgressTrigger.TextHandedToExternalApp), triggers)
        assertTrue(
            gate.evaluate(spec, request(toolName = "share_text"), context = { EgressRunContext(true, "private") }).isEmpty()
        )
    }

    @Test
    fun nonAsciiPunctuationDoesNotChangeUserWording() {
        val result = gate.evaluate(
            spec(egressArgs = emptySet()),
            request(),
            typedText = "上海酒店。",
            context = { EgressRunContext(true, "在携程搜索上海的酒店") }
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun sensitiveContentDoesNotEvaluateRunContext() {
        val result = gate.evaluate(
            spec(),
            request(args = mapOf("query" to "alice@example.com")),
            context = { error("sensitive text must not require run context") }
        )

        assertEquals(setOf(EgressTrigger.SensitiveNetworkQuery), result)
    }

    @Test
    fun sensitiveDetectionRunsOncePerArgument() {
        var reads = 0
        val countingGate = EgressGate { text ->
            reads++
            policy.containsSensitivePersonalOrSecretContent(text)
        }
        val spec = spec(externalApp = true, egressArgs = setOf("body", "subject"))

        countingGate.evaluate(
            spec,
            request(toolName = "share_text", args = mapOf("body" to "one", "subject" to "two")),
            context = { EgressRunContext(false, "") }
        )

        assertEquals(2, reads)
    }

    @Test
    fun supplementaryAndSymbolCodePointsCannotMatchEmptyInput() {
        val supplementary = gate.evaluate(
            spec(egressArgs = emptySet()),
            request(),
            typedText = "user𠀀",
            context = { EgressRunContext(true, "user") }
        )
        val symbols = gate.evaluate(
            spec(externalApp = true),
            request(toolName = "share_text", args = mapOf("query" to "♥")),
            context = { EgressRunContext(true, "") }
        )

        assertEquals(setOf(EgressTrigger.TypedTextNotFromUser), supplementary)
        assertEquals(setOf(EgressTrigger.TextHandedToExternalApp), symbols)
    }

    private fun spec(
        externalApp: Boolean = false,
        egressArgs: Set<String> = setOf("query")
    ): ToolSpec = ToolSpec(
        name = if (externalApp) "share_text" else "web_search",
        title = "test",
        description = "test",
        inputSchemaJson = """{"type":"object"}""",
        capability = if (externalApp) ToolCapability.ExternalShare else ToolCapability.WebSearch,
        permissions = if (externalApp) setOf(ToolPermission.SendsTextToExternalApp) else emptySet(),
        riskLevel = RiskLevel.LowReadOnly,
        confirmationPolicy = ConfirmationPolicy.NotRequired,
        egressTextArguments = egressArgs
    )

    private fun request(toolName: String = "web_search", args: Map<String, String> = emptyMap()): ToolRequest = ToolRequest(toolName = toolName, arguments = args, reason = "test")
}
