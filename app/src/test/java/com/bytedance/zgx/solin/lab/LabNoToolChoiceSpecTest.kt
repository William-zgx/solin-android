package com.bytedance.zgx.solin.lab

import com.bytedance.zgx.solin.tool.ToolCapability
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the lab's copy of production's `answer` no-tool choice.
 *
 * The production constant is `internal const val LOCAL_MODEL_NO_TOOL_CHOICE = "answer"` at
 * `orchestration/InitialToolPlanner.kt:43`, with the spec at `:52-60`. That file only exists on
 * `edge-lab/integration`; this lab branch is based on `edge-lab/lab`, which does not carry
 * `LocalModelFirst`, so the values are copied into `OllamaActionPlanningRuntime.kt`. If either side
 * drifts, the `*-lmf` modes stop measuring the production shape and this test must fail.
 */
class LabNoToolChoiceSpecTest {

    @Test
    fun noToolChoiceMatchesProduction() {
        assertEquals("answer", LOCAL_MODEL_NO_TOOL_CHOICE)
        assertEquals(LOCAL_MODEL_NO_TOOL_CHOICE, NO_TOOL_CHOICE_SPEC.name)
        assertEquals("直接回答", NO_TOOL_CHOICE_SPEC.title)
        assertEquals(
            "请求不需要手机动作（提问、闲聊、解释、翻译）时选它，由助手直接用文字回答。",
            NO_TOOL_CHOICE_SPEC.description,
        )
        assertEquals(
            """{"type":"object","properties":{},"additionalProperties":false}""",
            NO_TOOL_CHOICE_SPEC.inputSchemaJson,
        )
        // Orchestration is what the summary uses to split a no-tool false positive into
        // "orchestration only" versus a device, network or external-app action.
        assertEquals(ToolCapability.Orchestration, NO_TOOL_CHOICE_SPEC.capability)
        // It is never registered, so nothing can execute it.
        assertEquals(
            "answer" !in com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry.specs().map { it.name },
            true,
        )
    }
}
