package com.bytedance.zgx.gemmalocalqa.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMarkdownTest {
    @Test
    fun splitMessageSegments_keepsPlainTextAsSingleSegment() {
        val segments = splitMessageSegments("hello **world**")

        assertEquals(1, segments.size)
        assertEquals("hello **world**", segments.first().text)
        assertFalse(segments.first().isCode)
    }

    @Test
    fun splitMessageSegments_marksFenceContentAsCode() {
        val segments = splitMessageSegments("before```kotlin\nval x = 1\n```after")

        assertEquals(3, segments.size)
        assertEquals("before", segments[0].text)
        assertFalse(segments[0].isCode)
        assertTrue(segments[1].isCode)
        assertEquals("kotlin\nval x = 1\n", segments[1].text)
        assertEquals("after", segments[2].text)
        assertFalse(segments[2].isCode)
    }

    @Test
    fun splitMessageSegments_dropsEmptyFenceEdges() {
        val segments = splitMessageSegments("```plain\nonly code\n```")

        assertEquals(1, segments.size)
        assertTrue(segments.first().isCode)
        assertEquals("plain\nonly code\n", segments.first().text)
    }
}
