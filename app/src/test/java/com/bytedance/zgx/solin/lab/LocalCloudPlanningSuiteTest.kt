package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.common.toLowerHex
import com.bytedance.zgx.solin.orchestration.collab.CloudMilestonePlanner
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
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
        val tags = runCatching { client.tags() }.getOrNull()
        val models = availableLabModels(tags).filter { it in PRODUCTION_MODELS }
        Assume.assumeTrue("no production LiteRT models available", models.isNotEmpty())
        val servedTags = requireNotNull(tags)
        verifyProductionFiles(models)
        verifyServingDigests(servedTags, models)
        LabManifest.recordModels(client, models, tags = servedTags)
        val allCases = CloudPlanningSuiteTest.CASES
        val selectedCases = allCases
            .filter { case -> LabEnv.requestedCaseIds?.contains(case.id) != false }
            .let { LabEnv.caseLimit?.let(it::take) ?: it }
        Assume.assumeTrue("no cloud planning cases selected", selectedCases.isNotEmpty())
        LabManifest.recordSuite(
            SUITE,
            JSONObject()
                .put("cases", selectedCases.size)
                .put("datasetCases", allCases.size)
                .put("samplingOptions", LocalLiteRtMilestonePlannerModel.PLANNING_OPTIONS.toJson())
                .put("modes", JSONArray(listOf("sequential", "branching")))
        )

        val outcomes = models.map { model ->
            client.warmUp(model)
            val adapter = LocalLiteRtMilestonePlannerModel(client, model)
            val planner = CloudMilestonePlanner(adapter)
            val results = selectedCases.map { case ->
                JSONObject()
                    .put("caseId", case.id)
                    .put("sequential", evaluator.evaluate(planner, case, sequential = true) { adapter.lastRawOutput })
                    .put("branching", evaluator.evaluate(planner, case, sequential = false) { adapter.lastRawOutput })
            }
            val report = evaluator.comparisonSummary(results)
                .put("model", model)
                .put("verifiedDigest", EXPECTED_MODELS.getValue(model).digest)
            Triple(model, report, results)
        }

        val reports = outcomes.map { it.second }
        val report = JSONObject()
            .put("schema", "SolinLocalCloudPlanningReport/v1")
            .put("evidence", "devbox CPU / production LiteRT files; not cloud or device evidence")
            .put("hostNote", LabEnv.hostNote)
            .put("models", JSONArray(reports))
        val output = File(LabEnv.resultsDir, "local-cloud-planning-summary.json")
        output.parentFile?.mkdirs()
        output.writeText(report.toString())
        println("local cloud planning: $report")

        outcomes.forEach { (model, _, results) ->
            evaluator.assertAtLeastOneCorrect(results, model)
        }
    }

    @Test
    fun manifestRecordsActualOutputCap() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("""{"version":"litert-lm-0.12.0"}""").build())
            server.enqueue(MockResponse.Builder().code(200).body("""{"models":[]}""").build())
            server.start()

            val temporary = Files.createTempDirectory("local-cloud-planning-manifest").toFile()
            try {
                val output = File(temporary, "manifest.json")
                LabManifest.recordModels(OllamaClient(server.url("/").toString()), listOf("litert/gemma-4-e2b"), output)
                LabManifest.recordSuite(
                    SUITE,
                    JSONObject().put("samplingOptions", LocalLiteRtMilestonePlannerModel.PLANNING_OPTIONS.toJson()),
                    output
                )
                val manifest = JSONObject(output.readText())
                assertEquals(384, manifest.getJSONObject("samplingOptions").getInt("num_predict"))
                assertEquals(
                    1_024,
                    manifest.getJSONObject("suites")
                        .getJSONObject(SUITE)
                        .getJSONObject("samplingOptions")
                        .getInt("num_predict")
                )
            } finally {
                temporary.deleteRecursively()
            }
        }
    }

    @Test
    fun servingDigestMustMatchVerifiedModel() {
        val tags = JSONArray("""[{"name":"litert/gemma-4-e2b","digest":"different"}]""")
        var rejected = false
        try {
            verifyServingDigests(tags, listOf("litert/gemma-4-e2b"))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private fun verifyServingDigests(tags: JSONArray, models: List<String>) {
        val digests = tags.modelTagsByName().mapValues { (_, tag) -> tag.optString("digest") }
        models.forEach { model ->
            require(digests[model] == EXPECTED_MODELS.getValue(model).digest) {
                "serving model digest mismatch for $model"
            }
        }
    }

    private fun verifyProductionFiles(models: List<String>) {
        val root = requireNotNull(LabEnv.liteRtModelRoot) {
            "SOLIN_LAB_LITERT_MODEL_ROOT is required for production-file evidence"
        }
        models.forEach { model ->
            val expected = EXPECTED_MODELS.getValue(model)
            val file = File(root, expected.fileName)
            require(file.isFile) { "missing production model: $file" }
            require(sha256File(file) == expected.digest) { "production model digest mismatch: $file" }
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1_048_576)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toLowerHex()
    }

    private data class ExpectedModel(val fileName: String, val digest: String)

    private companion object {
        const val SUITE = "local-cloud-planning"
        val PRODUCTION_MODELS = setOf("litert/gemma-4-e2b", "litert/gemma-4-e4b")
        val EXPECTED_MODELS = mapOf(
            "litert/gemma-4-e2b" to ExpectedModel(
                fileName = "gemma-4-E2B-it.litertlm",
                digest = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
            ),
            "litert/gemma-4-e4b" to ExpectedModel(
                fileName = "gemma-4-E4B-it.litertlm",
                digest = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
            )
        )
    }
}
