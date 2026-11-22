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

    private fun share(text: String, input: String) = gate.evaluate(
        shareText,
        request(MobileActionFunctions.SHARE_TEXT, mapOf("text" to text)),
        context = { EgressRunContext(true, input) }
    )

    private fun typed(text: String, input: String) = gate.evaluate(
        typeText,
        request(toolName = MobileActionFunctions.UI_TYPE_TEXT),
        typedText = text,
        context = { EgressRunContext(true, input) }
    )

    @Test
    fun symbolOnlyTextNotInRunInputIsNotFromUser() {
        assertEquals(setOf(EgressTrigger.TextHandedToExternalApp), share("♥", ""))
        assertEquals(setOf(EgressTrigger.TextHandedToExternalApp), share("♥", "分享给小王"))
        assertEquals(setOf(EgressTrigger.TypedTextNotFromUser), typed("♥", ""))
        assertEquals(setOf(EgressTrigger.TypedTextNotFromUser), typed("♥", "输入 hello"))
        assertEquals(setOf(EgressTrigger.TypedTextNotFromUser), typed("♥", "输入 ♦"))
    }

    @Test
    fun explicitlyUserSuppliedSymbolOnlyTextStaysEligible() {
        assertTrue(share("♥", "把 ♥ 分享给小王").isEmpty())
        assertTrue(typed("♥", "输入♥").isEmpty())
        assertTrue(typed("♥", "输入 ♥ 然后别发送").isEmpty())
    }

    @Test
    fun wordsWithPunctuationAndMultipleArgumentsStayCorrect() {
        assertTrue(share("你好！！", "你好").isEmpty())
        // Text containing letters/digits keeps the legacy whole-token matching; symbols are ignored.
        assertTrue(share("hello ♥", "hello").isEmpty())
        assertTrue(typed("上海\u00A0酒店", "上海 酒店").isEmpty())
        assertEquals(setOf(EgressTrigger.TypedTextNotFromUser), typed("hello wor", "hello world"))
        val multi = gate.evaluate(
            shareText,
            request(MobileActionFunctions.SHARE_TEXT, mapOf("text" to "hello", "title" to "♥")),
            context = { EgressRunContext(true, "hello") }
        )
        assertEquals(setOf(EgressTrigger.TextHandedToExternalApp), multi)
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

    @Test
    fun compatibilitySymbolMatchesUserWordingAfterNormalization() {
        assertTrue(typed("＃", "输入 #").isEmpty())
    }

    @Test
    fun symbolSequenceMustAppearContiguouslyInUserInput() {
        assertTrue(typed("♥♦", "输入 ♥♦ 然后停止").isEmpty())
        assertEquals(setOf(EgressTrigger.TypedTextNotFromUser), typed("♥♦", "输入 ♥ 然后 ♦"))
    }

    @Test
    fun asciiTokensMatchWholeTokensNotSubstrings() {
        assertEquals(setOf(EgressTrigger.TypedTextNotFromUser), typed("1", "给10086发短信说查询话费"))
        assertTrue(typed("10086 查询话费", "给10086发短信说查询话费").isEmpty())
    }

    private fun spec(toolName: String): ToolSpec = requireNotNull(defaultBuiltInToolRegistry.specFor(toolName))

    private fun request(
        toolName: String = MobileActionFunctions.WEB_SEARCH,
        args: Map<String, String> = emptyMap()
    ): ToolRequest = ToolRequest(toolName = toolName, arguments = args, reason = "test")
}
