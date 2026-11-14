package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.ModelToolOutputParseResult
import com.bytedance.zgx.solin.device.DEVICE_CONTROL_METADATA_POLICY
import com.bytedance.zgx.solin.device.DEVICE_CONTROL_SOURCE_ACCESSIBILITY
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Shared fixtures for the focused runtime tests added alongside [AgentLoopRuntimeTest]. Kept
 * deliberately small: an action runtime whose parse result can be swapped between turns, and an
 * event bus that records every published event synchronously.
 */
internal class ScriptedActionRuntime(
    var modelOutputResult: ModelToolOutputParseResult = ModelToolOutputParseResult.None,
    private val planningResult: ActionPlanningResult = ActionPlanningResult(
        plan = ActionPlan(ActionPlanKind.NoAction),
        usedModel = false,
        fallbackReason = null,
    ),
    private val likelyAction: Boolean = planningResult.plan.kind != ActionPlanKind.NoAction,
) : ActionPlanningRuntime {
    override fun isLikelyAction(input: String): Boolean = likelyAction

    override fun plan(input: String, actionModelPath: String?): ActionPlanningResult = planningResult

    override fun parseModelToolOutput(output: String): ModelToolOutputParseResult = modelOutputResult
}

internal class RecordingSolinEventBus : SolinEventBus {
    val events = java.util.concurrent.CopyOnWriteArrayList<SolinEvent>()

    override fun publish(event: SolinEvent) {
        events += event
    }

    override fun <T : SolinEvent> subscribe(type: KClass<T>): Flow<T> = emptyFlow()

    override fun <E : SolinEvent> recent(type: KClass<out E>, limit: Int): List<E> = emptyList()

    inline fun <reified T : SolinEvent> ofType(): List<T> = events.filterIsInstance<T>()
}

internal class RecordingTelemetrySink : TelemetrySink {
    private val delegate = InMemoryTelemetrySink()
    val samples = java.util.concurrent.CopyOnWriteArrayList<MetricSample>()

    override fun record(sample: MetricSample) {
        samples += sample
        delegate.record(sample)
    }

    override fun snapshot(): TelemetrySnapshot = delegate.snapshot()
}

internal fun publicWebSearchResultData(
    query: String,
    summaryText: String = "$query summary",
): Map<String, String> =
    mapOf(
        "toolName" to MobileActionFunctions.WEB_SEARCH,
        "privacy" to com.bytedance.zgx.solin.MessagePrivacy.RemoteEligible.name,
        "requiresLocalModel" to "false",
        "query" to query,
        "source" to "duckduckgo",
        "searchMode" to "general",
        "retrievedAt" to "2026-06-13T00:00:00Z",
        "freshness" to "current",
        "maxResults" to "3",
        "summaryText" to summaryText,
        "resultsJson" to """{"kind":"instant_answer","provider":"DuckDuckGo","results":[]}""",
    )

internal fun externalDeepLinkPlanningResult(): ActionPlanningResult =
    ActionPlanningResult(
        plan = ActionPlan(
            kind = ActionPlanKind.Draft,
            draft = ActionDraft(
                functionName = MobileActionFunctions.OPEN_DEEP_LINK,
                title = "打开深链",
                summary = "将打开示例网页。",
                parameters = mapOf("uri" to "https://example.com/search?q=Kotlin"),
                requiresConfirmation = true,
            ),
        ),
        usedModel = false,
        fallbackReason = "test fallback",
    )

internal fun unverifiedExternalActivityResultData(
    toolName: String = MobileActionFunctions.OPEN_DEEP_LINK,
): Map<String, String> =
    mapOf(
        "toolName" to toolName,
        "completionState" to "ExternalActivityOpened",
        "completionVerified" to "false",
        "externalOutcome" to "Unknown",
        "externalOutcomeSource" to "Unknown",
        "targetKind" to "external_activity",
        "intentAction" to "android.intent.action.VIEW",
        "metadataPolicy" to "no_raw_payload_persisted",
        "rawPayloadIncluded" to "false",
    )

internal fun askUserDraft(prompt: String = "你想查询哪个城市？"): ActionDraft =
    ActionDraft(
        functionName = MobileActionFunctions.ASK_USER,
        title = "询问用户",
        summary = prompt,
        parameters = mapOf("prompt" to prompt),
        requiresConfirmation = false,
    )

internal class QueuedModelActionRuntime(
    drafts: List<ActionDraft>,
) : ActionPlanningRuntime {
    private val remainingDrafts = ArrayDeque(drafts)
    private val mutablePlannedInputs = mutableListOf<String>()
    private val mutableActionModelPaths = mutableListOf<String?>()

    val plannedInputs: List<String>
        get() = mutablePlannedInputs.toList()
    val actionModelPaths: List<String?>
        get() = mutableActionModelPaths.toList()

    override fun isLikelyAction(input: String): Boolean = false

    override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
        mutablePlannedInputs += input
        mutableActionModelPaths += actionModelPath
        return ActionPlanningResult(
            plan = ActionPlan(
                kind = ActionPlanKind.Draft,
                draft = remainingDrafts.removeFirst(),
            ),
            usedModel = true,
            fallbackReason = null,
        )
    }
}

