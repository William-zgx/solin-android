package com.bytedance.zgx.solin.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val DEFAULT_TOOL_EXECUTION_TIMEOUT_MILLIS = 20_000L
const val DEFAULT_PUBLIC_EVIDENCE_BATCH_RETRY_ATTEMPTS = 1

class TimeoutToolExecutionBoundary(
    private val executor: ToolExecutor,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = DEFAULT_TOOL_EXECUTION_TIMEOUT_MILLIS,
    private val publicEvidenceBatchRetryAttempts: Int =
        DEFAULT_PUBLIC_EVIDENCE_BATCH_RETRY_ATTEMPTS,
    private val publicEvidenceBatchRequestValidator: (ToolRequest) -> ToolResult? = { null },
    private val executionAuthorizer: ToolExecutionAuthorizer? = null,
) {
    suspend fun execute(request: ToolRequest, userConfirmed: Boolean = false): ToolResult {
        executionAuthorizer?.authorize(request, userConfirmed)?.let { return it }
        return executeInternal(request)
    }

    private suspend fun executeInternal(request: ToolRequest): ToolResult =
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

    /**
     * Execute a batch of requests with timeout and retry support. The batch is first
     * validated via [publicEvidenceBatchRequestValidator] — if any request is rejected,
     * NONE are executed and every request receives a rejection result (valid ones get
     * a synthetic "another request was ineligible" rejection). Validated requests are
     * dispatched through [executor.executeBatch] under a supervisorScope so a
     * catastrophic throw escaping the executor (defensive; executors are required to
     * isolate per-tool failures via their own [ToolExecutor.executeBatch] contract)
     * is caught at the boundary and mapped to per-tool failures. The executor is
     * responsible for concurrency/partitioning policy (e.g. running device-control
     * tools sequentially before a concurrent independent segment).
     */
    suspend fun executeBatch(
        requests: List<ToolRequest>,
        userConfirmed: Boolean = false,
        onRetry: suspend () -> Unit = {},
    ): List<ToolResult> {
        if (requests.isEmpty()) return emptyList()

        // Step 1: pre-validate the whole batch. Any rejection short-circuits execution.
        val rejectedByRequestId = requests.mapNotNull { request ->
            (executionAuthorizer?.authorize(request, userConfirmed)
                ?: publicEvidenceBatchRequestValidator(request))
                ?.let { rejection -> request.id to rejection }
        }.toMap()
        if (rejectedByRequestId.isNotEmpty()) {
            return requests.map { request ->
                rejectedByRequestId[request.id] ?: request.rejected(
                    "Public evidence batch rejected before execution because another request was ineligible.",
                )
            }
        }

        // Step 2: run the batch. Per-tool isolation is the executor's responsibility
        // (see ToolExecutor.executeBatch contract); the boundary only guards against
        // catastrophic failure that escapes the executor.
        var results = executeBatchInternal(requests)

        // Step 3: retry retryable failures.
        repeat(publicEvidenceBatchRetryAttempts) {
            val retryRequests = requests.filter { request ->
                resultsByRequestId(results, requests)[request.id]?.let { result ->
                    result.status != ToolStatus.Succeeded && result.retryable
                } == true
            }
            if (retryRequests.isEmpty()) return@repeat
            onRetry()
            val retryRejections = retryRequests.mapNotNull { request ->
                executionAuthorizer?.authorize(request, userConfirmed)?.let { rejection -> request.id to rejection }
            }.toMap()
            val retryResults = if (retryRejections.isEmpty()) {
                executeBatchInternal(retryRequests)
            } else {
                retryRequests.map { request ->
                    retryRejections[request.id] ?: request.rejected(
                        "Tool batch retry rejected before execution because another request was not authorized.",
                    )
                }
            }
            val retryById = resultsByRequestId(retryResults, retryRequests)
            results = requests.map { request ->
                retryById[request.id] ?: resultsByRequestId(results, requests).getValue(request.id)
            }
        }

        return results
    }

    private suspend fun executeBatchInternal(requests: List<ToolRequest>): List<ToolResult> =
        withTimeoutOrNull(timeoutMillis) {
            supervisorScope {
                runCatching {
                    withContext(dispatcher) { executor.executeBatch(requests) }
                }.getOrElse { throwable ->
                    if (throwable is CancellationException) {
                        if (!currentCoroutineContext().isActive) throw throwable
                        requests.map { request ->
                            request.cancelled(
                                summary = "Tool batch was cancelled before completion: ${throwable.cleanMessage()}",
                                data = request.toolExecutionContext(),
                            )
                        }
                    } else {
                        requests.map { request ->
                            request.failed(
                                code = ToolErrorCode.ExecutionFailed,
                                summary = "Tool batch failed before completion: ${throwable.cleanMessage()}",
                                retryable = true,
                                data = request.toolExecutionContext(),
                            )
                        }
                    }
                }
            }
        } ?: requests.map { request ->
            request.failed(
                code = ToolErrorCode.ExecutionFailed,
                summary = "Tool batch execution timed out after ${timeoutMillis / 1_000} seconds.",
                retryable = true,
                data = request.toolExecutionContext(),
            )
        }

    /**
     * Backwards-compatible public-evidence batch API. Delegates to [executeBatch].
     */
    suspend fun executePublicEvidenceBatch(
        requests: List<ToolRequest>,
        userConfirmed: Boolean = false,
        onRetry: suspend () -> Unit = {},
    ): List<ToolResult> = executeBatch(requests, userConfirmed, onRetry)
}

private fun resultsByRequestId(
    results: List<ToolResult>,
    requests: List<ToolRequest>,
): Map<String, ToolResult> {
    if (results.size == requests.size && results.indices.all { results[it].requestId == requests[it].id }) {
        return requests.zip(results).associate { (req, res) -> req.id to res }
    }
    return results.associateBy { it.requestId }
}

private fun ToolRequest.toolExecutionContext(): Map<String, String> =
    mapOf("toolName" to toolName)

private fun Throwable.cleanMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
