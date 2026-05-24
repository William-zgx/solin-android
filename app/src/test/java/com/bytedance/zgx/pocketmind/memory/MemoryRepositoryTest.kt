package com.bytedance.zgx.pocketmind.memory

import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryTest {
    @Test
    fun searchReturnsTopMatchesAndHonorsTopK() {
        val repository = MemoryRepository()
        repository.index("travel", "用户喜欢京都旅行和安静咖啡馆")
        repository.index("work", "助手整理 Android 模型下载方案")
        repository.index("food", "用户喜欢清淡早餐")

        val hits = repository.search("京都咖啡馆旅行", topK = 1)

        assertEquals(1, hits.size)
        assertEquals("travel", hits.first().id)
    }

    @Test
    fun searchReturnsEmptyWhenDisabledOrEmpty() {
        val repository = MemoryRepository()
        repository.index("one", "本地记忆内容")

        repository.enabled = false

        assertTrue(repository.search("记忆").isEmpty())
        assertTrue(MemoryRepository().search("记忆").isEmpty())
    }

    @Test
    fun rebuildIndexesConversationMessagesWithStableIds() {
        val repository = MemoryRepository()
        val message = ChatMessage(
            role = MessageRole.User,
            text = "我正在做端侧动作助手",
            id = 42L,
        )

        repository.rebuild(listOf(message))

        assertEquals("42", repository.search("动作助手").first().id)
    }
}
