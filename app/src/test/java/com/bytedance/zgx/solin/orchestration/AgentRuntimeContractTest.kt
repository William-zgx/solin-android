package com.bytedance.zgx.solin.orchestration

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRuntimeContractTest {
    @Test
    fun runStatesMapToProductContractPhases() {
        assertEquals(AgentRuntimePhase.Planning, AgentRunState.Planning.contractPhase())
        assertEquals(AgentRuntimePhase.NeedsConfirmation, AgentRunState.AwaitingUserConfirmation.contractPhase())
        assertEquals(AgentRuntimePhase.Executing, AgentRunState.ExecutingTool.contractPhase())
        assertEquals(AgentRuntimePhase.Executing, AgentRunState.RetryingTool.contractPhase())
        assertEquals(AgentRuntimePhase.Executing, AgentRunState.Observing.contractPhase())
        assertEquals(AgentRuntimePhase.WaitingExternalResult, AgentRunState.AwaitingExternalOutcome.contractPhase())
        assertEquals(AgentRuntimePhase.Completed, AgentRunState.Completed.contractPhase())
        assertEquals(AgentRuntimePhase.Failed, AgentRunState.Failed.contractPhase())
        assertEquals(AgentRuntimePhase.Cancelled, AgentRunState.Cancelled.contractPhase())
    }
}
