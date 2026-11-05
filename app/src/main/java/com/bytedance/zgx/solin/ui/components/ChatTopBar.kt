package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.resource.SystemResourceSnapshot
import com.bytedance.zgx.solin.ui.activePlacementDisplayText
import com.bytedance.zgx.solin.ui.ResourcePressureOverlay

/**
 * The app shell's top bar: menu (opens the session drawer), the current conversation title,
 * the placement pill (tap = model manager), and per-session actions.
 *
 * Buttons keep a 48dp touch target even when the row is tight; the placement pill states where
 * this run happens in one short word and carries the full explanation in its state description.
 */
@Composable
internal fun ChatTopBar(
    state: ChatUiState,
    resourceSampler: (suspend () -> SystemResourceSnapshot?)?,
    onOpenSessions: () -> Unit,
    onOpenModelManager: () -> Unit,
    onCreateSession: () -> Unit,
    onDeleteSession: () -> Unit,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopActionButton(
            modifier = Modifier.testTag("top_session_button"),
            glyph = SolinGlyphKind.Menu,
            label = "会话",
            size = 48.dp,
            onClick = onOpenSessions,
        )
        Text(
            modifier = Modifier
                .weight(1f)
                .padding(start = 2.dp)
                .testTag("app_title"),
            text = topBarSessionTitle(state),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (resourceSampler != null) {
            // The badge hides itself unless the device is under pressure; it keeps its 48dp target.
            ResourcePressureOverlay(resourceSampler = resourceSampler)
        }
        PlacementPill(
            modifier = Modifier
                .widthIn(max = 132.dp)
                .testTag("top_model_button")
                .semantics {
                    contentDescription = "模型管理"
                    stateDescription = placementStateDescription(state)
                },
            label = topBarPlacementStatus(state),
            leavesPhone = topBarLeavesPhone(state),
            onClick = onOpenModelManager,
        )
        Box {
            TopActionButton(
                modifier = Modifier.testTag("top_more_button"),
                glyph = SolinGlyphKind.More,
                label = "更多",
                size = 48.dp,
                onClick = { menuExpanded = true },
            )
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.widthIn(min = 152.dp, max = 172.dp),
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
                    modifier = Modifier.testTag("top_delete_session_button"),
                    glyph = SolinGlyphKind.Delete,
                    label = "删除会话",
                    enabled = !state.isBusy && state.activeSessionId != null,
                    onClick = {
                        menuExpanded = false
                        onDeleteSession()
                    },
                )
            }
        }
    }
}

/** The conversation title, or "新对话" while the active session has no messages yet. */
internal fun topBarSessionTitle(state: ChatUiState): String {
    if (state.messages.isEmpty()) return "新对话"
    return state.sessions
        .firstOrNull { it.id == state.activeSessionId }
        ?.title
        ?.takeIf { it.isNotBlank() }
        ?: "新对话"
}

/**
 * One short word for where this run happens: "本机 · E2B", "远程", "自动", "下载中 38%",
 * "未就绪". The full reason sentence lives in the pill's state description.
 */
internal fun topBarPlacementStatus(state: ChatUiState): String = when {
    state.isDownloading -> state.downloadShortLabel()
    state.isBusy -> compactModelStatusShort(state)

    state.activeRunPlacement == RunPlacement.Remote -> "远程" + state.remoteModelSuffix()
    state.activeRunPlacement == RunPlacement.Local -> "本机 · " + state.localModelShortName()
    state.activeRunPlacementReason != null -> "不可用"
    !state.isReady -> "未就绪"
    state.inferenceMode == InferenceMode.Remote -> "远程" + state.remoteModelSuffix()
    state.inferenceMode == InferenceMode.Auto -> "自动"
    else -> "本机 · " + state.localModelShortName()
}

/**
 * Filled dot = this phone, ring = leaves the phone, null = neutral (not decided / not ready).
 */
internal fun topBarLeavesPhone(state: ChatUiState): Boolean? = when {
    state.activeRunPlacement == RunPlacement.Remote -> true
    state.activeRunPlacement == RunPlacement.Local -> false
    state.activeRunPlacementReason != null -> null
    !state.isReady -> null
    state.inferenceMode == InferenceMode.Remote -> true
    state.inferenceMode == InferenceMode.Local -> false
    else -> null
}

internal fun placementStateDescription(state: ChatUiState): String =
    activePlacementDisplayText(state.activeRunPlacement, state.activeRunPlacementReason)
        ?: "本次运行位置尚未确定"

internal fun ChatUiState.downloadShortLabel(): String =
    downloadProgressPercent?.let { "下载中 $it%" } ?: "下载中"

/** "基础对话 E2B" -> "E2B"; unknown names keep their first characters so the pill stays short. */
internal fun compactModelShortName(displayName: String): String =
    Regex("""E\d+B""").find(displayName)?.value
        ?: displayName.trim().take(8).ifBlank { "模型" }

private fun ChatUiState.localModelShortName(): String {
    val name = installedModels
        .firstOrNull { it.id == activeInstalledModelId }
        ?.displayName
        ?: selectedRecommendedModel.shortName
    return compactModelShortName(name)
}

private fun ChatUiState.remoteModelSuffix(): String =
    remoteModelConfig.modelName
        .trim()
        .takeIf { it.isNotBlank() && it.length <= 12 }
        ?.let { " · $it" }
        .orEmpty()

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
    size: Dp,
    modifier: Modifier = Modifier,
) {
    IconButton(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .semantics {
                contentDescription = label
            },
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        SolinGlyph(
            kind = glyph,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.56f),
        )
    }
}

internal fun compactModelStatusShort(
    state: ChatUiState,
): String = when {
    state.isDownloading -> state.downloadShortLabel()
    state.activeRunPlacement == RunPlacement.Remote -> "${state.inferenceMode.label()}→远程"
    state.activeRunPlacement == RunPlacement.Local -> "${state.inferenceMode.label()}→本地"
    state.activeRunPlacementReason != null -> "${state.inferenceMode.label()}·阻断"
    state.isBusy -> "${state.inferenceMode.label()}·处理中"
    else -> "${state.inferenceMode.label()}偏好"
}
