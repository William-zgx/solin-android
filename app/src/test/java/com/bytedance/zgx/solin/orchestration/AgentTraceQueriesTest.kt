package com.bytedance.zgx.solin.orchestration

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTraceQueriesTest {
    @Test
    fun persistedSkillSummaryGroupsExplicitToolRequestIds() {
        val count = plannedSegmentCount(
            summary(
                position = 0,
                type = "SkillPlanned",
                json = """{"skillRequestId":"skill-1","toolRequestIds":["tool-1","tool-2"]}"""
            ),
            toolRequested(position = 1, requestId = "tool-1"),
            toolRequested(position = 2, requestId = "tool-2"),
            toolRequested(position = 3, requestId = "tool-3")
        )

        assertEquals(2, count)
    }

    @Test
    fun persistedSkillSummaryPrefersToolStepCountOverLegacyStepCount() {
        val count = plannedSegmentCount(
            summary(
                position = 0,
                type = "SkillPlanned",
                json = """{"skillRequestId":"skill-1","toolStepCount":1,"stepCount":3}"""
            ),
            toolRequested(position = 1, requestId = "tool-1"),
            toolRequested(position = 2, requestId = "tool-2"),
            toolRequested(position = 3, requestId = "tool-3")
        )

        assertEquals(3, count)
    }

    @Test
    fun persistedSkillSummaryFallsBackToLegacyStepCount() {
        val count = plannedSegmentCount(
            summary(
                position = 0,
                type = "SkillPlanned",
                json = """{"skillRequestId":"skill-1","stepCount":2}"""
            ),
            toolRequested(position = 1, requestId = "tool-1"),
            toolRequested(position = 2, requestId = "tool-2"),
            toolRequested(position = 3, requestId = "tool-3")
        )

        assertEquals(2, count)
    }

    @Test
    fun malformedPersistedSkillSummaryLeavesToolsInIndependentSegments() {
        val count = plannedSegmentCount(
            summary(position = 0, type = "SkillPlanned", json = "{malformed"),
            toolRequested(position = 1, requestId = "tool-1"),
            toolRequested(position = 2, requestId = "tool-2")
        )

        assertEquals(2, count)
    }

    private fun plannedSegmentCount(vararg summaries: AgentTraceStepSummary): Int {
        val store = object : AgentTraceStore by InMemoryAgentTraceStore() {
            override fun steps(runId: String): List<AgentStep> = emptyList()
            override fun stepSummaries(runId: String): List<AgentTraceStepSummary> = summaries.toList()
        }
        return AgentTraceQueries(store).plannedSequentialSegmentCount(RUN_ID)
    }

    private fun toolRequested(position: Int, requestId: String): AgentTraceStepSummary = summary(position = position, type = "ToolRequested", json = """{"requestId":"$requestId"}""")

    private fun summary(position: Int, type: String, json: String): AgentTraceStepSummary = AgentTraceStepSummary(
        runId = RUN_ID,
        position = position,
        type = type,
        summary = type,
        json = json,
        createdAtMillis = 1L
    )

    private companion object {
        const val RUN_ID = "run-trace-queries"
    }
}
