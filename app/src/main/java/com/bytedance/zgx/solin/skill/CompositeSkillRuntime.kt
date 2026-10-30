package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.tool.ToolRequest

class CompositeSkillRuntime(
    sources: List<SkillSource>,
    private val planners: List<SkillRuntime>,
) : SkillRuntime {
    private val registeredManifests = sources
        .flatMap { source -> source.manifests() }
        .toList()
    private val manifestsById = registeredManifests.associateBy { manifest -> manifest.id }

    init {
        require(manifestsById.size == registeredManifests.size) {
            "Duplicate skill manifest id(s) are not allowed."
        }
        val plannerManifestIds = planners
            .flatMap { planner -> planner.manifests() }
            .map { manifest -> manifest.id }
            .toSet()
        val unregisteredPlannerSkills = plannerManifestIds - manifestsById.keys
        require(unregisteredPlannerSkills.isEmpty()) {
            "Skill planner exposes unregistered manifest(s): ${unregisteredPlannerSkills.sorted().joinToString()}"
        }
    }

    override fun manifests(): List<SkillManifest> = registeredManifests

    override fun plan(input: String): SkillPlan? =
        planners.firstNotNullOfOrNull { planner -> planner.plan(input)?.validated() }

    override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? =
        planners.firstNotNullOfOrNull { planner -> planner.plan(input, draft, request)?.validated() }

    private fun SkillPlan.validated(): SkillPlan {
        val registeredManifest = manifestsById[request.skillId]
            ?: error("Skill planner returned unregistered skill: ${request.skillId}")
        require(manifest == registeredManifest) {
            "Skill planner returned a manifest that differs from the registered contract: ${request.skillId}"
        }
        return this
    }
}
