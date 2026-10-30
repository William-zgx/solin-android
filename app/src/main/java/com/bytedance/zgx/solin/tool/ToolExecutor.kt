package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.background.BackgroundTaskScheduler
import com.bytedance.zgx.solin.device.CalendarAvailabilityProvider
import com.bytedance.zgx.solin.device.ForegroundAppProvider
import com.bytedance.zgx.solin.device.ContactSummaryProvider
import com.bytedance.zgx.solin.device.CurrentScreenControlProvider
import com.bytedance.zgx.solin.device.CurrentScreenTextProvider
import com.bytedance.zgx.solin.device.NotificationSummaryProvider
import com.bytedance.zgx.solin.device.RecentFileProvider
import com.bytedance.zgx.solin.device.RecentImageTextProvider
import com.bytedance.zgx.solin.evidence.EvidenceBlobStore
import com.bytedance.zgx.solin.evidence.EvidenceBounds
import com.bytedance.zgx.solin.evidence.EvidenceSourceType
import com.bytedance.zgx.solin.evidence.NoOpEvidenceBlobStore
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrProvider
import com.bytedance.zgx.solin.module.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope

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
    fun executeBatch(requests: List<ToolRequest>): List<ToolResult> =
        requests.map { request ->
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
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val evidenceBlobStore: EvidenceBlobStore = NoOpEvidenceBlobStore,
    private val maxSummaryChars: Int = EvidenceBlobStore.MAX_INLINE_CHARS,
    private val maxDataValueChars: Int = EvidenceBlobStore.MAX_INLINE_CHARS,
    /**
     * Privacy label stamped on evidence blobs spilled by [bound].
     *
     * WHY a parameter and not a literal: privacy classification is a policy decision and
     * used to be hardcoded inside the routing layer, where it was invisible to reviewers of
     * the privacy boundary. Hoisting it makes the decision explicit at the composition root.
     *
     * The default MUST stay [MessagePrivacy.LocalOnly] and callers MUST NOT widen it: tool
     * evidence includes screen text, OCR, and private tool output, which the project's
     * fail-closed rule keeps on-device. This exists to make the label reviewable and
     * overridable in tests, NOT to allow remote-capable evidence spill.
     */
    private val evidencePrivacy: MessagePrivacy = MessagePrivacy.LocalOnly,
) : ToolExecutor {
    private val currentScreenOcrGroundingCache = CurrentScreenOcrGroundingCache(clockMillis)
    private val currentScreenControlProviderWithOcrGrounding =
        currentScreenControlProvider?.let { provider ->
            OcrGroundingCurrentScreenControlProvider(
                delegate = provider,
                cache = currentScreenOcrGroundingCache,
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
            clockMillis = clockMillis,
        )
    private val deviceControlToolExecutor =
        DeviceControlToolExecutor(
            provider = currentScreenControlProviderWithOcrGrounding,
            preflightProvider = currentScreenControlProvider,
        )

    override fun execute(request: ToolRequest): ToolResult =
        bound(executeUnvalidated(request))

    /**
     * Raw execution path without [bound] evidence truncation. Used both by [execute]
     * and by [executeBatch] so the concurrent path does not re-enter [execute] and
     * does not double-truncate evidence.
     */
    private fun executeUnvalidated(request: ToolRequest): ToolResult {
        val handler = toolHandlers[request.toolName]
        if (handler != null) {
            val handled = runBlocking(Dispatchers.IO) {
                runCatching { handler.execute(request) }
                    .getOrElse { t ->
                        if (t is CancellationException) throw t
                        request.failed(
                            code = ToolErrorCode.ExecutionFailed,
                            summary = "Tool handler for ${request.toolName} failed: ${t.cleanMessage()}",
                            retryable = true,
                            data = request.localOnlyData(),
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
            results[index] = bound(executeUnvalidated(requests[index]))
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
                            runCatching { bound(executeUnvalidated(request)) }
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

    /**
     * Evidence bounding: truncate an oversized summary / data value and spill the overflow
     * into the content-addressed [evidenceBlobStore].
     *
     * ARCHITECTURE NOTE (deliberate debt): this is evidence-bounding policy living inside the
     * router, so [RoutingToolExecutor] currently mixes routing with evidence truncation. It
     * belongs in its own decorator sitting between validation and routing
     * (Timeout → Validating → Bounding → Routing) so each link has one responsibility.
     * Extracting it is behavior-preserving but touches the decorator-chain assembly in
     * SolinAppContainer plus every executor construction in tests, so it is deferred to a
     * follow-up batch; the privacy label is already hoisted to [evidencePrivacy] so the
     * policy decision is at least explicit at the composition root.
     */
    private fun bound(result: ToolResult): ToolResult {
        val sourceType = EvidenceSourceType.ToolResult
        val privacy = evidencePrivacy

        // Bound summary
        val boundedSummaryResult = EvidenceBounds.headTail(
            text = result.summary,
            maxChars = maxSummaryChars,
            sourceType = sourceType,
            privacy = privacy,
            store = evidenceBlobStore,
        )

        // Bound each data value
        val dataRefs = mutableListOf<com.bytedance.zgx.solin.evidence.EvidenceBlobRef>()
        var boundedData: MutableMap<String, String>? = null
        for ((key, value) in result.data) {
            val boundedValue = EvidenceBounds.headTail(
                text = value,
                maxChars = maxDataValueChars,
                sourceType = sourceType,
                privacy = privacy,
                store = evidenceBlobStore,
            )
            if (boundedValue.truncated) {
                if (boundedData == null) {
                    boundedData = result.data.toMutableMap()
                }
                boundedData[key] = boundedValue.text
                boundedValue.ref?.let { dataRefs.add(it) }
            }
        }

        val summaryChanged = boundedSummaryResult.truncated
        val dataChanged = boundedData != null || dataRefs.isNotEmpty()
        val overflowRefsFromSummary = if (boundedSummaryResult.truncated) {
            listOfNotNull(boundedSummaryResult.ref)
        } else {
            emptyList()
        }
        val newOverflowRefs = overflowRefsFromSummary + dataRefs

        if (!summaryChanged && !dataChanged && newOverflowRefs.isEmpty()) {
            return result
        }
        return result.copy(
            summary = if (summaryChanged) boundedSummaryResult.text else result.summary,
            data = boundedData ?: result.data,
            overflowRefs = result.overflowRefs + newOverflowRefs,
        )
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
            MobileActionFunctions.QUERY_BACKGROUND_TASKS ->
                backgroundTasksToolExecutor?.execute(request)
                    ?: request.failed(
                        code = ToolErrorCode.ExecutionFailed,
                        summary = "后台任务本地存储不可用",
                        retryable = true,
                        data = request.localOnlyData(),
                    )
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            MobileActionFunctions.READ_RECENT_IMAGE_OCR ->
                recentScreenshotOcrToolExecutor?.execute(request)
                    ?: request.failed(
                        code = ToolErrorCode.ExecutionFailed,
                        summary = "图片 OCR 服务不可用",
                        retryable = true,
                        data = request.localOnlyData(),
                    )

            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT ->
                currentScreenTextToolExecutor?.execute(request)
                    ?: request.failed(
                        code = ToolErrorCode.ExecutionFailed,
                        summary = "当前屏幕文本服务不可用",
                        retryable = true,
                        data = request.localOnlyData(),
                    )

            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR ->
                currentScreenshotOcrToolExecutor.execute(request).also { result ->
                    currentScreenOcrGroundingCache.updateFrom(result)
                }

            else -> delegate.execute(request)
        }
    }

    private fun ToolRequest.isDeviceControlTool(): Boolean =
        toolRegistry.specFor(toolName)?.capability == ToolCapability.DeviceControl
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
            data = data,
        )
    } else {
        val where = if (concurrent) "concurrent batch" else "batch"
        failed(
            code = ToolErrorCode.ExecutionFailed,
            summary = "Tool $toolName failed in $where: ${t.cleanMessage()}",
            retryable = true,
            data = data,
        )
    }
}

/** The standard "this executor does not handle that tool" failure. */
internal fun ToolRequest.unknownToolFailure(): ToolResult =
    failed(
        code = ToolErrorCode.UnknownTool,
        summary = "Unknown tool: $toolName",
        retryable = false,
    )

/**
 * Reads an optional integer argument (trimmed), falling back to [default] when absent or not an
 * integer, then clamps it to [range].
 */
internal fun ToolRequest.boundedIntArgument(name: String, default: Int, range: IntRange): Int =
    (arguments[name]?.trim()?.toIntOrNull() ?: default).coerceIn(range)

internal fun ToolRequest.localOnlyData(): Map<String, String> =
    mapOf(
        "toolName" to toolName,
        "privacy" to MessagePrivacy.LocalOnly.name,
        "requiresLocalModel" to true.toString(),
    )

internal fun Throwable.cleanMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

internal fun ToolRequest.toolExecutionContext(): Map<String, String> =
    mapOf("toolName" to toolName)

internal fun ToolResult.withToolExecutionContext(toolName: String): ToolResult {
    return if (data["toolName"] == toolName) this else copy(data = data + ("toolName" to toolName))
}
