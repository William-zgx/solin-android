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
import com.bytedance.zgx.solin.action.IntentRoutingPath
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.MobileActionPlanner
import com.bytedance.zgx.solin.action.ModelToolOutputParseResult
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.SkillManifest
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.skill.SkillRuntime
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.isFirstStepPlanningCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InitialPlanningMode.LocalModelFirst] must either accept a model tool through the same registry,
 * safety and confirmation gates as a rule draft, or produce exactly the [InitialPlanningMode.RuleFirst]
 * plan. The "rules" here are scripted so each case controls whether they plan; the model output goes
 * through the real parser, registry, safety policy and retriever.
 */
class LocalModelFirstPlanningTest {
    private val traceStore = InMemoryAgentTraceStore()

    @Test
    fun withoutAModelStrategyAndAVerifiedModelItPlansExactlyLikeRuleFirst() {
        val noModelOptions = listOf(
            AgentRunOptions(),
            localModelFirst(LocalPlanningStrategy.RuleFirst),
            localModelFirst(LocalPlanningStrategy.ModelFirst, modelPath = null),
            localModelFirst(LocalPlanningStrategy.ModelFirstWithRuleFastPath, modelPath = null),
        )

        noModelOptions.forEach { options ->
            listOf(RULE_HIT_REQUEST, ALARM_REQUEST, QUESTION).forEach { input ->
                val runtime = ScriptedPlanningRuntime(modelOutput = ALARM_CALL)
                val planner = planner(runtime)

                assertEquals(
                    "$options / $input",
                    planner.planToolIfSupported(input, ACTION_MODEL_PROFILES, actionModelPath = null).shape(),
                    planner.planLocalModelFirst(runId(input), input, ACTION_MODEL_PROFILES, null, options).shape(),
                )
                assertEquals("$options / $input", 0, runtime.modelCallCount)
            }
        }
    }

    @Test
    fun withoutAnInstalledPlanningModelProfileItPlansExactlyLikeRuleFirst() {
        LocalPlanningStrategy.entries.forEach { strategy ->
            val runtime = ScriptedPlanningRuntime(modelOutput = ALARM_CALL)
            val planner = planner(runtime)

            assertEquals(
                planner.planToolIfSupported(RULE_HIT_REQUEST, emptyList(), actionModelPath = null).shape(),
                planner.planLocalModelFirst(
                    runId(RULE_HIT_REQUEST),
                    RULE_HIT_REQUEST,
                    emptyList(),
                    null,
                    localModelFirst(strategy),
                ).shape(),
            )
            assertEquals(0, runtime.modelCallCount)
        }
    }

    @Test
    fun ruleFastPathKeepsARulePlanWithoutAskingTheModel() {
        val runtime = ScriptedPlanningRuntime(modelOutput = ALARM_CALL)
        val planner = planner(runtime)

        val plan = planner.planLocalModelFirst(
            runId(RULE_HIT_REQUEST),
            RULE_HIT_REQUEST,
            ACTION_MODEL_PROFILES,
            null,
            localModelFirst(LocalPlanningStrategy.ModelFirstWithRuleFastPath),
        )

        assertNotNull(plan)
        assertEquals(
            planner.planToolIfSupported(RULE_HIT_REQUEST, ACTION_MODEL_PROFILES, actionModelPath = null).shape(),
            plan.shape(),
        )
        assertEquals(0, runtime.modelCallCount)
    }

    @Test
    fun aModelToolPassesTheRegistryAndSafetyAndStillNeedsConfirmation() {
        LocalPlanningStrategy.entries.filter { it != LocalPlanningStrategy.RuleFirst }.forEach { strategy ->
            val runtime = ScriptedPlanningRuntime(modelOutput = ALARM_CALL)

            val plan = planner(runtime).planLocalModelFirst(
                runId(ALARM_REQUEST),
                ALARM_REQUEST,
                ACTION_MODEL_PROFILES,
                null,
                localModelFirst(strategy),
            )

            require(plan is AgentPlan.UseTool) { "$strategy planned $plan" }
            assertEquals(MobileActionFunctions.SET_SYSTEM_ALARM, plan.request.toolName)
            assertEquals(mapOf("hour" to "7", "minutes" to "30"), plan.request.arguments)
            assertTrue(plan.plannedByModel)
            assertEquals("local model tool call", plan.fallbackReason)
            assertEquals(IntentRoutingPath.ModelToolCall, plan.routingPath())
            assertEquals(SafetyOutcome.RequireConfirmation, plan.safetyDecision.outcome)
            assertTrue(plan.requiresUserConfirmation())
            assertEquals(listOf(ALARM_REQUEST), runtime.modelInputs)
        }
    }

