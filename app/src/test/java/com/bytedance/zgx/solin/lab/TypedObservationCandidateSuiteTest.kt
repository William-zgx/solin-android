package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.screenObservationFromJsonStringOrNull
import com.bytedance.zgx.solin.device.toJsonObject
import com.bytedance.zgx.solin.orchestration.MODEL_OBSERVATION_REPLAN_REQUEST_REASON
import com.bytedance.zgx.solin.orchestration.TypedObservationCandidateReplanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedObservationCandidateSuiteTest {
    private val enabled = TypedObservationCandidateReplanner(enabled = true)

    @Test
    fun everyEmittedActionMatchesGoldAndUsesNonModelProvenance() {
        var emissions = 0
        ObservationStepCases.load().forEach { case ->
            val built = ObservationStepCases.build(case)
            val replan = enabled.planNext(built.context) ?: return@forEach
            emissions++
            assertFalse(case.id, replan.plannedByModel)
            assertNotEquals(case.id, MODEL_OBSERVATION_REPLAN_REQUEST_REASON, replan.request.reason)
            assertEquals(case.id, replan.request.toolName, replan.draft.functionName)
            val answer = LabAnswer(replan.request.toolName, replan.request.arguments)
            assertTrue(
                "${case.id}: ${replan.request.toolName} ${replan.request.arguments}",
                LabScoring.observationAnswerMatches(answer, case.accepts, built.nodeLabels)
            )
        }
        assertTrue("typed planner must cover at least one labeled case", emissions > 0)
        println("typed-observation gold sweep: $emissions emitted actions, all matched")
    }

    @Test
    fun disabledPlannerAlwaysFallsThrough() {
        val context = ObservationStepCases.build(ObservationStepCases.load().first()).context

        assertNull(TypedObservationCandidateReplanner(enabled = false).planNext(context))
    }

    @Test
    fun duplicateRealCandidatesAreAmbiguous() {
        val candidate = firstEmission(MobileActionFunctions.UI_TYPE_TEXT)
        val observation = candidate.context.observedResult.data["afterScreenObservationJson"]
            ?.let(::screenObservationFromJsonStringOrNull)
            ?: candidate.context.observedResult.data["screenObservationJson"]
                ?.let(::screenObservationFromJsonStringOrNull)
        requireNotNull(observation)
        val duplicate = observation.elements.first { it.clickability.editable }.copy(id = "duplicate-editable")
        val duplicated = observation.copy(elements = observation.elements + duplicate)
        val data = candidate.context.observedResult.data + mapOf(
            "afterScreenObservationJson" to duplicated.toJsonObject().toString(),
            "screenObservationJson" to duplicated.toJsonObject().toString()
        )

        assertNull(enabled.planNext(candidate.context.copy(observedResult = candidate.context.observedResult.copy(data = data))))
    }

    @Test
    fun submitRequiresTheExactVerifiedQuery() {
        val candidate = firstEmission(MobileActionFunctions.UI_SUBMIT_SEARCH)
        val previous = candidate.context.previousRequest
        val mismatched = previous.copy(arguments = previous.arguments + ("text" to "another query"))

        assertNull(enabled.planNext(candidate.context.copy(previousRequest = mismatched)))
    }

    private data class Emission(val context: com.bytedance.zgx.solin.orchestration.AgentObservationReplanContext)

    private fun firstEmission(toolName: String): Emission {
        ObservationStepCases.load().forEach { case ->
            val context = ObservationStepCases.build(case).context
            val replan = enabled.planNext(context)
            if (replan?.request?.toolName == toolName) return Emission(context)
        }
        throw AssertionError("no typed emission for $toolName")
    }
}
