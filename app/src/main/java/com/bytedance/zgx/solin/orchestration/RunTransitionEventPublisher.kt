package com.bytedance.zgx.solin.orchestration

/**
 * SolinEvent lifecycle publishing, dual-written alongside the trace store.
 *
 * Owns the per-run turn counter reads/bumps and the "run moved to state X" event mapping that the
 * model-result and tool-observation paths both publish after their state transition. Reads the
 * lifecycle metadata in [RunScopedState]; callers publish before terminal cleanup drops it.
 */
internal class RunTransitionEventPublisher(
    private val eventBus: SolinEventBus,
    private val runScopedState: RunScopedState,
    private val markStepStart: (runId: String, stepType: String) -> Unit,
) {
    fun publishRunFailed(
        runId: String,
        code: AgentErrorCode,
        message: String,
        terminalTurnIndex: Int? = runScopedState.runTurnIndex[runId],
    ) {
        eventBus.publish(
            SolinEvent.Agent.RunFailed(
                runId = runId,
                code = code,
                message = message,
                terminalTurnIndex = terminalTurnIndex,
            ),
        )
    }

    fun publishRunEnded(
        runId: String,
        finalText: String? = null,
    ) {
        val start = runScopedState.runStartedAtMillis[runId]
        val durationMs = start?.let { System.currentTimeMillis() - it }
        val turns = runScopedState.runTurnIndex[runId] ?: 0
        eventBus.publish(
            SolinEvent.Agent.RunEnded(
                runId = runId,
                totalTurns = turns,
                finalText = finalText,
                durationMs = durationMs,
            ),
        )
    }

    /**
     * Publishes the events for a run that just moved to [finalState]:
     *  - Completed: optional TurnEnded (model turns only; token stats are zero until the streaming
     *    runtime surfaces them), then RunEnded with [completedText].
     *  - Failed: RunFailed with [failureCode] / [failureMessage].
     *  - Cancelled: RunCancelled (not by the user; user cancellation publishes its own event).
     *  - GeneratingAnswer: bumps the turn counter, publishes TurnStarted, marks model_generation.
     *  - ExecutingTool / RetryingTool: marks tool_execution.
     */
    fun publishTransition(
        runId: String,
        finalState: AgentRunState,
        completedText: String?,
        publishTurnEndedOnCompletion: Boolean,
        failureCode: AgentErrorCode,
        failureMessage: String,
    ) {
        when (finalState) {
            AgentRunState.Completed -> {
                if (publishTurnEndedOnCompletion) {
                    val turnIdx = runScopedState.runTurnIndex[runId] ?: 0
                    eventBus.publish(
                        SolinEvent.Agent.TurnEnded(
                            runId = runId,
                            turnIndex = turnIdx,
                            tokensIn = 0,
                            tokensOut = 0,
                            ttftMs = 0L,
                            durationMs = 0L,
                        ),
                    )
                }
                publishRunEnded(runId, finalText = completedText)
            }
            AgentRunState.Failed -> publishRunFailed(runId, failureCode, failureMessage)
            AgentRunState.Cancelled ->
                eventBus.publish(SolinEvent.Agent.RunCancelled(runId = runId, byUser = false))
            AgentRunState.GeneratingAnswer -> {
                // Starting a new model turn — bump the turn counter and publish TurnStarted.
                val nextTurn = runScopedState.runTurnIndex.compute(runId) { _, old -> (old ?: 0) + 1 }!!
                eventBus.publish(
                    SolinEvent.Agent.TurnStarted(runId = runId, turnIndex = nextTurn),
                )
                markStepStart(runId, "model_generation")
            }
            AgentRunState.RetryingTool -> markStepStart(runId, "tool_execution")
            AgentRunState.ExecutingTool -> markStepStart(runId, "tool_execution")
            else -> Unit
        }
    }
}