    @Test
    fun everyFailedModelProposalYieldsTheRuleFirstPlan() {
        val failingModels = listOf(
            "blank output" to ScriptedPlanningRuntime(modelOutput = ""),
            "prose" to ScriptedPlanningRuntime(modelOutput = "好的，我来帮你设置闹钟"),
            "missing required argument" to ScriptedPlanningRuntime(modelOutput = """call:set_system_alarm{"hour":7}"""),
            "argument out of range" to
                ScriptedPlanningRuntime(modelOutput = """call:set_system_alarm{"hour":31,"minutes":0}"""),
            "registered tool that was not offered" to
                ScriptedPlanningRuntime(modelOutput = """call:dial_phone_number{"phoneNumber":"10086"}"""),
            "unregistered tool" to ScriptedPlanningRuntime(modelOutput = "call:launch_rockets{}"),
            "two calls" to ScriptedPlanningRuntime(
                modelOutput = """call:set_system_alarm{"hour":7,"minutes":30} call:set_system_alarm{"hour":8,"minutes":0}""",
            ),
            "model error" to ScriptedPlanningRuntime(modelError = IllegalStateException("engine failed")),
        )

        failingModels.forEach { (case, runtime) ->
            val planner = planner(runtime)
            listOf(LocalPlanningStrategy.ModelFirstWithRuleFastPath, LocalPlanningStrategy.ModelFirst).forEach { strategy ->
                listOf(RULE_HIT_REQUEST, ALARM_REQUEST, OPEN_APP_REQUEST).forEach { input ->
                    assertEquals(
                        "$case / $strategy / $input",
                        planner.planToolIfSupported(input, ACTION_MODEL_PROFILES, actionModelPath = null).shape(),
                        planner.planLocalModelFirst(runId(input), input, ACTION_MODEL_PROFILES, null, localModelFirst(strategy))
                            .shape(),
                    )
                }
            }
            assertTrue(case, runtime.modelCallCount > 0)
        }
    }

    @Test
    fun aRuleDraftCarriedByAFailedModelAttemptIsNotTreatedAsAModelPlan() {
        val runtime = ScriptedPlanningRuntime(modelOutput = "call:launch_rockets{}")

        val plan = planner(runtime).planLocalModelFirst(
            runId(RULE_HIT_REQUEST),
            RULE_HIT_REQUEST,
            ACTION_MODEL_PROFILES,
            null,
            localModelFirst(LocalPlanningStrategy.ModelFirst),
        )

        require(plan is AgentPlan.UseTool)
        assertFalse(plan.plannedByModel)
        assertEquals(1, runtime.modelCallCount)
    }

    @Test
    fun theNoToolChoiceAnswersWithoutATool() {
        val modelFirstRuntime = ScriptedPlanningRuntime(modelOutput = "call:answer{}")
        val fastPathRuntime = ScriptedPlanningRuntime(modelOutput = "call:answer{}")

        assertNull(
            planner(modelFirstRuntime).planLocalModelFirst(
                runId(RULE_HIT_REQUEST),
                RULE_HIT_REQUEST,
                ACTION_MODEL_PROFILES,
                null,
                localModelFirst(LocalPlanningStrategy.ModelFirst),
            ),
        )
        assertNull(
            planner(fastPathRuntime).planLocalModelFirst(
                runId(ALARM_REQUEST),
                ALARM_REQUEST,
                ACTION_MODEL_PROFILES,
                null,
                localModelFirst(LocalPlanningStrategy.ModelFirstWithRuleFastPath),
            ),
        )
        assertEquals(1, modelFirstRuntime.modelCallCount)
        assertEquals(1, fastPathRuntime.modelCallCount)
    }

    @Test
    fun theModelIsOfferedTheRetrievedShortlistPlusTheNoToolChoice() {
        val runtime = ScriptedPlanningRuntime(modelOutput = ALARM_CALL)

        planner(runtime).planLocalModelFirst(
            runId(ALARM_REQUEST),
            ALARM_REQUEST,
            ACTION_MODEL_PROFILES,
            null,
            localModelFirst(LocalPlanningStrategy.ModelFirst),
        )

        val offered = runtime.offeredToolSpecs.single()
        assertEquals(LOCAL_MODEL_NO_TOOL_CHOICE, offered.last().name)
        assertEquals(1, offered.count { spec -> spec.name == LOCAL_MODEL_NO_TOOL_CHOICE })
        val tools = offered.dropLast(1)
        assertTrue(tools.map { it.name }.toString(), MobileActionFunctions.SET_SYSTEM_ALARM in tools.map { it.name })
        assertTrue(tools.all { spec -> spec.isFirstStepPlanningCandidate() })
        assertTrue(tools.none { spec -> spec.capability == ToolCapability.DeviceControl })
        assertTrue(tools.size <= 16)
    }

