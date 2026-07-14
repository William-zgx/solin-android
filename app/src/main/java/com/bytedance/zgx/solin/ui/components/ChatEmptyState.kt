package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.ui.DeviceCheck
import com.bytedance.zgx.solin.ui.HOME_CAPABILITY_PILLS
import com.bytedance.zgx.solin.ui.HOME_VALUE_PROPOSITIONS
import com.bytedance.zgx.solin.ui.HomeValueKind
import com.bytedance.zgx.solin.ui.PRODUCT_HOME_TITLE_TEXT
import com.bytedance.zgx.solin.ui.PRODUCT_POSITIONING_TEXT
import com.bytedance.zgx.solin.ui.PRODUCT_PROMPT_SUGGESTIONS
import com.bytedance.zgx.solin.ui.pendingSelectedChatDownloadBytes

@Composable
internal fun ChatEmptyState(
    state: ChatUiState,
    onOpenModelManager: () -> Unit,
    onOpenPrivacyNotice: () -> Unit,
    onOpenRemoteModelConfig: () -> Unit,
    onPickModel: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onSendPrompt: (String) -> Unit,
) {
    val localModelIsLoading =
        state.inferenceMode == InferenceMode.Local && state.modelPath != null && !state.isReady
    val readyTitle = when {
        state.isReady -> "想让 Solin 做什么？"
        localModelIsLoading -> "正在校验并加载本地模型"
        else -> PRODUCT_HOME_TITLE_TEXT
    }
    val readyDescription = when {
        state.inferenceMode == InferenceMode.Remote && state.isReady ->
            "直接输入问题、整理线索，或先看看哪些内容会发送到远程。"
        state.isReady ->
            "直接输入问题、整理想法，或先看看哪些内容会留在本机。"
        localModelIsLoading ->
            "模型文件已找到，正在进行完整性校验和端侧初始化；完成后可离线问答。"
        else ->
            "先配置远程模型或下载本地模型；就绪前不会读取本地数据，也不会自动发送远程请求。"
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = if (state.isReady) {
                MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.86f)
            },
            border = BorderStroke(
                width = 1.dp,
                color = if (state.isReady) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
                },
            ),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Text(
                    text = readyTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = readyDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.isReady) {
                    StatusSummaryRow(state)
                    PromptSuggestionList(
                        enabled = !state.isBusy,
                        onSendPrompt = onSendPrompt,
                    )
                } else {
                    QuickModelSetup(
                        state = state,
                        onOpenModelManager = onOpenModelManager,
                        onOpenRemoteModelConfig = onOpenRemoteModelConfig,
                        onPickModel = onPickModel,
                        onDownloadModel = onDownloadModel,
                        onCancelDownload = onCancelDownload,
                    )
                    HomeCapabilityPills()
                }
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_privacy_notice_button"),
                    onClick = onOpenPrivacyNotice,
                    enabled = !state.isBusy,
                ) {
                    SolinGlyph(
                        kind = SolinGlyphKind.Shield,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(" 隐私说明")
                }
                HomePositioningPanel()
            }
        }

        if (!state.isReady) {
            DeviceCheck(
                state = state,
                requiredBytes = state.pendingSelectedChatDownloadBytes(),
            )
        }
    }
}

@Composable
internal fun HomePositioningPanel() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_positioning_panel"),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        SectionTitle(
            text = "放心用的三件事",
            subtitle = PRODUCT_POSITIONING_TEXT,
        )
        HOME_VALUE_PROPOSITIONS.forEach { proposition ->
            TrustBoundaryRow(
                icon = homeValueIcon(proposition.kind),
                title = proposition.title,
                body = proposition.body,
            )
        }
    }
}

private fun homeValueIcon(kind: HomeValueKind): SolinGlyphKind =
    when (kind) {
        HomeValueKind.Local -> SolinGlyphKind.Shield
        HomeValueKind.Remote -> SolinGlyphKind.Spark
        HomeValueKind.Action -> SolinGlyphKind.Check
    }

@Composable
internal fun HomeCapabilityPills() {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_capability_pills"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HOME_CAPABILITY_PILLS.forEach { label ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f)),
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun PromptSuggestionList(
    enabled: Boolean,
    onSendPrompt: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PRODUCT_PROMPT_SUGGESTIONS.forEach { prompt ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.78f),
                enabled = enabled,
                onClick = { onSendPrompt(prompt) },
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, top = 9.dp, end = 8.dp, bottom = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        SolinGlyph(
                            kind = SolinGlyphKind.Spark,
                            modifier = Modifier
                                .padding(6.dp)
                                .size(15.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

