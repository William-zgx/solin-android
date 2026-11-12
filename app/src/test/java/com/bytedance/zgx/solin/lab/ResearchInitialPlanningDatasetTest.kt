package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline contract tests for the independent research-derived initial-planning set. */
class ResearchInitialPlanningDatasetTest {
    private val registry: ToolRegistry = defaultBuiltInToolRegistry

    @Test
    fun metadataPinsDistributionAndEvaluationOnlyContract() {
        val cases = InitialPlanningDatasets.research.loadAll()
        val metadata = InitialPlanningDatasets.research.loadMetadata()

        assertEquals(InitialPlanningDatasets.research.datasetFile, metadata.getString("datasetFile"))
        assertEvaluationOnlyMetadata(cases, metadata)
        val toolFamilies = cases.flatMap { it.goldTools }.toSet()
        assertTrue(toolFamilies.size >= metadata.getInt("minimumToolFamilyCount"))
        val negativeConstraintCases = cases.count {
            it.forbiddenArgs.isNotEmpty() || it.forbiddenNonEmptyArgs.isNotEmpty() || it.forbiddenArgValues.isNotEmpty()
        }
        assertTrue(negativeConstraintCases >= metadata.getInt("minimumNegativeConstraintCases"))
        assertTrue(cases.all { it.source == "research_evaluation_only" && it.labeled })
        assertTrue(cases.all { it.json.getBoolean("heldOut") && it.json.getBoolean("evaluationOnly") })
        assertTrue(cases.all { it.json.getBoolean("tuningProhibited") })
    }

    @Test
    fun idsAndInputsAreUniqueAndDisjointFromExistingSets() {
        assertUniqueAndDisjoint(
            InitialPlanningDatasets.research.loadAll(),
            InitialPlanningCases.loadAll() + HeldOutInitialPlanningCases.loadAll()
        )
    }

    @Test
    fun goldAndNegativeConstraintsUseDeclaredArgumentsWithoutContradictions() {
        val cases = InitialPlanningDatasets.research.loadAll()
        assertInitialPlanningLabelsValid(cases, registry)
        val invalidGold = cases.flatMap { case ->
            case.goldTools.filter { toolName ->
                registry.validate(ToolRequest(toolName = toolName, arguments = case.goldArgs, reason = "dataset")) != null
            }.map { toolName -> "${case.id}: $toolName gold request is registry-invalid" }
        }
        assertTrue(invalidGold.joinToString("\n"), invalidGold.isEmpty())
    }

    @Test
    fun forbiddenNonEmptyArgumentRejectsNonBlankButAllowsBlank() {
        val case = InitialPlanningDatasets.research.loadAll().single { it.id == "research_args_005" }
        val tool = case.goldTools.single()

        val allowed = LabScoring.scoreInitial(case, LabAnswer(tool, case.goldArgs + ("subject" to "")))
        val rejected = LabScoring.scoreInitial(case, LabAnswer(tool, case.goldArgs + ("subject" to "不应出现")))

        assertTrue(allowed.getBoolean("correct"))
        assertTrue(allowed.getJSONObject("constraintResults").getBoolean("subject"))
        assertFalse(rejected.getBoolean("argsExact"))
        assertFalse(rejected.getJSONObject("constraintResults").getBoolean("subject"))
    }

    @Test
    fun forbiddenValueRejectsOnlyTheNamedValue() {
        val case = InitialPlanningDatasets.research.loadAll().single { it.id == "research_bool_005" }
        val tool = case.goldTools.single()
        val allowed = LabScoring.scoreInitial(case, LabAnswer(tool, case.goldArgs + ("requiresCharging" to "false")))
        val rejected = LabScoring.scoreInitial(case, LabAnswer(tool, case.goldArgs + ("requiresCharging" to "true")))

        assertTrue(allowed.getBoolean("correct"))
        assertFalse(rejected.getBoolean("argsExact"))
    }

    @Test
    fun registryAndConstraintValidationRejectImpossibleGold() {
        val invalidSchemaCase = InitialCase(
            org.json.JSONObject(
                """{"id":"invalid-schema","source":"research_evaluation_only","input":"x","labeled":true,
                    |"gold":{"tools":["web_search"],"args":{"query":"x","maxResults":"6"}},
                    |"expectsTool":true}
                """.trimMargin()
            )
        )
        val contradictoryCase = InitialCase(
            org.json.JSONObject(
                """{"id":"contradictory","source":"research_evaluation_only","input":"x","labeled":true,
                    |"group":"adversarial","category":"negation",
                    |"gold":{"tools":["compose_email"],"args":{"body":"nonblank"}},
                    |"expectsTool":true,"forbiddenNonEmptyArgs":["body"]}
                """.trimMargin()
            )
        )

        assertTrue(
            registry.validate(
                ToolRequest(
                    toolName = invalidSchemaCase.goldTools.single(),
                    arguments = invalidSchemaCase.goldArgs,
                    reason = "dataset"
                )
            ) != null
        )
        assertTrue(initialPlanningLabelProblems(listOf(contradictoryCase), registry).isNotEmpty())
    }

