package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.data.DecisionModelRoutingState
import com.bytedance.zgx.solin.orchestration.DecisionModelRouting

internal const val DECISION_MODEL_ROUTING_TITLE = "云端路由（Codiv OpenJev）"

internal const val DECISION_MODEL_ROUTING_SUBTITLE =
    "仅在“自动”推理偏好下生效。规则先判断：隐私、图片、资源压力仍按本机规则处理；只有本机和云端都可用、" +
        "且规则按复杂度二选一时，才把本条请求的文字（不含历史、记忆、附件，疑似个人或密钥内容不发）发给 " +
        "Codiv 决策模型。置信度达到 0.9 才采用，否则保持规则结果。每次外发记入远程发送记录。"

/** Trust Center control for the cloud/edge router: key, then Off / Shadow / Active. */
@Composable
internal fun DecisionModelRoutingSection(
    state: DecisionModelRoutingState,
    enabled: Boolean,
    onModeSelected: (DecisionModelRouting) -> Unit,
    onApiKeySaved: (String) -> Unit,
) {
    var keyInput by remember { mutableStateOf("") }
    PanelSurface {
        Column(
            modifier = Modifier.testTag("decision_model_routing_section"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(text = DECISION_MODEL_ROUTING_TITLE, subtitle = DECISION_MODEL_ROUTING_SUBTITLE)
            if (state.keyConfigured) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecisionModelRouting.entries.forEach { mode ->
                        FilterChip(
                            modifier = Modifier.testTag("decision_model_routing_${mode.name}"),
                            selected = state.mode == mode,
                            onClick = { onModeSelected(mode) },
                            label = { Text(mode.label()) },
                            enabled = enabled,
                        )
                    }
                }
                Text(
                    text = "本次启动：调用 ${state.calls} 次，不可用 ${state.unavailable} 次，" +
                        "与规则不同 ${state.disagreedWithRules} 次，已采用 ${state.applied} 次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    modifier = Modifier.testTag("decision_model_routing_clear_key"),
                    enabled = enabled,
                    onClick = { onApiKeySaved("") },
                ) {
                    Text("清除 API Key 并关闭")
                }
            } else {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("decision_model_routing_key_input"),
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    enabled = enabled,
                    maxLines = 1,
                    placeholder = { Text("粘贴 Codiv API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                Button(
                    modifier = Modifier.testTag("decision_model_routing_save_key"),
                    enabled = enabled && keyInput.isNotBlank(),
                    onClick = {
                        onApiKeySaved(keyInput)
                        keyInput = ""
                    },
                ) {
                    Text("保存（加密存储在本机）")
                }
            }
        }
    }
}

private fun DecisionModelRouting.label(): String = when (this) {
    DecisionModelRouting.Off -> "关闭"
    DecisionModelRouting.Shadow -> "只观察"
    DecisionModelRouting.Active -> "启用"
}
