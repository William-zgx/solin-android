package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.orchestration.AgentRunOptions
import com.bytedance.zgx.solin.orchestration.AssistantRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreparedChatExecutionProjectionTest {
    @Test
    fun promptOverrideClearsRemoteProjection() {
        val execution = PreparedChatExecution(
            userPrompt = "原始",
            route = AssistantRoute.Chat(
                runId = "run",
                promptForModel = "wrapped 原始",
                memoryHits = emptyList(),
                remotePromptForModel = "原始"
            ),
            stateBeforeSend = ChatUiState(),
            effectiveMessagePrivacy = MessagePrivacy.RemoteEligible,
            remoteConfig = RemoteModelConfig(),
            remoteHistory = emptyList(),
            imageAttachments = emptyList(),
            localImageAttachments = emptyList(),
            includePrivateLocalContext = false,
            agentRunOptions = AgentRunOptions(),
            currentPromptEvidenceSummary = null,
            remoteSendConfirmed = false
        )
        assertEquals("原始", execution.routeForDispatch.remotePromptForModel)

        execution.overridePrompt("已遮蔽")

        assertEquals("已遮蔽", execution.routeForDispatch.promptForModel)
        assertNull(execution.routeForDispatch.remotePromptForModel)
    }
}
