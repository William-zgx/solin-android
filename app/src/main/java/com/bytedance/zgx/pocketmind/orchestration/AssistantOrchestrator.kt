package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.ActionPlanKind
import com.bytedance.zgx.pocketmind.action.ActionPlanner
import com.bytedance.zgx.pocketmind.memory.MemoryHit
import com.bytedance.zgx.pocketmind.memory.MemoryRepository

sealed class AssistantRoute {
    data class Chat(
        val promptForModel: String,
        val memoryHits: List<MemoryHit>,
    ) : AssistantRoute()

    data class Action(
        val draft: ActionDraft,
    ) : AssistantRoute()

    data class MissingModel(
        val capability: ModelCapability,
    ) : AssistantRoute()
}

class AssistantOrchestrator(
    private val memoryRepository: MemoryRepository,
    private val actionPlanner: ActionPlanner,
) {
    fun route(
        input: String,
        installedCapabilities: Set<ModelCapability>,
        memoryEnabled: Boolean,
    ): AssistantRoute {
        if (actionPlanner.isLikelyAction(input)) {
            if (ModelCapability.MobileAction !in installedCapabilities) {
                return AssistantRoute.MissingModel(ModelCapability.MobileAction)
            }
            val actionPlan = actionPlanner.plan(input)
            if (actionPlan.kind == ActionPlanKind.Draft && actionPlan.draft != null) {
                return AssistantRoute.Action(actionPlan.draft)
            }
        }

        if (memoryEnabled && ModelCapability.MemoryEmbedding in installedCapabilities) {
            val hits = memoryRepository.search(input, topK = 3)
            if (hits.isNotEmpty()) {
                val context = memoryRepository.buildContext(hits)
                return AssistantRoute.Chat(
                    promptForModel = "请结合以下本地记忆回答用户问题；如果记忆无关，请忽略。\n$context\n\n用户问题：$input",
                    memoryHits = hits,
                )
            }
        }

        return AssistantRoute.Chat(
            promptForModel = input,
            memoryHits = emptyList(),
        )
    }
}
