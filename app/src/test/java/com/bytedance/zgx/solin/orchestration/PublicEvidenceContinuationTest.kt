package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.evidence.NoOpEvidenceBlobStore
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PublicEvidenceContinuationTest {
    private val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
    private val continuation = PublicEvidenceContinuation(
        toolRegistry = ToolRegistry(),
        traceStore = traceStore,
        traceQueries = AgentTraceQueries(traceStore),
        evidenceBlobStore = NoOpEvidenceBlobStore,
    )

    private fun evidenceJson(query: String, titles: List<String>): String =
        """
            {
              "kind": "web_search_evidence",
              "query": "$query",
              "retrievedAt": "2026-06-13T00:00:00Z",
              "sources": [{"id": "src", "name": "Example", "url": "https://example.com/"}],
              "results": [
                ${titles.joinToString(",") { title ->
            """{"kind":"web_result","sourceId":"src","title":"$title","url":"https://example.com/${title.lowercase()}"}"""
        }}
              ]
            }
        """.trimIndent()

    private fun observeSearch(runId: String, id: String, query: String, titles: List<String>?) {
        val request = ToolRequest(
            id = id,
            toolName = MobileActionFunctions.WEB_SEARCH,
            arguments = mapOf("query" to query),
            reason = "search",
        )
        traceStore.appendStep(
            runId,
            AgentStep.ToolRequested(
                request,
                ActionDraft(MobileActionFunctions.WEB_SEARCH, "搜索", "搜索", request.arguments, requiresConfirmation = false),
            ),
        )
        val data = publicWebSearchResultData(query).let { base ->
            if (titles == null) base else base + ("resultsJson" to evidenceJson(query, titles))
        }
        traceStore.appendStep(
            runId,
            AgentStep.ToolObserved(
                ToolResult(requestId = id, status = ToolStatus.Succeeded, summary = "已搜索 $query", data = data),
            ),
        )
    }

    @Test
    fun renderedPromptAndCitationCheckUseTheSameSourceIds() {
        val run = traceStore.createRun("比较 A 和 B")
        observeSearch(run.id, "call-a", "A", listOf("Alpha", "Apex"))
        // A block without an evidence pack must not consume citation ids.
        observeSearch(run.id, "call-plain", "plain", titles = null)
        observeSearch(run.id, "call-b", "B", listOf("Beta"))

        val numbered = continuation.numberedPublicEvidenceItemsForRun(run.id)
        val prompt = continuation.publicEvidenceContinuationPrompt(
            run = run,
            evidenceBlocks = continuation.priorPublicEvidencePromptBlocks(run.id, excludedRequestIds = emptySet()),
            gapBlocks = emptyList(),
        )

        assertEquals(listOf("S1", "S2", "S3"), numbered.map { item -> item.sourceId })
        assertEquals(listOf("Alpha", "Apex", "Beta"), numbered.map { item -> item.item.title })
        val renderedIds = Regex("""(?m)^\s*\[(S\d+)] (\S+)""").findAll(prompt)
            .map { match -> match.groupValues[1] to match.groupValues[2] }
            .toList()
        assertEquals(numbered.map { item -> item.sourceId to item.item.title }, renderedIds)
        assertFalse(prompt.contains("[S4]"))
    }
}
