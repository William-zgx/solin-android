package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.data.PendingAgentConfirmationEntity
import com.bytedance.zgx.solin.presentation.BoundGenerationAttemptResolver
import com.bytedance.zgx.solin.presentation.BoundGenerationStopResolution
import com.bytedance.zgx.solin.presentation.BoundGenerationStopResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunPlacementRecoveryTest {
    @Test
    fun preferenceChangeDoesNotAlterBindingAndRetryUsesAttemptsOneThenTwo() = runTest {
        val fixture = fixture("run-bound-retry", RunPlacement.Remote, InferenceMode.Remote)
        var nextRunPreference = InferenceMode.Remote
        val localInvocations = mutableListOf<ModelRuntimeInvocation>()
        val remoteInvocations = mutableListOf<ModelRuntimeInvocation>()
        val adapters = ModelRuntimeAdapters(
            local = { invocation ->
                localInvocations += invocation
                TestRuntimeCall(awaitBlock = { Unit })
            },
            remote = { invocation ->
                remoteInvocations += invocation
                TestRuntimeCall(awaitBlock = { Unit })
            },
        )

        fixture.dispatcher.dispatch(
            fixture.resolveAttempt(remoteEligiblePrivacyPlan()),
            testReceipt(RunPlacement.Remote),
            adapters,
        )
        nextRunPreference = InferenceMode.Local
        fixture.dispatcher.dispatch(
            fixture.resolveAttempt(remoteEligiblePrivacyPlan()),
            testReceipt(RunPlacement.Remote),
            adapters,
        )

        assertEquals(InferenceMode.Local, nextRunPreference)
        assertEquals(InferenceMode.Remote, fixture.permit.binding.preference)
        assertEquals(RunPlacement.Remote, fixture.permit.binding.placement)
        assertEquals(emptyList<ModelRuntimeInvocation>(), localInvocations)
        assertEquals(listOf(1, 2), remoteInvocations.map { invocation -> invocation.attempt })
        assertEquals(
            setOf(RunPlacement.Remote),
            remoteInvocations.map { invocation -> invocation.placement }.toSet(),
        )
    }

    @Test
    fun missingCorruptAndStaleBindingsFailClosedWithoutRuntimeInvocation() {
        val missingDao = FakeRunPlacementBindingDao()
        val missingStore = store(missingDao)
        assertRecoveryRejected(
            RunPlacementRecoveryRejection.MissingBinding,
            missingStore,
            "run-missing-binding",
            recoveryContext(),
        )

        val corrupt = fixture("run-corrupt-binding", RunPlacement.Remote)
        corrupt.dao.mutateBinding("run-corrupt-binding") { entity ->
            entity.copy(schemaVersion = RUN_PLACEMENT_BINDING_SCHEMA_VERSION + 1)
        }
        assertRecoveryRejected(
            RunPlacementRecoveryRejection.UnknownSchema,
            store(corrupt.dao),
            "run-corrupt-binding",
            recoveryContext(),
        )

        val stale = fixture("run-stale-binding", RunPlacement.Remote)
        assertRecoveryRejected(
            RunPlacementRecoveryRejection.Expired,
            store(stale.dao),
            "run-stale-binding",
            recoveryContext(
                elapsedRealtimeMillis = 1_000L + RUN_PLACEMENT_RECOVERY_MAX_AGE_MILLIS + 1L,
            ),
        )

        assertTrue(missingDao.steps("run-missing-binding").isEmpty())
        assertTrue(corrupt.dao.steps("run-corrupt-binding").none { step ->
            step.type == "ModelRuntimeInvocationStarted"
        })
        assertTrue(stale.dao.steps("run-stale-binding").none { step ->
            step.type == "ModelRuntimeInvocationStarted"
        })
    }

    @Test
    fun restoredPendingConfirmationActivatesOnlyTheDurablyValidatedBinding() = runTest {
        val first = fixture("run-confirmation-recovery", RunPlacement.Remote)
        first.dispatcher.dispatch(
            first.resolveAttempt(remoteEligiblePrivacyPlan()),
            testReceipt(RunPlacement.Remote),
            ModelRuntimeAdapters(
                local = { TestRuntimeCall(awaitBlock = { Unit }) },
                remote = { TestRuntimeCall(awaitBlock = { Unit }) },
            ),
        )
        makePendingConfirmationRecoveryEligible(first.dao, first.permit.binding.runId)
        val restarted = store(first.dao)

        val resolution = PendingConfirmationPlacementResolver.resolve(
            bindingStore = restarted,
            runId = first.permit.binding.runId,
            context = recoveryContext(),
        )

        val ready = resolution as PendingConfirmationPlacementResolution.Ready
        assertEquals(RunPlacement.Remote, ready.permit.binding.placement)
        assertEquals(1, ready.permit.binding.attempt)
        assertEquals(ready.permit, restarted.activeBinding(first.permit.binding.runId))
    }

    @Test
    fun restoredPendingConfirmationRejectsMissingCorruptAndExpiredBindings() {
        val missing = store(FakeRunPlacementBindingDao())
        assertPendingRecoveryBlocked(missing, "run-missing", recoveryContext())

        val corrupt = fixture("run-corrupt-confirmation", RunPlacement.Remote)
        corrupt.dao.mutateBinding(corrupt.permit.binding.runId) { entity ->
            entity.copy(schemaVersion = RUN_PLACEMENT_BINDING_SCHEMA_VERSION + 1)
        }
        assertPendingRecoveryBlocked(
            store(corrupt.dao),
            corrupt.permit.binding.runId,
            recoveryContext(),
        )

        val expired = fixture("run-expired-confirmation", RunPlacement.Remote)
        assertPendingRecoveryBlocked(
            store(expired.dao),
            expired.permit.binding.runId,
            recoveryContext(
                elapsedRealtimeMillis = 1_000L + RUN_PLACEMENT_RECOVERY_MAX_AGE_MILLIS + 1L,
            ),
        )
    }

    @Test
    fun stopUsesTheRunningHandleRegisteredByBindingAfterPreferenceChanges() = runTest {
        val fixture = fixture("run-bound-stop", RunPlacement.Remote, InferenceMode.Remote)
        var nextRunPreference = InferenceMode.Remote
        val remoteStarted = CountDownLatch(1)
        val stopped = CompletableDeferred<Unit>()
        val localStarts = AtomicInteger(0)
        val remoteStarts = AtomicInteger(0)
        val localStops = AtomicInteger(0)
        val remoteStops = AtomicInteger(0)
        val dispatch = async(Dispatchers.Default) {
            fixture.dispatcher.dispatch(
                fixture.permit,
                testReceipt(RunPlacement.Remote),
                ModelRuntimeAdapters(
                    local = {
                        localStarts.incrementAndGet()
                        TestRuntimeCall(
                            awaitBlock = { stopped.await() },
                            stopBlock = { localStops.incrementAndGet() },
                        )
                    },
                    remote = {
                        remoteStarts.incrementAndGet()
                        remoteStarted.countDown()
                        TestRuntimeCall(
                            awaitBlock = { stopped.await() },
                            stopBlock = {
                                remoteStops.incrementAndGet()
                                stopped.complete(Unit)
                            },
                        )
                    },
                ),
            )
        }
        assertTrue(remoteStarted.await(5, TimeUnit.SECONDS))

        nextRunPreference = InferenceMode.Local
        val resolution = BoundGenerationStopResolver.resolve(
            bindingStore = fixture.store,
            runId = fixture.permit.binding.runId,
            state = AgentRunState.Cancelled,
            updatedAtMillis = 3_000L,
        )
        val resolved = resolution as BoundGenerationStopResolution.Resolved
        assertEquals(RunPlacement.Remote, resolved.placement)
        resolved.stopHandle?.stop()
        dispatch.await()

        assertEquals(InferenceMode.Local, nextRunPreference)
        assertEquals(RunPlacement.Remote, fixture.permit.binding.placement)
        assertEquals(0, localStarts.get())
        assertEquals(1, remoteStarts.get())
        assertEquals(0, localStops.get())
        assertEquals(1, remoteStops.get())
    }

    private fun fixture(
        runId: String,
        placement: RunPlacement,
        preference: InferenceMode = InferenceMode.Auto,
    ): Fixture {
        val dao = FakeRunPlacementBindingDao()
        val store = store(dao)
        check(
            store.createCriticalRun(
                AgentRun(runId, "must not persist", AgentRunState.Created, 1L, 1L),
            ),
        )
        val permit = (store.bindAndReserve(
            testBinding(runId, placement).copy(preference = preference),
        ) as BindAndReserveResult.Bound).permit
        return Fixture(dao, store, permit, ModelRuntimeDispatcher(store))
    }

    private fun store(
        dao: FakeRunPlacementBindingDao,
        context: RunPlacementRecoveryContext = recoveryContext(),
    ): RoomRunPlacementBindingStore = RoomRunPlacementBindingStore(
        dao = dao,
        currentRecoveryContext = { context },
        traceClockMillis = { 9_000L },
    )

    private fun recoveryContext(
        elapsedRealtimeMillis: Long = 2_000L,
    ): RunPlacementRecoveryContext = RunPlacementRecoveryContext(
        bootCount = 7L,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        remoteProfileRevision = TEST_REMOTE_REVISION,
    )

    private fun assertRecoveryRejected(
        expected: RunPlacementRecoveryRejection,
        store: RoomRunPlacementBindingStore,
        runId: String,
        context: RunPlacementRecoveryContext,
    ) {
        val inspection = store.inspectForRecovery(runId, context)

        assertEquals(expected, (inspection as RecoveryInspection.NotRestorable).rejection)
        assertEquals(PlacementReasonCode.PLACEMENT_NOT_RESTORABLE, inspection.reason)
        assertNull(store.activeBinding(runId))
    }

    private fun assertPendingRecoveryBlocked(
        store: RoomRunPlacementBindingStore,
        runId: String,
        context: RunPlacementRecoveryContext,
    ) {
        val resolution = PendingConfirmationPlacementResolver.resolve(store, runId, context)

        assertTrue(resolution is PendingConfirmationPlacementResolution.Blocked)
        assertNull(store.activeBinding(runId))
    }

    private fun makePendingConfirmationRecoveryEligible(
        dao: FakeRunPlacementBindingDao,
        runId: String,
    ) {
        dao.mutateRun(runId) { run ->
            run.copy(state = AgentRunState.AwaitingUserConfirmation.name, updatedAtMillis = 2_000L)
        }
        dao.setPendingConfirmation(
            PendingAgentConfirmationEntity(
                runId = runId,
                requestId = "request-$runId",
                toolName = "device.open_settings",
                argumentsJson = "{}",
                reason = "[redacted]",
                draftFunctionName = "device.open_settings",
                draftTitle = "[redacted]",
                draftSummary = "[redacted]",
                draftParametersJson = "{}",
                skillId = null,
                skillPlanJson = null,
                plannedByModel = true,
                fallbackReason = null,
                nextActionInput = null,
                continuationCursorJson = null,
                createdAtMillis = 1_000L,
                updatedAtMillis = 2_000L,
            ),
        )
    }

    private fun remoteEligiblePrivacyPlan(): PromptPrivacyPlan = PromptPrivacyPlanner.build(
        listOf(
            PromptPrivacySegment(
                source = PromptSegmentSource.CurrentInput,
                privacy = MessagePrivacy.RemoteEligible,
                requiresLocalModel = false,
            ),
        ),
    )

    private data class Fixture(
        val dao: FakeRunPlacementBindingDao,
        val store: RoomRunPlacementBindingStore,
        val permit: ActiveRunPlacementPermit,
        val dispatcher: ModelRuntimeDispatcher,
    ) {
        fun resolveAttempt(privacyPlan: PromptPrivacyPlan): ActiveRunPlacementPermit {
            val resolution = BoundGenerationAttemptResolver.resolve(
                bindingStore = store,
                runId = permit.binding.runId,
                privacyPlan = privacyPlan,
            )
            return (resolution as BoundRunContinuationResolution.Dispatch).permit
        }
    }

    private class TestRuntimeCall<T>(
        private val awaitBlock: suspend () -> T,
        private val stopBlock: () -> Unit = {},
    ) : RunningModelRuntimeCall<T> {
        override suspend fun await(): T = awaitBlock()

        override fun stop() = stopBlock()
    }
}
