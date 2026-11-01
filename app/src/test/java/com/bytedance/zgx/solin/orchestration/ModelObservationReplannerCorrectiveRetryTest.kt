package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single corrective re-prompt: a guard-rejected model draft gets exactly one more model call
 * that names the rejection and the allowed shortlist; the corrected draft passes the same guards.
 */
class ModelObservationReplannerCorrectiveRetryTest {
    @Test
    fun badThenGoodDraftIsAcceptedAfterOneCorrectivePrompt() {
        val runtime = ScriptedDraftRuntime(
            tapDraft("not-on-screen"),
            tapDraft("search-input"),
        )
        val replanner = replanner(runtime)

        val replan = replanner.planNext(observeContext(searchInputObservationJson()))

        assertNotNull(replan)
        assertEquals("search-input", replan!!.request.arguments["target"])
        assertEquals(MODEL_OBSERVATION_REPLAN_REQUEST_REASON, replan.request.reason)
        assertEquals(2, runtime.inputs.size)
        val corrective = runtime.inputs[1]
        assertTrue(corrective, corrective.contains("被拒绝: ui_tap{target=not-on-screen}"))
        assertTrue(corrective, corrective.contains("(unsupported_observed_target)"))
        assertTrue(corrective, corrective.contains("search-input"))
        assertTrue(corrective.length <= MAX_CORRECTIVE_PROMPT_CHARS)
        assertEquals(
            listOf(0 to "unsupported_observed_target", CORRECTIVE_OBSERVATION_PROMPT_INDEX to "accepted"),
            replanner.diagnosticSnapshots().map { it.promptIndex to it.reason },
        )
    }

    @Test
    fun longUserRequestSurvivesTheCorrectivePromptIntact() {
        // A late constraint in a long request must reach the corrective prompt: only the non-user
        // lines are shortened, never the user's own wording.
        val request = userRequestOf(400)

        val runtime = ScriptedDraftRuntime(tapDraft("not-on-screen"), tapDraft("search-input"))
        val replanner = replanner(runtime)

        assertNotNull(replanner.planNext(observeContext(searchInputObservationJson(), input = request)))

        val corrective = runtime.inputs[1]
        assertTrue(corrective, corrective.contains("用户请求: $request"))
        assertTrue("prompt should still be capped: ${corrective.length}", corrective.length <= MAX_CORRECTIVE_PROMPT_CHARS)
    }

    @Test
    fun overCapCorrectivePromptDropsNonUserLinesButKeepsTheRequestWhole() {
        // The user's request alone is longer than the cap, so every other line must give way; the
        // request still arrives byte-identical and ends with its late constraint.
        val request = userRequestOf(MAX_CORRECTIVE_PROMPT_CHARS + 200)

        val runtime = ScriptedDraftRuntime(tapDraft("not-on-screen"), tapDraft("search-input"))
        val replanner = replanner(runtime)

        assertNotNull(replanner.planNext(observeContext(searchInputObservationJson(), input = request)))

        val corrective = runtime.inputs[1]
        assertTrue(corrective, corrective.contains("用户请求: $request"))
        assertTrue("prompt should not exceed the cap plus user text", corrective.length <= request.length + 200)
    }

    /** A user request of exactly [length] chars whose last words are a late "don't send" constraint. */
    private fun userRequestOf(length: Int): String {
        val tail = "但不要发送"
        val body = "帮我打开设置页面，找到蓝牙开关，把已经列出的每一台设备都看清楚，" +
            "记下它们的名字和配对状态，然后回到上一页，只做查看和记录，" +
            "不要点任何开关，不要修改任何设置，不要退出应用，不要分享任何内容，" +
            "不要复制任何文字，不要连接任何设备，不要断开任何连接，不要关闭蓝牙，" +
            "不要打开蓝牙，不要重启手机，不要卸载任何应用，不要清除任何数据，" +
            "不要重置任何设置，不要恢复出厂设置，整个过程只在我确认之后再继续，" +
            "如果没有把握就停下来问我，完成后只告诉我你看到了什么，" +
            "不要自己替我做出任何决定，再多核对两遍刚才记下的内容，"
        return body.repeat(length / body.length + 1).take(length - tail.length) + tail
    }

    @Test
    fun badThenBadDraftFailsClosedWithoutAThirdCall() {
        val runtime = ScriptedDraftRuntime(
            tapDraft("not-on-screen"),
            tapDraft("still-not-on-screen"),
            tapDraft("search-input"),
        )
        val replanner = replanner(runtime)

        val replan = replanner.planNext(observeContext(searchInputObservationJson()))

        assertNull(replan)
        assertEquals("no third model call, no loop", 2, runtime.inputs.size)
        assertEquals("unsupported_observed_target", replanner.lastDiagnosticSnapshot()?.reason)
        assertEquals(CORRECTIVE_OBSERVATION_PROMPT_INDEX, replanner.lastDiagnosticSnapshot()?.promptIndex)
    }

