package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.LocalModelTokenLimits
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.TranslationIntentParser
import com.bytedance.zgx.solin.device.DeviceContextSnapshot
import com.bytedance.zgx.solin.evidence.EvidenceCard
import com.bytedance.zgx.solin.evidence.EvidenceQuality
import com.bytedance.zgx.solin.evidence.EvidenceQualityLevel
import com.bytedance.zgx.solin.evidence.EvidenceSourceType
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.memory.MemoryIndex
import com.bytedance.zgx.solin.memory.isAvailableForLocalContext
import com.bytedance.zgx.solin.runtime.estimateLocalRuntimeTokens

/**
 * Assembles the per-turn user prompt string sent to the model by combining the raw user input
 * with structured evidence cards drawn from local memory hits, device context snapshot, and
 * [SystemPromptBuilder]-contributed cards.
 *
 * This component was mechanically extracted from [AgentLoopRuntime] to isolate the token-budget
 * selection and evidence-prompt formatting concerns. Behavior MUST remain byte-for-byte equivalent
 * to the original inline implementation: constants, comparator order, Chinese prompt scaffolding,
 * truncation fallback, and the `memoryIndex.buildContext()` last-resort fallback are preserved
 * exactly.
 *
 * This class owns only the USER-PROMPT string. It does NOT assemble ChatMessage history (that is
 * owned by SolinViewModel and the local/remote runtimes) and it does NOT set the system
 * instruction (that is set out-of-band in LiteRtRuntime/RemoteChatRuntime and is independent of
 * the per-turn [SystemPromptBuilder] evidence contributions, which are inlined into the user
 * prompt as "其他上下文" cards in the current codebase).
 */
