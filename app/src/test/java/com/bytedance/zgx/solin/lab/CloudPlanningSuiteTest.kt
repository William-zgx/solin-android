package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.orchestration.collab.BranchCapabilities
import com.bytedance.zgx.solin.orchestration.collab.BranchParseResult
import com.bytedance.zgx.solin.orchestration.collab.CloudMilestonePlanner
import com.bytedance.zgx.solin.orchestration.collab.MilestoneIntent
import com.bytedance.zgx.solin.orchestration.collab.MilestoneParseResult
import com.bytedance.zgx.solin.runtime.OkHttpRemoteChatRuntime
import java.io.File
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/** Optional live request-only comparison. No endpoint is contacted unless explicitly configured. */
class CloudPlanningSuiteTest {
    @Test
    fun liveRequestOnlyComparisonWhenEndpointConfigured() {
        requireLabOptIn("A")
        val baseUrl = System.getenv("SOLIN_LAB_REMOTE_BASE_URL").orEmpty()
        val modelName = System.getenv("SOLIN_LAB_REMOTE_MODEL").orEmpty()
        Assume.assumeTrue(
            "set SOLIN_LAB_REMOTE_BASE_URL and SOLIN_LAB_REMOTE_MODEL for live cloud planning",
            baseUrl.isNotBlank() && modelName.isNotBlank()
        )
        val config = RemoteModelConfig(
            baseUrl = baseUrl,
            modelName = modelName,
            apiKey = System.getenv("SOLIN_LAB_REMOTE_API_KEY").orEmpty()
        )
        val runtime = OkHttpRemoteChatRuntime(maxOutputTokens = 1_024)
        val planner = CloudMilestonePlanner(RemoteMilestonePlannerModel(runtime, config))
        try {
            val results = CASES.map { case ->
                JSONObject()
                    .put("caseId", case.id)
                    .put("sequential", evaluate(planner, case, sequential = true))
                    .put("branching", evaluate(planner, case, sequential = false))
            }
            val summary = JSONObject()
                .put("schema", "SolinCloudPlanningReport/v1")
                .put("evidence", "live request-only remote lab; not production or device evidence")
                .put("model", config.normalized().modelName)
                .put("caseCount", results.size)
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
                .put("cases", JSONArray(results))
            val output = File(LabEnv.repoRoot, "build/verification/cloud-planning/live-summary.json")
            output.parentFile?.mkdirs()
            output.writeText(summary.toString())
            println("live cloud planning: $summary")

            val errors = results.flatMap { result -> listOf("sequential", "branching").map { mode -> result.getJSONObject(mode) } }
                .filter { it.has("errorType") }
            assertTrue("live planner errors: $errors", errors.isEmpty())
            listOf("sequential", "branching").forEach { mode ->
                assertTrue(
                    "no valid $mode plan",
                    results.any { result -> result.getJSONObject(mode).getBoolean("correct") }
                )
            }
        } finally {
            runtime.stop()
        }
    }

    private fun evaluate(planner: CloudMilestonePlanner, case: CloudCase, sequential: Boolean): JSONObject {
        val started = System.nanoTime()
        return try {
            val correct = if (sequential) {
                when (val result = planner.planSequential(case.request)) {
                    is MilestoneParseResult.Accepted -> result.plan.milestones.map { it.intent } == case.expectedIntents
                    is MilestoneParseResult.Rejected -> false
                }
            } else {
                when (val result = planner.planBranching(case.request, case.capabilities)) {
                    is BranchParseResult.Accepted -> result.plan.nodes.map { it.milestone.intent } == case.expectedIntents
                    is BranchParseResult.Rejected -> false
                }
            }
            JSONObject()
                .put("correct", correct)
                .put("wallMs", (System.nanoTime() - started) / 1_000_000)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            JSONObject()
                .put("correct", false)
                .put("errorType", error.javaClass.simpleName)
                .put("wallMs", (System.nanoTime() - started) / 1_000_000)
        }
    }

    private data class CloudCase(
        val id: String,
        val request: String,
        val capabilities: BranchCapabilities,
        val expectedIntents: List<MilestoneIntent>
    )

    private companion object {
        val CASES = listOf(
            CloudCase(
                id = "taobao_search",
                request = "打开淘宝搜索牛奶",
                capabilities = BranchCapabilities(
                    intents = setOf(MilestoneIntent.OpenApp, MilestoneIntent.SearchInApp),
                    apps = setOf("com.taobao.taobao"),
                    tools = setOf(MobileActionFunctions.SET_SYSTEM_ALARM)
                ),
                expectedIntents = listOf(MilestoneIntent.OpenApp, MilestoneIntent.SearchInApp)
            ),
            CloudCase(
                id = "alarm",
                request = "设一个明天早上7点的闹钟",
                capabilities = BranchCapabilities(
                    intents = setOf(MilestoneIntent.UseTool),
                    apps = setOf("com.taobao.taobao"),
                    tools = setOf(MobileActionFunctions.SET_SYSTEM_ALARM)
                ),
                expectedIntents = listOf(MilestoneIntent.UseTool)
            ),
            CloudCase(
                id = "amap_search",
                request = "打开高德地图搜索北京机场",
                capabilities = BranchCapabilities(
                    intents = setOf(MilestoneIntent.OpenApp, MilestoneIntent.SearchInApp),
                    apps = setOf("com.autonavi.minimap"),
                    tools = setOf(MobileActionFunctions.SET_SYSTEM_ALARM)
                ),
                expectedIntents = listOf(MilestoneIntent.OpenApp, MilestoneIntent.SearchInApp)
            )
        )
    }
}
