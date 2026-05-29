package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.ActionPlanKind
import com.bytedance.zgx.pocketmind.action.ActionPlanningRuntime
import com.bytedance.zgx.pocketmind.memory.MemoryHit
import com.bytedance.zgx.pocketmind.memory.MemoryIndex

sealed class AssistantRoute {
    data class Chat(
        val promptForModel: String,
        val memoryHits: List<MemoryHit>,
    ) : AssistantRoute()

    data class Action(
        val draft: ActionDraft,
        val plannedByModel: Boolean,
        val fallbackReason: String?,
    ) : AssistantRoute()

    data class MissingModel(
        val capability: ModelCapability,
    ) : AssistantRoute()
}

class AssistantOrchestrator(
    private val memoryIndex: MemoryIndex,
    private val actionPlanningRuntime: ActionPlanningRuntime,
) : AutoCloseable {
    @Suppress("UNUSED_PARAMETER")
    fun route(
        input: String,
        installedCapabilities: Set<ModelCapability>,
        memoryEnabled: Boolean,
        actionModelPath: String? = null,
    ): AssistantRoute {
        if (actionPlanningRuntime.isLikelyAction(input)) {
            val result = actionPlanningRuntime.plan(input, actionModelPath)
            if (result.plan.kind == ActionPlanKind.Draft && result.plan.draft != null) {
                return AssistantRoute.Action(
                    draft = result.plan.draft,
                    plannedByModel = result.usedModel,
                    fallbackReason = result.fallbackReason,
                )
            }
        }

        if (memoryEnabled) {
            val hits = memoryIndex.search(input, topK = 3)
            if (hits.isNotEmpty()) {
                val context = memoryIndex.buildContext(hits)
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

    override fun close() {
        (actionPlanningRuntime as? AutoCloseable)?.close()
    }
}
