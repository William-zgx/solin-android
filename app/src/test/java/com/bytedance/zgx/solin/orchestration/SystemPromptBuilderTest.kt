package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.evidence.EvidenceCard
import com.bytedance.zgx.solin.evidence.EvidenceQuality
import com.bytedance.zgx.solin.evidence.EvidenceQualityLevel
import com.bytedance.zgx.solin.evidence.EvidenceSourceType
import com.bytedance.zgx.solin.runtime.DEFAULT_CHAT_SYSTEM_INSTRUCTION
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptBuilderTest {

    private fun fakeRun(id: String = "run-1", input: String = "hi"): AgentRun =
        AgentRun(
            id = id,
            input = input,
            state = AgentRunState.Created,
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
        )

    private fun card(
        id: String,
        sourceType: EvidenceSourceType = EvidenceSourceType.Memory,
        text: String = "evidence-$id",
        requiresLocalModel: Boolean = false,
    ): EvidenceCard = EvidenceCard(
        id = id,
        sourceType = sourceType,
        privacy = MessagePrivacy.RemoteEligible,
        requiresLocalModel = requiresLocalModel,
        text = text,
        quality = EvidenceQuality(EvidenceQualityLevel.Unknown),
    )

    private class FakeContributor(
        override val sourceType: EvidenceSourceType,
        private val card: EvidenceCard? = null,
        private val throwOnCall: Boolean = false,
        var callCount: Int = 0,
        var lastUserInput: String? = null,
        var lastRunId: String? = null,
    ) : SystemContextContributor {
        override suspend fun contribute(userInput: String, run: AgentRun): EvidenceCard? {
            callCount++
            lastUserInput = userInput
            lastRunId = run.id
            if (throwOnCall) error("boom from ${sourceType.name}")
            return card
        }
    }

    @Test
    fun defaultPromptReturnedWhenNoContributors() = runTest {
        val builder = SystemPromptBuilder()
        val prompt = builder.buildSystemPrompt("hello", fakeRun())

        assertEquals(DEFAULT_CHAT_SYSTEM_INSTRUCTION, prompt.baseInstruction)
        assertTrue(prompt.evidenceCards.isEmpty())
        assertTrue(prompt.contributorAttributions.isEmpty())
    }

    @Test
    fun contributorReturningCardIsIncluded() = runTest {
        val contributor = FakeContributor(
            sourceType = EvidenceSourceType.Memory,
            card = card("mem-1", sourceType = EvidenceSourceType.Memory, text = "remember me"),
        )
        val builder = SystemPromptBuilder(contributors = listOf(contributor))
        val prompt = builder.buildSystemPrompt("recall", fakeRun(id = "r-9"))

        assertEquals(1, prompt.evidenceCards.size)
        assertEquals("mem-1", prompt.evidenceCards.single().id)
        assertEquals("remember me", prompt.evidenceCards.single().text)
        assertEquals(listOf(EvidenceSourceType.Memory.name), prompt.contributorAttributions)
        assertEquals(1, contributor.callCount)
        assertEquals("recall", contributor.lastUserInput)
        assertEquals("r-9", contributor.lastRunId)
    }

    @Test
    fun throwingContributorDoesNotCrashAndReturnsDefault() = runTest {
        val throwing = FakeContributor(
            sourceType = EvidenceSourceType.DeviceContext,
            throwOnCall = true,
        )
        val good = FakeContributor(
            sourceType = EvidenceSourceType.Memory,
            card = card("ok-1"),
        )
        val builder = SystemPromptBuilder(contributors = listOf(throwing, good))

        val prompt = builder.buildSystemPrompt("input", fakeRun())

        // Throwing contributor is skipped; next contributor's card is still collected.
        assertEquals(1, prompt.evidenceCards.size)
        assertEquals("ok-1", prompt.evidenceCards.single().id)
        assertEquals(listOf(EvidenceSourceType.Memory.name), prompt.contributorAttributions)
        assertEquals(DEFAULT_CHAT_SYSTEM_INSTRUCTION, prompt.baseInstruction)
        // Both contributors were invoked (i.e. failure didn't abort iteration).
        assertEquals(1, throwing.callCount)
        assertEquals(1, good.callCount)
    }

    @Test
    fun multipleContributorsAreAllCalled() = runTest {
        val a = FakeContributor(sourceType = EvidenceSourceType.Memory, card = card("a"))
        val b = FakeContributor(sourceType = EvidenceSourceType.DeviceContext, card = card("b", sourceType = EvidenceSourceType.DeviceContext))
        val nullContributor = FakeContributor(sourceType = EvidenceSourceType.PublicWeb, card = null)

        val builder = SystemPromptBuilder(contributors = listOf(a, nullContributor, b))
        val prompt = builder.buildSystemPrompt("x", fakeRun())

        assertEquals(1, a.callCount)
        assertEquals(1, b.callCount)
        assertEquals(1, nullContributor.callCount)
        assertEquals(listOf("a", "b"), prompt.evidenceCards.map { it.id })
        assertEquals(
            listOf(EvidenceSourceType.Memory.name, EvidenceSourceType.DeviceContext.name),
            prompt.contributorAttributions,
        )
    }

}
