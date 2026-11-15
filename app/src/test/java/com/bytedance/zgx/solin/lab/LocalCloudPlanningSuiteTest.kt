package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.orchestration.collab.CloudMilestonePlanner
import java.io.File
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/** Live production-file comparison on the devbox LiteRT shim; not a cloud or phone benchmark. */
class LocalCloudPlanningSuiteTest {
    private val evaluator = CloudPlanningSuiteTest()

    @Test
    fun productionLiteRtModelsCompareSequentialAndBranching() {
        requireLabOptIn("A")
        val client = OllamaClient()
        val models = availableLabModels(client).filter { it in PRODUCTION_MODELS }
        Assume.assumeTrue("no production LiteRT models available", models.isNotEmpty())
        LabManifest.recordModels(client, models, OllamaClient.Options(numPredict = 1_024))
        LabManifest.recordSuite(
            SUITE,
            JSONObject()
                .put("cases", CloudPlanningSuiteTest.CASES.size)
                .put("datasetCases", CloudPlanningSuiteTest.CASES.size)
                .put("modes", JSONArray(listOf("sequential", "branching")))
        )

        val outcomes = models.map { model ->
            client.warmUp(model)
            val planner = CloudMilestonePlanner(LocalLiteRtMilestonePlannerModel(client, model))
            val results = CloudPlanningSuiteTest.CASES
                .filter { case -> LabEnv.requestedCaseIds?.contains(case.id) != false }
                .map { case ->
                    JSONObject()
                        .put("caseId", case.id)
                        .put("sequential", evaluator.evaluate(planner, case, sequential = true))
                        .put("branching", evaluator.evaluate(planner, case, sequential = false))
                }
            val report = JSONObject()
                .put("model", model)
                .put(
                    "fixedVsSequential",
                    JSONArray(
                        results.filter { result ->
                            result.getJSONObject("branching").getBoolean("correct") &&
                                !result.getJSONObject("sequential").getBoolean("correct")
                        }.map { it.getString("caseId") }
                    )
                )
                .put(
                    "brokenVsSequential",
                    JSONArray(
                        results.filter { result ->
                            !result.getJSONObject("branching").getBoolean("correct") &&
                                result.getJSONObject("sequential").getBoolean("correct")
                        }.map { it.getString("caseId") }
                    )
                )
                .put(
                    "metrics",
                    JSONObject()
                        .put("sequential", evaluator.metrics(results, "sequential"))
                        .put("branching", evaluator.metrics(results, "branching"))
                )
                .put("cases", JSONArray(results))
            report to results
        }

        val reports = outcomes.map { it.first }
        val report = JSONObject()
            .put("schema", "SolinLocalCloudPlanningReport/v1")
            .put("evidence", "devbox CPU / production LiteRT files; not cloud or device evidence")
            .put("hostNote", LabEnv.hostNote)
            .put("models", JSONArray(reports))
        val output = File(LabEnv.resultsDir, "local-cloud-planning-summary.json")
        output.parentFile?.mkdirs()
        output.writeText(report.toString())
        println("local cloud planning: $report")

        val errors = outcomes.flatMap { (_, results) ->
            results.flatMap { result -> listOf("sequential", "branching").map { mode -> result.getJSONObject(mode) } }
                .filter { it.has("errorType") }
        }
        assertTrue("live local planner errors: $errors", errors.isEmpty())
        outcomes.forEach { (model, results) ->
            listOf("sequential", "branching").forEach { mode ->
                assertTrue("no valid $mode plan for $model", results.any { result -> result.getJSONObject(mode).getBoolean("correct") })
            }
        }
    }

    @Test
    fun manifestRecordsActualOutputCap() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("""{"version":"litert-lm-0.12.0"}""").build())
            server.enqueue(MockResponse.Builder().code(200).body("""{"models":[]}""").build())
            server.start()

            LabManifest.recordModels(
                OllamaClient(server.url("/").toString()),
                listOf("litert/gemma-4-e2b"),
                OllamaClient.Options(numPredict = 1_024)
            )

            val manifest = JSONObject(File(LabEnv.resultsDir, "manifest.json").readText())
            assertEquals(1_024, manifest.getJSONObject("samplingOptions").getInt("num_predict"))
        }
    }

    private companion object {
        const val SUITE = "local-cloud-planning"
        val PRODUCTION_MODELS = setOf("litert/gemma-4-e2b", "litert/gemma-4-e4b")
    }
}
