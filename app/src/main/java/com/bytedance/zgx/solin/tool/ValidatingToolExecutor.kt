package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.orchestration.NoOpToolProgressPublisher
import com.bytedance.zgx.solin.orchestration.ToolProgressPublisher
import com.bytedance.zgx.solin.logging.cleanMessage

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
            publishRejected(request, rejection)
            return rejection
        }
        progressPublisher.start(toolCallId = request.id, toolName = request.toolName)
        val result = try {
            delegate.execute(request)
        } catch (throwable: Throwable) {
            request.failed(
                code = ToolErrorCode.ExecutionFailed,
                summary = "Tool execution failed before completion: ${throwable.cleanMessage()}",
                retryable = true,
                data = request.toolExecutionContext(),
            )
        }.withToolExecutionContext(request.toolName)
        val validated = registry.validateResult(request, result) ?: result
        publishTerminal(request, validated)
        return validated
    }

    /**
     * Every request is validated before any is dispatched, so policy checks finish before the
     * parallel pool starts. Each request gets exactly one terminal progress event.
     */
    override fun executeBatch(requests: List<ToolRequest>): List<ToolResult> {
        if (requests.isEmpty()) return emptyList()
        if (requests.size == 1) return listOf(execute(requests.single()))

        // Pre-validate every request sequentially before any dispatch.
        val rejections = mutableMapOf<String, ToolResult>()
        requests.forEach { request ->
            registry.validate(request)?.let { rejection ->
                rejections[request.id] = rejection
                publishRejected(request, rejection)
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
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "Tool batch failed before completion: ${throwable.cleanMessage()}",
                    retryable = true,
                    data = request.toolExecutionContext(),
                )
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
            publishTerminal(request, validated)
            resultsByRequestId[request.id] = validated
        }

        return requests.map { request -> rejections[request.id] ?: resultsByRequestId.getValue(request.id) }
    }

    private fun publishRejected(request: ToolRequest, rejection: ToolResult) {
        progressPublisher.fail(
            toolCallId = request.id,
            toolName = request.toolName,
            error = rejection.error?.message ?: "request rejected by registry",
        )
    }

    /** Publishes the terminal lifecycle event for a validated result. */
    private fun publishTerminal(request: ToolRequest, validated: ToolResult) {
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
    }
}
