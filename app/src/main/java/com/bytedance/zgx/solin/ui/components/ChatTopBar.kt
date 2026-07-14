package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.R
import com.bytedance.zgx.solin.resource.SystemResourceSnapshot
import com.bytedance.zgx.solin.ui.PRODUCT_POSITIONING_SHORT_TEXT
import com.bytedance.zgx.solin.ui.ResourcePressureOverlay
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors

@Composable
internal fun ChatTopBar(
    state: ChatUiState,
    resourceSampler: (suspend () -> SystemResourceSnapshot?)?,
    onOpenModelManager: () -> Unit,
    onOpenPrivacyNotice: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenBackgroundTasks: () -> Unit,
    onCreateSession: () -> Unit,
) {
    val topEdgeColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = topEdgeColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.88f),
        shadowElevation = 0.dp,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compactTopBar = maxWidth < 430.dp
            val actionButtonSize = if (compactTopBar) 30.dp else 36.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(
                        horizontal = if (compactTopBar) 8.dp else 14.dp,
                        vertical = if (compactTopBar) 4.dp else 5.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(if (compactTopBar) 4.dp else 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    modifier = Modifier
                        .size(if (compactTopBar) 32.dp else 38.dp),
                    painter = painterResource(R.drawable.solin_brand_mark),
                    contentDescription = null,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = if (compactTopBar) 56.dp else 132.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    Text(
                        modifier = Modifier.testTag("app_title"),
                        text = "Solin",
                        style = if (compactTopBar) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        modifier = Modifier.testTag("app_positioning_subtitle"),
                        text = if (compactTopBar) "隐私优先" else PRODUCT_POSITIONING_SHORT_TEXT,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (resourceSampler != null) {
                    ResourcePressureOverlay(
                        resourceSampler = resourceSampler,
                        modifier = Modifier.size(24.dp),
                    )
                }
                CompactModelStatusChip(
                    modifier = Modifier.widthIn(
                        min = if (compactTopBar) 58.dp else 96.dp,
                        max = if (compactTopBar) 78.dp else 148.dp,
                    ),
                    state = state,
                    compact = compactTopBar,
                    onClick = onOpenModelManager,
                )
                TopActionButton(
                    modifier = Modifier.testTag("top_session_button"),
                    glyph = SolinGlyphKind.Chat,
                    label = "会话",
                    size = actionButtonSize,
                    onClick = onOpenSessions,
                )
                Box {
                    TopActionButton(
                        modifier = Modifier.testTag("top_more_button"),
                        glyph = SolinGlyphKind.More,
                        label = "更多",
                        size = actionButtonSize,
                        onClick = { menuExpanded = true },
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.widthIn(min = 152.dp, max = 164.dp),
                    ) {
                        TopMenuItem(
                            modifier = Modifier.testTag("top_create_session_button"),
                            glyph = SolinGlyphKind.Add,
                            label = "新建会话",
                            enabled = !state.isBusy,
                            onClick = {
                                menuExpanded = false
                                onCreateSession()
                            },
                        )
                        TopMenuItem(
                            modifier = Modifier.testTag("top_model_menu_button"),
                            glyph = SolinGlyphKind.Spark,
                            label = "模型管理",
                            onClick = {
                                menuExpanded = false
                                onOpenModelManager()
                            },
                        )
                        TopMenuItem(
                            modifier = Modifier.testTag("top_privacy_button"),
                            glyph = SolinGlyphKind.Shield,
                            label = "隐私说明",
                            onClick = {
                                menuExpanded = false
                                onOpenPrivacyNotice()
                            },
                        )
                        TopMenuItem(
                            modifier = Modifier.testTag("top_background_tasks_button"),
                            glyph = SolinGlyphKind.Bell,
                            label = "后台任务",
                            onClick = {
                                menuExpanded = false
                                onOpenBackgroundTasks()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompactModelStatusChip(
    state: ChatUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val active = state.isReady && !state.isBusy
    val semanticColors = LocalSolinColors.current
    val container = when {
        active && state.inferenceMode == InferenceMode.Remote -> semanticColors.remoteContainer
        active -> semanticColors.localContainer
        state.isBusy || state.isDownloading -> semanticColors.busyContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        active && state.inferenceMode == InferenceMode.Remote -> semanticColors.onRemoteContainer
        active -> semanticColors.onLocalContainer
        state.isBusy || state.isDownloading -> semanticColors.onBusyContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .testTag("top_model_button")
            .semantics {
                contentDescription = "模型管理"
                stateDescription = compactModelStatus(state)
            },
        shape = CircleShape,
        color = container,
        border = BorderStroke(1.dp, content.copy(alpha = 0.18f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(content),
            )
            Text(
                text = if (compact) compactModelStatusShort(state) else compactModelStatus(state),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun TopMenuItem(
    glyph: SolinGlyphKind,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SolinGlyph(
            modifier = Modifier.size(20.dp),
            kind = glyph,
            tint = contentColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun TopActionButton(
    glyph: SolinGlyphKind,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
) {
    IconButton(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .semantics {
                contentDescription = label
            },
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
        ),
    ) {
        SolinGlyph(
            kind = glyph,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.56f),
        )
    }
}

@Composable
internal fun RuntimeStatusBadge(state: ChatUiState) {
    val active = state.isReady && !state.isBusy
    val label = when {
        state.isDownloading -> state.downloadProgressPercent?.let { "下载 $it%" } ?: "下载中"
        state.isBusy -> "处理中"
        state.inferenceMode == InferenceMode.Remote && state.isReady -> "远程可用"
        state.isReady -> "离线可用"
        state.inferenceMode == InferenceMode.Remote -> "待配置"
        state.modelPath != null -> "可加载"
        else -> "待准备"
    }
    val semanticColors = LocalSolinColors.current
    val container = when {
        active && state.inferenceMode == InferenceMode.Remote -> semanticColors.remoteContainer
        active -> semanticColors.localContainer
        state.isBusy || state.isDownloading -> semanticColors.busyContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        active && state.inferenceMode == InferenceMode.Remote -> semanticColors.onRemoteContainer
        active -> semanticColors.onLocalContainer
        state.isBusy || state.isDownloading -> semanticColors.onBusyContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = CircleShape,
        color = container,
        border = BorderStroke(1.dp, content.copy(alpha = 0.16f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(content),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = content,
                maxLines = 1,
            )
        }
    }
}

private fun compactModelStatus(state: ChatUiState): String {
    val ready = when {
        state.isDownloading -> state.downloadProgressPercent?.let { "下载 $it%" } ?: "下载中"
        state.isBusy -> state.statusText
        state.isReady -> "可用"
        state.inferenceMode == InferenceMode.Remote -> "待配置"
        state.modelPath != null -> "待加载"
        else -> "待准备"
    }
    if (state.inferenceMode == InferenceMode.Remote) {
        val modelName = state.remoteModelConfig.modelName.ifBlank { "远程模型" }
        return "$modelName · $ready"
    }
    val modelName = state.installedModels.firstOrNull { it.id == state.activeInstalledModelId }?.displayName
        ?: state.selectedRecommendedModel.shortName
    return "$modelName · ${state.backend.name} · $ready"
}

private fun compactModelStatusShort(state: ChatUiState): String =
    when {
        state.isDownloading -> state.downloadProgressPercent?.let { "下载 $it%" } ?: "下载中"
        state.isBusy -> "处理中"
        state.inferenceMode == InferenceMode.Remote && state.isReady -> "远程"
        state.inferenceMode == InferenceMode.Remote -> "待配置"
        state.isReady -> "本地"
        state.modelPath != null -> "待加载"
        else -> "待准备"
    }


