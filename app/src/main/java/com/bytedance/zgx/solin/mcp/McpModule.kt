package com.bytedance.zgx.solin.mcp

import com.bytedance.zgx.solin.module.SolinModule
import com.bytedance.zgx.solin.module.SolinModuleRegistry
import com.bytedance.zgx.solin.tool.ToolProvider

/**
 * SolinModule that exposes user-approved MCP tools to the ToolRegistry. Each approved MCP tool
 * becomes a ToolSpec (HighExternalSend risk, confirmation Required, LocalOnly results unless the
 * server is marked public) and a ToolHandler that proxies `tools/call` after re-checking the
 * Trust Center opt-in, approval catalog, peer verification, and schema snapshot.
 *
 * The module registry is frozen at app start, so the exposed tool set is the approved catalog
 * at that moment: turning MCP ON or approving new tools takes effect on the next app start,
 * while turning it OFF or revoking a tool takes effect immediately at call time.
 */
class McpModule(
    private val client: McpClient
) : SolinModule {
    override val moduleId: String get() = "mcp:core"

    override fun register(registry: SolinModuleRegistry) {
        val bindings = client.registrationBindings()
        val specs = bindings.map { it.spec }
        registry.addToolProvider(ToolProvider { specs })
        bindings.forEach { binding ->
            registry.addToolHandler(binding.spec.name, binding.handler)
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
