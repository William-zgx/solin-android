package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ChatUiState

@Composable
internal fun SessionManagerSheet(
    state: ChatUiState,
    onCreateSession: () -> Unit,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetColumn(spacing = 12.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier
                    .weight(1f)
                    .testTag("session_manager_title"),
                text = "会话",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(
                modifier = Modifier.testTag("session_create_button"),
                onClick = onCreateSession,
                enabled = !state.isBusy,
            ) {
                Text("新建")
            }
            CloseIconButton(
                testTag = "session_manager_close_button",
                contentDescription = "关闭会话管理",
                enabled = !state.isBusy,
                onClick = onDismiss,
            )
        }
        state.sessions.forEach { session ->
            ModelRow(
                title = session.title,
                subtitle = if (session.messageCount == 0) "空会话" else "${session.messageCount} 条消息",
                selected = session.id == state.activeSessionId,
                enabled = !state.isBusy,
                onClick = { onSessionSelected(session.id) },
            )
        }
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("session_delete_button"),
            onClick = onDeleteSession,
            enabled = !state.isBusy,
        ) {
            Text("删除当前会话")
        }
    }
}

