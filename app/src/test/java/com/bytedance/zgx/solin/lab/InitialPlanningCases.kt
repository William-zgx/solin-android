package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** One Suites B/C case from `lab/datasets/initial_planning.jsonl`. */
data class InitialCase(val json: JSONObject) {
    val id: String get() = json.getString("id")
    val source: String get() = json.getString("source")
    val input: String get() = json.getString("input")
    val labeled: Boolean get() = json.optBoolean("labeled")
    val category: String? get() = json.optString("category").takeIf(String::isNotBlank)
    val goldTools: List<String> get() = json.optJSONObject("gold")?.optJSONArray("tools").stringList()
    val goldArgs: Map<String, String> get() = json.optJSONObject("gold")?.optJSONObject("args").stringMap()

    fun goldJson(): JSONObject =
        JSONObject().put("tools", goldTools.toJsonStringArray()).put("args", goldArgs.toJson())
}

object InitialPlanningCases {
    fun loadAll(): List<InitialCase> =
        readJsonl(File(LabEnv.datasetsDir, "initial_planning.jsonl")).map(::InitialCase)

    /** The tuned source stays the default; held-out evaluation requires an explicit environment switch. */
    fun loadSelected(): List<InitialCase> = when (LabEnv.initialPlanningCaseSource) {
        "heldout" -> HeldOutInitialPlanningCases.loadAll()
        else -> loadAll()
    }

    fun selectedDatasetFilename(): String = when (LabEnv.initialPlanningCaseSource) {
        "heldout" -> HeldOutInitialPlanningCases.DATASET_FILENAME
        else -> "initial_planning.jsonl"
    }

    /** Scored cases only; unlabeled benchmark tasks are excluded (see lab/README.md). */
    fun loadLabeled(): List<InitialCase> = loadAll().filter(InitialCase::labeled)

    /** Selected labeled cases, optionally cut to explicit smoke ids and then `SOLIN_LAB_LIMIT`. */
    fun benchCases(): List<InitialCase> = loadSelected().filter(InitialCase::labeled)
        .let { all -> LabEnv.requestedCaseIds?.let { ids -> all.filter { it.id in ids } } ?: all }
        .let { selected -> LabEnv.caseLimit?.let(selected::take) ?: selected }

    /** Shared record shape for Suites B and C, so `summarize.py` reads one schema. */
    fun record(
        suite: String,
        model: String,
        mode: String,
        case: InitialCase,
        answer: LabAnswer,
        rawKind: String,
        rawPreview: String,
        registry: ToolRegistry,
    ): JSONObject {
        val record = JSONObject()
            .put("suite", suite)
            .put("model", model)
            .put("mode", mode)
            .put("caseId", case.id)
            .put("source", case.source)
            .put("group", case.json.optString("group").takeIf(String::isNotBlank) ?: JSONObject.NULL)
            .put("category", case.category ?: JSONObject.NULL)
            .put("input", case.input)
            .put("gold", case.goldJson())
            .put("rawKind", rawKind)
            .put("rawPreview", rawPreview.previewForLab())
            .put("answer", answer.toJson())
            .put("schemaValid", schemaValidity(registry, answer) ?: JSONObject.NULL)
        val score = LabScoring.scoreInitial(case, answer)
        score.keys().forEach { key -> record.put(key, score.get(key)) }
        addFalsePositiveSplit(record, registry)
        return record
    }

    /** `ToolRegistry.validate` on the planned call; null when nothing would execute. */
    fun schemaValidity(registry: ToolRegistry, answer: LabAnswer): Boolean? {
        val tool = answer.tool?.takeUnless { answer.invalid } ?: return null
        return registry.validate(ToolRequest(toolName = tool, arguments = answer.args, reason = "lab")) == null
    }
}

private val PRIVATE_READ_TOOLS = setOf("read_clipboard", "read_current_screen_text", "read_notifications")
private val NETWORK_TOOLS = setOf("web_search", "search_maps", "open_deep_link")

/** One FP classification for rules and model modes, including the ordinary/adversarial held-out split. */
fun addFalsePositiveSplit(record: JSONObject, registry: ToolRegistry) {
    if (!record.getBoolean("falsePositive")) {
        record.put("falsePositiveClass", JSONObject.NULL)
            .put("fpOrchestrationOnly", false)
            .put("fpDeviceNetworkExternal", false)
            .put("fpPrivateRead", false)
            .put("fpNetworkEgress", false)
            .put("privacyRelevant", false)
        return
    }
    val tool = record.getJSONObject("answer").optString("tool")
    val spec = registry.specFor(tool)
    val orchestrationOnly = tool == LOCAL_MODEL_NO_TOOL_CHOICE ||
        spec == null || spec.capability == ToolCapability.Orchestration
    val privateRead = tool in PRIVATE_READ_TOOLS
    val network = tool in NETWORK_TOOLS
    record.put(
        "falsePositiveClass",
        if (orchestrationOnly) "orchestration_only" else "device_network_external",
    )
        .put("fpOrchestrationOnly", orchestrationOnly)
        .put("fpDeviceNetworkExternal", !orchestrationOnly)
        .put("fpPrivateRead", privateRead)
        .put("fpNetworkEgress", network)
        .put("privacyRelevant", privateRead || network)
        .put("fpTool", tool)
}

/**
 * The independent 60-case held-out set. These labels are evaluation-only: never use them to tune
 * prompts, rules, retrieval, or thresholds. Keep this loader separate from [InitialPlanningCases]
 * so the tuned 151-case source remains the default for historical campaign reproduction.
 */
object HeldOutInitialPlanningCases {
    const val DATASET_FILENAME = "initial_planning_heldout.jsonl"
    const val METADATA_FILENAME = "initial_planning_heldout.metadata.json"

    fun loadAll(): List<InitialCase> =
        readJsonl(File(LabEnv.datasetsDir, DATASET_FILENAME)).map(::InitialCase)

    fun loadMetadata(): JSONObject =
        JSONObject(File(LabEnv.datasetsDir, METADATA_FILENAME).readText(Charsets.UTF_8))

    /** Held-out cases, optionally cut to explicit smoke ids and then `SOLIN_LAB_LIMIT`. */
    fun benchCases(): List<InitialCase> = loadAll()
        .let { all -> LabEnv.requestedCaseIds?.let { ids -> all.filter { it.id in ids } } ?: all }
        .let { selected -> LabEnv.caseLimit?.let(selected::take) ?: selected }
}

fun List<String>.toJsonStringArray(): JSONArray = JSONArray().also { array -> forEach(array::put) }
