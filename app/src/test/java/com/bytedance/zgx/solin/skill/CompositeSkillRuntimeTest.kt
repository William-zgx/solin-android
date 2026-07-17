package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CompositeSkillRuntimeTest {
    @Test
    fun `registered planner plan is returned`() {
        val manifest = manifest("skill.one")
        val runtime = CompositeSkillRuntime(
            sources = listOf(SkillSource { listOf(manifest) }),
            planners = listOf(FakeRuntime(manifest)),
        )

        assertEquals(manifest, runtime.manifests().single())
        assertNotNull(runtime.plan("run"))
    }

    @Test
    fun `duplicate manifests are rejected`() {
        val manifest = manifest("skill.one")

        assertThrows(IllegalArgumentException::class.java) {
            CompositeSkillRuntime(
                sources = listOf(
                    SkillSource { listOf(manifest) },
                    SkillSource { listOf(manifest) },
                ),
                planners = emptyList(),
            )
        }
    }

    @Test
    fun `planner exposing an unregistered manifest is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CompositeSkillRuntime(
                sources = listOf(SkillSource { listOf(manifest("skill.one")) }),
                planners = listOf(FakeRuntime(manifest("skill.other"))),
            )
        }
    }

    @Test
    fun `discovery returns descriptors without planning internals`() {
        val runtime = CompositeSkillRuntime(
            sources = listOf(
                SkillSource {
                    listOf(
                        manifest("calendar.search", title = "Calendar search"),
                        manifest("contact.lookup", title = "Contact lookup"),
                    )
                },
            ),
            planners = emptyList(),
        )

        assertEquals(listOf("calendar.search"), runtime.discover("calendar").map { it.id })
    }

    private fun manifest(id: String, title: String = id): SkillManifest = SkillManifest(
        id = id,
        version = 1,
        title = title,
        description = title,
        triggerExamples = listOf(title),
        requiredTools = emptyList(),
        inputSchemaJson = """{"type":"object"}""",
        riskLevel = RiskLevel.LowReadOnly,
    )

    private class FakeRuntime(private val manifest: SkillManifest) : SkillRuntime {
        override fun manifests(): List<SkillManifest> = listOf(manifest)

        override fun plan(input: String): SkillPlan = SkillPlan(
            request = SkillRequest(
                id = "request",
                skillId = manifest.id,
                arguments = emptyMap(),
                reason = input,
            ),
            manifest = manifest,
            steps = emptyList(),
        )

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan = plan(input)
    }
}
