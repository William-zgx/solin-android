package com.bytedance.zgx.pocketmind.skill

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRunProgressorTest {
    private val progressor = SkillRunProgressor()

    @Test
    fun privateOutputRefsComeFromInjectedToolRegistryPolicy() {
        val emptyRegistryProgressor = SkillRunProgressor(
            toolRegistry = ToolRegistry.fromSupportedActions(emptySet()),
        )

        assertEquals(
            setOf("read_clipboard.text"),
            progressor.privateOutputRefsFor("read_clipboard", MobileActionFunctions.READ_CLIPBOARD),
        )
        assertEquals(
            emptySet<String>(),
            emptyRegistryProgressor.privateOutputRefsFor("read_clipboard", MobileActionFunctions.READ_CLIPBOARD),
        )
    }

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
    fun rejectsScreenshotOcrPrivateOutputBindingToToolArgument() {
        val ocrDraft = ActionDraft(
            functionName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            title = "读取截图 OCR",
            summary = "读取截图 OCR",
            parameters = emptyMap(),
        )
        val shareDraft = ActionDraft(
            functionName = MobileActionFunctions.SHARE_TEXT,
            title = "分享 OCR",
            summary = "分享 OCR 摘要",
            parameters = emptyMap(),
        )
        val ocrStep = SkillStep.ToolStep(
            id = "read_screenshot",
            request = ToolRequest(
                id = "read-screenshot-request",
                toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            ),
            draft = ocrDraft,
        )
        val modelStep = SkillStep.ModelStep(
            id = "summarize_screenshot",
            dependsOn = listOf("read_screenshot"),
            title = "整理截图 OCR",
            instruction = "整理 OCR。",
            inputBindings = mapOf("ocrText" to "read_screenshot.ocrText"),
            outputKey = "summary",
        )
        val shareStep = SkillStep.ToolStep(
            id = "share_screenshot",
            dependsOn = listOf("summarize_screenshot"),
            request = ToolRequest(
                id = "share-screenshot-request",
                toolName = MobileActionFunctions.SHARE_TEXT,
            ),
            draft = shareDraft,
            argumentBindings = mapOf("text" to "read_screenshot.ocrText"),
        )
        val plan = SkillPlan(
            request = SkillRequest(
                id = "ocr-skill-request",
                skillId = "ocr_share_skill",
                arguments = mapOf("input" to "分享截图 OCR"),
                reason = "分享截图 OCR",
            ),
            manifest = SkillManifest(
                id = "ocr_share_skill",
                version = 1,
                title = "OCR share skill",
                description = "Test skill",
                triggerExamples = listOf("分享截图 OCR"),
                requiredTools = listOf(
                    MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                    MobileActionFunctions.SHARE_TEXT,
                ),
                inputSchemaJson = """
                    {
                      "type": "object",
                      "required": ["input"],
                      "properties": {
                        "input": {
                          "type": "string",
                          "minLength": 1
                        }
                      },
                      "additionalProperties": false
                    }
                """.trimIndent(),
                riskLevel = RiskLevel.HighExternalSend,
            ),
            steps = listOf(ocrStep, modelStep, shareStep),
        )

        val progression = progressor.nextToolAfterModelOutput(
            skillPlan = plan,
            requestedRequestIds = setOf(ocrStep.request.id),
            modelOutput = "安全 OCR 摘要",
        )

        require(progression is SkillModelOutputProgression.Rejected)
        assertTrue(progression.reason.contains("private tool output cannot be bound directly"))
        assertTrue(progression.reason.contains("read_screenshot.ocrText"))
    }

    @Test
    fun rejectsImageOcrPrivateOutputBindingToToolArgument() {
        val ocrDraft = ActionDraft(
            functionName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            title = "读取图片 OCR",
            summary = "读取图片 OCR",
            parameters = emptyMap(),
        )
        val shareDraft = ActionDraft(
            functionName = MobileActionFunctions.SHARE_TEXT,
            title = "分享 OCR",
            summary = "分享 OCR 摘要",
            parameters = emptyMap(),
        )
        val ocrStep = SkillStep.ToolStep(
            id = "read_image",
            request = ToolRequest(
                id = "read-image-request",
                toolName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            ),
            draft = ocrDraft,
        )
        val modelStep = SkillStep.ModelStep(
            id = "summarize_image",
            dependsOn = listOf("read_image"),
            title = "整理图片 OCR",
            instruction = "整理 OCR。",
            inputBindings = mapOf("ocrText" to "read_image.ocrText"),
            outputKey = "summary",
        )
        val shareStep = SkillStep.ToolStep(
            id = "share_image",
            dependsOn = listOf("summarize_image"),
            request = ToolRequest(
                id = "share-image-request",
                toolName = MobileActionFunctions.SHARE_TEXT,
            ),
            draft = shareDraft,
            argumentBindings = mapOf("text" to "read_image.ocrText"),
        )
        val plan = SkillPlan(
            request = SkillRequest(
                id = "image-ocr-skill-request",
                skillId = "image_ocr_share_skill",
                arguments = mapOf("input" to "分享图片 OCR"),
                reason = "分享图片 OCR",
            ),
            manifest = SkillManifest(
                id = "image_ocr_share_skill",
                version = 1,
                title = "Image OCR share skill",
                description = "Test skill",
                triggerExamples = listOf("分享图片 OCR"),
                requiredTools = listOf(
                    MobileActionFunctions.READ_RECENT_IMAGE_OCR,
                    MobileActionFunctions.SHARE_TEXT,
                ),
                inputSchemaJson = """
                    {
                      "type": "object",
                      "required": ["input"],
                      "properties": {
                        "input": {
                          "type": "string",
                          "minLength": 1
                        }
                      },
                      "additionalProperties": false
                    }
                """.trimIndent(),
                riskLevel = RiskLevel.HighExternalSend,
            ),
            steps = listOf(ocrStep, modelStep, shareStep),
        )

        val progression = progressor.nextToolAfterModelOutput(
            skillPlan = plan,
            requestedRequestIds = setOf(ocrStep.request.id),
            modelOutput = "安全 OCR 摘要",
        )

        require(progression is SkillModelOutputProgression.Rejected)
        assertTrue(progression.reason.contains("private tool output cannot be bound directly"))
        assertTrue(progression.reason.contains("read_image.ocrText"))
    }

    @Test
    fun rejectsCurrentScreenTextPrivateOutputBindingToToolArgument() {
        val screenDraft = ActionDraft(
            functionName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            title = "读取当前屏幕文本",
            summary = "读取当前屏幕文本",
            parameters = emptyMap(),
        )
        val shareDraft = ActionDraft(
            functionName = MobileActionFunctions.SHARE_TEXT,
            title = "分享屏幕文本",
            summary = "分享屏幕文本摘要",
            parameters = emptyMap(),
        )
        val screenStep = SkillStep.ToolStep(
            id = "read_screen",
            request = ToolRequest(
                id = "read-screen-request",
                toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            ),
            draft = screenDraft,
        )
        val modelStep = SkillStep.ModelStep(
            id = "summarize_screen",
            dependsOn = listOf("read_screen"),
            title = "整理屏幕文本",
            instruction = "整理屏幕文本。",
            inputBindings = mapOf("screenText" to "read_screen.screenText"),
            outputKey = "summary",
        )
        val shareStep = SkillStep.ToolStep(
            id = "share_screen",
            dependsOn = listOf("summarize_screen"),
            request = ToolRequest(
                id = "share-screen-request",
                toolName = MobileActionFunctions.SHARE_TEXT,
            ),
            draft = shareDraft,
            argumentBindings = mapOf("text" to "read_screen.screenText"),
        )
        val plan = SkillPlan(
            request = SkillRequest(
                id = "screen-skill-request",
                skillId = "screen_share_skill",
                arguments = mapOf("input" to "分享当前屏幕文本"),
                reason = "分享当前屏幕文本",
            ),
            manifest = SkillManifest(
                id = "screen_share_skill",
                version = 1,
                title = "Screen share skill",
                description = "Test skill",
                triggerExamples = listOf("分享当前屏幕文本"),
                requiredTools = listOf(
                    MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                    MobileActionFunctions.SHARE_TEXT,
                ),
                inputSchemaJson = """
                    {
                      "type": "object",
                      "required": ["input"],
                      "properties": {
                        "input": {
                          "type": "string",
                          "minLength": 1
                        }
                      },
                      "additionalProperties": false
                    }
                """.trimIndent(),
                riskLevel = RiskLevel.HighExternalSend,
            ),
            steps = listOf(screenStep, modelStep, shareStep),
        )

        val progression = progressor.nextToolAfterModelOutput(
            skillPlan = plan,
            requestedRequestIds = setOf(screenStep.request.id),
            modelOutput = "安全屏幕摘要",
        )

        require(progression is SkillModelOutputProgression.Rejected)
        assertTrue(progression.reason.contains("private tool output cannot be bound directly"))
        assertTrue(progression.reason.contains("read_screen.screenText"))
    }

    @Test
    fun publicOutputsRedactClipboardScreenshotImageOcrAndScreenTextPrivateFields() {
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
        val imageStep = SkillStep.ToolStep(
            id = "read_image",
            request = ToolRequest(
                id = "image",
                toolName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            ),
            draft = ActionDraft(
                functionName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
                title = "读取图片 OCR",
                summary = "读取图片 OCR",
                parameters = emptyMap(),
            ),
        )
        val screenStep = SkillStep.ToolStep(
            id = "read_screen",
            request = ToolRequest(
                id = "screen",
                toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            ),
            draft = ActionDraft(
                functionName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                title = "读取当前屏幕文本",
                summary = "读取当前屏幕文本",
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
        outputs[imageStep.id] = progressor.outputForToolResult(
            result = ToolResult(
                requestId = "image",
                status = ToolStatus.Succeeded,
                summary = "已读取图片 OCR",
                data = mapOf("ocrText" to "私密图片文字", "ocrTextIncluded" to "true"),
            ),
            draft = imageStep.draft,
        )
        privateRefs += progressor.privateOutputRefsFor(imageStep.id, imageStep.request.toolName)
        outputs[screenStep.id] = progressor.outputForToolResult(
            result = ToolResult(
                requestId = "screen",
                status = ToolStatus.Succeeded,
                summary = "已读取当前屏幕文本",
                data = mapOf("screenText" to "私密屏幕文本", "screenTextIncluded" to "true"),
            ),
            draft = screenStep.draft,
        )
        privateRefs += progressor.privateOutputRefsFor(screenStep.id, screenStep.request.toolName)

        val publicOutputs = progressor.publicOutputs(outputs, privateRefs)

        assertFalse(publicOutputs.toString().contains("私密剪贴板"))
        assertFalse(publicOutputs.toString().contains("私密截图文字"))
        assertFalse(publicOutputs.toString().contains("私密图片文字"))
        assertFalse(publicOutputs.toString().contains("私密屏幕文本"))
        assertEquals("false", publicOutputs[clipboardStep.id]?.get("truncated"))
        assertEquals("true", publicOutputs[screenshotStep.id]?.get("ocrTextIncluded"))
        assertEquals("true", publicOutputs[imageStep.id]?.get("ocrTextIncluded"))
        assertEquals("true", publicOutputs[screenStep.id]?.get("screenTextIncluded"))
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

    @Test
    fun nextToolAfterModelOutputRejectsToolWithUnmetDependencies() {
        val plan = BuiltInSkillRuntime().planClipboardSummaryShare("总结剪贴板并分享")
        val readStep = plan.steps[0] as SkillStep.ToolStep
        val modelStep = plan.steps[1] as SkillStep.ModelStep
        val shareStep = plan.steps[2] as SkillStep.ToolStep
        val foregroundStep = SkillStep.ToolStep(
            id = "check_foreground",
            request = ToolRequest(
                id = "foreground-request",
                toolName = MobileActionFunctions.QUERY_FOREGROUND_APP,
            ),
            draft = ActionDraft(
                functionName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                title = "查询前台应用",
                summary = "查询当前前台应用。",
                parameters = emptyMap(),
            ),
        )
        val brokenPlan = plan.copy(
            manifest = plan.manifest.copy(
                requiredTools = plan.manifest.requiredTools + MobileActionFunctions.QUERY_FOREGROUND_APP,
            ),
            steps = listOf(
                readStep,
                modelStep,
                foregroundStep,
                shareStep.copy(dependsOn = listOf(modelStep.id, foregroundStep.id)),
            ),
        )

        val progression = progressor.nextToolAfterModelOutput(
            skillPlan = brokenPlan,
            requestedRequestIds = setOf(readStep.request.id),
            modelOutput = "摘要",
        )

        require(progression is SkillModelOutputProgression.Rejected)
        assertTrue(progression.reason.contains("Unmet skill dependency"))
        assertTrue(progression.reason.contains("check_foreground"))
    }

}
