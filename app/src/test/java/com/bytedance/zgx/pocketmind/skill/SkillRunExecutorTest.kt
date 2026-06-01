package com.bytedance.zgx.pocketmind.skill

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolExecutor
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
                    data = clipboardSuccess(rawClipboardText),
                ),
                ToolResult(
                    requestId = "",
                    status = ToolStatus.Succeeded,
                    summary = "已打开系统分享面板",
                    data = externalActivitySuccess(MobileActionFunctions.SHARE_TEXT),
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
    fun executesCompositeCurrentScreenTextSummaryShareSkillInDependencyOrder() {
        val rawScreenText = "当前屏幕里的私密验证码 654321"
        val toolExecutor = RecordingToolExecutor(
            results = listOf(
                ToolResult(
                    requestId = "",
                    status = ToolStatus.Succeeded,
                    summary = "已读取当前屏幕可访问文本快照",
                    data = currentScreenTextSuccess(rawScreenText),
                ),
                ToolResult(
                    requestId = "",
                    status = ToolStatus.Succeeded,
                    summary = "已打开系统分享面板",
                    data = externalActivitySuccess(MobileActionFunctions.SHARE_TEXT),
                ),
            ),
        )
        val modelExecutor = SkillModelStepExecutor { step, inputs ->
            assertEquals("summarize_current_screen_text", step.id)
            assertEquals(rawScreenText, inputs["screenText"])
            Result.success("屏幕摘要：验证码用于本地确认")
        }
        val plan = BuiltInSkillRuntime().planCurrentScreenTextSummaryShare("总结当前屏幕文字并分享")
        val executor = SkillRunExecutor(
            toolExecutor = toolExecutor,
            modelExecutor = modelExecutor,
            toolGate = SkillToolGate.allowAllForTests(),
        )

        val result = executor.execute(plan)

        assertEquals(SkillRunState.Succeeded, result.state)
        assertEquals(
            listOf(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT, MobileActionFunctions.SHARE_TEXT),
            toolExecutor.requests.map { it.toolName },
        )
        assertEquals(
            "屏幕摘要：验证码用于本地确认",
            toolExecutor.requests.last().arguments["text"],
        )
        assertEquals(
            "屏幕摘要：验证码用于本地确认",
            result.outputs["summarize_current_screen_text"]?.get("shareText"),
        )
        assertFalse(result.trace.toString().contains(rawScreenText))
        assertFalse(result.outputs.toString().contains(rawScreenText))
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
                data = clipboardSuccess(rawClipboardText),
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
    fun continuationCheckpointContainsOnlyValueFreeSkillState() {
        val rawClipboardText = "私密剪贴板内容 13579"
        val summaryText = "私密内容摘要"
        val executor = SkillRunExecutor(
            toolExecutor = RecordingToolExecutor(emptyList()),
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
                data = clipboardSuccess(rawClipboardText),
            ),
        )

        val checkpoint = requireNotNull(awaitingShare.continuation)
            .toValueFreeCheckpoint(runId = "run-checkpoint", plan = plan)
        val serialized = checkpoint.toJsonObject().toString()

        assertEquals("share_summary", checkpoint.pendingStepId)
        assertEquals(MobileActionFunctions.SHARE_TEXT, checkpoint.pendingToolName)
        assertEquals(listOf("read_clipboard", "summarize_clipboard"), checkpoint.completedStepIds)
        assertEquals(setOf("read_clipboard.text"), checkpoint.privateOutputRefs)
        assertEquals(listOf("shareText"), checkpoint.outputKeysByStep["summarize_clipboard"])
        assertNull(checkpoint.validationErrorFor(plan))
        assertFalse(serialized.contains(rawClipboardText))
        assertFalse(serialized.contains(summaryText))
        assertFalse(serialized.contains("已读取剪贴板文本"))
    }

    @Test
    fun valueFreeCheckpointRejectsPrivateRefsWhenCompletedStepIdContainsDot() {
        val plan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")
        val readStep = plan.steps[0] as SkillStep.ToolStep
        val summarizeStep = plan.steps[1] as SkillStep.ModelStep
        val shareStep = plan.steps[2] as SkillStep.ToolStep
        val ambiguousPlan = plan.copy(
            steps = listOf(
                readStep.copy(id = "read.clipboard"),
                summarizeStep.copy(
                    dependsOn = listOf("read.clipboard"),
                    inputBindings = mapOf("clipboardText" to "read.clipboard.text"),
                ),
                shareStep,
            ),
        )

        val validation = ambiguousPlan.validateStructure()
        val checkpoint = requireNotNull(
            ambiguousPlan.valueFreeCheckpointForPendingTool(
                runId = "run-checkpoint",
                pendingRequest = shareStep.request,
            ),
        )

        assertFalse(validation.isValid)
        assertTrue(validation.errors.any { it.contains("source-ref delimiter") })
        assertEquals("skill checkpoint plan is invalid", checkpoint.validationErrorFor(ambiguousPlan))

        val ambiguousOutputKeyPlan = plan.copy(
            steps = listOf(
                readStep,
                summarizeStep.copy(outputKey = "share.text"),
                shareStep.copy(argumentBindings = mapOf("text" to "summarize_clipboard.share.text")),
            ),
        )
        val outputKeyValidation = ambiguousOutputKeyPlan.validateStructure()
        assertFalse(outputKeyValidation.isValid)
        assertTrue(outputKeyValidation.errors.any { it.contains("outputKey must not contain") })
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
                data = clipboardSuccess("私密剪贴板内容"),
            ),
        )

        val completed = executor.resume(
            plan = plan,
            continuation = requireNotNull(awaitingShare.continuation),
            result = ToolResult(
                requestId = requireNotNull(awaitingShare.pendingToolRequest).id,
                status = ToolStatus.Succeeded,
                summary = "已打开系统分享面板",
                data = externalActivitySuccess(MobileActionFunctions.SHARE_TEXT),
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
    fun executeRejectsMalformedSucceededToolResultBeforeSkillOutput() {
        val rawClipboardText = "malformed success payload must not leak"
        var modelCallCount = 0
        val toolExecutor = RecordingToolExecutor(
            results = listOf(
                ToolResult(
                    requestId = "",
                    status = ToolStatus.Succeeded,
                    summary = "malformed success summary $rawClipboardText",
                    data = clipboardSuccess(rawClipboardText) + ("unexpected" to rawClipboardText),
                ),
            ),
        )
        val executor = SkillRunExecutor(
            toolExecutor = toolExecutor,
            modelExecutor = SkillModelStepExecutor { _, _ ->
                modelCallCount += 1
                Result.success("unused")
            },
            toolGate = SkillToolGate.allowAllForTests(),
        )
        val plan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")

        val result = executor.execute(plan)

        assertEquals(SkillRunState.Failed, result.state)
        assertTrue(result.error.orEmpty().contains("returned invalid result"))
        assertTrue(result.error.orEmpty().contains("unexpected"))
        assertEquals(0, modelCallCount)
        assertEquals(listOf(MobileActionFunctions.READ_CLIPBOARD), toolExecutor.requests.map { it.toolName })
        assertFalse(result.outputs.toString().contains(rawClipboardText))
        assertFalse(result.trace.toString().contains(rawClipboardText))
    }

    @Test
    fun resumeRejectsMalformedSucceededToolResultBeforeModelStep() {
        val rawClipboardText = "malformed resume payload must not reach model"
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
                requestId = requireNotNull(awaitingRead.pendingToolRequest).id,
                status = ToolStatus.Succeeded,
                summary = "malformed resume summary $rawClipboardText",
                data = mapOf(
                    "toolName" to MobileActionFunctions.READ_CLIPBOARD,
                    "text" to rawClipboardText,
                ),
            ),
        )

        assertEquals(SkillRunState.Failed, result.state)
        assertTrue(result.error.orEmpty().contains("returned invalid result"))
        assertTrue(result.error.orEmpty().contains("truncated"))
        assertEquals(0, modelCallCount)
        assertEquals(null, result.pendingToolRequest)
        assertEquals(null, result.continuation)
        assertFalse(result.outputs.toString().contains(rawClipboardText))
        assertFalse(result.trace.toString().contains(rawClipboardText))
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
                data = clipboardSuccess(rawClipboardText),
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
    fun currentScreenTextPrivateOutputCannotBindDirectlyToLaterShareArgument() {
        val rawScreenText = "当前屏幕里的私密文本不应成为分享参数"
        val modelSummary = "安全屏幕摘要"
        val toolExecutor = RecordingToolExecutor(emptyList())
        val executor = SkillRunExecutor(
            toolExecutor = toolExecutor,
            modelExecutor = SkillModelStepExecutor { _, _ -> Result.success(modelSummary) },
        )
        val validPlan = BuiltInSkillRuntime().planCurrentScreenTextSummaryShare("总结当前屏幕文字并分享")
        val readStep = validPlan.steps[0]
        val modelStep = validPlan.steps[1]
        val shareStep = validPlan.steps[2] as SkillStep.ToolStep
        val maliciousPlan = validPlan.copy(
            steps = listOf(
                readStep,
                modelStep,
                shareStep.copy(argumentBindings = mapOf("text" to "read_current_screen_text.screenText")),
            ),
        )
        val awaitingRead = executor.execute(maliciousPlan)

        val rejected = executor.resume(
            plan = maliciousPlan,
            continuation = requireNotNull(awaitingRead.continuation),
            result = ToolResult(
                requestId = requireNotNull(awaitingRead.pendingToolRequest).id,
                status = ToolStatus.Succeeded,
                summary = "已读取当前屏幕可访问文本快照",
                data = currentScreenTextSuccess(rawScreenText),
            ),
        )

        assertEquals(SkillRunState.Failed, rejected.state)
        assertTrue(rejected.error.orEmpty().contains("private tool output cannot be bound directly"))
        assertTrue(rejected.error.orEmpty().contains("read_current_screen_text.screenText"))
        assertEquals(null, rejected.pendingToolRequest)
        assertEquals(null, rejected.continuation)
        assertEquals(modelSummary, rejected.outputs["summarize_current_screen_text"]?.get("shareText"))
        assertTrue(toolExecutor.requests.isEmpty())
        assertFalse(rejected.trace.any { trace ->
            trace is SkillRunTrace.AwaitingConfirmation &&
                trace.toolName == MobileActionFunctions.SHARE_TEXT
        })
        assertFalse(rejected.outputs.toString().contains(rawScreenText))
        assertFalse(rejected.trace.toString().contains(rawScreenText))
        assertFalse(requireNotNull(awaitingRead.continuation).toString().contains(rawScreenText))
    }

    @Test
    fun recentScreenshotOcrTextCannotBindDirectlyToLaterToolArgument() {
        val rawOcrText = "截图里的私密验证码 123456"
        val toolExecutor = RecordingToolExecutor(emptyList())
        val executor = SkillRunExecutor(
            toolExecutor = toolExecutor,
            modelExecutor = SkillModelStepExecutor { _, _ -> Result.success("unused") },
        )
        val readStep = SkillStep.ToolStep(
            id = "read_screenshot",
            request = ToolRequest(
                id = "read-screenshot",
                toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                arguments = mapOf("maxCount" to "1"),
            ),
            draft = ActionDraft(
                functionName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                title = "读取最近截图 OCR",
                summary = "读取最近截图文字",
                parameters = mapOf("maxCount" to "1"),
            ),
        )
        val shareStep = SkillStep.ToolStep(
            id = "share_ocr",
            dependsOn = listOf(readStep.id),
            request = ToolRequest(
                id = "share-ocr",
                toolName = MobileActionFunctions.SHARE_TEXT,
            ),
            draft = ActionDraft(
                functionName = MobileActionFunctions.SHARE_TEXT,
                title = "分享 OCR",
                summary = "分享 OCR 文本",
                parameters = emptyMap(),
            ),
            argumentBindings = mapOf("text" to "${readStep.id}.ocrText"),
        )
        val plan = SkillPlan(
            request = SkillRequest(
                id = "skill-ocr",
                skillId = "test.screenshot_ocr_share",
                arguments = emptyMap(),
                reason = "test",
            ),
            manifest = SkillManifest(
                id = "test.screenshot_ocr_share",
                version = 1,
                title = "OCR share",
                description = "test",
                triggerExamples = emptyList(),
                requiredTools = listOf(
                    MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                    MobileActionFunctions.SHARE_TEXT,
                ),
                inputSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}""",
                riskLevel = RiskLevel.MediumDraftOrNavigation,
            ),
            steps = listOf(readStep, shareStep),
        )
        val awaitingRead = executor.execute(plan)

        val rejected = executor.resume(
            plan = plan,
            continuation = requireNotNull(awaitingRead.continuation),
            result = ToolResult(
                requestId = requireNotNull(awaitingRead.pendingToolRequest).id,
                status = ToolStatus.Succeeded,
                summary = "已读取最近截图 OCR 摘录",
                data = recentScreenshotOcrSuccess(rawOcrText),
            ),
        )

        assertEquals(SkillRunState.Failed, rejected.state)
        assertTrue(rejected.error.orEmpty().contains("private tool output cannot be bound directly"))
        assertEquals(null, rejected.pendingToolRequest)
        assertEquals(null, rejected.continuation)
        assertTrue(toolExecutor.requests.isEmpty())
        assertFalse(rejected.outputs.toString().contains(rawOcrText))
        assertFalse(rejected.trace.toString().contains(rawOcrText))
        assertFalse(requireNotNull(awaitingRead.continuation).toString().contains(rawOcrText))
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
                data = clipboardSuccess(rawClipboardText),
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

    private fun clipboardSuccess(text: String): Map<String, String> =
        mapOf(
            "toolName" to MobileActionFunctions.READ_CLIPBOARD,
            "text" to text,
            "truncated" to "false",
        )

    private fun currentScreenTextSuccess(text: String): Map<String, String> =
        mapOf(
            "toolName" to MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            "privacy" to "LocalOnly",
            "requiresLocalModel" to "true",
            "source" to "accessibility",
            "maxChars" to "1200",
            "capturedAtMillis" to "1000",
            "nodeCount" to "1",
            "screenText" to text,
            "truncated" to "false",
            "screenTextIncluded" to "true",
            "rawTreeIncluded" to "false",
            "metadataPolicy" to "accessibility_text_local_only_no_node_ids_bounds_or_hierarchy_persisted",
        )

    private fun recentScreenshotOcrSuccess(text: String): Map<String, String> =
        mapOf(
            "toolName" to MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            "privacy" to "LocalOnly",
            "requiresLocalModel" to "true",
            "source" to "recent_screenshot",
            "maxCount" to "1",
            "scannedCount" to "1",
            "ocrText" to text,
            "truncated" to "false",
            "ocrTextIncluded" to "true",
            "rawPayloadIncluded" to "false",
            "metadataPolicy" to "ocr_text_local_only_no_uri_path_or_pixels_persisted",
        )

    private fun externalActivitySuccess(toolName: String): Map<String, String> =
        mapOf(
            "toolName" to toolName,
            "completionState" to "ExternalActivityOpened",
            "completionVerified" to "false",
            "externalOutcome" to "user_continues_in_external_activity",
            "targetKind" to "android_chooser",
            "intentAction" to "android.intent.action.SEND",
            "metadataPolicy" to "no_raw_payload_persisted",
            "rawPayloadIncluded" to "false",
        )

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
