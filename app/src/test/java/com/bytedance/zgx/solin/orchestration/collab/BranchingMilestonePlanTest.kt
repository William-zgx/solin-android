package com.bytedance.zgx.solin.orchestration.collab

import com.bytedance.zgx.solin.action.MobileActionFunctions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchingMilestonePlanTest {
    @Test
    fun validForwardPlanEvaluatesOnlyTheCurrentBranch() {
        val evaluator = BranchingPlanEvaluator(accepted(validPlan()))
        val first = evaluator.current()
        assertTrue(first is BranchStep.Act)
        assertEquals("search", (first as BranchStep.Act).node.id)

        val second = evaluator.advance(false)
        assertTrue(second is BranchStep.Act)
        assertEquals("open", (second as BranchStep.Act).node.id)
        assertEquals(BranchStep.Done, evaluator.advance(true))
        assertEquals(BranchStep.Blocked, evaluator.advance(true))
    }

    @Test
    fun trueAndUnknownOutcomesTerminateWithoutObservationsLeaving() {
        val done = BranchingPlanEvaluator(accepted(validPlan()))
        assertEquals(BranchStep.Done, done.advance(true))

        val blocked = BranchingPlanEvaluator(accepted(validPlan()))
        assertEquals(BranchStep.Blocked, blocked.advance(null))
        assertEquals(BranchStep.Blocked, blocked.current())
    }

    @Test
    fun strictJsonAndNodeShapeAreRejected() {
        assertRejected(BranchRejection.DuplicateKey, validPlan().replaceFirst("\"version\":1", "\"version\":1,\"version\":1"))
        assertRejected(BranchRejection.TrailingContent, validPlan() + " trailing")
        assertRejected(BranchRejection.UnknownPredicate, editNode(0) { put("predicate", "screen_text") })
        assertRejected(BranchRejection.DuplicateNodeId, editNode(1) { put("id", "search") })
        assertRejected(BranchRejection.InvalidNodeId, editNode(0) { put("id", "done") })
        assertRejected(BranchRejection.InvalidNodeId, editNode(0) { put("id", "blocked") })
        assertRejected(BranchRejection.NonForwardDestination, editNode(0) { put("on_false", "search") })
        assertRejected(BranchRejection.UnknownDestination, editNode(0) { put("on_false", "missing") })
        assertRejected(
            BranchRejection.UnreachableNode,
            editNode(0) {
                put("on_true", "done")
                put("on_false", "blocked")
            }
        )
    }

    @Test
    fun embeddedMilestoneMustRemainCanonicalAndLiteral() {
        assertRejected(
            BranchRejection.InvalidMilestone,
            editNode(0) { put("milestone", JSONObject().put("intent", "search_in_app").put("app", TAOBAO).put("query", "鞋子")) }
        )
        assertRejected(
            BranchRejection.InvalidMilestone,
            editNode(0) { put("milestone", JSONObject().put("intent", "delete_everything")) }
        )
    }

    @Test
    fun branchSteeringCannotEscapeCallerCapabilities() {
        assertRejected(BranchRejection.CapabilityNotAllowed, editNode(1) { put("milestone", JSONObject().put("intent", "open_app").put("app", WECHAT)) })
        val capabilities = capabilities().copy(intents = setOf(MilestoneIntent.SearchInApp))
        assertRejected(BranchRejection.CapabilityNotAllowed, validPlan(), capabilities)
        val toolOnly = JSONObject()
            .put("version", 1)
            .put(
                "nodes",
                JSONArray().put(
                    JSONObject()
                        .put("id", "tool")
                        .put("milestone", JSONObject().put("intent", "use_tool").put("tool", MobileActionFunctions.OPEN_CAMERA))
                        .put("predicate", "last_action_succeeded")
                        .put("on_true", "done")
                        .put("on_false", "blocked")
                )
            )
        assertRejected(BranchRejection.CapabilityNotAllowed, toolOnly.toString())
    }

    @Test
    fun capabilitiesMustBeDeclaredAndInsideTheStaticVocabulary() {
        listOf(
            capabilities().copy(intents = emptySet()),
            capabilities().copy(apps = emptySet()),
            capabilities().copy(tools = emptySet()),
            capabilities().copy(apps = setOf("com.example.unapproved"))
        ).forEach { capabilities ->
            assertRejected(BranchRejection.CapabilityNotAllowed, validPlan(), capabilities)
        }
    }

    private fun accepted(raw: String): BranchingMilestonePlan = when (val result = BranchingMilestonePlanParser.parse(raw, REQUEST, capabilities())) {
        is BranchParseResult.Accepted -> result.plan
        is BranchParseResult.Rejected -> error("expected acceptance, got ${result.reason} at ${result.index}")
    }

    private fun assertRejected(
        reason: BranchRejection,
        raw: String,
        capabilities: BranchCapabilities = capabilities()
    ) {
        val result = BranchingMilestonePlanParser.parse(raw, REQUEST, capabilities)
        assertTrue("expected rejection, got $result", result is BranchParseResult.Rejected)
        assertEquals(reason, (result as BranchParseResult.Rejected).reason)
    }

    private fun editNode(index: Int, edit: JSONObject.() -> Unit): String {
        val root = JSONObject(validPlan())
        root.getJSONArray("nodes").getJSONObject(index).edit()
        return root.toString()
    }

    private fun capabilities() = BranchCapabilities(
        intents = setOf(MilestoneIntent.SearchInApp, MilestoneIntent.OpenApp, MilestoneIntent.UseTool),
        apps = setOf(TAOBAO),
        tools = setOf(MobileActionFunctions.SET_SYSTEM_ALARM)
    )

    private fun validPlan(): String = JSONObject()
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

    private companion object {
        const val REQUEST = "在淘宝搜索牛奶"
        const val TAOBAO = "com.taobao.taobao"
        const val WECHAT = "com.tencent.mm"
    }
}
