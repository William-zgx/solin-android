package com.bytedance.zgx.solin.action

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResultContinuationPolicy
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.ValidatingToolExecutor
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import com.bytedance.zgx.solin.tool.succeeded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frozen `note` result contract: data keys `toolName` + `content` (required), `noteIndex`
 * optional (integer >= 1, only when known). Content may be derived from screen text, so it is
 * a private, LocalOnly output.
 */
class NoteToolContractTest {
    private val registry = defaultBuiltInToolRegistry
    private val executor = ValidatingToolExecutor(
        delegate = ActionExecutor(context = null, toolRegistry = registry),
        registry = registry,
    )

    @Test
    fun noteRequestPassesValidationEndToEnd() {
        val result = executor.execute(noteRequest("页面标题：搜索结果 - 北京天气"))

        assertEquals(result.summary, ToolStatus.Succeeded, result.status)
        assertEquals(MobileActionFunctions.NOTE, result.data["toolName"])
        assertEquals("页面标题：搜索结果 - 北京天气", result.data["content"])
        assertFalse("legacy noteContent key must be gone", "noteContent" in result.data)
        assertFalse("noteIndex is only emitted when known", "noteIndex" in result.data)
    }

    @Test
    fun noteContentIsTrimmed() {
        val result = executor.execute(noteRequest("  记下这个  "))

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("记下这个", result.data["content"])
    }

    @Test
    fun noteOutputIsPrivateLocalOnly() {
        val spec = requireNotNull(registry.specFor(MobileActionFunctions.NOTE))
        assertTrue("content" in spec.privateOutputKeys)
        assertEquals(ToolResultContinuationPolicy.LocalEvidence, spec.resultContinuationPolicy)
        assertNotNull(spec.redactedResultSummary)
        assertTrue(registry.requiresSequentialLocalModelBeforeTail(MobileActionFunctions.NOTE))

        val result = executor.execute(noteRequest("屏幕上的私密文本"))
        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("true", result.data["requiresLocalModel"])
        assertFalse(
            "private note content must not leak into the summary",
            result.summary.contains("屏幕上的私密文本"),
        )
    }

    @Test
    fun noteRejectsUndeclaredHintArgument() {
        val result = executor.execute(
            ToolRequest(
                toolName = MobileActionFunctions.NOTE,
                arguments = mapOf("content" to "x", "_noteIndexHint" to "3"),
            ),
        )

        assertEquals(ToolStatus.Rejected, result.status)
    }

    @Test
    fun noteIndexIsOptionalButMustBePositiveWhenPresent() {
        val request = noteRequest("abc")
        val base = mapOf(
            "toolName" to MobileActionFunctions.NOTE,
            "content" to "abc",
            "privacy" to MessagePrivacy.LocalOnly.name,
            "requiresLocalModel" to "true",
        )

        assertNull(registry.validateResult(request, request.succeeded("ok", base)))
        assertNull(registry.validateResult(request, request.succeeded("ok", base + ("noteIndex" to "1"))))
        val invalid = registry.validateResult(request, request.succeeded("ok", base + ("noteIndex" to "0")))
        assertEquals(ToolErrorCode.InvalidResult, invalid?.error?.code)
    }

    private fun noteRequest(content: String) =
        ToolRequest(
            toolName = MobileActionFunctions.NOTE,
            arguments = mapOf("content" to content),
        )
}
