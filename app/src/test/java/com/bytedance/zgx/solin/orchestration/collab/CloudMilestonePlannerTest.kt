package com.bytedance.zgx.solin.orchestration.collab

import com.bytedance.zgx.solin.action.MobileActionFunctions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudMilestonePlannerTest {
    @Test
    fun sequentialUsesTheExactRenderedRequestWithoutDeviceEvidence() {
        var instruction = ""
        var rendered = ""
        val planner = CloudMilestonePlanner { system, request ->
            instruction = system
            rendered = request
            SEQUENTIAL_PLAN
        }

        val result = planner.planSequential(REQUEST)

        assertTrue(result is MilestoneParseResult.Accepted)
        assertEquals(CloudPlannerRequest.render(REQUEST).text, rendered)
        assertFalse(rendered.contains("screenObservation"))
        assertFalse(rendered.contains("progress"))
        assertEquals(CloudMilestonePlanner.SEQUENTIAL_INSTRUCTION, instruction)
        assertFalse(rendered.contains(CloudMilestonePlanner.SEQUENTIAL_INSTRUCTION))
    }

    @Test
    fun branchingCarriesOnlyPublicCapabilitiesInTheInstruction() {
        var instruction = ""
        var rendered = ""
        val planner = CloudMilestonePlanner { system, request ->
            instruction = system
            rendered = request
            BRANCHING_PLAN
        }

        val result = planner.planBranching(REQUEST, capabilities())

        assertTrue(result is BranchParseResult.Accepted)
        assertEquals(CloudPlannerRequest.render(REQUEST).text, rendered)
        assertTrue(instruction.contains(TAOBAO))
        assertFalse(instruction.contains(WECHAT))
        assertTrue(instruction.contains("forward-only"))
        assertFalse(rendered.contains("progress"))
    }

    @Test
    fun modelOutputOutsideCapabilitiesOrSchemaIsRejected() {
        val outside = CloudMilestonePlanner { _, _ ->
            val root = JSONObject(BRANCHING_PLAN)
            root.getJSONArray("nodes").getJSONObject(1)
                .put("milestone", JSONObject().put("intent", "open_app").put("app", WECHAT))
            root.toString()
        }.planBranching(REQUEST, capabilities())
        assertEquals(
            BranchParseResult.Rejected(BranchRejection.CapabilityNotAllowed, 1),
            outside
        )

        val malformed = CloudMilestonePlanner { _, _ -> "$SEQUENTIAL_PLAN trailing" }
            .planSequential(REQUEST)
        assertEquals(MilestoneParseResult.Rejected(MilestoneRejection.TrailingContent), malformed)
    }

    @Test
    fun invalidCapabilitiesNeverReachTheRemoteModel() {
        var calls = 0
        val planner = CloudMilestonePlanner { _, _ ->
            calls++
            BRANCHING_PLAN
        }
        val invalid = capabilities().copy(apps = setOf("com.example.private"))

        val result = planner.planBranching(REQUEST, invalid)

        assertEquals(BranchParseResult.Rejected(BranchRejection.CapabilityNotAllowed), result)
        assertEquals(0, calls)
    }

    private fun capabilities() = BranchCapabilities(
        intents = setOf(MilestoneIntent.SearchInApp, MilestoneIntent.OpenApp),
        apps = setOf(TAOBAO),
        tools = setOf(MobileActionFunctions.SET_SYSTEM_ALARM)
    )

    private companion object {
        const val REQUEST = "在淘宝搜索牛奶"
        const val TAOBAO = "com.taobao.taobao"
        const val WECHAT = "com.tencent.mm"
        const val SEQUENTIAL_PLAN =
            """{"version":1,"milestones":[{"intent":"search_in_app","app":"com.taobao.taobao","query":"牛奶"}]}"""
        val BRANCHING_PLAN = JSONObject()
            .put("version", 1)
            .put(
                "nodes",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "search")
                            .put(
                                "milestone",
                                JSONObject()
                                    .put("intent", "search_in_app")
                                    .put("app", TAOBAO)
                                    .put("query", "牛奶")
                            )
                            .put("predicate", "results_match_query")
                            .put("on_true", "done")
                            .put("on_false", "open")
                    )
                    .put(
                        JSONObject()
                            .put("id", "open")
                            .put("milestone", JSONObject().put("intent", "open_app").put("app", TAOBAO))
                            .put("predicate", "planned_app_foreground")
                            .put("on_true", "done")
                            .put("on_false", "blocked")
                    )
            )
            .toString()
    }
}
