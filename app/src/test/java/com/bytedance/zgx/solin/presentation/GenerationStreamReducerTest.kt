package com.bytedance.zgx.solin.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationStreamReducerTest {
    @Test
    fun `stale run and session deltas are ignored`() {
        val coordinator = GenerationStreamCoordinator()
        val first = coordinator.start("session-1", "run-1")
        val second = coordinator.start("session-2", "run-2")

        assertFalse(coordinator.acceptDelta(first, "stale"))
        assertTrue(coordinator.acceptDelta(second, "current"))
        assertEquals("current", coordinator.snapshot().text)
    }

    @Test
    fun `terminal event rejects later deltas and terminals`() {
        val coordinator = GenerationStreamCoordinator()
        val key = coordinator.start("session", "run")

        assertTrue(coordinator.acceptDelta(key, "hello"))
        assertTrue(coordinator.complete(key))
        assertFalse(coordinator.acceptDelta(key, " late"))
        assertFalse(coordinator.fail(key))
        assertEquals("hello", coordinator.snapshot().text)
    }

    @Test
    fun `new generation token supersedes previous generation in same run`() {
        val coordinator = GenerationStreamCoordinator()
        val first = coordinator.start("session", "run")
        val second = coordinator.start("session", "run")

        assertFalse(coordinator.acceptDelta(first, "old"))
        assertTrue(coordinator.acceptDelta(second, "new"))
        assertEquals(second, coordinator.snapshot().activeKey)
    }

    @Test
    fun `reducer rejects non increasing start token`() {
        val reducer = GenerationStreamReducer()
        val current = GenerationStreamState(
            activeKey = GenerationStreamKey("session", "run", 2),
            text = "current",
        )

        val reduction = reducer.reduce(
            current,
            GenerationStreamEvent.Started(GenerationStreamKey("other", "other", 2)),
        )

        assertFalse(reduction.accepted)
        assertEquals(current, reduction.state)
    }
}
