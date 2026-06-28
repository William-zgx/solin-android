package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.tool.RiskLevel
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCatalogTest {
    @Test
    fun rejectsDuplicateManifestIds() {
        val failure = expectIllegalArgument {
            SkillCatalog(
                listOf(
                    skillDefinition(id = "duplicate.skill", requiredTools = listOf("tool_a")),
                    skillDefinition(id = "duplicate.skill", requiredTools = listOf("tool_b")),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("Duplicate skill manifest id"))
        assertTrue(failure.message.orEmpty().contains("duplicate.skill"))
    }

    @Test
    fun rejectsDuplicateDirectToolNames() {
        val failure = expectIllegalArgument {
            SkillCatalog(
                listOf(
                    skillDefinition(id = "skill.a", requiredTools = listOf("shared_tool")),
                    skillDefinition(id = "skill.b", requiredTools = listOf("shared_tool")),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("Duplicate skill direct tool name"))
        assertTrue(failure.message.orEmpty().contains("shared_tool"))
    }

    private fun skillDefinition(
        id: String,
        requiredTools: List<String> = listOf("tool"),
        directToolNames: List<String> = requiredTools,
    ): SkillDefinition =
        SkillDefinition(
            manifest = SkillManifest(
                id = id,
                version = 1,
                title = id,
                description = "test skill",
                triggerExamples = emptyList(),
                requiredTools = requiredTools,
                inputSchemaJson = """{"type":"object","additionalProperties":false}""",
                riskLevel = RiskLevel.LowReadOnly,
            ),
            directToolNames = directToolNames,
        )

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException =
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (throwable: IllegalArgumentException) {
            throwable
        }
}
