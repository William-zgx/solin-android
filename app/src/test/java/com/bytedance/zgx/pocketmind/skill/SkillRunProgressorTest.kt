package com.bytedance.zgx.pocketmind.skill

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRunProgressorTest {
    private val progressor = SkillRunProgressor()

    @Test
    fun bindsModelOutputToDependentToolRequestAndDraft() {
        val plan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")
        val readStep = plan.steps[0] as SkillStep.ToolStep

        val progression = progressor.nextToolAfterModelOutput(
            skillPlan = plan,
            requestedRequestIds = setOf(readStep.request.id),
            modelOutput = "适合分享的本地摘要",
        )

        require(progression is SkillModelOutputProgression.BoundTool)
        assertEquals("summarize_clipboard", progression.modelStep.id)
        assertEquals(MobileActionFunctions.SHARE_TEXT, progression.request.toolName)
        assertEquals("适合分享的本地摘要", progression.request.arguments["text"])
        assertEquals("适合分享的本地摘要", progression.draft.parameters["text"])
    }

    @Test
    fun rejectsPrivateToolOutputBindingBeforeLookingForPublicValue() {
        val plan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")
        val readStep = plan.steps[0] as SkillStep.ToolStep
        val modelStep = plan.steps[1]
        val shareStep = plan.steps[2] as SkillStep.ToolStep
        val maliciousPlan = plan.copy(
            steps = listOf(
                readStep,
                modelStep,
                shareStep.copy(argumentBindings = mapOf("text" to "${readStep.id}.text")),
            ),
        )

        val progression = progressor.nextToolAfterModelOutput(
            skillPlan = maliciousPlan,
            requestedRequestIds = setOf(readStep.request.id),
            modelOutput = "安全摘要",
        )

        require(progression is SkillModelOutputProgression.Rejected)
        assertTrue(progression.reason.contains("private tool output cannot be bound directly"))
        assertTrue(progression.reason.contains("${readStep.id}.text"))
    }

    @Test
    fun publicOutputsRedactClipboardAndScreenshotOcrPrivateFields() {
        val outputs = linkedMapOf<String, Map<String, String>>()
        val privateRefs = mutableSetOf<String>()
        val clipboardStep = SkillStep.ToolStep(
            id = "read_clipboard",
            request = ToolRequest(
                id = "clipboard",
                toolName = MobileActionFunctions.READ_CLIPBOARD,
            ),
            draft = ActionDraft(
                functionName = MobileActionFunctions.READ_CLIPBOARD,
                title = "读取剪贴板",
                summary = "读取剪贴板",
                parameters = emptyMap(),
            ),
        )
        val screenshotStep = SkillStep.ToolStep(
            id = "read_screenshot",
            request = ToolRequest(
                id = "screenshot",
                toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            ),
            draft = ActionDraft(
                functionName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                title = "读取截图 OCR",
                summary = "读取截图 OCR",
                parameters = emptyMap(),
            ),
        )

        outputs[clipboardStep.id] = progressor.outputForToolResult(
            result = ToolResult(
                requestId = "clipboard",
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板",
                data = mapOf("text" to "私密剪贴板", "truncated" to "false"),
            ),
            draft = clipboardStep.draft,
        )
        privateRefs += progressor.privateOutputRefsFor(clipboardStep.id, clipboardStep.request.toolName)
        outputs[screenshotStep.id] = progressor.outputForToolResult(
            result = ToolResult(
                requestId = "screenshot",
                status = ToolStatus.Succeeded,
                summary = "已读取截图 OCR",
                data = mapOf("ocrText" to "私密截图文字", "ocrTextIncluded" to "true"),
            ),
            draft = screenshotStep.draft,
        )
        privateRefs += progressor.privateOutputRefsFor(screenshotStep.id, screenshotStep.request.toolName)

        val publicOutputs = progressor.publicOutputs(outputs, privateRefs)

        assertFalse(publicOutputs.toString().contains("私密剪贴板"))
        assertFalse(publicOutputs.toString().contains("私密截图文字"))
        assertEquals("false", publicOutputs[clipboardStep.id]?.get("truncated"))
        assertEquals("true", publicOutputs[screenshotStep.id]?.get("ocrTextIncluded"))
    }

    @Test
    fun validatesPlanStructureAndStepLimitWithoutSideEffects() {
        val validPlan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")
        val invalidPlan = validPlan.copy(steps = listOf(validPlan.steps[2], validPlan.steps[0]))
        val limitedProgressor = SkillRunProgressor(maxSteps = 1)

        assertEquals(null, progressor.validateForExecution(validPlan))
        assertTrue(progressor.validateForExecution(invalidPlan).orEmpty().contains("depends on missing or later step"))
        assertEquals("skill step limit exceeded", limitedProgressor.validateForExecution(validPlan))
    }

    @Test
    fun missingModelOutputBindingFailsClosed() {
        val plan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")
        val readStep = plan.steps[0] as SkillStep.ToolStep
        val shareStep = plan.steps[2] as SkillStep.ToolStep
        val brokenPlan = plan.copy(
            steps = listOf(
                plan.steps[0],
                plan.steps[1],
                shareStep.copy(argumentBindings = mapOf("text" to "summarize_clipboard.missing")),
            ),
        )

        val progression = progressor.nextToolAfterModelOutput(
            skillPlan = brokenPlan,
            requestedRequestIds = setOf(readStep.request.id),
            modelOutput = "摘要",
        )

        require(progression is SkillModelOutputProgression.Rejected)
        assertEquals("Missing model output binding for skill step.", progression.reason)
    }

}
