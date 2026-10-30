package com.bytedance.zgx.solin.orchestration

/**
 * How a user message sent while a run is active is handled (mid-run steering).
 *
 *  - [Steer]: the run is streaming a model answer; the in-flight model call is cancelled
 *    cooperatively and the message becomes the next user turn.
 *  - [Queue]: the run is executing / observing a tool. Tool execution is NEVER interrupted; the
 *    message waits until the tool's result has been observed. While it waits the runtime stops
 *    autonomously planning further tool steps for that run (fail-safe: the user's new input wins
 *    over more automation).
 *  - [NotSteerable]: the run is parked on the user (confirmation, question, external outcome) or
 *    already terminal — the normal send flow (and its "resolve the pending decision first" gates)
 *    applies unchanged.
 */
enum class MidRunSteeringAction {
    Steer,
    Queue,
    NotSteerable,
}

fun midRunSteeringActionFor(state: AgentRunState?): MidRunSteeringAction =
    when (state) {
        AgentRunState.GeneratingAnswer -> MidRunSteeringAction.Steer
        AgentRunState.Created,
        AgentRunState.LoadingContext,
        AgentRunState.Planning,
        AgentRunState.ExecutingTool,
        AgentRunState.RetryingTool,
        AgentRunState.Observing -> MidRunSteeringAction.Queue
        AgentRunState.AwaitingUserConfirmation,
        AgentRunState.AwaitingUserAnswer,
        AgentRunState.AwaitingExternalOutcome,
        AgentRunState.Completed,
        AgentRunState.Cancelled,
        AgentRunState.Failed,
        null -> MidRunSteeringAction.NotSteerable
    }
