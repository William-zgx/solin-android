package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.action.HybridActionPlanningRuntime
import com.bytedance.zgx.solin.action.IntentRoutingPath
import com.bytedance.zgx.solin.action.startsWithActionNegation
import com.bytedance.zgx.solin.orchestration.AgentPlan
import com.bytedance.zgx.solin.orchestration.AgentRunOptions
import com.bytedance.zgx.solin.orchestration.AgentStep
import com.bytedance.zgx.solin.orchestration.InMemoryAgentTraceStore
import com.bytedance.zgx.solin.orchestration.InitialPlanningMode
import com.bytedance.zgx.solin.orchestration.InitialToolPlanner
import com.bytedance.zgx.solin.orchestration.LocalPlanningStrategy
import com.bytedance.zgx.solin.runtime.DedicatedLiteRtEngine
import com.bytedance.zgx.solin.runtime.FakeLiteRtFactory
import com.bytedance.zgx.solin.runtime.LocalOneShotCancellation
import com.bytedance.zgx.solin.runtime.LocalOneShotResult
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.AppSearchPlanningMode
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test

/** Suite B mode `R-gated-e2e`: the merged production RuleFirstGatedModel path on production files. */
class RuleFirstGatedE2eSuiteTest {
    private val registry: ToolRegistry = defaultBuiltInToolRegistry

    @Test
    fun ruleFirstGatedModelEndToEnd() {
        requireLabOptIn("B")
        Assume.assumeTrue("R-gated-e2e not selected by SOLIN_LAB_MODES", LabEnv.modeSelected(MODE))
        val client = OllamaClient()
        val requested = availableLabModels(client)
        val models = requested.filter { it in MODEL_PATHS }
        (requested - models.toSet()).forEach { model ->
            LabLog.info("suite B mode=$MODE model=$model skipped: mode requires a production .litertlm id")
        }
        Assume.assumeTrue("no production LiteRT lab models selected", models.isNotEmpty())
        val cases = InitialPlanningCases.benchCases()
        LabManifest.recordModels(client, models)
        LabManifest.recordSuite(
            SUITE,
            JSONObject()
                .put("cases", cases.size)
                .put("datasetCases", InitialPlanningCases.loadAll().size)
                .put("mode", MODE)
                .put("planner", "production InitialToolPlanner.planLocalModelFirst")
                .put("runtime", "production HybridActionPlanningRuntime")
                .put("strategy", LocalPlanningStrategy.RuleFirstGatedModel.name)
                .put("actionModelPath", JSONObject.NULL)
                .put("skillMode", AppSearchPlanningMode.ModelDrivenBootstrap.name)
                .put("retriever", "production ToolRetriever, limit 12, plus answer")
                .put("gateMaxOutputTokens", 64)
                .put("proposalMaxOutputTokens", 512)
                .put("gateMinConfidence", 0.9)
                .put("webSearchFilter", "production explicit-search-verb filter")
                .put(
                    "oneShotSampling",
                    JSONObject()
                        .put("temperature", 0.0)
                        .put("top_k", 1)
                        .put("top_p", 0.1)
                        .put("seed", 0)
                        .put("num_ctx", 8_192),
                ),
        )
        models.forEach { model ->
            // The adapter represents the shared engine already loaded for chat, as in SolinAppContainer.
            client.warmUp(model)
            runModel(model, requireNotNull(MODEL_PATHS[model]), cases)
        }
    }

