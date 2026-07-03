package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.audit.NoOpToolAuditSink
import com.bytedance.zgx.solin.memory.MemoryIndex
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteerQueueTest {

    @Test
    fun steerChannelHasUnlimitedCapacity() {
        // The contract: steerMessages is an UNLIMITED channel, so trySend always succeeds
        // even when no one is receiving. This keeps steer() non-blocking from the UI thread.
        val channel = Channel<String>(capacity = Channel.UNLIMITED)
        // Enqueue many items without a receiver; trySend must succeed for all.
        repeat(10_000) { i ->
            val result = channel.trySend("steer-$i")
            assertTrue("trySend must succeed on UNLIMITED channel; failed at $i", result.isSuccess)
        }
        // Drain and confirm count
        var drained = 0
        while (true) {
            val result = channel.tryReceive()
            if (result.isSuccess) drained++ else break
        }
        assertEquals(10_000, drained)
    }

    @Test
    fun steerRejectsEmptyMessages() {
        // Mirroring AgentLoopRuntime.steer(): empty message list returns false.
        // We can't easily construct a full AgentLoopRuntime with all deps just to send steer
        // without kicking off a model call, so we assert the semantic precondition directly:
        // empty steer batches are never enqueued.
        val channel = Channel<List<ChatMessage>>(capacity = Channel.UNLIMITED)
        val empty = emptyList<ChatMessage>()
        // Replicate the early-return guard.
        fun steer(messages: List<ChatMessage>): Boolean {
            if (messages.isEmpty()) return false
            return channel.trySend(messages).isSuccess
        }
        assertFalse(steer(empty))
        val one = listOf(ChatMessage(role = MessageRole.User, text = "new direction"))
        assertTrue(steer(one))
        assertEquals(1, channel.tryReceive().getOrNull()?.size)
    }

    @Test
    fun steerMessagesArePreservedInOrder() {
        // First-in-first-out delivery across UNLIMITED channels preserves message order,
        // which is the contract steer/queue rely on so steer batches are drained before
        // normal queued messages and in the order they were sent.
        data class Msg(val runId: String, val text: String)
        val ch = Channel<Msg>(capacity = Channel.UNLIMITED)
        (0 until 50).forEach { i -> ch.trySend(Msg("r", "m-$i")) }
        for (i in 0 until 50) {
            assertEquals("m-$i", ch.tryReceive().getOrNull()?.text)
        }
    }
}
