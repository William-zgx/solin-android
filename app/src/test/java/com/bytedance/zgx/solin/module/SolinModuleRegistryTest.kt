package com.bytedance.zgx.solin.module

import com.bytedance.zgx.solin.skill.SkillManifest
import com.bytedance.zgx.solin.skill.SkillSource
import com.bytedance.zgx.solin.tool.BuiltInToolsModule
import com.bytedance.zgx.solin.skill.BuiltInSkillsModule
import com.bytedance.zgx.solin.tool.ToolProvider
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `addToolHandler overwrites per toolName`() {
        val reg = SolinModuleRegistryImpl()
        val h1 = ToolHandler { null }
        val h2 = ToolHandler { ToolResult(requestId = it.id, status = ToolStatus.Succeeded, summary = "ok") }
        reg.addToolHandler("foo", h1)
        reg.addToolHandler("foo", h2)
        assertEquals(1, reg.toolHandlers.size)
        assertTrue(reg.toolHandlers["foo"] === h2)
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
        val manifests = reg.skillSources.first().manifests()
        assertTrue(manifests.isNotEmpty())
    }
}
