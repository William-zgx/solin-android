package com.bytedance.zgx.pocketmind.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptsTest {
    @Test
    fun systemPromptFailsClosedWhenToolsReturnNoEvidence() {
        assertTrue(DEFAULT_CHAT_SYSTEM_INSTRUCTION.contains("工具失败、返回空结果或没有可引用来源"))
        assertTrue(DEFAULT_CHAT_SYSTEM_INSTRUCTION.contains("不要把训练知识包装成最新事实或排名"))
        assertTrue(DEFAULT_CHAT_SYSTEM_INSTRUCTION.contains("除非本轮上下文包含相应工具结果"))
    }
}
