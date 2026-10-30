package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.screenObservationFromJsonStringOrNull
import com.bytedance.zgx.solin.device.toElementTable
import com.bytedance.zgx.solin.evidence.EvidenceBlobStore
import com.bytedance.zgx.solin.memory.PerRunScratchpad
import com.bytedance.zgx.solin.skill.SkillModelStepBinding
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.skill.SkillRunProgressor
import com.bytedance.zgx.solin.skill.SkillStep
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolResultContinuationPolicy
import com.bytedance.zgx.solin.tool.ToolStatus

private const val REDACTED_AGENT_RUN_INPUT_VALUE = "[redacted]"

/**
 * Builds the continuation prompt that follows a successful tool observation for [AgentLoopRuntime].
 *
 * PRIVACY / ROUTING: clipboard, OCR, screen-text and LocalEvidence continuations are always
 * `requiresLocalModel = true` (LocalOnly routing); skill model-step continuations are local whenever
 * any private input or output is involved. Public-evidence continuations are delegated to
 * [PublicEvidenceContinuation], whose remote-eligibility gate decides the remote path. Scratchpad notes
 * are only appended to local-model continuations.
 */
internal class ToolObservationContinuationBuilder(
    private val toolRegistry: ToolRegistry,
    private val toolPlanCoordinator: ToolPlanCoordinator,
    private val initialToolPlanner: InitialToolPlanner,
    private val skillProgressor: SkillRunProgressor,
    private val traceQueries: AgentTraceQueries,
    private val publicEvidenceContinuation: PublicEvidenceContinuation,
    private val scratchpad: PerRunScratchpad,
    private val evidenceBlobStore: EvidenceBlobStore,
) {
    fun continuationForToolObservation(
        run: AgentRun,
        request: ToolRequest?,
        result: ToolResult,
    ): ToolObservationContinuation? {
        if (result.status != ToolStatus.Succeeded) return null
        skillModelContinuationAfterToolObservation(run, request, result)?.let { continuation ->
            // Skill model steps demand an exact single output; they never carry scratchpad notes.
            return continuation
        }
        return builtInContinuationForToolObservation(run, request, result)
            ?.withLocalScratchpadNotes(run.id)
    }

    /**
     * Appends the run's `note` scratchpad to a continuation prompt — only when the continuation is
     * pinned to the LOCAL model. Notes can be copied from screen text, OCR or private tool results,
     * so a continuation that may be routed to a remote model (requiresLocalModel = false) fails closed
     * and never carries them. The scratchpad is never read into remote prompts or audit anywhere else.
     */
    private fun ToolObservationContinuation.withLocalScratchpadNotes(runId: String): ToolObservationContinuation {
        if (!requiresLocalModel) return this
        val notes = runCatching { scratchpad.formatForPrompt(runId) }.getOrNull() ?: return this
        return copy(prompt = "$prompt\n\n$notes")
    }

    private fun builtInContinuationForToolObservation(
        run: AgentRun,
        request: ToolRequest?,
        result: ToolResult,
    ): ToolObservationContinuation? {
        return when (request?.toolName) {
            MobileActionFunctions.READ_CLIPBOARD -> {
                val clipboardText = result.data["text"]?.takeIf { it.isNotBlank() } ?: return null
                val truncated = result.data["truncated"]?.toBooleanStrictOrNull() ?: false
                ToolObservationContinuation(
                    prompt = """
                    用户已经确认读取剪贴板。请根据用户原始请求处理剪贴板文本。
                    如果用户没有明确要求逐字复述，不要完整抄回剪贴板原文；优先总结、改写、提取信息或回答问题。
                    不要使用与当前请求无关的隐私内容。

                    用户原始请求：${run.input}
                    工具观察：${result.summary}
                    剪贴板文本${if (truncated) "（已截断）" else ""}：
                    $clipboardText
                    """.trimIndent(),
                    requiresLocalModel = true,
                    canPlanNextToolBeforeModel = toolPlanCoordinator.canPlanLocalToolFromCurrentScreenObservation(request, run),
                )
            }

            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR -> {
                val ocrText = result.data["ocrText"]?.takeIf { it.isNotBlank() } ?: return null
                val truncated = result.data["truncated"]?.toBooleanStrictOrNull() ?: false
                val screenObservationJson = if (request.toolName == MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR) {
                    result.data["screenObservationJson"]?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                val contentLabel = request.toolName.recentImageOcrContentLabel()
                val sourceBoundary = when (request.toolName) {
                    MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR ->
                        "这不是当前屏幕捕获，也不是图片语义理解；只使用已提取的截图文字。"

                    MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR ->
                        "这是用户前台同意后的单次当前屏幕截图 OCR；只使用本地 OCR 文本和可选 Accessibility/OCR 结构化观测，不推断视觉语义。"

                    else ->
                        "这不是当前屏幕捕获，也不是图片语义理解；只使用已提取的图片文字。"
                }
                val screenObservationSection = screenObservationJson?.let { observationJson ->
                    // Render a compact numbered element table (text Set-of-Marks) instead of raw
                    // JSON so the model picks a target by its stable [id] token. Fall back to the
                    // raw JSON only if parsing fails, so a schema change never blanks the screen.
                    val elementTable = screenObservationFromJsonStringOrNull(observationJson)
                        ?.toElementTable()
                    val rendered = elementTable ?: observationJson
                    """

                    当前屏幕结构化观测（LocalOnly，融合 OCR/Accessibility）：
                    $rendered
                    """.trimIndent()
                }.orEmpty()
                ToolObservationContinuation(
                    prompt = """
                    用户已经确认读取$contentLabel 并在本地提取 OCR 文本。请根据用户原始请求处理 OCR 摘录。
                    $sourceBoundary
                    如果用户没有明确要求逐字复述，不要完整抄回 OCR 原文；优先总结、改写、提取信息或回答问题。

                    用户原始请求：${run.input}
                    工具观察：${result.summary}
                    $contentLabel OCR 文本${if (truncated) "（已截断）" else ""}：
                    $ocrText
                    $screenObservationSection
                    """.trimIndent(),
                    requiresLocalModel = true,
                    canPlanNextToolBeforeModel = toolPlanCoordinator.canPlanLocalToolFromCurrentScreenObservation(request, run),
                )
            }

            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT -> {
                val screenText = result.data["screenText"]?.takeIf { it.isNotBlank() } ?: return null
                val truncated = result.data["truncated"]?.toBooleanStrictOrNull() ?: false
                ToolObservationContinuation(
                    prompt = """
                    用户已经确认读取当前 active window 的 Accessibility 可访问文本快照。请根据用户原始请求处理这段屏幕文本。
                    这不是截图捕获、不是 OCR、不是视觉/VLM 或语义屏幕理解；只使用当前屏幕暴露的可访问文本。
                    如果用户没有明确要求逐字复述，不要完整抄回屏幕文本；优先总结、提取信息或回答问题。

                    用户原始请求：${run.input}
                    工具观察：${result.summary}
                    当前屏幕文本${if (truncated) "（已截断）" else ""}：
                    $screenText
                    """.trimIndent(),
                    requiresLocalModel = true,
                )
            }

            // A note is a scratchpad write, not evidence to answer from: the run keeps replanning and
            // the note reaches later local-model prompts via [withScratchpadNotes]. Its private
            // output key still drives trace/audit redaction.
            MobileActionFunctions.NOTE -> null

            else -> policyContinuationAfterToolObservation(run, request, result)
        }
    }

    private fun policyContinuationAfterToolObservation(
        run: AgentRun,
        request: ToolRequest?,
        result: ToolResult,
    ): ToolObservationContinuation? {
        request ?: return null
        val spec = toolRegistry.specFor(request.toolName) ?: return null
        return when (spec.resultContinuationPolicy) {
            ToolResultContinuationPolicy.None -> null
            ToolResultContinuationPolicy.PublicEvidence ->
                publicEvidenceContinuation.continuationAfterToolObservation(run, request, result)

            ToolResultContinuationPolicy.LocalEvidence ->
                localEvidenceContinuationAfterToolObservation(run, request, result)
        }
    }

    private fun localEvidenceContinuationAfterToolObservation(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): ToolObservationContinuation? {
        if (result.data["privacy"] != MessagePrivacy.LocalOnly.name) return null
        if (result.data["requiresLocalModel"]?.toBooleanStrictOrNull() != true) return null
        val localData = result.promptDataBlock(evidenceBlobStore)
        return ToolObservationContinuation(
            prompt = """
                用户已经确认读取本地只读工具结果。请只根据用户原始请求处理这份本地证据，不要使用与当前请求无关的隐私内容。
                如果用户没有明确要求逐字复述，不要完整抄回本地原始数据；优先总结、比较、提取信息或回答问题。

                用户原始请求：${run.input}
                工具名称：${request.toolName}
                工具观察：${result.summary}
                本地工具数据：
                $localData
            """.trimIndent(),
            requiresLocalModel = true,
            canPlanNextToolBeforeModel = toolPlanCoordinator.canPlanLocalToolFromCurrentScreenObservation(request, run),
        )
    }

    private fun skillModelContinuationAfterToolObservation(
        run: AgentRun,
        request: ToolRequest?,
        result: ToolResult,
    ): ToolObservationContinuation? {
        request ?: return null
        val skillPlan = traceQueries.latestSkillPlan(run.id) ?: return null
        if (initialToolPlanner.invalidSkillPlanReason(skillPlan) != null) return null
        val currentStepIndex = skillPlan.steps.indexOfFirst { step ->
            step is SkillStep.ToolStep && step.request.id == request.id
        }
        if (currentStepIndex < 0) return null
        val currentStep = skillPlan.steps[currentStepIndex] as SkillStep.ToolStep
        val requestedStepIds = skillPlan.steps
            .filterIsInstance<SkillStep.ToolStep>()
            .filter { step -> traceQueries.toolRequestFor(run.id, step.request.id) != null }
            .mapTo(mutableSetOf()) { step -> step.id }
        val nextModelStep = skillPlan.steps
            .drop(currentStepIndex + 1)
            .filterIsInstance<SkillStep.ModelStep>()
            .firstOrNull { step ->
                currentStep.id in step.dependsOn && step.dependsOn.all { dependency -> dependency in requestedStepIds }
            } ?: return null
        val outputs = skillProgressor.initialOutputs(skillPlan)
        outputs[currentStep.id] = skillProgressor.outputForToolResult(result, currentStep.draft)
        val inputs = when (val binding = skillProgressor.bindModelStep(nextModelStep, outputs)) {
            is SkillModelStepBinding.Bound -> binding.inputs
            is SkillModelStepBinding.Missing -> return null
        }
        val originalRequest = originalRequestForSkillContinuation(run, skillPlan)
        val privateRefs = skillProgressor.privateOutputRefsFor(currentStep.id, request.toolName)
        val requiresLocalModel = nextModelStep.keepsSensitiveInputLocal ||
            nextModelStep.inputBindings.values.any { sourceRef -> sourceRef in privateRefs } ||
            toolRegistry.privateOutputKeysFor(request.toolName).isNotEmpty() ||
            result.requiresLocalModelContinuation()
        val inputBlock = inputs.entries.joinToString(separator = "\n\n") { (name, value) ->
            "$name:\n$value"
        }
        return ToolObservationContinuation(
            prompt = """
                继续执行本地 Skill：${skillPlan.manifest.title}
                用户原始请求：$originalRequest
                当前模型步骤：${nextModelStep.title}
                步骤指令：${nextModelStep.instruction}

                可用输入：
                $inputBlock

                只输出 `${nextModelStep.outputKey}` 对应的文本，不要输出 JSON 或额外说明。
            """.trimIndent(),
            requiresLocalModel = requiresLocalModel,
            blocksSequentialTail = true,
        )
    }

    private fun originalRequestForSkillContinuation(
        run: AgentRun,
        skillPlan: SkillPlan,
    ): String {
        return run.input
            .takeIf { input -> input.isNotBlank() && input != REDACTED_AGENT_RUN_INPUT_VALUE }
            ?: skillPlan.request.arguments["input"]?.takeIf { input ->
                input.isNotBlank() && input != REDACTED_AGENT_RUN_INPUT_VALUE
            }
            ?: skillPlan.request.reason.takeIf { reason ->
                reason.isNotBlank() && reason != REDACTED_AGENT_RUN_INPUT_VALUE
            }
            ?: skillPlan.manifest.title
    }

    private fun ToolResult.requiresLocalModelContinuation(): Boolean =
        data["requiresLocalModel"]?.toBooleanStrictOrNull() == true ||
            data["privacy"] == MessagePrivacy.LocalOnly.name

    private fun String?.recentImageOcrContentLabel(): String =
        when (this) {
            MobileActionFunctions.READ_RECENT_IMAGE_OCR -> "最近图片"
            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR -> "当前屏幕截图"
            else -> "最近截图"
        }

    private fun String.boundedPromptValue(maxLength: Int = 2_000): String =
        boundedPromptValue(evidenceBlobStore, maxLength)
}
