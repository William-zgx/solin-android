package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.action.ActionPlanKind
import com.bytedance.zgx.pocketmind.action.ActionPlanningRuntime
import com.bytedance.zgx.pocketmind.memory.MemoryHit
import com.bytedance.zgx.pocketmind.memory.MemoryIndex
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import com.bytedance.zgx.pocketmind.tool.ToolRequest

class AgentLoopRuntime(
    private val memoryIndex: MemoryIndex,
    private val actionPlanningRuntime: ActionPlanningRuntime,
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val traceStore: AgentTraceStore = InMemoryAgentTraceStore(),
) {
    @Suppress("UNUSED_PARAMETER")
    fun runOnce(
        input: String,
        installedCapabilities: Set<ModelCapability>,
        memoryEnabled: Boolean,
        actionModelPath: String? = null,
    ): AgentLoopResult {
        val createdRun = traceStore.createRun(input)
        traceStore.updateState(createdRun.id, AgentRunState.LoadingContext)

        val memoryHits = if (memoryEnabled) {
            memoryIndex.search(input, topK = 3)
        } else {
            emptyList()
        }
        traceStore.appendStep(createdRun.id, AgentStep.ContextLoaded(memoryHits))
        traceStore.updateState(createdRun.id, AgentRunState.Planning)

        when (val toolPlan = planToolIfSupported(input, actionModelPath)) {
            is AgentPlan.UseTool -> {
                traceStore.appendStep(createdRun.id, AgentStep.ModelPlanned(toolPlan))
                traceStore.appendStep(createdRun.id, AgentStep.ToolRequested(toolPlan.request, toolPlan.draft))
                traceStore.appendStep(
                    createdRun.id,
                    AgentStep.UserConfirmationRequested(toolPlan.request, toolPlan.draft),
                )
                val waitingRun = traceStore.updateState(createdRun.id, AgentRunState.AwaitingUserConfirmation)
                return AgentLoopResult(
                    run = waitingRun,
                    plan = toolPlan,
                    steps = traceStore.steps(createdRun.id),
                )
            }

            is AgentPlan.RejectedTool -> {
                traceStore.appendStep(createdRun.id, AgentStep.ModelPlanned(toolPlan))
                traceStore.appendStep(createdRun.id, AgentStep.ToolRejected(toolPlan.result))
                val failedRun = traceStore.updateState(createdRun.id, AgentRunState.Failed)
                return AgentLoopResult(
                    run = failedRun,
                    plan = toolPlan,
                    steps = traceStore.steps(createdRun.id),
                )
            }

            null -> Unit
            else -> Unit
        }

        val answerPlan = AgentPlan.Answer(
            promptForModel = promptWithMemoryIfUseful(input, memoryHits),
            memoryHits = memoryHits,
        )
        traceStore.appendStep(createdRun.id, AgentStep.ModelPlanned(answerPlan))
        val generatingRun = traceStore.updateState(createdRun.id, AgentRunState.GeneratingAnswer)
        return AgentLoopResult(
            run = generatingRun,
            plan = answerPlan,
            steps = traceStore.steps(createdRun.id),
        )
    }

    private fun planToolIfSupported(input: String, actionModelPath: String?): AgentPlan? {
        if (!actionPlanningRuntime.isLikelyAction(input)) return null
        val result = actionPlanningRuntime.plan(input, actionModelPath)
        val draft = result.plan.draft
        if (result.plan.kind != ActionPlanKind.Draft || draft == null) return null
        val request = ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = draft.summary,
        )
        val rejection = toolRegistry.validate(request)
        if (rejection != null) return AgentPlan.RejectedTool(rejection)
        return AgentPlan.UseTool(
            request = request,
            draft = draft,
            plannedByModel = result.usedModel,
            fallbackReason = result.fallbackReason,
        )
    }

    private fun promptWithMemoryIfUseful(input: String, memoryHits: List<MemoryHit>): String {
        if (memoryHits.isEmpty()) return input
        val context = memoryIndex.buildContext(memoryHits)
        return """
            请根据用户当前输入的语言回答。只有在以下本地记忆与当前问题明显相关时才使用；如果无关，请忽略，不要复述无关隐私内容。
            $context

            用户问题：$input
        """.trimIndent()
    }
}

fun AgentLoopResult.toAssistantRoute(): AssistantRoute =
    when (val planned = plan) {
        is AgentPlan.Answer -> AssistantRoute.Chat(
            promptForModel = planned.promptForModel,
            memoryHits = planned.memoryHits,
        )

        is AgentPlan.UseTool -> AssistantRoute.Action(
            runId = run.id,
            toolRequest = planned.request,
            draft = planned.draft,
            plannedByModel = planned.plannedByModel,
            fallbackReason = planned.fallbackReason,
        )

        is AgentPlan.RejectedTool -> AssistantRoute.ToolRejected(planned.result.summary)

        is AgentPlan.MissingModel -> AssistantRoute.MissingModel(planned.capability)
    }
