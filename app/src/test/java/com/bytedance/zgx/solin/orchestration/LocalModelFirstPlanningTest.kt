package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ModelBackendKind
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCapabilityProfile
import com.bytedance.zgx.solin.ModelFeature
import com.bytedance.zgx.solin.ModelInputModality
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionIntentGateResult
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.HybridActionPlanningRuntime
import com.bytedance.zgx.solin.action.IntentRoutingPath
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.MobileActionPlanner
import com.bytedance.zgx.solin.action.ModelToolOutputParseResult
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.runtime.DedicatedLiteRtEngine
import com.bytedance.zgx.solin.runtime.FakeLiteRtFactory
import com.bytedance.zgx.solin.runtime.LocalAccelerator
import com.bytedance.zgx.solin.runtime.LocalCallMetrics
import com.bytedance.zgx.solin.runtime.LocalEngine
import com.bytedance.zgx.solin.runtime.LocalEngineCapabilities
import com.bytedance.zgx.solin.runtime.LocalEngineSource
import com.bytedance.zgx.solin.runtime.LocalOneShotRequest
import com.bytedance.zgx.solin.runtime.LocalOneShotResult
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.SkillManifest
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.skill.SkillRequest
import com.bytedance.zgx.solin.skill.SkillRuntime
import com.bytedance.zgx.solin.skill.SkillStep
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolPermission
import com.bytedance.zgx.solin.tool.ToolProvider
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.isFirstStepPlanningCandidate
import java.nio.file.Files
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
    fun ruleFirstGatedModelKeepsARulePlanWithoutCallingTheGateOrPlanner() {
        val runtime = ScriptedPlanningRuntime(modelOutput = ALARM_CALL)
        val planner = planner(runtime)

        val plan = planner.planLocalModelFirst(
            runId(RULE_HIT_REQUEST),
            RULE_HIT_REQUEST,
            ACTION_MODEL_PROFILES,
            null,
            localModelFirst(LocalPlanningStrategy.RuleFirstGatedModel),
        )

        assertEquals(
            planner.planToolIfSupported(RULE_HIT_REQUEST, ACTION_MODEL_PROFILES, actionModelPath = null).shape(),
            plan.shape(),
        )
        assertTrue(runtime.gateInputs.isEmpty())
        assertEquals(0, runtime.modelCallCount)
    }

    @Test
    fun ruleFirstGatedModelAcceptedProposalKeepsLocalModelProvenanceAndAlwaysAsks() {
        val runtime = ScriptedPlanningRuntime(
            modelOutput = """call:open_app_by_name{"appName":"小红书"}""",
        )
        val actualRunId = runId(OPEN_APP_REQUEST)

        val plan = planner(runtime).planLocalModelFirst(
            actualRunId,
            OPEN_APP_REQUEST,
            ACTION_MODEL_PROFILES,
            null,
            localModelFirst(LocalPlanningStrategy.RuleFirstGatedModel),
        )

        require(plan is AgentPlan.UseTool)
        assertTrue(plan.plannedByModel)
        assertEquals(listOf(OPEN_APP_REQUEST), runtime.gateInputs)
        assertEquals(listOf(OPEN_APP_REQUEST), runtime.modelInputs)
        val registry = ToolRegistry()
        val policy = ConfirmationBypassPolicy(
            toolRegistry = registry,
            traceStore = traceStore,
            traceQueries = AgentTraceQueries(traceStore),
            isCriticalOnlyConfirmationEnabled = { true },
        )
        assertEquals(
            CriticalApprovalReason.LocalModelProposedStep,
            policy.criticalApprovalReason(actualRunId, plan.request),
        )
        assertFalse(policy.isAutoApproved(actualRunId, plan.request))
        val decision = localModelDecision(actualRunId)
        assertTrue(decision.rejectionReasons.contains("local_model_action_gate_open"))
        assertTrue(decision.rejectionReasons.contains(localModelProposedRequestTag(plan.request.id)))
    }

    @Test
    fun ruleFirstGatedModelClosedInvalidUnavailableAndBusyGatesMeanNoTool() {
        listOf(
            "local_model_action_gate_no_action",
            "local_model_action_gate_invalid_output",
            "local_model_action_gate_failed",
            "local_model_action_gate_busy",
        ).forEach { gateReason ->
            val runtime = ScriptedPlanningRuntime(
                modelOutput = ALARM_CALL,
                gateResult = ActionIntentGateResult(open = false, reason = gateReason),
            )
            val actualRunId = runId(ALARM_REQUEST)

            val plan = planner(runtime).planLocalModelFirst(
                actualRunId,
                ALARM_REQUEST,
                ACTION_MODEL_PROFILES,
                null,
                localModelFirst(LocalPlanningStrategy.RuleFirstGatedModel),
            )

            assertNull(gateReason, plan)
            assertEquals(listOf(ALARM_REQUEST), runtime.gateInputs)
            assertEquals(0, runtime.modelCallCount)
            val reasons = localModelDecision(actualRunId).rejectionReasons
            assertTrue(reasons.toString(), gateReason in reasons)
            assertTrue(reasons.toString(), reasons.none { reason -> ALARM_REQUEST in reason })
        }
    }

    @Test
    fun ruleFirstGatedModelRejectsNonJsonSyntaxWithoutCallingToolPlanner() {
        val malformedOutputs = listOf(
            """{action:true,confidence:0.9}""",
            """{'action':true,'confidence':0.9}""",
            """{"action":true,"confidence":0.9,}""",
            """{"action":true;"confidence":0.9}""",
            """{"action":true=>"confidence":0.9}""",
            """{"action":false,"action":true,"confidence":0.9}""",
            "﻿{\"action\":true,\"confidence\":0.9}",
            "// comment\n{\"action\":true,\"confidence\":0.9}",
        )
        val engine = ScriptedGateEngine()
        val gateRuntime = HybridActionPlanningRuntime(
            fallbackEngine = DedicatedLiteRtEngine(
                Files.createTempDirectory("strict-action-gate").toFile(),
                FakeLiteRtFactory(),
            ),
            sharedEngine = engine,
        )

        try {
            malformedOutputs.forEach { output ->
                engine.output = output
                val runtime = ScriptedPlanningRuntime(
                    modelOutput = ALARM_CALL,
                    gateDelegate = gateRuntime,
                )
                val actualRunId = runId(ALARM_REQUEST)

                val plan = planner(runtime).planLocalModelFirst(
                    actualRunId,
                    ALARM_REQUEST,
                    ACTION_MODEL_PROFILES,
                    null,
                    localModelFirst(LocalPlanningStrategy.RuleFirstGatedModel),
                )

                assertNull(output, plan)
                assertEquals(output, listOf(ALARM_REQUEST), runtime.gateInputs)
                assertEquals(output, 0, runtime.modelCallCount)
                val reasons = localModelDecision(actualRunId).rejectionReasons
                assertTrue(output, "local_model_action_gate_invalid_output" in reasons)
            }
        } finally {
            gateRuntime.close()
        }
    }

    @Test
    fun ruleFirstGatedModelFiltersWebSearchUnlessTheRequestHasASearchVerb() {
        val implicitSearch = "最新 AI 新闻"
        val implicitRuntime = ScriptedPlanningRuntime(
            modelOutput = """call:web_search{"query":"AI 新闻"}""",
        )
        val implicitRunId = runId(implicitSearch)

        val implicitPlan = planner(implicitRuntime).planLocalModelFirst(
            implicitRunId,
            implicitSearch,
            ACTION_MODEL_PROFILES,
            null,
            localModelFirst(LocalPlanningStrategy.RuleFirstGatedModel),
        )

        assertNull(implicitPlan)
        assertEquals(1, implicitRuntime.gateInputs.size)
        assertEquals(0, implicitRuntime.modelCallCount)
        val implicitReasons = localModelDecision(implicitRunId).rejectionReasons
        assertTrue("local_model_no_tool_retrieved" in implicitReasons)
        assertTrue(implicitReasons.none { reason -> reason == "retrieved=web_search" })

        val explicitSearch = "搜索最新 AI 新闻"
        val explicitRuntime = ScriptedPlanningRuntime(
            modelOutput = """call:web_search{"query":"AI 新闻"}""",
        )

        val explicitPlan = planner(explicitRuntime).planLocalModelFirst(
            runId(explicitSearch),
            explicitSearch,
            ACTION_MODEL_PROFILES,
            null,
            localModelFirst(LocalPlanningStrategy.RuleFirstGatedModel),
        )

        require(explicitPlan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.WEB_SEARCH, explicitPlan.request.toolName)
        assertEquals(1, explicitRuntime.gateInputs.size)
        assertEquals(1, explicitRuntime.modelCallCount)
        assertTrue(
            explicitRuntime.offeredToolSpecs.single().any { spec -> spec.name == MobileActionFunctions.WEB_SEARCH },
        )
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
            Triple("blank output", ScriptedPlanningRuntime(modelOutput = ""), "local_model_blank_output"),
            Triple("prose", ScriptedPlanningRuntime(modelOutput = "好的，我来帮你设置闹钟"), "local_model_no_call_output"),
            Triple(
                "missing required argument",
                ScriptedPlanningRuntime(modelOutput = """call:set_system_alarm{"hour":7}"""),
                "local_model_tool_rejected",
            ),
            Triple(
                "argument out of range",
                ScriptedPlanningRuntime(modelOutput = """call:set_system_alarm{"hour":31,"minutes":0}"""),
                "local_model_tool_rejected",
            ),
            Triple(
                "registered tool that was not offered",
                ScriptedPlanningRuntime(modelOutput = """call:dial_phone_number{"phoneNumber":"10086"}"""),
                "local_model_tool_not_retrieved",
            ),
            Triple(
                "unregistered tool",
                ScriptedPlanningRuntime(modelOutput = "call:launch_rockets{}"),
                "local_model_unknown_tool",
            ),
            Triple(
                "two calls on separate lines",
                ScriptedPlanningRuntime(
                    modelOutput = "call:set_system_alarm{\"hour\":7,\"minutes\":30}\n" +
                        "call:set_system_alarm{\"hour\":8,\"minutes\":0}",
                ),
                "local_model_multiple_calls",
            ),
            Triple(
                "two calls on one line",
                ScriptedPlanningRuntime(
                    modelOutput = """call:set_system_alarm{"hour":7,"minutes":30} call:set_system_alarm{"hour":8,"minutes":0}""",
                ),
                "local_model_invalid_arguments",
            ),
            Triple(
                "model error",
                ScriptedPlanningRuntime(modelError = IllegalStateException("engine failed")),
                "local_model_planning_error",
            ),
        )

        failingModels.forEach { (case, runtime, slug) ->
            val planner = planner(runtime)
            listOf(
                LocalPlanningStrategy.ModelFirstWithRuleFastPath,
                LocalPlanningStrategy.RuleFirstGatedModel,
                LocalPlanningStrategy.ModelFirst,
            ).forEach { strategy ->
                listOf(RULE_HIT_REQUEST, ALARM_REQUEST, OPEN_APP_REQUEST).forEach { input ->
                    val actualRunId = runId(input)
                    assertEquals(
                        "$case / $strategy / $input",
                        planner.planToolIfSupported(input, ACTION_MODEL_PROFILES, actionModelPath = null).shape(),
                        planner.planLocalModelFirst(actualRunId, input, ACTION_MODEL_PROFILES, null, localModelFirst(strategy))
                            .shape(),
                    )
                    if (strategy == LocalPlanningStrategy.ModelFirst && input == ALARM_REQUEST) {
                        assertTrue(
                            "$case / $input must record $slug in ${localModelDecision(actualRunId).rejectionReasons}",
                            slug in localModelDecision(actualRunId).rejectionReasons,
                        )
                    }
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
        val gatedRuntime = ScriptedPlanningRuntime(modelOutput = "call:answer{}")

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
        assertNull(
            planner(gatedRuntime).planLocalModelFirst(
                runId(ALARM_REQUEST),
                ALARM_REQUEST,
                ACTION_MODEL_PROFILES,
                null,
                localModelFirst(LocalPlanningStrategy.RuleFirstGatedModel),
            ),
        )
        assertEquals(1, modelFirstRuntime.modelCallCount)
        assertEquals(1, fastPathRuntime.modelCallCount)
        assertEquals(1, gatedRuntime.modelCallCount)
        assertEquals(1, gatedRuntime.gateInputs.size)
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
            listOf(
                LocalPlanningStrategy.ModelFirstWithRuleFastPath,
                LocalPlanningStrategy.RuleFirstGatedModel,
                LocalPlanningStrategy.ModelFirst,
            ).forEach { strategy ->
                val runtime = ScriptedPlanningRuntime(modelOutput = """call:open_app_by_name{"appName":"微信"}""")
                val planner = planner(runtime)
                assertEquals(
                    "$request / $strategy",
                    planner.planToolIfSupported(request, ACTION_MODEL_PROFILES, actionModelPath = null).shape(),
                    planner.planLocalModelFirst(runId(request), request, ACTION_MODEL_PROFILES, null, localModelFirst(strategy))
                        .shape(),
                )
                assertEquals("$request / $strategy", 0, runtime.modelCallCount)
                assertEquals("$request / $strategy", 0, runtime.gateInputs.size)
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

    @Test
    fun safetyPolicyRejectionEqualsRuleFirstAndRecordsItsSlug() {
        val unsafeAlarm = ToolRegistry().specFor(MobileActionFunctions.SET_SYSTEM_ALARM)!!.copy(
            permissions = setOf(ToolPermission.ReadsDeviceContext),
            confirmationPolicy = ConfirmationPolicy.Optional,
        )
        val registry = ToolRegistry(ToolProvider { listOf(unsafeAlarm) })
        val runtime = ScriptedPlanningRuntime(modelOutput = ALARM_CALL, toolRegistry = registry)

        assertFailureMatchesRuleFirst(
            planner(runtime, toolRegistry = registry),
            ALARM_REQUEST,
            "local_model_tool_rejected",
        )
    }

    @Test
    fun sequentialLocalTailGuardEqualsRuleFirstAndRecordsItsSlug() {
        val input = "读取剪贴板然后$OPEN_APP_REQUEST"
        val runtime = ScriptedPlanningRuntime(modelOutput = "call:read_clipboard{}")

        assertFailureMatchesRuleFirst(planner(runtime), input, "local_model_tool_needs_local_tail")
    }

    @Test
    fun multiStepSkillOnASegmentEqualsRuleFirstAndRecordsItsSlug() {
        val input = "${ALARM_REQUEST}然后$OPEN_APP_REQUEST"
        val runtime = ScriptedPlanningRuntime(modelOutput = ALARM_CALL)
        val multiStep = twoStepAlarmSkillPlan()
        val skills = object : SkillRuntime {
            override fun manifests(): List<SkillManifest> = emptyList()
            override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan = multiStep
        }

        assertFailureMatchesRuleFirst(
            planner(runtime, skillRuntime = skills),
            input,
            "local_model_tool_needs_local_tail",
        )
    }

    private fun assertFailureMatchesRuleFirst(
        planner: InitialToolPlanner,
        input: String,
        expectedSlug: String,
    ) {
        val expected = planner.planToolIfSupported(input, ACTION_MODEL_PROFILES, actionModelPath = null)
        val actualRunId = runId(input)

        val actual = planner.planLocalModelFirst(
            actualRunId,
            input,
            ACTION_MODEL_PROFILES,
            null,
            localModelFirst(LocalPlanningStrategy.ModelFirst),
        )

        assertEquals("$input must equal RuleFirst", expected.shape(), actual.shape())
        assertTrue(
            "$input must record $expectedSlug",
            expectedSlug in localModelDecision(actualRunId).rejectionReasons,
        )
    }

    private fun twoStepAlarmSkillPlan(): SkillPlan {
        val manifest = SkillManifest(
            id = "test_multi_step_alarm",
            version = 1,
            title = "两步测试",
            description = "两步测试",
            triggerExamples = emptyList(),
            requiredTools = listOf(MobileActionFunctions.SET_SYSTEM_ALARM, MobileActionFunctions.OPEN_APP_BY_NAME),
            inputSchemaJson = """{"type":"object"}""",
            riskLevel = RiskLevel.MediumDraftOrNavigation,
        )
        val alarm = ToolRequest(
            id = "skill-alarm",
            toolName = MobileActionFunctions.SET_SYSTEM_ALARM,
            arguments = mapOf("hour" to "7", "minutes" to "30"),
        )
        val open = ToolRequest(
            id = "skill-open",
            toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
            arguments = mapOf("appName" to "小红书"),
        )
        return SkillPlan(
            request = SkillRequest("skill-request", manifest.id, emptyMap(), "两步测试"),
            manifest = manifest,
            steps = listOf(alarm, open).map { request ->
                SkillStep.ToolStep(
                    request = request,
                    draft = ActionDraft(request.toolName, request.toolName, request.toolName, request.arguments),
                )
            },
        )
    }

    private fun planner(
        runtime: ActionPlanningRuntime,
        toolRegistry: ToolRegistry = ToolRegistry(),
        skillRuntime: SkillRuntime = NoSkillRuntime,
    ): InitialToolPlanner =
        InitialToolPlanner(
            toolRegistry = toolRegistry,
            skillRuntime = skillRuntime,
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
        private val gateResult: ActionIntentGateResult = ActionIntentGateResult(
            open = true,
            reason = "local_model_action_gate_open",
        ),
        private val gateDelegate: ActionPlanningRuntime? = null,
        toolRegistry: ToolRegistry = ToolRegistry(),
    ) : ActionPlanningRuntime {
        private val parser = MobileActionPlanner(toolRegistry = toolRegistry)
        val modelInputs = mutableListOf<String>()
        val offeredToolSpecs = mutableListOf<List<ToolSpec>>()
        val gateInputs = mutableListOf<String>()
        val modelCallCount: Int get() = modelInputs.size

        override fun isLikelyAction(input: String): Boolean = input in ruleDrafts

        override fun gateActionIntent(input: String, actionModelPath: String?): ActionIntentGateResult {
            gateInputs += input
            return gateDelegate?.gateActionIntent(input, actionModelPath) ?: gateResult
        }

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
            (parser.parseModelToolOutput(output) as? ModelToolOutputParseResult.Parsed)?.draft?.let { draft ->
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

    private class ScriptedGateEngine : LocalEngine {
        var output: String = ""

        override fun engineCapabilities() =
            LocalEngineCapabilities("fake", setOf(LocalAccelerator.CPU), 8_192)

        override fun generateOneShot(request: LocalOneShotRequest): LocalOneShotResult =
            LocalOneShotResult.Generated(
                text = output,
                metrics = LocalCallMetrics(
                    source = LocalEngineSource.SharedChatEngine,
                    accelerator = LocalAccelerator.CPU,
                    loadMs = null,
                    firstTokenMs = 5,
                    totalMs = 10,
                    inputTokens = null,
                    outputTokens = null,
                ),
            )
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
