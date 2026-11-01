package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2: progressive-fallback search via [SkillStep.BranchStep]. A branch after a search/verify step
 * skips the fallback step when the search verified, or falls into it (a simplified re-query) when
 * it did not — a forward-only, fail-closed control-flow construct.
 *
 * Branch resolution is exercised through [SkillRunProgressor], the production path that walks the
 * plan across turns.
 */
class SkillBranchStepTest {
    private val registry = ToolRegistry()
    private val progressor = SkillRunProgressor(toolRegistry = registry)

    // Plan shape: search → branch(verified? → done : fallbackSearch) → fallbackSearch → done
    private fun searchWithFallbackPlan(): SkillPlan {
        val search = testToolStep(
            id = "search",
            requestId = "req-search",
            toolName = MobileActionFunctions.UI_WAIT,
            arguments = mapOf("verifySearchQuery" to "海河牛奶 兰州"),
            title = "验证搜索结果",
            summary = "提交搜索后验证结果。",
        )
        val branch = SkillStep.BranchStep(
            id = "branch_on_verified",
            dependsOn = listOf(search.id),
            condition = StepOutcomeCondition.SearchVerified,
            onMatchStepId = "done",
            onElseStepId = "fallback_search",
        )
        val fallback = testToolStep(
            id = "fallback_search",
            dependsOn = listOf(search.id),
            requestId = "req-fallback",
            toolName = MobileActionFunctions.UI_WAIT,
            arguments = mapOf("verifySearchQuery" to "海河牛奶"),
            title = "回退简化检索",
            summary = "去掉厂家修饰，仅用药品名重试。",
        )
        val done = testToolStep(
            id = "done",
            dependsOn = emptyList(),
            requestId = "req-done",
            toolName = MobileActionFunctions.UI_WAIT,
            title = "完成",
            summary = "检索完成。",
        )
        return testSkillPlan(
            skillId = "test.search_fallback",
            requiredTools = listOf(MobileActionFunctions.UI_WAIT),
            steps = listOf(search, branch, fallback, done),
        )
    }

    private fun waitResult(verified: Boolean): ToolResult = ToolResult(
        requestId = "req-search",
        status = ToolStatus.Succeeded,
        summary = if (verified) "结果已验证" else "未验证",
        data = mapOf(
            "toolName" to MobileActionFunctions.UI_WAIT,
            "privacy" to "LocalOnly",
            "requiresLocalModel" to "true",
            "source" to "accessibility_active_window",
            "metadataPolicy" to "accessibility_control_local_only_transient_node_ids_no_pixels_persisted",
            "actionType" to "wait",
            "status" to "succeeded",
            "retryable" to "false",
            "summary" to if (verified) "结果已验证" else "未验证",
            "beforeObservationId" to "before-1",
            "afterObservationId" to "after-1",
            "verificationSummary" to if (verified) "已验证搜索结果" else "未能验证搜索结果",
            "searchVerificationStatus" to if (verified) "verified" else "not_verified",
            "uiActionOutcome" to if (verified) "verified" else "no_change",
        ),
    )

    @Test
    fun verifiedSearchSkipsFallbackViaBranch() {
        val progression = progressor.nextToolAfterToolResult(
            skillPlan = searchWithFallbackPlan(),
            requestedRequestIds = setOf("req-search"),
            result = waitResult(verified = true),
        )

        require(progression is SkillToolResultProgression.BoundTool)
        // Branch matched (verified) → the next step is `done`, skipping the fallback search entirely.
        assertEquals("done", progression.toolStep.id)
        assertEquals("req-done", progression.request.id)
    }

    @Test
    fun unverifiedSearchFallsIntoFallbackQueryViaBranch() {
        val progression = progressor.nextToolAfterToolResult(
            skillPlan = searchWithFallbackPlan(),
            requestedRequestIds = setOf("req-search"),
            result = waitResult(verified = false),
        )

        require(progression is SkillToolResultProgression.BoundTool)
        // Branch did not match → the next step is the fallback search with the simplified query.
        assertEquals("fallback_search", progression.toolStep.id)
        assertEquals("海河牛奶", progression.request.arguments["verifySearchQuery"])
    }

    @Test
    fun validateStructureRejectsBackwardBranchTarget() {
        val stepA = testToolStep(id = "a", requestId = "req-a", toolName = MobileActionFunctions.UI_WAIT)
        val stepB = testToolStep(id = "b", dependsOn = listOf("a"), requestId = "req-b", toolName = MobileActionFunctions.UI_WAIT)
        val backwardBranch = SkillStep.BranchStep(
            id = "bad_branch",
            dependsOn = listOf("b"),
            condition = StepOutcomeCondition.SearchVerified,
            onMatchStepId = "a", // backward → must be rejected
        )
        val plan = testSkillPlan(
            skillId = "test.backward_branch",
            requiredTools = listOf(MobileActionFunctions.UI_WAIT),
            steps = listOf(stepA, stepB, backwardBranch),
        )

        val validation = plan.validateStructure(registry)

        assertFalse(validation.isValid)
        assertTrue(validation.errors.any { it.contains("forward-only") })
    }

    @Test
    fun validateStructureRejectsUnknownBranchTarget() {
        val stepA = testToolStep(id = "a", requestId = "req-a", toolName = MobileActionFunctions.UI_WAIT)
        val branch = SkillStep.BranchStep(
            id = "branch",
            dependsOn = listOf("a"),
            condition = StepOutcomeCondition.SearchVerified,
            onMatchStepId = "nonexistent",
        )
        val plan = testSkillPlan(
            skillId = "test.unknown_branch",
            requiredTools = listOf(MobileActionFunctions.UI_WAIT),
            steps = listOf(stepA, branch),
        )

        val validation = plan.validateStructure(registry)

        assertFalse(validation.isValid)
        assertTrue(validation.errors.any { it.contains("unknown step") })
    }

    @Test
    fun checkpointRoundTripsWhenBranchPrecedesPendingConfirmation() {
        // Regression: a value-free checkpoint whose completed prefix includes a BranchStep must
        // validate. The build path records the branch id with empty output keys; the validate path
        // must not reject that (else the run is killed on process-death restore).
        val plan = searchWithFallbackPlan() // steps: search -> branch -> fallback_search -> done
        val doneStep = plan.steps.first { it is SkillStep.ToolStep && it.id == "done" } as SkillStep.ToolStep

        val checkpoint = requireNotNull(
            plan.valueFreeCheckpointForPendingTool(
                runId = "run-branch-checkpoint",
                pendingRequest = doneStep.request,
                toolRegistry = registry,
            ),
        )

        assertNull("checkpoint with a completed BranchStep must validate", checkpoint.validationErrorFor(plan, registry))
        assertTrue("branch id must be in the completed prefix", checkpoint.completedStepIds.contains("branch_on_verified"))
    }
}
