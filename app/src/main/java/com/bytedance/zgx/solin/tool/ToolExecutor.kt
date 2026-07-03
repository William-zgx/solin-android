package com.bytedance.zgx.solin.tool

import android.provider.Settings
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.background.BackgroundTaskScheduler
import com.bytedance.zgx.solin.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.solin.background.ScheduledTask
import com.bytedance.zgx.solin.device.CalendarAvailabilityProvider
import com.bytedance.zgx.solin.device.CalendarAvailabilityQuery
import com.bytedance.zgx.solin.device.CalendarAvailabilityQueryValidation
import com.bytedance.zgx.solin.device.CalendarAvailabilityReadResult
import com.bytedance.zgx.solin.device.ForegroundAppProvider
import com.bytedance.zgx.solin.device.ForegroundAppReadResult
import com.bytedance.zgx.solin.device.ContactSummaryItem
import com.bytedance.zgx.solin.device.ContactSummaryProvider
import com.bytedance.zgx.solin.device.ContactSummaryReadResult
import com.bytedance.zgx.solin.device.CurrentScreenControlProvider
import com.bytedance.zgx.solin.device.CurrentScreenTextProvider
import com.bytedance.zgx.solin.device.CurrentScreenTextReadResult
import com.bytedance.zgx.solin.device.DEVICE_CONTROL_METADATA_POLICY
import com.bytedance.zgx.solin.device.DEVICE_CONTROL_SOURCE_ACCESSIBILITY
import com.bytedance.zgx.solin.device.NotificationSummaryItem
import com.bytedance.zgx.solin.device.NotificationSummaryProvider
import com.bytedance.zgx.solin.device.NotificationSummaryReadResult
import com.bytedance.zgx.solin.device.RecentFileItem
import com.bytedance.zgx.solin.device.RecentFileProvider
import com.bytedance.zgx.solin.device.RecentFileReadResult
import com.bytedance.zgx.solin.device.RecentImageTextProvider
import com.bytedance.zgx.solin.device.RecentImageTextReadResult
import com.bytedance.zgx.solin.device.AppSearchProgressEvidence
import com.bytedance.zgx.solin.device.AppSearchResultVerifier
import com.bytedance.zgx.solin.device.SearchResultVerification
import com.bytedance.zgx.solin.device.ScreenBounds
import com.bytedance.zgx.solin.device.ScreenNode
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.ScreenStateSnapshot
import com.bytedance.zgx.solin.device.UiActionExecutionResult
import com.bytedance.zgx.solin.device.UiActionFailureKind
import com.bytedance.zgx.solin.device.UiActionReadResult
import com.bytedance.zgx.solin.device.UiActionStatus
import com.bytedance.zgx.solin.device.UiOcrGroundingHint
import com.bytedance.zgx.solin.device.UiScrollDirection
import com.bytedance.zgx.solin.device.hasDangerousActionControl
import com.bytedance.zgx.solin.device.hasOcrDangerousActionText
import com.bytedance.zgx.solin.device.hasSearchSubmitContext
import com.bytedance.zgx.solin.device.hasTargetlessTypingContext
import com.bytedance.zgx.solin.device.height
import com.bytedance.zgx.solin.device.normalizedLookupKey
import com.bytedance.zgx.solin.device.toScreenNodesJsonString
import com.bytedance.zgx.solin.device.toScreenObservationJsonString
import com.bytedance.zgx.solin.device.width
import com.bytedance.zgx.solin.evidence.EvidenceBlobStore
import com.bytedance.zgx.solin.evidence.EvidenceBounds
import com.bytedance.zgx.solin.evidence.EvidenceSourceType
import com.bytedance.zgx.solin.evidence.NoOpEvidenceBlobStore
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrContract
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrProvider
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrReadResult
import com.bytedance.zgx.solin.multimodal.OcrTextBlock
import com.bytedance.zgx.solin.multimodal.toOcrBlocksJsonString
import com.bytedance.zgx.solin.module.ToolHandler
import com.bytedance.zgx.solin.orchestration.NoOpToolProgressPublisher
import com.bytedance.zgx.solin.orchestration.ToolProgressPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_CONTACT_SUMMARY_COUNT = 20
private const val MAX_NOTIFICATION_SUMMARY_COUNT = 20
private const val DEFAULT_BACKGROUND_TASK_QUERY_COUNT = 20
private const val MAX_BACKGROUND_TASK_QUERY_COUNT = 50
private const val BACKGROUND_TASK_METADATA_POLICY = "background_tasks_local_only_no_reminder_body"
private const val CURRENT_SCREEN_OCR_GROUNDING_TTL_MILLIS = 15_000L
private const val OCR_GROUNDING_SIGNATURE_MAX_ELEMENTS = 24

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
            } catch (cancellation: CancellationException) {
                request.cancelled(
                    summary = "Tool ${request.toolName} was cancelled: ${cancellation.cleanMessage()}",
                    data = buildMap {
                        put("toolName", request.toolName)
                        putAll(request.localOnlyData())
                    },
                )
            } catch (t: Throwable) {
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "Tool ${request.toolName} failed in batch: ${t.cleanMessage()}",
                    retryable = true,
                    data = buildMap {
                        put("toolName", request.toolName)
                        putAll(request.localOnlyData())
                    },
                )
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
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val toolHandlers: Map<String, ToolHandler> = emptyMap(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val evidenceBlobStore: EvidenceBlobStore = NoOpEvidenceBlobStore,
    private val maxSummaryChars: Int = EvidenceBlobStore.MAX_INLINE_CHARS,
    private val maxDataValueChars: Int = EvidenceBlobStore.MAX_INLINE_CHARS,
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
                                .getOrElse { t ->
                                    when (t) {
                                        is CancellationException -> request.cancelled(
                                            summary = "Tool ${request.toolName} was cancelled in concurrent batch: ${t.cleanMessage()}",
                                            data = buildMap {
                                                put("toolName", request.toolName)
                                                putAll(request.localOnlyData())
                                            },
                                        )
                                        else -> request.failed(
                                            code = ToolErrorCode.ExecutionFailed,
                                            summary = "Tool ${request.toolName} failed in concurrent batch: ${t.cleanMessage()}",
                                            retryable = true,
                                            data = request.localOnlyData(),
                                        )
                                    }
                                }
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

    private fun bound(result: ToolResult): ToolResult {
        val sourceType = EvidenceSourceType.ToolResult
        val privacy = MessagePrivacy.LocalOnly

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

private class OcrGroundingCurrentScreenControlProvider(
    private val delegate: CurrentScreenControlProvider,
    private val cache: CurrentScreenOcrGroundingCache,
) : CurrentScreenControlProvider {
    override fun observeCurrentScreen(maxTextChars: Int, maxNodes: Int): ScreenStateReadResult {
        cache.clear()
        return delegate.observeCurrentScreen(maxTextChars = maxTextChars, maxNodes = maxNodes)
    }

    override fun tap(target: String, timeoutMillis: Long): UiActionReadResult {
        val validationSnapshot = ocrGroundingValidationSnapshot()
        val hint = cache.consumeFor(target, currentSnapshot = validationSnapshot)
        hint.rejectDangerousOcrAction(validationSnapshot)?.let { return it }
        return delegate.tapWithOcrGrounding(
            target = target,
            ocrGroundingHint = hint,
            timeoutMillis = timeoutMillis,
        )
    }

    override fun tapWithOcrGrounding(
        target: String,
        ocrGroundingHint: UiOcrGroundingHint?,
        timeoutMillis: Long,
    ): UiActionReadResult {
        val validationSnapshot = ocrGroundingValidationSnapshot()
        val hint = ocrGroundingHint ?: cache.consumeFor(
                target,
                currentSnapshot = validationSnapshot,
            )
        hint.rejectDangerousOcrAction(validationSnapshot)?.let { return it }
        return delegate.tapWithOcrGrounding(
            target = target,
            ocrGroundingHint = hint,
            timeoutMillis = timeoutMillis,
        )
    }

    override fun typeText(
        text: String,
        target: String?,
        timeoutMillis: Long,
        allowClipboardPasteFallback: Boolean,
    ): UiActionReadResult {
        val validationSnapshot = ocrGroundingValidationSnapshot()
        val hint = if (target.isNullOrBlank()) {
            cache.consumeForSearchEntry(currentSnapshot = validationSnapshot)
        } else {
            cache.consumeFor(target, currentSnapshot = validationSnapshot)
        }
        hint.rejectDangerousOcrAction(validationSnapshot)?.let { return it }
        validationSnapshot.rejectTargetlessTypingWithoutContext(target, hint)?.let { return it }
        return delegate.typeTextWithOcrGrounding(
            text = text,
            target = target,
            ocrGroundingHint = hint,
            timeoutMillis = timeoutMillis,
            allowClipboardPasteFallback = allowClipboardPasteFallback,
        )
    }

    override fun submitSearch(timeoutMillis: Long): UiActionReadResult {
        val validationSnapshot = ocrGroundingValidationSnapshot()
        val hint = cache.consumeForSubmitSearch(currentSnapshot = validationSnapshot)
        hint.rejectDangerousOcrAction(validationSnapshot)?.let { return it }
        validationSnapshot.rejectSubmitWithoutSearchContext(hint)?.let { return it }
        return delegate.submitSearchWithOcrGrounding(
            ocrGroundingHint = hint,
            timeoutMillis = timeoutMillis,
        )
    }

    override fun submitSearchWithOcrGrounding(
        ocrGroundingHint: UiOcrGroundingHint?,
        timeoutMillis: Long,
    ): UiActionReadResult {
        val validationSnapshot = ocrGroundingValidationSnapshot()
        val hint = ocrGroundingHint ?: cache.consumeForSubmitSearch(
            currentSnapshot = validationSnapshot,
        )
        hint.rejectDangerousOcrAction(validationSnapshot)?.let { return it }
        validationSnapshot.rejectSubmitWithoutSearchContext(hint)?.let { return it }
        return delegate.submitSearchWithOcrGrounding(
            ocrGroundingHint = hint,
            timeoutMillis = timeoutMillis,
        )
    }

    override fun scroll(direction: UiScrollDirection, target: String?, timeoutMillis: Long): UiActionReadResult {
        cache.clear()
        return delegate.scroll(direction = direction, target = target, timeoutMillis = timeoutMillis)
    }

    override fun pressBack(timeoutMillis: Long): UiActionReadResult {
        cache.clear()
        return delegate.pressBack(timeoutMillis = timeoutMillis)
    }

    override fun waitForScreen(timeoutMillis: Long): UiActionReadResult {
        cache.clear()
        return delegate.waitForScreen(timeoutMillis = timeoutMillis)
    }

    private fun ocrGroundingValidationSnapshot(): ScreenStateSnapshot? =
        when (val result = delegate.observeCurrentScreen()) {
            is ScreenStateReadResult.Available -> result.snapshot
            else -> null
        }

    private fun ScreenStateSnapshot?.rejectSubmitWithoutSearchContext(
        hint: UiOcrGroundingHint?,
    ): UiActionReadResult? {
        if (hint != null) return null
        if (this != null && hasSearchSubmitContext()) return null
        return toGuardFailure(
            reason = "当前屏幕没有搜索输入框、搜索提交控件或 OCR 搜索提交证据",
            failureKind = UiActionFailureKind.SubmitNotFound,
        )
    }

    private fun ScreenStateSnapshot?.rejectTargetlessTypingWithoutContext(
        target: String?,
        hint: UiOcrGroundingHint?,
    ): UiActionReadResult? {
        if (!target.isNullOrBlank()) return null
        if (hint != null) return null
        if (this != null && hasTargetlessTypingContext()) return null
        return toGuardFailure(
            reason = "当前屏幕没有明确的搜索输入目标，请先点击或指定输入框",
            failureKind = UiActionFailureKind.EditableNotFound,
        )
    }

    private fun UiOcrGroundingHint?.rejectDangerousOcrAction(
        snapshot: ScreenStateSnapshot?,
    ): UiActionReadResult? {
        if (this == null || !text.hasOcrDangerousActionText()) return null
        return snapshot.toGuardFailure(
            reason = "当前屏幕 OCR 显示支付、发送、删除、发布、下单、购买、转账或授权类控件，已停止自动 UI 动作。",
            failureKind = UiActionFailureKind.DangerousAction,
            retryable = false,
        )
    }

    private fun ScreenStateSnapshot?.toGuardFailure(
        reason: String,
        failureKind: UiActionFailureKind,
        retryable: Boolean = true,
    ): UiActionReadResult =
        if (this == null) {
            UiActionReadResult.Failed(
                reason = reason,
                retryable = retryable,
                failureKind = failureKind,
            )
        } else {
            UiActionReadResult.Available(
                UiActionExecutionResult(
                    status = UiActionStatus.Failed,
                    before = this,
                    after = this,
                    summary = reason,
                    retryable = retryable,
                    failureKind = failureKind,
                ),
            )
        }
}

private class CurrentScreenOcrGroundingCache(
    private val clockMillis: () -> Long,
) {
    private var cachedHint: CachedOcrGroundingHint? = null

    fun updateFrom(result: ToolResult) {
        if (result.status != ToolStatus.Succeeded) {
            clear()
            return
        }
        cachedHint = runCatching {
            val observationJson = result.data["screenObservationJson"]?.takeIf { value -> value.isNotBlank() }
                ?: return@runCatching null
            val observation = JSONObject(observationJson)
            val screenSignature = observation.toOcrGroundingScreenSignature()
                ?: return@runCatching null
            val elements = observation.optJSONArray("elements") ?: return@runCatching null
            val ocrElements = (0 until elements.length())
                .mapNotNull { index -> elements.optJSONObject(index)?.toOcrGroundingHint(observation) }
            if (ocrElements.isEmpty()) null else CachedOcrGroundingHint(
                capturedAtMillis = clockMillis(),
                screenSignature = screenSignature,
                hints = ocrElements,
            )
        }.getOrNull()
    }

    fun consumeFor(target: String, currentSnapshot: ScreenStateSnapshot?): UiOcrGroundingHint? {
        val cache = cachedHint ?: return null
        cachedHint = null
        if (clockMillis() - cache.capturedAtMillis > CURRENT_SCREEN_OCR_GROUNDING_TTL_MILLIS) return null
        if (!cache.screenSignature.matches(currentSnapshot)) return null
        val normalizedTarget = target.normalizedLookupKey()
        if (normalizedTarget.isBlank()) return null
        return cache.hints.bestForTarget(normalizedTarget)
    }

    fun consumeForSearchEntry(currentSnapshot: ScreenStateSnapshot?): UiOcrGroundingHint? {
        val cache = cachedHint ?: return null
        cachedHint = null
        if (clockMillis() - cache.capturedAtMillis > CURRENT_SCREEN_OCR_GROUNDING_TTL_MILLIS) return null
        if (!cache.screenSignature.matches(currentSnapshot)) return null
        return cache.hints
            .filter { hint -> hint.hasSearchContextText() }
            .maxByOrNull { hint -> hint.scoreForSearchEntryTarget() }
    }

    fun consumeForSubmitSearch(currentSnapshot: ScreenStateSnapshot?): UiOcrGroundingHint? {
        val cache = cachedHint ?: return null
        cachedHint = null
        if (clockMillis() - cache.capturedAtMillis > CURRENT_SCREEN_OCR_GROUNDING_TTL_MILLIS) return null
        if (!cache.screenSignature.matches(currentSnapshot)) return null
        if (!cache.hints.hasOcrSearchSubmitContext()) return null
        return cache.hints
            .filter { hint -> hint.matchesSubmitSearchTarget() }
            .maxByOrNull { hint -> hint.scoreForSubmitSearchTarget() }
    }

    fun clear() {
        cachedHint = null
    }
}

private data class CachedOcrGroundingHint(
    val capturedAtMillis: Long,
    val screenSignature: OcrGroundingScreenSignature,
    val hints: List<UiOcrGroundingHint>,
)

private data class OcrGroundingScreenSignature(
    val packageName: String?,
    val nodeCount: Int,
    val actionableNodeCount: Int,
    val elementFingerprint: String,
) {
    fun matches(snapshot: ScreenStateSnapshot?): Boolean =
        snapshot?.toOcrGroundingScreenSignature() == this
}

private fun ScreenStateSnapshot.toOcrGroundingScreenSignature(): OcrGroundingScreenSignature =
    OcrGroundingScreenSignature(
        packageName = packageName,
        nodeCount = nodeCount,
        actionableNodeCount = actionableNodeCount,
        elementFingerprint = nodes
            .asSequence()
            .map { node -> node.ocrGroundingFingerprint() }
            .take(OCR_GROUNDING_SIGNATURE_MAX_ELEMENTS)
            .joinToString(separator = ";"),
    )

private fun JSONObject.toOcrGroundingScreenSignature(): OcrGroundingScreenSignature? {
    val elements = optJSONArray("elements") ?: return null
    val accessibilityElements = (0 until elements.length())
        .mapNotNull { index -> elements.optJSONObject(index) }
        .filter { element -> element.optString("source") == "accessibility" }
    if (accessibilityElements.isEmpty()) return null
    return OcrGroundingScreenSignature(
        packageName = optNullableString("packageName"),
        nodeCount = accessibilityElements.size,
        actionableNodeCount = accessibilityElements.count { element -> element.isActionableObservationElement() },
        elementFingerprint = accessibilityElements
            .asSequence()
            .map { element -> element.ocrGroundingFingerprint() }
            .take(OCR_GROUNDING_SIGNATURE_MAX_ELEMENTS)
            .joinToString(separator = ";"),
    )
}

private fun ScreenNode.ocrGroundingFingerprint(): String =
    listOf(
        clickable.toString(),
        editable.toString(),
        scrollable.toString(),
        enabled.toString(),
        bounds?.boundsFingerprint().orEmpty(),
        text.ifBlank { contentDescription }.normalizedLookupKey(),
    ).joinToString(separator = ",")

private fun JSONObject.ocrGroundingFingerprint(): String {
    val clickability = optJSONObject("clickability")
    return listOf(
        clickability?.optBoolean("clickable", false).toString(),
        clickability?.optBoolean("editable", false).toString(),
        clickability?.optBoolean("scrollable", false).toString(),
        clickability?.optBoolean("enabled", true).toString(),
        optJSONObject("bounds")?.boundsFingerprint().orEmpty(),
        optString("text").normalizedLookupKey(),
    ).joinToString(separator = ",")
}

private fun JSONObject.isActionableObservationElement(): Boolean {
    val clickability = optJSONObject("clickability") ?: return false
    if (clickability.optBoolean("enabled", true) == false) return false
    return clickability.optBoolean("clickable", false) ||
        clickability.optBoolean("editable", false) ||
        clickability.optBoolean("scrollable", false)
}

private fun ScreenBounds.boundsFingerprint(): String =
    "$left,$top,$right,$bottom"

private fun JSONObject.boundsFingerprint(): String =
    "${optInt("left")},${optInt("top")},${optInt("right")},${optInt("bottom")}"

private fun List<UiOcrGroundingHint>.hasOcrSearchSubmitContext(): Boolean =
    any { hint -> hint.matchesSubmitSearchTarget() } &&
        any { hint -> hint.hasSearchContextText() }

private fun UiOcrGroundingHint.hasSearchContextText(): Boolean {
    val normalizedText = text.normalizedLookupKey()
    if (normalizedText.isBlank()) return false
    return listOf(
        "搜索框",
        "搜索栏",
        "搜索商品",
        "搜索输入",
        "搜索词",
        "搜索或输入网址",
        "地址栏",
        "网址",
        "目的地",
        "搜地点",
        "searchbox",
        "searchfield",
        "omnibox",
        "keyword",
    ).any { marker -> normalizedText.contains(marker.normalizedLookupKey()) }
}

private fun JSONObject.toOcrGroundingHint(observation: JSONObject): UiOcrGroundingHint? {
    if (optString("source") != "ocr") return null
    val text = optString("text").takeIf { value -> value.isNotBlank() } ?: return null
    val bounds = optJSONObject("bounds")?.toScreenBoundsOrNull() ?: return null
    if (bounds.width() <= 0 || bounds.height() <= 0) return null
    return UiOcrGroundingHint(
        observationId = observation.optString("observationId", "unknown"),
        packageName = observation.optNullableString("packageName"),
        capturedAtMillis = observation.optLong("capturedAtMillis").takeIf { value -> value > 0L },
        elementId = optString("id", "ocr"),
        text = text,
        bounds = bounds,
    )
}

private fun JSONObject.toScreenBoundsOrNull(): ScreenBounds? {
    if (!has("left") || !has("top") || !has("right") || !has("bottom")) return null
    return ScreenBounds(
        left = optInt("left"),
        top = optInt("top"),
        right = optInt("right"),
        bottom = optInt("bottom"),
    )
}

private fun List<UiOcrGroundingHint>.bestForTarget(normalizedTarget: String): UiOcrGroundingHint? {
    val idMatches = filter { hint -> hint.elementId.normalizedLookupKey() == normalizedTarget }
    if (idMatches.isNotEmpty()) {
        return idMatches.maxByOrNull { hint -> hint.scoreForTarget(normalizedTarget) }
    }
    val textMatches = filter { hint -> hint.textMatchesTarget(normalizedTarget) }
    if (textMatches.size != 1) return null
    return textMatches.single()
}

private fun UiOcrGroundingHint.textMatchesTarget(normalizedTarget: String): Boolean {
    val normalizedText = text.normalizedLookupKey()
    return normalizedText == normalizedTarget ||
        normalizedText.contains(normalizedTarget) ||
        normalizedTarget.contains(normalizedText)
}

private fun UiOcrGroundingHint.scoreForTarget(normalizedTarget: String): Int {
    val normalizedText = text.normalizedLookupKey()
    val normalizedId = elementId.normalizedLookupKey()
    return when {
        normalizedId == normalizedTarget -> 1_400
        normalizedText == normalizedTarget -> 1_000
        normalizedText.contains(normalizedTarget) -> 800 - (normalizedText.length - normalizedTarget.length).coerceAtLeast(0)
        normalizedTarget.contains(normalizedText) -> 620 - (normalizedTarget.length - normalizedText.length).coerceAtLeast(0)
        else -> 0
    } + bounds.width().coerceAtMost(200) + bounds.height().coerceAtMost(120)
}

private val SUBMIT_SEARCH_OCR_HINTS = listOf(
    "提交搜索",
    "搜索",
    "查找",
    "前往",
    "转到",
    "search",
    "go",
    "enter",
).map { value -> value.normalizedLookupKey() }

private fun UiOcrGroundingHint.matchesSubmitSearchTarget(): Boolean {
    val normalizedText = text.normalizedLookupKey()
    if (normalizedText.isBlank()) return false
    return normalizedText in SUBMIT_SEARCH_OCR_HINTS
}

private fun UiOcrGroundingHint.scoreForSubmitSearchTarget(): Int {
    val normalizedText = text.normalizedLookupKey()
    val matchScore = when {
        normalizedText in SUBMIT_SEARCH_OCR_HINTS -> 1_200
        else -> 0
    }
    val rightBias = (bounds.left / 20).coerceIn(0, 120)
    val topBias = if (bounds.top <= 300) 80 else 0
    val areaPenalty = (bounds.area() / 1_000).coerceAtMost(500)
    val widthPenalty = (bounds.width() / 8).coerceAtMost(250)
    return matchScore + rightBias + topBias - areaPenalty - widthPenalty
}

private fun UiOcrGroundingHint.scoreForSearchEntryTarget(): Int {
    val normalizedText = text.normalizedLookupKey()
    val contextScore = if (hasSearchContextText()) 1_200 else 0
    val topBias = if (bounds.top <= 360) 90 else 0
    val leftBias = ((10_000 - bounds.left.coerceIn(0, 10_000)) / 100).coerceIn(0, 80)
    val widthScore = (bounds.width() / 8).coerceAtMost(240)
    val shortTextPenalty = if (normalizedText.length <= 2) 220 else 0
    return contextScore + topBias + leftBias + widthScore - shortTextPenalty
}

private fun ScreenBounds.area(): Int =
    boundsDimension(width()) * boundsDimension(height())

private fun boundsDimension(value: Int): Int =
    value.coerceAtLeast(0).coerceAtMost(10_000)

private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { value -> value.isNotBlank() }

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

class ValidatingToolExecutor(
    private val delegate: ToolExecutor,
    private val registry: ToolRegistry = ToolRegistry(),
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
}

class ForegroundAppToolExecutor(
    private val provider: ForegroundAppProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.QUERY_FOREGROUND_APP) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        return when (val result = provider.currentForegroundApp()) {
            is ForegroundAppReadResult.Available ->
                request.succeeded(
                    summary = "当前前台应用估计：${result.appInfo.appLabel}",
                    data = request.localOnlyData() + mapOf(
                        "source" to result.appInfo.source,
                        "confidence" to result.appInfo.confidence,
                        "packageName" to result.appInfo.packageName,
                        "appLabel" to result.appInfo.appLabel,
                        "lastTimeUsedMillis" to result.appInfo.lastTimeUsedMillis.toString(),
                    ),
                )

            is ForegroundAppReadResult.PermissionDenied ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "需要“查看应用使用情况”权限来查询前台应用",
                    retryable = true,
                    data = request.localOnlyData() + mapOf(
                        "specialAccess" to "usage_stats",
                        "settingsAction" to Settings.ACTION_USAGE_ACCESS_SETTINGS,
                        "recoveryToolName" to MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
                    ),
                )

            is ForegroundAppReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "查询前台应用失败：${result.reason}",
                    retryable = true,
                    data = request.localOnlyData(),
                )
        }
    }
}

