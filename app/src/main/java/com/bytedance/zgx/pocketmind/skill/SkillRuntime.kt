package com.bytedance.zgx.pocketmind.skill

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.tool.ToolRequest

interface SkillRuntime {
    fun manifests(): List<SkillManifest>
    fun plan(input: String): SkillPlan? = null
    fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan?
}
