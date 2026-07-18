package com.bytedance.zgx.solin.orchestration

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.data.AgentRunEntity
import com.bytedance.zgx.solin.data.SolinDatabase
import com.bytedance.zgx.solin.resource.StableResourceBand
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RunPlacementBindingRoomTest {
    private lateinit var database: SolinDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            SolinDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun duplicateTraceInsertDoesNotReplaceRunOrCascadePlacementRows() {
        val run = runEntity("run-no-replace")
        val binding = binding(run.id)
        val placementDao = database.runPlacementBindingDao()
        placementDao.insertRunStrict(run)
        placementDao.bindAndReserveTransaction(
            binding.toEntity(),
            AgentStep.PlacementSelected(binding).toTraceEntity(run.id, 0, 2L),
        )

        val duplicate = database.agentTraceDao().insertRunIfAbsent(
            run.copy(input = "replacement", state = AgentRunState.Planning.name),
        )

        assertEquals(-1L, duplicate)
        assertEquals("[redacted]", placementDao.run(run.id)?.input)
        assertEquals(AgentRunState.Created.name, placementDao.run(run.id)?.state)
        assertEquals(RunPlacement.Local.name, placementDao.binding(run.id)?.placement)
        assertEquals(listOf("PlacementSelected"), placementDao.steps(run.id).map { it.type })
    }

    @Test
    fun terminalizationIsDurableAndFirstTerminalStateWins() {
        val run = runEntity("run-terminal")
        val binding = binding(run.id).copy(
            dispatchState = ModelDispatchState.Started,
            attempt = 1,
        )
        val dao = database.runPlacementBindingDao()
        dao.insertRunStrict(run)
        dao.bindAndReserveTransaction(
            binding.toEntity(),
            AgentStep.PlacementSelected(binding).toTraceEntity(run.id, 0, 2L),
        )

        val first = dao.terminalizeTransaction(run.id, AgentRunState.Cancelled.name, 3L)
        val repeated = dao.terminalizeTransaction(run.id, AgentRunState.Cancelled.name, 4L)
        val conflicting = dao.terminalizeTransaction(run.id, AgentRunState.Completed.name, 5L)

        assertTrue(first?.targetStateMatched == true)
        assertTrue(repeated?.targetStateMatched == true)
        assertFalse(conflicting?.targetStateMatched ?: true)
        assertEquals(AgentRunState.Cancelled.name, dao.run(run.id)?.state)
        assertEquals(ModelDispatchState.Terminal.name, dao.binding(run.id)?.dispatchState)
    }

    @Test
    fun invocationTraceAbortRollsBackClaimAttemptAndReceipt() {
        val run = runEntity("run-claim-rollback")
        val binding = binding(run.id)
        val dao = database.runPlacementBindingDao()
        dao.insertRunStrict(run)
        dao.bindAndReserveTransaction(
            binding.toEntity(),
            AgentStep.PlacementSelected(binding).toTraceEntity(run.id, 0, 2L),
        )
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_model_invocation_insert
            BEFORE INSERT ON agent_steps
            WHEN NEW.type = 'ModelRuntimeInvocationStarted'
            BEGIN
                SELECT RAISE(ABORT, 'injected invocation trace failure');
            END
            """.trimIndent(),
        )

        val result = runCatching {
            dao.claimAndRecordTransaction(
                runId = run.id,
                placement = RunPlacement.Local.name,
                expectedAttempt = 0,
                receiptStep = receiptStep(run.id),
                invocationStep = invocationStep(run.id),
            )
        }

        assertTrue(result.isFailure)
        assertEquals(ModelDispatchState.Pending.name, dao.binding(run.id)?.dispatchState)
        assertEquals(0, dao.binding(run.id)?.attempt)
        assertEquals(listOf("PlacementSelected"), dao.steps(run.id).map { it.type })
        assertTrue(dao.steps(run.id).none { it.type == "RunDataReceiptRecorded" })
        assertTrue(dao.steps(run.id).none { it.type == "ModelRuntimeInvocationStarted" })
    }

    @Test
    fun concurrentRoomClaimsHaveOneWinnerAndTerminalBlocksLateClaim() {
        val run = runEntity("run-concurrent-claim")
        val binding = binding(run.id)
        val dao = database.runPlacementBindingDao()
        dao.insertRunStrict(run)
        dao.bindAndReserveTransaction(
            binding.toEntity(),
            AgentStep.PlacementSelected(binding).toTraceEntity(run.id, 0, 2L),
        )
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val claims = List(2) {
                executor.submit {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    dao.claimAndRecordTransaction(
                        runId = run.id,
                        placement = RunPlacement.Local.name,
                        expectedAttempt = 0,
                        receiptStep = receiptStep(run.id),
                        invocationStep = invocationStep(run.id),
                    )
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = claims.map { claim -> claim.get(5, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it != null })
            assertEquals(ModelDispatchState.Started.name, dao.binding(run.id)?.dispatchState)
            assertEquals(1, dao.binding(run.id)?.attempt)
            assertEquals(1, dao.steps(run.id).count { it.type == "RunDataReceiptRecorded" })
            assertEquals(1, dao.steps(run.id).count { it.type == "ModelRuntimeInvocationStarted" })

            assertTrue(
                dao.terminalizeTransaction(run.id, AgentRunState.Cancelled.name, 3L)
                    ?.targetStateMatched == true,
            )
            assertNull(
                dao.claimAndRecordTransaction(
                    runId = run.id,
                    placement = RunPlacement.Local.name,
                    expectedAttempt = 1,
                    receiptStep = receiptStep(run.id),
                    invocationStep = invocationStep(run.id),
                ),
            )
            assertEquals(ModelDispatchState.Terminal.name, dao.binding(run.id)?.dispatchState)
            assertEquals(1, dao.steps(run.id).count { it.type == "RunDataReceiptRecorded" })
            assertEquals(1, dao.steps(run.id).count { it.type == "ModelRuntimeInvocationStarted" })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun liveTraceReadsIncludeCriticalDaoStepsAndTerminalState() {
        val rawPrompt = "private live prompt"
        val traceStore = RoomAgentTraceStore(
            traceDao = database.agentTraceDao(),
            clockMillis = { 1_000L },
            runIdFactory = { "run-cross-dao" },
        )
        val run = traceStore.createRun(rawPrompt)
        val binding = binding(run.id)
        database.runPlacementBindingDao().bindAndReserveTransaction(
            binding.toEntity(),
            AgentStep.PlacementSelected(binding).toTraceEntity(run.id, 0, 2L),
        )
        database.runPlacementBindingDao().terminalizeTransaction(
            run.id,
            AgentRunState.Cancelled.name,
            3_000L,
        )
        val nonTerminalAttempt = traceStore.updateState(run.id, AgentRunState.GeneratingAnswer)
        val differentTerminalAttempt = traceStore.updateState(run.id, AgentRunState.Completed)

        val steps = traceStore.steps(run.id)
        val summaries = traceStore.stepSummaries(run.id)

        assertEquals("PlacementSelected", (steps.single() as AgentStep.RestoredSummary).persistedType)
        assertEquals(listOf("PlacementSelected"), summaries.map { it.type })
        assertEquals(AgentRunState.Cancelled, nonTerminalAttempt.state)
        assertEquals(AgentRunState.Cancelled, differentTerminalAttempt.state)
        assertEquals(AgentRunState.Cancelled.name, database.agentTraceDao().run(run.id)?.state)
        assertEquals(AgentRunState.Cancelled, traceStore.run(run.id)?.state)
        assertEquals(rawPrompt, traceStore.run(run.id)?.input)
        assertNull(traceStore.run("missing-run"))
    }

    private fun runEntity(runId: String): AgentRunEntity = AgentRunEntity(
        id = runId,
        input = "[redacted]",
        state = AgentRunState.Created.name,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )

    private fun binding(runId: String): RunPlacementBinding = RunPlacementBinding(
        runId = runId,
        policyVersion = 1,
        preference = InferenceMode.Auto,
        placement = RunPlacement.Local,
        primaryReason = PlacementReasonCode.AUTO_SIMPLE_LOCAL,
        complexity = RequestComplexity.Simple,
        resourceBand = StableResourceBand.Normal,
        localState = CandidateState.Eligible,
        remoteState = CandidateState.Eligible,
        remoteProfileRevision = null,
        bootCount = 7L,
        boundAtElapsedRealtimeMillis = 1_000L,
    )

    private fun receiptStep(runId: String) = AgentStep.RunDataReceiptRecorded(
        RunDataReceipt(
            destination = RunDataDestination.Local,
            currentPromptPrivacy = "RemoteEligible",
        ),
    ).toTraceEntity(runId, 0, 3L)

    private fun invocationStep(runId: String) = AgentStep.ModelRuntimeInvocationStarted(
        ModelRuntimeInvocation(
            runId = runId,
            placement = RunPlacement.Local,
            attempt = 1,
            remoteProfileRevision = null,
        ),
    ).toTraceEntity(runId, 0, 4L)
}
