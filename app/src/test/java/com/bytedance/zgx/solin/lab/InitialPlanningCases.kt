package com.bytedance.zgx.solin.lab

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

    /** Scored cases only; unlabeled benchmark tasks are excluded (see lab/README.md). */
    fun loadLabeled(): List<InitialCase> = loadAll().filter(InitialCase::labeled)

    /** Labeled cases, cut to `SOLIN_LAB_LIMIT` for smoke runs. */
    fun benchCases(): List<InitialCase> = loadLabeled().let { all -> LabEnv.caseLimit?.let(all::take) ?: all }

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
            .put("category", case.category ?: JSONObject.NULL)
            .put("input", case.input)
            .put("gold", case.goldJson())
            .put("rawKind", rawKind)
            .put("rawPreview", rawPreview.previewForLab())
            .put("answer", answer.toJson())
            .put("schemaValid", schemaValidity(registry, answer) ?: JSONObject.NULL)
        val score = LabScoring.scoreInitial(case, answer)
        score.keys().forEach { key -> record.put(key, score.get(key)) }
        return record
    }

    /** `ToolRegistry.validate` on the planned call; null when nothing would execute. */
    fun schemaValidity(registry: ToolRegistry, answer: LabAnswer): Boolean? {
        val tool = answer.tool?.takeUnless { answer.invalid } ?: return null
        return registry.validate(ToolRequest(toolName = tool, arguments = answer.args, reason = "lab")) == null
    }
}

fun List<String>.toJsonStringArray(): JSONArray = JSONArray().also { array -> forEach(array::put) }
