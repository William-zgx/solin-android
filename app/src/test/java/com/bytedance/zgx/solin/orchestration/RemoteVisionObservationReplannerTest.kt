package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.UiScrollDirection
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVisionObservationReplannerTest {
    private fun context(
        priorRequests: List<ToolRequest> = emptyList(),
        previousArguments: Map<String, String> = emptyMap(),
        inheritedPackage: String? = null,
        status: ToolStatus = ToolStatus.Succeeded,
        runHasUserConfirmedStep: Boolean = true,
        previous: ToolRequest? = null,
        screenHistory: List<ScreenObservationFingerprint> = emptyList(),
    ): AgentObservationReplanContext {
        val previousRequest = previous ?: ToolRequest(
            id = "obs-1",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            arguments = previousArguments,
        )
        return AgentObservationReplanContext(
            run = AgentRun(
                id = "run-1",
                input = "帮我在小红书搜索并关注博主",
                state = AgentRunState.Observing,
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
            previousRequest = previousRequest,
            observedResult = ToolResult(
                requestId = previousRequest.id,
                status = status,
                summary = "已观察当前屏幕。",
                data = mapOf("toolName" to previousRequest.toolName),
            ),
            priorRequests = priorRequests + previousRequest,
            inheritedExpectedForegroundPackage = inheritedPackage,
            runHasUserConfirmedStep = runHasUserConfirmedStep,
            screenObservationHistory = screenHistory,
        )
    }

    private fun fingerprint(text: String, at: Long): ScreenObservationFingerprint =
        ScreenObservationFingerprint(
            packageName = "com.xingin.xhs",
            textSummary = text.hashCode().toString(),
            elementCount = 12,
            capturedAtMillis = at,
        )

    /** The request a previous remote-vision step produced (same reason the replanner stamps). */
    private fun previousRemoteVisionRequest(toolName: String, arguments: Map<String, String>): ToolRequest =
        ToolRequest(
            id = "prev-action",
            toolName = toolName,
            arguments = arguments,
            reason = MODEL_OBSERVATION_REPLAN_REQUEST_REASON,
        )

    private fun executingReplanner(
        decision: RemoteVisionDecision,
        logs: MutableList<String> = mutableListOf(),
    ): RemoteVisionObservationReplanner =
        RemoteVisionObservationReplanner(
            decider = { _, _ -> decision },
            gateProvider = { true },
            executeDecisions = true,
            logger = { logs += it },
        )

    // ── Back / scroll vocabulary ─────────────────────────────────────────────────────────────

    @Test
    fun backDecisionBecomesLocalPressBackWithoutPackageBinding() {
        val replan = executingReplanner(RemoteVisionDecision.Back).planNext(context(inheritedPackage = "com.xingin.xhs"))

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_PRESS_BACK, replan?.request?.toolName)
        // ui_press_back's schema has no package binding (additionalProperties=false).
        assertEquals(emptyMap<String, String>(), replan?.request?.arguments)
        assertEquals(MODEL_OBSERVATION_REPLAN_REQUEST_REASON, replan?.request?.reason)
        assertTrue(replan?.plannedByModel == true)
        assertEquals(MobileActionFunctions.UI_PRESS_BACK, replan?.draft?.functionName)
    }

    @Test
    fun scrollDecisionBecomesLocalScrollInheritingForegroundPackage() {
        val replan = executingReplanner(RemoteVisionDecision.Scroll(UiScrollDirection.Down))
            .planNext(context(inheritedPackage = "com.xingin.xhs"))

        assertEquals(MobileActionFunctions.UI_SCROLL, replan?.request?.toolName)
        assertEquals("down", replan?.request?.arguments?.get("direction"))
        assertEquals("com.xingin.xhs", replan?.request?.arguments?.get("expectedPackageName"))
        assertEquals(MODEL_OBSERVATION_REPLAN_REQUEST_REASON, replan?.request?.reason)
        assertEquals(replan?.request?.arguments, replan?.draft?.parameters)
    }

    @Test
    fun readOnlyModeDoesNotExecuteBackOrScroll() {
        listOf(RemoteVisionDecision.Back, RemoteVisionDecision.Scroll(UiScrollDirection.Up)).forEach { decision ->
            val logs = mutableListOf<String>()
            val replanner = RemoteVisionObservationReplanner(
                decider = { _, _ -> decision },
                gateProvider = { true },
                executeDecisions = false,
                logger = { logs += it },
            )
            assertNull(replanner.planNext(context()))
            assertTrue(logs.any { it.contains("read-only") })
        }
    }

    @Test
    fun backAndScrollCountAgainstTheSharedStepBudget() {
        // The 5-step checkpoint is one budget for the whole loop, whatever action the model picked.
        var deciderCalls = 0
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> deciderCalls++; RemoteVisionDecision.Back },
            gateProvider = { true },
            executeDecisions = true,
            maxReplans = 2,
            logger = {},
        )
        val prior = listOf(
            previousRemoteVisionRequest(MobileActionFunctions.UI_PRESS_BACK, emptyMap()),
            previousRemoteVisionRequest(MobileActionFunctions.UI_SCROLL, mapOf("direction" to "down")),
        )
        assertNull(replanner.planNext(context(priorRequests = prior)))
        assertEquals(0, deciderCalls)
    }

    // ── Local repeated-decision detection ────────────────────────────────────────────────────

    @Test
    fun repeatedTapOnUnchangedScreenFailsClosed() {
        val logs = mutableListOf<String>()
        val previous = previousRemoteVisionRequest(
            MobileActionFunctions.UI_TAP,
            mapOf("targetX" to "500", "targetY" to "250"),
        )
        val unchanged = listOf(fingerprint("feed", at = 1L), fingerprint("feed", at = 2L))

        val replan = executingReplanner(RemoteVisionDecision.Tap(505, 246), logs)
            .planNext(context(previous = previous, screenHistory = unchanged))

        assertNull("same tap (within tolerance) on an unchanged screen must stop", replan)
        assertTrue(logs.any { it.contains("repeated ui_tap on an unchanged screen") })
    }

    @Test
    fun repeatedTapIsAllowedWhenTheScreenChanged() {
        val previous = previousRemoteVisionRequest(
            MobileActionFunctions.UI_TAP,
            mapOf("targetX" to "500", "targetY" to "250"),
        )
        val changed = listOf(fingerprint("feed", at = 1L), fingerprint("detail page", at = 2L))

        val replan = executingReplanner(RemoteVisionDecision.Tap(500, 250))
            .planNext(context(previous = previous, screenHistory = changed))

        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
    }

    @Test
    fun differentTapOnUnchangedScreenIsAllowed() {
        val previous = previousRemoteVisionRequest(
            MobileActionFunctions.UI_TAP,
            mapOf("targetX" to "500", "targetY" to "250"),
        )
        val unchanged = listOf(fingerprint("feed", at = 1L), fingerprint("feed", at = 2L))

        val replan = executingReplanner(RemoteVisionDecision.Tap(500, 250 + REMOTE_VISION_SAME_TAP_TOLERANCE + 1))
            .planNext(context(previous = previous, screenHistory = unchanged))

        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
    }

    @Test
    fun repeatedScrollOrBackOnUnchangedScreenFailsClosed() {
        val unchanged = listOf(fingerprint("list end", at = 1L), fingerprint("list end", at = 2L))
        val scrollAgain = executingReplanner(RemoteVisionDecision.Scroll(UiScrollDirection.Down)).planNext(
            context(
                previous = previousRemoteVisionRequest(MobileActionFunctions.UI_SCROLL, mapOf("direction" to "down")),
                screenHistory = unchanged,
            ),
        )
        val backAgain = executingReplanner(RemoteVisionDecision.Back).planNext(
            context(
                previous = previousRemoteVisionRequest(MobileActionFunctions.UI_PRESS_BACK, emptyMap()),
                screenHistory = unchanged,
            ),
        )

        assertNull("scrolled to the end: another identical scroll cannot progress", scrollAgain)
        assertNull("back had no effect: pressing it again cannot progress", backAgain)
    }

    @Test
    fun oppositeScrollAfterNoProgressIsAllowed() {
        val unchanged = listOf(fingerprint("list end", at = 1L), fingerprint("list end", at = 2L))

        val replan = executingReplanner(RemoteVisionDecision.Scroll(UiScrollDirection.Up)).planNext(
            context(
                previous = previousRemoteVisionRequest(MobileActionFunctions.UI_SCROLL, mapOf("direction" to "down")),
                screenHistory = unchanged,
            ),
        )

        assertEquals("up", replan?.request?.arguments?.get("direction"))
    }

    @Test
    fun repeatedActionWithoutScreenEvidenceFailsClosed() {
        // No fingerprints to prove the screen moved → treat as no progress.
        val previous = previousRemoteVisionRequest(
            MobileActionFunctions.UI_TAP,
            mapOf("targetX" to "500", "targetY" to "250"),
        )

        val replan = executingReplanner(RemoteVisionDecision.Tap(500, 250))
            .planNext(context(previous = previous, screenHistory = listOf(fingerprint("feed", at = 1L))))

        assertNull(replan)
    }

    @Test
    fun firstActionAfterObserveIsNeverTreatedAsARepeat() {
        // The previous request is the (non-model) observe, so there is nothing to repeat even if the
        // screen fingerprints happen to be identical.
        val unchanged = listOf(fingerprint("feed", at = 1L), fingerprint("feed", at = 2L))

        val replan = executingReplanner(RemoteVisionDecision.Tap(500, 250))
            .planNext(context(screenHistory = unchanged))

        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
    }

    @Test
    fun decisionPromptCarriesNoStepHistory() {
        // Step history stays local: the prompt is built from the intent alone, with no slot for prior
        // steps, coordinates or screen text.
        val prompt = remoteVisionDecisionPrompt("在小红书搜索露营")

        assertTrue(prompt.contains("在小红书搜索露营"))
        assertTrue(prompt.contains("\"action\":\"back\""))
        assertTrue(prompt.contains("\"action\":\"scroll\""))
        listOf("历史", "上一步", "之前的操作", "history").forEach { marker ->
            assertTrue("prompt must not reference step history: $marker", !prompt.contains(marker))
        }
    }

    @Test
    fun parserReadsBackAndScroll() {
        assertEquals(RemoteVisionDecision.Back, parseRemoteVisionDecision("""{"action":"back"}"""))
        assertEquals(
            RemoteVisionDecision.Scroll(UiScrollDirection.Down),
            parseRemoteVisionDecision("""{"action":"scroll","direction":"down"}"""),
        )
        assertEquals(
            RemoteVisionDecision.Scroll(UiScrollDirection.Left),
            parseRemoteVisionDecision("""好的 {"action":"SCROLL","direction":" Left "}"""),
        )
    }

    @Test
    fun parserFailsClosedOnBadScrollDirection() {
        listOf(
            """{"action":"scroll"}""",
            """{"action":"scroll","direction":"sideways"}""",
            // forward/backward are local-only vocabulary; the remote model gets the 4 screen directions.
            """{"action":"scroll","direction":"forward"}""",
            """{"action":"scroll","direction":"backward"}""",
        ).forEach { raw ->
            assertEquals(
                raw,
                RemoteVisionDecision.Unavailable("scroll_invalid_direction"),
                parseRemoteVisionDecision(raw),
            )
        }
    }

    @Test
    fun returnsNullWhenGateOff() {
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> error("decider must not be called when gate is off") },
            gateProvider = { false },
            executeDecisions = true,
            logger = {},
        )
        assertNull(replanner.planNext(context()))
    }

    @Test
    fun failsClosedWhenRunNotYetUserConfirmed() {
        // First-confirm-then-auto: before the user has confirmed this run's first action, the
        // decider (which captures + sends the screenshot off-device) must never be called.
        var deciderCalls = 0
        val logs = mutableListOf<String>()
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> deciderCalls++; RemoteVisionDecision.Tap(1, 1) },
            gateProvider = { true },
            executeDecisions = true,
            logger = { logs += it },
        )
        assertNull(replanner.planNext(context(runHasUserConfirmedStep = false)))
        assertEquals(0, deciderCalls)
        assertTrue(logs.any { it.contains("not yet user-confirmed") })
    }

    @Test
    fun readOnlyModeLogsTapButDoesNotReplan() {
        val logs = mutableListOf<String>()
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> RemoteVisionDecision.Tap(normalizedX = 500, normalizedY = 250) },
            gateProvider = { true },
            executeDecisions = false,
            logger = { logs += it },
        )
        assertNull(replanner.planNext(context()))
        assertTrue(logs.any { it.contains("decision tap (500,250)") })
        assertTrue(logs.any { it.contains("read-only") })
    }

    @Test
    fun executeModeTurnsTapIntoLocalUiTapReplan() {
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> RemoteVisionDecision.Tap(normalizedX = 500, normalizedY = 250) },
            gateProvider = { true },
            executeDecisions = true,
            logger = {},
        )
        val replan = replanner.planNext(context())
        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
        assertEquals("500", replan?.request?.arguments?.get("targetX"))
        assertEquals("250", replan?.request?.arguments?.get("targetY"))
        assertTrue(replan?.plannedByModel == true)
        // Reason must match the shared model-observation replan reason so the per-run replan
        // counter counts remote-vision steps against the same budget.
        assertEquals(MODEL_OBSERVATION_REPLAN_REQUEST_REASON, replan?.request?.reason)
    }

    @Test
    fun tapInheritsExpectedForegroundPackageFromContext() {
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> RemoteVisionDecision.Tap(normalizedX = 100, normalizedY = 100) },
            gateProvider = { true },
            executeDecisions = true,
            logger = {},
        )
        val replan = replanner.planNext(context(inheritedPackage = "com.xingin.xhs"))
        assertEquals("com.xingin.xhs", replan?.request?.arguments?.get("expectedPackageName"))
    }

    @Test
    fun tapPrefersPreviousRequestExpectedPackageOverInherited() {
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> RemoteVisionDecision.Tap(normalizedX = 100, normalizedY = 100) },
            gateProvider = { true },
            executeDecisions = true,
            logger = {},
        )
        val replan = replanner.planNext(
            context(
                previousArguments = mapOf("expectedPackageName" to "com.from.request"),
                inheritedPackage = "com.from.context",
            ),
        )
        assertEquals("com.from.request", replan?.request?.arguments?.get("expectedPackageName"))
    }

    @Test
    fun stopDecisionReturnsNull() {
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> RemoteVisionDecision.Stop },
            gateProvider = { true },
            executeDecisions = true,
            logger = {},
        )
        assertNull(replanner.planNext(context()))
    }

    @Test
    fun unavailableDecisionFailsClosedToNull() {
        val logs = mutableListOf<String>()
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> RemoteVisionDecision.Unavailable("missing_consent") },
            gateProvider = { true },
            executeDecisions = true,
            logger = { logs += it },
        )
        assertNull(replanner.planNext(context()))
        assertTrue(logs.any { it.contains("unavailable (missing_consent)") })
    }

    @Test
    fun stopsWhenReplanLimitReached() {
        var deciderCalls = 0
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> deciderCalls++; RemoteVisionDecision.Tap(1, 1) },
            gateProvider = { true },
            executeDecisions = true,
            maxReplans = 2,
            logger = {},
        )
        // Two prior remote-vision replans already recorded → limit reached, decider not consulted.
        val priorReplans = List(2) {
            ToolRequest(toolName = MobileActionFunctions.UI_TAP, reason = MODEL_OBSERVATION_REPLAN_REQUEST_REASON)
        }
        assertNull(replanner.planNext(context(priorRequests = priorReplans)))
        assertEquals(0, deciderCalls)
    }

    @Test
    fun ignoresNonReplannableObservation() {
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _ -> error("decider must not be called for rejected observation") },
            gateProvider = { true },
            executeDecisions = true,
            logger = {},
        )
        assertNull(replanner.planNext(context(status = ToolStatus.Rejected)))
    }

    @Test
    fun parserReadsTapAndStop() {
        assertEquals(
            RemoteVisionDecision.Tap(500, 250),
            parseRemoteVisionDecision("""{"action":"tap","x":500,"y":250}"""),
        )
        assertEquals(RemoteVisionDecision.Stop, parseRemoteVisionDecision("""{"action":"stop"}"""))
    }

    @Test
    fun parserToleratesSurroundingProse() {
        assertEquals(
            RemoteVisionDecision.Tap(300, 400),
            parseRemoteVisionDecision("""好的，我的决策是：{"action":"tap","x":300,"y":400}。"""),
        )
    }

    @Test
    fun parserFailsClosedOnOutOfRangeOrUnparseable() {
        assertTrue(parseRemoteVisionDecision("""{"action":"tap","x":1500,"y":10}""") is RemoteVisionDecision.Unavailable)
        assertTrue(parseRemoteVisionDecision("""{"action":"tap","x":-5,"y":10}""") is RemoteVisionDecision.Unavailable)
        assertTrue(parseRemoteVisionDecision("""{"action":"tap","y":10}""") is RemoteVisionDecision.Unavailable)
        assertTrue(parseRemoteVisionDecision("no json here") is RemoteVisionDecision.Unavailable)
        assertTrue(parseRemoteVisionDecision("""{"action":"frobnicate"}""") is RemoteVisionDecision.Unavailable)
    }

    @Test
    fun parserReadsStringCoordinates() {
        assertEquals(
            RemoteVisionDecision.Tap(500, 250),
            parseRemoteVisionDecision("""{"action":"tap","x":"500","y":"250"}"""),
        )
    }
}
