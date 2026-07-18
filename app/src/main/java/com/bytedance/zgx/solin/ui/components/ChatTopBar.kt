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
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.resource.SystemResourceSnapshot
import com.bytedance.zgx.solin.ui.activePlacementDisplayText
import com.bytedance.zgx.solin.ui.inferencePreferenceDisplayText
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
    activeRunPlacement: RunPlacement?,
    activeRunPlacementReason: PlacementReasonCode?,
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
                        max = if (compactTopBar) 96.dp else 148.dp,
                    ),
                    state = state,
                    compact = compactTopBar,
                    activeRunPlacement = activeRunPlacement,
                    activeRunPlacementReason = activeRunPlacementReason,
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
    activeRunPlacement: RunPlacement? = null,
    activeRunPlacementReason: PlacementReasonCode? = null,
) {
    val active = state.isReady && !state.isBusy
    val semanticColors = LocalSolinColors.current
    val container = when {
        state.isBusy || state.isDownloading -> semanticColors.busyContainer
        active && activeRunPlacement == RunPlacement.Remote -> semanticColors.remoteContainer
        active && activeRunPlacement == RunPlacement.Local -> semanticColors.localContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        state.isBusy || state.isDownloading -> semanticColors.onBusyContainer
        active && activeRunPlacement == RunPlacement.Remote -> semanticColors.onRemoteContainer
        active && activeRunPlacement == RunPlacement.Local -> semanticColors.onLocalContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .testTag("top_model_button")
            .semantics {
                contentDescription = "模型管理"
                stateDescription = compactModelStatus(
                    state = state,
                    activeRunPlacement = activeRunPlacement,
                    activeRunPlacementReason = activeRunPlacementReason,
                )
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
                text = if (compact) {
                    compactModelStatusShort(state, activeRunPlacement, activeRunPlacementReason)
                } else {
                    compactModelStatus(state, activeRunPlacement, activeRunPlacementReason)
                },
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
internal fun RuntimeStatusBadge(
    state: ChatUiState,
    activeRunPlacement: RunPlacement? = null,
    activeRunPlacementReason: PlacementReasonCode? = null,
) {
    val active = state.isReady && !state.isBusy
    val label = when {
        state.isDownloading -> state.downloadProgressPercent?.let { "下载 $it%" } ?: "下载中"
        state.isBusy -> "处理中"
        activeRunPlacement == RunPlacement.Remote -> "本次远程"
        activeRunPlacement == RunPlacement.Local -> "本次本地"
        activeRunPlacementReason != null -> "本次已阻断"
        state.inferenceMode == InferenceMode.Remote -> "远程偏好"
        state.inferenceMode == InferenceMode.Auto -> "自动偏好"
        state.isReady -> "本地偏好"
        state.modelPath != null -> "可加载"
        else -> "待准备"
    }
    val semanticColors = LocalSolinColors.current
    val container = when {
        state.isBusy || state.isDownloading -> semanticColors.busyContainer
        active && activeRunPlacement == RunPlacement.Remote -> semanticColors.remoteContainer
        active && activeRunPlacement == RunPlacement.Local -> semanticColors.localContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        state.isBusy || state.isDownloading -> semanticColors.onBusyContainer
        active && activeRunPlacement == RunPlacement.Remote -> semanticColors.onRemoteContainer
        active && activeRunPlacement == RunPlacement.Local -> semanticColors.onLocalContainer
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

private fun compactModelStatus(
    state: ChatUiState,
    activeRunPlacement: RunPlacement?,
    activeRunPlacementReason: PlacementReasonCode?,
): String = listOfNotNull(
    inferencePreferenceDisplayText(state.inferenceMode),
    activePlacementDisplayText(activeRunPlacement, activeRunPlacementReason),
).joinToString(separator = "；")

internal fun compactModelStatusShort(
    state: ChatUiState,
    activeRunPlacement: RunPlacement?,
    activeRunPlacementReason: PlacementReasonCode?,
): String = when {
    state.isDownloading -> state.downloadProgressPercent?.let { "下载 $it%" } ?: "下载中"
    activeRunPlacement == RunPlacement.Remote -> "${state.inferenceMode.label()}→远程"
    activeRunPlacement == RunPlacement.Local -> "${state.inferenceMode.label()}→本地"
    activeRunPlacementReason != null -> "${state.inferenceMode.label()}·阻断"
    state.isBusy -> "${state.inferenceMode.label()}·处理中"
    else -> "${state.inferenceMode.label()}偏好"
}