    @Test
    fun aRequestThatRetrievesNoToolNeverReachesTheModel() {
        val runtime = ScriptedPlanningRuntime(modelOutput = ALARM_CALL)
        val runId = runId(QUESTION)

        val plan = planner(runtime).planLocalModelFirst(
            runId,
            QUESTION,
            ACTION_MODEL_PROFILES,
            null,
            localModelFirst(LocalPlanningStrategy.ModelFirst),
        )

        assertNull(plan)
        assertEquals(0, runtime.modelCallCount)
        assertTrue("local_model_no_tool_retrieved" in localModelDecision(runId).rejectionReasons)
    }

    @Test
    fun aSequentialRequestOnlyShowsTheModelItsFirstSegment() {
        val runtime = ScriptedPlanningRuntime(modelOutput = ALARM_CALL)
        val input = "${ALARM_REQUEST}然后$OPEN_APP_REQUEST"

        val plan = planner(runtime).planLocalModelFirst(
            runId(input),
            input,
            ACTION_MODEL_PROFILES,
            null,
            localModelFirst(LocalPlanningStrategy.ModelFirstWithRuleFastPath),
        )

        val modelInput = runtime.modelInputs.single()
        assertTrue(modelInput, "闹钟" in modelInput)
        assertFalse(modelInput, "微信" in modelInput)
        assertEquals(MobileActionFunctions.SET_SYSTEM_ALARM, (plan as AgentPlan.UseTool).request.toolName)
    }

    @Test
    fun theTraceRecordsProvenanceButNoArgumentValues() {
        val runtime = ScriptedPlanningRuntime(
            modelOutput = """call:set_system_alarm{"hour":7,"minutes":30,"message":"按时吃降压药"}""",
        )
        val runId = runId(ALARM_REQUEST)

        planner(runtime).planLocalModelFirst(
            runId,
            ALARM_REQUEST,
            ACTION_MODEL_PROFILES,
            null,
            localModelFirst(LocalPlanningStrategy.ModelFirstWithRuleFastPath),
        )

        val decision = localModelDecision(runId)
        assertTrue(decision.accepted)
        assertEquals(IntentRoutingPath.ModelToolCall, decision.selectedPath)
        assertEquals(MobileActionFunctions.SET_SYSTEM_ALARM, decision.selectedToolName)
        val reasons = decision.rejectionReasons
        assertTrue(reasons.toString(), "local_model_tool_accepted" in reasons)
        assertTrue(reasons.toString(), "strategy=ModelFirstWithRuleFastPath" in reasons)
        // Argument keys are sorted, and only keys are traced.
        assertTrue(reasons.toString(), "output=call:set_system_alarm{hour,message,minutes}" in reasons)
        assertTrue(reasons.toString(), "retrieved=${MobileActionFunctions.SET_SYSTEM_ALARM}" in reasons)
        assertTrue(reasons.toString(), reasons.none { reason -> "降压药" in reason || reason.endsWith("=30") || reason == "30" })
        assertTrue(reasons.all { reason -> reason.length <= 80 })
    }

    @Test
    fun agentLoopRuntimeParksAModelPlannedToolForConfirmation() {
        val runtime = ScriptedPlanningRuntime(modelOutput = ALARM_CALL)
        val loop = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = runtime,
            traceStore = traceStore,
        )

        val result = loop.runOnce(
            input = ALARM_REQUEST,
            installedCapabilities = setOf(ModelCapability.Chat, ModelCapability.MobileAction),
            memoryEnabled = false,
            options = localModelFirst(LocalPlanningStrategy.ModelFirst),
            installedCapabilityProfiles = ACTION_MODEL_PROFILES,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        val plan = result.plan as AgentPlan.UseTool
        assertEquals(MobileActionFunctions.SET_SYSTEM_ALARM, plan.request.toolName)
        assertTrue(plan.plannedByModel)
        assertTrue(plan.requiresUserConfirmation())
        assertEquals(1, runtime.modelCallCount)
        // The planner records the one routing decision for the proposal; the observation path adds none.
        val routed = traceStore.steps(result.run.id)
            .filterIsInstance<AgentStep.IntentRouted>()
            .filter { step -> step.decision.selectedPath == IntentRoutingPath.ModelToolCall }
        assertEquals(1, routed.size)
        assertTrue(routed.single().decision.accepted)
    }

