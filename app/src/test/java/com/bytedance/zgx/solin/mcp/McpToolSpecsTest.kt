package com.bytedance.zgx.solin.mcp

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.safety.SafetyContext
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapabilityTag
import com.bytedance.zgx.solin.tool.ToolProvider
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResultContinuationPolicy
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.isRemoteModelPlanningEligible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolSpecsTest {
    private val entry = McpServerEntry(serverId = "com.example.mcp/.Server", displayName = "Example")

    @Test
    fun everyMcpToolRequiresConfirmationAndIsAtLeastMediumRisk() {
        val spec = requireNotNull(McpToolSpecs.toToolSpecOrNull(entry, approved("lookup")))

        assertTrue(spec.riskLevel >= RiskLevel.MediumDraftOrNavigation)
        assertEquals(RiskLevel.HighExternalSend, spec.riskLevel)
        assertEquals(ConfirmationPolicy.Required, spec.confirmationPolicy)
        assertTrue(ToolCapabilityTag.McpTool in spec.tags)
        assertFalse("MCP tools are never offered to remote planning", spec.isRemoteModelPlanningEligible())
        val request = ToolRequest(toolName = spec.name, arguments = mapOf("query" to "x"))
        val decision = SafetyPolicy().evaluate(spec, request, SafetyContext(userConfirmed = false))
        assertEquals(SafetyOutcome.RequireConfirmation, decision.outcome)
    }

    @Test
    fun resultsAreLocalOnlyByDefaultAndRemoteEligibleOnlyForExplicitlyPublicServers() {
        val localSpec = requireNotNull(McpToolSpecs.toToolSpecOrNull(entry, approved("lookup")))
        val publicEntry = entry.copy(resultsPublic = true)
        val publicSpec = requireNotNull(McpToolSpecs.toToolSpecOrNull(publicEntry, approved("lookup")))
        val request = ToolRequest(toolName = localSpec.name)
        val raw = mapOf("content" to listOf(mapOf("type" to "text", "text" to "秘密结果")))

        val local = McpToolSpecs.toToolResult(request, entry, raw)
        val public = McpToolSpecs.toToolResult(request, publicEntry, raw)

        assertEquals(ToolResultContinuationPolicy.LocalEvidence, localSpec.resultContinuationPolicy)
        assertEquals(setOf(McpToolSpecs.DATA_TEXT), localSpec.privateOutputKeys)
        assertEquals(MessagePrivacy.LocalOnly.name, local.data[McpToolSpecs.DATA_PRIVACY])
        assertEquals("true", local.data[McpToolSpecs.DATA_REQUIRES_LOCAL_MODEL])
        assertEquals("秘密结果", local.data[McpToolSpecs.DATA_TEXT])
        assertFalse("LocalOnly text must not leak into the summary", local.summary.contains("秘密结果"))

        assertEquals(ToolResultContinuationPolicy.None, publicSpec.resultContinuationPolicy)
        assertTrue(publicSpec.privateOutputKeys.isEmpty())
        assertEquals(MessagePrivacy.RemoteEligible.name, public.data[McpToolSpecs.DATA_PRIVACY])
        assertEquals("false", public.data[McpToolSpecs.DATA_REQUIRES_LOCAL_MODEL])
    }

    @Test
    fun invalidNamesOversizedOrUnsupportedSchemasAreDroppedInsteadOfCrashingRegistry() {
        assertNull(McpToolSpecs.approvedSnapshotOrNull(McpTool("bad name!", null, flatSchema())))
        assertNull(McpToolSpecs.approvedSnapshotOrNull(McpTool("x".repeat(49), null, flatSchema())))
        val huge = mapOf(
            "type" to "object",
            "properties" to (0 until 400).associate { "p$it" to mapOf("type" to "string", "description" to "d".repeat(30)) },
            "additionalProperties" to false,
        )
        assertNull(McpToolSpecs.approvedSnapshotOrNull(McpTool("huge", null, huge)))

        // Typical MCP schema without additionalProperties=false is outside Solin's closed dialect.
        val open = McpToolSpecs.approvedSnapshotOrNull(
            McpTool("open", null, mapOf("type" to "object", "properties" to emptyMap<String, Any?>())),
        )
        assertNotNull(open)
        assertNull(McpToolSpecs.toToolSpecOrNull(entry, open!!))

        val accepted = listOfNotNull(
            McpToolSpecs.toToolSpecOrNull(entry, approved("lookup")),
            McpToolSpecs.toToolSpecOrNull(entry, approved("other")),
        )
        val registry = ToolRegistry(ToolProvider { accepted })
        assertEquals(2, registry.specs().size)
    }

    @Test
    fun toolNamesAreBoundedAndDistinctPerServer() {
        val longName = "t".repeat(McpToolSpecs.MAX_TOOL_NAME_CHARS)
        val a = McpToolSpecs.solinToolName("com.a/.S", longName)
        val b = McpToolSpecs.solinToolName("com_a/.S", longName)

        assertTrue(a.length <= 64)
        assertTrue(Regex("^[A-Za-z0-9_-]+$").matches(a))
        assertNotEquals("sanitization must not alias two servers", a, b)
    }

    @Test
    fun descriptionsAreSanitizedAndBounded() {
        val snapshot = McpToolSpecs.approvedSnapshotOrNull(
            McpTool("lookup", "line\u0000with\u0007control" + "x".repeat(1_000), flatSchema()),
        )!!

        assertFalse(snapshot.description.contains('\u0000'))
        assertTrue(snapshot.description.length <= McpToolSpecs.MAX_DESCRIPTION_CHARS)
    }

    @Test
    fun argumentsAreTypedFromTheApprovedSchemaNotGuessed() {
        val schema = canonicalJson(
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "zip" to mapOf("type" to "string"),
                    "count" to mapOf("type" to "integer"),
                    "exact" to mapOf("type" to "boolean"),
                    "ratio" to mapOf("type" to "number"),
                ),
                "additionalProperties" to false,
            ),
        )

        val typed = McpToolSpecs.typedArguments(
            schema,
            mapOf("zip" to "00123", "count" to "7", "exact" to "true", "ratio" to "0.5"),
        )

        assertEquals("00123", typed["zip"])
        assertEquals(7L, typed["count"])
        assertEquals(true, typed["exact"])
        assertEquals(0.5, typed["ratio"])
    }

    @Test
    fun binaryContentIsNeverForwardedAndTextIsCapped() {
        val request = ToolRequest(toolName = "mcp__x__lookup")
        val result = McpToolSpecs.toToolResult(
            request,
            entry,
            mapOf(
                "isError" to false,
                "content" to listOf(
                    mapOf("type" to "image", "data" to "QUJD", "mimeType" to "image/png"),
                    mapOf("type" to "text", "text" to "a".repeat(10_000)),
                    "not-a-map",
                ),
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(request.id, result.requestId)
        assertEquals("true", result.data[McpToolSpecs.DATA_TRUNCATED])
        assertEquals("2", result.data[McpToolSpecs.DATA_OMITTED_CONTENT_COUNT])
        assertTrue(result.data.values.none { it.contains("QUJD") })
    }

    @Test
    fun canonicalJsonIsKeyOrderIndependent() {
        assertEquals(
            canonicalJson(mapOf("b" to 1, "a" to listOf(true, null, "x"))),
            canonicalJson(linkedMapOf("a" to listOf(true, null, "x"), "b" to 1L)),
        )
    }

    private fun approved(name: String): McpApprovedTool =
        requireNotNull(McpToolSpecs.approvedSnapshotOrNull(McpTool(name, "desc", flatSchema())))

    private fun flatSchema(): Map<String, Any?> =
        mapOf(
            "type" to "object",
            "properties" to mapOf("query" to mapOf("type" to "string")),
            "additionalProperties" to false,
        )
}
