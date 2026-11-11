package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.background.BackgroundTaskScheduler
import com.bytedance.zgx.solin.device.CalendarAvailabilityProvider
import com.bytedance.zgx.solin.device.ContactSummaryProvider
import com.bytedance.zgx.solin.device.CurrentScreenControlProvider
import com.bytedance.zgx.solin.device.CurrentScreenTextProvider
import com.bytedance.zgx.solin.device.ForegroundAppProvider
import com.bytedance.zgx.solin.device.NotificationSummaryProvider
import com.bytedance.zgx.solin.device.RecentFileProvider
import com.bytedance.zgx.solin.device.RecentImageTextProvider
import com.bytedance.zgx.solin.logging.cleanMessage
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope

/**
 * Receives a [ToolRequest] and returns a [ToolResult], or null to fall through to the default
 * built-in executor.
 *
 * Implementations may run concurrently and inside `runInterruptible`-style contexts. They must be
 * thread-safe, honour cancellation promptly, and propagate [CancellationException].
 */
fun interface ToolHandler {
    suspend fun execute(request: ToolRequest): ToolResult?
}

interface ToolExecutor {
    fun execute(request: ToolRequest): ToolResult

    /**
     * Execute a batch of [requests] and return results aligned to the input order.
     *
     * Default implementation runs each request sequentially via [execute] with
     * per-tool isolation: if a single call throws (including
     * [kotlinx.coroutines.CancellationException] thrown by a suspend-aware tool
     * handler running inside [runBlocking]), it is caught and converted to a
     * terminal [ToolResult] rather than aborting the rest of the batch. The
     * caller's coroutine context is NOT cancelled — cooperative cancellation is
     * the caller's responsibility (see [com.bytedance.zgx.solin.tool.TimeoutToolExecutionBoundary]).
     *
     * Implementations MAY partition the batch and run independent requests concurrently
     * when safe (see [ToolExecutionMode.ConcurrentWhenIndependent]). Implementations
     * must preserve input order in the returned list and MUST isolate per-tool failures
     * (one tool's throw must not prevent siblings from running and must not surface
     * to the caller as a batch-level throw).
     */
    fun executeBatch(requests: List<ToolRequest>): List<ToolResult> = requests.map { request ->
        try {
            execute(request)
        } catch (t: Throwable) {
            request.batchThrowableResult(t, concurrent = false)
        }
    }
}