    @Test
    fun dangerousRejectionIsNeverRePrompted() {
        val runtime = ScriptedDraftRuntime(
            tapDraft("delete-recent"),
            tapDraft("search-input"),
        )
        val replanner = replanner(runtime)

        val replan = replanner.planNext(observeContext(dangerousSiblingObservationJson()))

        assertNull(replan)
        assertEquals(1, runtime.inputs.size)
        assertEquals("dangerous_observation_action", replanner.lastDiagnosticSnapshot()?.reason)
    }

    @Test
    fun correctiveRetryDoesNotRunWhenReplanBudgetIsSpent() {
        val runtime = ScriptedDraftRuntime(tapDraft("search-input"))
        val replanner = replanner(runtime, maxModelReplans = 1)
        val spent = ToolRequest(
            id = "spent",
            toolName = MobileActionFunctions.UI_TAP,
            reason = MODEL_OBSERVATION_REPLAN_REQUEST_REASON,
        )
        val base = observeContext(searchInputObservationJson())

        val replan = replanner.planNext(base.copy(priorRequests = listOf(spent) + base.priorRequests))

        assertNull(replan)
        assertTrue(runtime.inputs.isEmpty())
    }

    @Test
    fun noDraftStillFallsBackToMinimalPromptNotCorrective() {
        val runtime = ScriptedDraftRuntime(null, tapDraft("search-input"))
        val replanner = replanner(runtime)

        val replan = replanner.planNext(observeContext(searchInputObservationJson()))

        assertNotNull(replan)
        assertEquals(2, runtime.inputs.size)
        assertFalse(runtime.inputs[1].contains("被拒绝"))
        assertTrue(runtime.inputs[1].contains("当前屏幕可用目标"))
    }

    private fun replanner(runtime: ActionPlanningRuntime, maxModelReplans: Int = 5) =
        ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
            maxModelReplans = maxModelReplans,
        )

    private fun tapDraft(target: String) =
        ActionDraft(
            functionName = MobileActionFunctions.UI_TAP,
            title = "Tap",
            summary = "Tap target",
            parameters = mapOf("target" to target),
            requiresConfirmation = true,
        )

    private class ScriptedDraftRuntime(vararg drafts: ActionDraft?) : ActionPlanningRuntime {
        private val script = drafts.toMutableList()
        val inputs = mutableListOf<String>()

        override fun isLikelyAction(input: String): Boolean = true

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
            inputs += input
            val draft = if (script.isEmpty()) null else script.removeAt(0)
            return if (draft == null) {
                ActionPlanningResult(
                    plan = ActionPlan(ActionPlanKind.NoAction),
                    usedModel = false,
                    fallbackReason = "no draft",
                    modelAttempted = true,
                )
            } else {
                ActionPlanningResult(
                    plan = ActionPlan(ActionPlanKind.Draft, draft),
                    usedModel = true,
                    fallbackReason = null,
                )
            }
        }
    }

    private fun observeContext(
        observationJson: String,
        input: String = "点击搜索输入框",
    ): AgentObservationReplanContext {
        val previousRequest = ToolRequest(id = "observe-1", toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
        return AgentObservationReplanContext(
            run = AgentRun(
                id = "run-corrective",
                input = input,
                state = AgentRunState.Observing,
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
            previousRequest = previousRequest,
            observedResult = ToolResult(
                requestId = previousRequest.id,
                status = ToolStatus.Succeeded,
                summary = "已观察当前屏幕。",
                data = mapOf(
                    "toolName" to MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to true.toString(),
                    "screenObservationJson" to observationJson,
                ),
            ),
            priorRequests = listOf(previousRequest),
        )
    }

    private fun element(id: String, text: String, role: String, editable: Boolean, top: Int) =
        """
            {
              "id": "$id",
              "source": "accessibility",
              "bounds": {"left": 20, "top": $top, "right": 820, "bottom": ${top + 60}},
              "text": "$text",
              "role": "$role",
              "clickability": {"clickable": true, "editable": $editable, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
        """.trimIndent()

    private fun observation(id: String, vararg elements: String) =
        """
        {
          "schemaVersion": 1,
          "observationId": "$id",
          "capturedAtMillis": 2,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility"],
          "elementCount": ${elements.size},
          "sourceCounts": {"accessibility": ${elements.size}},
          "truncated": false,
          "elements": [${elements.joinToString(",")}]
        }
        """.trimIndent()

    private fun searchInputObservationJson(): String =
        observation(
            "screen-search-input",
            element("search-input", "搜索输入框", "input", editable = true, top = 32),
            element("search-submit", "搜索", "button", editable = false, top = 120),
        )

    private fun dangerousSiblingObservationJson(): String =
        observation(
            "screen-dangerous-sibling",
            element("search-input", "搜索输入框", "input", editable = true, top = 32),
            element("delete-recent", "删除", "button", editable = false, top = 120),
        )
}
