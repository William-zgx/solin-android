package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ChatUiState

/**
 * The model manager tab that owns memory settings. ModelManagerSheet keeps the memory controls in
 * its "高级" tab (index 3); this alias names the destination instead of the tab position.
 */
internal const val MODEL_MANAGER_MEMORY_TAB_INDEX = 3

/**
 * The session drawer: conversations on top, then the app-wide destinations the top bar no longer
 * carries (models, memory, privacy, background tasks). Rendered inside a ModalNavigationDrawer.
 */
@Composable
internal fun SessionDrawer(
    state: ChatUiState,
    onCreateSession: () -> Unit,
    onSessionSelected: (String) -> Unit,
    onOpenModelManager: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenPrivacyNotice: () -> Unit,
    onOpenBackgroundTasks: () -> Unit,
    onRequestDeleteSession: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("session_manager_title"),
                text = "会话",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
            CloseIconButton(
                testTag = "session_manager_close_button",
                contentDescription = "关闭会话",
                enabled = !state.isBusy,
                onClick = onClose,
            )
        }
        TextButton(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("session_create_button"),
            onClick = onCreateSession,
            enabled = !state.isBusy,
        ) {
            GlyphLabel(SolinGlyphKind.Add, "新对话")
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            state.sessions.forEach { session ->
                SessionDrawerRow(
                    title = session.title,
                    subtitle = if (session.messageCount == 0) {
                        "空会话"
                    } else {
                        "${session.messageCount} 条消息"
                    },
                    selected = session.id == state.activeSessionId,
                    enabled = !state.isBusy,
                    testTag = "session_row_${session.id}",
                    onClick = { onSessionSelected(session.id) },
                )
            }
        }

        if (state.activeSessionId != null) {
            // Kept in the drawer (not only in the per-session menu) so deleting the open
            // conversation is always one step away; the confirmation dialog is what guards it.
            TextButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("session_delete_entry_button"),
                onClick = onRequestDeleteSession,
                enabled = !state.isBusy,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("删除当前会话", color = MaterialTheme.colorScheme.error)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        DrawerDestinationRow(
            testTag = "top_model_menu_button",
            glyph = SolinGlyphKind.Spark,
            label = "模型管理",
            enabled = !state.isBusy,
            onClick = onOpenModelManager,
        )
        DrawerDestinationRow(
            testTag = "drawer_memory_button",
            glyph = SolinGlyphKind.Memory,
            label = "记忆",
            enabled = !state.isBusy,
            onClick = onOpenMemory,
        )
        DrawerDestinationRow(
            testTag = "top_privacy_button",
            glyph = SolinGlyphKind.Shield,
            label = "隐私与权限",
            enabled = !state.isBusy,
            onClick = onOpenPrivacyNotice,
        )
        DrawerDestinationRow(
            testTag = "top_background_tasks_button",
            glyph = SolinGlyphKind.Bell,
            label = "后台任务",
            enabled = !state.isBusy,
            onClick = onOpenBackgroundTasks,
        )
    }
}

@Composable
private fun SessionDrawerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val titleColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = { if (enabled) onClick() },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .semantics {
                contentDescription = title
            },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            Color.Transparent
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DrawerDestinationRow(
    testTag: String,
    glyph: SolinGlyphKind,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Surface(
        onClick = {
            if (enabled) onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .semantics { contentDescription = label },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp),
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
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Deleting a conversation cannot be undone, so it takes an explicit confirmation. The destructive
 * action is labelled with the outcome ("删除") rather than a generic "确认执行".
 */
@Composable
internal fun DeleteSessionConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("session_delete_confirm_dialog"),
        onDismissRequest = onDismiss,
        title = { Text("删除这个对话？") },
        text = { Text("删除后无法恢复。") },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("session_delete_button"),
                onClick = onConfirm,
            ) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("session_delete_cancel_button"),
                onClick = onDismiss,
            ) {
                Text("取消")
            }
        },
    )
}

