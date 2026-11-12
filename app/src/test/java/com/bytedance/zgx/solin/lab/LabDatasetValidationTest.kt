package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.action.actionPrompt
import com.bytedance.zgx.solin.orchestration.MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES
import com.bytedance.zgx.solin.orchestration.MODEL_OBSERVATION_REPLAN_COMPOSITE_TOOL_NAMES
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the committed datasets against the production registries and fixtures (no model calls), and
 * records model-independent facts the summary needs: prompt sizes and retriever recall@8.
 */
class LabDatasetValidationTest {
    private val registry: ToolRegistry = defaultBuiltInToolRegistry
    private val observationRegistry = ToolRegistry.fromSupportedActions(
        MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES + MODEL_OBSERVATION_REPLAN_COMPOSITE_TOOL_NAMES
    )

    @Test
    fun initialPlanningLabelsMatchRegistry() {
        requireLabOptIn()
        val cases = InitialPlanningCases.loadAll()
        assertEquals("duplicate ids", cases.size, cases.map(InitialCase::id).toSet().size)
        assertInitialPlanningLabelsValid(cases, registry)
    }

    @Test
    fun observationLabelsMatchFixtures() {
        requireLabOptIn()
        val cases = ObservationStepCases.load()
        assertEquals("duplicate ids", cases.size, cases.map(ObservationCase::id).toSet().size)
        val problems = mutableListOf<String>()
        cases.forEach { case ->
            val built = ObservationStepCases.build(case)
            case.accepts.forEach { accept ->
                if (accept.optBoolean("stop")) return@forEach
                val tool = accept.optString("tool")
                if (!observationRegistry.isKnownTool(tool)) problems += "${case.id}: $tool not in observation registry"
                accept.optJSONArray("targetNodes").stringList()
                    .filterNot(built.nodeLabels::containsKey)
                    .forEach { problems += "${case.id}: unknown target node $it" }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun recordPromptSizesAndRetrieverRecall() {
        requireLabOptIn()
        val retriever = LabToolRetriever(registry.specs())
        val labeled = InitialPlanningCases.loadLabeled()
        val toolCases = labeled.filter { it.goldTools.isNotEmpty() }
        val ranked = toolCases.associateWith { case -> retriever.topK(case.input, registry.specs().size).map(ToolSpec::name) }
        // 1-based rank of the best-ranked acceptable gold tool; Int.MAX_VALUE when no gold tool is ranked.
        val goldRank = ranked.mapValues { (case, names) ->
            names.indexOfFirst { it in case.goldTools }.let { index -> if (index < 0) Int.MAX_VALUE else index + 1 }
        }
        val recallAtK = JSONObject()
        val missesAtK = JSONObject()
        val promptCharsAtK = JSONObject()
        RECALL_KS.forEach { k ->
            val misses = toolCases.filter { goldRank.getValue(it) > k }
            recallAtK.put(k.toString(), JSONObject().put("hits", toolCases.size - misses.size).put("cases", toolCases.size))
            missesAtK.put(k.toString(), JSONArray(misses.map { "${it.id}:${it.goldTools}:${it.input}" }))
            promptCharsAtK.put(
                k.toString(),
                labeled.map { case -> actionPrompt(case.input, retriever.topK(case.input, k)).length }.average().roundToInt()
            )
        }
        val misses8 = toolCases.filter { goldRank.getValue(it) > 8 }
        LabManifest.recordSuite(
            "static",
            JSONObject()
                .put("registryTools", registry.specs().size)
                .put("observationRegistryTools", observationRegistry.specs().size)
                .put("actionPromptCharsFullRegistry", actionPrompt("用户请求", registry.specs()).length)
                .put("actionPromptCharsObservationRegistry", actionPrompt("用户请求", observationRegistry.specs()).length)
                .put("nativeToolSchemaCharsFullRegistry", registry.specs().sumOf { it.inputSchemaJson.length + it.description.length })
                .put("retrieverToolCases", toolCases.size)
                .put("retrieverRecallAt8", (toolCases.size - misses8.size).toDouble() / toolCases.size.coerceAtLeast(1))
                .put("retrieverMisses", misses8.map { "${it.id}:${it.goldTools}:${it.input}" })
                .put("retrieverRecallAtK", recallAtK)
                .put("retrieverMissesAtK", missesAtK)
                .put("retrieverGoldRank", JSONObject(goldRank.mapKeys { it.key.id }))
                .put("actionPromptCharsMeanAtK", promptCharsAtK)
        )
        LabLog.info(
            "static: registry=${registry.specs().size} " +
                RECALL_KS.joinToString(" ") { k -> "recall@$k=${toolCases.count { goldRank.getValue(it) <= k }}/${toolCases.size}" }
        )
    }

    private companion object {
        val RECALL_KS = listOf(4, 8, 12, 16)
    }
}