class ContactSummaryToolExecutor(
    private val provider: ContactSummaryProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.QUERY_CONTACTS) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        val query = request.arguments["query"]?.trim().orEmpty()
        val maxCount = (request.arguments["maxCount"]?.trim()?.toIntOrNull() ?: 5)
            .coerceIn(1, MAX_CONTACT_SUMMARY_COUNT)
        return when (val result = provider.queryContacts(query, maxCount)) {
            is ContactSummaryReadResult.Available ->
                request.succeeded(
                    summary = "已查询到 ${result.items.size} 个联系人。",
                    data = request.localOnlyData() + mapOf(
                        "query" to query,
                        "maxCount" to maxCount.toString(),
                        "contactCount" to result.items.size.toString(),
                        "contactsJson" to result.items.toContactsJsonString(),
                    ),
                )

            is ContactSummaryReadResult.PermissionDenied ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "未授权“读取联系人”权限，无法查询联系人",
                    retryable = true,
                    data = request.localOnlyData(),
                )

            is ContactSummaryReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "联系人查询失败：${result.reason}",
                    retryable = true,
                    data = request.localOnlyData(),
                )
        }
    }
}

class NotificationSummaryToolExecutor(
    private val provider: NotificationSummaryProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        val maxCount = (request.arguments["maxCount"]?.trim()?.toIntOrNull() ?: 5)
            .coerceIn(1, MAX_NOTIFICATION_SUMMARY_COUNT)
        return when (val result = provider.recentNotifications(maxCount)) {
            is NotificationSummaryReadResult.Available ->
                request.succeeded(
                    summary = "已读取 ${result.items.size} 条最近通知。",
                    data = request.localOnlyData() + mapOf(
                        "maxCount" to maxCount.toString(),
                        "notificationCount" to result.items.size.toString(),
                        "notificationsJson" to result.items.toJsonString(),
                    ),
                )

            is NotificationSummaryReadResult.PermissionDenied ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "未开启应用通知权限，无法读取通知摘要",
                    retryable = true,
                    data = request.localOnlyData(),
                )

            is NotificationSummaryReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "通知摘要查询失败：${result.reason}",
                    retryable = true,
                    data = request.localOnlyData(),
                )
        }
    }

    private fun List<NotificationSummaryItem>.toJsonString(): String {
        val notificationsArray = JSONArray()
        forEach { item ->
            notificationsArray.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("isOngoing", item.isOngoing)
                    .put("postTimeMillis", item.postTimeMillis),
            )
        }
        return notificationsArray.toString()
    }
}

