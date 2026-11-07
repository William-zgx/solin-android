package com.bytedance.zgx.solin.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunOptionsTest {
    @Test
    fun defaultsPlanWithRulesAndNoLocalPlanningModel() {
        val options = AgentRunOptions()

        assertEquals(InitialPlanningMode.RuleFirst, options.initialPlanningMode)
        assertEquals(LocalPlanningStrategy.RuleFirst, options.localPlanningStrategy)
        assertNull(options.localPlanningModelPath)
        assertFalse(options.localModelPlansTools())
    }

    @Test
    fun onlyLocalModelFirstWithAModelStrategyAndAPathLetsTheModelPlan() {
        val planning = AgentRunOptions(
            initialPlanningMode = InitialPlanningMode.LocalModelFirst,
            localPlanningStrategy = LocalPlanningStrategy.ModelFirstWithRuleFastPath,
            localPlanningModelPath = MODEL_PATH,
        )

        assertTrue(planning.localModelPlansTools())
        assertTrue(planning.copy(localPlanningStrategy = LocalPlanningStrategy.ModelFirst).localModelPlansTools())
        assertFalse(planning.copy(localPlanningModelPath = null).localModelPlansTools())
        assertFalse(planning.copy(localPlanningStrategy = LocalPlanningStrategy.RuleFirst).localModelPlansTools())
        assertFalse(planning.copy(initialPlanningMode = InitialPlanningMode.RuleFirst).localModelPlansTools())
        assertFalse(planning.copy(initialPlanningMode = InitialPlanningMode.ModelFirstRemoteTools).localModelPlansTools())
    }

    @Test
    fun parseAcceptsOnlyExactStrategyNames() {
        LocalPlanningStrategy.entries.forEach { strategy ->
            assertEquals(strategy, LocalPlanningStrategy.parse(strategy.name))
        }
        listOf(null, "", " ModelFirst", "modelfirst", "ModelOnly", "LocalModelFirst").forEach { raw ->
            assertNull(raw, LocalPlanningStrategy.parse(raw))
        }
    }

    private companion object {
        const val MODEL_PATH = "/data/models/gemma.litertlm"
    }
}
