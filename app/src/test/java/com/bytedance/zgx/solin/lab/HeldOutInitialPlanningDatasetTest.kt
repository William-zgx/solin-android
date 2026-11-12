package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic, offline checks for the evaluation-only held-out initial-planning set. */
class HeldOutInitialPlanningDatasetTest {
    private val registry: ToolRegistry = defaultBuiltInToolRegistry

    @Test
    fun heldOutMetadataPinsTheDeclaredDistribution() {
        val cases = InitialPlanningDatasets.heldout.loadAll()
        val metadata = InitialPlanningDatasets.heldout.loadMetadata()

        assertEquals(InitialPlanningDatasets.heldout.datasetFile, metadata.getString("datasetFile"))
        assertTrue(cases.all { it.source == "heldout_evaluation_only" })
        assertEvaluationOnlyMetadata(cases, metadata)
        val toolCategories = cases.filter { it.json.getString("group") == "tool" }
            .mapTo(mutableSetOf()) { it.category.orEmpty() }
        assertTrue(toolCategories.size >= metadata.getInt("minimumToolFamilyCount"))
        assertTrue(toolCategories.containsAll(metadata.getJSONArray("requiredToolCategories").stringList()))
        val adversarialCategories = cases.filter { it.json.getString("group") == "adversarial" }
            .mapTo(mutableSetOf()) { it.category.orEmpty() }
        assertTrue(
            adversarialCategories.containsAll(metadata.getJSONArray("requiredAdversarialCategories").stringList())
        )
        assertInitialPlanningLabelsValid(cases, registry)
    }

    @Test
    fun heldOutIdsAndNormalizedInputsAreUniqueAndDisjointFromTheTuningSet() {
        assertUniqueAndDisjoint(InitialPlanningDatasets.heldout.loadAll(), InitialPlanningCases.loadAll())
    }

    @Test
    fun heldOutRecordsRetainTheirGroupAndClassifyFalsePositives() {
        val case = InitialPlanningDatasets.heldout.loadAll().single { it.id == "heldout_no_tool_014" }
        val record = InitialPlanningCases.record(
            suite = "initial-planning",
            model = "rules",
            mode = "R",
            case = case,
            answer = LabAnswer("web_search", mapOf("query" to "unwanted")),
            rawKind = "use_tool",
            rawPreview = "",
            registry = registry
        )

        assertEquals("no_tool", record.getString("group"))
        assertEquals("tool_domain_question", record.getString("category"))
        assertTrue(record.getBoolean("falsePositive"))
        assertEquals("device_network_external", record.getString("falsePositiveClass"))
        assertTrue(record.getBoolean("fpDeviceNetworkExternal"))
        assertTrue(record.getBoolean("fpNetworkEgress"))
        assertTrue(record.getBoolean("privacyRelevant"))
    }
}
