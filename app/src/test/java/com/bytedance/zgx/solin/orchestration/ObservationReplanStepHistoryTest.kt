package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolError
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationReplanStepHistoryTest {
    private val observe = ToolRequest(id = "r-observe", toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
    private val failedTap = ToolRequest(
        id = "r-tap",
        toolName = MobileActionFunctions.UI_TAP,
        arguments = mapOf("target" to "search-entry"),
    )
    private val stuckScroll = ToolRequest(
        id = "r-scroll",
        toolName = MobileActionFunctions.UI_SCROLL,
        arguments = mapOf("direction" to "down"),
    )
    private val latestObserve = ToolRequest(id = "r-observe-2", toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN)

    @Test
    fun outcomeTokensAreValueFreeAndFailClosed() {
        assertEquals("ok", result(ToolStatus.Succeeded).replanStepOutcomeToken())
        assertEquals(
            "no_change",
            result(ToolStatus.Succeeded, data = mapOf("uiActionOutcome" to "no_change")).replanStepOutcomeToken(),
        )
        assertEquals(
            "failed:wrong_surface",
            result(ToolStatus.Succeeded, data = mapOf("uiActionOutcome" to "wrong_surface")).replanStepOutcomeToken(),
        )
        assertEquals(
            "failed:node_not_found",
            result(ToolStatus.Failed, data = mapOf("failureKind" to "node_not_found")).replanStepOutcomeToken(),
        )
        assertEquals(
            "failed:ExecutionFailed",
            result(ToolStatus.Failed, error = ToolError(ToolErrorCode.ExecutionFailed, "raw message 13800138000"))
                .replanStepOutcomeToken(),
        )
        assertEquals(
            "failed:notakind",
            result(ToolStatus.Failed, data = mapOf("failureKind" to "not a kind!")).replanStepOutcomeToken(),
        )
        assertEquals("failed:error", result(ToolStatus.Failed).replanStepOutcomeToken())
        assertEquals("failed:rejected", result(ToolStatus.Rejected).replanStepOutcomeToken())
        assertEquals("failed:cancelled", result(ToolStatus.Cancelled).replanStepOutcomeToken())
    }

    @Test
    fun traceOutcomesUseLatestObservationPerRequest() {
        val outcomes = listOf(
            AgentStep.ToolObserved(result(ToolStatus.Succeeded, requestId = observe.id)),
            AgentStep.ToolObserved(
                result(ToolStatus.Failed, requestId = failedTap.id, data = mapOf("failureKind" to "node_not_found")),
            ),
            AgentStep.ToolRejected(result(ToolStatus.Rejected, requestId = "r-rejected")),
            AgentStep.ToolObserved(
                result(ToolStatus.Succeeded, requestId = stuckScroll.id, data = mapOf("uiActionOutcome" to "no_change")),
            ),
        ).replanStepOutcomesByRequestId()

        assertEquals(
            mapOf(
                observe.id to "ok",
                failedTap.id to "failed:node_not_found",
                "r-rejected" to "failed:rejected",
                stuckScroll.id to "no_change",
            ),
            outcomes,
        )
    }

    @Test
    fun localPromptSnapshotListsPerStepOutcomesAndRunNotes() {
        val prompt = context(
            notes = listOf("搜索结果第一条是 海河牛奶 220ml", "价格 ¥4.5"),
        ).observationModelPrompt(ToolRegistry())

        val priorLine = prompt.lines().single { line -> line.startsWith("Prior request details:") }
        assertEquals(
            "Prior request details: observe_current_screen[ok] -> ui_tap{target=search-entry}[failed:node_not_found] -> " +
                "ui_scroll{direction=down}[no_change] -> observe_current_screen[ok]",
            priorLine,
        )
        val notesLine = prompt.lines().single { line -> line.startsWith(RUN_NOTES_PROMPT_LABEL) }
        assertEquals("Run notes (LocalOnly): 搜索结果第一条是 海河牛奶 220ml | 价格 ¥4.5", notesLine)
        assertTrue(prompt.contains("If the latest action failed, avoid the same target"))
    }

    @Test
    fun promptOmitsNotesLineWhenThereAreNoNotesAndUnknownOutcomes() {
        val prompt = context(notes = emptyList(), outcomes = emptyMap()).observationModelPrompt(ToolRegistry())

        val priorLine = prompt.lines().single { line -> line.startsWith("Prior request details:") }
        assertEquals(
            "Prior request details: observe_current_screen -> ui_tap{target=search-entry} -> " +
                "ui_scroll{direction=down} -> observe_current_screen[ok]",
            priorLine,
        )
        assertFalse(prompt.contains(RUN_NOTES_PROMPT_LABEL))
    }

    @Test
    fun runNotesStayWithinTheirCharacterCapKeepingNewestNotes() {
        val notes = List(12) { index -> "note-$index " + "x".repeat(60) }

        val rendered = context(notes = notes).localScratchpadNotesPrompt().orEmpty()

        assertTrue(rendered.length <= MAX_SCRATCHPAD_NOTES_PROMPT_CHARS)
        assertTrue(rendered, rendered.contains("note-11"))
        assertFalse(rendered.contains("note-0 "))
        val prompt = context(notes = notes).observationModelPrompt(ToolRegistry())
        assertTrue(prompt.length <= 2_600)
    }

    @Test
    fun priorRequestDetailsStayWithinExistingCap() {
        val longTaps = List(6) { index ->
            ToolRequest(
                id = "tap-$index",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "target-$index-" + "y".repeat(90)),
            )
        }
        val ctx = context(notes = emptyList()).copy(
            priorRequests = longTaps,
            priorStepOutcomes = longTaps.associate { it.id to "failed:node_not_found" },
        )

        assertTrue(ctx.priorRequestDetailsPrompt().length <= MAX_PRIOR_REQUEST_DETAILS_CHARS)
    }

    @Test
    fun scratchpadNotesNeverReachNonLocalReplanners() {
        val seenByRemote = mutableListOf<List<String>>()
        val seenByLocal = mutableListOf<List<String>>()
        val remoteLike = AgentObservationReplanner { ctx ->
            seenByRemote += ctx.localOnlyScratchpadNotes
            null
        }
        val local = object : LocalOnlyNotesObservationReplanner {
            override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? {
                seenByLocal += context.localOnlyScratchpadNotes
                return null
            }
        }
        val ctx = context(notes = listOf("LocalOnly 屏幕摘录"))

        CompositeAgentObservationReplanner(remoteLike, local).planNext(ctx)

        assertEquals(listOf(emptyList<String>()), seenByRemote)
        assertEquals(listOf(listOf("LocalOnly 屏幕摘录")), seenByLocal)
        val remoteVision = RemoteVisionObservationReplanner(
            decider = { _, _ -> RemoteVisionDecision.Stop },
            gateProvider = { true },
        )
        assertTrue(ctx.scopedForReplanner(remoteVision).localOnlyScratchpadNotes.isEmpty())
        assertTrue(ctx.scopedForReplanner(remoteLike).localOnlyScratchpadNotes.isEmpty())
        assertEquals(ctx, ctx.scopedForReplanner(local))
    }

    @Test
    fun outcomesWithoutNotesAreNotStrippedForNonLocalReplanners() {
        val ctx = context(notes = emptyList())
        val scoped = ctx.scopedForReplanner(AgentObservationReplanner { null })
        assertEquals(ctx.priorStepOutcomes, scoped.priorStepOutcomes)
        assertNull(scoped.localScratchpadNotesPrompt())
    }

    private fun context(
        notes: List<String>,
        outcomes: Map<String, String> = mapOf(
            observe.id to "ok",
            failedTap.id to "failed:node_not_found",
            stuckScroll.id to "no_change",
        ),
    ): AgentObservationReplanContext =
        AgentObservationReplanContext(
            run = AgentRun(
                id = "run-history",
                input = "在淘宝搜索海河牛奶",
                state = AgentRunState.Observing,
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
            previousRequest = latestObserve,
            observedResult = result(ToolStatus.Succeeded, requestId = latestObserve.id),
            priorRequests = listOf(observe, failedTap, stuckScroll, latestObserve),
            priorStepOutcomes = outcomes,
            localOnlyScratchpadNotes = notes,
        )

    private fun result(
        status: ToolStatus,
        requestId: String = "r",
        data: Map<String, String> = emptyMap(),
        error: ToolError? = null,
    ): ToolResult = ToolResult(requestId = requestId, status = status, summary = "s", data = data, error = error)
}

class ScratchpadNotesFromPromptBlockTest {
    @Test
    fun recoversIndividualNotesFromTheScratchpadPromptBlock() {
        val pad = com.bytedance.zgx.solin.memory.PerRunScratchpad()
        pad.addNote("run", "第一条 搜索结果")
        pad.addNote("run", "第二条\n跨行")

        assertEquals(
            listOf("第一条 搜索结果", "第二条 跨行"),
            scratchpadNotesFromPromptBlock(pad.formatForPrompt("run")),
        )
        assertEquals(emptyList<String>(), scratchpadNotesFromPromptBlock(pad.formatForPrompt("other")))
    }
}
