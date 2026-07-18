package com.bytedance.zgx.solin.orchestration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ModelRuntimeDispatcherTest {
    @Test
    fun concurrentDispatchHasOneClaimAndCallbackRunsOnlyAfterReceiptAndInvocationCommit() = runTest {
        val fixture = fixture("run-race", RunPlacement.Remote)
        var callbackCount = 0
        val adapters = testRuntimeAdapters<Unit>(
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
        val adapters = testRuntimeAdapters<Unit>(
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
        val adapters = testRuntimeAdapters<Unit>(
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
        val adapters = testRuntimeAdapters<Unit>(
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
                    testRuntimeAdapters(local = { calls++ }, remote = { calls++ }),
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
        val adapters = testRuntimeAdapters<Unit>(
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
                    testRuntimeAdapters(local = { calls++ }, remote = { calls++ }),
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
                    testRuntimeAdapters(local = { calls++ }, remote = { calls++ }),
                )
            }.isFailure,
        )
        assertEquals(0, calls)
        assertEquals("Pending", fixture.dao.binding("run-remote-revision")?.dispatchState)
    }

    @Test
    fun stopAfterRuntimeStartCallsOnlyTheBoundHandle() = runTest {
        listOf(RunPlacement.Local, RunPlacement.Remote).forEach { placement ->
            val fixture = fixture("run-stop-${placement.name.lowercase()}", placement)
            val started = CountDownLatch(1)
            val stopped = CompletableDeferred<Unit>()
            var localStarts = 0
            var remoteStarts = 0
            var localStops = 0
            var remoteStops = 0
            val adapters = ModelRuntimeAdapters(
                local = {
                    localStarts++
                    started.countDown()
                    TestRunningCall(
                        awaitBlock = { stopped.await() },
                        stopBlock = {
                            localStops++
                            stopped.complete(Unit)
                        },
                    )
                },
                remote = {
                    remoteStarts++
                    started.countDown()
                    TestRunningCall(
                        awaitBlock = { stopped.await() },
                        stopBlock = {
                            remoteStops++
                            stopped.complete(Unit)
                        },
                    )
                },
            )
            val dispatch = async(Dispatchers.Default) {
                fixture.dispatcher.dispatch(fixture.permit, testReceipt(placement), adapters)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))

            assertTrue(
                fixture.dispatcher.stop(
                    runId = fixture.permit.binding.runId,
                    state = AgentRunState.Cancelled,
                    updatedAtMillis = 2_000L,
                ),
            )
            dispatch.await()

            assertEquals(if (placement == RunPlacement.Local) 1 else 0, localStarts)
            assertEquals(if (placement == RunPlacement.Remote) 1 else 0, remoteStarts)
            assertEquals(if (placement == RunPlacement.Local) 1 else 0, localStops)
            assertEquals(if (placement == RunPlacement.Remote) 1 else 0, remoteStops)
            assertEquals("Cancelled", fixture.dao.run(fixture.permit.binding.runId)?.state)
            assertEquals("Terminal", fixture.dao.binding(fixture.permit.binding.runId)?.dispatchState)
        }
    }

    @Test
    fun runtimeAwaitAndStopHandleExecuteOutsidePlacementLocks() = runTest {
        val fixture = fixture("run-lock-free-handle", RunPlacement.Local)
        val awaitEntered = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        val releaseAwait = CountDownLatch(1)
        val reentryExecutor = Executors.newSingleThreadExecutor()
        val dispatch = async(Dispatchers.Default) {
            fixture.dispatcher.dispatch(
                fixture.permit,
                testReceipt(RunPlacement.Local),
                ModelRuntimeAdapters(
                    local = {
                        TestRunningCall(
                            awaitBlock = {
                                awaitEntered.countDown()
                                check(releaseAwait.await(5, TimeUnit.SECONDS))
                            },
                            stopBlock = {
                                val reentry = reentryExecutor.submit<TerminalizeRunResult> {
                                    fixture.store.terminalizeRun(
                                        "run-lock-free-handle",
                                        AgentRunState.Cancelled,
                                        3_000L,
                                    )
                                }
                                assertTrue(reentry.get(5, TimeUnit.SECONDS) is TerminalizeRunResult.Terminalized)
                                stopEntered.countDown()
                                releaseAwait.countDown()
                            },
                        )
                    },
                    remote = { error("remote must not start") },
                ),
            )
        }
        try {
            assertTrue(awaitEntered.await(5, TimeUnit.SECONDS))
            val stop = async(Dispatchers.Default) {
                fixture.dispatcher.stop(
                    "run-lock-free-handle",
                    AgentRunState.Cancelled,
                    2_000L,
                )
            }
            assertTrue(stopEntered.await(5, TimeUnit.SECONDS))
            assertTrue(stop.await())
        } finally {
            releaseAwait.countDown()
            reentryExecutor.shutdownNow()
        }

        dispatch.await()
        assertEquals("Terminal", fixture.dao.binding("run-lock-free-handle")?.dispatchState)
    }

    @Test
    fun stopBlocksUntilSynchronousStartPublishesTheExactHandle() = runTest {
        val fixture = fixture("run-start-wins", RunPlacement.Local)
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val startReturned = AtomicBoolean(false)
        val stopped = CompletableDeferred<Unit>()
        val stopCount = AtomicInteger(0)
        val stopResult = AtomicReference<Boolean>()
        val stopFailure = AtomicReference<Throwable>()
        val sentinel = TestRunningCall(
            awaitBlock = { stopped.await() },
            stopBlock = {
                assertTrue(startReturned.get())
                stopCount.incrementAndGet()
                stopped.complete(Unit)
            },
        )
        val dispatch = async(Dispatchers.Default) {
            fixture.dispatcher.dispatch(
                fixture.permit,
                testReceipt(RunPlacement.Local),
                ModelRuntimeAdapters(
                    local = {
                        startEntered.countDown()
                        check(releaseStart.await(5, TimeUnit.SECONDS))
                        startReturned.set(true)
                        sentinel
                    },
                    remote = { error("remote must not start") },
                ),
            )
        }
        assertTrue(startEntered.await(5, TimeUnit.SECONDS))

        val stopThread = Thread({
            runCatching {
                fixture.dispatcher.stop("run-start-wins", AgentRunState.Cancelled, 2_000L)
            }.onSuccess(stopResult::set).onFailure(stopFailure::set)
        }, "run-start-wins-stop")
        try {
            stopThread.start()
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (
                stopThread.state != Thread.State.BLOCKED &&
                stopThread.isAlive &&
                System.nanoTime() < deadlineNanos
            ) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, stopThread.state)
        } finally {
            releaseStart.countDown()
            stopThread.join(TimeUnit.SECONDS.toMillis(5))
        }

        stopFailure.get()?.let { throw AssertionError("stop failed", it) }
        assertFalse(stopThread.isAlive)
        assertTrue(stopResult.get())
        dispatch.await()
        assertEquals(1, stopCount.get())
        assertEquals("Terminal", fixture.dao.binding("run-start-wins")?.dispatchState)
    }

    @Test
    fun cancellingDispatchStopsTheRunningHandleExactlyOnceThenFinishes() = runTest {
        val fixture = fixture("run-cancel-running", RunPlacement.Local)
        val started = CountDownLatch(1)
        val stopCount = AtomicInteger(0)
        val dispatch = async(Dispatchers.Default) {
            fixture.dispatcher.dispatch(
                fixture.permit,
                testReceipt(RunPlacement.Local),
                ModelRuntimeAdapters(
                    local = {
                        started.countDown()
                        TestRunningCall(
                            awaitBlock = { awaitCancellation() },
                            stopBlock = {
                                assertEquals("Started", fixture.dao.binding("run-cancel-running")?.dispatchState)
                                stopCount.incrementAndGet()
                            },
                        )
                    },
                    remote = { error("remote must not start") },
                ),
            )
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        dispatch.cancel()
        assertTrue(runCatching { dispatch.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(1, stopCount.get())
        assertEquals("Idle", fixture.dao.binding("run-cancel-running")?.dispatchState)

        assertTrue(
            fixture.dispatcher.stop(
                "run-cancel-running",
                AgentRunState.Cancelled,
                2_000L,
            ),
        )
        assertEquals(1, stopCount.get())
        assertEquals("Terminal", fixture.dao.binding("run-cancel-running")?.dispatchState)
    }

    @Test
    fun concurrentCancellationAndExplicitStopStopTheSameHandleExactlyOnce() = runTest {
        val fixture = fixture("run-cancel-stop-race", RunPlacement.Local)
        val started = CountDownLatch(1)
        val stopCount = AtomicInteger(0)
        val dispatch = async(Dispatchers.Default) {
            fixture.dispatcher.dispatch(
                fixture.permit,
                testReceipt(RunPlacement.Local),
                ModelRuntimeAdapters(
                    local = {
                        started.countDown()
                        TestRunningCall(
                            awaitBlock = { awaitCancellation() },
                            stopBlock = { stopCount.incrementAndGet() },
                        )
                    },
                    remote = { error("remote must not start") },
                ),
            )
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val racersReady = CountDownLatch(2)
        val race = CountDownLatch(1)
        val cancellation = async(Dispatchers.Default) {
            racersReady.countDown()
            check(race.await(5, TimeUnit.SECONDS))
            dispatch.cancel()
            runCatching { dispatch.await() }.exceptionOrNull()
        }
        val explicitStop = async(Dispatchers.Default) {
            racersReady.countDown()
            check(race.await(5, TimeUnit.SECONDS))
            fixture.dispatcher.stop("run-cancel-stop-race", AgentRunState.Cancelled, 2_000L)
        }
        assertTrue(racersReady.await(5, TimeUnit.SECONDS))
        race.countDown()

        assertTrue(cancellation.await() is kotlinx.coroutines.CancellationException)
        assertTrue(explicitStop.await())
        assertEquals(1, stopCount.get())
        assertEquals("Terminal", fixture.dao.binding("run-cancel-stop-race")?.dispatchState)
    }

    @Test
    fun synchronousStartFailureFinishesClaimAndPermitsRetry() = runTest {
        val fixture = fixture("run-start-failure", RunPlacement.Local)
        val registrationFailure = IllegalStateException("runtime registration failed")

        val result = runCatching {
            fixture.dispatcher.dispatch(
                fixture.permit,
                testReceipt(RunPlacement.Local),
                ModelRuntimeAdapters<Unit>(
                    local = { throw registrationFailure },
                    remote = { error("remote must not start") },
                ),
            )
        }

        assertSame(registrationFailure, result.exceptionOrNull())
        assertEquals("Idle", fixture.dao.binding("run-start-failure")?.dispatchState)
        assertTrue(fixture.permit.entry.runtimeEntry === RuntimeInvocationEntry.Ready)

        var retryStarts = 0
        fixture.dispatcher.dispatch(
            fixture.permit,
            testReceipt(RunPlacement.Local),
            testRuntimeAdapters(
                local = { retryStarts++ },
                remote = { error("remote must not start") },
            ),
        )
        assertEquals(1, retryStarts)
        assertEquals(2, fixture.dao.binding("run-start-failure")?.attempt)
    }

    @Test
    fun synchronousStartFailureSuppressesFinishCasFailure() = runTest {
        val fixture = fixture("run-start-finish-failure", RunPlacement.Local)
        fixture.dao.failFinish = true
        val registrationFailure = IllegalStateException("runtime registration failed")

        val result = runCatching {
            fixture.dispatcher.dispatch(
                fixture.permit,
                testReceipt(RunPlacement.Local),
                ModelRuntimeAdapters<Unit>(
                    local = { throw registrationFailure },
                    remote = { error("remote must not start") },
                ),
            )
        }

        assertSame(registrationFailure, result.exceptionOrNull())
        assertEquals(1, registrationFailure.suppressed.size)
        assertTrue(registrationFailure.suppressed.single() is PlacementDispatchException)
        assertEquals("Started", fixture.dao.binding("run-start-finish-failure")?.dispatchState)
    }

    @Test
    fun stopWithoutBindingTerminalizesAndTerminalStateMismatchIsRejected() {
        val dao = FakeRunPlacementBindingDao()
        val store = RoomRunPlacementBindingStore(
            dao = dao,
            currentRecoveryContext = {
                RunPlacementRecoveryContext(bootCount = 7L, elapsedRealtimeMillis = 2_000L)
            },
        )
        assertTrue(store.createCriticalRun(AgentRun("run-pre-bind-stop", "secret", AgentRunState.Created, 1L, 1L)))
        val dispatcher = ModelRuntimeDispatcher(store)
        assertTrue(dispatcher.stop("run-pre-bind-stop", AgentRunState.Cancelled, 2_000L))
        assertFalse(dispatcher.stop("run-pre-bind-stop", AgentRunState.Completed, 3_000L))
        assertEquals("Cancelled", dao.run("run-pre-bind-stop")?.state)
    }

    @Test
    fun stopAfterClaimButBeforeRuntimeEntryPreventsTheAdapterForever() = runTest {
        val fixture = fixture("run-stop-before-entry", RunPlacement.Local)
        val claimed = CountDownLatch(1)
        val releaseClaim = CountDownLatch(1)
        val dispatcher = ModelRuntimeDispatcher(
            ClaimBarrierBindingStore(
                delegate = fixture.store,
                claimed = claimed,
                releaseClaim = releaseClaim,
            ),
        )
        var adapterStarts = 0
        val dispatch = async(Dispatchers.Default) {
            runCatching {
                dispatcher.dispatch(
                    fixture.permit,
                    testReceipt(RunPlacement.Local),
                    ModelRuntimeAdapters(
                        local = {
                            adapterStarts++
                            TestRunningCall(awaitBlock = { Unit })
                        },
                        remote = {
                            adapterStarts++
                            TestRunningCall(awaitBlock = { Unit })
                        },
                    ),
                )
            }
        }
        try {
            assertTrue(claimed.await(5, TimeUnit.SECONDS))
            assertTrue(
                dispatcher.stop(
                    runId = "run-stop-before-entry",
                    state = AgentRunState.Cancelled,
                    updatedAtMillis = 2_000L,
                ),
            )
        } finally {
            releaseClaim.countDown()
        }

        assertTrue(dispatch.await().isFailure)
        assertEquals(0, adapterStarts)
        assertEquals("Terminal", fixture.dao.binding("run-stop-before-entry")?.dispatchState)
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
                    testRuntimeAdapters(local = { calls++ }, remote = { calls++ }),
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
                    testRuntimeAdapters(local = { calls++ }, remote = { calls++ }),
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
            testRuntimeAdapters(
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

    private fun <T> testRuntimeAdapters(
        local: suspend (ModelRuntimeInvocation) -> T,
        remote: suspend (ModelRuntimeInvocation) -> T,
    ): ModelRuntimeAdapters<T> = ModelRuntimeAdapters(
        local = { invocation -> TestRunningCall(awaitBlock = { local(invocation) }) },
        remote = { invocation -> TestRunningCall(awaitBlock = { remote(invocation) }) },
    )

    private class TestRunningCall<T>(
        private val awaitBlock: suspend () -> T,
        private val stopBlock: () -> Unit = {},
    ) : RunningModelRuntimeCall<T> {
        override suspend fun await(): T = awaitBlock()

        override fun stop() = stopBlock()
    }

    private class ClaimBarrierBindingStore(
        private val delegate: RunPlacementBindingStore,
        private val claimed: CountDownLatch,
        private val releaseClaim: CountDownLatch,
    ) : RunPlacementBindingStore by delegate {
        override fun claimForDispatch(
            permit: ActiveRunPlacementPermit,
            receipt: RunDataReceipt,
        ): ClaimInvocationResult {
            val result = delegate.claimForDispatch(permit, receipt)
            if (result is ClaimInvocationResult.Started) {
                claimed.countDown()
                check(releaseClaim.await(5, TimeUnit.SECONDS))
            }
            return result
        }
    }
}
