package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.tool.PublicWebEvidenceItem
import com.bytedance.zgx.solin.tool.PublicWebEvidencePack
import com.bytedance.zgx.solin.common.firstNonBlank
import com.bytedance.zgx.solin.common.jsonObjects
import com.bytedance.zgx.solin.evidence.EvidenceBlobStore
import com.bytedance.zgx.solin.tool.publicWebEvidencePackFromToolResultData
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolResultContinuationPolicy
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.failed
import org.json.JSONObject

internal const val PUBLIC_EVIDENCE_CITATION_RETRY_REASON = "public_evidence_citation_retry"

/**
 * Builds the REMOTE-eligible public-evidence continuation prompts for [AgentLoopRuntime]: single and
 * batch tool continuations, the batch audit summary, and the one-shot citation retry prompt.
 *
 * PRIVACY: [isPublicEvidenceForRemoteModel] is the gate that decides whether a tool result may be
 * placed in a prompt routed to a remote model. Only results that declare themselves RemoteEligible
 * AND requiresLocalModel=false pass; everything else is excluded (single path) or turned into a
 * failure (batch path).
 */
internal class PublicEvidenceContinuation(
    private val toolRegistry: ToolRegistry,
    private val traceStore: AgentTraceStore,
    private val traceQueries: AgentTraceQueries,
    private val evidenceBlobStore: EvidenceBlobStore,
) {
    fun continuationAfterToolObservation(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): ToolObservationContinuation? {
        if (!result.isPublicEvidenceForRemoteModel()) return null
        val evidenceBlocks = priorPublicEvidencePromptBlocks(
            runId = run.id,
            excludedRequestIds = setOf(request.id),
        ) + request.publicEvidencePromptBlock(result)
        return ToolObservationContinuation(
            prompt = publicEvidenceContinuationPrompt(
                run = run,
                evidenceBlocks = evidenceBlocks,
                gapBlocks = emptyList(),
            ),
            requiresLocalModel = false,
            canPlanNextToolBeforeModel = true,
        )
    }

    fun publicEvidenceBatchContinuationPrompt(
        run: AgentRun,
        observedPairs: List<Pair<AgentPlan.UseTool, ToolResult>>,
        successfulPairs: List<Pair<AgentPlan.UseTool, ToolResult>>,
        gapPairs: List<Pair<AgentPlan.UseTool, ToolResult>>,
    ): String {
        val currentRequestIds = observedPairs.mapTo(mutableSetOf()) { (plan, _) -> plan.request.id }
        val evidenceBlocks = priorPublicEvidencePromptBlocks(
            runId = run.id,
            excludedRequestIds = currentRequestIds,
        ) + successfulPairs.map { (plan, result) ->
            plan.request.publicEvidencePromptBlock(result)
        }
        val gapBlocks = gapPairs.map { (plan, result) ->
            plan.request.publicEvidenceGapBlock(result)
        }
        return publicEvidenceContinuationPrompt(
            run = run,
            evidenceBlocks = evidenceBlocks,
            gapBlocks = gapBlocks,
        )
    }

    internal fun publicEvidenceContinuationPrompt(
        run: AgentRun,
        evidenceBlocks: List<PublicEvidencePromptBlock>,
        gapBlocks: List<PublicEvidenceGapBlock>,
    ): String {
        val gapSection = if (gapBlocks.isEmpty()) {
            ""
        } else {
            """

            失败缺口：
            ${gapBlocks.renderPublicEvidenceGapBlocks()}
            """.trimIndent()
        }
        return """
            请根据以下公开只读工具结果回答用户原始问题。不要只回答最后一次工具结果；如果用户要求比较、计算、总结或判断，请综合所有可用证据完成对应推理。
            只以工具公开证据为依据，并以每条编号来源的 retrievedAt 判断时效；同一事实存在多条证据时，以 retrievedAt 最新且最相关的证据为准。
            涉及“最新”“目前”“当前”“今天”等时效性问题时，必须优先使用最新 retrievedAt 的工具证据；不得用模型训练知识、旧知识或未给出的网页内容补全空白。
            关键事实必须带来源编号，例如 [S1] [S2]；只能引用下方存在的来源编号，不能编造来源编号或链接。
            如果来源不足、冲突、质量为 Low，或缺少可核验 URL，请明确说明证据不足，不要给过度确定结论。
            如果存在失败缺口，请先基于成功证据部分回答；无法完成的部分明确说明缺少什么信息，不要编造。
            如果工具结果仍不足以完成用户请求，可以继续调用公开只读工具补充证据；仍不足时请明确说明缺少什么信息，不要编造。

            用户原始请求：${run.input}

            成功公开证据：
            ${evidenceBlocks.renderPublicEvidenceBlocks()}
            $gapSection
        """.trimIndent()
    }

    internal fun priorPublicEvidencePromptBlocks(
        runId: String,
        excludedRequestIds: Set<String>,
    ): List<PublicEvidencePromptBlock> {
        val requestsById = traceQueries.toolRequestsFor(runId).associateBy { request -> request.id }
        return traceStore.steps(runId)
            .asSequence()
            .mapNotNull { step -> (step as? AgentStep.ToolObserved)?.result }
            .filter { result ->
                result.requestId !in excludedRequestIds &&
                    result.status == ToolStatus.Succeeded &&
                    result.isPublicEvidenceForRemoteModel()
            }
            .mapNotNull { result ->
                val request = requestsById[result.requestId] ?: return@mapNotNull null
                val spec = toolRegistry.specFor(request.toolName) ?: return@mapNotNull null
                if (spec.resultContinuationPolicy != ToolResultContinuationPolicy.PublicEvidence) {
                    return@mapNotNull null
                }
                request.publicEvidencePromptBlock(result)
            }
            .toList()
    }

    private fun ToolResult.isPublicEvidenceForRemoteModel(): Boolean =
        data["privacy"] == MessagePrivacy.RemoteEligible.name &&
            data["requiresLocalModel"]?.toBooleanStrictOrNull() == false

    private fun ToolRequest.publicEvidencePromptBlock(result: ToolResult): PublicEvidencePromptBlock {
        val evidencePack = publicWebEvidencePackFromToolResultData(result.data)
        return PublicEvidencePromptBlock(
            toolName = toolName,
            argumentBlock = argumentsPromptBlock(),
            summary = result.summary,
            dataBlock = if (evidencePack == null) result.promptDataBlock(evidenceBlobStore) else "",
            evidencePack = evidencePack,
        )
    }

    private fun ToolRequest.publicEvidenceGapBlock(result: ToolResult): PublicEvidenceGapBlock =
        PublicEvidenceGapBlock(
            toolName = toolName,
            argumentBlock = argumentsPromptBlock(),
            status = result.status.name,
            summary = result.summary,
            errorMessage = result.error?.message,
        )

    private fun ToolRequest.argumentsPromptBlock(): String =
        arguments.entries
            .sortedBy { (key, _) -> key }
            .joinToString(separator = "\n") { (key, value) ->
                "$key: ${value.boundedPromptValue()}"
            }
            .ifBlank { "无" }

    fun List<Pair<AgentPlan.UseTool, ToolResult>>.publicEvidenceBatchAuditSummary(): String {
        val remotePairs = filter { (_, result) ->
            result.status == ToolStatus.Succeeded && result.isPublicEvidenceForRemoteModel()
        }
        if (remotePairs.isEmpty()) return "证据摘要：无可公开摘要"
        val queries = remotePairs
            .mapNotNull { (plan, result) ->
                result.data["query"]?.takeIf { it.isNotBlank() }
                    ?: plan.request.arguments["query"]?.takeIf { it.isNotBlank() }
            }
            .distinct()
            .take(3)
            .joinToString(separator = "、") { query -> "「${query.compactAuditValue(80)}」" }
        val sourceLabels = remotePairs
            .flatMap { (_, result) -> result.publicEvidenceSourceLabels() }
            .distinct()
        val keyItems = remotePairs
            .flatMap { (_, result) -> result.publicEvidenceKeyItems() }
            .distinct()
            .take(3)
            .joinToString(separator = "；") { item -> item.compactAuditValue(120) }
        val parts = buildList {
            if (queries.isNotBlank()) add("查询：$queries")
            if (sourceLabels.isNotEmpty()) add("来源 ${sourceLabels.size} 个")
            if (keyItems.isNotBlank()) add("关键项：$keyItems")
        }
        return if (parts.isEmpty()) {
            "证据摘要：无可公开摘要"
        } else {
            parts.joinToString(separator = "；", prefix = "证据摘要：")
        }
    }

    private fun ToolResult.publicEvidenceSourceLabels(): List<String> {
        val json = publicEvidenceResultsJsonObject()
        val sourceLabels = json?.optJSONArray("sources")
            ?.jsonObjects()
            ?.mapNotNull { source ->
                firstNonBlank(
                    source.optString("name").trim(),
                    source.optString("url").trim(),
                    source.optString("id").trim(),
                )
            }
            .orEmpty()
        if (sourceLabels.isNotEmpty()) return sourceLabels
        return listOfNotNull(
            data["source"]?.takeIf { it.isNotBlank() },
            json?.optString("provider")?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private fun ToolResult.publicEvidenceKeyItems(): List<String> {
        val json = publicEvidenceResultsJsonObject()
        val resultItems = json?.optJSONArray("results")
            ?.jsonObjects()
            ?.mapNotNull { result ->
                val title = result.optString("title").trim()
                val url = result.optString("url").trim()
                val requestedLocation = result.optString("requestedLocation").trim()
                val location = result.optString("location").trim()
                when {
                    title.isNotBlank() && url.isNotBlank() -> "$title $url"
                    url.isNotBlank() -> url
                    title.isNotBlank() -> title
                    requestedLocation.isNotBlank() -> requestedLocation
                    location.isNotBlank() -> location
                    else -> null
                }
            }
            .orEmpty()
        val sourceLinks = json?.optJSONArray("sources")
            ?.jsonObjects()
            ?.mapNotNull { source -> source.optString("url").trim().takeIf { it.isNotBlank() } }
            .orEmpty()
        val fallbackSummary = data["summaryText"]
            ?.takeIf { it.isNotBlank() }
            ?: summary.takeIf { it.isNotBlank() }
        return (resultItems + sourceLinks).ifEmpty {
            listOfNotNull(fallbackSummary)
        }
    }

    private fun ToolResult.publicEvidenceResultsJsonObject(): JSONObject? =
        data["resultsJson"]?.let { rawJson ->
            runCatching { JSONObject(rawJson) }.getOrNull()
        }

    private fun List<PublicEvidencePromptBlock>.renderPublicEvidenceBlocks(): String =
        if (isEmpty()) {
            "无"
        } else {
            val numberedItemsByBlock = numberedEvidenceItemsByBlock()
            mapIndexed { index, block ->
                val evidencePack = block.evidencePack
                val numberedItems = numberedItemsByBlock[index]
                if (numberedItems.isNotEmpty()) {
                    """
                        工具 ${index + 1}
                        工具名称：${block.toolName}
                        工具参数：
                        ${block.argumentBlock}
                        工具观察：${block.summary.boundedPromptValue()}
                        证据包：retrievedAt=${evidencePack?.retrievedAt.orEmpty()} freshness=${evidencePack?.freshness.orEmpty()} quality=${evidencePack?.quality.orEmpty()}
                        编号来源：
                        ${numberedItems.joinToString(separator = "\n\n") { item -> item.renderPublicWebEvidencePromptItem() }}
                    """.trimIndent()
                } else {
                """
                    工具 ${index + 1}
                    工具名称：${block.toolName}
                    工具参数：
                    ${block.argumentBlock}
                    工具观察：${block.summary.boundedPromptValue()}
                    工具公开数据：
                    ${block.dataBlock}
                """.trimIndent()
                }
            }.joinToString(separator = "\n\n")
        }

    private fun NumberedPublicWebEvidenceItem.renderPublicWebEvidencePromptItem(): String =
        """
            [$sourceId] ${item.title.boundedPromptValue()}
            source: ${item.sourceName.ifBlank { "unknown" }.boundedPromptValue()}
            url: ${item.url.ifBlank { "无可核验 URL" }.boundedPromptValue()}
            retrievedAt: ${pack.retrievedAt.ifBlank { "unknown" }}
            freshness: ${pack.freshness.ifBlank { "unknown" }}
            quality: ${item.qualityLabel.ifBlank { pack.quality }.ifBlank { "unknown" }}
            snippet: ${item.snippet.ifBlank { "无摘要" }.boundedPromptValue()}
        """.trimIndent()

    fun citationRetryPromptOrNull(
        run: AgentRun,
        answer: String,
    ): String? {
        if (publicEvidenceCitationRetryAlreadyAttempted(run.id)) return null
        val numberedItems = numberedPublicEvidenceItemsForRun(run.id)
        if (numberedItems.isEmpty()) return null
        val check = checkPublicWebAnswerCitations(
            answer = answer,
            sourceIds = numberedItems.mapTo(linkedSetOf()) { item -> item.sourceId },
            hasLowQualityEvidence = numberedItems.any { item ->
                item.item.qualityLabel == "Low" || item.pack.quality == "Low"
            },
            hasVerifiableUrl = numberedItems.any { item -> item.item.url.isNotBlank() },
        )
        if (check.status == AnswerCitationCheckStatus.Warning) {
            recordPublicEvidenceCitationWarningIfNeeded(run.id, check.reason, answer.length)
        }
        if (!check.shouldRetry) return null
        return """
            上一版回答未通过公开来源引用检查：${check.reason}。
            请重写一次最终答案：关键事实必须引用下方已有编号来源；不能引用不存在的来源编号；证据不足、冲突、低质量或无可核验 URL 时必须明说。不要输出工具 JSON。

            上一版回答：
            ${answer.boundedPromptValue()}

            ${publicEvidenceContinuationPrompt(
            run = run,
            evidenceBlocks = priorPublicEvidencePromptBlocks(
                runId = run.id,
                excludedRequestIds = emptySet(),
            ),
            gapBlocks = emptyList(),
        )}
        """.trimIndent()
    }

    internal fun numberedPublicEvidenceItemsForRun(runId: String): List<NumberedPublicWebEvidenceItem> =
        priorPublicEvidencePromptBlocks(
            runId = runId,
            excludedRequestIds = emptySet(),
        ).numberedEvidenceItemsByBlock().flatten()

    /**
     * The single source of citation ids: numbers every evidence item S1, S2, ... across [this] blocks
     * in order (blocks without an evidence pack contribute no ids). Both the rendered prompt and the
     * citation check read from here, so the ids the model sees are the ids the check accepts.
     */
    private fun List<PublicEvidencePromptBlock>.numberedEvidenceItemsByBlock(): List<List<NumberedPublicWebEvidenceItem>> {
        var sourceIndex = 1
        return map { block ->
            val pack = block.evidencePack ?: return@map emptyList()
            pack.items.map { item ->
                NumberedPublicWebEvidenceItem(
                    sourceId = "S${sourceIndex++}",
                    pack = pack,
                    item = item,
                )
            }
        }
    }

    private fun recordPublicEvidenceCitationWarningIfNeeded(
        runId: String,
        reason: String,
        rawOutputLength: Int,
    ) {
        val alreadyRecorded = traceStore.steps(runId).any { step ->
            val trace = (step as? AgentStep.ModelOutputQualityGuardTriggered)?.trace
            trace?.triggeredRule == "public_web_answer_citation_check" &&
                trace.issue == reason
        }
        if (alreadyRecorded) return
        traceStore.appendStep(
            runId,
            AgentStep.ModelOutputQualityGuardTriggered(
                ModelOutputQualityTrace(
                    issue = reason,
                    severity = "warning",
                    triggeredRule = "public_web_answer_citation_check",
                    action = "record",
                    rawOutputLength = rawOutputLength,
                    keptPrefix = true,
                    modelId = null,
                    backend = null,
                    runtimeKind = "unknown",
                ),
            ),
        )
    }

    private fun publicEvidenceCitationRetryAlreadyAttempted(runId: String): Boolean =
        traceStore.steps(runId).any { step ->
            val decision = (step as? AgentStep.ObservationDecided)?.decision
            decision is AgentObservationDecision.ContinueWithModel &&
                decision.reason == PUBLIC_EVIDENCE_CITATION_RETRY_REASON
        }

    private fun List<PublicEvidenceGapBlock>.renderPublicEvidenceGapBlocks(): String =
        if (isEmpty()) {
            "无"
        } else {
            mapIndexed { index, block ->
                """
                    缺口 ${index + 1}
                    工具名称：${block.toolName}
                    工具参数：
                    ${block.argumentBlock}
                    状态：${block.status}
                    失败摘要：${block.summary.boundedPromptValue()}
                    错误：${block.errorMessage?.boundedPromptValue() ?: "无"}
                """.trimIndent()
            }.joinToString(separator = "\n\n")
        }

    fun ToolResult.publicEvidenceBatchResultOrFailure(request: ToolRequest): ToolResult {
        if (status != ToolStatus.Succeeded) return this
        if (!isPublicEvidenceForRemoteModel()) {
            val summary = "Tool ${request.toolName} returned local-only evidence in a public evidence batch."
            return request.failed(
                code = ToolErrorCode.InvalidResult,
                summary = summary,
                retryable = false,
                data = mapOf("toolName" to request.toolName),
            )
        }
        return this
    }

    private fun String.boundedPromptValue(maxLength: Int = 2_000): String =
        boundedPromptValue(evidenceBlobStore, maxLength)

    private fun String.compactAuditValue(maxLength: Int): String =
        trim().replace(auditWhitespaceRegex, " ").boundedPromptValue(maxLength)

    internal data class PublicEvidencePromptBlock(
        val toolName: String,
        val argumentBlock: String,
        val summary: String,
        val dataBlock: String,
        val evidencePack: PublicWebEvidencePack? = null,
    )

    internal data class NumberedPublicWebEvidenceItem(
        val sourceId: String,
        val pack: PublicWebEvidencePack,
        val item: PublicWebEvidenceItem,
    )

    internal data class PublicEvidenceGapBlock(
        val toolName: String,
        val argumentBlock: String,
        val status: String,
        val summary: String,
        val errorMessage: String?,
    )
}

private val auditWhitespaceRegex = Regex("\\s+")

private val ANSWER_CITATION_REGEX = Regex("""\[(S\d+)]""")
private val OVER_CERTAIN_PUBLIC_EVIDENCE_REGEX =
    Regex("""(?i)(已证实|证实了|确认了|可以确定|毫无疑问|一定是|confirmed|proven|definitely|certainly)""")

internal enum class AnswerCitationCheckStatus {
    Pass,
    Fail,
    Warning,
}

internal data class AnswerCitationCheck(
    val status: AnswerCitationCheckStatus,
    val reason: String,
    val missingSourceIds: Set<String> = emptySet(),
) {
    val shouldRetry: Boolean
        get() = status == AnswerCitationCheckStatus.Fail
}

internal fun checkPublicWebAnswerCitations(
    answer: String,
    sourceIds: Set<String>,
    hasLowQualityEvidence: Boolean,
    hasVerifiableUrl: Boolean,
): AnswerCitationCheck {
    if (sourceIds.isEmpty()) {
        return AnswerCitationCheck(AnswerCitationCheckStatus.Pass, "no_public_web_evidence")
    }
    val citedIds = ANSWER_CITATION_REGEX.findAll(answer)
        .map { match -> match.groupValues[1] }
        .toSet()
    if (citedIds.isEmpty()) {
        return AnswerCitationCheck(AnswerCitationCheckStatus.Fail, "missing_citation")
    }
    val invalidIds = citedIds - sourceIds
    if (invalidIds.isNotEmpty()) {
        return AnswerCitationCheck(
            status = AnswerCitationCheckStatus.Fail,
            reason = "unknown_source_id",
            missingSourceIds = invalidIds,
        )
    }
    if (!hasVerifiableUrl && OVER_CERTAIN_PUBLIC_EVIDENCE_REGEX.containsMatchIn(answer)) {
        return AnswerCitationCheck(AnswerCitationCheckStatus.Fail, "unverifiable_confirmation_claim")
    }
    if (hasLowQualityEvidence && OVER_CERTAIN_PUBLIC_EVIDENCE_REGEX.containsMatchIn(answer)) {
        return AnswerCitationCheck(AnswerCitationCheckStatus.Warning, "low_quality_over_certainty")
    }
    return AnswerCitationCheck(AnswerCitationCheckStatus.Pass, "citations_valid")
}
