package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.action.MobileActionFunctions

/**
 * Executes `calculate` with [LocalCalculator]: no device reads, network or model call. The
 * expression and result are LocalOnly private outputs (trace/audit see only the redacted summary);
 * the user-visible summary carries the answer because the tool has no model continuation.
 */
class LocalCalculatorToolExecutor : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.CALCULATE) return request.unknownToolFailure()
        val expression = request.arguments["expression"].orEmpty()
        return when (val outcome = LocalCalculator.evaluate(expression)) {
            is CalculationOutcome.Value ->
                request.succeeded(
                    summary = outcome.summary,
                    data = request.localOnlyData() + mapOf(
                        "kind" to outcome.kind.wireName,
                        "expression" to outcome.expressionDisplay,
                        "result" to outcome.resultDisplay,
                        "approximate" to outcome.approximate.toString(),
                    ),
                )

            is CalculationOutcome.Invalid ->
                request.failed(
                    code = ToolErrorCode.InvalidRequest,
                    summary = "无法计算：${outcome.reason}",
                    retryable = false,
                    data = request.localOnlyData(),
                )
        }
    }
}
