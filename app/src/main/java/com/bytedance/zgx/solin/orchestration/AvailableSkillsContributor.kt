package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.evidence.EvidenceCard
import com.bytedance.zgx.solin.evidence.EvidenceQuality
import com.bytedance.zgx.solin.evidence.EvidenceQualityLevel
import com.bytedance.zgx.solin.evidence.EvidenceSourceType
import com.bytedance.zgx.solin.runtime.estimateLocalRuntimeTokens
import com.bytedance.zgx.solin.skill.SkillManifest

/**
 * Contributes an advisory `<available_skills>` catalog to the system prompt so the model knows
 * which built-in skills exist and roughly when each applies.
 *
 * Trust-boundary contract:
 * - This block is **advisory only**. Skill dispatch stays deterministic and rule-first in
 *   `BuiltInSkillRuntime.plan`; the model cannot redirect execution through this catalog. It only
 *   helps the model phrase/route a request toward an existing capability.
 * - Only non-authorizing display fields are exposed: skill `id`, `title`, and `whenToUse`. Input
 *   schemas, required tools, risk levels, and skill instructions are deliberately withheld, so the
 *   catalog can never widen what a skill is authorized to do.
 * - The card is `RemoteEligible` (`requiresLocalModel = false`): the catalog is static built-in
 *   capability text with no user content. It is kept out of remote prompts anyway;
 *   [ContextAssembler.remotePromptProjection] allowlists this card id and sends only raw input.
 */
class AvailableSkillsContributor(
    private val manifestsProvider: () -> List<SkillManifest>,
    private val maxSkills: Int = DEFAULT_MAX_ADVERTISED_SKILLS,
) : SystemContextContributor {

    override val sourceType: EvidenceSourceType = EvidenceSourceType.UserPrompt

    override suspend fun contribute(userInput: String, run: AgentRun): EvidenceCard? {
        val manifests = manifestsProvider().take(maxSkills.coerceAtLeast(0))
        if (manifests.isEmpty()) return null
        val text = renderCatalog(manifests)
        return EvidenceCard(
            id = "available-skills",
            sourceType = sourceType,
            // The catalog is a static list of built-in capability names/titles — not private user
            // data — so it is RemoteEligible. This also lets it survive ContextAssembler's
            // `!requiresLocalModel` contributor filter (a LocalOnly card would be dropped there and
            // never reach any model). It carries no user content, so exposing it to a remote model
            // does not cross the privacy boundary.
            privacy = MessagePrivacy.RemoteEligible,
            requiresLocalModel = false,
            text = text,
            quality = EvidenceQuality(EvidenceQualityLevel.High),
            tokenEstimate = estimateLocalRuntimeTokens(text),
        )
    }

    private fun renderCatalog(manifests: List<SkillManifest>): String {
        val rows = manifests.joinToString("\n") { manifest ->
            val hint = manifest.whenToUse.ifBlank { manifest.title }
            "- ${manifest.id}: ${manifest.title} — $hint"
        }
        return buildString {
            append("<available_skills>（仅供参考：实际派发由本地规则决定，你不能通过此列表改变执行路径）\n")
            append(rows)
            append("\n</available_skills>")
        }
    }

    companion object {
        const val DEFAULT_MAX_ADVERTISED_SKILLS = 40
    }
}
