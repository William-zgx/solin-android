package com.bytedance.zgx.pocketmind.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val DEFAULT_TOOL_EXECUTION_TIMEOUT_MILLIS = 20_000L
const val DEFAULT_PUBLIC_EVIDENCE_BATCH_RETRY_ATTEMPTS = 1

interface ToolExecutionBoundary {
    suspend fun execute(request: ToolRequest): ToolResult

    suspend fun executePublicEvidenceBatch(
        requests: List<ToolRequest>,
        onRetry: suspend () -> Unit = {},
    ): List<ToolResult>
}

class TimeoutToolExecutionBoundary(
    private val executor: ToolExecutor,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = DEFAULT_TOOL_EXECUTION_TIMEOUT_MILLIS,
    private val publicEvidenceBatchRetryAttempts: Int =
        DEFAULT_PUBLIC_EVIDENCE_BATCH_RETRY_ATTEMPTS,
    private val publicEvidenceBatchRequestValidator: (ToolRequest) -> ToolResult? = { null },
) : ToolExecutionBoundary {
    override suspend fun execute(request: ToolRequest): ToolResult =
        withTimeoutOrNull(timeoutMillis) {
            withContext(dispatcher) {
                runCatching {
                    executor.execute(request)
                }.getOrElse { throwable ->
                    if (throwable is CancellationException) throw throwable
                    request.failed(
                        code = ToolErrorCode.ExecutionFailed,
                        summary = "Tool execution failed before completion: ${throwable.cleanMessage()}",
                        retryable = true,
                        data = request.toolExecutionContext(),
                    )
                }
            }
        } ?: request.failed(
            code = ToolErrorCode.ExecutionFailed,
            summary = "Tool execution timed out after ${timeoutMillis / 1_000} seconds.",
            retryable = true,
            data = request.toolExecutionContext(),
        )

    override suspend fun executePublicEvidenceBatch(
        requests: List<ToolRequest>,
        onRetry: suspend () -> Unit,
    ): List<ToolResult> {
        val rejectedByRequestId = requests.mapNotNull { request ->
            publicEvidenceBatchRequestValidator(request)?.let { rejection -> request.id to rejection }
        }.toMap()
        if (rejectedByRequestId.isNotEmpty()) {
            return requests.map { request ->
                rejectedByRequestId[request.id] ?: request.rejected(
                    "Public evidence batch rejected before execution because another request was ineligible.",
                )
            }
        }
        var results = executeAll(requests)
        repeat(publicEvidenceBatchRetryAttempts) {
            val retryRequests = requests.retryableFailures(results)
            if (retryRequests.isEmpty()) return results
            onRetry()
            val retryResultsByRequestId = executeAll(retryRequests)
                .associateBy { result -> result.requestId }
            results = results.map { result ->
                retryResultsByRequestId[result.requestId] ?: result
            }
        }
        return results
    }

    private suspend fun executeAll(requests: List<ToolRequest>): List<ToolResult> =
        supervisorScope {
            requests.map { request ->
                async {
                    executeForBatch(request)
                }
            }.awaitAll()
        }

    private suspend fun executeForBatch(request: ToolRequest): ToolResult =
        try {
            execute(request)
        } catch (cancellation: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancellation
            request.cancelled(
                summary = "Tool execution was cancelled before completion: ${cancellation.cleanMessage()}",
                data = request.toolExecutionContext(),
            )
        }
}

private fun List<ToolRequest>.retryableFailures(results: List<ToolResult>): List<ToolRequest> {
    val resultsByRequestId = results.associateBy { result -> result.requestId }
    return filter { request ->
        resultsByRequestId[request.id]?.let { result ->
            result.status != ToolStatus.Succeeded && result.retryable
        } == true
    }
}

private fun ToolRequest.toolExecutionContext(): Map<String, String> =
    mapOf("toolName" to toolName)

private fun Throwable.cleanMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
