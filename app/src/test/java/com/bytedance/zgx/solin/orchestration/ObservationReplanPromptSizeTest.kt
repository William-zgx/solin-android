package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.actionPrompt
import com.bytedance.zgx.solin.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measured size of the local observation-replanner prompt for a canonical in-app step (post-launch
 * observe of an app home screen, "search in Taobao").
 *
 * Baseline (before the tool-scope/few-shot change, same fixture):
 *  - replanner prompt: 2,399 chars, but only after the over-cap fallback DROPPED four rule lines
 *    ("Decide if", "Prefer a type-tagged target", "If only tap=", "Do not output ordinary text");
 *  - model input as wrapped by action/ActionPlanningRuntime.actionPrompt with all 45 tool specs:
 *    7,696 chars (5,297 of them tool specs).
 */
class ObservationReplanPromptSizeTest {
    private val registry = ToolRegistry()

    @Test
    fun promptKeepsEveryRuleLineWithinTheExistingCapAndAddsFewShotExamples() {
        val prompt = ObservationReplanPromptFixtures.searchEntryObserveContext().observationModelPrompt(registry)

        assertTrue(prompt.length <= 2_600)
        listOf(
            "Output exactly one call",
            "Examples: call:ui_tap{",
            "Allowed tools: observe_current_screen,ui_tap{target},ui_type_text{target,text},ui_submit_search,",
            "output only these local device-control tools",
            "Do not output web_search",
            "copy only the candidate target=... value into the tool target",
            "Stop on payment",
            "If the latest action failed, avoid the same target",
            "tap -> type -> submit",
            "stop if no scroll candidate/evidence exists",
            "Do not output ordinary text when a targetShortlist entry",
            "targetShortlist(tap=search-entry",
        ).forEach { expected -> assertTrue("missing: $expected\n$prompt", prompt.contains(expected)) }
        assertTrue("signature line is never cut", prompt.lines().single { it.startsWith("Allowed tools:") }.endsWith("ui_press_back"))
    }

    @Test
    fun scopedToolSpecsShrinkTheWrappedModelInput() {
        val context = ObservationReplanPromptFixtures.searchEntryObserveContext()
        val prompt = context.observationModelPrompt(registry)

        val wrappedAll = actionPrompt(prompt, registry.specs()).length
        val wrappedScoped = actionPrompt(prompt, context.observationReplanToolSpecs(registry)).length
        println("ObservationReplanPromptSize prompt=${prompt.length} wrappedAll=$wrappedAll wrappedScoped=$wrappedScoped")

        // The registry keeps growing, so the full-spec wrap is only a reference point; replanner calls
        // pass the scoped list (ModelObservationReplanner -> ActionPlanningRuntime.plan(toolSpecs)).
        assertTrue("wrappedScoped=$wrappedScoped wrappedAll=$wrappedAll", wrappedScoped <= (wrappedAll * 0.6).toInt())
        // Scoped wrap stays >= 40% under the pre-scope 7,696-char baseline.
        assertTrue("wrappedScoped=$wrappedScoped", wrappedScoped <= (BASELINE_WRAPPED_ALL_SPECS * 0.6).toInt())
    }

    @Test
    fun toolScopeIsDeviceControlOnlyUnderLocalOnlyEvidence() {
        val gated = ObservationReplanPromptFixtures.searchEntryObserveContext()
        assertEquals(
            MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES.toList(),
            gated.observationReplanToolSpecs(registry).map { it.name },
        )

        val ungated = gated.copy(
            observedResult = gated.observedResult.copy(
                data = mapOf("toolName" to "web_search", "privacy" to "RemoteEligible", "requiresLocalModel" to "false"),
            ),
            previousRequest = gated.previousRequest.copy(toolName = MobileActionFunctions.WEB_SEARCH),
        )
        assertEquals(
            MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES.toList() + IN_APP_LOOP_CONTROL_TOOL_NAMES,
            ungated.observationReplanToolSpecs(registry).map { it.name },
        )
        assertTrue(
            ungated.observationReplanToolSignatures(registry)
                .endsWith("finish{message},ask_user{prompt},take_over{reason},note{content}"),
        )
    }

    private companion object {
        const val BASELINE_WRAPPED_ALL_SPECS = 7_696
    }
}
