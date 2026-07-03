package com.bytedance.zgx.solin.mcp

import com.bytedance.zgx.solin.module.SolinModule
import com.bytedance.zgx.solin.module.SolinModuleRegistry
import com.bytedance.zgx.solin.tool.ToolProvider

/**
 * SolinModule that exposes MCP-approved tools to the ToolRegistry. Each
 * approved MCP tool becomes a ToolSpec (with conservative High risk /
 * Required confirmation) and a corresponding ToolHandler that proxies
 * tools/call over the bound Binder transport.
 *
 * Wave 6: registry is empty by default; no servers are shipped with v1.
 * Users add servers explicitly via a future management UI.
 */
class McpModule(
    private val client: McpClient,
) : SolinModule {
    override val moduleId: String get() = "mcp:core"

    override fun register(registry: SolinModuleRegistry) {
        registry.addToolProvider(ToolProvider { client.allApprovedToolSpecs() })
        // Register a handler for every approved spec by name
        val specs = client.allApprovedToolSpecs()
        specs.forEach { spec ->
            client.handlerFor(spec.name)?.let { registry.addToolHandler(spec.name, it) }
        }
    }

    companion object {
        /** Create a disabled (no-op) module for configurations where MCP is not enabled. */
        fun disabled(): SolinModule = object : SolinModule {
            override val moduleId: String get() = "mcp:disabled"
            override fun register(registry: SolinModuleRegistry) { /* no-op */ }
        }
    }
}
