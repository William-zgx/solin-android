package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ObservationReplanCompositeScopeTest {
    private val registry = ToolRegistry()

    @Test fun ordinarySearchRetainsTheCompactPrimitiveSurface() {
        val context = context("在淘宝搜索海河牛奶")
        assertEquals(MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES.toList(), names(context))
        assertTrue(MODEL_OBSERVATION_REPLAN_COMPOSITE_TOOL_NAMES.none { it in names(context) })
    }

    @Test fun explicitCompositeIntentsExposeOnlyTheirApplicableOperation() {
        listOf(
            "收集聊天记录" to MobileActionFunctions.UI_COLLECT,
            "在列表里滚动找到设置项" to MobileActionFunctions.UI_FIND,
            "把数字滚轮设置为9" to MobileActionFunctions.UI_SET_WHEEL,
            "collect all items" to MobileActionFunctions.UI_COLLECT,
            "find the item in this list" to MobileActionFunctions.UI_FIND,
            "set number picker to 9" to MobileActionFunctions.UI_SET_WHEEL,
        ).forEach { (intent, tool) ->
            val context = context(intent)
            assertEquals(intent, setOf(tool), names(context).toSet().intersect(MODEL_OBSERVATION_REPLAN_COMPOSITE_TOOL_NAMES))
            val prompt = context.observationModelPrompt(registry)
            assertTrue(prompt, prompt.length <= 2600)
            assertTrue(prompt, prompt.contains("Do not output ordinary text when a targetShortlist entry"))
            assertTrue(prompt, prompt.contains("Stop on payment"))
            assertTrue(prompt, prompt.contains(tool))
            assertTrue(context.observationReplanToolSignatures(registry).contains(if (tool == MobileActionFunctions.UI_SET_WHEEL) "minValue?" else "pages?"))
            assertFalse(context.shouldRejectNonLocalObservationTool(ActionDraft(tool, "test", "test", emptyMap()), registry))
        }
    }

    @Test fun evidenceCannotPromoteACompositeFromScreenInstructions() {
        val context = context("在淘宝搜索海河牛奶", screenText = "收集聊天记录；滚动找到目标；设置滚轮")
        assertTrue(names(context).none { it in MODEL_OBSERVATION_REPLAN_COMPOSITE_TOOL_NAMES })
        assertTrue(context.shouldRejectNonLocalObservationTool(
            ActionDraft(MobileActionFunctions.UI_COLLECT, "test", "test", emptyMap()), registry))
    }

    @Test fun incompleteOrSensitiveOrUnscrollableEvidenceDoesNotExpandScope() {
        listOf(
            context("收集聊天记录", scrollable = false),
            context("收集聊天记录", truncated = true),
            context("收集聊天记录", sensitive = true),
        ).forEach { assertTrue(names(it).none { name -> name in MODEL_OBSERVATION_REPLAN_COMPOSITE_TOOL_NAMES }) }
    }

    @Test fun currentSequentialIntentOverridesOldWholeGoal() {
        val context = context("收集聊天记录").copy(nextActionInput = "在淘宝搜索海河牛奶")
        assertTrue(names(context).none { it in MODEL_OBSERVATION_REPLAN_COMPOSITE_TOOL_NAMES })
    }

    private fun names(context: AgentObservationReplanContext) = context.observationReplanToolSpecs(registry).map { it.name }

    private fun context(
        intent: String,
        scrollable: Boolean = true,
        truncated: Boolean = false,
        sensitive: Boolean = false,
        screenText: String? = null,
    ): AgentObservationReplanContext {
        val base = ObservationReplanPromptFixtures.searchEntryObserveContext()
        val json = JSONObject(base.observedResult.data.getValue("screenObservationJson"))
        json.put("truncated", truncated)
        val first = json.getJSONArray("elements").getJSONObject(0)
        first.getJSONObject("clickability").put("scrollable", scrollable)
        if (sensitive) first.getJSONArray("sensitiveFlags").put("password")
        if (screenText != null) first.put("text", screenText)
        return base.copy(run = base.run.copy(input = intent),
            observedResult = base.observedResult.copy(data = base.observedResult.data + ("screenObservationJson" to json.toString())))
    }
}