class RecentFilesToolExecutor(
    private val provider: RecentFileProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.QUERY_RECENT_FILES) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        val kind = request.arguments["kind"]?.trim().orEmpty().ifBlank { "all" }
        val maxCount = request.arguments["maxCount"]?.trim()?.toIntOrNull() ?: 5
        return when (val result = provider.recentFiles(kind, maxCount)) {
            is RecentFileReadResult.Available ->
                request.succeeded(
                    summary = "已读取 ${result.items.size} 个最近文件。",
                    data = request.localOnlyData() + mapOf(
                        "kind" to kind.ifBlank { "all" },
                        "maxCount" to maxCount.toString(),
                        "mediaAccessScope" to result.mediaAccessScope,
                        "fileCount" to result.items.size.toString(),
                        "filesJson" to result.items.toRecentFilesJsonString(),
                    ),
                )

            is RecentFileReadResult.PermissionDenied ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "无法读取最近文件：${result.reason}",
                    retryable = result.retryable,
                    data = request.localOnlyData(),
                )

            is RecentFileReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "最近文件查询失败：${result.reason}",
                    retryable = true,
                    data = request.localOnlyData(),
                )
        }
    }
}

class BackgroundTasksToolExecutor(
    private val scheduler: BackgroundTaskScheduler,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.QUERY_BACKGROUND_TASKS) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        val scope = request.arguments["scope"]?.trim()?.lowercase().orEmpty().ifBlank { "active" }
        val maxCount = (request.arguments["maxCount"]?.trim()?.toIntOrNull() ?: DEFAULT_BACKGROUND_TASK_QUERY_COUNT)
            .coerceIn(1, MAX_BACKGROUND_TASK_QUERY_COUNT)

        val activeTasks = if (scope == "active" || scope == "all") {
            scheduler.scheduledTasks(maxCount)
        } else {
            emptyList()
        }
        val historyTasks = if (scope == "history" || scope == "all") {
            scheduler.recentTasks(maxCount)
        } else {
            emptyList()
        }
        val policy = if (scope == "policy" || scope == "all") {
            scheduler.periodicCheckPolicy()
        } else {
            null
        }

        val includedTasks = JSONArray().apply {
            activeTasks.appendToBackgroundTasksJson(scope = "active", target = this)
            historyTasks.appendToBackgroundTasksJson(scope = "history", target = this)
        }
        val summary = when (scope) {
            "history" -> "已读取 ${historyTasks.size} 条后台任务历史元数据。"
            "policy" -> "已读取本地提醒周期检查策略。"
            "all" -> "已读取 ${activeTasks.size} 个活动后台任务元数据、${historyTasks.size} 条历史元数据与周期检查策略。"
            else -> "已读取 ${activeTasks.size} 个活动后台任务元数据。"
        }
        return request.succeeded(
            summary = summary,
            data = buildMap {
                putAll(request.localOnlyData())
                put("scope", scope)
                put("source", "local_store")
                put("maxCount", maxCount.toString())
                put("metadataPolicy", BACKGROUND_TASK_METADATA_POLICY)
                put("rawPayloadIncluded", false.toString())
                if (scope == "active" || scope == "all") {
                    put("activeTaskCount", activeTasks.size.toString())
                }
                if (scope == "history" || scope == "all") {
                    put("historyTaskCount", historyTasks.size.toString())
                }
                if (includedTasks.length() > 0 || scope != "policy") {
                    put("tasksJson", includedTasks.toString())
                }
                policy?.let { put("policyJson", it.toJsonString()) }
            },
        )
    }
}

