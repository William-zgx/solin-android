package com.bytedance.zgx.solin.module

import com.bytedance.zgx.solin.skill.SkillManifest
import com.bytedance.zgx.solin.skill.SkillSource
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.BuiltInToolsModule
import com.bytedance.zgx.solin.skill.BuiltInSkillsModule
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolProvider
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SolinModuleRegistryTest {

    @Test
    fun `addToolProvider accumulates`() {
        val reg = SolinModuleRegistryImpl()
        val p1 = ToolProvider { emptyList() }
        val p2 = ToolProvider { emptyList() }
        reg.addToolProvider(p1)
        reg.addToolProvider(p2)
        assertEquals(2, reg.toolProviders.size)
    }

    @Test
    fun `duplicate tool handlers are rejected`() {
        val reg = SolinModuleRegistryImpl()
        val h1 = ToolHandler { null }
        val h2 = ToolHandler { ToolResult(requestId = it.id, status = ToolStatus.Succeeded, summary = "ok") }
        reg.addToolHandler("foo", h1)

        assertThrows(IllegalArgumentException::class.java) {
            reg.addToolHandler("foo", h2)
        }
    }

    @Test
    fun `addSkillSource accumulates`() {
        val reg = SolinModuleRegistryImpl()
        val s1 = SkillSource { emptyList() }
        val s2 = SkillSource { emptyList() }
        reg.addSkillSource(s1)
        reg.addSkillSource(s2)
        assertEquals(2, reg.skillSources.size)
    }

    @Test
    fun `freeze materializes providers once and blocks later registration`() {
        val reg = SolinModuleRegistryImpl()
        var calls = 0
        reg.addToolProvider(
            ToolProvider {
                calls += 1
                listOf(toolSpec("foo"))
            },
        )

        val first = reg.freeze()
        val second = reg.freeze()

        assertTrue(first === second)
        assertEquals(listOf("foo"), first.toolProviders.flatMap { it.specs() }.map { it.name })
        assertEquals(listOf("foo"), second.toolProviders.flatMap { it.specs() }.map { it.name })
        assertEquals(1, calls)
        assertThrows(IllegalStateException::class.java) {
            reg.addSkillSource(SkillSource { emptyList() })
        }
    }

    @Test
    fun `freeze materializes skill sources once and rejects duplicate ids`() {
        val reg = SolinModuleRegistryImpl()
        var calls = 0
        val manifest = skillManifest("skill.one")
        reg.addSkillSource(
            SkillSource {
                calls += 1
                listOf(manifest)
            },
        )

        val frozen = reg.freeze()

        assertEquals(listOf(manifest), frozen.skillSources.single().manifests())
        assertEquals(listOf(manifest), frozen.skillSources.single().manifests())
        assertEquals(1, calls)

        val duplicate = SolinModuleRegistryImpl()
        duplicate.addSkillSource(SkillSource { listOf(manifest) })
        duplicate.addSkillSource(SkillSource { listOf(manifest) })
        assertThrows(IllegalArgumentException::class.java) {
            duplicate.freeze()
        }
    }

    @Test
    fun `freeze rejects duplicate tool specs`() {
        val reg = SolinModuleRegistryImpl()
        reg.addToolProvider(ToolProvider { listOf(toolSpec("foo")) })
        reg.addToolProvider(ToolProvider { listOf(toolSpec("foo")) })

        assertThrows(IllegalArgumentException::class.java) {
            reg.freeze()
        }
    }

    @Test
    fun `freeze rejects handlers without registered specs`() {
        val reg = SolinModuleRegistryImpl()
        reg.addToolHandler("foo", ToolHandler { null })

        assertThrows(IllegalArgumentException::class.java) {
            reg.freeze()
        }
    }

    @Test
    fun `BuiltInToolsModule registers a provider containing ask_user`() {
        val reg = SolinModuleRegistryImpl()
        BuiltInToolsModule().register(reg)
        assertEquals(1, reg.toolProviders.size)
        val specNames = reg.toolProviders.first().specs().map { it.name }
        assertTrue(
            "Expected ask_user in built-in tool specs, got $specNames",
            specNames.contains("ask_user"),
        )
    }

    @Test
    fun `BuiltInSkillsModule registers a source with non-empty manifests`() {
        val reg = SolinModuleRegistryImpl()
        BuiltInSkillsModule().register(reg)
        assertEquals(1, reg.skillSources.size)
        assertTrue(reg.skillSources.first().manifests().isNotEmpty())
    }

    private fun skillManifest(id: String): SkillManifest = SkillManifest(
        id = id,
        version = 1,
        title = id,
        description = id,
        triggerExamples = emptyList(),
        requiredTools = emptyList(),
        inputSchemaJson = """{"type":"object"}""",
        riskLevel = RiskLevel.LowReadOnly,
    )

    private fun toolSpec(name: String): ToolSpec = ToolSpec(
        name = name,
        title = name,
        description = name,
        inputSchemaJson = """{"type":"object"}""",
        capability = ToolCapability.Extension,
    )
}
