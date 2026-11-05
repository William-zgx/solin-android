package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ModelCapabilityProfile
import java.util.concurrent.ConcurrentHashMap

/** Key for per-run step-latency start markers; see [RunScopedState.stepStartedAtMillis]. */
internal data class StepStartKey(val runId: String, val stepType: String)

/**
 * In-memory, runId-keyed bookkeeping owned by [AgentLoopRuntime].
 *
 * Every map here is per run and must be dropped once the run reaches a terminal state
 * ([forgetRun]); before this holder existed several of them (remote GUI opt-in, lifecycle
 * timestamps, turn counter, parked time, step markers) were only cleared on session delete or
 * `close()`, so a long-lived process grew them by one entry per run.
 *
 * Nothing here is needed after a run is terminal: lifecycle events that read the start time / turn
 * counter (RunEnded, RunFailed) are published before the terminal cleanup, terminal runs never
 * re-enter planning, and restore after a restart is driven by the trace store, not by these maps.
 *
 * Ephemeral state with its own lifecycle (pending ask_user questions, in-flight model-call jobs,
 * steer channels, tool dispatch timestamps keyed by request id, the scratchpad) stays on the runtime.
 */
internal class RunScopedState {
    val valueFreeCompletedStepFrontiersByRunId = ConcurrentHashMap<String, Set<String>>()
    val remoteToolScopesByRunId = ConcurrentHashMap<String, RemoteToolScope>()
    val remoteExposedToolNamesByRunId = ConcurrentHashMap<String, Set<String>>()
    val lowRiskDeviceActionConfirmationBypassByRunId = ConcurrentHashMap<String, Boolean>()
    val remoteGuiDrivingEnabledByRunId = ConcurrentHashMap<String, Boolean>()
    val installedCapabilityProfilesByRunId = ConcurrentHashMap<String, List<ModelCapabilityProfile>>()
    /** Composite child steps count against the same run budget, without replacing its parent plan. */
    val compositeStepsByRunId = ConcurrentHashMap<String, Int>()
    val compositeChildIdsByRunId = ConcurrentHashMap<String, MutableSet<String>>()

    /** Wall-clock start per run: lifecycle event durations and the run wall-clock deadline. */
    val runStartedAtMillis = ConcurrentHashMap<String, Long>()

    /** Accumulated time parked on the user / an external app; see AgentLoopRuntime.parkedMillisFor. */
    val parkedMillisByRunId = ConcurrentHashMap<String, Long>()
    val lastParkSampleAtMillis = ConcurrentHashMap<String, Long>()

    /** Monotonic per-run turn counter for TurnStarted / RunEnded / RunFailed events. */
    val runTurnIndex = ConcurrentHashMap<String, Int>()

    /**
     * Runs holding user input sent mid-run (steer/queue) that has not been drained yet. While set,
     * the observation path stops autonomously planning further tool steps for the run.
     */
    val pendingUserInputByRunId = ConcurrentHashMap<String, Boolean>()

    /** (runId, stepType) -> last started-at epoch millis for StepLatency telemetry. */
    val stepStartedAtMillis = ConcurrentHashMap<StepStartKey, Long>()

    private val runKeyedMaps: List<MutableMap<String, *>> = listOf(
        valueFreeCompletedStepFrontiersByRunId,
        remoteToolScopesByRunId,
        remoteExposedToolNamesByRunId,
        lowRiskDeviceActionConfirmationBypassByRunId,
        remoteGuiDrivingEnabledByRunId,
        installedCapabilityProfilesByRunId,
        compositeStepsByRunId,
        compositeChildIdsByRunId,
        runStartedAtMillis,
        parkedMillisByRunId,
        lastParkSampleAtMillis,
        runTurnIndex,
        pendingUserInputByRunId,
    )

    /** Drops every entry recorded for [runId]. */
    fun forgetRun(runId: String) {
        runKeyedMaps.forEach { map -> map.remove(runId) }
        stepStartedAtMillis.keys.removeIf { key -> key.runId == runId }
    }

    fun clear() {
        runKeyedMaps.forEach { map -> map.clear() }
        stepStartedAtMillis.clear()
    }

    /** Run ids that still have any entry; used by tests to assert terminal cleanup. */
    fun trackedRunIds(): Set<String> =
        runKeyedMaps.flatMapTo(mutableSetOf()) { map -> map.keys } +
            stepStartedAtMillis.keys.map { key -> key.runId }
}
