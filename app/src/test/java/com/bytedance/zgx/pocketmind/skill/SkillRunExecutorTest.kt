package com.bytedance.zgx.pocketmind.skill

import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.tool.ToolExecutor
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRunExecutorTest {
    @Test
    fun executesCompositeClipboardSummaryShareSkillInDependencyOrder() {
        val rawClipboardText = "明天十点和设计同学评审首页改版"
        val toolExecutor = RecordingToolExecutor(
            results = listOf(
                ToolResult(
                    requestId = "",
                    status = ToolStatus.Succeeded,
                    summary = "已读取剪贴板文本",
                    data = mapOf("text" to rawClipboardText),
                ),
                ToolResult(
                    requestId = "",
                    status = ToolStatus.Succeeded,
                    summary = "已打开系统分享面板",
                ),
            ),
        )
        val modelExecutor = SkillModelStepExecutor { _, inputs ->
            Result.success("明天 10 点评审首页改版")
                .also { assertEquals(rawClipboardText, inputs["clipboardText"]) }
        }
        val plan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")
        val executor = SkillRunExecutor(
            toolExecutor = toolExecutor,
            modelExecutor = modelExecutor,
            toolGate = SkillToolGate.allowAllForTests(),
        )

        val result = executor.execute(plan)

        assertEquals(SkillRunState.Succeeded, result.state)
        assertEquals(
            listOf(MobileActionFunctions.READ_CLIPBOARD, MobileActionFunctions.SHARE_TEXT),
            toolExecutor.requests.map { it.toolName },
        )
        assertEquals(
            "明天 10 点评审首页改版",
            toolExecutor.requests.last().arguments["text"],
        )
        assertEquals("明天 10 点评审首页改版", result.outputs["summarize_clipboard"]?.get("shareText"))
        assertFalse(result.trace.toString().contains(rawClipboardText))
        assertFalse(result.outputs.toString().contains(rawClipboardText))
        assertTrue(result.trace.any { it is SkillRunTrace.ModelFinished })
    }

    @Test
    fun defaultGateStopsBeforeExecutingToolsThatNeedConfirmation() {
        val toolExecutor = RecordingToolExecutor(emptyList())
        val plan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")
        val executor = SkillRunExecutor(
            toolExecutor = toolExecutor,
            modelExecutor = SkillModelStepExecutor { _, _ -> Result.success("unused") },
        )

        val result = executor.execute(plan)

        assertEquals(SkillRunState.AwaitingConfirmation, result.state)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, result.pendingToolRequest?.toolName)
        assertTrue(result.error.orEmpty().contains("requires user confirmation"))
        assertTrue(toolExecutor.requests.isEmpty())
    }

    @Test
    fun failsBeforeExecutingWhenPlanStructureIsInvalid() {
        val validPlan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")
        val invalidPlan = validPlan.copy(steps = listOf(validPlan.steps[2], validPlan.steps[0]))
        val executor = SkillRunExecutor(
            toolExecutor = RecordingToolExecutor(emptyList()),
            modelExecutor = SkillModelStepExecutor { _, _ -> Result.success("unused") },
            toolGate = SkillToolGate.allowAllForTests(),
        )

        val result = executor.execute(invalidPlan)

        assertEquals(SkillRunState.Failed, result.state)
        assertTrue(result.error.orEmpty().contains("depends on missing or later step"))
    }

    @Test
    fun failsBeforeExecutingWhenSkillArgumentsDoNotMatchManifestSchema() {
        val validPlan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")
        val invalidPlan = validPlan.copy(
            request = validPlan.request.copy(arguments = mapOf("toolName" to "read_clipboard")),
        )
        val toolExecutor = RecordingToolExecutor(emptyList())
        val executor = SkillRunExecutor(
            toolExecutor = toolExecutor,
            modelExecutor = SkillModelStepExecutor { _, _ -> Result.success("unused") },
            toolGate = SkillToolGate.allowAllForTests(),
        )

        val result = executor.execute(invalidPlan)

        assertEquals(SkillRunState.Failed, result.state)
        assertTrue(result.error.orEmpty().contains("requires argument(s): input"))
        assertTrue(result.error.orEmpty().contains("does not accept argument(s): toolName"))
        assertTrue(toolExecutor.requests.isEmpty())
    }

    @Test
    fun failsWhenStepLimitWouldBeExceeded() {
        val validPlan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")
        val executor = SkillRunExecutor(
            toolExecutor = RecordingToolExecutor(emptyList()),
            modelExecutor = SkillModelStepExecutor { _, _ -> Result.success("unused") },
            toolGate = SkillToolGate.allowAllForTests(),
            maxSteps = 2,
        )

        val result = executor.execute(validPlan)

        assertEquals(SkillRunState.Failed, result.state)
        assertEquals("skill step limit exceeded", result.error)
    }

    private class RecordingToolExecutor(
        results: List<ToolResult>,
    ) : ToolExecutor {
        private val remainingResults = ArrayDeque(results)
        val requests = mutableListOf<ToolRequest>()

        override fun execute(request: ToolRequest): ToolResult {
            requests += request
            val result = remainingResults.removeFirst()
            return result.copy(requestId = request.id)
        }
    }
}
