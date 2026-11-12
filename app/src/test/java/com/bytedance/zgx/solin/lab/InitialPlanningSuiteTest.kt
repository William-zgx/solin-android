package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.action.MobileActionPlanner
import com.bytedance.zgx.solin.orchestration.AgentPlan
import com.bytedance.zgx.solin.orchestration.InMemoryAgentTraceStore
import com.bytedance.zgx.solin.orchestration.InitialToolPlanner
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.AppSearchPlanningMode
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test

/**
 * Suite B: the first tool for a request. `R` and `R+M` run the production `InitialToolPlanner`;
 * `M-full` and `M-topk` call the model alone through the same prompt builder and parser.
 */
class InitialPlanningSuiteTest {
    private val registry: ToolRegistry = defaultBuiltInToolRegistry
    private val parser = MobileActionPlanner(toolRegistry = registry)
    private val retriever = LabToolRetriever(registry.specs())

    @Test
    fun initialPlanningSuite() {
        requireLabOptIn("B")
        val client = OllamaClient()
        val models = availableLabModels(client)
        val cases = InitialPlanningCases.benchCases()
        LabManifest.recordSuite(
            SUITE,
            JSONObject()
                .put("cases", cases.size)
                .put("datasetCases", InitialPlanningCases.loadSelected().size)
                .put("caseSource", LabEnv.initialPlanningCaseSource)
                .put("datasetFile", InitialPlanningCases.selectedDatasetFilename())
                .put("registryTools", registry.specs().size)
                .put("topK", TOP_K)
                .put("fixedClock", FIXED_NOW.toString())
                .put("skillModeR", AppSearchPlanningMode.StaticSkill.name)
                .put("skillModeRM", AppSearchPlanningMode.ModelDrivenBootstrap.name)
                .put("rmInstalledProfile", MOBILE_ACTION_PROFILE.id)
        )
        runRules(cases)
        Assume.assumeTrue("no lab models installed", models.isNotEmpty())
        LabManifest.recordModels(client, models)
        models.forEach { model ->
            client.warmUp(model)
            val cache = mutableMapOf<String, OllamaClient.ChatResult>()
            val runtime = OllamaActionPlanningRuntime(client, model, toolRegistry = registry, responseCache = cache)
            if (LabEnv.modeSelected("M-full")) runModelOnly(model, "M-full", cases, runtime) { registry.specs() }
            if (LabEnv.modeSelected("M-topk")) {
                runModelOnly(model, "M-topk", cases, runtime) { case -> retriever.topK(case.input, TOP_K) }
            }
            if (LabEnv.modeSelected("R+M")) runRulesPlusModel(model, cases, runtime)
            val ablation = OllamaActionPlanningRuntime(client, model, toolRegistry = registry, placeholderAblation = true)
            if (LabEnv.modeSelected("M-full-fix")) runModelOnly(model, "M-full-fix", cases, ablation) { registry.specs() }
            if (LabEnv.modeSelected("M-topk-fix")) {
                runModelOnly(model, "M-topk-fix", cases, ablation) { case -> retriever.topK(case.input, TOP_K) }
            }
            // Production-faithful LocalModelFirst text shape: the top-k shortlist plus the `answer`
            // no-tool choice, with the lab's placeholder fix (the production prompt still overflows
            // or copies `function`; see SUMMARY Suite F).
            val productionText = OllamaActionPlanningRuntime(
                client,
                model,
                toolRegistry = registry,
                placeholderAblation = true,
                noToolChoice = true
            )
            if (LabEnv.modeSelected("M-topk-fix-lmf")) {
                runModelOnly(model, "M-topk-fix-lmf", cases, productionText) { case ->
                    retriever.topK(case.input, TOP_K)
                }
            }
        }
    }

    private fun runRules(cases: List<InitialCase>) {
        val runtime = OllamaActionPlanningRuntime(OllamaClient(), model = "none", toolRegistry = registry)
        val planner = planner(runtime, AppSearchPlanningMode.StaticSkill)
        var correct = 0
        JsonlWriter(SUITE, RULES_MODEL, "R").use { writer ->
            cases.forEach { case ->
                val started = System.nanoTime()
                val plan = planner.planToolIfSupported(case.input, emptyList(), actionModelPath = null)
                val wallMs = (System.nanoTime() - started) / 1_000_000
                val (answer, kind) = plan.toLabAnswer()
                val record = InitialPlanningCases.record(SUITE, RULES_MODEL, "R", case, answer, kind, "", registry)
                    .put("planSource", plan.planSource())
                    .put("wallMs", wallMs)
                    .put("modelCalls", 0)
                if (record.getBoolean("correct")) correct++
                writer.write(record)
            }
        }
        LabLog.info("suite B mode=R cases=${cases.size} correct=$correct")
    }

