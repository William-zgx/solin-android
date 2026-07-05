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
     * that run on synchronous/blocking threads. Runs [buildSystemPrompt] inside [runBlocking] on
     * [Dispatchers.Default] to avoid calling blocking I/O on the main thread.
     */
    fun buildSystemPromptBlocking(
        userInput: String,
        run: AgentRun,
    ): SystemPrompt =
        runBlocking(Dispatchers.Default) {
            buildSystemPrompt(userInput, run)
        }
}
