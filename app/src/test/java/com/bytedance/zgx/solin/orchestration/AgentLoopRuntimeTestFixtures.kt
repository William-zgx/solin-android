package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.ModelToolOutputParseResult
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
