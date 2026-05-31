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
    fun resumesAfterConfirmedToolResultAndStopsAtNextConfirmation() {
        val rawClipboardText = "明天十点和设计同学评审首页改版"
        val summaryText = "明天 10 点评审首页改版"
        val toolExecutor = RecordingToolExecutor(emptyList())
        var modelInput: String? = null
        val executor = SkillRunExecutor(
            toolExecutor = toolExecutor,
            modelExecutor = SkillModelStepExecutor { _, inputs ->
                modelInput = inputs["clipboardText"]
                Result.success(summaryText)
            },
        )
        val plan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")

        val awaitingRead = executor.execute(plan)
        val afterRead = executor.resume(
            plan = plan,
            continuation = requireNotNull(awaitingRead.continuation),
            result = ToolResult(
                requestId = requireNotNull(awaitingRead.pendingToolRequest).id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf("text" to rawClipboardText),
            ),
        )

        assertEquals(SkillRunState.AwaitingConfirmation, afterRead.state)
        assertEquals(MobileActionFunctions.SHARE_TEXT, afterRead.pendingToolRequest?.toolName)
        assertEquals(summaryText, afterRead.pendingToolRequest?.arguments?.get("text"))
        assertEquals(rawClipboardText, modelInput)
        assertEquals(summaryText, afterRead.outputs["summarize_clipboard"]?.get("shareText"))
        assertTrue(toolExecutor.requests.isEmpty())
        assertFalse(afterRead.outputs.toString().contains(rawClipboardText))
        assertFalse(afterRead.trace.toString().contains(rawClipboardText))
        assertFalse(afterRead.continuation.toString().contains(rawClipboardText))
    }

    @Test
    fun resumesAgainAfterSecondConfirmationAndCompletesSkill() {
        val toolExecutor = RecordingToolExecutor(emptyList())
        val executor = SkillRunExecutor(
            toolExecutor = toolExecutor,
            modelExecutor = SkillModelStepExecutor { _, _ -> Result.success("会议摘要") },
        )
        val plan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")
        val awaitingRead = executor.execute(plan)
        val awaitingShare = executor.resume(
            plan = plan,
            continuation = requireNotNull(awaitingRead.continuation),
            result = ToolResult(
                requestId = requireNotNull(awaitingRead.pendingToolRequest).id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf("text" to "私密剪贴板内容"),
            ),
        )

        val completed = executor.resume(
            plan = plan,
            continuation = requireNotNull(awaitingShare.continuation),
            result = ToolResult(
                requestId = requireNotNull(awaitingShare.pendingToolRequest).id,
                status = ToolStatus.Succeeded,
                summary = "已打开系统分享面板",
            ),
        )

        assertEquals(SkillRunState.Succeeded, completed.state)
        assertEquals("会议摘要", completed.outputs["share_summary"]?.get("text"))
        assertTrue(toolExecutor.requests.isEmpty())
        assertFalse(completed.outputs.toString().contains("私密剪贴板内容"))
        assertTrue(completed.trace.any {
            it is SkillRunTrace.ToolFinished && it.toolName == MobileActionFunctions.SHARE_TEXT
        })
    }

    @Test
    fun resumeRejectsToolResultThatDoesNotMatchPendingRequest() {
        var modelCallCount = 0
        val executor = SkillRunExecutor(
            toolExecutor = RecordingToolExecutor(emptyList()),
            modelExecutor = SkillModelStepExecutor { _, _ ->
                modelCallCount += 1
                Result.success("unused")
            },
        )
        val plan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")
        val awaitingRead = executor.execute(plan)

        val result = executor.resume(
            plan = plan,
            continuation = requireNotNull(awaitingRead.continuation),
            result = ToolResult(
                requestId = "wrong-request",
                status = ToolStatus.Succeeded,
                summary = "不应恢复",
            ),
        )

        assertEquals(SkillRunState.Failed, result.state)
        assertTrue(result.error.orEmpty().contains("does not match pending skill step"))
        assertEquals(0, modelCallCount)
    }

    @Test
    fun privateToolOutputCannotBindDirectlyToLaterToolArgument() {
        val rawClipboardText = "私密剪贴板内容不应成为分享参数"
        val modelSummary = "安全摘要"
        val toolExecutor = RecordingToolExecutor(emptyList())
        val executor = SkillRunExecutor(
            toolExecutor = toolExecutor,
            modelExecutor = SkillModelStepExecutor { _, _ -> Result.success(modelSummary) },
        )
        val validPlan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")
        val readStep = validPlan.steps[0]
        val modelStep = validPlan.steps[1]
        val shareStep = validPlan.steps[2] as SkillStep.ToolStep
        val maliciousPlan = validPlan.copy(
            steps = listOf(
                readStep,
                modelStep,
                shareStep.copy(argumentBindings = mapOf("text" to "read_clipboard.text")),
            ),
        )
        val awaitingRead = executor.execute(maliciousPlan)

        val rejected = executor.resume(
            plan = maliciousPlan,
            continuation = requireNotNull(awaitingRead.continuation),
            result = ToolResult(
                requestId = requireNotNull(awaitingRead.pendingToolRequest).id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf("text" to rawClipboardText),
            ),
        )

        assertEquals(SkillRunState.Failed, rejected.state)
        assertTrue(rejected.error.orEmpty().contains("private tool output cannot be bound directly"))
        assertEquals(null, rejected.pendingToolRequest)
        assertEquals(null, rejected.continuation)
        assertEquals(modelSummary, rejected.outputs["summarize_clipboard"]?.get("shareText"))
        assertTrue(toolExecutor.requests.isEmpty())
        assertFalse(rejected.trace.any { trace ->
            trace is SkillRunTrace.AwaitingConfirmation &&
                trace.toolName == MobileActionFunctions.SHARE_TEXT
        })
        assertFalse(rejected.outputs.toString().contains(rawClipboardText))
        assertFalse(rejected.trace.toString().contains(rawClipboardText))
        assertFalse(requireNotNull(awaitingRead.continuation).toString().contains(rawClipboardText))
    }

    @Test
    fun cancelStopsPendingSkillWithoutExecutingOrLeakingPrivateOutputs() {
        val rawClipboardText = "私密剪贴板内容"
        val summaryText = "公开摘要"
        val toolExecutor = RecordingToolExecutor(emptyList())
        val executor = SkillRunExecutor(
            toolExecutor = toolExecutor,
            modelExecutor = SkillModelStepExecutor { _, _ -> Result.success(summaryText) },
        )
        val plan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")
        val awaitingRead = executor.execute(plan)
        val awaitingShare = executor.resume(
            plan = plan,
            continuation = requireNotNull(awaitingRead.continuation),
            result = ToolResult(
                requestId = requireNotNull(awaitingRead.pendingToolRequest).id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf("text" to rawClipboardText),
            ),
        )

        val cancelled = executor.cancel(
            plan = plan,
            continuation = requireNotNull(awaitingShare.continuation),
            reason = "用户取消分享",
        )

        assertEquals(SkillRunState.Cancelled, cancelled.state)
        assertEquals("用户取消分享", cancelled.error)
        assertEquals(summaryText, cancelled.outputs["summarize_clipboard"]?.get("shareText"))
        assertTrue(toolExecutor.requests.isEmpty())
        assertFalse(cancelled.outputs.toString().contains(rawClipboardText))
        assertFalse(cancelled.trace.toString().contains(rawClipboardText))
        assertTrue(cancelled.trace.any { trace ->
            trace is SkillRunTrace.Cancelled &&
                trace.stepId == "share_summary" &&
                trace.toolName == MobileActionFunctions.SHARE_TEXT
        })
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
