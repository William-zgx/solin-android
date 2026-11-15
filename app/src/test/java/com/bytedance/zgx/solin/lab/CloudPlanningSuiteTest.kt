package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.orchestration.collab.BranchCapabilities
import com.bytedance.zgx.solin.orchestration.collab.BranchNode
import com.bytedance.zgx.solin.orchestration.collab.BranchParseResult
import com.bytedance.zgx.solin.orchestration.collab.CloudMilestonePlanner
import com.bytedance.zgx.solin.orchestration.collab.CloudPlannerRequest
import com.bytedance.zgx.solin.orchestration.collab.LocalPredicate
import com.bytedance.zgx.solin.orchestration.collab.Milestone
import com.bytedance.zgx.solin.orchestration.collab.MilestoneIntent
import com.bytedance.zgx.solin.orchestration.collab.MilestoneParseResult
import com.bytedance.zgx.solin.runtime.OkHttpRemoteChatRuntime
import java.io.File
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            val summary = comparisonSummary(results)
                .put("schema", "SolinCloudPlanningReport/v1")
                .put("evidence", "live request-only remote lab; not production or device evidence")
                .put("model", config.normalized().modelName)
                .put("caseCount", results.size)
            val output = File(LabEnv.repoRoot, "build/verification/cloud-planning/live-summary.json")
            output.parentFile?.mkdirs()
            output.writeText(summary.toString())
            println("live cloud planning: $summary")

            assertAtLeastOneCorrect(results, config.normalized().modelName)
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun acceptedButWrongPlansAreNotCorrectAndRejectionDiagnosticsSurvive() {
        val wrongQuery = CloudMilestonePlanner { _, _ ->
            """{"version":1,"milestones":[{"intent":"search_in_app","app":"com.taobao.taobao","query":"北京机场"}]}"""
        }
        val wrongResult = evaluate(wrongQuery, CASES.first().copy(request = "打开淘宝搜索牛奶和北京机场"), sequential = true)
        assertTrue(wrongResult.getBoolean("accepted"))
        assertFalse(wrongResult.getBoolean("correct"))
        assertEquals("北京机场", wrongResult.getJSONArray("plan").getJSONObject(0).getString("query"))

        val wrongPredicate = CloudMilestonePlanner { _, _ ->
            val root = JSONObject(BRANCHING_PLAN)
            root.getJSONArray("nodes").getJSONObject(1).put("predicate", "last_action_succeeded")
            root.toString()
        }
        val wrongBranch = evaluate(wrongPredicate, CASES.first(), sequential = false)
        assertTrue(wrongBranch.getBoolean("accepted"))
        assertFalse(wrongBranch.getBoolean("correct"))

        val rejected = evaluate(CloudMilestonePlanner { _, _ -> "$SEQUENTIAL_PLAN trailing" }, CASES.first(), sequential = true)
        assertFalse(rejected.getBoolean("accepted"))
        assertFalse(rejected.getBoolean("correct"))
        assertEquals("TrailingContent", rejected.getString("rejectionReason"))

        val summary = comparisonSummary(
            listOf(
                JSONObject()
                    .put("caseId", "taobao_search")
                    .put("sequential", wrongResult)
                    .put("branching", wrongBranch)
            )
        )
        assertEquals("taobao_search", summary.getJSONArray("cases").getJSONObject(0).getString("caseId"))
        assertFalse(summary.getJSONObject("metrics").has("cases"))
    }

    internal fun evaluate(
        planner: CloudMilestonePlanner,
        case: CloudCase,
        sequential: Boolean,
        rawPreview: (() -> String?)? = null
    ): JSONObject {
        val started = System.nanoTime()
        val result = JSONObject()
            .put("cloudCalls", 1)
            .put("requestBytes", CloudPlannerRequest.render(case.request).text.toByteArray(Charsets.UTF_8).size)
        return try {
            if (sequential) {
                when (val parsed = planner.planSequential(case.request)) {
                    is MilestoneParseResult.Accepted ->
                        result
                            .put("accepted", true)
                            .put("correct", parsed.plan.milestones == case.expectedMilestones)
                            .put("plan", milestonesJson(parsed.plan.milestones))
                    is MilestoneParseResult.Rejected ->
                        result
                            .put("accepted", false)
                            .put("correct", false)
                            .put("rejectionReason", parsed.reason.name)
                            .put("rejectionIndex", parsed.index ?: JSONObject.NULL)
                }
            } else {
                when (val parsed = planner.planBranching(case.request, case.capabilities)) {
                    is BranchParseResult.Accepted ->
                        result
                            .put("accepted", true)
                            .put("correct", branchMatches(case, parsed.plan.nodes))
                            .put("plan", branchJson(parsed.plan.nodes))
                    is BranchParseResult.Rejected ->
                        result
                            .put("accepted", false)
                            .put("correct", false)
                            .put("rejectionReason", parsed.reason.name)
                            .put("rejectionIndex", parsed.index ?: JSONObject.NULL)
                }
            }
            rawPreview?.invoke()?.let { raw ->
                result.put("rawPreview", raw.previewForLab())
            }
            result.put("wallMs", (System.nanoTime() - started) / 1_000_000)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            result
                .put("accepted", false)
                .put("correct", false)
                .put("errorType", error.javaClass.simpleName)
                .put("wallMs", (System.nanoTime() - started) / 1_000_000)
        }
    }

    private fun branchMatches(case: CloudCase, nodes: List<BranchNode>): Boolean = nodes.map { it.milestone } == case.expectedMilestones &&
        nodes.indices.all { index ->
            val node = nodes[index]
            val expected = case.expectedBranches[index]
            node.predicate == expected.predicate &&
                destinationMatches(expected.onTrue, index, nodes, node.onTrue) &&
                destinationMatches(expected.onFalse, index, nodes, node.onFalse)
        }

    private fun destinationMatches(
        expected: BranchDestination,
        index: Int,
        nodes: List<BranchNode>,
        actual: String
    ): Boolean = when (expected) {
        BranchDestination.Next -> index + 1 < nodes.size && actual == nodes[index + 1].id
        BranchDestination.Done -> actual == "done"
        BranchDestination.Blocked -> actual == "blocked"
    }

    private fun milestonesJson(milestones: List<Milestone>): JSONArray = JSONArray().apply {
        milestones.forEach { milestone ->
            put(
                JSONObject()
                    .put("intent", milestone.intent.wireName)
                    .put("app", milestone.app ?: JSONObject.NULL)
                    .put("query", milestone.query ?: JSONObject.NULL)
                    .put("tool", milestone.tool ?: JSONObject.NULL)
            )
        }
    }

    private fun branchJson(nodes: List<BranchNode>): JSONArray = JSONArray().apply {
        nodes.forEach { node ->
            put(
                JSONObject()
                    .put("id", node.id)
                    .put("predicate", node.predicate.wireName)
                    .put("onTrue", node.onTrue)
                    .put("onFalse", node.onFalse)
                    .put("milestone", milestonesJson(listOf(node.milestone)).getJSONObject(0))
            )
        }
    }

    internal fun comparisonSummary(results: List<JSONObject>): JSONObject = JSONObject()
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
                .put("sequential", metrics(results, "sequential"))
                .put("branching", metrics(results, "branching"))
        )
        .put("cases", JSONArray(results))

    internal fun transportErrors(results: List<JSONObject>): List<JSONObject> = results.flatMap { result -> listOf("sequential", "branching").map { mode -> result.getJSONObject(mode) } }
        .filter { it.has("errorType") }

    internal fun assertAtLeastOneCorrect(results: List<JSONObject>, model: String) {
        val errors = transportErrors(results)
        assertTrue("live planner errors for $model: $errors", errors.isEmpty())
        listOf("sequential", "branching").forEach { mode ->
            assertTrue("no valid $mode plan for $model", results.any { result -> result.getJSONObject(mode).getBoolean("correct") })
        }
    }

    internal fun metrics(results: List<JSONObject>, mode: String): JSONObject {
        val values = results.map { it.getJSONObject(mode) }
        return JSONObject()
            .put("correct", values.count { it.getBoolean("correct") })
            .put("accepted", values.count { it.getBoolean("accepted") })
            .put("cloudCalls", values.sumOf { it.getInt("cloudCalls") })
            .put("requestBytes", values.sumOf { it.getInt("requestBytes") })
            .put("wallMs", values.sumOf { it.getLong("wallMs") })
    }

    internal enum class BranchDestination { Next, Done, Blocked }

    internal data class ExpectedBranch(
        val predicate: LocalPredicate,
        val onTrue: BranchDestination,
        val onFalse: BranchDestination
    )

    internal data class CloudCase(
        val id: String,
        val request: String,
        val capabilities: BranchCapabilities,
        val expectedMilestones: List<Milestone>,
        val expectedBranches: List<ExpectedBranch>
    )

    internal companion object {
        val CASES = listOf(
            searchCase("taobao_search", "打开淘宝搜索牛奶", "com.taobao.taobao", "牛奶"),
            alarmCase(),
            searchCase("amap_search", "打开高德地图搜索北京机场", "com.autonavi.minimap", "北京机场")
        )
        const val SEQUENTIAL_PLAN =
            """{"version":1,"milestones":[{"intent":"search_in_app","app":"com.taobao.taobao","query":"牛奶"}]}"""
        val BRANCHING_PLAN = JSONObject()
            .put("version", 1)
            .put(
                "nodes",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "open")
                            .put("milestone", JSONObject().put("intent", "open_app").put("app", "com.taobao.taobao"))
                            .put("predicate", "planned_app_foreground")
                            .put("on_true", "search")
                            .put("on_false", "blocked")
                    )
                    .put(
                        JSONObject()
                            .put("id", "search")
                            .put(
                                "milestone",
                                JSONObject()
                                    .put("intent", "search_in_app")
                                    .put("app", "com.taobao.taobao")
                                    .put("query", "牛奶")
                            )
                            .put("predicate", "results_match_query")
                            .put("on_true", "done")
                            .put("on_false", "blocked")
                    )
            )
            .toString()

        fun searchCase(id: String, request: String, app: String, query: String) = CloudCase(
            id = id,
            request = request,
            capabilities = BranchCapabilities(
                intents = setOf(MilestoneIntent.OpenApp, MilestoneIntent.SearchInApp),
                apps = setOf(app),
                tools = setOf(MobileActionFunctions.SET_SYSTEM_ALARM)
            ),
            expectedMilestones = listOf(
                Milestone(MilestoneIntent.OpenApp, app = app),
                Milestone(MilestoneIntent.SearchInApp, app = app, query = query)
            ),
            expectedBranches = listOf(
                ExpectedBranch(LocalPredicate.PlannedAppForeground, BranchDestination.Next, BranchDestination.Blocked),
                ExpectedBranch(LocalPredicate.ResultsMatchQuery, BranchDestination.Done, BranchDestination.Blocked)
            )
        )

        fun alarmCase() = CloudCase(
            id = "alarm",
            request = "设一个明天早上7点的闹钟",
            capabilities = BranchCapabilities(
                intents = setOf(MilestoneIntent.UseTool),
                apps = setOf("com.taobao.taobao"),
                tools = setOf(MobileActionFunctions.SET_SYSTEM_ALARM)
            ),
            expectedMilestones = listOf(Milestone(MilestoneIntent.UseTool, tool = MobileActionFunctions.SET_SYSTEM_ALARM)),
            expectedBranches = listOf(
                ExpectedBranch(LocalPredicate.LastActionSucceeded, BranchDestination.Done, BranchDestination.Blocked)
            )
        )
    }
}
