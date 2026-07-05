package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.evidence.EvidenceCard
import com.bytedance.zgx.solin.evidence.EvidenceQuality
import com.bytedance.zgx.solin.evidence.EvidenceQualityLevel
import com.bytedance.zgx.solin.evidence.EvidenceSourceType
import com.bytedance.zgx.solin.runtime.DEFAULT_CHAT_SYSTEM_INSTRUCTION
import com.bytedance.zgx.solin.runtime.estimateLocalRuntimeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Result of assembling the per-turn system prompt: the base instruction plus evidence cards
 * contributed by registered [SystemContextContributor]s.
 *
 * @property baseInstruction The base system instruction (defaults to [DEFAULT_CHAT_SYSTEM_INSTRUCTION]).
 * @property evidenceCards Evidence cards collected from contributors (non-null results, each produced
 *           inside a runCatching block so a throwing contributor cannot crash the run).
 * @property contributorAttributions Optional list of contributor identifiers/descriptions for telemetry.
 */
data class SystemPrompt(
    val baseInstruction: String,
    val evidenceCards: List<EvidenceCard>,
    val contributorAttributions: List<String> = emptyList(),
)

/**
 * Builds the per-turn [SystemPrompt] by invoking each registered [SystemContextContributor] and
 * collecting non-null [EvidenceCard] results. Contributors are isolated via [runCatching] so that a
 * misbehaving/throwing contributor cannot crash an agent run.
 *
 * This is the Wave 2 seam that replaces inline memory/device context assembly in [AgentLoopRuntime].
 * Future waves will migrate the built-in memory and device-context providers to proper
 * [SystemContextContributor] implementations; for the initial wave the contributor list is empty.
 */
class SystemPromptBuilder(
    private val defaultSystemInstruction: String = DEFAULT_CHAT_SYSTEM_INSTRUCTION,
    private val contributors: List<SystemContextContributor> = emptyList(),
    private val includeDeviceControlSurvivalRules: Boolean = false,
) {
    /**
     * Compute the [SystemPrompt] for a given [userInput] and [run]. Suspends so that contributors may
     * call suspend APIs (e.g., disk/network I/O). Each contributor is wrapped in [runCatching] to
     * isolate failures.
     */
    suspend fun buildSystemPrompt(
        userInput: String,
        run: AgentRun,
    ): SystemPrompt {
        val collected = mutableListOf<EvidenceCard>()
        val attributions = mutableListOf<String>()
        contributors.forEach { contributor ->
            val result = runCatching { contributor.contribute(userInput, run) }
            result.onFailure {
                // Ignore contributor failures per spec (do not crash the run).
                // Future waves may want to log/telemetry this failure.
            }
            val card = result.getOrNull()
            if (card != null) {
                collected += card
                attributions += contributor.sourceType.name
            }
        }
        if (includeDeviceControlSurvivalRules) {
            val survivalCard = EvidenceCard(
                id = "agent-survival-rules",
                sourceType = EvidenceSourceType.UserPrompt,
                privacy = MessagePrivacy.LocalOnly,
                requiresLocalModel = true,
                text = AgentSurvivalRules.SYSTEM_PROMPT_RULES,
                quality = EvidenceQuality(EvidenceQualityLevel.High),
                tokenEstimate = estimateLocalRuntimeTokens(AgentSurvivalRules.SYSTEM_PROMPT_RULES),
            )
            collected.add(0, survivalCard)
            attributions.add(0, "SurvivalRules")
        }
        return SystemPrompt(
            baseInstruction = defaultSystemInstruction,
            evidenceCards = collected,
            contributorAttributions = attributions,
        )
    }

    /**
     * Convenience non-suspend wrapper for call sites (e.g., [AgentLoopRuntime.promptWithContextIfUseful])
     * that run on synchronous/blocking threads.
     *
     * Optimisation: when the contributor list is empty (the common case in the current
     * codebase — contributors are reserved for future waves), we can build the result
     * directly without [runBlocking]. This avoids blocking a coroutine dispatcher thread
     * when this method is invoked from within a `viewModelScope.launch` chain (e.g.
     * [ContextAssembler.assembleAnswerPrompt] called from [AgentLoopRuntime.runOnce]).
     * `runBlocking` inside a coroutine is a known anti-pattern that can starve the
     * dispatcher; this fast path eliminates it for the no-contributors case.
     */
    fun buildSystemPromptBlocking(
        userInput: String,
        run: AgentRun,
    ): SystemPrompt {
        if (contributors.isEmpty()) {
            // Fast path: no contributors means no suspend calls needed.
            // Duplicates the survival-rules logic from buildSystemPrompt but avoids
            // runBlocking entirely — safe because contributors is empty.
            val cards = if (includeDeviceControlSurvivalRules) {
                listOf(
                    EvidenceCard(
                        id = "agent-survival-rules",
                        sourceType = EvidenceSourceType.UserPrompt,
                        privacy = MessagePrivacy.LocalOnly,
                        requiresLocalModel = true,
                        text = AgentSurvivalRules.SYSTEM_PROMPT_RULES,
                        quality = EvidenceQuality(EvidenceQualityLevel.High),
                        tokenEstimate = estimateLocalRuntimeTokens(AgentSurvivalRules.SYSTEM_PROMPT_RULES),
                    ),
                )
            } else {
                emptyList()
            }
            val attributions = if (includeDeviceControlSurvivalRules) {
                listOf("SurvivalRules")
            } else {
                emptyList()
            }
            return SystemPrompt(
                baseInstruction = defaultSystemInstruction,
                evidenceCards = cards,
                contributorAttributions = attributions,
            )
        }
        // Slow path: contributors may use suspend APIs; fall back to runBlocking.
        return runBlocking(Dispatchers.Default) {
            buildSystemPrompt(userInput, run)
        }
    }
}
