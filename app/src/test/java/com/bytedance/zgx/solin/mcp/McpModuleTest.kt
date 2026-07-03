package com.bytedance.zgx.solin.mcp

import com.bytedance.zgx.solin.module.SolinModuleRegistryImpl
import com.bytedance.zgx.solin.module.ToolHandler
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolCapabilityTag
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.ToolStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpModuleTest {
    @Test
    fun registersApprovedToolsAndHandlers() = runTest {
        val specName = "mcp__test__ping"
        val pingSpec = ToolSpec(
            name = specName,
            title = "ping (test)",
            description = "MCP ping tool",
            inputSchemaJson = """{"type":"object"}""",
            capability = ToolCapability.Extension,
            riskLevel = RiskLevel.HighExternalSend,
            confirmationPolicy = ConfirmationPolicy.Required,
            tags = setOf(ToolCapabilityTag.McpTool),
        )
        val pingHandler = ToolHandler { _: ToolRequest ->
            ToolResult(
                requestId = "",
                status = ToolStatus.Succeeded,
                summary = "pong",
            )
        }

        // Build a fake McpClient that returns our stub spec/handler without wiring
        // a transport/connection (McpClient is open for testing). Pass an empty
        // test-only registry; the fake overrides all methods so the registry is unused.
        val fakeClient = object : McpClient(McpServerRegistry()) {
            override fun allApprovedToolSpecs(): List<ToolSpec> = listOf(pingSpec)
            override fun handlerFor(toolName: String): ToolHandler? =
                if (toolName == specName) pingHandler else null
        }

        val reg = SolinModuleRegistryImpl()
        McpModule(fakeClient).register(reg)

        // The ToolProvider exposes the spec
        val allSpecs = reg.toolProviders.flatMap { it.specs() }
        assertTrue("expected spec $specName in registered providers, got $allSpecs",
            allSpecs.any { it.name == specName })

        // Handler registered by name
        val handler = reg.toolHandlers[specName]
        assertNotNull("handler for $specName should be registered", handler)

        // Dispatch returns the "pong" result
        val result = handler!!.execute(ToolRequest(toolName = specName, arguments = emptyMap()))
        assertNotNull(result)
        assertEquals(ToolStatus.Succeeded, result!!.status)
        assertTrue("summary should contain pong, got '${result.summary}'",
            result.summary.contains("pong"))
    }
}
