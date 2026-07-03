package com.bytedance.zgx.solin.module

class SolinModuleRegistryImpl : SolinModuleRegistry {
    private val _toolProviders = mutableListOf<com.bytedance.zgx.solin.tool.ToolProvider>()
    private val _toolHandlers = mutableMapOf<String, ToolHandler>()
    private val _skillSources = mutableListOf<com.bytedance.zgx.solin.skill.SkillSource>()

    val toolProviders: List<com.bytedance.zgx.solin.tool.ToolProvider> get() = _toolProviders.toList()
    val toolHandlers: Map<String, ToolHandler> get() = _toolHandlers.toMap()
    val skillSources: List<com.bytedance.zgx.solin.skill.SkillSource> get() = _skillSources.toList()

    override fun addToolProvider(provider: com.bytedance.zgx.solin.tool.ToolProvider) { _toolProviders += provider }
    override fun addToolHandler(toolName: String, handler: ToolHandler) { _toolHandlers[toolName] = handler }
    override fun addSkillSource(source: com.bytedance.zgx.solin.skill.SkillSource) { _skillSources += source }
}
