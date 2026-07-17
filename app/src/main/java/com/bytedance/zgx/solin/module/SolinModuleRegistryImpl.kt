package com.bytedance.zgx.solin.module

import com.bytedance.zgx.solin.skill.SkillSource
import com.bytedance.zgx.solin.tool.ToolProvider

class SolinModuleRegistryImpl : SolinModuleRegistry {
    private val mutableToolProviders = mutableListOf<ToolProvider>()
    private val mutableToolHandlers = linkedMapOf<String, ToolHandler>()
    private val mutableSkillSources = mutableListOf<SkillSource>()
    private var frozenSnapshot: FrozenSolinModuleRegistry? = null

    val toolProviders: List<ToolProvider> get() = frozenSnapshot?.toolProviders ?: mutableToolProviders.toList()
    val toolHandlers: Map<String, ToolHandler> get() = frozenSnapshot?.toolHandlers ?: mutableToolHandlers.toMap()
    val skillSources: List<SkillSource> get() = frozenSnapshot?.skillSources ?: mutableSkillSources.toList()

    override fun addToolProvider(provider: ToolProvider) {
        checkMutable()
        mutableToolProviders += provider
    }

    override fun addToolHandler(toolName: String, handler: ToolHandler) {
        checkMutable()
        require(toolName.isNotBlank()) { "Tool handler name must not be blank." }
        require(toolName !in mutableToolHandlers) { "Duplicate tool handler: $toolName" }
        mutableToolHandlers[toolName] = handler
    }

    override fun addSkillSource(source: SkillSource) {
        checkMutable()
        mutableSkillSources += source
    }

    fun freeze(): FrozenSolinModuleRegistry {
        frozenSnapshot?.let { return it }

        val materializedProviders = mutableToolProviders.map { provider ->
            val specs = provider.specs().toList()
            ToolProvider { specs }
        }
        val toolNames = materializedProviders
            .flatMap { provider -> provider.specs() }
            .map { spec -> spec.name }
        val duplicateToolNames = toolNames.duplicates()
        require(duplicateToolNames.isEmpty()) {
            "Duplicate tool spec name(s): ${duplicateToolNames.joinToString()}"
        }
        val handlersWithoutSpecs = mutableToolHandlers.keys - toolNames.toSet()
        require(handlersWithoutSpecs.isEmpty()) {
            "Tool handler(s) missing registered spec: ${handlersWithoutSpecs.sorted().joinToString()}"
        }

        val materializedSkillSources = mutableSkillSources.map { source ->
            val manifests = source.manifests().toList()
            SkillSource { manifests }
        }
        val skillIds = materializedSkillSources
            .flatMap { source -> source.manifests() }
            .map { manifest -> manifest.id }
        val duplicateSkillIds = skillIds.duplicates()
        require(duplicateSkillIds.isEmpty()) {
            "Duplicate skill manifest id(s): ${duplicateSkillIds.joinToString()}"
        }

        return FrozenSolinModuleRegistry(
            toolProviders = materializedProviders,
            toolHandlers = mutableToolHandlers.toMap(),
            skillSources = materializedSkillSources,
        ).also { snapshot -> frozenSnapshot = snapshot }
    }

    private fun checkMutable() {
        check(frozenSnapshot == null) { "Solin module registry is frozen." }
    }
}

private fun List<String>.duplicates(): List<String> =
    groupingBy { value -> value }
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys
        .sorted()