    private fun runModelOnly(
        model: String,
        mode: String,
        cases: List<InitialCase>,
        runtime: OllamaActionPlanningRuntime,
        specsFor: (InitialCase) -> List<ToolSpec>
    ) {
        val noToolChoice = mode.endsWith("-lmf")
        var correct = 0
        JsonlWriter(SUITE, model, mode).use { writer ->
            cases.forEach { case ->
                val specs = specsFor(case)
                runtime.clearCalls()
                runtime.plan(case.input, "ollama:$model", specs)
                val call = runtime.calls.single()
                val (answer, kind) = if (call.result.error != null) {
                    LabAnswer.INVALID to "error"
                } else {
                    LabScoring.answerFromCallOutput(call.rawOutput, parser, noToolChoice = noToolChoice)
                }
                val advertised = call.toolNames
                val record = InitialPlanningCases.record(SUITE, model, mode, case, answer, kind, call.rawOutput, registry)
                    .put("parseFailure", call.failureReason ?: JSONObject.NULL)
                    .put("modelCalls", 1)
                    .put("cached", call.cached)
                    .put("advertisedToolCount", advertised.size)
                    .put("metrics", call.result.metricsJson())
                if (advertised.size < registry.specs().size) {
                    record.put("advertisedTools", JSONArray(advertised))
                    record.put("outsideAdvertised", answer.tool != null && answer.tool !in advertised)
                    if (case.goldTools.isNotEmpty()) {
                        record.put("retrieverHit", case.goldTools.any { it in advertised })
                    }
                }
                if (record.getBoolean("correct")) correct++
                writer.write(record)
            }
        }
        LabLog.info("suite B model=$model mode=$mode cases=${cases.size} correct=$correct")
    }

    private fun runRulesPlusModel(model: String, cases: List<InitialCase>, runtime: OllamaActionPlanningRuntime) {
        val planner = planner(runtime, AppSearchPlanningMode.ModelDrivenBootstrap)
        var correct = 0
        JsonlWriter(SUITE, model, "R+M").use { writer ->
            cases.forEach { case ->
                runtime.clearCalls()
                val started = System.nanoTime()
                val plan = planner.planToolIfSupported(
                    case.input,
                    listOf(MOBILE_ACTION_PROFILE),
                    actionModelPath = "ollama:$model"
                )
                val wallMs = (System.nanoTime() - started) / 1_000_000
                val calls = runtime.calls
                val (answer, kind) = plan.toLabAnswer()
                val record = InitialPlanningCases.record(
                    SUITE,
                    model,
                    "R+M",
                    case,
                    answer,
                    kind,
                    calls.lastOrNull()?.rawOutput.orEmpty(),
                    registry
                )
                    .put("planSource", plan.planSource())
                    .put("wallMs", wallMs)
                    .put("modelCalls", calls.size)
                    .put("modelCallsCached", calls.count { it.cached })
                    .put(
                        "modelOutputs",
                        JSONArray(
                            calls.map { call ->
                                JSONObject()
                                    .put("rawPreview", call.rawOutput.previewForLab())
                                    .put("parsed", call.parsedDraft?.toLabJson() ?: JSONObject.NULL)
                                    .put("parseFailure", call.failureReason ?: JSONObject.NULL)
                                    .put("cached", call.cached)
                                    .put("metrics", call.result.metricsJson())
                            }
                        )
                    )
                if (calls.any { it.result.error != null }) {
                    record.put("correct", false)
                        .put("argsExact", false)
                        .put("toolCorrect", false)
                        .put("argKeys", JSONObject())
                        .put("constraintResults", JSONObject())
                        .put("falsePositive", false)
                }
                if (record.getBoolean("correct")) correct++
                writer.write(record)
            }
        }
        LabLog.info("suite B model=$model mode=R+M cases=${cases.size} correct=$correct")
    }

    private fun planner(runtime: OllamaActionPlanningRuntime, mode: AppSearchPlanningMode): InitialToolPlanner = InitialToolPlanner(
        toolRegistry = registry,
        skillRuntime = BuiltInSkillRuntime(
            clockMillis = { FIXED_NOW.toEpochMilli() },
            zoneId = ZoneOffset.UTC,
            appSearchPlanningModeProvider = { mode }
        ),
        safetyPolicy = SafetyPolicy(),
        actionPlanningRuntime = runtime,
        traceStore = InMemoryAgentTraceStore(clockMillis = { FIXED_NOW.toEpochMilli() })
    )

    private fun AgentPlan?.toLabAnswer(): Pair<LabAnswer, String> = when (this) {
        null -> LabAnswer.STOP to "no_tool"
        is AgentPlan.UseTool -> LabAnswer(request.toolName, request.arguments) to "use_tool"
        is AgentPlan.RejectedTool -> LabAnswer(result.data["toolName"], invalid = true) to "rejected_tool"
        is AgentPlan.MissingModel -> LabAnswer.INVALID to "missing_model"
        is AgentPlan.Answer -> LabAnswer.STOP to "answer"
    }

    private fun AgentPlan?.planSource(): String = when (this) {
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
        const val RULES_MODEL = "rules"
        const val TOP_K = 8
        val FIXED_NOW: Instant = Instant.parse("2026-06-14T02:00:00Z")
        val MOBILE_ACTION_PROFILE = ModelCatalog.recommendedProfiles().first { profile ->
            profile.capability == ModelCapability.MobileAction
        }
    }
}
