package com.bytedance.zgx.solin

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingAssistantUpdateCoalescerTest {
    @Test
    fun update_coalescesIntermediateTextAndFlushPublishesFinalText() {
        var nowMillis = 0L
        val published = mutableListOf<String>()
        val coalescer = StreamingAssistantUpdateCoalescer(
            publish = published::add,
            intervalMillis = 75L,
            nowMillis = { nowMillis },
        )

        coalescer.update("a")
        coalescer.update("ab")
        nowMillis = 74L
        coalescer.update("abc")
        nowMillis = 75L
        coalescer.update("abcd")
        nowMillis = 76L
        coalescer.update("abcde")
        coalescer.flush()

        assertEquals(listOf("a", "abcd", "abcde"), published)
    }

    @Test
    fun discard_removesDeferredTextBeforeItCanBeFlushed() {
        var nowMillis = 0L
        val published = mutableListOf<String>()
        val coalescer = StreamingAssistantUpdateCoalescer(
            publish = published::add,
            intervalMillis = 75L,
            nowMillis = { nowMillis },
        )

        coalescer.update("safe prefix")
        nowMillis = 1L
        coalescer.update("unsafe deferred text")
        coalescer.discard()
        coalescer.flush()

        assertEquals(listOf("safe prefix"), published)
    }
}
