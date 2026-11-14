package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.orchestration.TypedObservationCandidateReplanner
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays typed candidates before the saved 20261108 production-model answers; no model is called. */
class TypedObservationReplayTest {
    private val typed = TypedObservationCandidateReplanner(enabled = true)
    private val cases = ObservationStepCases.load().associateBy { it.id }
    private val builtCases = cases.mapValues { (_, case) -> ObservationStepCases.build(case) }

    @Test
    fun pairedSavedOutputReplayImprovesWithoutBreakingCases() {
        val reports = MODELS.map(::replayModel)
        val reportFile = File(LabEnv.repoRoot, "build/verification/typed-observation/replay-summary.json")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(
            JSONObject()
                .put("schema", "SolinTypedObservationReplayReport/v1")
                .put("sourceRun", "20261108")
                .put("evidence", "replanner-level saved-output replay; not full production or device evidence")
                .put("models", JSONArray(reports))
                .toString()
        )
        println("typed-observation replay: $reportFile")
    }

    private fun replayModel(model: String): JSONObject {
        val input = File(
            LabEnv.repoRoot,
            "lab/results/20261108/observation-step__litert-gemma-4-${model}__replanner.jsonl"
        )
        val baselineRecords = input.readLines().filter(String::isNotBlank).map(::JSONObject)
        assertEquals(46, baselineRecords.size)
        var baselineCorrect = 0
        var replayCorrect = 0
        var avoidedCalls = 0
        val fixed = mutableListOf<String>()
        val broken = mutableListOf<String>()
        val handled = mutableListOf<JSONObject>()

        baselineRecords.forEach { record ->
            val caseId = record.getString("caseId")
            val built = requireNotNull(builtCases[caseId])
            val typedReplan = typed.planNext(built.context)
            val before = record.getBoolean("finalCorrect")
            val after = if (typedReplan == null) {
                before
            } else {
                LabScoring.observationAnswerMatches(
                    LabAnswer(typedReplan.request.toolName, typedReplan.request.arguments),
                    requireNotNull(cases[caseId]).accepts,
                    built.nodeLabels
                )
            }
            if (before) baselineCorrect++
            if (after) replayCorrect++
            if (!before && after) fixed += caseId
            if (before && !after) broken += caseId
            if (typedReplan != null) {
                avoidedCalls += record.optInt("modelCalls")
                handled += JSONObject()
                    .put("caseId", caseId)
                    .put("baselineCorrect", before)
                    .put("replayCorrect", after)
                    .put("modelCallsAvoided", record.optInt("modelCalls"))
            }
        }

        assertTrue("$model must improve at least one saved case", fixed.isNotEmpty())
        assertEquals("$model must not break a previously correct case", emptyList<String>(), broken)
        println(
            "typed-observation $model baseline=$baselineCorrect replay=$replayCorrect " +
                "fixed=${fixed.size} broken=${broken.size} avoidedCalls=$avoidedCalls"
        )
        return JSONObject()
            .put("model", model)
            .put("baselineCorrect", baselineCorrect)
            .put("replayCorrect", replayCorrect)
            .put("fixed", fixed.size)
            .put("broken", broken.size)
            .put("fixedCaseIds", JSONArray(fixed))
            .put("brokenCaseIds", JSONArray(broken))
            .put("handledCaseCount", handled.size)
            .put("modelCallsAvoided", avoidedCalls)
            .put("handledCases", JSONArray(handled))
    }

    private companion object {
        val MODELS = listOf("e2b", "e4b")
    }
}
