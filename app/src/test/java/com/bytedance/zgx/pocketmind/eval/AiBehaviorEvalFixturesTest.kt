package com.bytedance.zgx.pocketmind.eval

import com.bytedance.zgx.pocketmind.capability.CapabilityMatrix
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBehaviorEvalFixturesTest {
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

        val loadedCategories = expected.map { name ->
            val rows = loadFixtureRows("$name.jsonl")
            assertTrue("$name should have at least two cases", rows.size >= 2)
            rows.forEach { row ->
                assertEquals(name, row.getString("category"))
                assertTrue(row.getString("input").isNotBlank())
                assertTrue(row.getString("expectedBoundary").isNotBlank())
                assertTrue(row.getString("ownerAgent").isNotBlank())
                assertTrue(row.getString("mvpScenario").isNotBlank())
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
