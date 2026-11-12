package com.bytedance.zgx.solin.mcp

import com.bytedance.zgx.solin.module.SolinModuleRegistryImpl
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolCapabilityTag
import com.bytedance.zgx.solin.tool.ToolHandler
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
            tags = setOf(ToolCapabilityTag.McpTool)
        )
        val pingHandler = ToolHandler { _: ToolRequest ->
            ToolResult(
                requestId = "",
                status = ToolStatus.Succeeded,
                summary = "pong"
            )
        }

        // Build a fake McpClient that returns our stub binding without wiring
        // a transport/connection (McpClient is open for testing). Pass an empty
        // test-only registry; the fake overrides registration so the registry is unused.
        val fakeClient = object : McpClient(McpServerRegistry()) {
            override fun registrationBindings(): List<McpToolBinding> = listOf(McpToolBinding(pingSpec, pingHandler))
        }

        val reg = SolinModuleRegistryImpl()
        McpModule(fakeClient).register(reg)

        // The ToolProvider exposes the spec
        val allSpecs = reg.toolProviders.flatMap { it.specs() }
        assertTrue(
            "expected spec $specName in registered providers, got $allSpecs",
            allSpecs.any { it.name == specName }
        )

        // Handler registered by name
        val handler = reg.toolHandlers[specName]
        assertNotNull("handler for $specName should be registered", handler)

        // Dispatch returns the "pong" result
        val result = handler!!.execute(ToolRequest(toolName = specName, arguments = emptyMap()))
        assertNotNull(result)
        assertEquals(ToolStatus.Succeeded, result!!.status)
        assertTrue(
            "summary should contain pong, got '${result.summary}'",
            result.summary.contains("pong")
        )
    }

    @Test
    fun registrationReadsOneCatalogSnapshotForSpecsAndHandlers() {
        val serverId = "com.example.mcp/com.example.mcp.Server"
        val approvedTools = (1..4).map { index ->
            requireNotNull(
                McpToolSpecs.approvedSnapshotOrNull(
                    McpTool(
                        name = "lookup$index",
                        description = "Lookup $index",
                        inputSchema = mapOf(
                            "type" to "object",
                            "required" to listOf("query"),
                            "properties" to mapOf("query" to mapOf("type" to "string")),
                            "additionalProperties" to false
                        )
                    )
                )
            )
        }
        val settings = CountingSettings(
            InMemoryMcpCapabilitySettings(
                enabled = true,
                disclosureAcknowledged = true,
                servers = listOf(McpServerEntry(serverId, "Example", approvedTools = approvedTools))
            )
        )
        val serverRegistry = McpServerRegistry(context = null, settings = settings)
        settings.resetCatalogReads()
        val reg = SolinModuleRegistryImpl()

        McpModule(McpClient(serverRegistry, settings)).register(reg)

        val frozen = reg.freeze()
        val specs = frozen.toolProviders.flatMap { it.specs() }
        val specNames = specs.map { it.name }.toSet()
        assertEquals("registration must not re-read a changed catalog", 1, settings.catalogReads)
        assertEquals(approvedTools.size, specNames.size)
        assertEquals("specs and handlers must come from the same revision", specNames, frozen.toolHandlers.keys)
    }

    private class CountingSettings(
        private val delegate: InMemoryMcpCapabilitySettings
    ) : McpCapabilitySettings by delegate {
        var catalogReads: Int = 0
            private set

        fun resetCatalogReads() {
            catalogReads = 0
        }

        override fun servers(): List<McpServerEntry> {
            catalogReads += 1
            // A second read simulates a newer revision that revoked every tool.
            return if (catalogReads == 1) delegate.servers() else emptyList()
        }
    }
}
