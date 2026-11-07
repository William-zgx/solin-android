package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionPlanner
import com.bytedance.zgx.solin.orchestration.MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES
import com.bytedance.zgx.solin.orchestration.MODEL_OBSERVATION_REPLAN_COMPOSITE_TOOL_NAMES
import com.bytedance.zgx.solin.orchestration.ModelObservationReplanDiagnostic
import com.bytedance.zgx.solin.orchestration.ModelObservationReplanner
import com.bytedance.zgx.solin.tool.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test

/**
 * Suite A: one observation step through the production `ModelObservationReplanner`, wired like
 * `SolinAppContainer` (observation-only registry for prompt and parser, `maxModelReplans = 5`).
 * Mode `replanner-fix` is the lab-only [PlaceholderAblation] of the same prompts.
 */
class ObservationStepSuiteTest {
    private val registry = ToolRegistry.fromSupportedActions(
        MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES + MODEL_OBSERVATION_REPLAN_COMPOSITE_TOOL_NAMES,
    )
    private val parser = MobileActionPlanner(toolRegistry = registry)

    @Test
    fun observationStepSuite() {
        requireLabOptIn("A")
        val modes = MODES.filter(LabEnv::modeSelected)
        Assume.assumeTrue("no Suite A mode selected by SOLIN_LAB_MODES", modes.isNotEmpty())
        val client = OllamaClient()
        val models = availableLabModels(client)
        Assume.assumeTrue("no lab models installed", models.isNotEmpty())
        LabManifest.recordModels(client, models)

        val allCases = ObservationStepCases.load()
        val cases = LabEnv.caseLimit?.let(allCases::take) ?: allCases
        val built = cases.map { case -> case to ObservationStepCases.build(case) }
        LabManifest.recordSuite(
            SUITE,
            JSONObject()
                .put("cases", cases.size)
                .put("datasetCases", allCases.size)
                .put("registryTools", JSONArray(registry.specs().map { it.name }))
                .put("maxModelReplans", MAX_MODEL_REPLANS),
        )

        models.forEach { model ->
            client.warmUp(model)
            modes.forEach { mode -> runMode(client, model, mode, built) }
        }
    }

    private fun runMode(
        client: OllamaClient,
        model: String,
        mode: String,
        built: List<Pair<ObservationCase, BuiltObservationCase>>,
    ) {
        val runtime = OllamaActionPlanningRuntime(
            client,
            model,
            toolRegistry = registry,
            placeholderAblation = mode == ABLATION_MODE,
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "ollama:$model" },
            toolRegistry = registry,
            maxModelReplans = MAX_MODEL_REPLANS,
        )
        var rawCorrect = 0
        var finalCorrect = 0
        JsonlWriter(SUITE, model, mode).use { writer ->
            built.forEach { (case, builtCase) ->
                runtime.clearCalls()
                val started = System.nanoTime()
                val replan = replanner.planNext(builtCase.context)
                val wallMs = (System.nanoTime() - started) / 1_000_000
                val calls = runtime.calls
                val diagnostics = replanner.diagnosticSnapshots().takeLast(calls.size.coerceAtLeast(1))

                val first = calls.firstOrNull()
                val (rawAnswer, rawKind) = first?.let { call ->
                    if (call.result.error != null) LabAnswer.INVALID to "error"
                    else LabScoring.answerFromCallOutput(call.rawOutput, parser)
                } ?: (LabAnswer.STOP to "not_attempted")
                val finalAnswer = replan?.let { LabAnswer(it.request.toolName, it.request.arguments) }
                    ?: LabAnswer.STOP
                val rawOk = LabScoring.observationAnswerMatches(rawAnswer, case.accepts, builtCase.nodeLabels)
                val finalOk = LabScoring.observationAnswerMatches(finalAnswer, case.accepts, builtCase.nodeLabels)
                if (rawOk) rawCorrect++
                if (finalOk) finalCorrect++

                val callRecords = calls.mapIndexed { index, call ->
                    val diagnostic = diagnostics.getOrNull(index)
                    JSONObject()
                        .put("promptIndex", diagnostic?.promptIndex ?: JSONObject.NULL)
                        .put("guardReason", diagnostic?.reason ?: JSONObject.NULL)
                        .put("rawPreview", call.rawOutput.previewForLab())
                        .put("parsed", call.parsedDraft?.toLabJson() ?: JSONObject.NULL)
                        .put("parseFailure", call.failureReason ?: JSONObject.NULL)
                        .put("cached", call.cached)
                        .put("metrics", call.result.metricsJson())
                }
                val accepted = diagnostics.firstOrNull { it.reason == "accepted" }
                writer.write(
                    JSONObject()
                        .put("suite", SUITE)
                        .put("model", model)
                        .put("mode", mode)
                        .put("caseId", case.id)
                        .put("category", case.category)
                        .put("goldLabel", case.goldLabel)
                        .put("gold", JSONArray(case.accepts))
                        .put("rawKind", rawKind)
                        .put("rawAnswer", rawAnswer.toJson())
                        .put("rawCorrect", rawOk)
                        .put("finalAnswer", finalAnswer.toJson())
                        .put("finalCorrect", finalOk)
                        .put("finalSource", finalSource(replan != null, accepted))
                        .put("rawPreview", first?.rawOutput?.previewForLab() ?: "")
                        .put("guardReasons", JSONArray(diagnostics.map { it.reason }))
                        .put("modelCalls", calls.size)
                        .put("calls", JSONArray(callRecords))
                        .put("wallMs", wallMs)
                        .put("modelWallMs", calls.sumOf { it.result.wallMillis })
                        .put("promptTokens", calls.sumOf { it.result.promptEvalCount })
                        .put("firstPromptTokens", first?.result?.promptEvalCount ?: 0)
                        .put("evalTokens", calls.sumOf { it.result.evalCount }),
                )
            }
        }
        LabLog.info(
            "suite A model=$model mode=$mode cases=${built.size} rawCorrect=$rawCorrect finalCorrect=$finalCorrect",
        )
    }

    private fun finalSource(planned: Boolean, accepted: ModelObservationReplanDiagnostic?): String =
        when {
            !planned -> "stop"
            accepted?.promptIndex == 0 -> "full_prompt"
            accepted?.promptIndex == 1 -> "minimal_prompt"
            accepted?.promptIndex != null -> "corrective_prompt"
            else -> "unknown"
        }

    private companion object {
        const val SUITE = "observation-step"
        const val ABLATION_MODE = "replanner-fix"
        val MODES = listOf("replanner", ABLATION_MODE)
        const val MAX_MODEL_REPLANS = 5
    }
}

fun ActionDraft.toLabJson(): JSONObject =
    JSONObject().put("tool", functionName).put("args", parameters.toJson())
