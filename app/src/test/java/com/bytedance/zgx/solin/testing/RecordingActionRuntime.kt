package com.bytedance.zgx.solin.testing

import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.ModelToolOutputParseResult

/** [ActionPlanningRuntime] returning fixed answers and counting calls. */
class RecordingActionRuntime(
    private val likelyAction: Boolean,
    private val planningResult: ActionPlanningResult = ActionPlanningResult(
        plan = ActionPlan(ActionPlanKind.NoAction),
        usedModel = false,
        fallbackReason = null,
    ),
    private val modelOutputResult: ModelToolOutputParseResult = ModelToolOutputParseResult.None,
) : ActionPlanningRuntime {
    var isLikelyActionCallCount: Int = 0
        private set
    var planCallCount: Int = 0
        private set
    var parseModelToolOutputCallCount: Int = 0
        private set

    override fun isLikelyAction(input: String): Boolean {
        isLikelyActionCallCount += 1
        return likelyAction
    }

    override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
        planCallCount += 1
        return planningResult
    }

    override fun parseModelToolOutput(output: String): ModelToolOutputParseResult {
        parseModelToolOutputCallCount += 1
        return modelOutputResult
    }
}