class RecentScreenshotOcrToolExecutor(
    private val provider: RecentImageTextProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        val config = recentImageOcrToolConfigFor(request.toolName) ?: return request.failed(
            code = ToolErrorCode.UnknownTool,
            summary = "Unknown tool: ${request.toolName}",
            retryable = false,
        )

        val maxCount = (request.arguments["maxCount"]?.trim()?.toIntOrNull() ?: config.defaultMaxCount)
            .coerceIn(1, config.maxMaxCount)
        return when (val result = provider.extractRecentImageText(kind = config.kind, maxCount = maxCount)) {
            is RecentImageTextReadResult.Available -> {
                val item = result.item
                if (item == null) {
                    request.succeeded(
                        summary = "未能在最近 ${result.scannedCount} 张${config.sourceLabel}中识别出文字。",
                        data = request.localOnlyData() + mapOf(
                            "source" to config.kind,
                            "maxCount" to maxCount.toString(),
                            "scannedCount" to result.scannedCount.toString(),
                            "mediaAccessScope" to result.mediaAccessScope,
                            "ocrTextIncluded" to false.toString(),
                            "rawPayloadIncluded" to false.toString(),
                            "metadataPolicy" to "no_uri_path_or_pixels_persisted",
                        ),
                    )
                } else {
                    request.succeeded(
                        summary = "已从最近${config.sourceLabel}提取 ${item.text.length} 个字符的本地 OCR 摘录。",
                        data = request.localOnlyData() + mapOf(
                            "source" to config.kind,
                            "maxCount" to maxCount.toString(),
                            "scannedCount" to result.scannedCount.toString(),
                            "mediaAccessScope" to result.mediaAccessScope,
                            "ocrText" to item.text,
                            "truncated" to item.truncated.toString(),
                            "ocrTextIncluded" to true.toString(),
                            "rawPayloadIncluded" to false.toString(),
                            "metadataPolicy" to "ocr_text_local_only_no_uri_path_or_pixels_persisted",
                        ),
                    )
                }
            }

            is RecentImageTextReadResult.PermissionDenied ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "未授权“读取图片”权限，无法识别最近${config.sourceLabel}文字",
                    retryable = true,
                    data = request.localOnlyData(),
                )

            is RecentImageTextReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "最近${config.sourceLabel} OCR 失败",
                    retryable = true,
                    data = request.localOnlyData(),
                )
        }
    }

    private data class RecentImageOcrToolConfig(
        val kind: String,
        val sourceLabel: String,
        val defaultMaxCount: Int,
        val maxMaxCount: Int,
    )

    private fun recentImageOcrToolConfigFor(toolName: String): RecentImageOcrToolConfig? =
        when (toolName) {
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR -> RecentImageOcrToolConfig(
                kind = "screenshots",
                sourceLabel = "截图",
                defaultMaxCount = 1,
                maxMaxCount = 1,
            )

            MobileActionFunctions.READ_RECENT_IMAGE_OCR -> RecentImageOcrToolConfig(
                kind = "images",
                sourceLabel = "图片",
                defaultMaxCount = 3,
                maxMaxCount = 3,
            )

            else -> null
        }
}

