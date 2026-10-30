package com.bytedance.zgx.solin.ui

import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.ui.components.MID_RUN_STEERING_COMPOSER_HINT
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
        assertFalse(idle.actionIsStop)
        assertEquals("输入问题", idle.placeholder)
    }

    @Test
    fun busyWithoutSteeringKeepsTheOldDisabledInputAndStopButton() {
        val busy = composerActionState(generating.copy(midRunSteeringAvailable = false), input = "")

        assertFalse(busy.inputEnabled)
        assertFalse(busy.canSend)
        assertTrue(busy.actionIsStop)
        assertEquals("生成中", busy.placeholder)
    }

    @Test
    fun steeringKeepsInputEnabledWithHintAndStopUntilTextIsTyped() {
        val blank = composerActionState(generating, input = " ")

        assertTrue(blank.inputEnabled)
        assertFalse(blank.canSend)
        assertTrue(blank.actionIsStop)
        assertEquals(MID_RUN_STEERING_COMPOSER_HINT, blank.placeholder)
        assertEquals("运行中发送将插话调整", MID_RUN_STEERING_COMPOSER_HINT)

        val typed = composerActionState(generating, input = "换个方向")

        assertTrue(typed.canSend)
        assertFalse(typed.actionIsStop)
    }

    @Test
    fun steeringDuringToolExecutionSendsAndShowsHeldCount() {
        val tool = composerActionState(
            generating.copy(isGenerating = false, pendingSteerMessageCount = 2),
            input = "等一下",
        )

        assertTrue(tool.canSend)
        assertFalse(tool.actionIsStop)
        assertEquals("已插话 2 条 · $MID_RUN_STEERING_COMPOSER_HINT", tool.placeholder)
    }
}