    @Test
    fun overlappingConstraintsDoNotOverwriteTheAbsenceFailure() {
        val case = InitialCase(
            org.json.JSONObject(
                """{"id":"overlap","source":"research_evaluation_only","input":"x","labeled":true,
                    |"gold":{"tools":["compose_email"],"args":{"body":"ok"}},
                    |"expectsTool":true,"forbiddenArgs":["subject"],
                    |"forbiddenArgValues":{"subject":"weekly"}}
                """.trimMargin()
            )
        )

        val score = LabScoring.scoreInitial(
            case,
            LabAnswer("compose_email", mapOf("body" to "ok", "subject" to "other"))
        )

        assertFalse(score.getBoolean("argsExact"))
        assertFalse(score.getJSONObject("constraintResults").getBoolean("subject"))
    }

    @Test
    fun adversarialCaseRequiresExpectsTool() {
        val case = InitialCase(
            org.json.JSONObject(
                """{"id":"missing-expects-tool","source":"research_evaluation_only","input":"x",
                    |"labeled":true,"group":"adversarial","category":"negation",
                    |"gold":{"tools":[],"args":{}}}
                """.trimMargin()
            )
        )

        assertThrows(AssertionError::class.java) {
            assertInitialPlanningLabelsValid(listOf(case), registry)
        }
    }

    @Test
    fun malformedNegativeConstraintDeclarationFailsClosed() {
        val case = InitialCase(
            org.json.JSONObject(
                """{"id":"bad-constraint-type","source":"research_evaluation_only","input":"x","labeled":true,
                    |"gold":{"tools":["configure_periodic_check"],"args":{"enabled":"false"}},
                    |"expectsTool":true,"forbiddenArgs":"requiresCharging"}
                """.trimMargin()
            )
        )

        assertThrows(org.json.JSONException::class.java) {
            case.constraints
        }
    }

    @Test
    fun transportErrorIsNotACorrectNoToolAnswer() {
        val case = InitialPlanningDatasets.research.loadAll().single { it.id == "research_no_tool_001" }
        val score = LabScoring.scoreInitial(case, LabAnswer.INVALID, rawKind = "error")

        assertFalse(score.getBoolean("correct"))
        assertFalse(score.getBoolean("toolCorrect"))
    }

    @Test
    fun registryRejectedNoToolCallIsNotAnExecutedFalsePositive() {
        val case = InitialPlanningDatasets.research.loadAll().single { it.id == "research_no_tool_001" }
        val score = LabScoring.scoreInitial(
            case,
            LabAnswer("web_search", mapOf("query" to "x", "maxResults" to "6")),
            schemaValid = false
        )

        assertTrue(score.getBoolean("correct"))
        assertFalse(score.getBoolean("falsePositive"))
        assertTrue(score.getBoolean("toolCorrect"))
    }

    @Test
    fun mutuallyExclusiveReminderIsRejectedByRegistryAndResultLimitIsRejected() {
        val cases = InitialPlanningDatasets.research.loadAll()
        val reminder = cases.single { it.id == "research_args_006" }
        val invalidReminder = InitialPlanningCases.record(
            suite = "initial-planning",
            model = "test",
            mode = "test",
            case = reminder,
            answer = LabAnswer(
                reminder.goldTools.single(),
                reminder.goldArgs + ("triggerAtMillis" to "1780000000000")
            ),
            rawKind = "use_tool",
            rawPreview = "",
            registry = registry
        )
        val search = cases.single { it.id == "research_args_002" }
        val limitedSearch = InitialPlanningCases.record(
            suite = "initial-planning",
            model = "test",
            mode = "test",
            case = search,
            answer = LabAnswer(search.goldTools.single(), search.goldArgs + ("maxResults" to "6")),
            rawKind = "use_tool",
            rawPreview = "",
            registry = registry
        )

        assertFalse(invalidReminder.getBoolean("correct"))
        assertFalse(invalidReminder.getBoolean("schemaValid"))
        assertFalse(limitedSearch.getBoolean("correct"))
        assertFalse(limitedSearch.getBoolean("schemaValid"))
        assertTrue(limitedSearch.getBoolean("toolCorrect"))
        assertFalse(limitedSearch.getBoolean("missedTool"))
    }
}