class CurrentScreenTextToolExecutor(
    private val provider: CurrentScreenTextProvider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.READ_CURRENT_SCREEN_TEXT) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        val maxChars = (request.arguments["maxChars"]?.trim()?.toIntOrNull() ?: DEFAULT_MAX_SCREEN_TEXT_CHARS)
            .coerceIn(1, MAX_SCREEN_TEXT_CHARS)
        return when (val result = provider.currentScreenText(maxChars)) {
            is CurrentScreenTextReadResult.Available -> {
                val snapshot = result.snapshot
                if (snapshot.text.isBlank()) {
                    request.succeeded(
                        summary = "当前屏幕未暴露可访问文本。",
                        data = request.localOnlyData() + mapOf(
                            "source" to "accessibility_active_window",
                            "maxChars" to maxChars.toString(),
                            "capturedAtMillis" to snapshot.capturedAtMillis.toString(),
                            "nodeCount" to snapshot.nodeCount.toString(),
                            "truncated" to snapshot.truncated.toString(),
                            "screenTextIncluded" to false.toString(),
                            "rawTreeIncluded" to false.toString(),
                            "metadataPolicy" to "accessibility_text_local_only_no_node_ids_bounds_or_hierarchy_persisted",
                        ) + snapshot.packageNameData() + snapshot.structureSummaryData(),
                    )
                } else {
                    request.succeeded(
                        summary = "已读取当前屏幕 ${snapshot.text.length} 个字符的可访问文本快照。",
                        data = request.localOnlyData() + mapOf(
                            "source" to "accessibility_active_window",
                            "maxChars" to maxChars.toString(),
                            "capturedAtMillis" to snapshot.capturedAtMillis.toString(),
                            "nodeCount" to snapshot.nodeCount.toString(),
                            "screenText" to snapshot.text,
                            "truncated" to snapshot.truncated.toString(),
                            "screenTextIncluded" to true.toString(),
                            "rawTreeIncluded" to false.toString(),
                            "metadataPolicy" to "accessibility_text_local_only_no_node_ids_bounds_or_hierarchy_persisted",
                        ) + snapshot.packageNameData() + snapshot.structureSummaryData(),
                    )
                }
            }

            is CurrentScreenTextReadResult.PermissionDenied ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "需要开启Solin无障碍服务才能读取当前屏幕文本",
                    retryable = true,
                    data = request.localOnlyData() + mapOf(
                        "specialAccess" to "accessibility_screen_text",
                        "settingsAction" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
                    ),
                )

            is CurrentScreenTextReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "当前屏幕文本读取失败",
                    retryable = true,
                    data = request.localOnlyData(),
                )
        }
    }

    private fun com.bytedance.zgx.solin.device.CurrentScreenTextSnapshot.packageNameData(): Map<String, String> =
        packageName?.takeIf { it.isNotBlank() }?.let { mapOf("packageName" to it) }.orEmpty()

    private fun com.bytedance.zgx.solin.device.CurrentScreenTextSnapshot.structureSummaryData(): Map<String, String> {
        val included = structureSummaryIncluded && structureSummary.isNotBlank()
        return mapOf("structureSummaryIncluded" to included.toString()) +
            structureSummary.takeIf { included }?.let { mapOf("structureSummary" to it) }.orEmpty()
    }

    private companion object {
        const val DEFAULT_MAX_SCREEN_TEXT_CHARS = 2_000
        const val MAX_SCREEN_TEXT_CHARS = 4_000
    }
}

