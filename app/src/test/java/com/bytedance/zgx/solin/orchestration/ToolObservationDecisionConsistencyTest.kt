package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InstalledModelSummary
import com.bytedance.zgx.solin.MOBILE_ACTION_MODEL_ID
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.data.ModelVerificationStatus
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.skill.SkillManifest
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.skill.SkillRuntime
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for observation-decision consistency and the bounded termination loop.
 *
 * The decision tests pin ONE invariant: the trace step, the run-state transition, the side effects
 * (pending confirmation / remote tool scope) and the returned decision must all follow the same
 * value. They used to diverge — overrides (post-launch in-app continuation, `finish`, `take_over`)
 * drove the state machine while the side effects and the returned decision still followed the
 * pre-override base decision. The worst case lost the pending-confirmation row for a run parked in
 * AwaitingUserConfirmation, which startup repair then failed as unrestorable.
 */
class ToolObservationDecisionConsistencyTest {
    @Test
    fun postLaunchInAppContinuationSavesPendingConfirmationExactlyOnce() {
        val store = CountingPendingConfirmationTraceStore(InMemoryAgentTraceStore(clockMillis = { 1_000L }))
        val runtime = postLaunchRuntime(store)

        val planned = runtime.runOnce(
            input = OPEN_APP_INPUT,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            installedCapabilityProfiles = listOf(ModelCatalog.profileForModelId(MOBILE_ACTION_MODEL_ID)),
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_APP_BY_NAME, planned.plan.request.toolName)
        assertEquals(FOLLOW_UP_INTENT, planned.plan.request.arguments["followUpIntent"])
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        // The initial open_app confirmation already persisted one snapshot; measure the continuation
        // save relative to that so the assertion below is about the post-launch step only.
        val savesBeforeObservation = store.saveCount

        val observed = requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = launchedAppResult(planned.plan.request),
            ),
        )

        // The override fired: the run parks for confirmation of the in-app observe step...
        assertEquals(AgentRunState.AwaitingUserConfirmation, observed.run.state)
        require(observed.decision is AgentObservationDecision.PlanNextTool)
        val continuationPlan = observed.decision.plan
        assertEquals(MobileActionFunctions.OBSERVE_CURRENT_SCREEN, continuationPlan.request.toolName)
        assertTrue(continuationPlan.draft.requiresConfirmation)
        assertEquals(TARGET_PACKAGE, continuationPlan.request.arguments["expectedPackageName"])
        // ...and exactly one pending confirmation was persisted for it. Without the fix this stayed
        // unchanged (the side effect tested the pre-override Complete decision), leaving the run in
        // AwaitingUserConfirmation with no pending row — which failStaleInFlightRuns then fails as
        // unrestorable on the next process start.
        assertEquals(savesBeforeObservation + 1, store.saveCount)
        assertEquals(continuationPlan.request.id, store.lastSavedRequestId)
        assertEquals(continuationPlan.request.id, runtime.latestPendingConfirmation()?.request?.id)
    }

    @Test
    fun postLaunchInAppContinuationReturnsTheSameDecisionItTraced() {
        val store = CountingPendingConfirmationTraceStore(InMemoryAgentTraceStore(clockMillis = { 1_000L }))
        val runtime = postLaunchRuntime(store)
        val planned = runtime.runOnce(
            input = OPEN_APP_INPUT,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            installedCapabilityProfiles = listOf(ModelCatalog.profileForModelId(MOBILE_ACTION_MODEL_ID)),
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = launchedAppResult(planned.plan.request),
            ),
        )

        // The UI used to be handed Complete for a run that was in fact still awaiting confirmation.
        assertEquals(lastTracedDecision(observed), observed.decision)
        assertNull(observed.retryRequest)
    }

    @Test
    fun retiredInstalledOnlyActionModelKeepsOpenThenStopLikeEmptyState() {
        val empty = observePostLaunchWithCapabilities(ChatUiState())
        val retired = observePostLaunchWithCapabilities(
            ChatUiState(
                installedModels = listOf(
                    InstalledModelSummary(
                        id = "retired-action",
                        displayName = "Retired action model",
                        path = "/tmp/retired-action.litertlm",
                        fileBytes = 1L,
                        recommendedModelId = MOBILE_ACTION_MODEL_ID,
                        verificationStatus = ModelVerificationStatus.VerifiedRecommended,
                    ),
                ),
            ),
        )

        assertEquals(empty.run.state, retired.run.state)
        assertEquals(empty.decision, retired.decision)
        assertEquals(lastTracedDecision(empty), lastTracedDecision(retired))
        assertTrue(
            retired.steps.filterIsInstance<AgentStep.ToolRequested>().none { step ->
                step.request.toolName == MobileActionFunctions.OBSERVE_CURRENT_SCREEN
            },
        )
    }

    @Test
    fun takeOverOverrideReturnsCompleteInsteadOfTheBaseContinueWithModelDecision() {
        // Opposite direction of the same split. take_over declares LocalEvidence continuation, so the
        // base decision is ContinueWithModel; shouldTakeOver forces Complete. Pre-fix the trace said
        // Complete while the caller received ContinueWithModel (and the remote tool scope was widened
        // for a run that had already finished).
        val store = CountingPendingConfirmationTraceStore(InMemoryAgentTraceStore(clockMillis = { 1_000L }))
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = TakeOverActionRuntime(),
            skillRuntime = NoPlanSkillRuntime(),
            traceStore = store,
        )
        val planned = runtime.runOnce(
            input = "帮我登录",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.TAKE_OVER, planned.plan.request.toolName)
        assertEquals(AgentRunState.ExecutingTool, planned.run.state)
        val savesBeforeObservation = store.saveCount

        val observed = requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = takeOverResult(planned.plan.request),
            ),
        )

        assertEquals(AgentRunState.Completed, observed.run.state)
        assertEquals(AgentObservationDecision.Complete, observed.decision)
        assertEquals(lastTracedDecision(observed), observed.decision)
        // A completed run must not leave a pending confirmation behind.
        assertEquals(savesBeforeObservation, store.saveCount)
    }

    @Test(timeout = 10_000L)
    fun terminateRunGivesUpInsteadOfSpinningWhenCompareAndSetNeverSucceeds() {
        // RoomAgentTraceStore.compareAndSetState can fail *persistently* when its persisted and live
        // views disagree; the previous `while (updatedRun == null)` loop then span forever, doing two
        // disk reads per iteration. The bounded loop must give up instead. The test timeout is the
        // real "does not hang" assertion; the null return proves the give-up path was taken.
        val store = NeverCompareAndSetTraceStore(InMemoryAgentTraceStore(clockMillis = { 1_000L }))
        val runtime = terminateRuntime(store)
        val run = store.createRun("永远无法 CAS 的运行")
        store.updateState(run.id, AgentRunState.GeneratingAnswer)

        val terminated = runtime.terminateRun(run.id, "test give up")

        assertNull(terminated)
        assertTrue(
            "attempts=${store.compareAndSetAttempts}",
            store.compareAndSetAttempts in 1..MAX_EXPECTED_CAS_ATTEMPTS,
        )
        // The run is deliberately left untouched, which callers already treat as "nothing to cancel".
        assertEquals(AgentRunState.GeneratingAnswer, store.run(run.id)?.state)
    }

    @Test(timeout = 10_000L)
    fun terminateRunStillCancelsWhenCompareAndSetSucceeds() {
        val store = InMemoryAgentTraceStore(clockMillis = { 1_000L })
        val runtime = terminateRuntime(store)
        val run = store.createRun("可以正常取消的运行")
        store.updateState(run.id, AgentRunState.GeneratingAnswer)

        val terminated = runtime.terminateRun(run.id, "test cancel")

        assertNotNull(terminated)
        assertEquals(AgentRunState.Cancelled, terminated?.run?.state)
        assertEquals(AgentObservationDecision.Cancel, terminated?.decision)
    }

    @Test(timeout = 10_000L)
    fun terminateRunReturnsNullForAnAlreadyTerminalRun() {
        val store = InMemoryAgentTraceStore(clockMillis = { 1_000L })
        val runtime = terminateRuntime(store)
        val run = store.createRun("已终态的运行")
        store.updateState(run.id, AgentRunState.Completed)

        assertNull(runtime.terminateRun(run.id, "test terminal"))
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    private fun lastTracedDecision(observed: AgentObservationResult): AgentObservationDecision =
        observed.steps
            .filterIsInstance<AgentStep.ObservationDecided>()
            .last()
            .decision

    private fun observePostLaunchWithCapabilities(state: ChatUiState): AgentObservationResult {
        val runtime = postLaunchRuntime(InMemoryAgentTraceStore(clockMillis = { 1_000L }))
        val planned = runtime.runOnce(
            input = OPEN_APP_INPUT,
            installedCapabilities = state.installedCapabilities,
            memoryEnabled = false,
            installedCapabilityProfiles = state.installedCapabilityProfiles,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        return requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = launchedAppResult(planned.plan.request),
            ),
        )
    }

    private fun postLaunchRuntime(traceStore: AgentTraceStore): AgentLoopRuntime =
        AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = OpenAppWithFollowUpActionRuntime(),
            skillRuntime = NoPlanSkillRuntime(),
            traceStore = traceStore,
        )

    private fun terminateRuntime(traceStore: AgentTraceStore): AgentLoopRuntime =
        AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = NoActionPlanningRuntime(),
            traceStore = traceStore,
        )

    private fun launchedAppResult(request: ToolRequest): ToolResult =
        ToolResult(
            requestId = request.id,
            status = ToolStatus.Succeeded,
            summary = "已打开淘宝",
            data = mapOf(
                "toolName" to MobileActionFunctions.OPEN_APP_BY_NAME,
                "completionState" to "ExternalActivityOpened",
                "completionVerified" to "false",
                "externalOutcome" to "Unknown",
                "externalOutcomeSource" to "Unknown",
                "targetKind" to "external_activity",
                "targetPackage" to TARGET_PACKAGE,
                "intentAction" to "android.intent.action.MAIN",
                "metadataPolicy" to "no_raw_payload_persisted",
                "rawPayloadIncluded" to "false",
            ),
        )

    private fun takeOverResult(request: ToolRequest): ToolResult =
        ToolResult(
            requestId = request.id,
            status = ToolStatus.Succeeded,
            summary = "已请求人工接管：需要登录",
            data = mapOf(
                "toolName" to MobileActionFunctions.TAKE_OVER,
                "shouldTakeOver" to "true",
                "takeOverReason" to "需要登录",
                "takeOverPrompt" to "请完成登录后告诉我继续。",
                "privacy" to MessagePrivacy.LocalOnly.name,
                "requiresLocalModel" to "true",
            ),
        )

    /** Records every [savePendingConfirmation] so a test can assert the exact call count. */
    private class CountingPendingConfirmationTraceStore(
        private val delegate: AgentTraceStore,
    ) : AgentTraceStore by delegate {
        var saveCount = 0
            private set
        var lastSavedRequestId: String? = null
            private set

        override fun savePendingConfirmation(snapshot: PendingToolConfirmationSnapshot) {
            saveCount++
            lastSavedRequestId = snapshot.request.id
            delegate.savePendingConfirmation(snapshot)
        }
    }

    /** Simulates a store whose persisted/live views disagree so every CAS attempt fails. */
    private class NeverCompareAndSetTraceStore(
        private val delegate: AgentTraceStore,
    ) : AgentTraceStore by delegate {
        var compareAndSetAttempts = 0
            private set

        override fun compareAndSetState(
            runId: String,
            expectedState: AgentRunState,
            state: AgentRunState,
        ): AgentRun? {
            compareAndSetAttempts++
            return null
        }
    }

    private class OpenAppWithFollowUpActionRuntime : ActionPlanningRuntime {
        override fun isLikelyAction(input: String): Boolean = true

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
            ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.OPEN_APP_BY_NAME,
                        title = "打开淘宝",
                        summary = "将打开淘宝并在应用内继续。",
                        parameters = mapOf(
                            "appName" to "淘宝",
                            "followUpIntent" to FOLLOW_UP_INTENT,
                        ),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test rule plan",
            )
    }

    private class TakeOverActionRuntime : ActionPlanningRuntime {
        override fun isLikelyAction(input: String): Boolean = true

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
            ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.TAKE_OVER,
                        title = "人工接管",
                        summary = "需要用户手动完成登录。",
                        parameters = mapOf(
                            "reason" to "需要登录",
                            "prompt" to "请完成登录后告诉我继续。",
                        ),
                        requiresConfirmation = false,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test rule plan",
            )
    }

    private class NoActionPlanningRuntime : ActionPlanningRuntime {
        override fun isLikelyAction(input: String): Boolean = false

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
            ActionPlanningResult(
                plan = ActionPlan(ActionPlanKind.NoAction),
                usedModel = false,
                fallbackReason = null,
            )
    }

    /** Keeps the run free of skill plans so the observation override is the only decision source. */
    private class NoPlanSkillRuntime : SkillRuntime {
        override fun manifests(): List<SkillManifest> = emptyList()
        override fun plan(input: String): SkillPlan? = null
        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null
    }

    private companion object {
        const val OPEN_APP_INPUT = "打开淘宝"
        const val FOLLOW_UP_INTENT = "搜索耳机"
        const val TARGET_PACKAGE = "com.taobao.taobao"

        /** Mirrors MAX_TERMINATE_RUN_CAS_ATTEMPTS in AgentLoopRuntime (private there by design). */
        const val MAX_EXPECTED_CAS_ATTEMPTS = 8
    }
}
