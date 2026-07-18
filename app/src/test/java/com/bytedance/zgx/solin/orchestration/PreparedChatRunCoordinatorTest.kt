package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatImageAttachment
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.LocalImageAttachment
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.resource.StableResourceBand
import com.bytedance.zgx.solin.resource.StableResourceState
import com.bytedance.zgx.solin.resource.ThermalPressure
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedChatRunCoordinatorTest {
    @Test
    fun blockedCallsPolicyOnceWithoutBindingOrDispatch() = runTest {
        val fixture = fixture(blockedDecision())

        val result = fixture.coordinator.prepare(request())

        assertTrue(result is PrepareChatRunResult.Blocked)
        assertEquals(1, fixture.policyCalls.get())
        assertEquals(0, fixture.bindings.size)
        assertEquals(0, fixture.dispatched.size)
    }

    @Test
    fun rejectedBindingCreatesNeitherPreparedRunNorPendingEntry() = runTest {
        val fixture = fixture(chosen(RunPlacement.Remote), bindSucceeds = false)

        assertEquals(PrepareChatRunResult.BindingRejected, fixture.coordinator.prepare(request()))
        assertEquals(1, fixture.bindings.size)
        assertEquals(0, fixture.dispatched.size)
        assertEquals(0, fixture.coordinator.pendingDisclosureCount())
    }

    @Test
    fun localDispatchesTheSameFrozenPreparedRunImmediately() = runTest {
        val history = mutableListOf(ChatMessage(MessageRole.User, "private history"))
        val localBytes = byteArrayOf(1, 2, 3)
        val fixture = fixture(chosen(RunPlacement.Local, preference = InferenceMode.Auto))
        val input = request(
            preference = InferenceMode.Auto,
            history = history,
            localImages = listOf(LocalImageAttachment("image/png", localBytes)),
        )

        val result = fixture.coordinator.prepare(input) as PrepareChatRunResult.Ready
        history.clear()
        localBytes[0] = 9

        assertSame(result.prepared, fixture.dispatched.single())
        assertSame(result.prepared.binding, result.prepared.permit.binding)
        assertEquals(RunPlacement.Local, result.prepared.placement)
        assertEquals(InferenceMode.Auto, result.prepared.preference)
        assertEquals("private history", result.prepared.history.single().text)
        assertEquals(1, result.prepared.localImageAttachments.single().bytes[0].toInt())
    }

    @Test
    fun remoteDisclosureIsConsumedOnceAndConcurrentConfirmHasSingleWinner() = runTest {
        val fixture = fixture(chosen(RunPlacement.Remote))
        val result = fixture.coordinator.prepare(request()) as PrepareChatRunResult.AwaitingDisclosure
        assertFalse(fixture.coordinator.confirmAndDispatch(result.runId, REVISION_2))
        assertEquals(1, fixture.coordinator.pendingDisclosureCount())
        val gate = CountDownLatch(1)
        val winners = List(2) {
            async(Dispatchers.Default) {
                gate.await()
                fixture.coordinator.confirmAndDispatch(result.runId, result.expectedConfigRevision)
            }
        }

        gate.countDown()
        assertEquals(1, winners.awaitAll().count { it })
        assertEquals(1, fixture.policyCalls.get())
        assertEquals(1, fixture.dispatched.size)
        assertEquals(0, fixture.coordinator.pendingDisclosureCount())
    }

    @Test
    fun wrongRunCancelTeardownAndRevisionChangeNeverDispatch() = runTest {
        val fixture = fixture(chosen(RunPlacement.Remote))
        val first = fixture.coordinator.prepare(request(runId = "run-1")) as PrepareChatRunResult.AwaitingDisclosure
        assertFalse(fixture.coordinator.confirmAndDispatch("wrong", first.expectedConfigRevision))
        fixture.coordinator.cancel("run-1")
        assertFalse(fixture.coordinator.confirmAndDispatch(first.runId, first.expectedConfigRevision))

        fixture.coordinator.prepare(request(runId = "run-2", sessionId = "session-a"))
        fixture.coordinator.teardownSession("session-a")
        assertFalse(fixture.coordinator.confirmAndDispatch("run-2", REVISION_1))

        fixture.coordinator.prepare(request(runId = "run-3"))
        fixture.currentRevision = REVISION_2
        assertFalse(fixture.coordinator.confirmAndDispatch("run-3", REVISION_1))
        assertEquals(0, fixture.dispatched.size)
        assertEquals(0, fixture.coordinator.pendingDisclosureCount())
        assertTrue(fixture.activePermits.isEmpty())
    }

    @Test
    fun duplicatePendingAndDispatchFailureAbortTheNewReservation() = runTest {
        val duplicate = fixture(chosen(RunPlacement.Remote))
        duplicate.coordinator.prepare(request(runId = "duplicate"))

        assertEquals(
            PrepareChatRunResult.BindingRejected,
            duplicate.coordinator.prepare(request(runId = "duplicate")),
        )
        assertEquals(1, duplicate.aborted.size)
        assertEquals(1, duplicate.activePermits.size)

        val dispatchFailure = fixture(chosen(RunPlacement.Local), dispatchThrows = true)
        assertEquals(
            PrepareChatRunResult.DispatchRejected,
            dispatchFailure.coordinator.prepare(request(runId = "dispatch-failure")),
        )
        assertEquals(1, dispatchFailure.aborted.size)
        assertTrue(dispatchFailure.activePermits.isEmpty())
    }

    @Test
    fun preparedRunRedactsSensitiveTextAndKeepsChosenTargetAfterExternalPreferenceChange() = runTest {
        val fixture = fixture(chosen(RunPlacement.Local))
        val result = fixture.coordinator.prepare(
            request(prompt = "raw secret prompt", history = listOf(ChatMessage(MessageRole.User, "raw history"))),
        ) as PrepareChatRunResult.Ready
        fixture.nextDecision = chosen(RunPlacement.Remote, preference = InferenceMode.Remote)

        assertEquals(RunPlacement.Local, result.prepared.placement)
        assertFalse(result.prepared.toString().contains("raw secret prompt"))
        assertFalse(result.prepared.toString().contains("raw history"))
        assertFalse(result.prepared.toString().contains("endpoint"))
        assertFalse(result.prepared.toString().contains("apiKey"))
        assertTrue(
            PreparedChatRun::class.java.declaredFields.none { field ->
                field.name in setOf("remoteEndpoint", "remoteModelName", "remoteApiKey")
            },
        )
        assertFalse(request(prompt = "raw secret prompt").toString().contains("raw secret prompt"))
    }

    @Test
    fun cancelWhileBindingInvalidatesPrepareWithoutPreventingRunIdReuse() = runTest {
        val bindStarted = CountDownLatch(1)
        val releaseBind = CountDownLatch(1)
        val fixture = fixture(
            initialDecision = chosen(RunPlacement.Remote),
            bindStarted = bindStarted,
            releaseBind = releaseBind,
        )
        val preparing = async(Dispatchers.Default) {
            fixture.coordinator.prepare(request(runId = "reusable"))
        }
        assertTrue(bindStarted.await(5, TimeUnit.SECONDS))

        fixture.coordinator.cancel("reusable")
        releaseBind.countDown()

        assertEquals(PrepareChatRunResult.BindingRejected, preparing.await())
        assertEquals(0, fixture.coordinator.pendingDisclosureCount())
        assertEquals(0, fixture.dispatched.size)
        assertEquals(1, fixture.aborted.size)

        assertTrue(
            fixture.coordinator.prepare(request(runId = "reusable")) is
                PrepareChatRunResult.AwaitingDisclosure,
        )
    }

    @Test
    fun teardownWhileBindingInvalidatesPrepareBeforePublishOrDispatch() = runTest {
        val bindStarted = CountDownLatch(1)
        val releaseBind = CountDownLatch(1)
        val fixture = fixture(
            initialDecision = chosen(RunPlacement.Local),
            bindStarted = bindStarted,
            releaseBind = releaseBind,
        )
        val preparing = async(Dispatchers.Default) {
            fixture.coordinator.prepare(request(runId = "run", sessionId = "closing-session"))
        }
        assertTrue(bindStarted.await(5, TimeUnit.SECONDS))

        fixture.coordinator.teardownSession("closing-session")
        releaseBind.countDown()

        assertEquals(PrepareChatRunResult.BindingRejected, preparing.await())
        assertEquals(0, fixture.coordinator.pendingDisclosureCount())
        assertEquals(0, fixture.dispatched.size)
        assertEquals(1, fixture.aborted.size)
    }

    @Test
    fun cancelBeforeDispatchHandoffPreventsLocalDispatcherEntry() = runTest {
        val dispatchReady = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val fixture = fixture(
            initialDecision = chosen(RunPlacement.Local),
            dispatchReady = dispatchReady,
            releaseDispatch = releaseDispatch,
        )
        val preparing = async(Dispatchers.Default) {
            fixture.coordinator.prepare(request(runId = "cancel-before-dispatch"))
        }
        assertTrue(dispatchReady.await(5, TimeUnit.SECONDS))

        fixture.coordinator.cancel("cancel-before-dispatch")
        releaseDispatch.countDown()

        assertEquals(PrepareChatRunResult.BindingRejected, preparing.await())
        assertEquals(0, fixture.dispatched.size)
        assertEquals(1, fixture.aborted.size)
    }

    @Test
    fun teardownBeforeDispatchHandoffPreventsLocalDispatcherEntry() = runTest {
        val dispatchReady = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val fixture = fixture(
            initialDecision = chosen(RunPlacement.Local),
            dispatchReady = dispatchReady,
            releaseDispatch = releaseDispatch,
        )
        val preparing = async(Dispatchers.Default) {
            fixture.coordinator.prepare(request(runId = "run", sessionId = "teardown-before-dispatch"))
        }
        assertTrue(dispatchReady.await(5, TimeUnit.SECONDS))

        fixture.coordinator.teardownSession("teardown-before-dispatch")
        releaseDispatch.countDown()

        assertEquals(PrepareChatRunResult.BindingRejected, preparing.await())
        assertEquals(0, fixture.dispatched.size)
        assertEquals(1, fixture.aborted.size)
    }

    @Test
    fun dispatcherCancellationAbortsPermitAndPropagates() = runTest {
        val fixture = fixture(
            initialDecision = chosen(RunPlacement.Local),
            dispatchCancels = true,
        )
        var cancellationObserved = false

        try {
            fixture.coordinator.prepare(request(runId = "cancelled-dispatch"))
        } catch (_: CancellationException) {
            cancellationObserved = true
        }

        assertTrue(cancellationObserved)
        assertEquals(1, fixture.aborted.size)
        assertTrue(fixture.activePermits.isEmpty())
    }

    @Test
    fun dispatcherSynchronousPrefixDoesNotHoldLifecycleLock() = runTest {
        val dispatcherEntered = CountDownLatch(1)
        val cancelReturned = CountDownLatch(1)
        val fixture = fixture(
            initialDecision = chosen(RunPlacement.Local),
            dispatcherEntered = dispatcherEntered,
            waitForCancelReturned = cancelReturned,
        )
        val preparing = async(Dispatchers.Default) {
            fixture.coordinator.prepare(request(runId = "dispatcher-lock"))
        }
        assertTrue(dispatcherEntered.await(5, TimeUnit.SECONDS))

        val cancelling = async(Dispatchers.Default) {
            fixture.coordinator.cancel("dispatcher-lock")
            cancelReturned.countDown()
        }

        assertTrue(preparing.await() is PrepareChatRunResult.Ready)
        cancelling.await()
        assertEquals(1, fixture.dispatched.size)
        assertEquals(0, fixture.aborted.size)
        assertEquals(1, fixture.stopped.get())
    }

    @Test
    fun callerCancellationStopsRunningCallExactlyOnce() = runTest {
        val awaitStarted = CountDownLatch(1)
        val fixture = fixture(
            initialDecision = chosen(RunPlacement.Local),
            awaitStarted = awaitStarted,
            awaitUntilCancelled = true,
        )
        val preparing = launch(Dispatchers.Default) {
            fixture.coordinator.prepare(request(runId = "caller-cancel", sessionId = "cancel-session"))
        }
        assertTrue(awaitStarted.await(5, TimeUnit.SECONDS))

        preparing.cancelAndJoin()
        fixture.coordinator.cancel("caller-cancel")
        fixture.coordinator.teardownSession("cancel-session")

        assertEquals(1, fixture.stopped.get())
        assertEquals(1, fixture.aborted.size)
        assertEquals(listOf("stop", "abort"), fixture.lifecycleEvents)
        assertTrue(fixture.activePermits.isEmpty())
    }

    @Test
    fun concurrentCancelAndTeardownStopRunningCallExactlyOnce() = runTest {
        val awaitStarted = CountDownLatch(1)
        val fixture = fixture(
            initialDecision = chosen(RunPlacement.Local),
            awaitStarted = awaitStarted,
            awaitUntilCancelled = true,
        )
        val preparing = launch(Dispatchers.Default) {
            fixture.coordinator.prepare(request(runId = "concurrent-stop", sessionId = "stop-session"))
        }
        assertTrue(awaitStarted.await(5, TimeUnit.SECONDS))
        val gate = CountDownLatch(1)
        val cancellations = listOf(
            async(Dispatchers.Default) {
                gate.await()
                fixture.coordinator.cancel("concurrent-stop")
            },
            async(Dispatchers.Default) {
                gate.await()
                fixture.coordinator.teardownSession("stop-session")
            },
        )

        gate.countDown()
        cancellations.awaitAll()
        preparing.cancelAndJoin()

        assertEquals(1, fixture.stopped.get())
        assertEquals(1, fixture.aborted.size)
        assertEquals(listOf("stop", "abort"), fixture.lifecycleEvents)
        assertTrue(fixture.activePermits.isEmpty())
    }

    private fun fixture(
        initialDecision: PlacementDecision,
        bindSucceeds: Boolean = true,
        dispatchThrows: Boolean = false,
        dispatchCancels: Boolean = false,
        bindStarted: CountDownLatch? = null,
        releaseBind: CountDownLatch? = null,
        dispatchReady: CountDownLatch? = null,
        releaseDispatch: CountDownLatch? = null,
        dispatcherEntered: CountDownLatch? = null,
        waitForCancelReturned: CountDownLatch? = null,
        awaitStarted: CountDownLatch? = null,
        awaitUntilCancelled: Boolean = false,
    ): Fixture {
        val policyCalls = AtomicInteger()
        val bindings = CopyOnWriteArrayList<RunPlacementBinding>()
        val dispatched = CopyOnWriteArrayList<PreparedChatRun>()
        val activePermits = ConcurrentHashMap<String, ActiveRunPlacementPermit>()
        val aborted = CopyOnWriteArrayList<ActiveRunPlacementPermit>()
        val stopped = AtomicInteger()
        val lifecycleEvents = CopyOnWriteArrayList<String>()
        lateinit var fixture: Fixture
        val coordinator = PreparedChatRunCoordinator(
            policy = PreparedChatPlacementPolicy {
                policyCalls.incrementAndGet()
                fixture.nextDecision
            },
            bindingStore = object : PreparedChatBindingStore {
                override fun bindAndReserve(binding: RunPlacementBinding): ActiveRunPlacementPermit? {
                    bindings += binding
                    if (!bindSucceeds) return null
                    bindStarted?.countDown()
                    releaseBind?.await(5, TimeUnit.SECONDS)
                    return ActiveRunPlacementPermit.detached(binding).also { permit ->
                        activePermits[binding.runId + ":" + System.identityHashCode(permit)] = permit
                    }
                }

                override fun abort(permit: ActiveRunPlacementPermit) {
                    lifecycleEvents += "abort"
                    aborted += permit
                    activePermits.entries.removeIf { (_, active) -> active === permit }
                }
            },
            dispatcher = PreparedChatDispatcher { prepared ->
                dispatcherEntered?.countDown()
                check(waitForCancelReturned?.await(5, TimeUnit.SECONDS) != false) {
                    "cancel blocked on lifecycle lock"
                }
                if (dispatchThrows) error("dispatch failed")
                dispatched += prepared
                object : PreparedChatRunningCall {
                    override suspend fun awaitCompletion() {
                        awaitStarted?.countDown()
                        if (awaitUntilCancelled) awaitCancellation()
                        if (dispatchCancels) throw CancellationException("dispatch cancelled")
                    }

                    override fun stop() {
                        lifecycleEvents += "stop"
                        stopped.incrementAndGet()
                    }
                }
            },
            revisionValidator = PreparedChatRevisionValidator { revision -> fixture.currentRevision == revision },
            dispatchReadiness = PreparedChatDispatchReadiness {
                dispatchReady?.countDown()
                releaseDispatch?.await(5, TimeUnit.SECONDS)
            },
        )
        fixture = Fixture(
            coordinator = coordinator,
            policyCalls = policyCalls,
            bindings = bindings,
            dispatched = dispatched,
            activePermits = activePermits,
            aborted = aborted,
            stopped = stopped,
            lifecycleEvents = lifecycleEvents,
            nextDecision = initialDecision,
        )
        return fixture
    }

    private fun request(
        runId: String = "run-1",
        sessionId: String = "session-1",
        preference: InferenceMode = InferenceMode.Remote,
        prompt: String = "prompt",
        history: List<ChatMessage> = emptyList(),
        localImages: List<LocalImageAttachment> = emptyList(),
    ): PrepareChatRunRequest = PrepareChatRunRequest(
        runId = runId,
        sessionId = sessionId,
        preference = preference,
        privacyPlan = PromptPrivacyPlan(MessagePrivacy.RemoteEligible, false, 0),
        requirements = ModelRequirements(true, localImages.isNotEmpty(), false, 32, 64),
        complexity = RequestComplexity.Simple,
        resources = resources(),
        localCandidate = candidate(),
        remoteCandidate = candidate(remote = true),
        remoteConfigRevision = REVISION_1,
        prompt = prompt,
        history = history,
        imageAttachments = listOf(ChatImageAttachment("image/png", "data:image/png;base64,AA==")),
        localImageAttachments = localImages,
        generationParameters = GenerationParameters(),
        requiresRemoteDisclosure = true,
        bootCount = 1,
        boundAtElapsedRealtimeMillis = 10,
    )

    private fun chosen(
        placement: RunPlacement,
        preference: InferenceMode = InferenceMode.Remote,
    ): PlacementDecision.Chosen = PlacementDecision.Chosen(
        policyVersion = 1,
        preference = preference,
        primaryReason = if (placement == RunPlacement.Local) {
            PlacementReasonCode.AUTO_SIMPLE_LOCAL
        } else {
            PlacementReasonCode.USER_FORCED_REMOTE
        },
        diagnostics = diagnostics(),
        placement = placement,
    )

    private fun blockedDecision(): PlacementDecision.Blocked = PlacementDecision.Blocked(
        policyVersion = 1,
        preference = InferenceMode.Remote,
        primaryReason = PlacementReasonCode.NO_ELIGIBLE_TARGET,
        diagnostics = diagnostics(),
    )

    private fun diagnostics() = PlacementDiagnostics(
        complexity = RequestComplexity.Simple,
        resourceBand = StableResourceBand.Normal,
        stableLowMemory = false,
        latestLowMemory = false,
        localHardBlocked = false,
        thermalPressure = ThermalPressure.Normal,
        localState = CandidateState.Eligible,
        remoteState = CandidateState.Eligible,
    )

    private fun resources() = StableResourceState(
        band = StableResourceBand.Normal,
        stableLowMemory = false,
        latestLowMemory = false,
        localHardBlocked = false,
        thermalPressure = ThermalPressure.Normal,
    )

    private fun candidate(remote: Boolean = false) = ModelCandidateSnapshot(
        available = true,
        supportsText = true,
        supportsVision = true,
        supportsTools = true,
        contextWindowTokens = 4_096,
        configured = remote,
        authorized = remote,
        connectivityStatus = if (remote) RemoteModelConnectivityStatus.Reachable else RemoteModelConnectivityStatus.Unknown,
        connectivityFresh = remote,
        profileRevisionMatches = remote,
    )

    private data class Fixture(
        val coordinator: PreparedChatRunCoordinator,
        val policyCalls: AtomicInteger,
        val bindings: MutableList<RunPlacementBinding>,
        val dispatched: MutableList<PreparedChatRun>,
        val activePermits: MutableMap<String, ActiveRunPlacementPermit>,
        val aborted: MutableList<ActiveRunPlacementPermit>,
        val stopped: AtomicInteger,
        val lifecycleEvents: MutableList<String>,
        var nextDecision: PlacementDecision,
        var currentRevision: String = REVISION_1,
    )

    private companion object {
        const val REVISION_1 = "00000000-0000-0000-0000-000000000001"
        const val REVISION_2 = "00000000-0000-0000-0000-000000000002"
    }
}
