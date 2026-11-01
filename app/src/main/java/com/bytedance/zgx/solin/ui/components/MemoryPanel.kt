package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.LongTermMemorySummary
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.memory.MemoryFactSuggestion
import com.bytedance.zgx.solin.memory.MemoryRecordType
import com.bytedance.zgx.solin.memory.SemanticMemoryRuntimeStatus

@Composable
internal fun MemoryTogglePanel(
    state: ChatUiState,
    enabled: Boolean,
    onMemoryEnabledChanged: (Boolean) -> Unit,
    onForgetLongTermMemory: (String) -> Unit,
    onClearLongTermMemory: () -> Unit,
    /** Shown when the last agent-run memory retrieval degraded (see `MemoryPanelState`). */
    retrievalStatusText: String? = null,
    /** "Remember this?" suggestions; nothing is stored until [onConfirmSuggestion] is tapped. */
    suggestions: List<MemoryFactSuggestion> = emptyList(),
    onConfirmSuggestion: (String) -> Unit = {},
    onDismissSuggestion: (String) -> Unit = {},
) {
    val memoryModelInstalled = ModelCapability.MemoryEmbedding in state.installedCapabilities
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var pendingForget by remember { mutableStateOf<LongTermMemorySummary?>(null) }
    PanelSurface {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "本地记忆",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = when {
                            !state.memoryEnabled ->
                                "已关闭；已有记录仍可查看和清除，不参与召回。"

                            state.semanticMemory.runtimeStatus == SemanticMemoryRuntimeStatus.Active ->
                                "语义记忆已启用；只在本机检索。"

                            state.semanticMemory.runtimeStatus == SemanticMemoryRuntimeStatus.BuildingIndex ->
                                "正在建立语义索引；完成前用轻量索引。"

                            state.semanticMemory.runtimeStatus == SemanticMemoryRuntimeStatus.ProbeFailed ||
                                state.semanticMemory.runtimeStatus == SemanticMemoryRuntimeStatus.DegradedLexical ->
                                "语义运行时未通过探测，已回退轻量索引。"

                            state.semanticMemory.runtimeStatus == SemanticMemoryRuntimeStatus.RuntimeUnavailable &&
                                memoryModelInstalled ->
                                "记忆模型已安装；当前没有可用 embedding runtime。"

                            else ->
                                "当前使用本地轻量索引。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = semanticMemoryIndexStatusText(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (retrievalStatusText != null) {
                        Text(
                            modifier = Modifier.testTag("memory_retrieval_status"),
                            text = retrievalStatusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Switch(
                    modifier = Modifier.testTag("memory_switch"),
                    checked = state.memoryEnabled,
                    onCheckedChange = onMemoryEnabledChanged,
                    enabled = enabled,
                )
            }

            SheetRowDivider()

            if (suggestions.isNotEmpty() && state.memoryEnabled) {
                SectionTitle(
                    text = "要记住吗？",
                    subtitle = "从你的消息中发现的信息；确认后才会保存在本机。",
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    suggestions.forEach { suggestion ->
                        MemorySuggestionRow(
                            suggestion = suggestion,
                            enabled = enabled,
                            onConfirm = { onConfirmSuggestion(suggestion.id) },
                            onDismiss = { onDismissSuggestion(suggestion.id) },
                        )
                    }
                }
                SheetRowDivider()
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    SectionTitle(
                        text = "长期记忆",
                        subtitle = "已保存 ${state.longTermMemories.size} 条偏好或任务状态。",
                    )
                }
                TextButton(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("long_term_memory_clear_button"),
                    onClick = { confirmClear = true },
                    enabled = enabled && state.longTermMemories.isNotEmpty(),
                ) {
                    Text("清空")
                }
            }

            if (state.longTermMemories.isEmpty()) {
                EmptyPanelText("还没有已保存的长期记忆。")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.longTermMemories.forEachIndexed { index, memory ->
                        if (index > 0) SheetRowDivider()
                        LongTermMemoryRow(
                            memory = memory,
                            enabled = enabled,
                            onForget = { pendingForget = memory },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            modifier = Modifier.testTag("long_term_memory_clear_dialog"),
            onDismissRequest = { confirmClear = false },
            title = { Text("清空长期记忆") },
            text = { Text("只会清空已保存的偏好和任务状态记录，会话历史不会删除。") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("long_term_memory_confirm_clear_button"),
                    enabled = enabled,
                    onClick = {
                        confirmClear = false
                        onClearLongTermMemory()
                    },
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("取消")
                }
            },
        )
    }

    // Forgetting one record is irreversible and has no undo state, so it asks first.
    pendingForget?.let { memory ->
        AlertDialog(
            modifier = Modifier.testTag("long_term_memory_forget_dialog"),
            onDismissRequest = { pendingForget = null },
            title = { Text("忘记这条记忆？") },
            text = { Text("删除后无法恢复：${memory.text.take(80)}") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("long_term_memory_confirm_forget_button"),
                    enabled = enabled,
                    onClick = {
                        pendingForget = null
                        onForgetLongTermMemory(memory.id)
                    },
                ) {
                    Text("忘记")
                }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag("long_term_memory_cancel_forget_button"),
                    onClick = { pendingForget = null },
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
internal fun MemorySuggestionRow(
    suggestion: MemoryFactSuggestion,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("memory_suggestion_${suggestion.id}"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = suggestion.fact,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            modifier = Modifier.testTag("memory_suggestion_dismiss_${suggestion.id}"),
            onClick = onDismiss,
            enabled = enabled,
        ) {
            Text("忽略")
        }
        TextButton(
            modifier = Modifier.testTag("memory_suggestion_confirm_${suggestion.id}"),
            onClick = onConfirm,
            enabled = enabled,
        ) {
            Text("记住")
        }
    }
}

@Composable
internal fun LongTermMemoryRow(
    memory: LongTermMemorySummary,
    enabled: Boolean,
    onForget: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("long_term_memory_record_${memory.id}")
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = memory.type.label(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = memory.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            modifier = Modifier
                .size(48.dp)
                .testTag("long_term_memory_forget_${memory.id}")
                .semantics { contentDescription = "遗忘这条记忆：${memory.text}" },
            onClick = onForget,
            enabled = enabled,
        ) {
            SolinGlyph(
                kind = SolinGlyphKind.Delete,
                tint = LocalContentColor.current,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

internal fun MemoryRecordType.label(): String =
    when (this) {
        MemoryRecordType.Preference -> "偏好"
        MemoryRecordType.UserFact -> "事实"
        MemoryRecordType.TaskState -> "任务状态"
        MemoryRecordType.SuppressedTaskState -> "已隐藏任务状态"
        MemoryRecordType.Conversation -> "会话"
    }