class DeviceControlToolExecutor(
    private val provider: CurrentScreenControlProvider?,
    private val preflightProvider: CurrentScreenControlProvider? = provider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        val controlProvider = provider
            ?: return request.failed(
                code = ToolErrorCode.ExecutionFailed,
                summary = "当前屏幕控制服务不可用",
                retryable = true,
                data = request.deviceControlBaseData(),
            )
        return when (request.toolName) {
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN -> executeObserve(request, controlProvider)
            MobileActionFunctions.UI_TAP -> executeTap(request, controlProvider)
            MobileActionFunctions.UI_TYPE_TEXT -> executeTypeText(request, controlProvider)
            MobileActionFunctions.UI_SUBMIT_SEARCH -> executeSubmitSearch(request, controlProvider)
            MobileActionFunctions.UI_SCROLL -> executeScroll(request, controlProvider)
            MobileActionFunctions.UI_PRESS_BACK -> executePressBack(request, controlProvider)
            MobileActionFunctions.UI_WAIT -> executeWait(request, controlProvider)
            else -> request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }
    }

    private fun executeObserve(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        val maxTextChars = request.arguments["maxTextChars"]?.toIntOrNull() ?: DEFAULT_MAX_SCREEN_STATE_TEXT_CHARS
        val maxNodes = request.arguments["maxNodes"]?.toIntOrNull() ?: DEFAULT_MAX_SCREEN_STATE_NODES
        return when (val result = provider.observeCurrentScreen(maxTextChars = maxTextChars, maxNodes = maxNodes)) {
            is ScreenStateReadResult.Available ->
                request.succeeded(
                    summary = result.snapshot.observationSummary(),
                    data = request.deviceControlBaseData() + result.snapshot.toObservationData(
                        requestedMaxTextChars = maxTextChars,
                        requestedMaxNodes = maxNodes,
                    ),
                )

            is ScreenStateReadResult.PermissionDenied ->
                request.deviceControlPermissionDenied(result.reason)

            is ScreenStateReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = result.reason,
                    retryable = true,
                    data = request.deviceControlBaseData() + mapOf(
                        "failureKind" to result.failureKind.schemaValue,
                    ),
                )
        }
    }

    private fun executeTap(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        val target = request.arguments["target"].orEmpty()
        expectedForegroundPackagePreflight(request, actionType = "tap", target = target)?.let { return it }
        dangerousUiActionPreflight(request, actionType = "tap", target = target)?.let { return it }
        return actionResult(
            request = request,
            actionType = "tap",
            target = target,
            result = provider.tap(
                target = target,
                timeoutMillis = request.timeoutMillis(),
            ).withExpectedForegroundPackageVerification(request),
        )
    }

    private fun executeTypeText(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        val target = request.arguments["target"].orEmpty()
        expectedForegroundPackagePreflight(request, actionType = "type_text", target = target)?.let { return it }
        dangerousUiActionPreflight(request, actionType = "type_text", target = target)?.let { return it }
        return actionResult(
            request = request,
            actionType = "type_text",
            target = target,
            result = provider.typeText(
                text = request.arguments["text"].orEmpty(),
                target = request.arguments["target"],
                timeoutMillis = request.timeoutMillis(),
                allowClipboardPasteFallback = request.allowClipboardPasteFallback(),
            ).withExpectedForegroundPackageVerification(request),
        )
    }

    private fun executeSubmitSearch(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        expectedForegroundPackagePreflight(request, actionType = "submit_search", target = "")?.let { return it }
        dangerousUiActionPreflight(request, actionType = "submit_search", target = "")?.let { return it }
        return actionResult(
            request = request,
            actionType = "submit_search",
            target = "",
            result = provider.submitSearch(
                timeoutMillis = request.timeoutMillis(),
            ).withExpectedForegroundPackageVerification(request),
        )
    }

    private fun executeScroll(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        val direction = UiScrollDirection.fromSchemaValue(request.arguments["direction"])
            ?: return request.failed(
                code = ToolErrorCode.InvalidRequest,
                summary = "不支持的滚动方向：${request.arguments["direction"]}",
                retryable = false,
                data = request.deviceControlBaseData(),
            )
        val target = request.arguments["target"].orEmpty()
        expectedForegroundPackagePreflight(request, actionType = "scroll", target = target)?.let { return it }
        dangerousUiActionPreflight(request, actionType = "scroll", target = target)?.let { return it }
        return actionResult(
            request = request,
            actionType = "scroll",
            target = target,
            result = provider.scroll(
                direction = direction,
                target = request.arguments["target"],
                timeoutMillis = request.timeoutMillis(),
            ).withExpectedForegroundPackageVerification(request),
            extraData = mapOf("direction" to direction.schemaValue),
        )
    }

    private fun expectedForegroundPackagePreflight(
        request: ToolRequest,
        actionType: String,
        target: String,
    ): ToolResult? {
        val expectedPackage = request.expectedForegroundPackageName() ?: return null
        return when (
            val result = preflightProvider?.observeCurrentScreen(
                maxTextChars = DEFAULT_MAX_SCREEN_STATE_TEXT_CHARS,
                maxNodes = DEFAULT_MAX_SCREEN_STATE_NODES,
            )
        ) {
            is ScreenStateReadResult.Available -> {
                val snapshot = result.snapshot
                if (snapshot.packageName == expectedPackage) {
                    null
                } else {
                    request.foregroundPackageGateFailure(
                        actionType = actionType,
                        target = target,
                        expectedPackage = expectedPackage,
                        actualPackage = snapshot.packageName,
                        snapshot = snapshot,
                    )
                }
            }

            is ScreenStateReadResult.PermissionDenied ->
                request.deviceControlPermissionDenied(result.reason)

            is ScreenStateReadResult.Failed ->
                request.foregroundPackageGateFailure(
                    actionType = actionType,
                    target = target,
                    expectedPackage = expectedPackage,
                    actualPackage = null,
                    snapshot = null,
                    summary = "无法确认目标应用仍在前台，已停止自动 UI 动作。",
                )

            null ->
                request.foregroundPackageGateFailure(
                    actionType = actionType,
                    target = target,
                    expectedPackage = expectedPackage,
                    actualPackage = null,
                    snapshot = null,
                    summary = "当前屏幕控制 preflight 不可用，无法确认目标应用仍在前台。",
                )
        }
    }

    private fun dangerousUiActionPreflight(
        request: ToolRequest,
        actionType: String,
        target: String,
    ): ToolResult? {
        val snapshot = when (
            val result = preflightProvider?.observeCurrentScreen(
                maxTextChars = DEFAULT_MAX_SCREEN_STATE_TEXT_CHARS,
                maxNodes = DEFAULT_MAX_SCREEN_STATE_NODES,
            )
        ) {
            is ScreenStateReadResult.Available -> result.snapshot
            else -> return null
        }
        if (!snapshot.hasDangerousActionControl()) return null
        return request.failed(
            code = ToolErrorCode.ExecutionFailed,
            summary = "当前屏幕包含支付、发送、删除、发布、下单、购买、转账或授权类控件，已停止自动 UI 动作。",
            retryable = false,
            data = (
                request.deviceControlBaseData() +
                mapOf(
                    "actionType" to actionType,
                    "status" to UiActionStatus.Failed.schemaValue(),
                    "retryable" to false.toString(),
                    "summary" to "dangerous_ui_action_control_detected",
                    "failureKind" to UiActionFailureKind.DangerousAction.schemaValue,
                    "beforeObservationId" to snapshot.id,
                    "afterObservationId" to "",
                    "verificationSummary" to "动作前检测到危险控件，未执行 UI 动作。",
                    "screenObservationDiffSummary" to "blocked_before_execution;reason=dangerous_action_control",
                ) +
                target.takeIf { it.isNotBlank() }?.let { mapOf("target" to it) }.orEmpty() +
                snapshot.toBeforeObservationData()
            ).withAppSearchProgressEvidence(),
        )
    }

    private fun executePressBack(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult =
        actionResult(
            request = request,
            actionType = "press_back",
            target = "",
            result = provider.pressBack(timeoutMillis = request.timeoutMillis()),
        )

    private fun executeWait(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        val result = provider.waitForScreen(timeoutMillis = request.timeoutMillis())
            .withExpectedForegroundPackageVerification(request)
        val verification = result.searchVerificationFor(request)
        return actionResult(
            request = request,
            actionType = "wait",
            target = "",
            result = result.withSearchVerification(verification),
            extraData = verification.toData(),
        )
    }

    private fun actionResult(
        request: ToolRequest,
        actionType: String,
        target: String,
        result: UiActionReadResult,
        extraData: Map<String, String> = emptyMap(),
    ): ToolResult =
        when (result) {
            is UiActionReadResult.Available -> {
                val execution = result.result
                val after = execution.after
                val data = (
                    request.deviceControlBaseData() +
                    mapOf(
                        "actionType" to actionType,
                        "status" to execution.status.schemaValue(),
                        "retryable" to execution.retryable.toString(),
                        "summary" to execution.summary,
                        "beforeObservationId" to execution.before?.id.orEmpty(),
                        "afterObservationId" to after?.id.orEmpty(),
                        "verificationSummary" to (after?.observationSummary() ?: "动作后未能读取屏幕状态"),
                        "screenObservationDiffSummary" to screenObservationDiffSummary(
                            before = execution.before,
                            after = after,
                        ),
                    ) +
                    target.takeIf { it.isNotBlank() }?.let { mapOf("target" to it) }.orEmpty() +
                    execution.failureKind?.let { mapOf("failureKind" to it.schemaValue) }.orEmpty() +
                    execution.before?.toBeforeObservationData().orEmpty() +
                    after?.toAfterObservationData().orEmpty() +
                    extraData
                    ).withAppSearchProgressEvidence()
                if (execution.status == UiActionStatus.Succeeded) {
                    request.succeeded(
                        summary = execution.summary,
                        data = data,
                    )
                } else {
                    request.failed(
                        code = ToolErrorCode.ExecutionFailed,
                        summary = execution.summary,
                        retryable = execution.retryable,
                        data = data,
                    )
                }
            }

            is UiActionReadResult.PermissionDenied ->
                request.deviceControlPermissionDenied(result.reason)

            is UiActionReadResult.Failed ->
                request.failed(
                    code = if (result.failureKind == UiActionFailureKind.PermissionMissing) {
                        ToolErrorCode.PermissionDenied
                    } else {
                        ToolErrorCode.ExecutionFailed
                    },
                    summary = result.reason,
                    retryable = result.retryable,
                    data = (
                        request.deviceControlBaseData() + mapOf(
                            "actionType" to actionType,
                            "status" to UiActionStatus.Failed.schemaValue(),
                            "retryable" to result.retryable.toString(),
                            "summary" to result.reason,
                            "failureKind" to result.failureKind.schemaValue,
                        ) + target.takeIf { it.isNotBlank() }?.let { mapOf("target" to it) }.orEmpty()
                    ).withAppSearchProgressEvidence(),
                )
        }

    private fun ToolRequest.timeoutMillis(): Long =
        arguments["timeoutMillis"]?.trim()?.toLongOrNull() ?: DEFAULT_UI_ACTION_TIMEOUT_MILLIS

    private fun ToolRequest.allowClipboardPasteFallback(): Boolean =
        arguments["allowClipboardPasteFallback"]?.toBooleanStrictOrNull() == true

    private fun ToolRequest.expectedForegroundPackageName(): String? =
        arguments["expectedPackageName"]?.trim()?.takeIf { it.isNotBlank() }
            ?: arguments["targetPackageName"]?.trim()?.takeIf { it.isNotBlank() }

    private fun ToolRequest.foregroundPackageGateFailure(
        actionType: String,
        target: String,
        expectedPackage: String,
        actualPackage: String?,
        snapshot: ScreenStateSnapshot?,
        summary: String = "目标应用未保持在前台，已停止自动 UI 动作。",
    ): ToolResult =
        failed(
            code = ToolErrorCode.ExecutionFailed,
            summary = summary,
            retryable = false,
            data = (
                deviceControlBaseData() +
                mapOf(
                    "actionType" to actionType,
                    "status" to UiActionStatus.Failed.schemaValue(),
                    "retryable" to false.toString(),
                    "summary" to "foreground_package_mismatch",
                    "failureKind" to UiActionFailureKind.AppNotForeground.schemaValue,
                    "expectedPackageName" to expectedPackage,
                    "actualPackageName" to actualPackage.orEmpty(),
                    "beforeObservationId" to snapshot?.id.orEmpty(),
                    "afterObservationId" to snapshot?.id.orEmpty(),
                    "verificationSummary" to summary,
                    "screenObservationDiffSummary" to "blocked_before_execution;reason=app_not_foreground",
                ) +
                target.takeIf { it.isNotBlank() }?.let { mapOf("target" to it) }.orEmpty() +
                snapshot?.toBeforeObservationData().orEmpty() +
                snapshot?.toAfterObservationData().orEmpty()
            ).withAppSearchProgressEvidence(),
        )

    private fun UiActionReadResult.withExpectedForegroundPackageVerification(
        request: ToolRequest,
    ): UiActionReadResult {
        val expectedPackage = request.expectedForegroundPackageName() ?: return this
        if (this !is UiActionReadResult.Available) return this
        if (result.status != UiActionStatus.Succeeded) return this
        val afterPackage = result.after?.packageName
        if (afterPackage == expectedPackage) return this
        return copy(
            result = result.copy(
                status = UiActionStatus.Failed,
                summary = "目标应用未保持在前台，已停止自动 UI 动作。",
                retryable = false,
                failureKind = UiActionFailureKind.AppNotForeground,
            ),
        )
    }

    private fun UiActionReadResult.searchVerificationFor(request: ToolRequest): SearchResultVerification? {
        val query = request.arguments["verifySearchQuery"]?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val execution = (this as? UiActionReadResult.Available)?.result ?: return null
        return AppSearchResultVerifier.verify(
            before = execution.before,
            after = execution.after,
            query = query,
            expectedPackageName = request.expectedForegroundPackageName(),
            expectedAppName = request.arguments["expectedAppName"],
        )
    }

    private fun UiActionReadResult.withSearchVerification(
        verification: SearchResultVerification?,
    ): UiActionReadResult {
        verification ?: return this
        if (this !is UiActionReadResult.Available) return this
        if (result.status != UiActionStatus.Succeeded) return this
        if (verification.verified) {
            return copy(
                result = result.copy(
                    summary = verification.summary,
                ),
            )
        }
        return copy(
            result = result.copy(
                status = UiActionStatus.Failed,
                summary = verification.summary,
                retryable = true,
                failureKind = verification.failureKind ?: UiActionFailureKind.ResultNotVerified,
            ),
        )
    }

    private fun SearchResultVerification?.toData(): Map<String, String> =
        this?.let { verification ->
            mapOf(
                "searchVerificationStatus" to if (verification.verified) "verified" else "not_verified",
                "searchVerificationEvidence" to verification.evidence,
            )
        }.orEmpty()

    private fun ToolRequest.deviceControlPermissionDenied(reason: String): ToolResult =
        failed(
            code = ToolErrorCode.PermissionDenied,
            summary = reason.ifBlank { "需要开启Solin无障碍服务才能控制当前屏幕" },
            retryable = true,
            data = (
                deviceControlBaseData() + mapOf(
                    "specialAccess" to SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL,
                    "settingsAction" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
                    "failureKind" to UiActionFailureKind.PermissionMissing.schemaValue,
                )
            ).withAppSearchProgressEvidence(),
        )

    private fun ToolRequest.deviceControlBaseData(): Map<String, String> =
        localOnlyData() + mapOf(
            "source" to DEVICE_CONTROL_SOURCE_ACCESSIBILITY,
            "metadataPolicy" to DEVICE_CONTROL_METADATA_POLICY,
        )

    private fun Map<String, String>.withAppSearchProgressEvidence(): Map<String, String> =
        this + AppSearchProgressEvidence.fromData(this).toData()

    private fun ScreenStateSnapshot.toObservationData(
        requestedMaxTextChars: Int,
        requestedMaxNodes: Int,
    ): Map<String, String> =
        mapOf(
            "observationId" to id,
            "capturedAtMillis" to capturedAtMillis.toString(),
            "nodeCount" to nodeCount.toString(),
            "actionableNodeCount" to actionableNodeCount.toString(),
            "textSummary" to textSummary,
            "truncated" to truncated.toString(),
            "nodesJson" to nodes.toScreenNodesJsonString(),
            "screenObservationJson" to toScreenObservationJsonString(),
            "maxTextChars" to requestedMaxTextChars.toString(),
            "maxNodes" to requestedMaxNodes.toString(),
        ) + packageName?.takeIf { it.isNotBlank() }?.let { mapOf("packageName" to it) }.orEmpty()

    private fun ScreenStateSnapshot.toAfterObservationData(): Map<String, String> =
        mapOf(
            "afterPackageName" to packageName.orEmpty(),
            "afterCapturedAtMillis" to capturedAtMillis.toString(),
            "afterNodeCount" to nodeCount.toString(),
            "afterActionableNodeCount" to actionableNodeCount.toString(),
            "afterTextSummary" to textSummary,
            "afterTruncated" to truncated.toString(),
            "afterNodesJson" to nodes.toScreenNodesJsonString(),
            "afterScreenObservationJson" to toScreenObservationJsonString(),
        )

    private fun ScreenStateSnapshot.toBeforeObservationData(): Map<String, String> =
        mapOf(
            "beforePackageName" to packageName.orEmpty(),
            "beforeCapturedAtMillis" to capturedAtMillis.toString(),
            "beforeNodeCount" to nodeCount.toString(),
            "beforeActionableNodeCount" to actionableNodeCount.toString(),
            "beforeTextSummary" to textSummary,
            "beforeTruncated" to truncated.toString(),
            "beforeNodesJson" to nodes.toScreenNodesJsonString(),
            "beforeScreenObservationJson" to toScreenObservationJsonString(),
        )

    private fun ScreenStateSnapshot.observationSummary(): String {
        val packagePart = packageName?.takeIf { it.isNotBlank() }?.let { "包名 $it，" }.orEmpty()
        return "已观察当前屏幕：${packagePart}${nodeCount} 个节点，${actionableNodeCount} 个可交互节点。"
    }

    private fun screenObservationDiffSummary(
        before: ScreenStateSnapshot?,
        after: ScreenStateSnapshot?,
    ): String {
        if (before == null && after == null) return "before_after_unavailable"
        if (before == null) return "before_unavailable;afterNodes=${after?.nodeCount ?: 0}"
        if (after == null) return "after_unavailable;beforeNodes=${before.nodeCount}"
        val beforeTexts = before.nodes.mapNotNull { node -> node.diffTextLabel() }.toOrderedSet()
        val afterTexts = after.nodes.mapNotNull { node -> node.diffTextLabel() }.toOrderedSet()
        val beforeActions = before.nodes.mapNotNull { node -> node.diffActionableLabel() }.toOrderedSet()
        val afterActions = after.nodes.mapNotNull { node -> node.diffActionableLabel() }.toOrderedSet()
        val addedTexts = (afterTexts - beforeTexts).take(6)
        val removedTexts = (beforeTexts - afterTexts).take(6)
        val addedActions = (afterActions - beforeActions).take(4)
        val removedActions = (beforeActions - afterActions).take(4)
        val changed = before.packageName != after.packageName ||
            before.nodeCount != after.nodeCount ||
            before.actionableNodeCount != after.actionableNodeCount ||
            addedTexts.isNotEmpty() ||
            removedTexts.isNotEmpty() ||
            addedActions.isNotEmpty() ||
            removedActions.isNotEmpty()
        return buildString {
            append("changed=")
            append(changed)
            append(";package=")
            append(before.packageName.orEmpty().ifBlank { "unknown" })
            append("->")
            append(after.packageName.orEmpty().ifBlank { "unknown" })
            append(";nodes=")
            append(before.nodeCount)
            append("->")
            append(after.nodeCount)
            append(";actionable=")
            append(before.actionableNodeCount)
            append("->")
            append(after.actionableNodeCount)
            append(";addedText=")
            append(addedTexts.joinToStringLimited().ifBlank { "none" })
            append(";removedText=")
            append(removedTexts.joinToStringLimited().ifBlank { "none" })
            append(";addedActionable=")
            append(addedActions.joinToStringLimited().ifBlank { "none" })
            append(";removedActionable=")
            append(removedActions.joinToStringLimited().ifBlank { "none" })
        }
    }

    private fun List<String>.toOrderedSet(): Set<String> =
        map { value -> value.trim() }
            .filter { value -> value.isNotBlank() }
            .toCollection(linkedSetOf())

    private fun List<String>.joinToStringLimited(): String =
        joinToString(separator = "|") { value -> value.take(64) }

    private fun ScreenNode.diffTextLabel(): String? =
        (text.ifBlank { contentDescription })
            .trim()
            .takeIf { value -> value.isNotBlank() }

    private fun ScreenNode.diffActionableLabel(): String? {
        if (!clickable && !editable && !scrollable) return null
        val label = diffTextLabel() ?: id.takeIf { value -> value.isNotBlank() } ?: className
        val role = when {
            editable -> "editable"
            clickable -> "clickable"
            scrollable -> "scrollable"
            else -> "actionable"
        }
        return "$role:$label"
    }

    private fun UiActionStatus.schemaValue(): String =
        when (this) {
            UiActionStatus.Succeeded -> "succeeded"
            UiActionStatus.Failed -> "failed"
        }

    private companion object {
        const val DEFAULT_MAX_SCREEN_STATE_TEXT_CHARS = 2_000
        const val DEFAULT_MAX_SCREEN_STATE_NODES = 50
        const val DEFAULT_UI_ACTION_TIMEOUT_MILLIS = 1_000L
    }
}