class RoutingToolExecutor(
    private val calendarAvailabilityProvider: CalendarAvailabilityProvider,
    private val foregroundAppProvider: ForegroundAppProvider,
    private val contactSummaryProvider: ContactSummaryProvider,
    private val notificationSummaryProvider: NotificationSummaryProvider,
    private val recentFileProvider: RecentFileProvider,
    private val webSearchProvider: WebSearchProvider,
    private val delegate: ToolExecutor,
    private val backgroundTaskScheduler: BackgroundTaskScheduler? = null,
    private val recentImageTextProvider: RecentImageTextProvider? = null,
    private val currentScreenTextProvider: CurrentScreenTextProvider? = null,
    private val currentScreenshotOcrProvider: CurrentScreenshotOcrProvider? = null,
    private val currentScreenControlProvider: CurrentScreenControlProvider? = null,
    // Built-in-only fallback. Production MUST pass the module-aware registry (see
    // SolinAppContainer): this executor uses the registry to classify DeviceControl tools and
    // to decide sequential-vs-concurrent batching, and an unrecognized tool would be routed
    // by the `else` branch and mis-partitioned. See [defaultBuiltInToolRegistry].
    private val toolRegistry: ToolRegistry = defaultBuiltInToolRegistry,
    private val toolHandlers: Map<String, ToolHandler> = emptyMap(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) : ToolExecutor {
    private val currentScreenOcrGroundingCache = CurrentScreenOcrGroundingCache(clockMillis)
    private val currentScreenControlProviderWithOcrGrounding =
        currentScreenControlProvider?.let { provider ->
            OcrGroundingCurrentScreenControlProvider(
                delegate = provider,
                cache = currentScreenOcrGroundingCache
            )
        }
    private val calendarAvailabilityToolExecutor =
        CalendarAvailabilityToolExecutor(calendarAvailabilityProvider)
    private val foregroundAppToolExecutor = ForegroundAppToolExecutor(foregroundAppProvider)
    private val contactSummaryToolExecutor = ContactSummaryToolExecutor(contactSummaryProvider)
    private val notificationSummaryToolExecutor =
        NotificationSummaryToolExecutor(notificationSummaryProvider)
    private val recentFilesToolExecutor = RecentFilesToolExecutor(recentFileProvider)
    private val webSearchToolExecutor = WebSearchToolExecutor(webSearchProvider)
    private val localCalculatorToolExecutor = LocalCalculatorToolExecutor()
    private val backgroundTasksToolExecutor =
        backgroundTaskScheduler?.let(::BackgroundTasksToolExecutor)
    private val recentScreenshotOcrToolExecutor =
        recentImageTextProvider?.let(::RecentScreenshotOcrToolExecutor)
    private val currentScreenTextToolExecutor =
        currentScreenTextProvider?.let(::CurrentScreenTextToolExecutor)
    private val currentScreenshotOcrToolExecutor =
        CurrentScreenshotOcrToolExecutor(
            provider = currentScreenshotOcrProvider,
            currentScreenControlProvider = currentScreenControlProvider,
            clockMillis = clockMillis
        )
    private val deviceControlToolExecutor =
        DeviceControlToolExecutor(
            provider = currentScreenControlProviderWithOcrGrounding,
            preflightProvider = currentScreenControlProvider
        )

    override fun execute(request: ToolRequest): ToolResult = executeRouted(request)

    /** Routes one request without applying validation or output bounding. */
    private fun executeRouted(request: ToolRequest): ToolResult {
        val handler = toolHandlers[request.toolName]
        if (handler != null) {
            val handled = runBlocking(Dispatchers.IO) {
                runCatching { handler.execute(request) }
                    .getOrElse { t ->
                        if (t is CancellationException) throw t
                        request.localOnlyFailure(
                            ToolErrorCode.ExecutionFailed,
                            "Tool handler for ${request.toolName} failed: ${t.cleanMessage()}"
                        )
                    }
            }
            return handled ?: dispatchAsBuiltIn(request)
        }
        return dispatchAsBuiltIn(request)
    }

    /**
     * Execute a batch of requests, partitioning into a sequential "device control" segment
     * (run first, in input order) and an independent segment (run concurrently on
     * [Dispatchers.IO]). Order is preserved across the whole returned list.
     *
     * Partitioning rules (any match puts a request in the sequential segment):
     *  - [ToolCapability.DeviceControl] tool
     *  - [RiskLevel.HighExternalSend] or higher risk
     *  - [ConfirmationPolicy] is not [ConfirmationPolicy.NotRequired]
     *  - [ToolExecutionMode.Sequential] (explicit opt-out)
     *
     * Remaining requests whose [ToolSpec.executionMode] is [ToolExecutionMode.ConcurrentWhenIndependent]
     * run concurrently. All others fall back to sequential execution.
     *
     * NOTE: this method is the raw dispatch path; per-tool validation and progress publishing
     * are the caller's responsibility (see [ValidatingToolExecutor.executeBatch]).
     */
    override fun executeBatch(requests: List<ToolRequest>): List<ToolResult> {
        if (requests.isEmpty()) return emptyList()
        if (requests.size == 1) return listOf(execute(requests.single()))

        val sequentialIndices = mutableListOf<Int>()
        val concurrentIndices = mutableListOf<Int>()
        requests.forEachIndexed { index, request ->
            if (shouldRunSequentially(request)) {
                sequentialIndices += index
            } else {
                concurrentIndices += index
            }
        }

        val results = arrayOfNulls<ToolResult>(requests.size)

        // Run sequential segment first, in input order.
        for (index in sequentialIndices) {
            results[index] = executeRouted(requests[index])
        }

        // Run independent segment concurrently when present. Each task is isolated via
        // supervisorScope + runCatching so one failure or cancellation cannot cancel siblings
        // (mirrors the default sequential `executeBatch` contract).
        if (concurrentIndices.isNotEmpty()) {
            runBlocking(Dispatchers.IO) {
                supervisorScope {
                    val deferreds = concurrentIndices.map { index ->
                        async(Dispatchers.IO) {
                            val request = requests[index]
                            runCatching { executeRouted(request) }
                                .getOrElse { t -> request.batchThrowableResult(t, concurrent = true) }
                        }
                    }
                    val concurrentResults = deferreds.awaitAll()
                    concurrentIndices.forEachIndexed { i, index ->
                        results[index] = concurrentResults[i]
                    }
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        return results.toList() as List<ToolResult>
    }

    private fun shouldRunSequentially(request: ToolRequest): Boolean {
        if (request.isDeviceControlTool()) return true
        val spec = toolRegistry.specFor(request.toolName) ?: return true
        if (spec.riskLevel.ordinal >= RiskLevel.HighExternalSend.ordinal) return true
        if (spec.confirmationPolicy != ConfirmationPolicy.NotRequired) return true
        if (spec.executionMode == ToolExecutionMode.Sequential) return true
        // Only ConcurrentWhenIndependent tools may run concurrently; everything else stays sequential.
        return spec.executionMode != ToolExecutionMode.ConcurrentWhenIndependent
    }

    private fun dispatchAsBuiltIn(request: ToolRequest): ToolResult {
        if (request.isDeviceControlTool()) return deviceControlToolExecutor.execute(request)
        if (request.toolName != MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR) {
            currentScreenOcrGroundingCache.clear()
        }
        return when (request.toolName) {
            MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY ->
                calendarAvailabilityToolExecutor.execute(request)
            MobileActionFunctions.QUERY_FOREGROUND_APP ->
                foregroundAppToolExecutor.execute(request)
            MobileActionFunctions.QUERY_CONTACTS ->
                contactSummaryToolExecutor.execute(request)
            MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS ->
                notificationSummaryToolExecutor.execute(request)
            MobileActionFunctions.QUERY_RECENT_FILES ->
                recentFilesToolExecutor.execute(request)
            MobileActionFunctions.WEB_SEARCH ->
                webSearchToolExecutor.execute(request)
            MobileActionFunctions.CALCULATE ->
                localCalculatorToolExecutor.execute(request)
            MobileActionFunctions.QUERY_BACKGROUND_TASKS ->
                backgroundTasksToolExecutor?.execute(request)
                    ?: request.localOnlyFailure(ToolErrorCode.ExecutionFailed, "后台任务本地存储不可用")
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            MobileActionFunctions.READ_RECENT_IMAGE_OCR ->
                recentScreenshotOcrToolExecutor?.execute(request)
                    ?: request.localOnlyFailure(ToolErrorCode.ExecutionFailed, "图片 OCR 服务不可用")

            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT ->
                currentScreenTextToolExecutor?.execute(request)
                    ?: request.localOnlyFailure(ToolErrorCode.ExecutionFailed, "当前屏幕文本服务不可用")

            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR ->
                currentScreenshotOcrToolExecutor.execute(request).also { result ->
                    currentScreenOcrGroundingCache.updateFrom(result)
                }

            else -> delegate.execute(request)
        }
    }

    private fun ToolRequest.isDeviceControlTool(): Boolean = toolRegistry.specFor(toolName)?.capability == ToolCapability.DeviceControl
}

/**
 * Maps a throwable escaping one batched tool call to that call's terminal result, so one tool's
 * failure never aborts its siblings. Cancellation becomes Cancelled; anything else Failed
 * (retryable). The summary wording differs between the default sequential batch and the
 * concurrent segment of [RoutingToolExecutor.executeBatch] and is kept as it was.
 */
internal fun ToolRequest.batchThrowableResult(t: Throwable, concurrent: Boolean): ToolResult {
    val data = buildMap {
        put("toolName", toolName)
        putAll(localOnlyData())
    }
    return if (t is CancellationException) {
        val where = if (concurrent) " in concurrent batch" else ""
        cancelled(
            summary = "Tool $toolName was cancelled$where: ${t.cleanMessage()}",
            data = data
        )
    } else {
        val where = if (concurrent) "concurrent batch" else "batch"
        failed(
            code = ToolErrorCode.ExecutionFailed,
            summary = "Tool $toolName failed in $where: ${t.cleanMessage()}",
            retryable = true,
            data = data
        )
    }
}

/** The standard "this executor does not handle that tool" failure. */
internal fun ToolRequest.unknownToolFailure(): ToolResult = failed(
    code = ToolErrorCode.UnknownTool,
    summary = "Unknown tool: $toolName",
    retryable = false
)

/**
 * Reads an optional integer argument (trimmed), falling back to [default] when absent or not an
 * integer, then clamps it to [range].
 */
internal fun ToolRequest.boundedIntArgument(name: String, default: Int, range: IntRange): Int = (arguments[name]?.trim()?.toIntOrNull() ?: default).coerceIn(range)

internal fun ToolRequest.localOnlyData(): Map<String, String> = mapOf(
    "toolName" to toolName,
    "privacy" to MessagePrivacy.LocalOnly.name,
    "requiresLocalModel" to true.toString()
)

/** Retryable failure whose data marks the result local-only. */
internal fun ToolRequest.localOnlyFailure(code: ToolErrorCode, summary: String): ToolResult = failed(code = code, summary = summary, retryable = true, data = localOnlyData())

internal fun ToolRequest.toolExecutionContext(): Map<String, String> = mapOf("toolName" to toolName)

internal fun ToolResult.withToolExecutionContext(toolName: String): ToolResult = if (data["toolName"] == toolName) this else copy(data = data + ("toolName" to toolName))
