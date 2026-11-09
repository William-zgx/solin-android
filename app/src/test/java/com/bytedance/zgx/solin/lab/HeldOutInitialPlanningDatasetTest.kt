package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic, offline checks for the evaluation-only held-out initial-planning set. */
class HeldOutInitialPlanningDatasetTest {
    private val registry: ToolRegistry = defaultBuiltInToolRegistry

    @Test
    fun heldOutMetadataPinsTheDeclaredDistribution() {
        val cases = HeldOutInitialPlanningCases.loadAll()
        val metadata = HeldOutInitialPlanningCases.loadMetadata()

        assertEquals(HeldOutInitialPlanningCases.DATASET_FILENAME, metadata.getString("datasetFile"))
        assertEquals("HELD_OUT_EVALUATION_ONLY", metadata.getString("status"))
        assertTrue(metadata.getBoolean("heldOut"))
        assertTrue(metadata.getBoolean("evaluationOnly"))
        assertTrue(metadata.getBoolean("tuningProhibited"))
        assertTrue(metadata.getString("warning").contains("DO NOT USE FOR PROMPT, RULE, OR THRESHOLD TUNING"))
        assertEquals(
            setOf("prompt tuning", "rule tuning", "threshold tuning"),
            metadata.getJSONArray("prohibitedUses").stringList().toSet(),
        )
        assertEquals(metadata.getInt("caseCount"), cases.size)

        val actualGroups = cases.groupingBy { it.json.getString("group") }.eachCount()
        assertEquals(metadata.getJSONObject("groupCounts").intMap(), actualGroups)
        val declaredCategories = metadata.getJSONObject("categoryCountsByGroup")
        assertEquals(declaredCategories.keys().asSequence().toSet(), actualGroups.keys)
        declaredCategories.keys().forEach { group ->
            val actual = cases.filter { it.json.getString("group") == group }
                .groupingBy { it.category.orEmpty() }
                .eachCount()
            assertEquals("category counts in $group", declaredCategories.getJSONObject(group).intMap(), actual)
        }

        val toolCategories = cases.filter { it.json.getString("group") == "tool" }
            .mapTo(mutableSetOf()) { it.category.orEmpty() }
        assertTrue(toolCategories.size >= metadata.getInt("minimumToolFamilyCount"))
        assertTrue(toolCategories.containsAll(metadata.getJSONArray("requiredToolCategories").stringList()))
        val adversarialCategories = cases.filter { it.json.getString("group") == "adversarial" }
            .mapTo(mutableSetOf()) { it.category.orEmpty() }
        assertTrue(
            adversarialCategories.containsAll(metadata.getJSONArray("requiredAdversarialCategories").stringList()),
        )

        val problems = cases.mapNotNull { case ->
            when {
                case.source != "heldout_evaluation_only" -> "${case.id}: wrong source"
                !case.json.getBoolean("heldOut") -> "${case.id}: heldOut is false"
                !case.json.getBoolean("evaluationOnly") -> "${case.id}: evaluationOnly is false"
                !case.json.getBoolean("tuningProhibited") -> "${case.id}: tuningProhibited is false"
                !case.labeled -> "${case.id}: case is unlabeled"
                case.input.isBlank() -> "${case.id}: blank input"
                case.category.isNullOrBlank() -> "${case.id}: blank category"
                case.json.getString("group") == "tool" && case.json.getBoolean("expectsTool") -> null
                case.json.getString("group") == "no_tool" && !case.json.getBoolean("expectsTool") -> null
                case.json.getString("group") == "adversarial" -> null
                else -> "${case.id}: group and expectsTool disagree"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun heldOutIdsAndNormalizedInputsAreUniqueAndDisjointFromTheTuningSet() {
        val heldOut = HeldOutInitialPlanningCases.loadAll()
        val tuning = InitialPlanningCases.loadAll()

        assertEquals("duplicate held-out ids", heldOut.size, heldOut.map(InitialCase::id).toSet().size)
        val tuningIds = tuning.mapTo(mutableSetOf(), InitialCase::id)
        assertTrue("held-out ids overlap the tuning set", heldOut.none { it.id in tuningIds })

        val normalizedHeldOut = heldOut.map { LabScoring.normalized(it.input) }
        assertEquals(
            "duplicate normalized inputs inside the held-out set",
            normalizedHeldOut.size,
            normalizedHeldOut.toSet().size,
        )
        val normalizedTuning = tuning.mapTo(mutableSetOf()) { LabScoring.normalized(it.input) }
        val duplicates = heldOut.filter { LabScoring.normalized(it.input) in normalizedTuning }
        assertTrue(
            "normalized inputs also in InitialPlanningCases.loadAll(): " +
                duplicates.joinToString { "${it.id}=${it.input}" },
            duplicates.isEmpty(),
        )
    }

    @Test
    fun heldOutGoldToolsAndPinnedArgumentsMatchTheBuiltInRegistry() {
        val problems = mutableListOf<String>()
        HeldOutInitialPlanningCases.loadAll().forEach { case ->
            val expectsTool = case.json.getBoolean("expectsTool")
            if (expectsTool != case.goldTools.isNotEmpty()) {
                problems += "${case.id}: expectsTool does not match gold.tools"
            }
            case.goldTools.filterNot(registry::isKnownTool).forEach { problems += "${case.id}: unknown tool $it" }
            val declaredArguments = case.goldTools.mapNotNull(registry::specFor).flatMap { spec ->
                JSONObject(spec.inputSchemaJson).optJSONObject("properties")?.keys()?.asSequence()?.toList().orEmpty()
            }.toSet()
            (case.goldArgs.keys - declaredArguments).forEach {
                problems += "${case.id}: argument $it not in gold tool schemas"
            }
            if (case.goldTools.isEmpty() && case.goldArgs.isNotEmpty()) {
                problems += "${case.id}: args on a no-tool case"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun heldOutRecordsRetainTheirGroupAndClassifyFalsePositives() {
        val case = HeldOutInitialPlanningCases.loadAll().single { it.id == "heldout_no_tool_014" }
        val record = InitialPlanningCases.record(
            suite = "initial-planning",
            model = "rules",
            mode = "R",
            case = case,
            answer = LabAnswer("web_search", mapOf("query" to "unwanted")),
            rawKind = "use_tool",
            rawPreview = "",
            registry = registry,
        )

        assertEquals("no_tool", record.getString("group"))
        assertEquals("tool_domain_question", record.getString("category"))
        assertTrue(record.getBoolean("falsePositive"))
        assertEquals("device_network_external", record.getString("falsePositiveClass"))
        assertTrue(record.getBoolean("fpDeviceNetworkExternal"))
        assertTrue(record.getBoolean("fpNetworkEgress"))
        assertTrue(record.getBoolean("privacyRelevant"))
    }

    private fun JSONObject.intMap(): Map<String, Int> =
        keys().asSequence().associateWith { getInt(it) }
}