class CurrentScreenshotOcrToolExecutor(
    private val provider: CurrentScreenshotOcrProvider?,
    private val currentScreenControlProvider: CurrentScreenControlProvider? = null,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR) {
            return request.failed(
                code = ToolErrorCode.UnknownTool,
                summary = "Unknown tool: ${request.toolName}",
                retryable = false,
            )
        }

        val nowMillis = clockMillis()
        val screenObservationBeforeCapture = provider
            ?.takeIf { candidate -> candidate.hasOneShotConsent(request.id, nowMillis) }
            ?.let {
                currentScreenControlProvider?.observeCurrentScreen()
            }
        return when (val result = provider?.captureCurrentScreenshotOcr(request.id, nowMillis)) {
            null ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "当前屏幕截图 OCR 服务不可用",
                    retryable = true,
                    data = request.localOnlyData(),
                )

            CurrentScreenshotOcrReadResult.MissingConsent ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "需要完成 Android MediaProjection 前台同意后，才能单次截取当前屏幕并本地提取 OCR 文本。",
                    retryable = false,
                    data = request.localOnlyData() + mapOf(
                        "specialAccess" to CurrentScreenshotOcrContract.CONSENT_REASON,
                    ),
                )

            is CurrentScreenshotOcrReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = result.reason,
                    retryable = true,
                    data = request.currentScreenshotOcrBaseData(),
                )

            is CurrentScreenshotOcrReadResult.Available -> {
                val ocrText = result.text?.takeIf { it.isNotBlank() }
                val screenObservationData = currentScreenControlProvider.screenObservationData(
                    ocrBlocks = result.ocrBlocks,
                    beforeCapture = screenObservationBeforeCapture,
                )
                request.succeeded(
                    summary = if (ocrText == null) {
                        "已完成当前屏幕单次 OCR，未识别到可用文字。"
                    } else {
                        "已从当前屏幕单次截图提取 ${ocrText.length} 个字符的本地 OCR 摘录。"
                    },
                    data = request.currentScreenshotOcrBaseData() +
                        mapOf(
                            "truncated" to result.truncated.toString(),
                            "ocrTextIncluded" to (ocrText != null).toString(),
                        ) +
                        ocrText?.let { mapOf("ocrText" to it) }.orEmpty() +
                        result.ocrBlocks.takeIf { it.isNotEmpty() }
                            ?.let { mapOf("ocrBlocksJson" to it.toOcrBlocksJsonString()) }
                            .orEmpty() +
                        screenObservationData,
                )
            }
        }
    }

    private fun CurrentScreenControlProvider?.screenObservationData(
        ocrBlocks: List<OcrTextBlock>,
        beforeCapture: ScreenStateReadResult?,
    ): Map<String, String> {
        val provider = this ?: return mapOf(
            "screenObservationIncluded" to false.toString(),
            "screenObservationFailureKind" to "provider_unavailable",
        )
        return when (val beforeResult = beforeCapture ?: provider.observeCurrentScreen()) {
            is ScreenStateReadResult.Available ->
                when (val afterResult = provider.observeCurrentScreen()) {
                    is ScreenStateReadResult.Available -> {
                        val beforeSignature = beforeResult.snapshot.toOcrGroundingScreenSignature()
                        val afterSignature = afterResult.snapshot.toOcrGroundingScreenSignature()
                        if (beforeSignature != afterSignature) {
                            mapOf(
                                "screenObservationIncluded" to false.toString(),
                                "screenObservationFailureKind" to "page_changed",
                            )
                        } else {
                            mapOf(
                                "screenObservationIncluded" to true.toString(),
                                "screenObservationJson" to afterResult.snapshot.toScreenObservationJsonString(
                                    ocrBlocks = ocrBlocks,
                                ),
                            )
                        }
                    }

                    is ScreenStateReadResult.PermissionDenied ->
                        mapOf(
                            "screenObservationIncluded" to false.toString(),
                            "screenObservationFailureKind" to UiActionFailureKind.PermissionMissing.schemaValue,
                        )

                    is ScreenStateReadResult.Failed ->
                        mapOf(
                            "screenObservationIncluded" to false.toString(),
                            "screenObservationFailureKind" to afterResult.failureKind.schemaValue,
                        )
                }

            is ScreenStateReadResult.PermissionDenied ->
                mapOf(
                    "screenObservationIncluded" to false.toString(),
                    "screenObservationFailureKind" to UiActionFailureKind.PermissionMissing.schemaValue,
                )

            is ScreenStateReadResult.Failed ->
                mapOf(
                    "screenObservationIncluded" to false.toString(),
                    "screenObservationFailureKind" to beforeResult.failureKind.schemaValue,
                )
        }
    }
}