internal fun observeScreenResult(
    request: ToolRequest,
    packageName: String = "com.taobao.taobao",
    textSummary: String = "淘宝 搜索商品 海河牛奶",
    rawScreenObservationJson: String = screenObservationJson("screen-${request.id}", packageName),
): ToolResult =
    ToolResult(
        requestId = request.id,
        status = ToolStatus.Succeeded,
        summary = "已观察当前屏幕",
        data = localDeviceControlData(request.toolName) + mapOf(
            "observationId" to "screen-${request.id}",
            "capturedAtMillis" to "1000",
            "nodeCount" to "3",
            "actionableNodeCount" to "2",
            "textSummary" to textSummary,
            "truncated" to "false",
            "nodesJson" to "[]",
            "screenObservationJson" to rawScreenObservationJson,
            "maxTextChars" to "2000",
            "maxNodes" to "50",
            "packageName" to packageName,
        ),
    )

internal fun screenObservationJson(
    observationId: String,
    packageName: String,
): String =
    """{"schemaVersion":1,"observationId":"$observationId","capturedAtMillis":1000,"packageName":"$packageName","privacyLevel":"LocalOnly","sources":["accessibility"],"elementCount":0,"sourceCounts":{},"truncated":false,"elements":[]}"""

internal fun searchEntryScreenObservationJson(observationId: String): String =
    """{"schemaVersion":1,"observationId":"$observationId","capturedAtMillis":1000,"packageName":"com.taobao.taobao","privacyLevel":"LocalOnly","sources":["accessibility"],"elementCount":1,"sourceCounts":{"accessibility":1},"truncated":false,"elements":[{"id":"search_entry","source":"accessibility","bounds":{"left":0,"top":0,"right":200,"bottom":80},"text":"搜索商品","role":"button","clickability":{"clickable":true,"editable":false,"scrollable":false,"enabled":true},"confidence":1.0,"sensitiveFlags":[],"privacyLevel":"LocalOnly"}]}"""

internal fun searchInputScreenObservationJson(observationId: String): String =
    """{"schemaVersion":1,"observationId":"$observationId","capturedAtMillis":1000,"packageName":"com.taobao.taobao","privacyLevel":"LocalOnly","sources":["accessibility"],"elementCount":2,"sourceCounts":{"accessibility":2},"truncated":false,"elements":[{"id":"search_edit_text","source":"accessibility","bounds":{"left":0,"top":0,"right":400,"bottom":80},"text":"搜索商品","role":"edit_text","clickability":{"clickable":true,"editable":true,"scrollable":false,"enabled":true},"confidence":1.0,"sensitiveFlags":[],"privacyLevel":"LocalOnly"},{"id":"search_submit","source":"accessibility","bounds":{"left":410,"top":0,"right":500,"bottom":80},"text":"搜索","role":"button","clickability":{"clickable":true,"editable":false,"scrollable":false,"enabled":true},"confidence":1.0,"sensitiveFlags":[],"privacyLevel":"LocalOnly"}]}"""

internal fun uiActionResult(
    request: ToolRequest,
    actionType: String,
    target: String = "",
    packageName: String = "com.taobao.taobao",
    textSummary: String = "淘宝 搜索商品 海河牛奶",
    extraData: Map<String, String> = emptyMap(),
): ToolResult =
    ToolResult(
        requestId = request.id,
        status = ToolStatus.Succeeded,
        summary = "已执行 UI 动作",
        data = localDeviceControlData(request.toolName) +
            mapOf(
                "actionType" to actionType,
                "status" to "succeeded",
                "retryable" to "false",
                "summary" to "已执行 UI 动作",
                "beforeObservationId" to "before-${request.id}",
                "afterObservationId" to "after-${request.id}",
                "verificationSummary" to "已观察当前屏幕：包名 $packageName，3 个节点，2 个可交互节点。",
                "afterPackageName" to packageName,
                "afterCapturedAtMillis" to "1000",
                "afterNodeCount" to "3",
                "afterActionableNodeCount" to "2",
                "afterTextSummary" to textSummary,
                "afterTruncated" to "false",
                "afterNodesJson" to "[]",
            ) +
            target.takeIf { it.isNotBlank() }?.let { mapOf("target" to it) }.orEmpty() +
            extraData,
    )

internal fun localDeviceControlData(toolName: String): Map<String, String> =
    mapOf(
        "toolName" to toolName,
        "privacy" to MessagePrivacy.LocalOnly.name,
        "requiresLocalModel" to "true",
        "source" to DEVICE_CONTROL_SOURCE_ACCESSIBILITY,
        "metadataPolicy" to DEVICE_CONTROL_METADATA_POLICY,
    )