class ContextAssembler(
    private val systemPromptBuilder: SystemPromptBuilder? = null,
    private val memoryIndex: MemoryIndex? = null
) {
    /**
     * Build the user-prompt string for an answer turn. If there is no memory, no device context,
     * and no contributor cards, returns [input] unchanged (fast path, identical to prior behavior).
     * Otherwise wraps [input] with the Chinese-language evidence scaffolding after running the
     * token-budget selection over all cards.
     */
    fun assembleAnswerPrompt(
        input: String,
        memoryHits: List<MemoryHit>,
        deviceContext: DeviceContextSnapshot?,
        run: AgentRun
    ): String {
        TranslationIntentParser.parse(input)?.let { request -> return request.chatInstruction() }
        return assembleAnswerPromptWithCards(
            input = input,
            memoryHits = memoryHits,
            deviceContext = deviceContext,
            contributorCards = loadContributorCards(input, run).cards
        )
    }

    /** Local answer prompt plus its privacy-safe remote projection, kept paired in one plan. */
    fun answerPlan(
        input: String,
        memoryHits: List<MemoryHit>,
        deviceContext: DeviceContextSnapshot?,
        run: AgentRun
    ): AgentPlan.Answer {
        TranslationIntentParser.parse(input)?.let { request ->
            return AgentPlan.Answer(
                promptForModel = request.chatInstruction(),
                memoryHits = memoryHits,
                deviceContext = deviceContext,
                remotePromptForModel = null
            )
        }
        // Load contributor cards once per turn so local and remote projections stay consistent
        // without repeating runBlocking.
        val loaded = loadContributorCards(input, run)
        return AgentPlan.Answer(
            promptForModel = assembleAnswerPromptWithCards(input, memoryHits, deviceContext, loaded.cards),
            memoryHits = memoryHits,
            deviceContext = deviceContext,
            remotePromptForModel = remotePromptProjectionWithCards(input, memoryHits, deviceContext, loaded)
        )
    }

    /**
     * Prompt that may be sent to a remote model: the raw [input], or null when the local prompt
     * carries anything beyond an explicitly allowlisted static card. No local memory, device
     * context or translation turn, and every included contributor card id must be in
     * [REMOTE_PROJECTION_ALLOWED_CARD_IDS] and [MessagePrivacy.RemoteEligible]; RemoteEligible alone is never trusted.
     */
    fun remotePromptProjection(
        input: String,
        memoryHits: List<MemoryHit>,
        deviceContext: DeviceContextSnapshot?,
        run: AgentRun
    ): String? {
        if (memoryHits.isNotEmpty() || deviceContext != null) return null
        if (TranslationIntentParser.parse(input) != null) return null
        return remotePromptProjectionWithCards(input, memoryHits, deviceContext, loadContributorCards(input, run))
    }

    private data class LoadedContributorCards(
        val cards: List<EvidenceCard>,
        val failed: Boolean
    )

    private fun loadContributorCards(input: String, run: AgentRun): LoadedContributorCards {
        val builder = systemPromptBuilder ?: return LoadedContributorCards(emptyList(), failed = false)
        val prompt = runCatching { builder.buildSystemPromptBlocking(input, run) }.getOrNull()
            ?: return LoadedContributorCards(emptyList(), failed = true)
        val cards = prompt.evidenceCards.filter { card -> !card.requiresLocalModel }
        return LoadedContributorCards(cards = cards, failed = prompt.hadContributorFailure)
    }

    private fun remotePromptProjectionWithCards(
        input: String,
        memoryHits: List<MemoryHit>,
        deviceContext: DeviceContextSnapshot?,
        loaded: LoadedContributorCards
    ): String? {
        if (memoryHits.isNotEmpty() || deviceContext != null || loaded.failed) return null
        val allowed = loaded.cards.all { card ->
            card.privacy == MessagePrivacy.RemoteEligible &&
                card.id in REMOTE_PROJECTION_ALLOWED_CARD_IDS
        }
        return input.takeIf { allowed }
    }

    private fun assembleAnswerPromptWithCards(
        input: String,
        memoryHits: List<MemoryHit>,
        deviceContext: DeviceContextSnapshot?,
        contributorCards: List<EvidenceCard>
    ): String {
        val localMemoryHits = memoryHits.filter { hit -> hit.isAvailableForLocalContext() }
        val builtInCards = evidenceCardsForAnswerContext(localMemoryHits, deviceContext)
        val allCards = builtInCards + contributorCards
        if (
            localMemoryHits.isEmpty() &&
            deviceContext == null &&
            contributorCards.isEmpty()
        ) {
            return input
        }
        val evidenceCards = budgetEvidenceCards(
            cards = allCards,
            input = input
        )
        val memoryBlock = evidenceCards
            .filter { card -> card.sourceType == EvidenceSourceType.Memory }
            .joinToString(separator = "\n") { card -> card.toPromptLine() }
            .ifBlank {
                runCatching { memoryIndex?.buildContext(localMemoryHits) }.getOrDefault("").orEmpty()
            }
        val safeMemoryBlock = if (memoryBlock.isBlank()) {
            "无"
        } else {
            memoryBlock
        }
        val deviceBlock = evidenceCards
            .firstOrNull { card -> card.sourceType == EvidenceSourceType.DeviceContext }
            ?.toPromptLine(prefix = "")
            ?: "无"
        val otherCards = evidenceCards.filter { card ->
            card.sourceType != EvidenceSourceType.Memory &&
                card.sourceType != EvidenceSourceType.DeviceContext
        }
        val otherBlock = if (otherCards.isEmpty()) {
            null
        } else {
            otherCards.joinToString(separator = "\n") { card -> card.toPromptLine() }
        }
        val otherSection = otherBlock?.let { block ->
            """

                其他上下文：
                $block
            """.trimIndent()
        } ?: ""
        return """
            请根据用户当前输入的语言回答。只有在以下本地记忆或设备上下文与当前问题明显相关时才使用；如果无关，请忽略，不要复述无关隐私内容。
            以下上下文已按相关性、隐私边界和端侧 token 预算裁剪；被标记为截断的内容只能作为弱证据。
            本地记忆：
            $safeMemoryBlock

            设备上下文：
            $deviceBlock$otherSection

            用户问题：$input
        """.trimIndent()
    }

    private fun evidenceCardsForAnswerContext(
        memoryHits: List<MemoryHit>,
        deviceContext: DeviceContextSnapshot?
    ): List<EvidenceCard> = buildList {
        memoryHits
            .filter { hit -> hit.isAvailableForLocalContext() }
            .forEach { hit ->
                add(
                    EvidenceCard(
                        id = "memory:${hit.id}",
                        sourceType = EvidenceSourceType.Memory,
                        privacy = MessagePrivacy.LocalOnly,
                        requiresLocalModel = true,
                        text = hit.text,
                        quality = EvidenceQuality(hit.memoryEvidenceQualityLevel()),
                        tokenEstimate = estimateLocalRuntimeTokens(hit.text)
                    )
                )
            }
        val deviceText = deviceContext?.toPromptContext()?.takeIf { it.isNotBlank() }
        if (deviceText != null) {
            add(
                EvidenceCard(
                    id = "device-context",
                    sourceType = EvidenceSourceType.DeviceContext,
                    privacy = MessagePrivacy.LocalOnly,
                    requiresLocalModel = true,
                    text = deviceText,
                    quality = EvidenceQuality(EvidenceQualityLevel.Medium),
                    tokenEstimate = estimateLocalRuntimeTokens(deviceText)
                )
            )
        }
    }

    private fun budgetEvidenceCards(
        cards: List<EvidenceCard>,
        input: String
    ): List<EvidenceCard> {
        if (cards.isEmpty()) return emptyList()
        var remainingTokens = (
            ANSWER_CONTEXT_TOKEN_BUDGET -
                estimateLocalRuntimeTokens(input) -
                ANSWER_PROMPT_SCAFFOLD_TOKEN_RESERVE
            ).coerceAtLeast(0)
        if (remainingTokens <= 0) return emptyList()
        val selected = mutableListOf<EvidenceCard>()
        cards.sortedWith(evidencePriorityComparator).forEach { card ->
            val cost = card.tokenEstimate.coerceAtLeast(estimateLocalRuntimeTokens(card.text))
            when {
                cost <= remainingTokens -> {
                    selected += card
                    remainingTokens -= cost
                }
                remainingTokens >= MIN_TRUNCATED_EVIDENCE_TOKENS -> {
                    val truncatedText = card.text.takeFirstEstimatedTokens(remainingTokens)
                    if (truncatedText.isNotBlank()) {
                        selected += card.copy(
                            text = truncatedText,
                            truncated = true,
                            tokenEstimate = estimateLocalRuntimeTokens(truncatedText)
                        )
                        remainingTokens = 0
                    }
                }
            }
            if (remainingTokens <= 0) return@forEach
        }
        return selected.sortedWith(evidenceRenderComparator)
    }

    private companion object {
        val REMOTE_PROJECTION_ALLOWED_CARD_IDS: Set<String> = setOf("available-skills")
        val ANSWER_CONTEXT_TOKEN_BUDGET =
            LocalModelTokenLimits.MAX_INPUT_TOKENS -
                LocalModelTokenLimits.SYSTEM_PROMPT_TOKEN_RESERVE -
                LocalModelTokenLimits.CURRENT_PROMPT_TOKEN_RESERVE
        const val ANSWER_PROMPT_SCAFFOLD_TOKEN_RESERVE: Int = 256
        const val MIN_TRUNCATED_EVIDENCE_TOKENS: Int = 96

        val evidencePriorityComparator: Comparator<EvidenceCard> =
            compareBy<EvidenceCard> { it.sourceType.priorityForPromptBudget() }
                .thenByDescending { it.quality.level.priorityForPromptBudget() }

        val evidenceRenderComparator: Comparator<EvidenceCard> =
            compareBy { it.sourceType.priorityForPromptRender() }

        fun EvidenceSourceType.priorityForPromptBudget(): Int = when (this) {
            EvidenceSourceType.Memory -> 0
            EvidenceSourceType.DeviceContext -> 1
            EvidenceSourceType.UserPrompt -> 2
            EvidenceSourceType.ToolResult,
            EvidenceSourceType.OcrText,
            EvidenceSourceType.FilePreview,
            EvidenceSourceType.ImageAttachment,
            EvidenceSourceType.PublicWeb,
            EvidenceSourceType.ProtectedSource -> 3
        }

        fun EvidenceSourceType.priorityForPromptRender(): Int = when (this) {
            EvidenceSourceType.Memory -> 0
            EvidenceSourceType.DeviceContext -> 1
            else -> 2
        }

        fun EvidenceQualityLevel.priorityForPromptBudget(): Int = when (this) {
            EvidenceQualityLevel.High -> 3
            EvidenceQualityLevel.Medium -> 2
            EvidenceQualityLevel.Unknown -> 1
            EvidenceQualityLevel.Low -> 0
        }

        fun MemoryHit.memoryEvidenceQualityLevel(): EvidenceQualityLevel = when {
            finalScore >= 0.70f -> EvidenceQualityLevel.High
            finalScore >= 0.25f -> EvidenceQualityLevel.Medium
            else -> EvidenceQualityLevel.Low
        }

        fun EvidenceCard.toPromptLine(prefix: String = "- "): String {
            val quality = when (this.quality.level) {
                EvidenceQualityLevel.High -> "高"
                EvidenceQualityLevel.Medium -> "中"
                EvidenceQualityLevel.Low -> "低"
                EvidenceQualityLevel.Unknown -> "未知"
            }
            val truncatedLabel = if (truncated) "，已截断" else ""
            return "$prefix[证据=${sourceType.name}，质量=$quality$truncatedLabel] $text"
        }

        fun String.takeFirstEstimatedTokens(maxTokens: Int): String {
            if (maxTokens <= 0) return ""
            var usedTokens = 0
            val builder = StringBuilder()
            for (char in this) {
                val cost = 1
                if (usedTokens + cost > maxTokens) break
                builder.append(char)
                usedTokens += cost
            }
            return builder.toString().trimEnd()
        }
    }
}
