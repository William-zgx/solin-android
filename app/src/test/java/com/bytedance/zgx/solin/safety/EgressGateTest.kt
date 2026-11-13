package com.bytedance.zgx.solin.safety

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EgressGateTest {
    private val policy = SafetyPolicy()
    private val gate = EgressGate(policy::containsSensitivePersonalOrSecretContent)
    private val webSearch = spec(MobileActionFunctions.WEB_SEARCH)
    private val shareText = spec(MobileActionFunctions.SHARE_TEXT)
    private val typeText = spec(MobileActionFunctions.UI_TYPE_TEXT)

    @Test
    fun networkRequiresSensitiveTextOrPrivateReadEvenWhenNoTextWasSupplied() {
        assertEquals(
            setOf(EgressTrigger.SensitiveNetworkQuery),
            gate.evaluate(
                webSearch,
                request(args = mapOf("query" to "alice@example.com")),
                context = { EgressRunContext(false, "") }
            )
        )
        val triggers = gate.evaluate(webSearch, request(), context = { EgressRunContext(true, "user") })
        assertEquals(setOf(EgressTrigger.SensitiveNetworkQuery), triggers)
        assertTrue(
            gate.evaluate(
                webSearch,
                request(args = mapOf("query" to "weather")),
                context = { EgressRunContext(false, "") }
            ).isEmpty()
        )
    }

    @Test
    fun typedTextNeedsPrivateReadAndNewContent() {
        val triggers = gate.evaluate(
            typeText,
            request(toolName = MobileActionFunctions.UI_TYPE_TEXT),
            typedText = "new private text",
            context = { EgressRunContext(true, "original request") }
        )
        assertEquals(setOf(EgressTrigger.TypedTextNotFromUser), triggers)
        assertTrue(
            gate.evaluate(
                typeText,
                request(toolName = MobileActionFunctions.UI_TYPE_TEXT),
                typedText = "sensitive looking text",
                context = { EgressRunContext(false, "") }
            ).isEmpty()
        )
        assertTrue(
            gate.evaluate(
                typeText,
                request(toolName = MobileActionFunctions.UI_TYPE_TEXT),
                typedText = "上海酒店",
                context = { EgressRunContext(true, "在携程搜索上海的酒店") }
            ).isEmpty()
        )
    }

    @Test
    fun externalDraftUsesSensitiveContentTaintAndUserWording() {
        assertEquals(
            setOf(EgressTrigger.TextHandedToExternalApp),
            gate.evaluate(
                shareText,
                request(MobileActionFunctions.SHARE_TEXT, mapOf("text" to "alice@example.com")),
                context = { EgressRunContext(false, "") }
            )
        )
        assertTrue(
            gate.evaluate(
                shareText,
                request(MobileActionFunctions.SHARE_TEXT, mapOf("text" to "上海酒店")),
                context = { EgressRunContext(true, "在携程搜索上海的酒店") }
            ).isEmpty()
        )
        val triggers = gate.evaluate(
            shareText,
            request(MobileActionFunctions.SHARE_TEXT, mapOf("text" to "上海酒店", "title" to "new")),
            context = { EgressRunContext(true, "在携程搜索上海的酒店") }
        )
        assertEquals(setOf(EgressTrigger.TextHandedToExternalApp), triggers)
        assertTrue(
            gate.evaluate(
                shareText,
                request(MobileActionFunctions.SHARE_TEXT),
                context = { EgressRunContext(true, "private") }
            ).isEmpty()
        )
    }

    @Test
    fun nonAsciiPunctuationDoesNotChangeUserWording() {
        val result = gate.evaluate(
            typeText,
            request(toolName = MobileActionFunctions.UI_TYPE_TEXT),
            typedText = "上海酒店。",
            context = { EgressRunContext(true, "在携程搜索上海的酒店") }
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun nonBreakingSpaceDoesNotChangeUserWording() {
        val result = gate.evaluate(
            typeText,
            request(toolName = MobileActionFunctions.UI_TYPE_TEXT),
            typedText = "上海 酒店",
            context = { EgressRunContext(true, "上海 酒店") }
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun symbolOnlyTextKeepsLegacyMatching() {
        val result = gate.evaluate(
            shareText,
            request(MobileActionFunctions.SHARE_TEXT, mapOf("text" to "♥")),
            context = { EgressRunContext(true, "") }
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun sensitiveContentDoesNotEvaluateRunContext() {
        val result = gate.evaluate(
            webSearch,
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

        countingGate.evaluate(
            shareText,
            request(MobileActionFunctions.SHARE_TEXT, mapOf("text" to "one", "title" to "two")),
            context = { EgressRunContext(false, "") }
        )

        assertEquals(2, reads)
    }

    @Test
    fun supplementaryCodePointsCannotMatchEmptyInput() {
        val supplementary = gate.evaluate(
            typeText,
            request(toolName = MobileActionFunctions.UI_TYPE_TEXT),
            typedText = "user𠀀",
            context = { EgressRunContext(true, "user") }
        )
        assertEquals(setOf(EgressTrigger.TypedTextNotFromUser), supplementary)
    }

    private fun spec(toolName: String): ToolSpec = requireNotNull(defaultBuiltInToolRegistry.specFor(toolName))

    private fun request(
        toolName: String = MobileActionFunctions.WEB_SEARCH,
        args: Map<String, String> = emptyMap()
    ): ToolRequest = ToolRequest(toolName = toolName, arguments = args, reason = "test")
}
