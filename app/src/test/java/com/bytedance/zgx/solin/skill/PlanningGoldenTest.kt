package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.action.MobileActionPlanner
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden routing test for the rule-based planners, used to keep refactors of BuiltInSkills /
 * ActionPlanner / BasicDraftActionParsers behaviour-preserving.
 *
 * For every input in `planning_golden/inputs.txt` it renders the skill plan (skill id, step ids,
 * dependencies, tool names, arguments, bindings) and the rule planner's draft, and compares the
 * result with `planning_golden/expected.txt`. Step IDs are persisted in checkpoints, and branch
 * order decides routing, so any diff here must be an intentional change.
 *
 * To regenerate after an intentional change, run the test with SOLIN_UPDATE_PLANNING_GOLDEN=true
 * in the environment and review the diff of expected.txt.
 */
class PlanningGoldenTest {
    private val fixedNow = Instant.parse("2026-06-14T02:00:00Z").toEpochMilli()

    @Test
    fun rulePlannersMatchGolden() {
        val inputs = resource("inputs.txt").lines().filter { it.isNotBlank() }
        val actual = inputs.joinToString("\n") { input -> render(input) } + "\n"
        val goldenFile = File("src/test/resources/planning_golden/expected.txt")
        if (System.getProperty("solin.updatePlanningGolden") == "true" || System.getenv("SOLIN_UPDATE_PLANNING_GOLDEN") == "true") {
            goldenFile.writeText(actual)
            return
        }
        assertEquals(resource("expected.txt"), actual)
    }

    private fun render(input: String): String {
        val lines = mutableListOf("## $input")
        listOf(AppSearchPlanningMode.StaticSkill, AppSearchPlanningMode.ModelDrivenBootstrap).forEach { mode ->
            val runtime = BuiltInSkillRuntime(
                clockMillis = { fixedNow },
                zoneId = ZoneOffset.UTC,
                appSearchPlanningModeProvider = { mode },
            )
            val plan = runCatching { runtime.plan(input) }
            lines += "skill[$mode]: " + plan.fold(
                onSuccess = { it?.render() ?: "null" },
                onFailure = { "THROWS ${it::class.java.simpleName}" },
            )
        }
        val planner = MobileActionPlanner(clockMillis = { fixedNow }, zoneId = ZoneOffset.UTC)
        val draft = planner.plan(input)
        lines += "action: ${draft.kind} ${draft.draft?.let { "${it.functionName} ${it.parameters.toSortedMap()} title=${it.title} summary=${it.summary}" }}"
        lines += "intent: ${planner.classifyIntent(input).let { "${it.toolName} ${it.confidence}" }}"
        return lines.joinToString("\n")
    }

    private fun SkillPlan.render(): String =
        buildString {
            append(manifest.id)
            append(" reason=").append(request.reason)
            steps.forEach { step ->
                append("\n    - ").append(step.id).append(" deps=").append(step.dependsOn)
                when (step) {
                    is SkillStep.ToolStep -> {
                        append(" tool=").append(step.request.toolName)
                        append(" args=").append(step.request.arguments.toSortedMap())
                        append(" bind=").append(step.argumentBindings.toSortedMap())
                        append(" title=").append(step.draft.title)
                        append(" summary=").append(step.draft.summary)
                        append(" reason=").append(step.request.reason)
                    }
                    else -> append(" ").append(step.toString())
                }
            }
        }

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("planning_golden/$name")) {
            "missing planning_golden/$name"
        }.bufferedReader(Charsets.UTF_8).readText()
}
