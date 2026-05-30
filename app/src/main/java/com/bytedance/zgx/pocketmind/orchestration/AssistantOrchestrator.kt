package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.ActionPlanningRuntime
import com.bytedance.zgx.pocketmind.memory.MemoryHit
import com.bytedance.zgx.pocketmind.memory.MemoryIndex
import com.bytedance.zgx.pocketmind.tool.ToolRequest

sealed class AssistantRoute {
    data class Chat(
        val promptForModel: String,
        val memoryHits: List<MemoryHit>,
    ) : AssistantRoute()

    data class Action(
        val draft: ActionDraft,
        val plannedByModel: Boolean,
        val fallbackReason: String?,
        val runId: String? = null,
        val toolRequest: ToolRequest? = null,
    ) : AssistantRoute()

    data class ToolRejected(
        val summary: String,
    ) : AssistantRoute()

    data class MissingModel(
        val capability: ModelCapability,
    ) : AssistantRoute()
}

class AssistantOrchestrator(
    private val memoryIndex: MemoryIndex,
    private val actionPlanningRuntime: ActionPlanningRuntime,
) : AutoCloseable {
    private val agentLoopRuntime = AgentLoopRuntime(
        memoryIndex = memoryIndex,
        actionPlanningRuntime = actionPlanningRuntime,
    )

    fun route(
        input: String,
        installedCapabilities: Set<ModelCapability>,
        memoryEnabled: Boolean,
        actionModelPath: String? = null,
    ): AssistantRoute =
        agentLoopRuntime.runOnce(
            input = input,
            installedCapabilities = installedCapabilities,
            memoryEnabled = memoryEnabled,
            actionModelPath = actionModelPath,
        ).toAssistantRoute()

    override fun close() {
        (actionPlanningRuntime as? AutoCloseable)?.close()
    }
}
