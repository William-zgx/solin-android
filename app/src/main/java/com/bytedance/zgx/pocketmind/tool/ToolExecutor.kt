package com.bytedance.zgx.pocketmind.tool

import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityProvider
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityQuery
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityQueryValidation
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityReadResult
import org.json.JSONArray
import org.json.JSONObject

interface ToolExecutor {
    fun execute(request: ToolRequest): ToolResult
}

class RoutingToolExecutor(
    private val calendarAvailabilityProvider: CalendarAvailabilityProvider,
    private val delegate: ToolExecutor,
) : ToolExecutor {
    private val calendarAvailabilityToolExecutor =
        CalendarAvailabilityToolExecutor(calendarAvailabilityProvider)

    override fun execute(request: ToolRequest): ToolResult =
        when (request.toolName) {
            MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY ->
                calendarAvailabilityToolExecutor.execute(request)

            else -> delegate.execute(request)
        }
}

class CalendarAvailabilityToolExecutor(
    private val provider: CalendarAvailabilityProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        return when (
            val validation = CalendarAvailabilityQuery.parseWindow(
                startIso = request.arguments["start"],
                endIso = request.arguments["end"],
            )
        ) {
            is CalendarAvailabilityQueryValidation.Invalid ->
                request.failed(
                    code = ToolErrorCode.InvalidRequest,
                    summary = validation.reason,
                    retryable = false,
                    data = request.localOnlyData(),
                )

            is CalendarAvailabilityQueryValidation.Valid ->
                executeValidated(request, validation)
        }
    }

    private fun executeValidated(
        request: ToolRequest,
        validation: CalendarAvailabilityQueryValidation.Valid,
    ): ToolResult =
        when (val result = provider.queryAvailability(validation.window)) {
            is CalendarAvailabilityReadResult.Available ->
                request.succeeded(
                    summary = "已查询日历忙闲：${result.snapshot.busyBlockCount} 个忙碌时段，" +
                        "${result.snapshot.freeBlockCount} 个空闲时段（仅包含时间区间）。",
                    data = request.localOnlyData() + mapOf(
                        "start" to CalendarAvailabilityQuery.formatInstant(result.snapshot.window.start),
                        "end" to CalendarAvailabilityQuery.formatInstant(result.snapshot.window.end),
                        "busyBlockCount" to result.snapshot.busyBlockCount.toString(),
                        "freeBlockCount" to result.snapshot.freeBlockCount.toString(),
                        "blocksJson" to result.snapshot.blocks.toJsonString(),
                    ),
                )

            CalendarAvailabilityReadResult.MissingPermission ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "需要日历读取权限才能查询忙闲区间",
                    retryable = true,
                    data = request.localOnlyData(),
                )

            is CalendarAvailabilityReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "日历忙闲查询失败：${result.reason}",
                    retryable = true,
                    data = request.localOnlyData(),
                )
        }

    private fun ToolRequest.localOnlyData(): Map<String, String> =
        mapOf(
            "toolName" to toolName,
            "privacy" to MessagePrivacy.LocalOnly.name,
        )

    private fun List<com.bytedance.zgx.pocketmind.device.CalendarAvailabilityBlock>.toJsonString(): String {
        val blocksArray = JSONArray()
        forEach { block ->
            blocksArray.put(
                JSONObject()
                    .put("status", block.status.wireValue)
                    .put("start", CalendarAvailabilityQuery.formatInstant(block.start))
                    .put("end", CalendarAvailabilityQuery.formatInstant(block.end)),
            )
        }
        return blocksArray.toString()
    }
}