    /**
     * A request that starts with a negation never reaches the planning model, under either
     * strategy, and the plan is the RuleFirst plan. Pins the guard before the model call.
     */
    @Test
    fun aNegatedRequestNeverReachesThePlanningModel() {
        listOf("别启动微信", "不要分享我的号码给别人").forEach { request ->
            listOf(LocalPlanningStrategy.ModelFirstWithRuleFastPath, LocalPlanningStrategy.ModelFirst).forEach { strategy ->
                val runtime = ScriptedPlanningRuntime(modelOutput = """call:open_app_by_name{"appName":"微信"}""")
                val planner = planner(runtime)
                assertEquals(
                    "$request / $strategy",
                    planner.planToolIfSupported(request, ACTION_MODEL_PROFILES, actionModelPath = null).shape(),
                    planner.planLocalModelFirst(runId(request), request, ACTION_MODEL_PROFILES, null, localModelFirst(strategy))
                        .shape(),
                )
                assertEquals("$request / $strategy", 0, runtime.modelCallCount)
            }
        }
    }

    /** Every failure names its stable trace slug, not only the RuleFirst plan it falls back to. */
    @Test
    fun failureReasonsAreStableTraceSlugs() {
        val cases = listOf(
            Triple(ALARM_REQUEST, ScriptedPlanningRuntime(modelOutput = ""), "local_model_blank_output"),
            Triple(ALARM_REQUEST, ScriptedPlanningRuntime(modelOutput = "好的，我来帮你设置闹钟"), "local_model_no_call_output"),
            Triple(ALARM_REQUEST, ScriptedPlanningRuntime(modelError = IllegalStateException("engine failed")), "local_model_planning_error"),
            Triple(ALARM_REQUEST, ScriptedPlanningRuntime(modelThrows = IllegalStateException("engine failed")), "local_model_planning_error"),
            Triple(QUESTION, ScriptedPlanningRuntime(modelOutput = ALARM_CALL), "local_model_no_tool_retrieved"),
        )
        cases.forEach { (input, runtime, slug) ->
            val runId = runId(input)
            planner(runtime).planLocalModelFirst(runId, input, ACTION_MODEL_PROFILES, null, localModelFirst(LocalPlanningStrategy.ModelFirst))
            val decision = localModelDecision(runId)
            assertTrue("$slug in ${decision.rejectionReasons}", slug in decision.rejectionReasons)
            assertFalse(decision.accepted)
        }
    }

    @Test
    fun agentLoopRuntimeWithDefaultOptionsNeverAsksThePlanningModel() {
        val runtime = ScriptedPlanningRuntime(modelOutput = ALARM_CALL)
        val loop = AgentLoopRuntime(memoryIndex = MemoryRepository(), actionPlanningRuntime = runtime)

        loop.runOnce(
            input = ALARM_REQUEST,
            installedCapabilities = setOf(ModelCapability.Chat, ModelCapability.MobileAction),
            memoryEnabled = false,
            installedCapabilityProfiles = ACTION_MODEL_PROFILES,
        )

        assertEquals(0, runtime.modelCallCount)
    }

    private fun planner(runtime: ActionPlanningRuntime): InitialToolPlanner =
        InitialToolPlanner(
            toolRegistry = ToolRegistry(),
            skillRuntime = NoSkillRuntime,
            safetyPolicy = SafetyPolicy(),
            actionPlanningRuntime = runtime,
            traceStore = traceStore,
        )

    private fun runId(input: String): String = traceStore.createRun(input).id

    private fun localModelDecision(runId: String) =
        traceStore.steps(runId)
            .filterIsInstance<AgentStep.IntentRouted>()
            .single { step -> step.decision.selectedPath == IntentRoutingPath.ModelToolCall }
            .decision

    private fun localModelFirst(
        strategy: LocalPlanningStrategy,
        modelPath: String? = MODEL_PATH,
    ): AgentRunOptions =
        AgentRunOptions(
            initialPlanningMode = InitialPlanningMode.LocalModelFirst,
            localPlanningStrategy = strategy,
            localPlanningModelPath = modelPath,
        )

