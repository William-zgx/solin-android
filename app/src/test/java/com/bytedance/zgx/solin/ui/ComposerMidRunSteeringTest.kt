package com.bytedance.zgx.solin.ui

import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.SharedInputDraft
import com.bytedance.zgx.solin.ui.components.COMPOSER_NOT_READY_HINT
import com.bytedance.zgx.solin.ui.components.MID_RUN_STEERING_COMPOSER_HINT
import com.bytedance.zgx.solin.ui.components.NOT_READY_PLACEHOLDER
import com.bytedance.zgx.solin.ui.components.composerActionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerMidRunSteeringTest {
    private val generating = ChatUiState(
        isBusy = true,
        isGenerating = true,
        isReady = true,
        statusText = "生成中",
        midRunSteeringAvailable = true,
    )

    @Test
    fun idleComposerIsUnchanged() {
        val idle = composerActionState(ChatUiState(isReady = true, statusText = "就绪"), input = "你好")

        assertTrue(idle.inputEnabled)
        assertTrue(idle.canSend)
        assertFalse(idle.showStop)
        assertTrue(idle.showSend)
        assertEquals("输入问题", idle.placeholder)
    }

    @Test
    fun busyWithoutSteeringKeepsTheOldDisabledInputAndStopButton() {
        val busy = composerActionState(generating.copy(midRunSteeringAvailable = false), input = "")

        assertFalse(busy.inputEnabled)
        assertFalse(busy.canSend)
        assertTrue(busy.showStop)
        assertFalse(busy.showSend)
        assertEquals("生成中", busy.placeholder)
    }

    @Test
    fun steeringKeepsInputEnabledWithHintAndStopUntilTextIsTyped() {
        val blank = composerActionState(generating, input = " ")

        assertTrue(blank.inputEnabled)
        assertFalse(blank.canSend)
        assertTrue(blank.showStop)
        assertFalse(blank.showSend)
        assertEquals(MID_RUN_STEERING_COMPOSER_HINT, blank.placeholder)
        assertEquals("运行中发送将插话调整", MID_RUN_STEERING_COMPOSER_HINT)

        val typed = composerActionState(generating, input = "换个方向")

        assertTrue(typed.canSend)
        // Regression (audit #6): typing mid-run must not take the stop button away.
        assertTrue(typed.showStop)
        // The steer send appears next to it, so the user never has to clear the text to stop.
        assertTrue(typed.showSend)
    }

    @Test
    fun steeringDuringToolExecutionSendsAndShowsHeldCount() {
        val tool = composerActionState(
            generating.copy(isGenerating = false, pendingSteerMessageCount = 2),
            input = "等一下",
        )

        assertTrue(tool.canSend)
        // The tool phase has no model generation to stop, so only the steer send is shown.
        assertFalse(tool.showStop)
        assertTrue(tool.showSend)
        assertEquals("已插话 2 条 · $MID_RUN_STEERING_COMPOSER_HINT", tool.placeholder)
    }

    @Test
    fun notReadyComposerCannotSendAndHasNoBusyPlaceholder() {
        val notReady = composerActionState(
            ChatUiState(isReady = false, statusText = "未加载模型"),
            input = "你好",
        )

        assertTrue(notReady.inputEnabled)
        assertFalse(notReady.canSend)
        assertFalse(notReady.showStop)
        assertTrue(notReady.showSend)
        assertEquals(NOT_READY_PLACEHOLDER, notReady.placeholder)
        assertEquals("先下载本地模型或连接远程模型", COMPOSER_NOT_READY_HINT)
    }

    @Test
    fun notReadyComposerDoesNotSendAStagedSharedDraft() {
        val notReadyWithDraft = ChatUiState(
            isReady = false,
            statusText = "未加载模型",
            pendingSharedInputDraft = SharedInputDraft(id = 1L, prompt = "分享文本", summary = "分享文本"),
        )
        val state = composerActionState(notReadyWithDraft, input = "")

        assertFalse(state.canSend)
        assertTrue(state.showSend)
    }
}
