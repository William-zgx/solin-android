package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.RemoteSendAuditSummary
import com.bytedance.zgx.solin.RemoteSendDisclosurePolicy
import com.bytedance.zgx.solin.mcp.McpCapabilityState
import com.bytedance.zgx.solin.memory.SemanticMemoryRuntimeStatus
import com.bytedance.zgx.solin.ui.PRIVACY_POLICY_ENTRY_TEXT
import com.bytedance.zgx.solin.ui.PRODUCT_ACTION_VALUE_TEXT
import com.bytedance.zgx.solin.ui.PRODUCT_LOCAL_VALUE_TEXT
import com.bytedance.zgx.solin.ui.PRODUCT_POSITIONING_TEXT
import com.bytedance.zgx.solin.ui.PRODUCT_REMOTE_VALUE_TEXT
import com.bytedance.zgx.solin.ui.SENSITIVE_CAPABILITY_DISCLOSURE_TEXT
import com.bytedance.zgx.solin.ui.TRUST_CENTER_CAPABILITY_TEXT
import com.bytedance.zgx.solin.ui.TRUST_LOCAL_BOUNDARY_TEXT
import com.bytedance.zgx.solin.ui.TRUST_PERMISSION_BOUNDARY_TEXT
import com.bytedance.zgx.solin.ui.TRUST_REMOTE_BOUNDARY_TEXT
import com.bytedance.zgx.solin.ui.sensitiveCapabilityDisclosureDisplayRows
import com.bytedance.zgx.solin.ui.trustCenterCapabilityDisplayRows
import com.bytedance.zgx.solin.orchestration.DecisionModelRouting
import com.bytedance.zgx.solin.data.DecisionModelRoutingState