    /** Request ids are random, so plans compare by what they would do. */
    private fun AgentPlan?.shape(): String =
        when (this) {
            null -> "no plan"
            is AgentPlan.UseTool -> listOf(
                "use",
                request.toolName,
                request.arguments,
                plannedByModel,
                fallbackReason,
                safetyDecision.outcome,
            ).joinToString("|")
            is AgentPlan.RejectedTool -> "rejected|${result.summary}"
            is AgentPlan.MissingModel -> "missing|$capability"
            is AgentPlan.Answer -> "answer"
        }

    private object NoSkillRuntime : SkillRuntime {
        override fun manifests(): List<SkillManifest> = emptyList()

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null
    }

    /**
     * Stands in for HybridActionPlanningRuntime: the two-argument plan is the rule planner (scripted
     * per request), and the tool-scoped plan parses [modelOutput] with the real parser. As on device,
     * a failed model attempt returns the rule plan with `usedModel = false` and the parser's reason.
     */
    private class ScriptedPlanningRuntime(
        private val modelOutput: String? = null,
        /** Reported the way HybridActionPlanningRuntime reports an engine exception: as a failure reason. */
        private val modelError: Throwable? = null,
        /** An exception that escapes the runtime, which the planner must still fail closed on. */
        private val modelThrows: Throwable? = null,
        private val ruleDrafts: Map<String, ActionDraft> = mapOf(RULE_HIT_REQUEST to RULE_HIT_DRAFT),
    ) : ActionPlanningRuntime {
        private val parser = MobileActionPlanner()
        val modelInputs = mutableListOf<String>()
        val offeredToolSpecs = mutableListOf<List<ToolSpec>>()
        val modelCallCount: Int get() = modelInputs.size

        override fun isLikelyAction(input: String): Boolean = input in ruleDrafts

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
            ActionPlanningResult(plan = rulePlan(input), usedModel = false, fallbackReason = null)

        override fun plan(input: String, actionModelPath: String?, toolSpecs: List<ToolSpec>): ActionPlanningResult {
            modelInputs += input
            offeredToolSpecs += toolSpecs
            modelThrows?.let { throw it }
            modelError?.let { error ->
                return ActionPlanningResult(
                    plan = rulePlan(input),
                    usedModel = false,
                    fallbackReason = null,
                    modelAttempted = true,
                    modelOutputPreview = null,
                    modelFailureReason = "${error.javaClass.simpleName}:${error.message}",
                )
            }
            val output = modelOutput.orEmpty()
            parser.parseModelOutput(output)?.let { draft ->
                return ActionPlanningResult(
                    plan = ActionPlan(ActionPlanKind.Draft, draft),
                    usedModel = true,
                    fallbackReason = null,
                    modelOutputPreview = output,
                )
            }
            return ActionPlanningResult(
                plan = rulePlan(input),
                usedModel = false,
                fallbackReason = "动作规划模型未产出可执行草稿",
                modelAttempted = true,
                modelOutputPreview = output.ifBlank { null },
                modelFailureReason = when (val parsed = parser.parseModelToolOutput(output)) {
                    ModelToolOutputParseResult.None ->
                        if (output.isBlank()) "blank_model_output" else "missing_call_tool_output"
                    is ModelToolOutputParseResult.Parsed -> "model_tool_call_not_accepted"
                    is ModelToolOutputParseResult.Rejected -> parsed.reason
                },
            )
        }

        override fun parseModelToolOutput(output: String): ModelToolOutputParseResult =
            parser.parseModelToolOutput(output)

        private fun rulePlan(input: String): ActionPlan =
            ruleDrafts[input]?.let { draft -> ActionPlan(ActionPlanKind.Draft, draft) }
                ?: ActionPlan(ActionPlanKind.NoAction)
    }

    private companion object {
        const val MODEL_PATH = "/data/models/gemma.litertlm"
        const val RULE_HIT_REQUEST = "打开微信"
        const val ALARM_REQUEST = "定个早上七点半的闹钟"
        const val OPEN_APP_REQUEST = "打开小红书"
        const val QUESTION = "为什么天空是蓝色的"
        const val ALARM_CALL = """call:set_system_alarm{"hour":7,"minutes":30}"""

        val RULE_HIT_DRAFT = ActionDraft(
            functionName = MobileActionFunctions.OPEN_APP_BY_NAME,
            title = "按名称打开应用",
            summary = "打开微信",
            parameters = mapOf("appName" to "微信"),
        )

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
    }
}
