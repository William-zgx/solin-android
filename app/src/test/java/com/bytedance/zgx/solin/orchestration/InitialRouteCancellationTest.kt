package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ModelBackendKind
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCapabilityProfile
import com.bytedance.zgx.solin.ModelFeature
import com.bytedance.zgx.solin.ModelInputModality
import com.bytedance.zgx.solin.action.ActionIntentGateResult
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.HybridActionPlanningRuntime
import com.bytedance.zgx.solin.action.IntentRoutingPath
import com.bytedance.zgx.solin.device.background.BackgroundPhoneSession
import com.bytedance.zgx.solin.memory.EmbeddingRuntime
import com.bytedance.zgx.solin.memory.MemoryIndex
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.memory.SemanticMemoryRuntimeStatus
import com.bytedance.zgx.solin.runtime.LocalAccelerator
import com.bytedance.zgx.solin.runtime.LocalCallMetrics
import com.bytedance.zgx.solin.runtime.LocalEngine
import com.bytedance.zgx.solin.runtime.LocalEngineCapabilities
import com.bytedance.zgx.solin.runtime.LocalEngineSource
import com.bytedance.zgx.solin.runtime.LocalOneShotCancellation
import com.bytedance.zgx.solin.runtime.LocalOneShotRequest
import com.bytedance.zgx.solin.runtime.LocalOneShotResult
import com.bytedance.zgx.solin.runtime.LocalOneShotUnavailableReason
import com.bytedance.zgx.solin.tool.ToolSpec
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production-level Stop races through the real orchestrator, loop, planner and hybrid runtime. */
class InitialRouteCancellationTest {
    @Test
    fun stopBeforeTheGateStartsNoModelCallAndTerminalizesTheRun() {
        val engine = CooperativeEngine { error("a cancelled route must not call the engine") }
        RouteFixture(engine).use { fixture ->
            val cancellation = LocalOneShotCancellation().apply { cancel() }

            val route = fixture.route(cancellation)

            fixture.assertCancelledCleanup(route)
            assertTrue(engine.requests.isEmpty())
            fixture.assertNoAnswerRouteSteps(route.runId())
        }
    }

