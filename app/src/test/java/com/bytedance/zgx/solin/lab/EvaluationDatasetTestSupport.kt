package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.tool.ToolRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Shared deterministic contract checks for independently labeled initial-planning datasets. */
internal fun JSONObject.intMap(): Map<String, Int> = keys().asSequence().associateWith { getInt(it) }

internal fun assertEvaluationOnlyMetadata(cases: List<InitialCase>, metadata: JSONObject) {
    assertEquals("HELD_OUT_EVALUATION_ONLY", metadata.getString("status"))
    assertTrue(metadata.getBoolean("heldOut"))
    assertTrue(metadata.getBoolean("evaluationOnly"))
    assertTrue(metadata.getBoolean("tuningProhibited"))
    assertTrue(metadata.getString("warning").contains("DO NOT USE FOR PROMPT, RULE, OR THRESHOLD TUNING"))
    assertEquals(
        setOf("prompt tuning", "rule tuning", "threshold tuning"),
        metadata.getJSONArray("prohibitedUses").stringList().toSet()
    )

    if (!metadata.optBoolean("deriveDistribution")) {
        assertTrue("caseCount is required unless distribution is derived", metadata.has("caseCount"))
        assertTrue("groupCounts is required unless distribution is derived", metadata.has("groupCounts"))
    }
    val recordSource = metadata.optString("recordSource", "heldout_evaluation_only")
    val problems = cases.mapNotNull { case ->
        when {
            case.source != recordSource -> "${case.id}: wrong source"
            !case.json.getBoolean("heldOut") -> "${case.id}: heldOut is false"
            !case.json.getBoolean("evaluationOnly") -> "${case.id}: evaluationOnly is false"
            !case.json.getBoolean("tuningProhibited") -> "${case.id}: tuningProhibited is false"
            !case.labeled -> "${case.id}: case is unlabeled"
            case.input.isBlank() -> "${case.id}: blank input"
            case.category.isNullOrBlank() -> "${case.id}: blank category"
            case.json.optString("group") == "tool" && case.json.getBoolean("expectsTool") -> null
            case.json.optString("group") == "no_tool" && !case.json.getBoolean("expectsTool") -> null
            case.json.optString("group") == "adversarial" && case.json.has("expectsTool") -> null
            case.json.optString("group") == "adversarial" -> "${case.id}: missing expectsTool"
            else -> "${case.id}: group and expectsTool disagree"
        }
    }
    assertTrue(problems.joinToString("\n"), problems.isEmpty())

    val categoryCounts = metadata.getJSONObject("categoryCountsByGroup")
    val derivedCaseCount = categoryCounts.keys().asSequence().sumOf { group ->
        categoryCounts.getJSONObject(group).intMap().values.sum()
    }
    if (metadata.has("caseCount")) assertEquals(metadata.getInt("caseCount"), cases.size)
    assertEquals(derivedCaseCount, cases.size)

    val actualGroups = cases.groupingBy { it.json.getString("group") }.eachCount()
    if (metadata.has("groupCounts")) assertEquals(metadata.getJSONObject("groupCounts").intMap(), actualGroups)
    assertEquals(categoryCounts.keys().asSequence().toSet(), actualGroups.keys)
    categoryCounts.keys().forEach { group ->
        val actual = cases.filter { it.json.getString("group") == group }
            .groupingBy { it.category.orEmpty() }
            .eachCount()
        assertEquals("category counts in $group", categoryCounts.getJSONObject(group).intMap(), actual)
    }
}

internal fun assertUniqueAndDisjoint(cases: List<InitialCase>, existing: List<InitialCase>) {
    assertEquals("duplicate ids within the new dataset", cases.size, cases.map(InitialCase::id).toSet().size)
    val existingIds = existing.mapTo(mutableSetOf(), InitialCase::id)
    assertTrue("ids overlap an existing dataset", cases.none { it.id in existingIds })

    val normalized = cases.map { LabScoring.normalized(it.input) }
    assertEquals("duplicate normalized inputs inside the new dataset", normalized.size, normalized.toSet().size)
    val normalizedExisting = existing.mapTo(mutableSetOf()) { LabScoring.normalized(it.input) }
    val duplicates = cases.filter { LabScoring.normalized(it.input) in normalizedExisting }
    assertTrue(
        "normalized inputs also exist in another dataset: " + duplicates.joinToString { "${it.id}=${it.input}" },
        duplicates.isEmpty()
    )
}

internal fun assertInitialPlanningLabelsValid(cases: List<InitialCase>, registry: ToolRegistry) {
    val problems = initialPlanningLabelProblems(cases, registry)
    assertTrue(problems.joinToString("\n"), problems.isEmpty())
}

internal fun declaredToolArguments(case: InitialCase, registry: ToolRegistry): Set<String> = case.goldTools.mapNotNull(registry::specFor).flatMap { spec ->
    JSONObject(spec.inputSchemaJson).optJSONObject("properties")?.keys()?.asSequence()?.toList().orEmpty()
}.toSet()

internal fun argumentPatternsOverlap(key: String, left: String, right: String): Boolean {
    if (left.startsWith("~") && right.startsWith("~")) return true
    return left.split('|').any { leftAlternative ->
        right.split('|').any { rightAlternative ->
            LabScoring.argValueMatches(key, leftAlternative, rightAlternative) ||
                LabScoring.argValueMatches(key, rightAlternative, leftAlternative)
        }
    }
}

internal fun initialPlanningLabelProblems(cases: List<InitialCase>, registry: ToolRegistry): List<String> {
    val problems = mutableListOf<String>()
    cases.forEach { case ->
        case.goldTools.filterNot(registry::isKnownTool).forEach { problems += "${case.id}: unknown tool $it" }
        val declared = declaredToolArguments(case, registry)
        (case.goldArgs.keys - declared).forEach { problems += "${case.id}: argument $it not in gold tool schemas" }
        (case.forbiddenNonEmptyArgs intersect case.goldArgs.keys).forEach { key ->
            if (case.goldArgs.getValue(key).isNotBlank()) {
                problems += "${case.id}: nonblank gold argument is forbidden when non-empty: $key"
            }
        }
        if (case.goldTools.isEmpty() && case.goldArgs.isNotEmpty()) problems += "${case.id}: args on a no-tool case"
        (case.forbiddenArgs - declared).forEach { problems += "${case.id}: unknown forbidden argument $it" }
        (case.forbiddenNonEmptyArgs - declared).forEach { problems += "${case.id}: unknown non-empty forbidden argument $it" }
        (case.forbiddenArgValues.keys - declared).forEach { problems += "${case.id}: unknown forbidden-value argument $it" }
        (case.forbiddenArgs intersect case.goldArgs.keys).forEach {
            problems += "${case.id}: argument is both required and forbidden: $it"
        }
        case.forbiddenArgValues.forEach { (key, forbidden) ->
            val gold = case.goldArgs[key]
            if (gold != null && argumentPatternsOverlap(key, forbidden, gold)) {
                problems += "${case.id}: gold and forbidden values agree for $key"
            }
        }
        if (case.json.optString("group") == "adversarial" && !case.json.has("expectsTool")) {
            problems += "${case.id}: missing expectsTool"
        }
        if (case.json.has("expectsTool") && case.json.getBoolean("expectsTool") != case.goldTools.isNotEmpty()) {
            problems += "${case.id}: expectsTool does not match gold.tools"
        }
    }
    return problems
}
