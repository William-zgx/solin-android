package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.orchestration.NoOpToolProgressPublisher
import com.bytedance.zgx.solin.orchestration.ToolProgressPublisher

class ValidatingToolExecutor(
    private val delegate: ToolExecutor,
    // Built-in-only fallback. Production MUST pass the module-aware registry (see
    // SolinAppContainer): a tool this registry does not know is rejected as UnknownTool,
    // so a module tool would be blocked outright. See [defaultBuiltInToolRegistry].
    private val registry: ToolRegistry = defaultBuiltInToolRegistry,
    private val progressPublisher: ToolProgressPublisher = NoOpToolProgressPublisher,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        registry.validate(request)?.let { rejection ->
            // Pre-execution schema rejection — no tool handler was invoked; still publish a
            // terminal fail event so subscribers see the lifecycle close out.
            progressPublisher.fail(
                toolCallId = request.id,
                toolName = request.toolName,
                error = rejection.error?.message ?: "request rejected by registry",
            )
            return rejection
        }
        progressPublisher.start(toolCallId = request.id, toolName = request.toolName)
        val result = try {
            delegate.execute(request)
        } catch (throwable: Throwable) {
            progressPublisher.fail(
                toolCallId = request.id,
                toolName = request.toolName,
                error = throwable.cleanMessage(),
            )
            request.failed(
                code = ToolErrorCode.ExecutionFailed,
                summary = "Tool execution failed before completion: ${throwable.cleanMessage()}",
                retryable = true,
                data = request.toolExecutionContext(),
            )
        }.withToolExecutionContext(request.toolName)
        val validated = registry.validateResult(request, result) ?: result
        when (validated.status) {
            ToolStatus.Succeeded ->
                progressPublisher.complete(toolCallId = request.id, toolName = request.toolName)
            ToolStatus.Failed, ToolStatus.Rejected, ToolStatus.Cancelled ->
                progressPublisher.fail(
                    toolCallId = request.id,
                    toolName = request.toolName,
                    error = validated.error?.message ?: validated.summary,
                )
        }
        return validated
    }

    /**
     * Batch execution that publishes per-tool start/complete/fail lifecycle events and
     * validates each request/result sequentially before/after delegating to
     * [delegate.executeBatch]. Validation is performed SEQUENTIALLY for every request
     * BEFORE any tool is dispatched, so a single invalid request fails fast with no
     * concurrent work launched (matches the safety/gate requirement that policy checks
     * happen before the parallel pool starts).
     */
    override fun executeBatch(requests: List<ToolRequest>): List<ToolResult> {
        if (requests.isEmpty()) return emptyList()
        if (requests.size == 1) return listOf(execute(requests.single()))

        // Pre-validate every request sequentially before any dispatch.
        val rejections = mutableMapOf<String, ToolResult>()
        requests.forEach { request ->
            registry.validate(request)?.let { rejection ->
                rejections[request.id] = rejection
                progressPublisher.fail(
                    toolCallId = request.id,
                    toolName = request.toolName,
                    error = rejection.error?.message ?: "request rejected by registry",
                )
            }
        }

        val dispatchable = requests.filter { it.id !in rejections }
        // Publish start for every dispatchable request up-front so subscribers see the
        // concurrent lifecycle before any tool begins blocking.
        dispatchable.forEach { request ->
            progressPublisher.start(toolCallId = request.id, toolName = request.toolName)
        }

        val batchResults = try {
            delegate.executeBatch(dispatchable)
        } catch (throwable: Throwable) {
            // Defensive: the delegate should isolate per-tool failures, but a catastrophic
            // failure in the batch infrastructure is mapped to a failure for each request.
            dispatchable.map { request ->
                val failed = request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "Tool batch failed before completion: ${throwable.cleanMessage()}",
                    retryable = true,
                    data = request.toolExecutionContext(),
                ).withToolExecutionContext(request.toolName)
                progressPublisher.fail(
                    toolCallId = request.id,
                    toolName = request.toolName,
                    error = throwable.cleanMessage(),
                )
                failed
            }
        }

        // Pair each dispatched request with its result, validate, and publish terminal state.
        val resultsByRequestId = mutableMapOf<String, ToolResult>()
        dispatchable.forEachIndexed { i, request ->
            val raw = batchResults.getOrElse(i) {
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "Tool batch returned fewer results than requests",
                    retryable = false,
                    data = request.toolExecutionContext(),
                )
            }
            val withCtx = raw.withToolExecutionContext(request.toolName)
            val validated = registry.validateResult(request, withCtx) ?: withCtx
            when (validated.status) {
                ToolStatus.Succeeded ->
                    progressPublisher.complete(toolCallId = request.id, toolName = request.toolName)
                ToolStatus.Failed, ToolStatus.Rejected, ToolStatus.Cancelled ->
                    progressPublisher.fail(
                        toolCallId = request.id,
                        toolName = request.toolName,
                        error = validated.error?.message ?: validated.summary,
                    )
            }
            resultsByRequestId[request.id] = validated
        }

        return requests.map { request ->
            rejections[request.id] ?: resultsByRequestId[request.id]
            ?: request.failed(
                code = ToolErrorCode.ExecutionFailed,
                summary = "Tool batch produced no result for ${request.toolName}",
                retryable = false,
                data = request.toolExecutionContext(),
            )
        }
    }
}
