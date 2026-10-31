package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.ScreenBounds
import com.bytedance.zgx.solin.device.ScreenNode
import com.bytedance.zgx.solin.device.ScreenStateSnapshot
import com.bytedance.zgx.solin.device.UiScrollDirection
import com.bytedance.zgx.solin.device.sampledPixelsLookBlank
import com.bytedance.zgx.solin.device.toScreenObservationJsonString
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
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

    private fun plannerWith(decider: () -> RemoteVisionDecision): RemoteVisionObservationReplanner =
        RemoteVisionObservationReplanner(
            decider = { _, _, _ -> decider() },
            gateProvider = { true },
            executeDecisions = true,
        )

    private fun executingReplanner(
        decision: RemoteVisionDecision,
        logs: MutableList<String> = mutableListOf(),
    ): RemoteVisionObservationReplanner =
        RemoteVisionObservationReplanner(
            decider = { _, _, _ -> decision },
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
                decider = { _, _, _ -> decision },
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
            decider = { _, _, _ -> deciderCalls++; RemoteVisionDecision.Back },
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

        assertFinishes("same tap (within tolerance) on an unchanged screen must stop", replan)
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

        assertFinishes("scrolled to the end: another identical scroll cannot progress", scrollAgain)
        assertFinishes("back had no effect: pressing it again cannot progress", backAgain)
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

        assertFinishes("no screen evidence", replan)
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
    fun decisionPromptCarriesTheControlListAndOwnActionsOnlyWhenGiven() {
        val bare = remoteVisionDecisionPrompt("在小红书搜索露营")
        assertTrue(bare.contains("在小红书搜索露营"))
        assertTrue(bare.contains("\"action\":\"back\""))
        assertTrue(bare.contains("\"action\":\"scroll\""))
        assertTrue(!bare.contains("本轮已执行的操作") && !bare.contains("屏幕控件"))

        val full = remoteVisionDecisionPrompt("在小红书搜索露营", "输入框 搜索 (500,80)", listOf("tap(500,80)", "type"))
        assertTrue(full.contains("输入框 搜索 (500,80)"))
        assertTrue(full.contains("本轮已执行的操作：tap(500,80)，type"))
    }

    @Test
    fun priorStepsListOwnActionsWithoutTypedTextAndMarkAFailedLastStep() {
        val tap = previousRemoteVisionRequest(MobileActionFunctions.UI_TAP, mapOf("targetX" to "500", "targetY" to "80"))
            .copy(id = "earlier-tap")
        val typed = previousRemoteVisionRequest(MobileActionFunctions.UI_TYPE_TEXT, mapOf("text" to "我的私密文字"))
        val failed = context(
            priorRequests = listOf(ToolRequest(toolName = MobileActionFunctions.OPEN_APP_BY_NAME), tap),
            previous = typed,
            status = ToolStatus.Failed,
        )

        assertEquals(listOf("tap(500,80)", "type 失败"), failed.remoteVisionPriorSteps())
    }

    @Test
    fun planRunsFollowUpsWithoutAnotherRemoteCallWhileStepsSucceed() {
        var calls = 0
        val replanner = plannerWith(
            decider = {
                calls++
                RemoteVisionDecision.Plan(
                    listOf(RemoteVisionDecision.Tap(500, 80), RemoteVisionDecision.Type("牛奶"), RemoteVisionDecision.SubmitSearch),
                )
            },
        )

        val tap = replanner.planNext(context())!!.request
        assertEquals(MobileActionFunctions.UI_TAP, tap.toolName)
        val typed = replanner.planNext(context(previous = tap))!!.request
        assertEquals(MobileActionFunctions.UI_TYPE_TEXT, typed.toolName)
        assertEquals("牛奶", typed.arguments["text"])
        assertEquals(null, typed.arguments["target"])
        val submit = replanner.planNext(context(priorRequests = listOf(tap), previous = typed))!!.request
        assertEquals(MobileActionFunctions.UI_SUBMIT_SEARCH, submit.toolName)
        assertEquals(1, calls)
    }

    @Test
    fun failedPlanStepDropsTheQueueAndAsksTheModelAgain() {
        var calls = 0
        val replanner = plannerWith(
            decider = {
                calls++
                if (calls == 1) {
                    RemoteVisionDecision.Plan(listOf(RemoteVisionDecision.Tap(500, 80), RemoteVisionDecision.Type("牛奶")))
                } else {
                    RemoteVisionDecision.Tap(100, 100)
                }
            },
        )
        val tap = replanner.planNext(context())!!.request
        val failedContext = context(previous = tap).let { ctx ->
            ctx.copy(
                observedResult = ctx.observedResult.copy(
                    status = ToolStatus.Failed,
                    retryable = true,
                    data = mapOf("screenObservationDiffSummary" to "unchanged"),
                ),
            )
        }

        assertEquals(MobileActionFunctions.UI_TAP, replanner.planNext(failedContext)!!.request.toolName)
        assertEquals(2, calls)
    }

    @Test
    fun queuedStepsDoNotLeakIntoAnotherRun() {
        var calls = 0
        val replanner = plannerWith(
            decider = {
                calls++
                RemoteVisionDecision.Plan(listOf(RemoteVisionDecision.Tap(100 * calls, 80), RemoteVisionDecision.SubmitSearch))
            },
        )
        val tap = replanner.planNext(context())!!.request
        val otherRun = context(previous = tap).let { ctx ->
            ctx.copy(run = ctx.run.copy(id = "another-run"))
        }

        assertEquals(MobileActionFunctions.UI_TAP, replanner.planNext(otherRun)!!.request.toolName)
        assertEquals(2, calls)
    }

    @Test
    fun plannedStepsPassTheToolSchemas() {
        val replanner = plannerWith(
            decider = {
                RemoteVisionDecision.Plan(
                    listOf(RemoteVisionDecision.Wait, RemoteVisionDecision.Type("牛奶"), RemoteVisionDecision.SubmitSearch),
                )
            },
        )
        var previous: ToolRequest? = null
        val seen = mutableListOf<ToolRequest>()
        repeat(3) {
            val ctx = previous?.let { context(priorRequests = seen.dropLast(1), previous = it) } ?: context()
            val request = replanner.planNext(ctx)!!.request
            assertNull(request.toolName, defaultBuiltInToolRegistry.validate(request))
            seen += request
            previous = request
        }
    }

    @Test
    fun parserReadsPlansAndKeepsOnlyCoordinateFreeFollowUps() {
        assertEquals(
            RemoteVisionDecision.Plan(listOf(RemoteVisionDecision.Tap(500, 80), RemoteVisionDecision.Type("牛奶"), RemoteVisionDecision.SubmitSearch)),
            parseRemoteVisionDecision("""{"steps":[{"action":"tap","x":500,"y":80},{"action":"type","text":"牛奶"},{"action":"submit"}]}"""),
        )
        // A second tap would use a coordinate from a screen that no longer exists: the plan ends there.
        assertEquals(
            RemoteVisionDecision.Tap(500, 80),
            parseRemoteVisionDecision("""{"steps":[{"action":"tap","x":500,"y":80},{"action":"tap","x":1,"y":1},{"action":"submit"}]}"""),
        )
        assertEquals(
            RemoteVisionDecision.Plan(listOf(RemoteVisionDecision.Tap(1, 1), RemoteVisionDecision.Wait)),
            parseRemoteVisionDecision("""{"steps":[{"action":"tap","x":1,"y":1},{"action":"wait"},{"action":"bogus"},{"action":"submit"}]}"""),
        )
        assertEquals(RemoteVisionDecision.Stop, parseRemoteVisionDecision("""{"steps":[{"action":"stop"},{"action":"submit"}]}"""))
        assertEquals(RemoteVisionDecision.Unavailable("plan_empty"), parseRemoteVisionDecision("""{"steps":[]}"""))
        assertEquals(RemoteVisionDecision.Unavailable("type_missing_text"), parseRemoteVisionDecision("""{"action":"type","text":" "}"""))
    }

    @Test
    fun screenTreeListsActionableNodesFirstWithTapCoordinatesAndRespectsTheCap() {
        fun node(text: String, clickable: Boolean, editable: Boolean = false) = ScreenNode(
            id = text,
            text = text,
            contentDescription = "",
            className = "",
            bounds = ScreenBounds(0, 0, 500, 200),
            clickable = clickable,
            editable = editable,
            scrollable = false,
            enabled = true,
        )
        val snapshot = ScreenStateSnapshot(
            id = "s",
            packageName = "p",
            capturedAtMillis = 1L,
            nodes = listOf(node("标题", clickable = false), node("搜索", clickable = true), node("关键词", clickable = false, editable = true)),
            textSummary = "",
            truncated = false,
            widthPx = 1000,
            heightPx = 2000,
        )

        assertEquals("按钮 搜索 (250,50)\n输入框 关键词 (250,50)\n文字 标题 (250,50)", snapshot.toRemoteVisionScreenTree())
        assertEquals("按钮 搜索 (250,50)", snapshot.toRemoteVisionScreenTree(maxChars = 20))
        assertEquals(null, snapshot.copy(nodes = emptyList()).toRemoteVisionScreenTree())
    }

    @Test
    fun blankSamplesAreRefusedButDarkScreensWithContentAreNot() {
        assertTrue(sampledPixelsLookBlank(IntArray(16) { 0xFF000000.toInt() }))
        assertTrue(!sampledPixelsLookBlank(IntArray(16) { i -> if (i == 3) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }))
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
            decider = { _, _, _ -> error("decider must not be called when gate is off") },
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
            decider = { _, _, _ -> deciderCalls++; RemoteVisionDecision.Tap(1, 1) },
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
            decider = { _, _, _ -> RemoteVisionDecision.Tap(normalizedX = 500, normalizedY = 250) },
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
            decider = { _, _, _ -> RemoteVisionDecision.Tap(normalizedX = 500, normalizedY = 250) },
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
            decider = { _, _, _ -> RemoteVisionDecision.Tap(normalizedX = 100, normalizedY = 100) },
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
            decider = { _, _, _ -> RemoteVisionDecision.Tap(normalizedX = 100, normalizedY = 100) },
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
    fun stopDecisionFinishesTheRun() {
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _, _ -> RemoteVisionDecision.Stop },
            gateProvider = { true },
            executeDecisions = true,
            logger = {},
        )
        val replan = replanner.planNext(context())
        assertEquals(MobileActionFunctions.FINISH, replan?.request?.toolName)
        assertTrue(replan?.request?.arguments?.get("message").orEmpty().isNotBlank())
    }

    @Test
    fun stopDecisionInReadOnlyModeReturnsNull() {
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _, _ -> RemoteVisionDecision.Stop },
            gateProvider = { true },
            executeDecisions = false,
            logger = {},
        )
        assertNull(replanner.planNext(context()))
    }

    @Test
    fun unavailableDecisionFailsClosedToNull() {
        val logs = mutableListOf<String>()
        val replanner = RemoteVisionObservationReplanner(
            decider = { _, _, _ -> RemoteVisionDecision.Unavailable("missing_consent") },
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
            decider = { _, _, _ -> deciderCalls++; RemoteVisionDecision.Tap(1, 1) },
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
            decider = { _, _, _ -> error("decider must not be called for rejected observation") },
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

    // ── until: local wait conditions ─────────────────────────────────────────────────────────

    @Test
    fun parserTurnsUntilIntoAQueuedWait() {
        assertEquals(
            RemoteVisionDecision.Plan(
                listOf(
                    RemoteVisionDecision.Tap(500, 80),
                    RemoteVisionDecision.WaitUntil(RemoteVisionUntil(text = "搜索", timeoutMillis = 8_000L)),
                    RemoteVisionDecision.Type("牛奶"),
                ),
            ),
            parseRemoteVisionDecision(
                """{"steps":[{"action":"tap","x":500,"y":80,"until":{"text":"搜索","timeoutMs":8000}},{"action":"type","text":"牛奶"}]}""",
            ),
        )
        // A single action with until is a two-step plan; a wait with until is just the wait.
        assertEquals(
            RemoteVisionDecision.Plan(
                listOf(RemoteVisionDecision.SubmitSearch, RemoteVisionDecision.WaitUntil(RemoteVisionUntil(gone = "加载中"))),
            ),
            parseRemoteVisionDecision("""{"action":"submit","until":{"gone":"加载中"}}"""),
        )
        assertEquals(
            RemoteVisionDecision.WaitUntil(RemoteVisionUntil(packageName = "com.xingin.xhs", timeoutMillis = 20_000L)),
            parseRemoteVisionDecision("""{"action":"wait","until":{"package":"com.xingin.xhs","timeoutMs":99999}}"""),
        )
    }

    @Test
    fun badUntilDropsTheWholePlan() {
        listOf(
            """{"action":"tap","x":1,"y":1,"until":{"text":"a","colour":"red"}}""",
            """{"action":"tap","x":1,"y":1,"until":"搜索"}""",
            """{"action":"tap","x":1,"y":1,"until":{"text":5}}""",
            """{"action":"tap","x":1,"y":1,"until":{"text":"a","timeoutMs":"8000"}}""",
            """{"action":"tap","x":1,"y":1,"until":{"text":"a","timeoutMs":1.5}}""",
            """{"action":"tap","x":1,"y":1,"until":{"timeoutMs":3000}}""",
            """{"action":"tap","x":1,"y":1,"until":{"text":"  "}}""",
            """{"action":"tap","x":1,"y":1,"until":{"package":"not a package"}}""",
            """{"action":"tap","x":1,"y":1,"until":null}""",
            """{"action":"stop","until":{"text":"a"}}""",
            // Even on a step that would otherwise be cut off by truncation.
            """{"steps":[{"action":"tap","x":1,"y":1},{"action":"tap","x":2,"y":2,"until":{"bogus":1}}]}""",
        ).forEach { raw ->
            assertEquals(raw, RemoteVisionDecision.Unavailable("plan_invalid_until"), parseRemoteVisionDecision(raw))
        }
    }

    @Test
    fun untilWaitRunsBeforeTheNextStepAndOnlyItsSuccessReleasesIt() {
        var calls = 0
        val replanner = plannerWith(
            decider = {
                calls++
                if (calls == 1) {
                    RemoteVisionDecision.Plan(
                        listOf(
                            RemoteVisionDecision.Tap(500, 80),
                            RemoteVisionDecision.WaitUntil(RemoteVisionUntil(text = "搜索", timeoutMillis = 6_000L)),
                            RemoteVisionDecision.Type("牛奶"),
                        ),
                    )
                } else {
                    RemoteVisionDecision.Back
                }
            },
        )
        val tap = replanner.planNext(context(inheritedPackage = "com.xingin.xhs"))!!.request
        val wait = replanner.planNext(context(previous = tap).withAfterPackage("com.xingin.xhs"))!!.request
        assertEquals(MobileActionFunctions.UI_WAIT, wait.toolName)
        assertEquals(
            mapOf("expectText" to "搜索", "timeoutMillis" to "6000", "expectedPackageName" to "com.xingin.xhs"),
            wait.arguments,
        )
        assertNull(defaultBuiltInToolRegistry.validate(wait))

        // The wait timed out: the queued type is dropped and the model decides again.
        val timedOut = context(priorRequests = listOf(tap), previous = wait).let { ctx ->
            ctx.copy(
                observedResult = ctx.observedResult.copy(
                    status = ToolStatus.Failed,
                    retryable = true,
                    data = mapOf("failureKind" to "timeout", "screenObservationDiffSummary" to "unchanged"),
                ),
            )
        }
        assertEquals(MobileActionFunctions.UI_PRESS_BACK, replanner.planNext(timedOut)!!.request.toolName)
        assertEquals(2, calls)
    }

    @Test
    fun succeededUntilWaitReleasesTheNextStep() {
        val replanner = plannerWith(
            decider = {
                RemoteVisionDecision.Plan(
                    listOf(
                        RemoteVisionDecision.Tap(500, 80),
                        RemoteVisionDecision.WaitUntil(RemoteVisionUntil(text = "搜索")),
                        RemoteVisionDecision.Type("牛奶"),
                    ),
                )
            },
        )
        val tap = replanner.planNext(context())!!.request
        val wait = replanner.planNext(context(previous = tap))!!.request
        val typed = replanner.planNext(context(priorRequests = listOf(tap), previous = wait))!!.request

        assertEquals(MobileActionFunctions.UI_TYPE_TEXT, typed.toolName)
    }

    @Test
    fun packageChangeDropsTheQueue() {
        var calls = 0
        val replanner = plannerWith(
            decider = {
                calls++
                if (calls == 1) {
                    RemoteVisionDecision.Plan(listOf(RemoteVisionDecision.Tap(500, 80), RemoteVisionDecision.Type("牛奶")))
                } else {
                    RemoteVisionDecision.Back
                }
            },
        )
        val tap = replanner.planNext(context(inheritedPackage = "com.xingin.xhs"))!!.request
        val switched = context(previous = tap).withAfterPackage("com.android.launcher")

        assertEquals(MobileActionFunctions.UI_PRESS_BACK, replanner.planNext(switched)!!.request.toolName)
        assertEquals(2, calls)
    }

    @Test
    fun unknownForegroundDropsTheQueueWhenAPackageIsExpected() {
        var calls = 0
        val replanner = plannerWith(
            decider = {
                calls++
                RemoteVisionDecision.Plan(listOf(RemoteVisionDecision.Tap(100 * calls, 80), RemoteVisionDecision.SubmitSearch))
            },
        )
        val tap = replanner.planNext(context(inheritedPackage = "com.xingin.xhs"))!!.request

        assertEquals(MobileActionFunctions.UI_TAP, replanner.planNext(context(previous = tap))!!.request.toolName)
        assertEquals(2, calls)
    }

    @Test
    fun aPlannedPackageWaitIsNotAnAppSwitch() {
        val replanner = plannerWith(
            decider = {
                RemoteVisionDecision.Plan(
                    listOf(
                        RemoteVisionDecision.Tap(500, 80),
                        RemoteVisionDecision.WaitUntil(RemoteVisionUntil(packageName = "com.taobao.taobao")),
                        RemoteVisionDecision.Type("牛奶"),
                    ),
                )
            },
        )
        val tap = replanner.planNext(context(inheritedPackage = "com.xingin.xhs"))!!.request
        val wait = replanner.planNext(context(previous = tap).withAfterPackage("com.android.launcher"))!!.request
        assertEquals(mapOf("expectPackage" to "com.taobao.taobao"), wait.arguments)

        val typed = replanner.planNext(
            context(priorRequests = listOf(tap), previous = wait).withAfterPackage("com.taobao.taobao"),
        )!!.request
        assertEquals(MobileActionFunctions.UI_TYPE_TEXT, typed.toolName)
    }

    @Test
    fun aNewOverlayDropsTheQueue() {
        var calls = 0
        val replanner = plannerWith(
            decider = {
                calls++
                if (calls == 1) {
                    RemoteVisionDecision.Plan(listOf(RemoteVisionDecision.Tap(500, 80), RemoteVisionDecision.Type("牛奶")))
                } else {
                    RemoteVisionDecision.Back
                }
            },
        )
        val tap = replanner.planNext(context())!!.request
        val overlay = context(previous = tap).let { ctx ->
            ctx.copy(
                observedResult = ctx.observedResult.copy(
                    data = ctx.observedResult.data + mapOf(
                        "beforeScreenObservationJson" to observationJson("首页"),
                        "afterScreenObservationJson" to observationJson("优惠券", "红包", "关闭"),
                    ),
                ),
            )
        }

        assertEquals(MobileActionFunctions.UI_PRESS_BACK, replanner.planNext(overlay)!!.request.toolName)
        assertEquals(2, calls)
    }

    private fun AgentObservationReplanContext.withAfterPackage(packageName: String): AgentObservationReplanContext =
        copy(observedResult = observedResult.copy(data = observedResult.data + ("afterPackageName" to packageName)))

    private fun observationJson(vararg texts: String): String =
        ScreenStateSnapshot(
            id = "o",
            packageName = "com.xingin.xhs",
            capturedAtMillis = 1L,
            nodes = texts.map { text ->
                ScreenNode(
                    id = text,
                    text = text,
                    contentDescription = "",
                    className = "",
                    bounds = null,
                    clickable = false,
                    editable = false,
                    scrollable = false,
                    enabled = true,
                )
            },
            textSummary = "",
            truncated = false,
        ).toScreenObservationJsonString()

    private fun assertFinishes(message: String, replan: AgentObservationReplan?) {
        assertEquals(message, MobileActionFunctions.FINISH, replan?.request?.toolName)
    }
}
