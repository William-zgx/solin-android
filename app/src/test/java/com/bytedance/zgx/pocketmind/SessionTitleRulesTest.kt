package com.bytedance.zgx.pocketmind

import com.bytedance.zgx.pocketmind.data.SessionTitleRules
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTitleRulesTest {
    @Test
    fun deriveTitle_usesFirstUserMessageAndNormalizesWhitespace() {
        val title = SessionTitleRules.deriveTitle(
            listOf(
                ChatMessage(MessageRole.Assistant, "ready"),
                ChatMessage(MessageRole.User, "  给我\n一个\t端侧模型验收清单  "),
            ),
        )

        assertEquals("给我 一个 端侧模型验收清单", title)
    }

    @Test
    fun deriveTitle_fallsBackToNewSession() {
        assertEquals(
            "新会话",
            SessionTitleRules.deriveTitle(listOf(ChatMessage(MessageRole.Assistant, "hello"))),
        )
    }

    @Test
    fun deriveTitle_skipsLocalOnlyUserText() {
        val title = SessionTitleRules.deriveTitle(
            listOf(
                ChatMessage(
                    role = MessageRole.User,
                    text = "私密 OCR 摘录 starboat-secret",
                    privacy = MessagePrivacy.LocalOnly,
                ),
                ChatMessage(
                    role = MessageRole.User,
                    text = "普通远程问题",
                    privacy = MessagePrivacy.RemoteEligible,
                ),
            ),
        )

        assertEquals("普通远程问题", title)
    }

    @Test
    fun deriveTitle_usesGenericTitleForOnlyLocalOnlyUserText() {
        val title = SessionTitleRules.deriveTitle(
            listOf(
                ChatMessage(
                    role = MessageRole.User,
                    text = "私密 OCR 摘录 starboat-secret",
                    privacy = MessagePrivacy.LocalOnly,
                ),
            ),
        )

        assertEquals("本地内容", title)
    }
}