    @Test
    fun stopDuringTheGateCancelsItWithoutStartingTheProposal() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelObserved = AtomicBoolean()
        val engine = CooperativeEngine { request ->
            started.countDown()
            val stopListening = request.cancellation.onCancel {
                cancelObserved.set(true)
                release.countDown()
            }
            try {
                check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.Cancelled, "caller cancelled")
            } finally {
                stopListening()
            }
        }
        RouteFixture(engine).use { fixture ->
            val cancellation = LocalOneShotCancellation()
            val stopper = thread {
                if (started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    cancellation.cancel()
                    release.countDown()
                }
            }

            val route = fixture.route(cancellation)
            stopper.join(TIMEOUT_MILLIS)

            assertFalse(stopper.isAlive)
            assertTrue("Stop did not reach the gate's cancellation handle", cancelObserved.get())
            fixture.assertCancelledCleanup(route)
            assertEquals("no proposal may follow a cancelled gate", 1, engine.requests.size)
            fixture.assertNoAnswerRouteSteps(route.runId())
        }
    }

    @Test
    fun localModelFirstProposalUsesTheRunCancellationHandle() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelObserved = AtomicBoolean()
        val engine = CooperativeEngine { request ->
            started.countDown()
            val stopListening = request.cancellation.onCancel {
                cancelObserved.set(true)
                release.countDown()
            }
            try {
                check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                LocalOneShotResult.Unavailable(LocalOneShotUnavailableReason.Cancelled, "caller cancelled")
            } finally {
                stopListening()
            }
        }
        RouteFixture(engine).use { fixture ->
            val cancellation = LocalOneShotCancellation()
            val stopper = thread {
                if (started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    cancellation.cancel()
                    release.countDown()
                }
            }

            val route = fixture.route(
                cancellation = cancellation,
                options = gatedOptions(cancellation).copy(
                    localPlanningStrategy = LocalPlanningStrategy.ModelFirst,
                ),
            )
            stopper.join(TIMEOUT_MILLIS)

            assertFalse(stopper.isAlive)
            assertTrue(cancelObserved.get())
            fixture.assertCancelledCleanup(route)
            assertEquals(1, engine.requests.size)
            fixture.assertNoAnswerRouteSteps(route.runId())
        }
    }

    @Test
    fun stopAfterTheGateReturnsStartsNoProposal() {
        val gateReturned = BlockingHook()
        val engine = CooperativeEngine { generated(GATE_OPEN) }
        RouteFixture(
            engine = engine,
            planningFactory = { cacheDir, sharedEngine ->
                BoundaryPlanningRuntime(
                    HybridActionPlanningRuntime(cacheDir, sharedEngine = sharedEngine),
                    onGateReturned = gateReturned::block,
                )
            },
        ).use { fixture ->
            val route = runStopAtHook(fixture, gateReturned)

            fixture.assertCancelledCleanup(route)
            assertEquals(1, engine.requests.size)
            fixture.assertNoAnswerRouteSteps(route.runId())
        }
    }

    @Test
    fun stopAfterTheProposalReturnsSavesNoActionOrAnswer() {
        val proposalReturned = BlockingHook()
        val calls = AtomicInteger()
        val engine = CooperativeEngine {
            when (calls.incrementAndGet()) {
                1 -> generated(GATE_OPEN)
                2 -> generated(ALARM_CALL)
                else -> error("unexpected extra model call")
            }
        }
        RouteFixture(
            engine = engine,
            planningFactory = { cacheDir, sharedEngine ->
                BoundaryPlanningRuntime(
                    HybridActionPlanningRuntime(cacheDir, sharedEngine = sharedEngine),
                    onProposalReturned = proposalReturned::block,
                )
            },
        ).use { fixture ->
            val route = runStopAtHook(fixture, proposalReturned)

            fixture.assertCancelledCleanup(route)
            assertEquals(2, engine.requests.size)
            fixture.assertNoAnswerRouteSteps(route.runId())
        }
    }

    @Test
    fun stopDuringSemanticRecallLeavesTheRuntimeActiveAndStartsNoGate() {
        val embedding = BlockingEmbeddingRuntime()
        val memory = MemoryRepository(semanticRuntimeFactory = { embedding })
        memory.useMemoryModel(MEMORY_MODEL_PATH)
        memory.indexUserFact("fact-1", "用户喜欢简洁回答")
        val engine = CooperativeEngine { error("semantic Stop must not reach the gate") }
        RouteFixture(engine = engine, memoryIndex = memory).use { fixture ->
            embedding.blockNextEmbed.set(true)
            val cancellation = LocalOneShotCancellation()
            val stoppedDuringRecall = AtomicBoolean()
            val stopper = thread {
                if (embedding.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    stoppedDuringRecall.set(true)
                    cancellation.cancel()
                    embedding.release.countDown()
                }
            }

            val route = fixture.route(cancellation, memoryEnabled = true)
            stopper.join(TIMEOUT_MILLIS)

            assertFalse(stopper.isAlive)
            assertTrue("Stop did not happen while the embedding was blocked", stoppedDuringRecall.get())
            fixture.assertCancelledCleanup(route)
            assertTrue(engine.requests.isEmpty())
            assertEquals(SemanticMemoryRuntimeStatus.Active, memory.semanticMemoryRuntimeStatus)
            assertEquals(MEMORY_MODEL_PATH, memory.activeMemoryModelPath)
            assertEquals(1, memory.semanticMemoryIndexedRecordCount)
            assertEquals("Stop must not close the semantic runtime", 0, embedding.closeCount.get())
            fixture.assertNoAnswerRouteSteps(route.runId())
        }
    }

    @Test
    fun stopBeforeNoActionPersistsNoChatOutcome() {
        val gateTraceSaved = BlockingHook()
        val traceStore = HookedTraceStore(onStep = { step ->
            if (step is AgentStep.IntentRouted &&
                step.decision.selectedPath == IntentRoutingPath.ModelToolCall
            ) {
                gateTraceSaved.block()
            }
        })
        val engine = CooperativeEngine { generated(GATE_CLOSED) }
        RouteFixture(engine = engine, traceStore = traceStore).use { fixture ->
            val route = runStopAtHook(fixture, gateTraceSaved)

            fixture.assertCancelledCleanup(route)
            assertEquals(1, engine.requests.size)
            fixture.assertNoAnswerRouteSteps(route.runId())
        }
    }

    @Test
    fun stopAfterNoActionButBeforeTheAnswerPlanPersistsNoAnswerPlan() {
        val noActionSaved = BlockingHook()
        val traceStore = HookedTraceStore(onStep = { step ->
            if (step is AgentStep.IntentRouted &&
                step.decision.selectedPath == IntentRoutingPath.NoAction
            ) {
                noActionSaved.block()
            }
        })
        val engine = CooperativeEngine { generated(GATE_CLOSED) }
        RouteFixture(engine = engine, traceStore = traceStore).use { fixture ->
            val route = runStopAtHook(fixture, noActionSaved)

            fixture.assertCancelledCleanup(route)
            val steps = fixture.traceStore.steps(route.runId())
            assertTrue(steps.any { step ->
                step is AgentStep.IntentRouted && step.decision.selectedPath == IntentRoutingPath.NoAction
            })
            assertTrue(steps.none { step -> step is AgentStep.ModelPlanned && step.plan is AgentPlan.Answer })
        }
    }

    @Test
    fun stopAfterTheAnswerPlanButBeforeGeneratingAnswerTerminalizesTheRun() {
        val answerPlanSaved = BlockingHook()
        val traceStore = HookedTraceStore(onStep = { step ->
            if (step is AgentStep.ModelPlanned && step.plan is AgentPlan.Answer) {
                answerPlanSaved.block()
            }
        })
        val engine = CooperativeEngine { generated(GATE_CLOSED) }
        RouteFixture(engine = engine, traceStore = traceStore).use { fixture ->
            val route = runStopAtHook(fixture, answerPlanSaved)

            fixture.assertCancelledCleanup(route)
            assertTrue(fixture.traceStore.steps(route.runId()).any { step ->
                step is AgentStep.ModelPlanned && step.plan is AgentPlan.Answer
            })
            assertEquals(AgentRunState.Cancelled, fixture.traceStore.run(route.runId())?.state)
        }
    }

    @Test
    fun stopAfterPendingSnapshotSaveClearsRestoreAndEveryBinding() {
        val snapshotSaved = BlockingHook()
        val traceStore = HookedTraceStore(onPendingSaved = snapshotSaved::block)
        val calls = AtomicInteger()
        val engine = CooperativeEngine {
            when (calls.incrementAndGet()) {
                1 -> generated(GATE_OPEN)
                2 -> generated(ALARM_CALL)
                else -> error("unexpected extra model call")
            }
        }
        RouteFixture(engine = engine, traceStore = traceStore).use { fixture ->
            val cancellation = LocalOneShotCancellation()
            val beforeStop = AtomicReference<AssistantRoute.Action?>()
            val stopper = thread {
                if (snapshotSaved.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    beforeStop.set(fixture.orchestrator.restorePendingAction())
                    cancellation.cancel()
                    snapshotSaved.release.countDown()
                }
            }

            val route = fixture.route(cancellation)
            stopper.join(TIMEOUT_MILLIS)

            assertFalse(stopper.isAlive)
            assertNotNull("the race must begin with a restorable action", beforeStop.get())
            fixture.assertCancelledCleanup(route)
            assertNull("a stopped action must not be restorable", fixture.orchestrator.restorePendingAction())
            assertTrue(fixture.traceStore.steps(route.runId()).none { step ->
                step is AgentStep.ModelPlanned && step.plan is AgentPlan.Answer
            })
        }
    }

    @Test
    fun ruleFirstRouteKeepsItsRulesOnlyBehavior() {
        val engine = CooperativeEngine { error("RuleFirst must not call a local planning model") }
        RouteFixture(engine).use { fixture ->
            val cancellation = LocalOneShotCancellation()

            val route = fixture.route(
                cancellation = cancellation,
                input = QUESTION,
                options = AgentRunOptions(initialRouteCancellation = cancellation),
            )

            assertTrue(route is AssistantRoute.Chat)
            assertEquals(AgentRunState.GeneratingAnswer, fixture.traceStore.run(route.runId())?.state)
            assertTrue(engine.requests.isEmpty())
            assertFalse("RuleFirst routing must not interrupt its thread", Thread.currentThread().isInterrupted)
            fixture.orchestrator.cancelRun(route.runId(), "test cleanup")
        }
    }

    /** The route stays on the JUnit thread; only the small Stop signal runs on another thread. */
    private fun runStopAtHook(fixture: RouteFixture, hook: BlockingHook): AssistantRoute {
        val cancellation = LocalOneShotCancellation()
        val stopper = thread {
            if (hook.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                cancellation.cancel()
                hook.release.countDown()
            }
        }
        val route = fixture.route(cancellation)
        stopper.join(TIMEOUT_MILLIS)
        assertFalse("the Stop watchdog did not finish", stopper.isAlive)
        return route
    }

    private class RouteFixture(
        val engine: CooperativeEngine,
        val memoryIndex: MemoryIndex = MemoryRepository(),
        val traceStore: AgentTraceStore = InMemoryAgentTraceStore(),
        planningFactory: (File, LocalEngine) -> ActionPlanningRuntime = { cacheDir, sharedEngine ->
            HybridActionPlanningRuntime(cacheDir, sharedEngine = sharedEngine)
        },
    ) : AutoCloseable {
        private val cacheDir = Files.createTempDirectory("initial-route-cancellation").toFile()
        private val planningRuntime = planningFactory(cacheDir, engine)
        val orchestrator = AssistantOrchestrator(
            memoryIndex = memoryIndex,
            actionPlanningRuntime = planningRuntime,
            traceStore = traceStore,
        )

        fun route(
            cancellation: LocalOneShotCancellation,
            input: String = ALARM_REQUEST,
            memoryEnabled: Boolean = false,
            options: AgentRunOptions = gatedOptions(cancellation),
        ): AssistantRoute = orchestrator.route(
            input = input,
            installedCapabilities = setOf(ModelCapability.Chat, ModelCapability.MobileAction),
            memoryEnabled = memoryEnabled,
            actionModelPath = null,
            deviceContext = null,
            sessionId = "session-c2",
            options = options,
            installedCapabilityProfiles = ACTION_MODEL_PROFILES,
        )

        fun assertCancelledCleanup(route: AssistantRoute) {
            assertTrue("expected an inert cancelled route but was $route", route is AssistantRoute.Cancelled)
            val runId = route.runId()
            assertEquals(AgentRunState.Cancelled, traceStore.run(runId)?.state)
            assertNull(orchestrator.restorePendingAction())
            val loop = AssistantOrchestrator::class.java.getDeclaredField("agentLoopRuntime").let { field ->
                field.isAccessible = true
                field.get(orchestrator) as AgentLoopRuntime
            }
            assertFalse("run-scoped maps retained $runId", runId in loop.runIdsWithScopedState())
            assertFalse("background binding retained $runId", runId in retainedBackgroundRunIds())
        }

        fun assertNoAnswerRouteSteps(runId: String) {
            val steps = traceStore.steps(runId)
            assertTrue(steps.none { step ->
                step is AgentStep.IntentRouted && step.decision.selectedPath == IntentRoutingPath.NoAction
            })
            assertTrue(steps.none { step -> step is AgentStep.ModelPlanned && step.plan is AgentPlan.Answer })
        }

        override fun close() {
            runCatching { orchestrator.close() }
            cacheDir.deleteRecursively()
        }
    }

    private class BoundaryPlanningRuntime(
        private val delegate: HybridActionPlanningRuntime,
        private val onGateReturned: () -> Unit = {},
        private val onProposalReturned: () -> Unit = {},
    ) : ActionPlanningRuntime by delegate {
        override fun gateActionIntent(
            input: String,
            actionModelPath: String?,
            cancellation: LocalOneShotCancellation,
        ): ActionIntentGateResult = delegate.gateActionIntent(input, actionModelPath, cancellation).also {
            onGateReturned()
        }

        override fun plan(
            input: String,
            actionModelPath: String?,
            toolSpecs: List<ToolSpec>,
            cancellation: LocalOneShotCancellation,
        ): ActionPlanningResult = delegate.plan(input, actionModelPath, toolSpecs, cancellation).also {
            onProposalReturned()
        }
    }

    private class CooperativeEngine(
        private val handler: (LocalOneShotRequest) -> LocalOneShotResult,
    ) : LocalEngine {
        val requests = Collections.synchronizedList(mutableListOf<LocalOneShotRequest>())

        override fun engineCapabilities() =
            LocalEngineCapabilities("fake", setOf(LocalAccelerator.CPU), 8_192)

        override fun generateOneShot(request: LocalOneShotRequest): LocalOneShotResult {
            requests += request
            return handler(request)
        }
    }

    private class BlockingEmbeddingRuntime : EmbeddingRuntime {
        override val runtimeId = "blocking-semantic-test"
        override val modelId = MEMORY_MODEL_PATH
        override val dimension = 2
        override val supportsSemanticRecall = true
        val blockNextEmbed = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeCount = AtomicInteger()

        override fun embed(text: String): FloatArray {
            if (blockNextEmbed.compareAndSet(true, false)) {
                started.countDown()
                check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "embedding was not released" }
            }
            return floatArrayOf(1f, 0f)
        }

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class BlockingHook {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun block() {
            entered.countDown()
            check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "route boundary was not released" }
        }
    }

    private class HookedTraceStore(
        private val delegate: InMemoryAgentTraceStore = InMemoryAgentTraceStore(),
        private val onStep: (AgentStep) -> Unit = {},
        private val onPendingSaved: () -> Unit = {},
    ) : AgentTraceStore by delegate {
        override fun appendStep(runId: String, step: AgentStep) {
            delegate.appendStep(runId, step)
            onStep(step)
        }

        override fun savePendingConfirmation(snapshot: PendingToolConfirmationSnapshot) {
            delegate.savePendingConfirmation(snapshot)
            onPendingSaved()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
        const val TIMEOUT_MILLIS = 5_000L
        const val MODEL_PATH = "/tmp/planning-model.litertlm"
        const val MEMORY_MODEL_PATH = "/tmp/embedding-model.tflite"
        const val ALARM_REQUEST = "帮我设置闹钟"
        const val QUESTION = "为什么天空是蓝色的"
        const val ALARM_CALL = """call:set_system_alarm{"hour":7,"minutes":30}"""
        const val GATE_OPEN = """{"action":true,"confidence":1.0}"""
        const val GATE_CLOSED = """{"action":false,"confidence":1.0}"""

        val ACTION_MODEL_PROFILES = listOf(
            ModelCapabilityProfile(
                id = "test-planner",
                displayName = "test planning model",
                capability = ModelCapability.MobileAction,
                backendKind = ModelBackendKind.LocalLiteRt,
                inputModalities = setOf(ModelInputModality.Text),
                features = setOf(ModelFeature.MobileActionPlanning),
            ),
        )

        fun gatedOptions(cancellation: LocalOneShotCancellation) = AgentRunOptions(
            initialPlanningMode = InitialPlanningMode.LocalModelFirst,
            localPlanningStrategy = LocalPlanningStrategy.RuleFirstGatedModel,
            localPlanningModelPath = MODEL_PATH,
            initialRouteCancellation = cancellation,
        )

        fun generated(text: String) = LocalOneShotResult.Generated(
            text = text,
            metrics = LocalCallMetrics(
                source = LocalEngineSource.SharedChatEngine,
                accelerator = LocalAccelerator.CPU,
                loadMs = null,
                firstTokenMs = 1,
                totalMs = 2,
                inputTokens = null,
                outputTokens = null,
            ),
        )

        fun AssistantRoute.runId(): String = when (this) {
            is AssistantRoute.Chat -> requireNotNull(runId)
            is AssistantRoute.Action -> requireNotNull(runId)
            is AssistantRoute.Cancelled -> runId
            else -> error("route has no run id: $this")
        }

        @Suppress("UNCHECKED_CAST")
        fun retainedBackgroundRunIds(): Set<String> {
            val field = BackgroundPhoneSession::class.java.getDeclaredField("runs")
            field.isAccessible = true
            return (field.get(null) as Map<String, *>).keys.toSet()
        }
    }
}
