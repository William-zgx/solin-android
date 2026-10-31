package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ModelBackendKind
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCapabilityProfile
import com.bytedance.zgx.solin.ModelFeature
import com.bytedance.zgx.solin.ModelInputModality
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.ModelToolOutputParseResult
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.SkillManifest
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.skill.SkillRuntime
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolProvider
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResultContinuationPolicy
import com.bytedance.zgx.solin.tool.ToolSpec
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for the initial sequential-segment guard.
 *
 * The guard exists so a tool whose result must be folded into a LOCAL model prompt before the next
 * segment runs is never scheduled as the first step of a multi-segment ("X然后Y") request. It used
 * to consult only the BUILT-IN registry, which silently failed OPEN for module/MCP-provided tools:
 * a private tool absent from the built-in set looked like a plain tool and was planned anyway.
 */
class InitialToolPlannerSequentialTailTest {
    @Test
    fun moduleToolWithLocalEvidencePolicyIsNotPlannedAsASequentialSegment() {
        val planner = plannerWith(
            ToolSpec(
                name = MODULE_TOOL,
                title = "模块工具",
                description = "module-provided tool",
                inputSchemaJson = """{"type":"object","additionalProperties":false}""",
                capability = ToolCapability.Extension,
                riskLevel = RiskLevel.LowReadOnly,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            ),
        )

        assertNull(
            "a LocalEvidence module tool must not start a sequential segment",
            planner.planInitialSequentialSegment(
                input = FIRST_SEGMENT,
                installedCapabilityProfiles = ACTION_MODEL_PROFILES,
                actionModelPath = null,
            ),
        )
    }

    @Test
    fun moduleToolWithPrivateOutputKeysIsNotPlannedAsASequentialSegment() {
        val planner = plannerWith(
            ToolSpec(
                name = MODULE_TOOL,
                title = "模块工具",
                description = "module-provided tool",
                inputSchemaJson = """{"type":"object","additionalProperties":false}""",
                capability = ToolCapability.Extension,
                riskLevel = RiskLevel.LowReadOnly,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                privateOutputKeys = setOf("content"),
            ),
        )

        assertNull(
            "a private-output module tool must not start a sequential segment",
            planner.planInitialSequentialSegment(
                input = FIRST_SEGMENT,
                installedCapabilityProfiles = ACTION_MODEL_PROFILES,
                actionModelPath = null,
            ),
        )
    }

    private fun plannerWith(moduleSpec: ToolSpec): InitialToolPlanner {
        val registry = ToolRegistry(
            object : ToolProvider {
                override fun specs(): List<ToolSpec> = listOf(moduleSpec)
            },
        )
        return InitialToolPlanner(
            toolRegistry = registry,
            skillRuntime = NoSkillRuntime,
            safetyPolicy = SafetyPolicy(),
            actionPlanningRuntime = DraftingActionRuntime(MODULE_TOOL),
            traceStore = InMemoryAgentTraceStore(),
        )
    }

    private object NoSkillRuntime : SkillRuntime {
        override fun manifests(): List<SkillManifest> = emptyList()

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null
    }

    /** A runtime that always "drafts" [toolName], standing in for the action model. */
    private class DraftingActionRuntime(private val toolName: String) : ActionPlanningRuntime {
        override fun isLikelyAction(input: String): Boolean = true

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
            ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = toolName,
                        title = "模块工具",
                        summary = "drafted module tool",
                        parameters = emptyMap(),
                    ),
                ),
                usedModel = true,
                fallbackReason = null,
            )

        override fun parseModelToolOutput(output: String): ModelToolOutputParseResult =
            ModelToolOutputParseResult.None
    }

    private companion object {
        const val MODULE_TOOL = "module.private.read"
        const val FIRST_SEGMENT = "先读取模块内容"

        /**
         * A MobileAction-capable profile, so the "missing model" rejection cannot mask the guard
         * under test. Without it every case here would return MissingModel regardless of the tool's
         * continuation policy and the regression would pass for the wrong reason.
         */
        val ACTION_MODEL_PROFILES = listOf(
            ModelCapabilityProfile(
                id = "test-action",
                displayName = "test action model",
                capability = ModelCapability.MobileAction,
                backendKind = ModelBackendKind.LocalLiteRt,
                inputModalities = setOf(ModelInputModality.Text),
                features = setOf(ModelFeature.MobileActionPlanning),
            ),
        )
    }
}
