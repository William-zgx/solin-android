package com.bytedance.zgx.pocketmind.eval

import com.bytedance.zgx.pocketmind.capability.CapabilityMatrix
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBehaviorEvalFixturesTest {
    private val toolRegistry = ToolRegistry()

    @Test
    fun fixturesCoverPhaseOneBehaviorCategories() {
        val expected = listOf(
            "memory_recall",
            "planner_false_positive",
            "tool_sequence",
            "ocr_noise",
            "runtime_failure",
            "privacy_boundary",
            "restart_recovery",
        )
        val seenIds = linkedSetOf<String>()

        val loadedCategories = expected.map { name ->
            val rows = loadFixtureRows("$name.jsonl")
            assertTrue("$name should have at least two cases", rows.size >= 2)
            rows.forEach { row ->
                val id = row.getString("id")
                assertTrue("eval fixture id must be stable and non-blank", id.matches(Regex("^[a-z0-9][a-z0-9_.-]*$")))
                assertTrue("Duplicate eval fixture id: $id", seenIds.add(id))
                assertEquals(name, row.getString("category"))
                assertTrue(row.getString("input").isNotBlank())
                assertTrue(row.getString("expectedBoundary").isNotBlank())
                assertTrue(row.getString("ownerAgent").isNotBlank())
                assertTrue(row.getString("mvpScenario").isNotBlank())
                assertTraceExpectationFields(row)
            }
            name
        }

        assertEquals(expected, loadedCategories)
    }

    @Test
    fun fixturesCoverNextStageMvpScenarios() {
        val expectedScenarios = CapabilityMatrix.nextStageMvpScenarioIds.toSet()
        val scenarioCounts = mutableMapOf<String, Int>()

        listOf(
            "memory_recall",
            "planner_false_positive",
            "tool_sequence",
            "ocr_noise",
            "runtime_failure",
            "privacy_boundary",
            "restart_recovery",
        ).flatMap { loadFixtureRows("$it.jsonl") }
            .forEach { row ->
                val scenario = row.getString("mvpScenario")
                scenarioCounts[scenario] = scenarioCounts.getOrDefault(scenario, 0) + 1
            }

        assertEquals(expectedScenarios, scenarioCounts.keys)
        expectedScenarios.forEach { scenario ->
            assertTrue("$scenario should have at least two eval cases", scenarioCounts.getValue(scenario) >= 2)
        }
    }

    @Test
    fun fixturesDeclarePlanningTraceExpectations() {
        val rows = listOf(
            "memory_recall",
            "planner_false_positive",
            "tool_sequence",
            "ocr_noise",
            "runtime_failure",
            "privacy_boundary",
            "restart_recovery",
        ).flatMap { loadFixtureRows("$it.jsonl") }

        assertTrue("eval suite should include at least one expected tool", rows.any { row ->
            row.getJSONArray("expectedTools").length() > 0
        })
        assertTrue("eval suite should include fail-closed cases", rows.any { row ->
            row.getString("expectedConfirmation") == "fail_closed"
        })
        assertTrue("eval suite should include remote-send confirmation cases", rows.any { row ->
            row.getString("expectedConfirmation") == "remote_send_confirmation"
        })
        assertTrue("eval suite should include remote-eligible cases", rows.any { row ->
            row.getBoolean("remoteEligible")
        })
        assertTrue("eval suite should include LocalOnly cases", rows.any { row ->
            row.getBoolean("localOnly")
        })
    }

    @Test
    fun runtimeFailureFixturesCoverRealAppSearchFailureModes() {
        val expectedFailureModes = setOf(
            "search_entry_not_found",
            "editable_not_found",
            "submit_not_found",
            "result_not_verified",
            "required_hint_missing",
            "page_not_changed",
        )
        val observedFailureModes = loadFixtureRows("runtime_failure.jsonl")
            .flatMap { row -> row.getJSONArray("allowedFailureModes").toStringList() }
            .toSet()

        assertTrue(
            "runtime failure fixtures must cover real-app search failure modes",
            observedFailureModes.containsAll(expectedFailureModes),
        )
    }

    @Test
    fun runtimeFailureFixturesCoverSafetyFailureModes() {
        val observedFailureModes = loadFixtureRows("runtime_failure.jsonl")
            .flatMap { row -> row.getJSONArray("allowedFailureModes").toStringList() }
            .toSet()

        assertTrue(
            "runtime failure fixtures must cover permission-denied fail-closed recovery",
            "permissiondenied" in observedFailureModes,
        )
    }

    @Test
    fun privacyFixturesCoverPublicEvidenceBatchBoundary() {
        val observedBoundaries = loadFixtureRows("privacy_boundary.jsonl")
            .map { row -> row.getString("expectedBoundary") }
            .toSet()

        assertTrue(
            "privacy fixtures must cover multi-tool public evidence batch allowance",
            "public_evidence_multi_search_batch_allowed" in observedBoundaries,
        )
    }

    private fun assertTraceExpectationFields(row: JSONObject) {
        val tools = row.get("expectedTools")
        assertTrue("expectedTools must be an array", tools is JSONArray)
        val expectedTools = (tools as JSONArray).toStringList()
        expectedTools.forEach { toolName ->
            assertTrue("Unknown expected tool in eval fixture: $toolName", toolRegistry.isKnownTool(toolName))
        }
        val confirmation = row.getString("expectedConfirmation")
        assertTrue(
            "unexpected confirmation expectation: $confirmation",
            confirmation in setOf("none", "tool_confirmation", "remote_send_confirmation", "second_confirmation", "fail_closed"),
        )
        val risk = row.getString("expectedRiskLevel")
        assertTrue(
            "unexpected risk level: $risk",
            risk in setOf("public_evidence", "low", "medium", "high", "sensitive"),
        )
        val privacy = row.getString("privacy")
        assertTrue("unexpected privacy: $privacy", privacy in setOf("LocalOnly", "RemoteEligible"))
        val localOnly = row.getBoolean("localOnly")
        val remoteEligible = row.getBoolean("remoteEligible")
        when (privacy) {
            "LocalOnly" -> {
                assertEquals(true, localOnly)
                assertEquals(false, remoteEligible)
            }
            "RemoteEligible" -> {
                assertEquals(false, localOnly)
                assertEquals(true, remoteEligible)
            }
        }
        if (confirmation == "remote_send_confirmation") {
            assertEquals("remote send confirmation must be RemoteEligible", "RemoteEligible", privacy)
            assertEquals(false, localOnly)
            assertEquals(true, remoteEligible)
        }
        assertTrue("allowedFailureModes must be an array", row.get("allowedFailureModes") is JSONArray)
        row.getJSONArray("allowedFailureModes").toStringList().forEach { failureMode ->
            assertTrue(
                "allowedFailureModes must use stable slug syntax: $failureMode",
                failureMode.matches(Regex("^[a-z0-9][a-z0-9_:-]*$")),
            )
        }
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { index -> getString(index) }

    private fun loadFixtureRows(fileName: String): List<JSONObject> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("ai_behavior_eval/$fileName")
            ?: error("Missing fixture $fileName")
        return stream.bufferedReader().useLines { lines ->
            lines
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map(::JSONObject)
                .toList()
        }
    }
}