private fun ToolRequest.currentScreenshotOcrBaseData(): Map<String, String> =
    localOnlyData() + mapOf(
        "source" to CurrentScreenshotOcrContract.SOURCE,
        "captureMode" to CurrentScreenshotOcrContract.CAPTURE_MODE,
        "metadataPolicy" to CurrentScreenshotOcrContract.OUTPUT_METADATA_POLICY,
        "rawPayloadIncluded" to false.toString(),
    )

private fun List<ContactSummaryItem>.toContactsJsonString(): String {
    val contactsArray = JSONArray()
    forEach { item ->
        contactsArray.put(
            JSONObject()
                .put("name", item.name)
                .put("phone", item.phone),
        )
    }
    return contactsArray.toString()
}

private fun List<RecentFileItem>.toRecentFilesJsonString(): String {
    val filesArray = JSONArray()
    forEach { item ->
        filesArray.put(
            JSONObject()
                .put("name", item.name)
                .put("mimeType", item.mimeType)
                .put("kind", item.kind)
                .put("sizeBytes", item.sizeBytes)
                .put("lastModifiedMillis", item.lastModifiedMillis),
        )
    }
    return filesArray.toString()
}

private fun List<ScheduledTask>.appendToBackgroundTasksJson(
    scope: String,
    target: JSONArray,
) {
    forEach { task ->
        target.put(
            JSONObject()
                .put("scope", scope)
                .put("id", task.id)
                .put("type", task.type.name)
                .put("status", task.status.name)
                .put("triggerAtMillis", task.triggerAtMillis)
                .put("createdAtMillis", task.createdAtMillis)
                .put("updatedAtMillis", task.updatedAtMillis),
        )
    }
}

private fun PeriodicCheckPolicySummary.toJsonString(): String {
    val normalized = request.normalized()
    return JSONObject()
        .put("enabled", normalized.enabled)
        .put("intervalMinutes", normalized.intervalMinutes)
        .put("minNotificationSpacingMinutes", normalized.minNotificationSpacingMinutes)
        .put("overdueGraceMinutes", normalized.overdueGraceMinutes)
        .put("requiresBatteryNotLow", normalized.constraints.requiresBatteryNotLow)
        .put("requiresCharging", normalized.constraints.requiresCharging)
        .put("taskStatus", taskStatus?.name)
        .put("nextAllowedRunAtMillis", nextAllowedRunAtMillis)
        .put("updatedAtMillis", updatedAtMillis)
        .put("lastRunSummaryIncluded", false)
        .toString()
}

private fun ToolRequest.localOnlyData(): Map<String, String> =
    mapOf(
        "toolName" to toolName,
        "privacy" to MessagePrivacy.LocalOnly.name,
        "requiresLocalModel" to true.toString(),
    )

private fun List<com.bytedance.zgx.solin.device.CalendarAvailabilityBlock>.toJsonString(): String {
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

private fun ToolRequest.toolExecutionContext(): Map<String, String> =
    mapOf("toolName" to toolName)

private fun ToolResult.withToolExecutionContext(toolName: String): ToolResult {
    return if (data["toolName"] == toolName) this else copy(data = data + ("toolName" to toolName))
}

private fun Throwable.cleanMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
