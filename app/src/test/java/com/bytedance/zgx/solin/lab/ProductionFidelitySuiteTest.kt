package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.action.startsWithActionNegation
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test

/**
 * Production-faithful negation guard, offline over every Suite B/C LiteRT mode and model.
 *
 * Production LocalModelFirst never shows a negated request to the model:
 * `orchestration/InitialToolPlanner.kt:100` calls `input.startsWithActionNegation()`
 * (`action/BasicDraftActionParsers.kt:199`) and takes `planRuleFirst()`. So the guarded composite
 * scores a guarded case with the rules' answer (mode `R`) and every other case with the mode's own
 * answer. No model is called here: it reads the JSONL the model runs already wrote.
 *
 * Only answered calls are scored; an errored call produced no answer and must not be counted.
 */
class ProductionFidelitySuiteTest {
    private val registry: ToolRegistry = defaultBuiltInToolRegistry

    /** Private reads whose output is screen/user data; naming one leaks nothing but is worth a flag. */
    private val privateReadTools = setOf("read_clipboard", "read_current_screen_text", "read_notifications")

    /** Tools that egress to the network. */
    private val networkTools = setOf("web_search", "search_maps", "open_deep_link")

    @Test
    fun negationGuardComposite() {
        requireLabOptIn()
        val modes = setOf("M-topk", "M-topk-fix", "M-topk-fix-lmf", "N-topk", "N-topk-lmf")
        val rules = rulesByCase()
        Assume.assumeTrue("no `R` records yet; run Suite B first", rules.isNotEmpty())
        val guarded = rules.values.filter { it.getString("input").startsWithActionNegation() }
        // The production guard is only sound if the rule path it falls back to never acts on a
        // negated request: `R` must plan no tool for every guarded case.
        val actingOnGuarded = guarded.filter { it.getString("rawKind") == "use_tool" }
        check(actingOnGuarded.isEmpty()) {
            "rules plan a tool on negated requests: ${actingOnGuarded.map { it.getString("caseId") }}"
        }
        val toolGoldGuarded = guarded.count { it.getBoolean("expectsTool") }

        val rows = mutableListOf<JSONObject>()
        for (file in LabEnv.resultsDir.listFiles().orEmpty().sortedBy { it.name }) {
            if (!file.name.endsWith(".jsonl") || "litert" !in file.name) continue
            val records = runCatching { readJsonl(file) }.getOrNull().orEmpty()
            if (records.isEmpty()) continue
            val first = records.first()
            val suite = first.optString("suite")
            if (suite != "initial-planning" && suite != "native-tools") continue
            val mode = first.optString("mode")
            if (mode !in modes) continue
            var rawOverall = 0
            var guardedOverall = 0
            var rawFp = 0
            var guardedFp = 0
            var fired = 0
            var answered = 0
            var errored = 0
            var fpOrchestration = 0
            var fpDevice = 0
            var fpPrivateRead = 0
            var fpNetwork = 0
            for (record in records) {
                if (record.isErrored()) {
                    errored++
                    continue
                }
                val rule = rules[record.getString("caseId")] ?: continue
                answered++
                val isGuarded = rule.getString("input").startsWithActionNegation()
                if (isGuarded) fired++
                val rawCorrect = record.getBoolean("correct")
                val guardedCorrect = if (isGuarded) rule.getBoolean("correct") else rawCorrect
                if (rawCorrect) rawOverall++
                if (guardedCorrect) guardedOverall++
                if (record.getBoolean("falsePositive")) {
                    rawFp++
                    // A guarded case takes R's no-tool plan, so it can no longer be a false positive.
                    if (!isGuarded) guardedFp++
                    val tool = record.optJSONObject("answer")?.optString("tool").orEmpty()
                    val spec = registry.specFor(tool)
                    if (tool == LOCAL_MODEL_NO_TOOL_CHOICE ||
                        spec?.capability == ToolCapability.Orchestration ||
                        spec == null
                    ) {
                        // A hallucinated name is not an orchestration choice, but it is also not a
                        // device action; count it with the orchestration group and name it in prose.
                        fpOrchestration++
                    } else {
                        fpDevice++
                    }
                    if (tool in privateReadTools) fpPrivateRead++
                    if (tool in networkTools) fpNetwork++
                }
            }
            rows.add(
                JSONObject()
                    .put("suite", suite)
                    .put("model", first.optString("model"))
                    .put("mode", mode)
                    .put("answered", answered)
                    .put("errored", errored)
                    .put("guardedCases", fired)
                    .put("guardedDatasetCases", guarded.size)
                    .put("rawCorrect", rawOverall)
                    .put("guardedCorrect", guardedOverall)
                    .put("rawFalsePositive", rawFp)
                    .put("guardedFalsePositive", guardedFp)
                    .put("fpOrchestrationOnly", fpOrchestration)
                    .put("fpDeviceNetworkExternal", fpDevice)
                    .put("fpPrivateRead", fpPrivateRead)
                    .put("fpNetworkEgress", fpNetwork)
            )
        }
        val file = File(LabEnv.resultsDir, "production-fidelity__negation-guard.jsonl")
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            rows.forEach { row ->
                writer.write(row.toString())
                writer.newLine()
            }
        }
        LabLog.info(
            "production-fidelity negation guard: guarded=${guarded.size} toolGoldGuarded=$toolGoldGuarded " +
                "rows=${rows.size} -> ${file.name}"
        )
        check(toolGoldGuarded == 0) { "negation guard fires on $toolGoldGuarded tool-gold cases" }
    }

    private fun JSONObject.isErrored(): Boolean {
        if (optString("rawKind") == "error") return true
        val flat = optJSONObject("metrics")?.optString("error").orEmpty()
        val nested = optJSONArray("modelOutputs")?.let { outputs ->
            (0 until outputs.length()).any { outputs.getJSONObject(it).optJSONObject("metrics")?.optString("error").orEmpty().isNotEmpty() }
        } ?: false
        return flat.isNotEmpty() || nested
    }

    private fun rulesByCase(): Map<String, JSONObject> = runCatching { readJsonl(File(LabEnv.resultsDir, "initial-planning__rules__R.jsonl")) }
        .getOrDefault(emptyList())
        .associateBy { it.getString("caseId") }
}
