package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.MobileActionFunctions

class WebSearchToolExecutor(
    private val provider: WebSearchProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.WEB_SEARCH) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }
        val query = request.arguments["query"].orEmpty().trim()
        if (query.isBlank()) {
            return request.failed(
                code = ToolErrorCode.InvalidRequest,
                summary = "搜索词不能为空",
                retryable = false,
                data = mapOf("toolName" to request.toolName),
            )
        }
        val searchMode = WebSearchMode.fromSchemaValue(request.arguments["searchMode"])
            ?: return request.failed(
                code = ToolErrorCode.InvalidRequest,
                summary = "不支持的搜索模式：${request.arguments["searchMode"]}",
                retryable = false,
                data = mapOf("toolName" to request.toolName),
            )
        val freshnessArgument = request.arguments["freshness"]
        val freshness = WebSearchFreshness.fromSchemaValue(freshnessArgument)
            ?: if (freshnessArgument.isNullOrBlank()) {
                inferWebSearchFreshness(
                    query = query,
                    searchMode = searchMode,
                )
            } else {
                return request.failed(
                    code = ToolErrorCode.InvalidRequest,
                    summary = "不支持的搜索时效：$freshnessArgument",
                    retryable = false,
                    data = mapOf("toolName" to request.toolName),
                )
            }
        val maxResultsArgument = request.arguments["maxResults"]
        val maxResults = maxResultsArgument?.toIntOrNull()
            ?: if (maxResultsArgument.isNullOrBlank()) {
                3
            } else {
                return request.failed(
                    code = ToolErrorCode.InvalidRequest,
                    summary = "搜索结果数量必须是整数：$maxResultsArgument",
                    retryable = false,
                    data = mapOf("toolName" to request.toolName),
                )
            }
        if (maxResults !in 1..5) {
            return request.failed(
                code = ToolErrorCode.InvalidRequest,
                summary = "搜索结果数量必须在 1 到 5 之间：$maxResults",
                retryable = false,
                data = mapOf("toolName" to request.toolName),
            )
        }
        val searchRequest = WebSearchRequest(
            query = query,
            searchMode = searchMode,
            freshness = freshness,
            maxResults = maxResults,
        )
        return when (val result = provider.search(searchRequest)) {
            is WebSearchReadResult.Available ->
                request.succeeded(
                    summary = "已完成 Web 搜索：${result.summaryText}",
                    data = mapOf(
                        "toolName" to request.toolName,
                        "privacy" to MessagePrivacy.RemoteEligible.name,
                        "requiresLocalModel" to false.toString(),
                        "query" to result.query,
                        "source" to result.source,
                        "searchMode" to result.searchMode.schemaValue,
                        "retrievedAt" to result.retrievedAt.toString(),
                        "freshness" to result.freshness.schemaValue,
                        "maxResults" to result.maxResults.toString(),
                        "summaryText" to result.summaryText,
                        "resultsJson" to result.resultsJson,
                    ),
                )

            is WebSearchReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = result.reason,
                    retryable = true,
                    data = mapOf("toolName" to request.toolName),
                )
        }
    }
}