@Composable
internal fun TrustBoundaryPanel(
    state: ChatUiState,
    onRemoteSendDisclosurePolicySelected: (RemoteSendDisclosurePolicy) -> Unit,
    onReduceDeviceActionConfirmationsChanged: (Boolean) -> Unit,
    onRemoteGuiAutomationEnabledChanged: (Boolean) -> Unit,
    /** MCP opt-in toggle state; `null` hides the section until the caller wires it. */
    mcpCapability: McpCapabilityToggleUiState? = null,
    /** `(enabled, disclosureAccepted)`; enabling the first time requires the disclosure. */
    onMcpCapabilityEnabledChanged: (Boolean, Boolean) -> Unit = { _, _ -> },
    /** Cloud/edge router state; `null` hides the section until the caller wires it. */
    decisionModelRouting: DecisionModelRoutingState? = null,
    onDecisionModelRoutingModeSelected: (DecisionModelRouting) -> Unit = {},
    onDecisionModelRoutingKeySaved: (String) -> Unit = {},
) {
    val sensitiveDisclosureRows = sensitiveCapabilityDisclosureDisplayRows()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(
                    text = "为什么装它",
                    subtitle = PRODUCT_POSITIONING_TEXT,
                )
                TrustBoundaryRow(
                    icon = SolinGlyphKind.Shield,
                    title = "本地可用",
                    body = PRODUCT_LOCAL_VALUE_TEXT,
                )
                TrustBoundaryRow(
                    icon = SolinGlyphKind.Spark,
                    title = "远程多模态可选",
                    body = PRODUCT_REMOTE_VALUE_TEXT,
                )
                TrustBoundaryRow(
                    icon = SolinGlyphKind.Check,
                    title = "动作确认执行",
                    body = PRODUCT_ACTION_VALUE_TEXT,
                )
            }
        }
        PanelSurface {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(
                    text = "能力与信任中心",
                    subtitle = TRUST_CENTER_CAPABILITY_TEXT,
                )
                trustCenterCapabilityDisplayRows().forEach { row ->
                    TrustBoundaryRow(
                        icon = SolinGlyphKind.Shield,
                        title = row.title,
                        body = row.body,
                    )
                }
            }
        }
        PanelSurface {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(
                    text = "隐私说明",
                    subtitle = PRIVACY_POLICY_ENTRY_TEXT,
                )
                TrustBoundaryRow(
                    icon = SolinGlyphKind.Shield,
                    title = "本地优先",
                    body = TRUST_LOCAL_BOUNDARY_TEXT,
                )
                TrustBoundaryRow(
                    icon = SolinGlyphKind.Spark,
                    title = "远程模型",
                    body = TRUST_REMOTE_BOUNDARY_TEXT,
                )
                TrustBoundaryRow(
                    icon = SolinGlyphKind.Check,
                    title = "敏感权限",
                    body = TRUST_PERMISSION_BOUNDARY_TEXT,
                )
                TrustBoundaryRow(
                    icon = SolinGlyphKind.Delete,
                    title = "用户控制",
                    body = trustDeletionBoundaryText(state),
                )
            }
        }
        PanelSurface {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(
                    text = "远程模式提醒",
                    subtitle = "默认切换到远程模型时提醒一次；疑似敏感内容发送仍会单独确认。",
                )
                RemoteSendDisclosurePolicySelector(
                    selectedPolicy = state.remoteSendDisclosurePolicy,
                    enabled = !state.isBusy,
                    onSelected = onRemoteSendDisclosurePolicySelected,
                )
            }
        }
        PanelSurface {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle(
                    modifier = Modifier.weight(1f),
                    text = "只确认关键操作（推荐）",
                    subtitle = "开启后，打开应用、填写草稿、设置提醒、点击滚动等可撤销的操作直接执行；支付、发送、删除、发布、下单、转账、授权等按钮由你亲手点击，外发数据和模型标注的敏感步骤仍会确认。关闭后每个手机操作都先确认。",
                )
                Switch(
                    modifier = Modifier.testTag("reduce_device_action_confirmations_switch"),
                    checked = state.reduceDeviceActionConfirmations,
                    enabled = !state.isBusy,
                    onCheckedChange = onReduceDeviceActionConfirmationsChanged,
                )
            }
        }
        PanelSurface {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle(
                    modifier = Modifier.weight(1f),
                    text = "远程视觉操作应用（需知情同意）",
                    subtitle = "仅远程模式生效。开启后，操作应用时会把当前屏幕截图和屏幕上的文字（不含密码框）发给远程视觉模型，由它规划点击和输入；支付、发送、删除、发布等按钮始终由你亲手点击。截图被系统拦截或为空白的页面不外发。关闭则屏幕内容不外发，改由本地动作模型驱动。",
                )
                Switch(
                    modifier = Modifier.testTag("remote_gui_automation_switch"),
                    checked = state.remoteGuiAutomationEnabled,
                    enabled = !state.isBusy,
                    onCheckedChange = onRemoteGuiAutomationEnabledChanged,
                )
            }
        }
        if (decisionModelRouting != null) {
            DecisionModelRoutingSection(
                state = decisionModelRouting,
                enabled = !state.isBusy,
                onModeSelected = onDecisionModelRoutingModeSelected,
                onApiKeySaved = onDecisionModelRoutingKeySaved,
            )
        }
        if (mcpCapability != null) {
            McpCapabilityToggleSection(
                state = mcpCapability,
                enabled = !state.isBusy,
                onEnabledChanged = onMcpCapabilityEnabledChanged,
            )
        }
        PanelSurface {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(
                    text = "远程发送记录",
                    subtitle = "只记录决策、模型和类别，不保存原始 prompt。",
                )
                RemoteSendAuditList(events = state.remoteSendAuditEvents)
            }
        }
        PanelSurface {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(
                    text = "敏感能力披露",
                    subtitle = SENSITIVE_CAPABILITY_DISCLOSURE_TEXT,
                )
                sensitiveDisclosureRows.forEach { row ->
                    TrustBoundaryRow(
                        icon = SolinGlyphKind.Shield,
                        title = row.title,
                        body = row.body,
                    )
                }
            }
        }
        PanelSurface {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(
                    text = "发布前仍需人工完成",
                    subtitle = "这些不是 App 代码能自动替代的事项。",
                )
                listOf(
                    "使用生产签名或 Play App Signing，不使用本地 debug keystore 发布。",
                    "准备公开隐私政策 URL，并与 Play Console Data safety 表单保持一致。",
                    "接入 crash/ANR 监控，并完成真机矩阵、无障碍和大字体验收。",
                ).forEach { item ->
                    Text(
                        text = "• $item",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Trust Center view of the MCP opt-in capability (mirrors `mcp.McpCapabilityState`). */
data class McpCapabilityToggleUiState(
    val enabled: Boolean,
    val disclosureAcknowledged: Boolean,
    val restartRequired: Boolean,
    val approvedToolCount: Int,
)

internal fun McpCapabilityState.toToggleUiState(): McpCapabilityToggleUiState =
    McpCapabilityToggleUiState(
        enabled = enabled,
        disclosureAcknowledged = disclosureAcknowledged,
        restartRequired = restartRequired,
        approvedToolCount = approvedToolCount,
    )

internal const val MCP_CAPABILITY_TITLE = "MCP 扩展工具（默认关闭）"

internal const val MCP_CAPABILITY_SUBTITLE =
    "开启后，你已批准的 MCP 扩展应用的工具可被 Solin 使用；每次调用前都会确认，调用记录会出现在下方远程发送记录中（不保存参数内容）。关闭立即生效，开启在重启应用后生效。"

internal const val MCP_CAPABILITY_DISCLOSURE_TITLE = "开启 MCP 扩展工具"

internal const val MCP_CAPABILITY_DISCLOSURE_BODY =
    "MCP 工具由其他已安装应用提供。调用时，工具参数（可能包含你的对话内容）会离开 Solin 发送给该应用，该应用可能再将其上传到网络，因此视为离开本机。每次调用仍需你逐个确认，并记录到远程发送记录（只记工具名和参数数量）。工具返回的结果默认仅保存在本机，只交给本地模型处理。"

@Composable
private fun McpCapabilityToggleSection(
    state: McpCapabilityToggleUiState,
    enabled: Boolean,
    onEnabledChanged: (Boolean, Boolean) -> Unit,
) {
    var showDisclosure by remember { mutableStateOf(false) }
    PanelSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle(
                modifier = Modifier.weight(1f),
                text = MCP_CAPABILITY_TITLE,
                subtitle = buildString {
                    append(MCP_CAPABILITY_SUBTITLE)
                    append(" 已批准工具：${state.approvedToolCount} 个。")
                    if (state.restartRequired) append(" 重启应用后生效。")
                },
            )
            Switch(
                modifier = Modifier.testTag("mcp_capability_switch"),
                checked = state.enabled,
                enabled = enabled,
                onCheckedChange = { checked ->
                    if (checked && !state.disclosureAcknowledged) {
                        showDisclosure = true
                    } else {
                        onEnabledChanged(checked, false)
                    }
                },
            )
        }
    }
    if (showDisclosure) {
        AlertDialog(
            modifier = Modifier.testTag("mcp_capability_disclosure_dialog"),
            onDismissRequest = { showDisclosure = false },
            title = { Text(MCP_CAPABILITY_DISCLOSURE_TITLE) },
            text = { Text(MCP_CAPABILITY_DISCLOSURE_BODY) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("mcp_capability_disclosure_confirm"),
                    onClick = {
                        showDisclosure = false
                        onEnabledChanged(true, true)
                    },
                ) {
                    Text("同意并开启")
                }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag("mcp_capability_disclosure_cancel"),
                    onClick = { showDisclosure = false },
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
internal fun RemoteSendDisclosurePolicySelector(
    selectedPolicy: RemoteSendDisclosurePolicy,
    enabled: Boolean,
    onSelected: (RemoteSendDisclosurePolicy) -> Unit,
) {
    Column(
        modifier = Modifier.testTag("remote_send_disclosure_policy_selector"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RemoteSendDisclosurePolicy.values().forEach { policy ->
            FilterChip(
                modifier = Modifier.testTag("remote_send_policy_${policy.name}"),
                selected = selectedPolicy == policy,
                onClick = { onSelected(policy) },
                label = { Text(policy.remoteSendPolicyLabel()) },
                enabled = enabled,
            )
            if (selectedPolicy == policy) {
                Text(
                    text = policy.remoteSendPolicyDescription(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun RemoteSendAuditList(events: List<RemoteSendAuditSummary>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("remote_send_audit_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (events.isEmpty()) {
            Text(
                text = "暂无远程发送记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val visibleEvents = events.take(12)
        visibleEvents.forEachIndexed { index, event ->
            RemoteSendAuditRow(event)
            if (index < visibleEvents.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
internal fun RemoteSendAuditRow(event: RemoteSendAuditSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("remote_send_audit_${event.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = event.decisionLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = event.remoteSendAuditTimeLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = listOfNotNull(event.modelName?.takeIf { it.isNotBlank() }, event.summary)
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun RemoteSendDisclosurePolicy.remoteSendPolicyLabel(): String =
    when (this) {
        RemoteSendDisclosurePolicy.OnRemoteModeSwitch -> "切换时提醒"
        RemoteSendDisclosurePolicy.EveryMessage -> "每次发送"
        RemoteSendDisclosurePolicy.OncePerSession -> "每会话一次"
        RemoteSendDisclosurePolicy.OnlyWhenSensitive -> "仅敏感内容"
    }

internal fun RemoteSendDisclosurePolicy.remoteSendPolicyDescription(): String =
    when (this) {
        RemoteSendDisclosurePolicy.OnRemoteModeSwitch ->
            "切换到远程模型时弹一次提醒；普通文本随后不再逐条弹窗，图片仍每次确认。"
        RemoteSendDisclosurePolicy.EveryMessage -> "所有远程发送都会弹出确认。"
        RemoteSendDisclosurePolicy.OncePerSession -> "普通文本确认一次后本会话内静默，疑似敏感内容和图片仍会弹窗。"
        RemoteSendDisclosurePolicy.OnlyWhenSensitive -> "普通文本直接发送；疑似敏感内容和图片仍会强制确认。"
    }

internal fun RemoteSendAuditSummary.remoteSendAuditTimeLabel(): String = shortTimestamp(createdAtMillis)

@Composable
internal fun TrustBoundaryRow(
    icon: SolinGlyphKind,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SolinGlyph(
            modifier = Modifier.size(20.dp),
            kind = icon,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun trustDeletionBoundaryText(state: ChatUiState): String =
    "可清空长期记忆、删除当前会话、取消后台任务，并可一键清除远程服务地址、模型名和 API Key。当前已保存长期记忆 ${state.longTermMemories.size} 条。"

internal fun semanticMemoryIndexStatusText(state: ChatUiState): String {
    val mode = if (state.semanticMemory.runtimeStatus == SemanticMemoryRuntimeStatus.Active) {
        "语义记忆"
    } else {
        "轻量索引"
    }
    val rebuilt = state.semanticMemory.lastRebuiltAtMillis
        ?.let(::shortTimestamp)
        ?: "尚未建立"
    return "召回模式：$mode；索引：${state.semanticMemory.indexedRecordCount} 条长期记忆；最近重建：$rebuilt"
}

