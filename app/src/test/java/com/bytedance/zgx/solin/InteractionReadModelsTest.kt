package com.bytedance.zgx.solin

import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.memory.MemoryRecallMode
import com.bytedance.zgx.solin.memory.MemoryRecordType
import com.bytedance.zgx.solin.orchestration.AgentRunEvent
import com.bytedance.zgx.solin.orchestration.AgentRunEventState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionReadModelsTest {
    @Test
    fun memoryEvidenceSummariesOmitRawMemoryText() {
        val privateEmail = "secret@example.com"

        val summaries = memoryEvidenceSummariesFor(
            listOf(
                MemoryHit(
                    id = "memory-with-private-email",
                    text = "用户邮箱：$privateEmail",
                    score = 0.91f,
                    recallMode = MemoryRecallMode.Semantic,
                    recordType = MemoryRecordType.UserFact,
                    rankingReason = "semantic vector similarity",
                ),
            ),
        )

        assertEquals(1, summaries.size)
        val rendered = summaries.single().toString()
        assertTrue(rendered.contains("事实"))
        assertTrue(rendered.contains("语义召回"))
        assertTrue(rendered.contains("高相关"))
        assertFalse(rendered.contains(privateEmail))
        assertFalse(rendered.contains("用户邮箱"))
    }

    @Test
    fun runTimelineSummariesNameCoreStates() {
        val timeline = runTimelineSummariesFor(
            listOf(
                AgentRunEvent.InputReceived(
                    eventId = "event-input",
                    runId = "run-1",
                    inputId = "input-1",
                    sourceLabel = "typed",
                ),
                AgentRunEvent.ContextLoaded(
                    eventId = "event-context",
                    runId = "run-1",
                    contextId = "context-1",
                    memoryHitCount = 2,
                ),
                AgentRunEvent.ConfirmationRequested(
                    eventId = "event-confirm",
                    runId = "run-1",
                    confirmationId = "confirm-1",
                    toolCallId = "tool-1",
                    actionLabel = "分享摘要",
                ),
                AgentRunEvent.RunFailed(
                    eventId = "event-failed",
                    runId = "run-1",
                    failureId = "failed-1",
                    reasonLabel = "permission denied",
                ),
            ),
        )

        assertEquals(
            listOf("收到输入", "加载上下文", "等待确认", "失败"),
            timeline.map { it.label },
        )
        assertEquals(AgentRunEventState.Failed.name, timeline.last().state)
        assertTrue(timeline[1].detail.contains("本地记忆 2 条"))
    }
}
