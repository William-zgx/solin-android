package com.bytedance.zgx.solin.runtime

import org.junit.Test

class ChatPromptsTest {
    @Test
    fun systemPromptFailsClosedWhenToolsReturnNoEvidence() {
        assertContainsAll(
            DEFAULT_CHAT_SYSTEM_INSTRUCTION,
            "工具失败、返回空结果或没有可引用来源",
            "不要把训练知识包装成最新事实或排名",
            "除非本轮上下文包含相应工具结果",
        )
    }
}
