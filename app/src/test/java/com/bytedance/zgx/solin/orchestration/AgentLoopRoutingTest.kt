package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.device.DeviceContextSnapshot
import com.bytedance.zgx.solin.evidence.EvidenceCard
import com.bytedance.zgx.solin.evidence.EvidenceBlobRef
import com.bytedance.zgx.solin.evidence.EvidenceSourceType
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolResultContinuationPolicy
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopRoutingTest {
    @Test
    fun messageAdaptersPreserveHistorySteerAndQueuedPrivacy() {
        val remote = ChatMessage(MessageRole.User, "remote", privacy = MessagePrivacy.RemoteEligible)
        val local = ChatMessage(MessageRole.User, "local", privacy = MessagePrivacy.LocalOnly)

        val history = listOf(remote, local).toPromptPrivacySegments(
            source = PromptSegmentSource.History,
            optionalHistory = true,
        )
        val steer = local.toPromptPrivacySegment(PromptSegmentSource.Steer)
        val queued = remote.toPromptPrivacySegment(PromptSegmentSource.QueuedInput)

        assertEquals(listOf(MessagePrivacy.RemoteEligible, MessagePrivacy.LocalOnly), history.map { it.privacy })
        assertTrue(history.all { it.optionalHistory })
        assertEquals(MessagePrivacy.LocalOnly, steer.privacy)
        assertTrue(steer.requiresLocalModel == true)
        assertEquals(MessagePrivacy.RemoteEligible, queued.privacy)
        assertFalse(queued.requiresLocalModel == true)

        val drainSegments = PendingMessagesDrain(
            steer = listOf(local),
            queued = listOf(remote),
        ).toPromptPrivacySegments()
        assertEquals(
            listOf(PromptSegmentSource.Steer, PromptSegmentSource.QueuedInput),
            drainSegments.map { it.source },
        )
        assertEquals(MessagePrivacy.LocalOnly, PromptPrivacyPlanner.build(drainSegments).aggregatePrivacy)
    }

    @Test
    fun routeContextAdaptersTightenRemoteEligibleInputWithMemoryAndDeviceContext() {
        val initial = PromptPrivacyPlanner.build(
            listOf(
                PromptPrivacySegment(
                    PromptSegmentSource.CurrentInput,
                    MessagePrivacy.RemoteEligible,
                    false,
                ),
            ),
        )
        val route = AssistantRoute.Chat(
            runId = "run",
            promptForModel = "prompt",
            memoryHits = listOf(MemoryHit("memory", "private", 1f)),
            deviceContext = DeviceContextSnapshot(
                isArm64Supported = true,
                inferenceMode = "Auto",
                installedCapabilities = emptySet(),
                memoryEnabled = true,
                availableStorageBytes = 1L,
                activeSessionId = "session",
                hasPendingConfirmation = false,
            ),
        )

        val plan = PromptPrivacyPlanner.append(initial, route.toContextPrivacySegments())

        assertEquals(MessagePrivacy.LocalOnly, plan.aggregatePrivacy)
        assertTrue(plan.requiresLocalModel)
        assertEquals(
            listOf(PromptSegmentSource.Memory, PromptSegmentSource.DeviceContext),
            route.toContextPrivacySegments().map { it.source },
        )
    }

    @Test
    fun evidenceAdapterPreservesSourcePrivacyAndRuntimeRequirement() {
        val evidence = EvidenceCard(
            id = "public",
            sourceType = EvidenceSourceType.PublicWeb,
            privacy = MessagePrivacy.RemoteEligible,
            requiresLocalModel = false,
            text = "public",
        )

        val segment = evidence.toPromptPrivacySegment()

        assertEquals(PromptSegmentSource.Evidence, segment.source)
        assertEquals(MessagePrivacy.RemoteEligible, segment.privacy)
        assertFalse(segment.requiresLocalModel == true)
    }

    @Test
    fun toolObservationAdapterFailsClosedForMissingOrMalformedMetadata() {
        val publicSpec = toolSpec(ToolResultContinuationPolicy.PublicEvidence)
        listOf(
            emptyMap(),
            mapOf("privacy" to "future", "requiresLocalModel" to "false"),
            mapOf("privacy" to MessagePrivacy.RemoteEligible.name, "requiresLocalModel" to "not-a-boolean"),
        ).forEach { data ->
            val plan = PromptPrivacyPlanner.build(
                listOf(toolResult(data).toPromptPrivacySegment(publicSpec)),
            )
            assertEquals(MessagePrivacy.LocalOnly, plan.aggregatePrivacy)
            assertTrue(plan.requiresLocalModel)
        }

        assertLocalOnly(toolResult(remoteEligibleData()).toPromptPrivacySegment(null))
        assertLocalOnly(
            toolResult(remoteEligibleData()).toPromptPrivacySegment(
                toolSpec(ToolResultContinuationPolicy.LocalEvidence),
            ),
        )
        assertLocalOnly(
            toolResult(remoteEligibleData()).toPromptPrivacySegment(
                publicSpec.copy(privateOutputKeys = setOf("privateResult")),
            ),
        )
        listOf(
            mapOf("screenObservationDiffSummary" to "changed=true"),
            mapOf("payloadJson" to "{\"afterScreenObservationJson\":{\"text\":\"private\"}}"),
        ).forEach { protectedData ->
            assertLocalOnly(
                toolResult(remoteEligibleData() + protectedData).toPromptPrivacySegment(publicSpec),
            )
        }
        assertLocalOnly(
            toolResult(
                data = remoteEligibleData(),
                overflowRefs = listOf(overflowRef(MessagePrivacy.LocalOnly)),
            ).toPromptPrivacySegment(publicSpec),
        )

        val remotePlan = PromptPrivacyPlanner.build(
            listOf(
                toolResult(remoteEligibleData()).toPromptPrivacySegment(publicSpec),
            ),
        )
        assertEquals(MessagePrivacy.RemoteEligible, remotePlan.aggregatePrivacy)
        assertFalse(remotePlan.requiresLocalModel)
    }

    private fun assertLocalOnly(segment: PromptPrivacySegment) {
        val plan = PromptPrivacyPlanner.build(listOf(segment))
        assertEquals(MessagePrivacy.LocalOnly, plan.aggregatePrivacy)
        assertTrue(plan.requiresLocalModel)
    }

    private fun remoteEligibleData(): Map<String, String> = mapOf(
        "privacy" to MessagePrivacy.RemoteEligible.name,
        "requiresLocalModel" to "false",
    )

    private fun toolSpec(policy: ToolResultContinuationPolicy) = ToolSpec(
        name = "test_tool",
        title = "Test",
        description = "Test",
        inputSchemaJson = "{}",
        capability = ToolCapability.WebSearch,
        resultContinuationPolicy = policy,
    )

    private fun overflowRef(privacy: MessagePrivacy): EvidenceBlobRef = EvidenceBlobRef.fromSha256(
        sha256 = "a".repeat(64),
        sizeBytes = 1,
        mimeType = "text/plain",
        privacy = privacy,
        sourceType = EvidenceSourceType.ToolResult,
    )

    private fun toolResult(
        data: Map<String, String>,
        overflowRefs: List<EvidenceBlobRef> = emptyList(),
    ) = ToolResult(
        requestId = "tool",
        status = ToolStatus.Succeeded,
        summary = "done",
        data = data,
        overflowRefs = overflowRefs,
    )
}
