package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.ModelToolOutputParseResult
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `note` writes go to the per-run scratchpad and are read back into continuation prompts — but only
 * prompts pinned to the LOCAL model. Notes can be derived from screen text or private tool results,
 * so any continuation that may be routed to a remote model must not carry them.
 */
class ScratchpadContinuationPrivacyTest {
    private val noteText = "笔记：订单号 PRIVATE-NOTE-4821"
    private val actionRuntime = ScriptedActionRuntime()
    private var nextReplan: (() -> AgentObservationReplan)? = null
    private val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
    private val runtime = AgentLoopRuntime(
        memoryIndex = MemoryRepository(),
        actionPlanningRuntime = actionRuntime,
        traceStore = traceStore,
        observationReplanner = AgentObservationReplanner { nextReplan?.invoke() },
    )

    /** Starts a run, has the model emit `note`, and observes the note result. */
    private fun runWithObservedNote(): String {
        val started = runtime.runOnce(
            input = "看看订单状态",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            options = AgentRunOptions(remoteGuiDrivingEnabled = true),
        )
        actionRuntime.modelOutputResult = ModelToolOutputParseResult.Parsed(
            ActionDraft(
                functionName = MobileActionFunctions.NOTE,
                title = "记录笔记",
                summary = "记录笔记",
                parameters = mapOf("content" to noteText),
                requiresConfirmation = false,
            ),
        )
        val planned = runtime.observeModelResult(started.run.id, """call:note{"content":"$noteText"}""")
        val noteRequest = (planned?.decision as AgentObservationDecision.PlanNextTool).plan.request
        assertEquals(AgentRunState.ExecutingTool, planned.run.state)
        return started.run.id.also { runId ->
            runtime.observeToolResult(
                runId,
                ToolResult(
                    requestId = noteRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已记录笔记 #1",
                    data = mapOf(
                        "toolName" to MobileActionFunctions.NOTE,
                        "content" to noteText,
                        "noteIndex" to "1",
                        "privacy" to MessagePrivacy.LocalOnly.name,
                        "requiresLocalModel" to "true",
                    ),
                ),
            )
        }
    }

    private fun replanTo(toolName: String, arguments: Map<String, String> = emptyMap()) {
        nextReplan = {
            nextReplan = null
            AgentObservationReplan(
                request = ToolRequest(toolName = toolName, arguments = arguments, reason = "next"),
                draft = ActionDraft(
                    functionName = toolName,
                    title = toolName,
                    summary = toolName,
                    parameters = arguments,
                    requiresConfirmation = true,
                ),
                fallbackReason = "test replan",
            )
        }
    }

    @Test
    fun localOnlyContinuationIncludesScratchpadNotes() {
        replanTo(MobileActionFunctions.READ_CLIPBOARD)
        val runId = runWithObservedNote()
        val pending = traceStore.steps(runId).filterIsInstance<AgentStep.ToolRequested>().last().request
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, pending.toolName)
        runtime.confirmToolRequest(runId, pending.id)

        val observed = runtime.observeToolResult(
            runId,
            ToolResult(
                requestId = pending.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf(
                    "toolName" to MobileActionFunctions.READ_CLIPBOARD,
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to "true",
                    "text" to "剪贴板内容",
                    "truncated" to "false",
                ),
            ),
        )

        requireNotNull(observed)
        assertTrue(observed.continuationRequiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains(noteText))
    }

    @Test
    fun remoteEligibleContinuationNeverIncludesScratchpadNotes() {
        replanTo(MobileActionFunctions.WEB_SEARCH, mapOf("query" to "订单状态"))
        val runId = runWithObservedNote()
        val search = traceStore.steps(runId).filterIsInstance<AgentStep.ToolRequested>().last().request
        assertEquals(MobileActionFunctions.WEB_SEARCH, search.toolName)
        // A network step after the run read a private note waits for the user.
        assertEquals(AgentRunState.AwaitingUserConfirmation, traceStore.run(runId)?.state)
        runtime.confirmToolRequest(runId, search.id)
        assertEquals(AgentRunState.ExecutingTool, traceStore.run(runId)?.state)

        val observed = runtime.observeToolResult(
            runId,
            ToolResult(
                requestId = search.id,
                status = ToolStatus.Succeeded,
                summary = "已搜索订单状态",
                data = publicWebSearchResultData(query = "订单状态"),
            ),
        )

        requireNotNull(observed)
        assertFalse(observed.continuationRequiresLocalModel)
        val prompt = observed.continuationPromptForModel.orEmpty()
        assertTrue("public evidence continuation should exist", prompt.isNotBlank())
        assertFalse(prompt.contains(noteText))
        assertFalse(prompt.contains("PRIVATE-NOTE"))
    }
}
