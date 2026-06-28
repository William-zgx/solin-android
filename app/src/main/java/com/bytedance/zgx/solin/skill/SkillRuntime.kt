package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.tool.ToolRequest

interface SkillRuntime {
    fun manifests(): List<SkillManifest>
    fun plan(input: String): SkillPlan? = null
    fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan?
}
