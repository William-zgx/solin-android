package com.bytedance.zgx.solin.orchestration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ModelRuntimeDispatcherTest {
    @Test
    fun concurrentDispatchHasOneClaimAndCallbackRunsOnlyAfterReceiptAndInvocationCommit() = runTest {
        val fixture = fixture("run-race", RunPlacement.Remote)
        var callbackCount = 0
        val adapters = ModelRuntimeAdapters<Unit>(
            local = { error("local must not run") },
            remote = { invocation ->
                assertEquals(
                    listOf("PlacementSelected", "RunDataReceiptRecorded", "ModelRuntimeInvocationStarted"),
                    fixture.dao.steps(invocation.runId).map { it.type },
                )
                callbackCount++
            },
        )

        val results = listOf(
            async(Dispatchers.Default) {
                runCatching { fixture.dispatcher.dispatch(fixture.permit, testReceipt(RunPlacement.Remote), adapters) }
            },
            async(Dispatchers.Default) {
                runCatching { fixture.dispatcher.dispatch(fixture.permit, testReceipt(RunPlacement.Remote), adapters) }
            },
        ).awaitAll()

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, callbackCount)
        assertEquals(1, fixture.dao.steps("run-race").count { it.type == "ModelRuntimeInvocationStarted" })
    }

    @Test
    fun repeatedSamePlacementDispatchIncrementsAttemptOneThenTwo() = runTest {
        val fixture = fixture("run-attempts", RunPlacement.Local)
        val attempts = mutableListOf<Int>()
        val adapters = ModelRuntimeAdapters<Unit>(
            local = { invocation -> attempts += invocation.attempt },
            remote = { error("remote must not run") },
        )

        fixture.dispatcher.dispatch(fixture.permit, testReceipt(RunPlacement.Local), adapters)
        fixture.dispatcher.dispatch(fixture.permit, testReceipt(RunPlacement.Local), adapters)

        assertEquals(listOf(1, 2), attempts)
        assertEquals(2, fixture.dao.binding("run-attempts")?.attempt)
        assertEquals("Idle", fixture.dao.binding("run-attempts")?.dispatchState)
    }

    @Test
    fun permitSelectsOnlyBoundAdapterAndRuntimeFailureNeverFallsBack() = runTest {
        val fixture = fixture("run-no-fallback", RunPlacement.Remote)
        var localCalls = 0
        var remoteCalls = 0
        val adapters = ModelRuntimeAdapters<Unit>(
            local = { localCalls++ },
            remote = {
                remoteCalls++
                error("remote runtime failed")
            },
        )

        val result = runCatching {
            fixture.dispatcher.dispatch(fixture.permit, testReceipt(RunPlacement.Remote), adapters)
        }

        assertTrue(result.isFailure)
        assertEquals(0, localCalls)
        assertEquals(1, remoteCalls)
    }

    @Test
    fun missingActiveOrCrossDestinationReceiptInvokesNeitherAdapter() = runTest {
        val fixture = fixture("run-gate", RunPlacement.Remote)
        var calls = 0
        val adapters = ModelRuntimeAdapters<Unit>(
            local = { calls++ },
            remote = { calls++ },
        )

        assertTrue(
            runCatching {
                fixture.dispatcher.dispatch(
                    ActiveRunPlacementPermit.detached(fixture.permit.binding),
                    testReceipt(RunPlacement.Remote),
                    adapters,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                fixture.dispatcher.dispatch(
                    fixture.permit,
                    testReceipt(RunPlacement.Local),
                    adapters,
                )
            }.isFailure,
        )
        assertEquals(0, calls)
        assertEquals("Pending", fixture.dao.binding("run-gate")?.dispatchState)
    }

    @Test
    fun receiptOrInvocationTraceFailureRollsBackClaimAndCallbackIsZero() = runTest {
        listOf("RunDataReceiptRecorded", "ModelRuntimeInvocationStarted").forEach { failedType ->
            val fixture = fixture("run-${failedType.lowercase()}", RunPlacement.Local)
            fixture.dao.failStepType = failedType
            var calls = 0
            val result = runCatching {
                fixture.dispatcher.dispatch(
                    fixture.permit,
                    testReceipt(RunPlacement.Local),
                    ModelRuntimeAdapters(local = { calls++ }, remote = { calls++ }),
                )
            }

            assertTrue(result.isFailure)
            assertEquals(0, calls)
            assertEquals("Pending", fixture.dao.binding(fixture.permit.binding.runId)?.dispatchState)
            assertEquals(0, fixture.dao.binding(fixture.permit.binding.runId)?.attempt)
            assertTrue(fixture.dao.steps(fixture.permit.binding.runId).none { it.type == failedType })
        }
    }

    @Test
    fun finishCasFailureLeavesStartedAndPermanentlyBlocksRetry() = runTest {
        val fixture = fixture("run-finish", RunPlacement.Local)
        fixture.dao.failFinish = true
        var calls = 0
        val adapters = ModelRuntimeAdapters<Unit>(
            local = { calls++ },
            remote = { error("remote must not run") },
        )

        assertTrue(
            runCatching {
                fixture.dispatcher.dispatch(fixture.permit, testReceipt(RunPlacement.Local), adapters)
            }.isFailure,
        )
        assertEquals("Started", fixture.dao.binding("run-finish")?.dispatchState)
        assertTrue(
            runCatching {
                fixture.dispatcher.dispatch(fixture.permit, testReceipt(RunPlacement.Local), adapters)
            }.isFailure,
        )
        assertEquals(1, calls)
    }

    @Test
    fun terminalRunRejectsClaimAndInvokesNeitherAdapter() = runTest {
        val fixture = fixture("run-terminal", RunPlacement.Local)
        assertTrue(
            fixture.store.terminalizeRun("run-terminal", AgentRunState.Cancelled, 2_000L)
                is TerminalizeRunResult.Terminalized,
        )
        var calls = 0

        assertTrue(
            runCatching {
                fixture.dispatcher.dispatch(
                    fixture.permit,
                    testReceipt(RunPlacement.Local),
                    ModelRuntimeAdapters(local = { calls++ }, remote = { calls++ }),
                )
            }.isFailure,
        )
        assertEquals(0, calls)
        assertEquals("Terminal", fixture.dao.binding("run-terminal")?.dispatchState)
    }

    @Test
    fun changedRemoteRevisionRejectsDispatchBeforeRuntimeCallback() = runTest {
        val fixture = fixture("run-remote-revision", RunPlacement.Remote)
        fixture.recoveryContext.value = fixture.recoveryContext.value.copy(
            remoteProfileRevision = "00000000-0000-0000-0000-000000000002",
        )
        var calls = 0

        assertTrue(
            runCatching {
                fixture.dispatcher.dispatch(
                    fixture.permit,
                    testReceipt(RunPlacement.Remote),
                    ModelRuntimeAdapters(local = { calls++ }, remote = { calls++ }),
                )
            }.isFailure,
        )
        assertEquals(0, calls)
        assertEquals("Pending", fixture.dao.binding("run-remote-revision")?.dispatchState)
    }

    @Test
    fun stopTerminalizesThenCallsOnlyTheBoundRuntime() {
        listOf(RunPlacement.Local, RunPlacement.Remote).forEach { placement ->
            val fixture = fixture("run-stop-${placement.name.lowercase()}", placement)
            var localStops = 0
            var remoteStops = 0

            assertTrue(
                fixture.dispatcher.stop(
                    runId = fixture.permit.binding.runId,
                    state = AgentRunState.Cancelled,
                    updatedAtMillis = 2_000L,
                    stops = ModelRuntimeStops(
                        local = { localStops++ },
                        remote = { remoteStops++ },
                    ),
                ),
            )

            assertEquals(if (placement == RunPlacement.Local) 1 else 0, localStops)
            assertEquals(if (placement == RunPlacement.Remote) 1 else 0, remoteStops)
            assertEquals("Cancelled", fixture.dao.run(fixture.permit.binding.runId)?.state)
            assertEquals("Terminal", fixture.dao.binding(fixture.permit.binding.runId)?.dispatchState)
        }
    }

    @Test
    fun stopWithoutBindingCallsNeitherRuntimeAndTerminalStateMismatchIsRejected() {
        val dao = FakeRunPlacementBindingDao()
        val store = RoomRunPlacementBindingStore(
            dao = dao,
            currentRecoveryContext = {
                RunPlacementRecoveryContext(bootCount = 7L, elapsedRealtimeMillis = 2_000L)
            },
        )
        assertTrue(store.createCriticalRun(AgentRun("run-pre-bind-stop", "secret", AgentRunState.Created, 1L, 1L)))
        val dispatcher = ModelRuntimeDispatcher(store)
        var localStops = 0
        var remoteStops = 0
        val stops = ModelRuntimeStops(local = { localStops++ }, remote = { remoteStops++ })

        assertTrue(dispatcher.stop("run-pre-bind-stop", AgentRunState.Cancelled, 2_000L, stops))
        assertFalse(dispatcher.stop("run-pre-bind-stop", AgentRunState.Completed, 3_000L, stops))
        assertEquals(0, localStops)
        assertEquals(0, remoteStops)
        assertEquals("Cancelled", dao.run("run-pre-bind-stop")?.state)
    }

    @Test
    fun concurrentStopAndInitialClaimAlwaysLeaveTheRunTerminal() = runTest {
        val fixture = fixture("run-stop-race", RunPlacement.Local)
        var calls = 0

        val dispatch = async(Dispatchers.Default) {
            runCatching {
                fixture.dispatcher.dispatch(
                    fixture.permit,
                    testReceipt(RunPlacement.Local),
                    ModelRuntimeAdapters(local = { calls++ }, remote = { calls++ }),
                )
            }.isSuccess
        }
        val stopped = async(Dispatchers.Default) {
            fixture.store.terminalizeRun("run-stop-race", AgentRunState.Cancelled, 2_000L) is
                TerminalizeRunResult.Terminalized
        }

        awaitAll(dispatch, stopped)
        assertTrue(stopped.await())
        assertTrue(calls in 0..1)
        assertEquals("Cancelled", fixture.dao.run("run-stop-race")?.state)
        assertEquals("Terminal", fixture.dao.binding("run-stop-race")?.dispatchState)
    }

    @Test
    fun dispatchWaitingOnStopTransactionNeverStartsRuntime() = runTest {
        val fixture = fixture("run-stop-in-flight", RunPlacement.Local)
        val terminalized = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        fixture.dao.afterTerminalizedInsideTransaction = {
            terminalized.countDown()
            check(releaseStop.await(5, TimeUnit.SECONDS))
        }
        val stop = async(Dispatchers.Default) {
            fixture.store.terminalizeRun("run-stop-in-flight", AgentRunState.Cancelled, 2_000L)
        }
        assertTrue(terminalized.await(5, TimeUnit.SECONDS))
        var calls = 0
        val dispatch = async(Dispatchers.Default) {
            runCatching {
                fixture.dispatcher.dispatch(
                    fixture.permit,
                    testReceipt(RunPlacement.Local),
                    ModelRuntimeAdapters(local = { calls++ }, remote = { calls++ }),
                )
            }
        }

        releaseStop.countDown()
        assertTrue(stop.await() is TerminalizeRunResult.Terminalized)
        assertTrue(dispatch.await().isFailure)
        assertEquals(0, calls)
    }

    @Test
    fun runtimeFinallyKeepsTerminalBindingTerminal() = runTest {
        val fixture = fixture("run-stop-started", RunPlacement.Local)

        fixture.dispatcher.dispatch(
            fixture.permit,
            testReceipt(RunPlacement.Local),
            ModelRuntimeAdapters(
                local = {
                    assertTrue(
                        fixture.store.terminalizeRun(
                            "run-stop-started",
                            AgentRunState.Cancelled,
                            2_000L,
                        ) is TerminalizeRunResult.Terminalized,
                    )
                },
                remote = { error("remote must not run") },
            ),
        )

        assertEquals("Cancelled", fixture.dao.run("run-stop-started")?.state)
        assertEquals("Terminal", fixture.dao.binding("run-stop-started")?.dispatchState)
        assertEquals(ModelDispatchState.Terminal, fixture.permit.binding.dispatchState)
    }

    private fun fixture(runId: String, placement: RunPlacement): Fixture {
        val dao = FakeRunPlacementBindingDao()
        val recoveryContext = MutableRecoveryContext(
            RunPlacementRecoveryContext(
                bootCount = 7L,
                elapsedRealtimeMillis = 2_000L,
                remoteProfileRevision = TEST_REMOTE_REVISION,
            ),
        )
        val store = RoomRunPlacementBindingStore(
            dao = dao,
            currentRecoveryContext = { recoveryContext.value },
            traceClockMillis = { 9_000L },
        )
        check(
            store.createCriticalRun(
                AgentRun(runId, "secret", AgentRunState.Created, 1L, 1L),
            ),
        )
        val permit = (store.bindAndReserve(testBinding(runId, placement)) as BindAndReserveResult.Bound).permit
        return Fixture(
            dao = dao,
            store = store,
            recoveryContext = recoveryContext,
            permit = permit,
            dispatcher = ModelRuntimeDispatcher(store),
        )
    }

    private data class Fixture(
        val dao: FakeRunPlacementBindingDao,
        val store: RoomRunPlacementBindingStore,
        val recoveryContext: MutableRecoveryContext,
        val permit: ActiveRunPlacementPermit,
        val dispatcher: ModelRuntimeDispatcher,
    )

    private class MutableRecoveryContext(
        var value: RunPlacementRecoveryContext,
    )
}
