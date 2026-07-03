package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.memory.MemoryHit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lightweight tests for [ContextAssembler].
 *
 * The assembleTurnMessages-style method described in the task brief does not exist on
 * [ContextAssembler] (that class builds the per-turn user-prompt STRING, not a ChatMessage
 * list — history assembly lives in SolinViewModel / the local/remote runtimes). So these
 * tests exercise the public API that IS here: [ContextAssembler.assembleAnswerPrompt] with
 * the no-op (default) configuration returns the input unchanged, and construction with
 * all-default / null dependencies succeeds without NPEs.
 */
class ContextAssemblerTest {

    private fun fakeRun(id: String = "r-1", input: String = "hi"): AgentRun =
        AgentRun(
            id = id,
            input = input,
            state = AgentRunState.Created,
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
        )

    @Test
    fun defaultConstructorSucceeds() {
        // Must not throw; both params are nullable and default to null.
        val assembler = ContextAssembler()
        assertNotNull(assembler)
    }

    @Test
    fun answerPromptReturnedUnchangedWhenNoContext() {
        val assembler = ContextAssembler(systemPromptBuilder = null, memoryIndex = null)
        val input = "你好，今天天气怎么样？"
        val out = assembler.assembleAnswerPrompt(
            input = input,
            memoryHits = emptyList(),
            deviceContext = null,
            run = fakeRun(input = input),
        )
        // Fast path: no evidence, no scaffolding — input returned verbatim.
        assertTrue(
            "Expected fast path to return input unchanged, got:\n$out",
            out == input,
        )
    }

    @Test
    fun answerPromptWrapsInputWithScaffoldingWhenMemoryIsPresent() {
        val assembler = ContextAssembler(systemPromptBuilder = null, memoryIndex = null)
        val input = "what is my preference?"
        val memHit = MemoryHit(
            id = "pref-1",
            text = "用户喜欢端侧离线聊天",
            score = 0.9f,
            finalScore = 0.9f,
        )
        val out = assembler.assembleAnswerPrompt(
            input = input,
            memoryHits = listOf(memHit),
            deviceContext = null,
            run = fakeRun(input = input),
        )
        // Chinese scaffolding must be present when there is evidence.
        assertTrue("Expected prompt to contain 本地记忆 scaffolding", out.contains("本地记忆"))
        assertTrue("Expected prompt to contain 用户问题 prefix", out.contains("用户问题"))
        assertTrue("Expected prompt to include user input verbatim", out.contains(input))
        // The memory hit text should be carried as an evidence line.
        assertTrue("Expected memory evidence in prompt", out.contains("用户喜欢端侧离线聊天"))
    }
}