    private fun runModel(model: String, modelPath: String, cases: List<InitialCase>) {
        val engine = LiteRtShimLocalEngine(modelPath)
        val fallback = DedicatedLiteRtEngine(
            Files.createTempDirectory("rule-first-gated-e2e").toFile(),
            FakeLiteRtFactory(),
        )
        val runtime = HybridActionPlanningRuntime(
            fallbackEngine = fallback,
            toolRegistry = registry,
            sharedEngine = engine,
        )
        val traceStore = InMemoryAgentTraceStore(clockMillis = { FIXED_NOW.toEpochMilli() })
        val planner = InitialToolPlanner(
            toolRegistry = registry,
            skillRuntime = BuiltInSkillRuntime(
                clockMillis = { FIXED_NOW.toEpochMilli() },
                zoneId = ZoneOffset.UTC,
                appSearchPlanningModeProvider = { AppSearchPlanningMode.ModelDrivenBootstrap },
            ),
            safetyPolicy = SafetyPolicy(),
            actionPlanningRuntime = runtime,
            traceStore = traceStore,
        )
        val profile = ModelCatalog.recommendedProfiles().first { profile ->
            profile.supportsMobileActionPlanning && profile.byteSize == File(modelPath).length()
        }
        var correct = 0
        try {
            JsonlWriter(SUITE, model, MODE).use { writer ->
                cases.forEach { case ->
                    engine.clearCalls()
                    val run = traceStore.createRun(case.input)
                    val started = System.nanoTime()
                    val plan = planner.planLocalModelFirst(
                        runId = run.id,
                        input = case.input,
                        installedCapabilityProfiles = listOf(profile),
                        // verifiedActionModelPath() is null after PF1 retired the only MobileAction file.
                        actionModelPath = null,
                        options = AgentRunOptions(
                            initialPlanningMode = InitialPlanningMode.LocalModelFirst,
                            localPlanningStrategy = LocalPlanningStrategy.RuleFirstGatedModel,
                            localPlanningModelPath = modelPath,
                            initialRouteCancellation = LocalOneShotCancellation(),
                        ),
                    )
                    val wallMs = (System.nanoTime() - started) / 1_000_000
                    val calls = engine.calls
                    val decision = traceStore.steps(run.id)
                        .filterIsInstance<AgentStep.IntentRouted>()
                        .singleOrNull { step -> step.decision.selectedPath == IntentRoutingPath.ModelToolCall }
                        ?.decision
                    val reasons = decision?.rejectionReasons.orEmpty()
                    val gateReason = reasons.firstOrNull { it.startsWith(GATE_REASON_PREFIX) }
                    val gateOpen = gateReason == GATE_OPEN
                    val gateCall = calls.firstOrNull()
                    val proposalCall = calls.getOrNull(1)
                    val firstError = calls.firstNotNullOfOrNull { call ->
                        call.metrics.optString("error").takeIf { it.isNotBlank() }
                    }
                    val stage = decisionStage(case, plan, calls.size, gateOpen, reasons)
                    val outcome = reasons.firstOrNull { reason ->
                        reason.startsWith("local_model_") && !reason.startsWith(GATE_REASON_PREFIX)
                    }
                    val (answer, kind) = if (firstError != null) {
                        LabAnswer.INVALID to "error"
                    } else {
                        plan.toLabAnswer()
                    }
                    val raw = proposalCall?.rawText ?: gateCall?.rawText.orEmpty()
                    val record = InitialPlanningCases.record(SUITE, model, MODE, case, answer, kind, raw, registry)
                        .put("decisionStage", stage)
                        .put("planSource", plan.planSource())
                        .put("gateReason", gateReason ?: JSONObject.NULL)
                        .put("gateOpen", gateOpen)
                        .put("gateRawJsonValid", gateJsonValidity(gateCall, gateReason))
                        .put("gateRawPreview", gateCall?.rawText?.previewForLab().orEmpty())
                        .put("proposalOutcome", outcome ?: JSONObject.NULL)
                        .put("traceReasons", JSONArray(reasons))
                        .put("retrievedTools", reasons.retrievedTools().toJsonStringArray())
                        .put("modelCalls", calls.size)
                        .put("modelCallsCached", 0)
                        .put("wallMs", wallMs)
                        .put("metrics", aggregateMetrics(wallMs, firstError))
                        .put(
                            "modelOutputs",
                            JSONArray(
                                calls.mapIndexed { index, call ->
                                    JSONObject()
                                        .put("phase", if (index == 0) "gate" else "proposal")
                                        .put("rawPreview", call.rawText.previewForLab())
                                        .put("maxOutputTokens", call.request.maxOutputTokens)
                                        .put(
                                            "unavailableReason",
                                            (call.result as? LocalOneShotResult.Unavailable)?.reason?.name ?: JSONObject.NULL,
                                        )
                                        .put("cached", false)
                                        .put("metrics", call.metrics)
                                },
                            ),
                        )
                    if (firstError != null) {
                        // Decision L1: an errored call produced no answer and is never a correct refusal.
                        record.put("correct", false)
                            .put("toolCorrect", false)
                            .put("argsExact", false)
                            .put("falsePositive", false)
                    }
                    addFalsePositiveSplit(record)
                    val retrieved = reasons.retrievedTools()
                    if (retrieved.isNotEmpty()) {
                        record.put("advertisedToolCount", retrieved.size + 1)
                            .put("advertisedTools", (retrieved + LOCAL_MODEL_NO_TOOL_CHOICE).toJsonStringArray())
                            .put("answerOffered", true)
                            .put(
                                "outsideAdvertised",
                                answer.tool != null && answer.tool !in retrieved && answer.tool != LOCAL_MODEL_NO_TOOL_CHOICE,
                            )
                        if (case.goldTools.isNotEmpty()) {
                            record.put("retrieverHit", case.goldTools.any { it in retrieved })
                        }
                    } else {
                        record.put("answerOffered", false)
                    }
                    if (record.getBoolean("correct")) correct++
                    writer.write(record)
                }
            }
        } finally {
            runtime.close()
        }
        LabLog.info("suite B model=$model mode=$MODE cases=${cases.size} correct=$correct")
    }

