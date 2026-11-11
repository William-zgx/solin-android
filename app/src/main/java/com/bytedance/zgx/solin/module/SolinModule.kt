package com.bytedance.zgx.solin.module

import com.bytedance.zgx.solin.skill.SkillSource
import com.bytedance.zgx.solin.tool.ToolHandler
import com.bytedance.zgx.solin.tool.ToolProvider

/**
 * A compile-time registration unit for Solin extensions.
 *
 * Modules are discovered via a static listOf(...) in SolinAppContainer.
 * Third-party (out-of-process) extensions come later over Binder (#15);
 * this seam is for in-process first-party Kotlin modules only.
 *
 * TODO: when Hilt/Dagger lands, migrate to @IntoSet multibindings.
 */
interface SolinModule {
    val moduleId: String
    fun register(registry: SolinModuleRegistry)
}

interface SolinModuleRegistry {
    fun addToolProvider(provider: ToolProvider)
    fun addToolHandler(toolName: String, handler: ToolHandler)
    fun addSkillSource(source: SkillSource)
}

data class FrozenSolinModuleRegistry(
    val toolProviders: List<ToolProvider>,
    val toolHandlers: Map<String, ToolHandler>,
    val skillSources: List<SkillSource>
)
