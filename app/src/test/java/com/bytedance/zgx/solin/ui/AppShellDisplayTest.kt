package com.bytedance.zgx.solin.ui

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatSessionSummary
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.resource.ResourcePressure
import com.bytedance.zgx.solin.resource.SystemResourceSnapshot
import com.bytedance.zgx.solin.resource.ThermalPressure
import com.bytedance.zgx.solin.ui.components.compactModelShortName
import com.bytedance.zgx.solin.ui.components.topBarLeavesPhone
import com.bytedance.zgx.solin.ui.components.topBarPlacementStatus
import com.bytedance.zgx.solin.ui.components.topBarSessionTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shell display rules: the top bar states where a run happens in one short word, and the resource
 * badge only appears under pressure. These are plain functions so the rules are pinned without a
 * device.
 */
class AppShellDisplayTest {
    @Test
    fun emptyConversationIsTitledNewChat() {
        assertEquals("新对话", topBarSessionTitle(ChatUiState(isReady = true)))
        // A session whose title never got derived still reads as an empty conversation.
        assertEquals(
            "新对话",
            topBarSessionTitle(
                ChatUiState(
                    isReady = true,
                    activeSessionId = "s1",
                    sessions = listOf(ChatSessionSummary("s1", "  ", 1L, 2)),
                    messages = listOf(userMessage("写周报")),
                ),
            ),
        )
    }

    @Test
    fun conversationTitleComesFromTheActiveSession() {
        assertEquals(
            "写周报",
            topBarSessionTitle(
                ChatUiState(
                    isReady = true,
                    activeSessionId = "s1",
                    sessions = listOf(
                        ChatSessionSummary("s1", "写周报", 1L, 2),
                        ChatSessionSummary("s2", "别的对话", 2L, 4),
                    ),
                    messages = listOf(userMessage("写周报")),
                ),
            ),
        )
    }

    @Test
    fun placementStatusNamesLocalRemoteAutoAndNotReady() {
        val local = ChatUiState(isReady = true, inferenceMode = InferenceMode.Local)
        assertEquals("本机 · E2B", topBarPlacementStatus(local))
        assertEquals(false, topBarLeavesPhone(local))

        val remote = ChatUiState(
            isReady = true,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = RemoteModelConfig(modelName = "deepseek"),
        )
        assertEquals("远程 · deepseek", topBarPlacementStatus(remote))
        assertEquals(true, topBarLeavesPhone(remote))

        val auto = ChatUiState(isReady = true, inferenceMode = InferenceMode.Auto)
        assertEquals("自动", topBarPlacementStatus(auto))
        // Auto has not decided yet, so the mark must not claim either placement.
        assertNull(topBarLeavesPhone(auto))

        val notReady = ChatUiState(isReady = false)
        assertEquals("未就绪", topBarPlacementStatus(notReady))
        assertNull(topBarLeavesPhone(notReady))
    }

    @Test
    fun placementStatusFollowsTheActualRunOverThePreference() {
        val remotePreferenceRunningLocal = ChatUiState(
            isReady = true,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = RemoteModelConfig(modelName = "deepseek"),
            activeRunPlacement = RunPlacement.Local,
            activeRunPlacementReason = PlacementReasonCode.PRIVACY_REQUIRES_LOCAL,
        )
        assertEquals("本机 · E2B", topBarPlacementStatus(remotePreferenceRunningLocal))
        assertEquals(false, topBarLeavesPhone(remotePreferenceRunningLocal))

        val autoRunningRemote = ChatUiState(
            isReady = true,
            inferenceMode = InferenceMode.Auto,
            remoteModelConfig = RemoteModelConfig(modelName = "deepseek"),
            activeRunPlacement = RunPlacement.Remote,
            activeRunPlacementReason = PlacementReasonCode.AUTO_COMPLEX_REMOTE,
        )
        assertEquals("远程 · deepseek", topBarPlacementStatus(autoRunningRemote))
        assertEquals(true, topBarLeavesPhone(autoRunningRemote))
    }

    @Test
    fun blockedPlacementNeverClaimsALocation() {
        val blocked = ChatUiState(
            isReady = true,
            inferenceMode = InferenceMode.Auto,
            activeRunPlacementReason = PlacementReasonCode.NO_ELIGIBLE_TARGET,
        )
        assertEquals("不可用", topBarPlacementStatus(blocked))
        assertNull(topBarLeavesPhone(blocked))
    }

    @Test
    fun downloadStatusShowsProgress() {
        val downloading = ChatUiState(isReady = false, isDownloading = true, downloadProgressPercent = 38)
        assertEquals("下载中 38%", topBarPlacementStatus(downloading))
        assertEquals(
            "下载中",
            topBarPlacementStatus(ChatUiState(isDownloading = true, downloadProgressPercent = null)),
        )
    }

    @Test
    fun longRemoteModelNamesStayOutOfTheTopBar() {
        val longName = ChatUiState(
            isReady = true,
            inferenceMode = InferenceMode.Remote,
            remoteModelConfig = RemoteModelConfig(modelName = "a-very-long-model-name-that-would-overflow"),
        )
        assertEquals("远程", topBarPlacementStatus(longName))
    }

    @Test
    fun modelShortNameIsCompact() {
        assertEquals("E2B", compactModelShortName("基础对话模型 E2B"))
        assertEquals("E4B", compactModelShortName("高质量对话 E4B"))
        assertEquals("模型", compactModelShortName("   "))
    }

    @Test
    fun resourceBadgeOnlyAppearsUnderPressure() {
        assertTrue(ResourcePressure.Warm.isElevated())
        assertTrue(ResourcePressure.Hot.isElevated())
        assertEquals(false, ResourcePressure.Normal.isElevated())

        val quiet = SystemResourceSnapshot(
            appPssBytes = 180L * 1024L * 1024L,
            javaHeapBytes = 40L * 1024L * 1024L,
            nativeHeapBytes = 60L * 1024L * 1024L,
            availableRamBytes = 3L * 1024L * 1024L * 1024L,
            lowMemory = false,
            appCpuPercent = 12,
            thermalPressure = ThermalPressure.Normal,
        )
        assertEquals(ResourcePressure.Normal, quiet.pressure)
        assertEquals(false, quiet.pressure.isElevated())
    }

    private fun userMessage(text: String): ChatMessage = ChatMessage(
        id = 1L,
        role = MessageRole.User,
        text = text,
        privacy = MessagePrivacy.LocalOnly,
    )
}
