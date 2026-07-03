package com.bytedance.zgx.solin.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProfileTest {

    @Test
    fun defaultProfileIsGeneralWithProfileIdGeneral() {
        val profile = AgentProfile.DEFAULT
        assertTrue("DEFAULT must be General", profile is AgentProfile.General)
        assertEquals("general", profile.profileId)
    }

    @Test
    fun subagentDepthAtAndUnderMaxIsAllowed() {
        // Depth 1, 2, 3 are all <= MAX_SUBAGENT_DEPTH.
        for (depth in 1..AgentProfile.MAX_SUBAGENT_DEPTH) {
            val sub = AgentProfile.Subagent(
                parentRunId = "run-parent",
                parentToolCallId = "tc-parent",
                depth = depth,
            )
            assertEquals(depth, sub.depth)
            assertTrue("depth $depth must be <= MAX_SUBAGENT_DEPTH", sub.depth <= AgentProfile.MAX_SUBAGENT_DEPTH)
        }
    }

    @Test
    fun effectiveMaxToolStepsMatchesProfileValue() {
        val general = AgentProfile.General()
        val build = AgentProfile.Build()
        val plan = AgentProfile.Plan()
        val sub = AgentProfile.Subagent(parentRunId = "r", parentToolCallId = "t")

        assertEquals(15, general.effectiveMaxToolSteps())
        assertEquals(30, build.effectiveMaxToolSteps())
        assertEquals(3, plan.effectiveMaxToolSteps())
        assertEquals(10, sub.effectiveMaxToolSteps())
    }

    @Test
    fun isSubagentReturnsTrueOnlyForSubagent() {
        assertTrue(AgentProfile.Subagent(parentRunId = "r", parentToolCallId = "t").isSubagent())
        assertFalse(AgentProfile.General().isSubagent())
        assertFalse(AgentProfile.Build().isSubagent())
        assertFalse(AgentProfile.Plan().isSubagent())
        assertFalse(AgentProfile.DEFAULT.isSubagent())
    }
}
