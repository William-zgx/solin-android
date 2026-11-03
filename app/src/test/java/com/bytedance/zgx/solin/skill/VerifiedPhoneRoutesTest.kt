package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.ObservationClickability
import com.bytedance.zgx.solin.device.ObservationElement
import com.bytedance.zgx.solin.device.ScreenBounds
import com.bytedance.zgx.solin.device.ScreenObservation
import com.bytedance.zgx.solin.device.toJsonObject
import com.bytedance.zgx.solin.orchestration.AgentObservationReplanContext
import com.bytedance.zgx.solin.orchestration.AgentObservationReplanner
import com.bytedance.zgx.solin.orchestration.AgentRun
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.VerifiedPhoneRouteReplanner
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.*
import org.junit.Test

class VerifiedPhoneRoutesTest {
    private val now = 10_000L
    private val goal = "搜索栖知文档"
    private val query = "栖知文档"
    private val pkg = "example.search"
    private fun routes() = VerifiedPhoneRoutes { now }

    @Test fun verifiedRouteProposesOneFreshRequestAndAwaitsActualResult() {
        val routes = routes()
        learn(routes)
        val first = routes.propose("second", "session", goal, observation(screen()))!!
        assertEquals(MobileActionFunctions.UI_TYPE_TEXT, first.toolName)
        assertEquals(query, first.arguments["text"])
        assertEquals("搜索框", first.arguments["target"])
        assertEquals(pkg, first.arguments["expectedPackageName"])
        assertNull(routes.propose("second", "session", goal, observation(screen())))
        routes.observe("second", "session", goal, first, action(first, screen(), screen(query)))
        val submit = routes.propose("second", "session", goal, observation(screen(query)))!!
        assertEquals(MobileActionFunctions.UI_SUBMIT_SEARCH, submit.toolName)
        assertEquals(query, submit.arguments["verifySearchQuery"])
        routes.observe("second", "session", goal, submit, action(submit, screen(query), screen("结果"), verified = true))
        assertNull(routes.propose("second", "session", goal, observation(screen("结果"))))
    }

    @Test fun modelSuccessWithoutDeterministicSearchEvidenceNeverLearns() {
        val routes = routes()
        learn(routes, verified = false)
        assertNull(routes.propose("second", "session", goal, observation(screen())))
    }

    @Test fun unknownInputVerificationNeverLearns() {
        val routes = routes()
        learn(routes, inputVerification = "unknown")
        assertNull(routes.propose("second", "session", goal, observation(screen())))
    }

    @Test fun changedGoalOrDifferentConversationCannotReuse() {
        val routes = routes()
        learn(routes)
        assertNull(routes.propose("other", "session", "搜索另一件事", observation(screen())))
        assertNull(routes.propose("other-session", "another", goal, observation(screen())))
        assertNull(routes.propose("unknown-session", null, goal, observation(screen())))
        assertNotNull(routes.propose("second", "session", goal, observation(screen())))
        assertNull(routes.propose("second", "session", "搜索另一件事", observation(screen())))
        assertNull(routes.propose("second", "session", goal, observation(screen())))
    }

    @Test fun switchedAppOrAmbiguousTargetDisablesReplay() {
        val routes = routes()
        learn(routes)
        assertNull(routes.propose("wrong-app", "session", goal, observation(screen().copy(packageName = "another.app"))))
        val duplicate = screen().let { it.copy(elements = it.elements + it.elements.first().copy(id = "duplicate")) }
        assertNull(routes.propose("ambiguous", "session", goal, observation(duplicate)))
        // Two independent misses retire this route, even when the original page reappears.
        assertNull(routes.propose("third", "session", goal, observation(screen())))
    }

    @Test fun failedOrUnknownSideEffectDoesNotReplayAgain() {
        val routes = routes()
        learn(routes)
        val first = routes.propose("second", "session", goal, observation(screen()))!!
        routes.observe("second", "session", goal, first, action(first, screen(), screen(query)).copy(status = ToolStatus.Failed))
        assertNull(routes.propose("second", "session", goal, observation(screen())))
    }

    @Test fun noLearningFromSensitiveOrTruncatedScreens() {
        for (unsafe in listOf(
            screen().copy(truncated = true),
            screen().let { it.copy(elements = listOf(it.elements.first().copy(sensitiveFlags = listOf("password")))) },
        )) {
            val routes = routes()
            learn(routes, before = unsafe)
            assertNull(routes.propose("second", "session", goal, observation(screen())))
        }
    }

    @Test fun staleScreenNeverDrivesReplay() {
        val routes = routes()
        learn(routes)
        assertNull(routes.propose("second", "session", goal, observation(screen().copy(capturedAtMillis = 1))))
    }

