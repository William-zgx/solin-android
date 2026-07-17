package com.bytedance.zgx.solin.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionBoundaryTest {
    @Test
    fun executeRejectsUnknownToolBeforeExecutor() = runTest {
        var calls = 0
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor { request ->
                calls += 1
                request.succeeded("unexpected")
            },
            dispatcher = Dispatchers.Unconfined,
            executionAuthorizer = SafetyPolicyToolExecutionAuthorizer(ToolRegistry(emptyList())),
        )

        val result = boundary.execute(ToolRequest(id = "unknown", toolName = "unknown"))

        assertEquals(0, calls)
        assertEquals(ToolStatus.Rejected, result.status)
    }

    @Test
    fun executeRejectsMediumRiskToolWithoutRequiredConfirmationPolicy() = runTest {
        var calls = 0
        val registry = ToolRegistry(
            ToolProvider {
                listOf(
                    ToolSpec(
                        name = "medium",
                        title = "medium",
                        description = "medium",
                        inputSchemaJson = """{"type":"object","additionalProperties":false}""",
                        capability = ToolCapability.Extension,
                        riskLevel = RiskLevel.MediumDraftOrNavigation,
                        confirmationPolicy = ConfirmationPolicy.NotRequired,
                    ),
                )
            },
        )
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor {
                calls += 1
                it.succeeded("unexpected")
            },
            dispatcher = Dispatchers.Unconfined,
            executionAuthorizer = SafetyPolicyToolExecutionAuthorizer(registry),
        )

        val result = boundary.execute(ToolRequest(id = "medium", toolName = "medium"), userConfirmed = true)

        assertEquals(0, calls)
        assertEquals(ToolStatus.Rejected, result.status)
    }

    @Test
    fun executeRequiresCurrentConfirmationAtFinalBoundary() = runTest {
        val request = ToolRequest(id = "required", toolName = "required")
        var calls = 0
        val registry = ToolRegistry(
            ToolProvider {
                listOf(
                    ToolSpec(
                        name = "required",
                        title = "required",
                        description = "required",
                        inputSchemaJson = """{"type":"object","additionalProperties":false}""",
                        capability = ToolCapability.Extension,
                        confirmationPolicy = ConfirmationPolicy.Required,
                    ),
                )
            },
        )
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor {
                calls += 1
                it.succeeded("ok")
            },
            dispatcher = Dispatchers.Unconfined,
            executionAuthorizer = SafetyPolicyToolExecutionAuthorizer(registry),
        )

        assertEquals(ToolStatus.Rejected, boundary.execute(request).status)
        assertEquals(ToolStatus.Succeeded, boundary.execute(request, userConfirmed = true).status)
        assertEquals(1, calls)
    }

    @Test
    fun batchRetryIsReauthorizedBeforeSecondExecution() = runTest {
        val request = ToolRequest(id = "retry", toolName = "retry")
        var authorizationCalls = 0
        var executionCalls = 0
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor {
                executionCalls += 1
                it.failed(ToolErrorCode.ExecutionFailed, "retry", retryable = true)
            },
            dispatcher = Dispatchers.Unconfined,
            executionAuthorizer = ToolExecutionAuthorizer { authorizedRequest, _ ->
                authorizationCalls += 1
                if (authorizationCalls > 1) authorizedRequest.rejected("authorization changed") else null
            },
        )

        val result = boundary.executeBatch(listOf(request)).single()

        assertEquals(2, authorizationCalls)
        assertEquals(1, executionCalls)
        assertEquals(ToolStatus.Rejected, result.status)
    }

    @Test
    fun batchAuthorizationRejectsAllRequestsBeforeExecution() = runTest {
        val allowed = ToolRequest(id = "allowed", toolName = "allowed")
        val blocked = ToolRequest(id = "blocked", toolName = "blocked")
        var calls = 0
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor {
                calls += 1
                it.succeeded("unexpected")
            },
            dispatcher = Dispatchers.Unconfined,
            executionAuthorizer = ToolExecutionAuthorizer { authorizedRequest, _ ->
                if (authorizedRequest.id == "blocked") authorizedRequest.rejected("blocked") else null
            },
        )

        val results = boundary.executeBatch(listOf(allowed, blocked))

        assertEquals(0, calls)
        assertEquals(listOf(ToolStatus.Rejected, ToolStatus.Rejected), results.map { it.status })
    }

    @Test
    fun executeMapsExecutorExceptionsToRetryableFailure() = runTest {
        val request = ToolRequest(id = "request-1", toolName = "test_tool")
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor {
                throw IllegalStateException("boom")
            },
            dispatcher = Dispatchers.Unconfined,
        )

        val result = boundary.execute(request)

        assertEquals("request-1", result.requestId)
        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertTrue(result.retryable)
        assertEquals("test_tool", result.data["toolName"])
        assertEquals("Tool execution failed before completion: boom", result.summary)
    }

    @Test
    fun executePropagatesCancellationException() = runTest {
        val request = ToolRequest(id = "request-1", toolName = "test_tool")
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor {
                throw CancellationException("stop")
            },
            dispatcher = Dispatchers.Unconfined,
        )

        var cancellationThrown = false
        try {
            boundary.execute(request)
        } catch (throwable: CancellationException) {
            cancellationThrown = true
            assertEquals("stop", throwable.message)
        }

        assertTrue(cancellationThrown)
    }

    @Test
    fun executeReturnsTimeoutFailureWhenTimeoutElapsesBeforeExecution() = runTest {
        var callCount = 0
        val request = ToolRequest(id = "request-1", toolName = "test_tool")
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor {
                callCount += 1
                request.succeeded("unexpected")
            },
            dispatcher = Dispatchers.Unconfined,
            timeoutMillis = 0L,
        )

        val result = boundary.execute(request)

        assertEquals(0, callCount)
        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertTrue(result.retryable)
        assertEquals("test_tool", result.data["toolName"])
        assertEquals("Tool execution timed out after 0 seconds.", result.summary)
    }

    @Test
    fun publicEvidenceBatchRetriesOnlyRetryableFailuresAndPreservesOrder() = runTest {
        val first = ToolRequest(id = "first", toolName = "first_tool")
        val retryable = ToolRequest(id = "retryable", toolName = "retryable_tool")
        val stableFailure = ToolRequest(id = "stable-failure", toolName = "stable_failure_tool")
        val callsByRequestId = mutableMapOf<String, Int>()
        var retryCallbackCount = 0
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor { request ->
                val callCount = callsByRequestId.getOrDefault(request.id, 0) + 1
                callsByRequestId[request.id] = callCount
                when (request.id) {
                    "first" -> request.succeeded("first success")
                    "retryable" -> if (callCount == 1) {
                        request.failed(
                            code = ToolErrorCode.ExecutionFailed,
                            summary = "temporary failure",
                            retryable = true,
                        )
                    } else {
                        request.succeeded("retry success")
                    }
                    "stable-failure" -> request.failed(
                        code = ToolErrorCode.ExecutionFailed,
                        summary = "final failure",
                        retryable = false,
                    )
                    else -> error("Unexpected request ${request.id}")
                }
            },
            dispatcher = Dispatchers.Unconfined,
            publicEvidenceBatchRetryAttempts = 1,
        )

        val results = boundary.executePublicEvidenceBatch(
            requests = listOf(first, retryable, stableFailure),
        ) {
            retryCallbackCount += 1
        }

        assertEquals(listOf("first", "retryable", "stable-failure"), results.map { it.requestId })
        assertEquals(listOf(ToolStatus.Succeeded, ToolStatus.Succeeded, ToolStatus.Failed), results.map { it.status })
        assertEquals("retry success", results[1].summary)
        assertFalse(results[2].retryable)
        assertEquals(1, callsByRequestId["first"])
        assertEquals(2, callsByRequestId["retryable"])
        assertEquals(1, callsByRequestId["stable-failure"])
        assertEquals(1, retryCallbackCount)
    }

    @Test
    fun publicEvidenceBatchDoesNotRetryWhenNothingIsRetryable() = runTest {
        val request = ToolRequest(id = "request-1", toolName = "test_tool")
        var callCount = 0
        var retryCallbackCount = 0
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor {
                callCount += 1
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "final failure",
                    retryable = false,
                )
            },
            dispatcher = Dispatchers.Unconfined,
        )

        val results = boundary.executePublicEvidenceBatch(listOf(request)) {
            retryCallbackCount += 1
        }

        assertEquals(1, results.size)
        assertEquals(ToolStatus.Failed, results.single().status)
        assertEquals(1, callCount)
        assertEquals(0, retryCallbackCount)
    }

    @Test
    fun publicEvidenceBatchMapsSingleToolCancellationWithoutCancellingWholeBatch() = runTest {
        val cancelled = ToolRequest(id = "cancelled", toolName = "cancelled_tool")
        val succeeded = ToolRequest(id = "succeeded", toolName = "succeeded_tool")
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor { request ->
                when (request.id) {
                    "cancelled" -> throw CancellationException("single tool stopped")
                    "succeeded" -> request.succeeded("success")
                    else -> error("Unexpected request ${request.id}")
                }
            },
            dispatcher = Dispatchers.Unconfined,
        )

        val results = boundary.executePublicEvidenceBatch(listOf(cancelled, succeeded))

        assertEquals(listOf("cancelled", "succeeded"), results.map { it.requestId })
        assertEquals(listOf(ToolStatus.Cancelled, ToolStatus.Succeeded), results.map { it.status })
        assertEquals(ToolErrorCode.UserCancelled, results.first().error?.code)
        assertFalse(results.first().retryable)
        assertEquals("cancelled_tool", results.first().data["toolName"])
    }

    @Test
    fun publicEvidenceBatchValidatorRejectsWholeBatchBeforeExecution() = runTest {
        val allowed = ToolRequest(id = "allowed", toolName = "allowed_tool")
        val blocked = ToolRequest(id = "blocked", toolName = "blocked_tool")
        var executeCallCount = 0
        val boundary = TimeoutToolExecutionBoundary(
            executor = LambdaToolExecutor { request ->
                executeCallCount += 1
                request.succeeded("unexpected")
            },
            dispatcher = Dispatchers.Unconfined,
            publicEvidenceBatchRequestValidator = { request ->
                if (request.id == "blocked") {
                    request.rejected("blocked is not public evidence")
                } else {
                    null
                }
            },
        )

        val results = boundary.executePublicEvidenceBatch(listOf(allowed, blocked))

        assertEquals(0, executeCallCount)
        assertEquals(listOf("allowed", "blocked"), results.map { it.requestId })
        assertEquals(listOf(ToolStatus.Rejected, ToolStatus.Rejected), results.map { it.status })
        assertTrue(results[0].summary.contains("another request was ineligible"))
        assertTrue(results[1].summary.contains("blocked is not public evidence"))
    }
}

private class LambdaToolExecutor(
    private val executeBlock: (ToolRequest) -> ToolResult,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult = executeBlock(request)
}
