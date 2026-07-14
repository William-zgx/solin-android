package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.LongTermMemorySummary
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.memory.MemoryRecordType
import com.bytedance.zgx.solin.memory.SemanticMemoryRuntimeStatus

@Composable
internal fun MemoryTogglePanel(
    state: ChatUiState,
    enabled: Boolean,
    onMemoryEnabledChanged: (Boolean) -> Unit,
    onForgetLongTermMemory: (String) -> Unit,
    onClearLongTermMemory: () -> Unit,
) {
    val memoryModelInstalled = ModelCapability.MemoryEmbedding in state.installedCapabilities
    var confirmClear by rememberSaveable { mutableStateOf(false) }
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
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when {
                            !state.memoryEnabled ->
                                "本地记忆已关闭；已有记录仍可查看和清除，不会参与召回，也不会自动发送到远程模型。"

                            state.semanticMemoryRuntimeStatus == SemanticMemoryRuntimeStatus.Active ->
                                "语义记忆运行时已启用；记忆仍只在本机检索，不会自动发送到远程模型。"

                            state.semanticMemoryRuntimeStatus == SemanticMemoryRuntimeStatus.BuildingIndex ->
                                "正在建立语义记忆索引；完成前仍可使用轻量索引。"

                            state.semanticMemoryRuntimeStatus == SemanticMemoryRuntimeStatus.ProbeFailed ||
                                state.semanticMemoryRuntimeStatus == SemanticMemoryRuntimeStatus.DegradedLexical ->
                                "记忆模型已安装但语义运行时未通过探测，已回退轻量索引。"

                            state.semanticMemoryRuntimeStatus == SemanticMemoryRuntimeStatus.RuntimeUnavailable &&
                                memoryModelInstalled ->
                                "记忆模型资产已安装；当前没有可用 embedding runtime，语义运行时未启用。"

                            else ->
                                "当前使用本地轻量索引；可补装记忆模型资产。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = semanticMemoryIndexStatusText(state),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    modifier = Modifier.testTag("memory_switch"),
                    checked = state.memoryEnabled,
                    onCheckedChange = onMemoryEnabledChanged,
                    enabled = enabled,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

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
                    modifier = Modifier.testTag("long_term_memory_clear_button"),
                    onClick = { confirmClear = true },
                    enabled = enabled && state.longTermMemories.isNotEmpty(),
                ) {
                    Text("清空")
                }
            }

            if (state.longTermMemories.isEmpty()) {
                EmptyPanelText("还没有已保存的长期记忆。")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.longTermMemories.forEach { memory ->
                        LongTermMemoryRow(
                            memory = memory,
                            enabled = enabled,
                            onForget = { onForgetLongTermMemory(memory.id) },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
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
}

@Composable
internal fun LongTermMemoryRow(
    memory: LongTermMemorySummary,
    enabled: Boolean,
    onForget: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("long_term_memory_record_${memory.id}"),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f),
        ),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = memory.type.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
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
}

internal fun MemoryRecordType.label(): String =
    when (this) {
        MemoryRecordType.Preference -> "偏好"
        MemoryRecordType.UserFact -> "事实"
        MemoryRecordType.TaskState -> "任务状态"
        MemoryRecordType.SuppressedTaskState -> "已隐藏任务状态"
        MemoryRecordType.Conversation -> "会话"
    }

