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

/** Suite B modes `R-gated-e2e` and its lab-only single-fence gate variant on production files. */
class RuleFirstGatedE2eSuiteTest {
    private val registry: ToolRegistry = defaultBuiltInToolRegistry

    @Test
    fun ruleFirstGatedModelEndToEnd() = runMode(MODE)

    @Test
    fun ruleFirstGatedModelSingleFenceEndToEnd() = runMode(FENCE_MODE)

    private fun runMode(mode: String) {
        requireLabOptIn("B")
        Assume.assumeTrue("$mode not selected by SOLIN_LAB_MODES", LabEnv.modeSelected(mode))
        val modelRoot = LabEnv.liteRtModelRoot
        Assume.assumeTrue(
            "$mode requires SOLIN_LAB_LITERT_MODEL_ROOT to point to the production .litertlm directory",
            modelRoot != null
        )
        val client = OllamaClient()
        val requested = availableLabModels(client)
        val models = requested.filter { it in MODEL_FILE_NAMES }
        (requested - models.toSet()).forEach { model ->
            LabLog.info("suite B mode=$mode model=$model skipped: mode requires a production .litertlm id")
        }
        Assume.assumeTrue("no production LiteRT lab models selected", models.isNotEmpty())
        val modelPaths = models.associateWith { model ->
            File(modelRoot, MODEL_FILE_NAMES.getValue(model)).absolutePath
        }
        modelPaths.forEach { (model, path) ->
            Assume.assumeTrue(
                "$mode requires the production file for $model at $path; check SOLIN_LAB_LITERT_MODEL_ROOT",
                File(path).isFile
            )
        }
        val cases = InitialPlanningCases.benchCases()
        LabManifest.recordModels(client, models)
        LabManifest.recordSuite(
            SUITE,
            JSONObject()
                .put("cases", cases.size)
                .put("datasetCases", InitialPlanningCases.loadSelected().size)
                .put("caseSource", LabEnv.initialPlanningCaseSource)
                .put("datasetFile", InitialPlanningCases.selectedDatasetFilename())
                .put("mode", mode)
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
                        .put("num_ctx", 8_192)
                )
        )
        models.forEach { model ->
            // The adapter represents the shared engine already loaded for chat, as in SolinAppContainer.
            client.warmUp(model)
            runModel(model, modelPaths.getValue(model), cases, mode)
        }
    }

    private fun runModel(model: String, modelPath: String, cases: List<InitialCase>, mode: String) {
        val engine = LiteRtShimLocalEngine(
            loadedModelPath = modelPath,
            gateReplyTransform = if (mode == FENCE_MODE) ::unwrapSingleJsonFence else null
        )
        val fallback = DedicatedLiteRtEngine(
            Files.createTempDirectory("rule-first-gated-e2e").toFile(),
            FakeLiteRtFactory()
        )
        val runtime = HybridActionPlanningRuntime(
            fallbackEngine = fallback,
            toolRegistry = registry,
            sharedEngine = engine
        )
        val traceStore = InMemoryAgentTraceStore(clockMillis = { FIXED_NOW.toEpochMilli() })
        val planner = InitialToolPlanner(
            toolRegistry = registry,
            skillRuntime = BuiltInSkillRuntime(
                clockMillis = { FIXED_NOW.toEpochMilli() },
                zoneId = ZoneOffset.UTC,
                appSearchPlanningModeProvider = { AppSearchPlanningMode.ModelDrivenBootstrap }
            ),
            safetyPolicy = SafetyPolicy(),
            actionPlanningRuntime = runtime,
            traceStore = traceStore
        )
        val profile = ModelCatalog.recommendedProfiles().first { profile ->
            profile.supportsMobileActionPlanning && profile.byteSize == File(modelPath).length()
        }
        var correct = 0
        try {
            JsonlWriter(SUITE, model, mode).use { writer ->
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
                            initialRouteCancellation = LocalOneShotCancellation()
                        )
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
                    val gateUnwrappedText = gateCall?.transformedText
                    val proposalCall = calls.getOrNull(1)
                    val firstError = calls.firstNotNullOfOrNull { call ->
                        call.metrics.optString("error").takeIf { it.isNotBlank() }
                    }
                    val stage = decisionStage(case, plan, calls.size, gateOpen, reasons)
                    val outcome = reasons.firstOrNull { reason ->
                        reason.startsWith("local_model_") && !reason.startsWith(GATE_REASON_PREFIX)
                    }
                    val (answer, kind) = plan.toLabAnswer(modelCallFailed = firstError != null)
                    val raw = proposalCall?.rawText ?: gateCall?.rawText.orEmpty()
                    val retrieved = reasons.retrievedTools()
                    val advertised = if (retrieved.isNotEmpty()) retrieved + LOCAL_MODEL_NO_TOOL_CHOICE else null
                    val record = InitialPlanningCases.record(
                        SUITE, model, mode, case, answer, kind, raw, registry, advertised
                    )
                        .put("decisionStage", stage)
                        .put("planSource", plan.planSource())
                        .put("gateReason", gateReason ?: JSONObject.NULL)
                        .put("gateOpen", gateOpen)
                        .put("gateRawJsonValid", gateJsonValidity(gateCall, gateReason))
                        .put("gateRawPreview", gateCall?.rawText?.previewForLab().orEmpty())
                        .put("gateFenceUnwrapped", gateUnwrappedText != null)
                        .put("proposalOutcome", outcome ?: JSONObject.NULL)
                        .put("traceReasons", JSONArray(reasons))
                        .put("retrievedTools", retrieved.toJsonStringArray())
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
                                            (call.result as? LocalOneShotResult.Unavailable)?.reason?.name ?: JSONObject.NULL
                                        )
                                        .put("cached", false)
                                        .put("metrics", call.metrics)
                                }
                            )
                        )
                    if (gateUnwrappedText != null) {
                        record.put("gateUnwrappedPreview", gateUnwrappedText.previewForLab())
                    }
                    record.put("answerOffered", advertised != null)
                    if (record.getBoolean("correct")) correct++
                    writer.write(record)
                }
            }
        } finally {
            runtime.close()
        }
        LabLog.info("suite B model=$model mode=$mode cases=${cases.size} correct=$correct")
    }

    private fun decisionStage(
        case: InitialCase,
        plan: AgentPlan?,
        callCount: Int,
        gateOpen: Boolean,
        reasons: List<String>
    ): String = when {
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

    private fun aggregateMetrics(wallMs: Long, error: String?): JSONObject = JSONObject()
        .put("wallMs", wallMs)
        .put("totalMs", wallMs)
        .put("promptTokens", 0)
        .put("promptCachedTokens", 0)
        .put("evalTokens", 0)
        .put("error", error ?: JSONObject.NULL)

    private fun List<String>.retrievedTools(): List<String> = filter { it.startsWith("retrieved=") }.map { it.removePrefix("retrieved=") }

    private companion object {
        const val SUITE = "initial-planning"
        const val MODE = "R-gated-e2e"
        const val FENCE_MODE = "R-gated-e2e-fence"
        const val GATE_REASON_PREFIX = "local_model_action_gate_"
        const val GATE_OPEN = "local_model_action_gate_open"
        val FIXED_NOW: Instant = Instant.parse("2026-06-14T02:00:00Z")
        val MODEL_FILE_NAMES = mapOf(
            "litert/gemma-4-e2b" to "gemma-4-E2B-it.litertlm",
            "litert/gemma-4-e4b" to "gemma-4-E4B-it.litertlm"
        )
    }
}