    private fun decisionStage(
        case: InitialCase,
        plan: AgentPlan?,
        callCount: Int,
        gateOpen: Boolean,
        reasons: List<String>,
    ): String =
        when {
            callCount == 0 && plan != null -> "rule"
            callCount == 0 && case.input.startsWithActionNegation() -> "negation guard"
            callCount == 0 -> "rule"
            !gateOpen -> "gate closed"
            plan is AgentPlan.UseTool && plan.plannedByModel -> "gate open → accepted"
            "local_model_chose_no_tool" in reasons -> "gate open → answer"
            else -> "gate open → failed"
        }

    private fun gateJsonValidity(call: LiteRtShimLocalEngine.ObservedCall?, reason: String?): Any {
        if (call == null || call.result !is LocalOneShotResult.Generated) return JSONObject.NULL
        return reason != "local_model_action_gate_invalid_output"
    }

    private fun aggregateMetrics(wallMs: Long, error: String?): JSONObject =
        JSONObject()
            .put("wallMs", wallMs)
            .put("totalMs", wallMs)
            .put("promptTokens", 0)
            .put("promptCachedTokens", 0)
            .put("evalTokens", 0)
            .put("error", error ?: JSONObject.NULL)

    private fun addFalsePositiveSplit(record: JSONObject) {
        if (!record.getBoolean("falsePositive")) {
            record.put("falsePositiveClass", JSONObject.NULL)
                .put("fpOrchestrationOnly", false)
                .put("fpDeviceNetworkExternal", false)
                .put("fpPrivateRead", false)
                .put("fpNetworkEgress", false)
                .put("privacyRelevant", false)
            return
        }
        val tool = record.getJSONObject("answer").optString("tool")
        val spec = registry.specFor(tool)
        val orchestrationOnly = tool == LOCAL_MODEL_NO_TOOL_CHOICE ||
            spec == null || spec.capability == ToolCapability.Orchestration
        val privateRead = tool in PRIVATE_READ_TOOLS
        val network = tool in NETWORK_TOOLS
        record.put(
            "falsePositiveClass",
            if (orchestrationOnly) "orchestration_only" else "device_network_external",
        )
            .put("fpOrchestrationOnly", orchestrationOnly)
            .put("fpDeviceNetworkExternal", !orchestrationOnly)
            .put("fpPrivateRead", privateRead)
            .put("fpNetworkEgress", network)
            .put("privacyRelevant", privateRead || network)
            .put("fpTool", tool)
    }

    private fun List<String>.retrievedTools(): List<String> =
        filter { it.startsWith("retrieved=") }.map { it.removePrefix("retrieved=") }

    private fun AgentPlan?.toLabAnswer(): Pair<LabAnswer, String> =
        when (this) {
            null -> LabAnswer.STOP to "no_tool"
            is AgentPlan.UseTool -> LabAnswer(request.toolName, request.arguments) to "use_tool"
            is AgentPlan.RejectedTool -> LabAnswer(result.data["toolName"], invalid = true) to "rejected_tool"
            is AgentPlan.MissingModel -> LabAnswer.INVALID to "missing_model"
            is AgentPlan.Answer -> LabAnswer.STOP to "answer"
        }

    private fun AgentPlan?.planSource(): String =
        when (this) {
            is AgentPlan.UseTool -> when {
                skillPlan != null && fallbackReason == "skill-first" -> "skill"
                plannedByModel -> "model"
                else -> "rules"
            }
            is AgentPlan.RejectedTool -> "rejected"
            is AgentPlan.MissingModel -> "missing_model"
            else -> "none"
        }

    private companion object {
        const val SUITE = "initial-planning"
        const val MODE = "R-gated-e2e"
        const val GATE_REASON_PREFIX = "local_model_action_gate_"
        const val GATE_OPEN = "local_model_action_gate_open"
        val FIXED_NOW: Instant = Instant.parse("2026-06-14T02:00:00Z")
        val MODEL_PATHS = mapOf(
            "litert/gemma-4-e2b" to "/data00/home/zouguoxue/edge-litert/models/gemma-4-E2B-it.litertlm",
            "litert/gemma-4-e4b" to "/data00/home/zouguoxue/edge-litert/models/gemma-4-E4B-it.litertlm",
        )
        val PRIVATE_READ_TOOLS = setOf("read_clipboard", "read_current_screen_text", "read_notifications")
        val NETWORK_TOOLS = setOf("web_search", "search_maps", "open_deep_link")
    }
}
