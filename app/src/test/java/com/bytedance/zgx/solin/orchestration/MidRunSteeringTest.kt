package com.bytedance.zgx.solin.orchestration

import org.junit.Assert.assertEquals
import org.junit.Test

class MidRunSteeringTest {
    @Test
    fun onlyAModelStreamIsSteeredAndToolPhasesQueue() {
        assertEquals(MidRunSteeringAction.Steer, midRunSteeringActionFor(AgentRunState.GeneratingAnswer))
        listOf(
            AgentRunState.Created,
            AgentRunState.LoadingContext,
            AgentRunState.Planning,
            AgentRunState.ExecutingTool,
            AgentRunState.RetryingTool,
            AgentRunState.Observing,
        ).forEach { state ->
            assertEquals(state.name, MidRunSteeringAction.Queue, midRunSteeringActionFor(state))
        }
    }

    @Test
    fun parkedTerminalOrUnknownRunsAreNotSteerable() {
        listOf(
            AgentRunState.AwaitingUserConfirmation,
            AgentRunState.AwaitingUserAnswer,
            AgentRunState.AwaitingExternalOutcome,
            AgentRunState.Completed,
            AgentRunState.Cancelled,
            AgentRunState.Failed,
            null,
        ).forEach { state ->
            assertEquals(state?.name, MidRunSteeringAction.NotSteerable, midRunSteeringActionFor(state))
        }
    }
}
