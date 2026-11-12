package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Negative argument constraints embedded in the saved gold record. */
data class ArgumentConstraints(
    val forbiddenArgs: Set<String> = emptySet(),
    val forbiddenNonEmptyArgs: Set<String> = emptySet(),
    val forbiddenArgValues: Map<String, String> = emptyMap()
) {
    fun isEmpty(): Boolean = forbiddenArgs.isEmpty() && forbiddenNonEmptyArgs.isEmpty() && forbiddenArgValues.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("forbiddenArgs", forbiddenArgs.sorted().toJsonStringArray())
        .put("forbiddenNonEmptyArgs", forbiddenNonEmptyArgs.sorted().toJsonStringArray())
        .put("forbiddenArgValues", forbiddenArgValues.toJson())
}

/** One Suites B/C case from a registered initial-planning dataset. */
data class InitialCase(val json: JSONObject) {
    val id: String get() = json.getString("id")
    val source: String get() = json.getString("source")
    val input: String get() = json.getString("input")
    val labeled: Boolean get() = json.optBoolean("labeled")
    val category: String? get() = json.optString("category").takeIf(String::isNotBlank)
    val goldTools: List<String> get() = json.optJSONObject("gold")?.optJSONArray("tools").stringList()
    val goldArgs: Map<String, String> get() = json.optJSONObject("gold")?.optJSONObject("args").stringMap()
    val constraints: ArgumentConstraints by lazy {
        ArgumentConstraints(
            forbiddenArgs = if (json.has("forbiddenArgs")) json.getJSONArray("forbiddenArgs").stringList().toSet() else emptySet(),
            forbiddenNonEmptyArgs = if (json.has("forbiddenNonEmptyArgs")) json.getJSONArray("forbiddenNonEmptyArgs").stringList().toSet() else emptySet(),
            forbiddenArgValues = scalarForbiddenArgValues(json)
        )
    }
    val forbiddenArgs: Set<String> get() = constraints.forbiddenArgs
    val forbiddenNonEmptyArgs: Set<String> get() = constraints.forbiddenNonEmptyArgs
    val forbiddenArgValues: Map<String, String> get() = constraints.forbiddenArgValues

    fun goldJson(): JSONObject {
        val gold = JSONObject().put("tools", goldTools.toJsonStringArray()).put("args", goldArgs.toJson())
        if (!constraints.isEmpty()) gold.put("constraints", constraints.toJson())
        return gold
    }
}

private fun scalarForbiddenArgValues(json: JSONObject): Map<String, String> {
    if (!json.has("forbiddenArgValues")) return emptyMap()
    val raw = json.getJSONObject("forbiddenArgValues")
    raw.keys().forEach { key ->
        val value = raw.get(key)
        require(value is String || value is Number || value is Boolean) {
            "forbiddenArgValues.$key must be a string, number or boolean"
        }
    }
    return raw.stringMap()
}

/** Descriptor shared by selection, loading and manifest metadata for one initial-planning dataset. */
data class InitialPlanningDataset(
    val source: String,
    val datasetFile: String,
    val metadataFile: String? = null
) {
    fun loadAll(): List<InitialCase> = readJsonl(File(LabEnv.datasetsDir, datasetFile)).map(::InitialCase)

    fun loadMetadata(): JSONObject {
        val filename = requireNotNull(metadataFile) { "Dataset $source has no metadata sidecar" }
        return JSONObject(File(LabEnv.datasetsDir, filename).readText(Charsets.UTF_8))
    }

    fun benchCases(): List<InitialCase> = loadAll().filter(InitialCase::labeled)
        .let { all -> LabEnv.requestedCaseIds?.let { ids -> all.filter { it.id in ids } } ?: all }
        .let { selected -> LabEnv.caseLimit?.let(selected::take) ?: selected }
}

object InitialPlanningDatasets {
    val tuning = InitialPlanningDataset("tuning", "initial_planning.jsonl")
    val heldout = InitialPlanningDataset("heldout", "initial_planning_heldout.jsonl", "initial_planning_heldout.metadata.json")
    val research = InitialPlanningDataset("research", "initial_planning_research.jsonl", "initial_planning_research.metadata.json")
    private val descriptors = listOf(tuning, heldout, research)
    val sources: Set<String> = descriptors.mapTo(linkedSetOf(), InitialPlanningDataset::source)

    fun selected(): InitialPlanningDataset = descriptors.firstOrNull { it.source == LabEnv.initialPlanningCaseSource } ?: tuning
}

object InitialPlanningCases {
    fun loadAll(): List<InitialCase> = InitialPlanningDatasets.tuning.loadAll()

    /** The tuned source stays the default; evaluation sources require an explicit environment switch. */
    fun loadSelected(): List<InitialCase> = InitialPlanningDatasets.selected().loadAll()

    fun selectedDatasetFilename(): String = InitialPlanningDatasets.selected().datasetFile

    /** Scored cases only; unlabeled benchmark tasks are excluded (see lab/README.md). */
    fun loadLabeled(): List<InitialCase> = loadAll().filter(InitialCase::labeled)

    /** Selected labeled cases, optionally cut to explicit smoke ids and then `SOLIN_LAB_LIMIT`. */
    fun benchCases(): List<InitialCase> = InitialPlanningDatasets.selected().benchCases()

    /** Shared record shape for Suites B and C, so `summarize.py` reads one schema. */
    fun record(
        suite: String,
        model: String,
        mode: String,
        case: InitialCase,
        answer: LabAnswer,
        rawKind: String,
        rawPreview: String,
        registry: ToolRegistry
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
        val schemaValid = schemaValidity(registry, answer)
        record.put("schemaValid", schemaValid ?: JSONObject.NULL)
        val score = LabScoring.scoreInitial(case, answer, schemaValid, rawKind)
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
            .put("fpTool", JSONObject.NULL)
        return
    }
    val tool = record.getJSONObject("answer").optString("tool")
    val spec = registry.specFor(tool)
    val orchestrationOnly = tool == LOCAL_MODEL_NO_TOOL_CHOICE ||
        spec == null ||
        spec.capability == ToolCapability.Orchestration
    val privateRead = tool in PRIVATE_READ_TOOLS
    val network = tool in NETWORK_TOOLS
    record.put(
        "falsePositiveClass",
        if (orchestrationOnly) "orchestration_only" else "device_network_external"
    )
        .put("fpOrchestrationOnly", orchestrationOnly)
        .put("fpDeviceNetworkExternal", !orchestrationOnly)
        .put("fpPrivateRead", privateRead)
        .put("fpNetworkEgress", network)
        .put("privacyRelevant", privateRead || network)
        .put("fpTool", tool)
}

/** Backward-compatible accessors for the original independent 60-case held-out descriptor. */
object HeldOutInitialPlanningCases {
    val DATASET_FILENAME = InitialPlanningDatasets.heldout.datasetFile
    val METADATA_FILENAME: String = requireNotNull(InitialPlanningDatasets.heldout.metadataFile)

    fun loadAll(): List<InitialCase> = InitialPlanningDatasets.heldout.loadAll()

    fun loadMetadata(): JSONObject = InitialPlanningDatasets.heldout.loadMetadata()

    /** Held-out cases, optionally cut to explicit smoke ids and then `SOLIN_LAB_LIMIT`. */
    fun benchCases(): List<InitialCase> = InitialPlanningDatasets.heldout.benchCases()
}

fun List<String>.toJsonStringArray(): JSONArray = JSONArray().also { array -> forEach(array::put) }