    @Test fun foreignInputAndClipboardOptInCannotBecomeRememberedArguments() {
        for (args in listOf(mapOf("text" to "private screen text"), mapOf("allowClipboardPasteFallback" to "true"))) {
            val routes = routes()
            learn(routes, arguments = args)
            assertNull(routes.propose("second", "session", goal, observation(screen())))
        }
    }

    @Test fun reorderedRequestsAndMidRunWritesCannotRestartRoute() {
        val routes = routes()
        learn(routes)
        val type = type()
        routes.observe("second", "session", goal, type, action(type, screen(), screen(query)))
        assertNull(routes.propose("second", "session", goal, observation(screen())))
    }

    @Test fun wrapperEmitsConfirmationDraftWithoutExecutingDelegateOrActions() {
        val routes = routes()
        learn(routes)
        var delegated = 0
        val wrapper = VerifiedPhoneRouteReplanner(AgentObservationReplanner { delegated++; null }, routes)
        val read = ToolRequest(toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
        val run = AgentRun("second", goal, AgentRunState.Observing, now, now, "session")
        val result = observation(screen(), read.id)
        val context = AgentObservationReplanContext(run, read, result, listOf(read))
        val proposal = wrapper.planNext(context)!!
        assertTrue(proposal.draft.requiresConfirmation)
        assertEquals(proposal.request.arguments, proposal.draft.parameters)
        assertFalse(proposal.plannedByModel)
        assertEquals(0, delegated)
        assertNull(wrapper.planNext(context))
        assertEquals(0, delegated)
        wrapper.clear()
        assertNull(wrapper.planNext(context.copy(run = run.copy(id = "third"))))
    }

    @Test fun sequentialTailFallsBackInsteadOfReplayingWholeGoal() {
        val routes = routes()
        learn(routes)
        var delegated = 0
        val wrapper = VerifiedPhoneRouteReplanner(AgentObservationReplanner { delegated++; null }, routes)
        val read = ToolRequest(toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
        val run = AgentRun("second", goal, AgentRunState.Observing, now, now, "session")
        assertNull(wrapper.planNext(AgentObservationReplanContext(run, read, observation(screen(), read.id), listOf(read), nextActionInput = "再打开设置")))
        assertEquals(1, delegated)
    }

    private fun learn(
        routes: VerifiedPhoneRoutes,
        verified: Boolean = true,
        inputVerification: String = "match",
        before: ScreenObservation = screen(),
        arguments: Map<String, String> = emptyMap(),
    ) {
        val type = type().let { it.copy(arguments = it.arguments + arguments) }
        val submit = ToolRequest(toolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
            arguments = mapOf("expectedPackageName" to pkg, "verifySearchQuery" to query))
        routes.observe("learn", "session", goal, type,
            action(type, before, screen(query)).let { it.copy(data = it.data + ("inputVerification" to inputVerification)) })
        routes.observe("learn", "session", goal, submit, action(submit, screen(query), screen("结果"), verified))
    }

    private fun type() = ToolRequest(toolName = MobileActionFunctions.UI_TYPE_TEXT,
        arguments = mapOf("target" to "input", "text" to query, "expectedPackageName" to pkg))

    private fun screen(content: String = "") = ScreenObservation(
        observationId = "screen-$content", capturedAtMillis = now, packageName = pkg,
        privacyLevel = MessagePrivacy.LocalOnly, sources = listOf("accessibility"), truncated = false,
        elements = listOf(
            ObservationElement("input", "accessibility", ScreenBounds(0, 0, 100, 50), "搜索框", "edit",
                ObservationClickability(false, true, false, true), 1.0, emptyList(), MessagePrivacy.LocalOnly),
            ObservationElement("content", "accessibility", ScreenBounds(0, 50, 100, 100), content, "text",
                ObservationClickability(false, false, false, true), 1.0, emptyList(), MessagePrivacy.LocalOnly),
        ),
    )

    private fun observation(screen: ScreenObservation, id: String = "read") = ToolResult(id, ToolStatus.Succeeded, "observed",
        mapOf("privacy" to "LocalOnly", "requiresLocalModel" to "true", "screenObservationJson" to screen.toJsonObject().toString()))

    private fun action(request: ToolRequest, before: ScreenObservation, after: ScreenObservation, verified: Boolean = false) =
        ToolResult(request.id, ToolStatus.Succeeded, "done", mapOf(
            "privacy" to "LocalOnly", "requiresLocalModel" to "true", "inputVerification" to "match",
            "beforeScreenObservationJson" to before.toJsonObject().toString(),
            "afterScreenObservationJson" to after.toJsonObject().toString(),
            "searchVerificationStatus" to if (verified) "verified" else "not_verified",
        ))
}
