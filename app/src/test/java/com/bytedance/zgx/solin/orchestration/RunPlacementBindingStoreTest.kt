package com.bytedance.zgx.solin.orchestration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RunPlacementBindingStoreTest {
    @Test
    fun bindAndReserveIsAtomicAndConcurrentOppositeBindingsHaveOneWinner() = runTest {
        val dao = FakeRunPlacementBindingDao()
        val store = store(dao)
        assertTrue(store.createCriticalRun(testRun("run-race")))

        val results = listOf(
            async(Dispatchers.Default) { store.bindAndReserve(testBinding("run-race", RunPlacement.Local)) },
            async(Dispatchers.Default) { store.bindAndReserve(testBinding("run-race", RunPlacement.Remote)) },
        ).awaitAll()

        assertEquals(1, results.count { it is BindAndReserveResult.Bound })
        assertEquals(1, dao.steps("run-race").count { it.type == "PlacementSelected" })
        assertEquals("Pending", dao.binding("run-race")?.dispatchState)
        assertEquals(0, dao.binding("run-race")?.attempt)
        assertEquals(dao.binding("run-race")?.placement, store.activeBinding("run-race")?.binding?.placement?.name)
    }

    @Test
    fun foreignKeyOrPlacementTraceFailureRollsBackAndDoesNotActivate() {
        val dao = FakeRunPlacementBindingDao()
        val store = store(dao)

        assertTrue(store.bindAndReserve(testBinding("missing-run")) is BindAndReserveResult.Rejected)
        assertNull(dao.binding("missing-run"))
        assertNull(store.activeBinding("missing-run"))

        assertTrue(store.createCriticalRun(testRun("run-trace-failure")))
        dao.failStepType = "PlacementSelected"
        assertTrue(store.bindAndReserve(testBinding("run-trace-failure")) is BindAndReserveResult.Rejected)
        assertNull(dao.binding("run-trace-failure"))
        assertTrue(dao.steps("run-trace-failure").isEmpty())
        assertNull(store.activeBinding("run-trace-failure"))
    }

    @Test
    fun terminalRunCannotAcquireAPreServingBinding() {
        val dao = FakeRunPlacementBindingDao()
        val store = store(dao)
        assertTrue(store.createCriticalRun(testRun("run-stopped-before-bind")))
        assertTrue(
            store.terminalizeRun("run-stopped-before-bind", AgentRunState.Cancelled, 2_000L)
                is TerminalizeRunResult.Terminalized,
        )

        assertTrue(
            store.bindAndReserve(testBinding("run-stopped-before-bind")) is BindAndReserveResult.Rejected,
        )
        assertNull(dao.binding("run-stopped-before-bind"))
        assertNull(store.activeBinding("run-stopped-before-bind"))
    }

    @Test
    fun terminalizationInPostCommitGapPreventsStaleBindingPublication() = runTest {
        val dao = FakeRunPlacementBindingDao()
        val store = store(dao)
        assertTrue(store.createCriticalRun(testRun("run-bind-publish-race")))
        val committed = CountDownLatch(1)
        val releaseBind = CountDownLatch(1)
        dao.afterBindCommitted = {
            committed.countDown()
            check(releaseBind.await(5, TimeUnit.SECONDS))
        }

        val bind = async(Dispatchers.Default) {
            store.bindAndReserve(testBinding("run-bind-publish-race"))
        }
        try {
            assertTrue(committed.await(5, TimeUnit.SECONDS))
            val terminalized = async(Dispatchers.Default) {
                store.terminalizeRun(
                    "run-bind-publish-race",
                    AgentRunState.Cancelled,
                    2_000L,
                )
            }.await()
            assertTrue(terminalized is TerminalizeRunResult.Terminalized)
        } finally {
            releaseBind.countDown()
        }

        assertTrue(bind.await() is BindAndReserveResult.Rejected)
        assertNull(store.activeBinding("run-bind-publish-race"))
        assertEquals("Cancelled", dao.run("run-bind-publish-race")?.state)
        assertEquals("Terminal", dao.binding("run-bind-publish-race")?.dispatchState)
    }

    @Test
    fun terminalizationDuringActivationCannotLeaveAnIdlePublishedPermit() = runTest {
        val dao = FakeRunPlacementBindingDao()
        val first = store(dao)
        assertTrue(first.createCriticalRun(testRun("run-activate-publish-race")))
        val initialPermit = (first.bindAndReserve(
            testBinding("run-activate-publish-race", RunPlacement.Remote),
        ) as BindAndReserveResult.Bound).permit
        val invocation = (first.claimForDispatch(initialPermit, testReceipt(RunPlacement.Remote)) as
            ClaimInvocationResult.Started).invocation
        assertTrue(first.finishInvocation(invocation))
        makePendingRecoveryEligible(dao, "run-activate-publish-race")

        val restarted = store(dao)
        val candidate = restarted.inspectForRecovery(
            "run-activate-publish-race",
            validRecoveryContext(),
        ) as RecoveryInspection.ContinuationCandidate
        val snapshotRead = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        dao.afterRecoverySnapshot = {
            snapshotRead.countDown()
            check(releaseActivation.await(5, TimeUnit.SECONDS))
        }
        val activation = async(Dispatchers.Default) { restarted.activate(candidate) }
        try {
            assertTrue(snapshotRead.await(5, TimeUnit.SECONDS))
            val terminalized = async(Dispatchers.Default) {
                restarted.terminalizeRun(
                    "run-activate-publish-race",
                    AgentRunState.Cancelled,
                    3_000L,
                )
            }
            releaseActivation.countDown()
            assertTrue(terminalized.await() is TerminalizeRunResult.Terminalized)
        } finally {
            releaseActivation.countDown()
        }

        val permit = activation.await()
        assertEquals(ModelDispatchState.Terminal, permit?.binding?.dispatchState)
        assertEquals(ModelDispatchState.Terminal, restarted.activeBinding("run-activate-publish-race")?.binding?.dispatchState)
        assertEquals("Cancelled", dao.run("run-activate-publish-race")?.state)
    }

    @Test
    fun newStoreNeverTreatsPersistedBindingAsActiveAndPendingIsNotRecoverable() {
        val dao = FakeRunPlacementBindingDao()
        val first = store(dao)
        assertTrue(first.createCriticalRun(testRun("run-pending")))
        assertTrue(first.bindAndReserve(testBinding("run-pending")) is BindAndReserveResult.Bound)

        val restarted = store(dao)
        assertNull(restarted.activeBinding("run-pending"))
        val inspection = restarted.inspectForRecovery(
            "run-pending",
            RunPlacementRecoveryContext(bootCount = 7L, elapsedRealtimeMillis = 2_000L),
        )
        assertEquals(
            RunPlacementRecoveryRejection.PendingNeverStarted,
            (inspection as RecoveryInspection.NotRestorable).rejection,
        )
        assertNull(restarted.activeBinding("run-pending"))
    }

    @Test
    fun onlyValidIdleContinuationRequiresExplicitActivation() = runTest {
        val dao = FakeRunPlacementBindingDao()
        val first = store(dao)
        assertTrue(first.createCriticalRun(testRun("run-recovery")))
        val permit = (first.bindAndReserve(
            testBinding("run-recovery", RunPlacement.Remote),
        ) as BindAndReserveResult.Bound).permit
        val started = first.claimForDispatch(permit, testReceipt(RunPlacement.Remote)) as ClaimInvocationResult.Started
        assertTrue(first.finishInvocation(started.invocation))
        makePendingRecoveryEligible(dao, "run-recovery")

        val restarted = store(dao)
        val inspection = restarted.inspectForRecovery(
            "run-recovery",
            RunPlacementRecoveryContext(
                bootCount = 7L,
                elapsedRealtimeMillis = 1_000L + RUN_PLACEMENT_RECOVERY_MAX_AGE_MILLIS,
                remoteProfileRevision = TEST_REMOTE_REVISION,
            ),
        )
        val candidate = inspection as RecoveryInspection.ContinuationCandidate
        assertNull(restarted.activeBinding("run-recovery"))

        val recoveredPermit = restarted.activate(candidate)

        assertEquals(RunPlacement.Remote, recoveredPermit?.binding?.placement)
        assertEquals(recoveredPermit, restarted.activeBinding("run-recovery"))
    }

    @Test
    fun activationRechecksFreshTtlAndRemoteRevisionAfterInspection() {
        val dao = FakeRunPlacementBindingDao()
        val first = store(dao)
        assertTrue(first.createCriticalRun(testRun("run-activation-freshness")))
        val permit = (first.bindAndReserve(
            testBinding("run-activation-freshness", RunPlacement.Remote),
        ) as BindAndReserveResult.Bound).permit
        val started = first.claimForDispatch(permit, testReceipt(RunPlacement.Remote)) as ClaimInvocationResult.Started
        assertTrue(first.finishInvocation(started.invocation))
        makePendingRecoveryEligible(dao, "run-activation-freshness")
        var freshContext = RunPlacementRecoveryContext(
            bootCount = 7L,
            elapsedRealtimeMillis = 2_000L,
            remoteProfileRevision = TEST_REMOTE_REVISION,
        )
        val restarted = store(dao) { freshContext }
        val candidate = restarted.inspectForRecovery("run-activation-freshness", freshContext)
            as RecoveryInspection.ContinuationCandidate

        freshContext = freshContext.copy(
            elapsedRealtimeMillis = 1_001L + RUN_PLACEMENT_RECOVERY_MAX_AGE_MILLIS,
        )
        assertNull(restarted.activate(candidate))

        freshContext = freshContext.copy(
            elapsedRealtimeMillis = 2_000L,
            remoteProfileRevision = "00000000-0000-0000-0000-000000000002",
        )
        assertNull(restarted.activate(candidate))
        assertNull(restarted.activeBinding("run-activation-freshness"))
    }

    @Test
    fun recoveryRejectsBootTtlRevisionSchemaStartedPendingTerminalAndTraceMismatch() = runTest {
        assertRecoveryRejected(
            expected = RunPlacementRecoveryRejection.BootChanged,
            contextTransform = { context ->
            context.copy(bootCount = context.bootCount + 1)
            },
        )
        assertRecoveryRejected(
            expected = RunPlacementRecoveryRejection.Expired,
            contextTransform = { context ->
            context.copy(elapsedRealtimeMillis = 1_001L + RUN_PLACEMENT_RECOVERY_MAX_AGE_MILLIS)
            },
        )
        assertRecoveryRejected(
            expected = RunPlacementRecoveryRejection.RemoteRevisionChanged,
            contextTransform = { context ->
            context.copy(remoteProfileRevision = "00000000-0000-0000-0000-000000000002")
            },
        )
        assertRecoveryRejected(
            expected = RunPlacementRecoveryRejection.UnknownSchema,
            mutate = { dao, runId -> dao.mutateBinding(runId) { it.copy(schemaVersion = 99) } },
        )
        assertRecoveryRejected(
            expected = RunPlacementRecoveryRejection.StartedNeverReplayed,
            finish = false,
        )
        assertRecoveryRejected(
            expected = RunPlacementRecoveryRejection.TerminalRun,
            mutate = { dao, runId -> dao.updateRunTerminal(runId, "Completed", 2_000L) },
        )
        assertRecoveryRejected(
            expected = RunPlacementRecoveryRejection.TraceMismatch,
            mutate = { dao, runId ->
                dao.mutateStep(runId, "ModelRuntimeInvocationStarted") { entity ->
                    val json = JSONObject(entity.json).put("attempt", 2)
                    entity.copy(json = json.toString())
                }
            },
        )
    }

    @Test
    fun recoveryRequiresADurableContinuationStateAndMatchingEvidence() {
        listOf(
            AgentRunState.Created,
            AgentRunState.Planning,
            AgentRunState.GeneratingAnswer,
        ).forEach { state ->
            val dao = FakeRunPlacementBindingDao()
            val runId = "run-state-${state.name.lowercase()}"
            prepareIdleRemoteRun(dao, runId)
            dao.mutateRun(runId) { run -> run.copy(state = state.name) }

            val inspection = store(dao).inspectForRecovery(runId, validRecoveryContext())

            assertEquals(
                RunPlacementRecoveryRejection.RunStateNotRestorable,
                (inspection as RecoveryInspection.NotRestorable).rejection,
            )
        }

        listOf(
            AgentRunState.AwaitingUserConfirmation,
            AgentRunState.AwaitingExternalOutcome,
        ).forEach { state ->
            val dao = FakeRunPlacementBindingDao()
            val runId = "run-evidence-${state.name.lowercase()}"
            prepareIdleRemoteRun(dao, runId)
            dao.mutateRun(runId) { run -> run.copy(state = state.name) }

            val inspection = store(dao).inspectForRecovery(runId, validRecoveryContext())

            assertEquals(
                RunPlacementRecoveryRejection.ContinuationEvidenceMissing,
                (inspection as RecoveryInspection.NotRestorable).rejection,
            )
        }
    }

    @Test
    fun terminalizationUsesPersistedStateAndTerminalAlwaysWins() {
        val dao = FakeRunPlacementBindingDao()
        val activeStore = store(dao)
        assertTrue(activeStore.createCriticalRun(testRun("run-durable-terminal")))
        assertTrue(
            activeStore.bindAndReserve(testBinding("run-durable-terminal", RunPlacement.Remote)) is
                BindAndReserveResult.Bound,
        )
        dao.mutateBinding("run-durable-terminal") { binding ->
            binding.copy(placement = "Unknown")
        }
        val restarted = store(dao) {
            validRecoveryContext().copy(
                bootCount = 99L,
                elapsedRealtimeMillis = RUN_PLACEMENT_RECOVERY_MAX_AGE_MILLIS + 9_999L,
            )
        }

        val first = restarted.terminalizeRun(
            "run-durable-terminal",
            AgentRunState.Cancelled,
            2_000L,
        )
        val repeated = restarted.terminalizeRun(
            "run-durable-terminal",
            AgentRunState.Cancelled,
            3_000L,
        )
        val conflicting = restarted.terminalizeRun(
            "run-durable-terminal",
            AgentRunState.Completed,
            4_000L,
        )

        assertTrue(first is TerminalizeRunResult.Terminalized)
        assertNull((first as TerminalizeRunResult.Terminalized).placement)
        assertTrue(repeated is TerminalizeRunResult.Terminalized)
        assertTrue(conflicting is TerminalizeRunResult.Rejected)
        assertEquals("Cancelled", dao.run("run-durable-terminal")?.state)
        assertEquals("Terminal", dao.binding("run-durable-terminal")?.dispatchState)
    }

    @Test
    fun finishIsIdempotentForMatchingIdleAndTerminalAttempts() {
        val dao = FakeRunPlacementBindingDao()
        val store = store(dao)
        assertTrue(store.createCriticalRun(testRun("run-finish-idempotent")))
        val permit = (store.bindAndReserve(testBinding("run-finish-idempotent")) as BindAndReserveResult.Bound).permit
        val invocation = (store.claimForDispatch(permit, testReceipt(RunPlacement.Local)) as
            ClaimInvocationResult.Started).invocation

        assertTrue(store.finishInvocation(invocation))
        assertTrue(store.finishInvocation(invocation))
        assertTrue(
            store.terminalizeRun("run-finish-idempotent", AgentRunState.Cancelled, 2_000L) is
                TerminalizeRunResult.Terminalized,
        )
        assertTrue(store.finishInvocation(invocation))
        assertEquals("Terminal", dao.binding("run-finish-idempotent")?.dispatchState)
    }

    @Test
    fun remoteRevisionAcceptsOnlyCanonicalLowercaseUuidAndCorruptionFailsClosed() {
        val canaries = listOf(
            "https://private.example/v1",
            "10.0.0.8:8443",
            "sk-secret-token",
            "private-model-name",
            "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF",
            "$TEST_REMOTE_REVISION-extra",
        )
        canaries.forEach { canary ->
            assertTrue(
                "binding accepted unsafe revision: $canary",
                runCatching {
                    testBinding(
                        runId = "run-invalid-binding",
                        placement = RunPlacement.Remote,
                        remoteProfileRevision = canary,
                    )
                }.isFailure,
            )
            assertTrue(
                "invocation accepted unsafe revision: $canary",
                runCatching {
                    ModelRuntimeInvocation(
                        runId = "run-invalid-invocation",
                        placement = RunPlacement.Remote,
                        attempt = 1,
                        remoteProfileRevision = canary,
                    )
                }.isFailure,
            )
        }

        val dao = FakeRunPlacementBindingDao()
        val first = store(dao)
        assertTrue(first.createCriticalRun(testRun("run-corrupt-revision")))
        assertTrue(
            first.bindAndReserve(testBinding("run-corrupt-revision", RunPlacement.Remote)) is
                BindAndReserveResult.Bound,
        )
        dao.mutateBinding("run-corrupt-revision") { binding ->
            binding.copy(remoteProfileRevision = "https://private.example/v1")
        }

        val inspection = store(dao).inspectForRecovery("run-corrupt-revision", validRecoveryContext())

        assertEquals(
            RunPlacementRecoveryRejection.UnknownEnum,
            (inspection as RecoveryInspection.NotRestorable).rejection,
        )
    }

    @Test
    fun duplicateCriticalCreateAndTerminalUpdateNeverReplaceRunOrCascadeBinding() {
        val dao = FakeRunPlacementBindingDao()
        val store = store(dao)
        assertTrue(store.createCriticalRun(testRun("run-strict")))
        assertTrue(store.bindAndReserve(testBinding("run-strict")) is BindAndReserveResult.Bound)

        assertFalse(store.createCriticalRun(testRun("run-strict").copy(state = AgentRunState.Planning)))
        assertEquals("Pending", dao.binding("run-strict")?.dispatchState)
        assertTrue(
            store.terminalizeRun("run-strict", AgentRunState.Completed, 3_000L)
                is TerminalizeRunResult.Terminalized,
        )
        assertEquals("Completed", dao.run("run-strict")?.state)
        assertEquals("Terminal", dao.binding("run-strict")?.dispatchState)
    }

    private suspend fun assertRecoveryRejected(
        expected: RunPlacementRecoveryRejection,
        finish: Boolean = true,
        contextTransform: (RunPlacementRecoveryContext) -> RunPlacementRecoveryContext = { it },
        mutate: (FakeRunPlacementBindingDao, String) -> Unit = { _, _ -> },
    ) {
        val dao = FakeRunPlacementBindingDao()
        val runId = "run-${expected.name.lowercase()}"
        val first = store(dao)
        assertTrue(first.createCriticalRun(testRun(runId)))
        val permit = (first.bindAndReserve(
            testBinding(runId, RunPlacement.Remote),
        ) as BindAndReserveResult.Bound).permit
        val started = first.claimForDispatch(permit, testReceipt(RunPlacement.Remote)) as ClaimInvocationResult.Started
        if (finish) assertTrue(first.finishInvocation(started.invocation))
        makePendingRecoveryEligible(dao, runId)
        mutate(dao, runId)

        val context = contextTransform(
            RunPlacementRecoveryContext(
                bootCount = 7L,
                elapsedRealtimeMillis = 2_000L,
                remoteProfileRevision = TEST_REMOTE_REVISION,
            ),
        )
        val inspection = store(dao).inspectForRecovery(runId, context)

        assertEquals(expected, (inspection as RecoveryInspection.NotRestorable).rejection)
    }

    private fun store(
        dao: FakeRunPlacementBindingDao,
        currentContext: () -> RunPlacementRecoveryContext = {
            RunPlacementRecoveryContext(
                bootCount = 7L,
                elapsedRealtimeMillis = 2_000L,
                remoteProfileRevision = TEST_REMOTE_REVISION,
            )
        },
    ): RoomRunPlacementBindingStore = RoomRunPlacementBindingStore(
        dao = dao,
        currentRecoveryContext = currentContext,
        traceClockMillis = { 10_000L },
    )

    private fun testRun(runId: String): AgentRun = AgentRun(
        id = runId,
        input = "must never persist",
        state = AgentRunState.Created,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )

    private fun makePendingRecoveryEligible(
        dao: FakeRunPlacementBindingDao,
        runId: String,
    ) {
        dao.mutateRun(runId) { run ->
            run.copy(state = AgentRunState.AwaitingUserConfirmation.name, updatedAtMillis = 2_000L)
        }
        dao.setPendingConfirmation(
            com.bytedance.zgx.solin.data.PendingAgentConfirmationEntity(
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
                plannedByModel = false,
                fallbackReason = null,
                nextActionInput = null,
                continuationCursorJson = null,
                createdAtMillis = 1_000L,
                updatedAtMillis = 2_000L,
            ),
        )
    }

    private fun prepareIdleRemoteRun(
        dao: FakeRunPlacementBindingDao,
        runId: String,
    ) {
        val first = store(dao)
        check(first.createCriticalRun(testRun(runId)))
        val permit = (first.bindAndReserve(testBinding(runId, RunPlacement.Remote)) as
            BindAndReserveResult.Bound).permit
        val invocation = (first.claimForDispatch(permit, testReceipt(RunPlacement.Remote)) as
            ClaimInvocationResult.Started).invocation
        check(first.finishInvocation(invocation))
    }

    private fun validRecoveryContext(): RunPlacementRecoveryContext = RunPlacementRecoveryContext(
        bootCount = 7L,
        elapsedRealtimeMillis = 2_000L,
        remoteProfileRevision = TEST_REMOTE_REVISION,
    )
}
