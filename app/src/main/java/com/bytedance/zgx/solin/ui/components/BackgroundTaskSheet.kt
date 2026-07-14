package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.bytedance.zgx.solin.AgentTraceRunUiSummary
import com.bytedance.zgx.solin.AuditEventSummary
import com.bytedance.zgx.solin.BackgroundTaskSummary
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.RunDataReceiptUiSummary
import com.bytedance.zgx.solin.background.PeriodicCheckConstraints
import com.bytedance.zgx.solin.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.solin.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.solin.background.ScheduledTaskStatus
import com.bytedance.zgx.solin.background.ScheduledTaskType
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.ui.runDataReceiptDisplayText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun BackgroundTaskSheet(
    state: ChatUiState,
    onRefreshBackgroundTasks: () -> Unit,
    onRefreshAuditEvents: () -> Unit,
    onCancelBackgroundTask: (String) -> Unit,
    onSetPeriodicCheckPolicy: (PeriodicCheckScheduleRequest) -> Unit,
    onDisablePeriodicCheckPolicy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier
                    .weight(1f)
                    .testTag("background_task_manager_title"),
                text = "后台任务",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(
                modifier = Modifier.testTag("background_task_refresh_button"),
                onClick = {
                    onRefreshBackgroundTasks()
                    onRefreshAuditEvents()
                },
                enabled = !state.isBusy,
            ) {
                Text("刷新")
            }
        }

        PeriodicCheckPolicySection(
            policy = state.periodicCheckPolicy,
            enabled = !state.isBusy,
            onSetPeriodicCheckPolicy = onSetPeriodicCheckPolicy,
            onDisablePeriodicCheckPolicy = onDisablePeriodicCheckPolicy,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

        if (state.backgroundTasks.isEmpty()) {
            EmptyPanelText("暂无运行中的后台任务")
        } else {
            state.backgroundTasks.forEach { task ->
                BackgroundTaskRow(
                    task = task,
                    enabled = !state.isBusy,
                    onCancel = if (task.status == ScheduledTaskStatus.Scheduled) {
                        { onCancelBackgroundTask(task.id) }
                    } else {
                        null
                    },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

        SectionTitle(
            text = "最近后台任务",
            subtitle = "已结束任务只展示状态，不提供再次执行入口。",
        )

        if (state.backgroundTaskHistory.isEmpty()) {
            EmptyPanelText("暂无后台任务历史")
        } else {
            state.backgroundTaskHistory.forEach { task ->
                BackgroundTaskRow(
                    task = task,
                    enabled = !state.isBusy,
                    onCancel = null,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle(
                modifier = Modifier.weight(1f),
                text = "最近审计日志",
                subtitle = "只显示不含参数的工具活动摘要。",
            )
            TextButton(
                modifier = Modifier.testTag("audit_event_refresh_button"),
                onClick = onRefreshAuditEvents,
                enabled = !state.isBusy,
            ) {
                Text("刷新")
            }
        }

        if (state.auditEvents.isEmpty()) {
            EmptyPanelText("暂无审计记录")
        } else {
            state.auditEvents.forEach { event ->
                AuditEventRow(event)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

        SectionTitle(
            text = "最近 Agent 轨迹",
            subtitle = "只展示持久化摘要，不展示工具参数。",
        )

        if (state.agentTraceRuns.isEmpty()) {
            EmptyPanelText("暂无 Agent 轨迹")
        } else {
            state.agentTraceRuns.forEach { run ->
                AgentTraceRunRow(run)
            }
        }
    }
}

@Composable
internal fun AgentTraceRunRow(run: AgentTraceRunUiSummary) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent_trace_run_${run.id}"),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f),
        ),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "Run ${run.id.takeLast(8)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = run.state.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            run.runDataReceipt?.let { receipt ->
                RunDataReceiptSummary(receipt)
            }
            run.steps
                .filter { step -> step.runDataReceipt == null }
                .takeLast(4)
                .forEach { step ->
                AgentTraceStepRow(step)
            }
        }
    }
}

@Composable
internal fun AgentTraceStepRow(step: com.bytedance.zgx.solin.AgentTraceStepUiSummary) {
    val receipt = step.runDataReceipt
    if (receipt == null) {
        Text(
            text = "${step.type} · ${step.summary}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    RunDataReceiptSummary(receipt)
}

@Composable
internal fun RunDataReceiptSummary(receipt: RunDataReceiptUiSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("run_data_receipt_summary"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "本次数据回执",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = runDataReceiptDisplayText(receipt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun PeriodicCheckPolicySection(
    policy: PeriodicCheckPolicySummary,
    enabled: Boolean,
    onSetPeriodicCheckPolicy: (PeriodicCheckScheduleRequest) -> Unit,
    onDisablePeriodicCheckPolicy: () -> Unit,
) {
    val request = policy.request.normalized()
    val policyKey = listOf(
        policy.updatedAtMillis?.toString().orEmpty(),
        policy.taskStatus?.name.orEmpty(),
        request.storageSummary(),
    ).joinToString(separator = "|")
    var policyEnabled by rememberSaveable(policyKey) { mutableStateOf(policy.isSwitchEnabled()) }
    var intervalMinutes by rememberSaveable(policyKey) { mutableStateOf(request.intervalMinutes) }
    var minSpacingMinutes by rememberSaveable(policyKey) { mutableStateOf(request.minNotificationSpacingMinutes) }
    var overdueGraceMinutes by rememberSaveable(policyKey) { mutableStateOf(request.overdueGraceMinutes) }
    var requiresBatteryNotLow by rememberSaveable(policyKey) {
        mutableStateOf(request.constraints.requiresBatteryNotLow)
    }
    var requiresCharging by rememberSaveable(policyKey) {
        mutableStateOf(request.constraints.requiresCharging)
    }
    val controlsEnabled = enabled && policyEnabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("periodic_check_policy_section"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle(
            text = "周期检查策略",
            subtitle = policy.statusLine(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = "启用本地提醒巡检",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Switch(
                modifier = Modifier.testTag("periodic_check_policy_switch"),
                checked = policyEnabled,
                onCheckedChange = { policyEnabled = it },
                enabled = enabled,
            )
        }
        PeriodicCheckChoiceRow(
            label = "检查间隔",
            options = periodicCheckIntervalOptions,
            selectedValue = intervalMinutes,
            enabled = controlsEnabled,
            onSelected = { intervalMinutes = it },
        )
        PeriodicCheckChoiceRow(
            label = "最小通知间隔",
            options = periodicCheckIntervalOptions,
            selectedValue = minSpacingMinutes,
            enabled = controlsEnabled,
            onSelected = { minSpacingMinutes = it },
        )
        PeriodicCheckChoiceRow(
            label = "过期宽限",
            options = periodicCheckGraceOptions,
            selectedValue = overdueGraceMinutes,
            enabled = controlsEnabled,
            onSelected = { overdueGraceMinutes = it },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = requiresBatteryNotLow,
                onCheckedChange = { requiresBatteryNotLow = it },
                enabled = controlsEnabled,
            )
            Text(
                modifier = Modifier.weight(1f),
                text = "低电量时暂停",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = requiresCharging,
                onCheckedChange = { requiresCharging = it },
                enabled = controlsEnabled,
            )
            Text(
                modifier = Modifier.weight(1f),
                text = "仅充电时运行",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .testTag("periodic_check_disable_button"),
                onClick = onDisablePeriodicCheckPolicy,
                enabled = enabled,
            ) {
                Text("关闭检查")
            }
            Button(
                modifier = Modifier
                    .weight(1f)
                    .testTag("periodic_check_save_button"),
                onClick = {
                    if (policyEnabled) {
                        onSetPeriodicCheckPolicy(
                            PeriodicCheckScheduleRequest(
                                enabled = true,
                                intervalMinutes = intervalMinutes,
                                minNotificationSpacingMinutes = minSpacingMinutes,
                                overdueGraceMinutes = overdueGraceMinutes,
                                constraints = PeriodicCheckConstraints(
                                    requiresBatteryNotLow = requiresBatteryNotLow,
                                    requiresCharging = requiresCharging,
                                ),
                            ),
                        )
                    } else {
                        onDisablePeriodicCheckPolicy()
                    }
                },
                enabled = enabled,
            ) {
                Text("保存策略")
            }
        }
    }
}

@Composable
internal fun PeriodicCheckChoiceRow(
    label: String,
    options: List<Pair<Long, String>>,
    selectedValue: Long,
    enabled: Boolean,
    onSelected: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, optionLabel) ->
                FilterChip(
                    selected = selectedValue == value,
                    onClick = { onSelected(value) },
                    label = { Text(optionLabel) },
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
internal fun AuditEventRow(event: AuditEventSummary) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("audit_event_${event.id}"),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f),
        ),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = event.eventType,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = event.auditTimeLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            val metadata = listOfNotNull(
                event.toolName,
                event.status,
                event.riskLevel,
                event.permissions.takeIf { it.isNotEmpty() }?.joinToString(separator = ","),
            ).joinToString(separator = " · ")
            if (metadata.isNotBlank()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = event.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun AuditEventSummary.auditTimeLabel(): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(createdAtMillis))

internal val periodicCheckIntervalOptions = listOf(
    PeriodicCheckScheduleRequest.MIN_INTERVAL_MINUTES to "1 小时",
    PeriodicCheckScheduleRequest.DEFAULT_INTERVAL_MINUTES to "6 小时",
    12L * 60L to "12 小时",
    PeriodicCheckScheduleRequest.MAX_INTERVAL_MINUTES to "24 小时",
)

internal val periodicCheckGraceOptions = listOf(
    PeriodicCheckScheduleRequest.MIN_OVERDUE_GRACE_MINUTES to "5 分钟",
    PeriodicCheckScheduleRequest.DEFAULT_OVERDUE_GRACE_MINUTES to "30 分钟",
    60L to "1 小时",
)

internal fun PeriodicCheckPolicySummary.statusLine(): String {
    val policyLabel = if (isSwitchEnabled()) "已启用" else "未启用"
    val statusLabel = taskStatus?.label()?.let { "状态 $it" }
    val nextCheckLabel = nextAllowedRunAtMillis?.let {
        "下次允许检查 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))}"
    }
    val lastRunLabel = lastRunSummary?.takeIf { it.isNotBlank() }?.let { "最近 $it" }
    return listOfNotNull(policyLabel, statusLabel, nextCheckLabel, lastRunLabel)
        .joinToString(separator = " · ")
}

internal fun PeriodicCheckPolicySummary.isSwitchEnabled(): Boolean =
    request.enabled &&
        taskStatus != ScheduledTaskStatus.Cancelled &&
        taskStatus != ScheduledTaskStatus.Deleted

@Composable
internal fun BackgroundTaskRow(
    task: BackgroundTaskSummary,
    enabled: Boolean,
    onCancel: (() -> Unit)?,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("background_task_${task.id}"),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
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
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${task.type.label()} · ${task.status.label()} · ${task.triggerLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (task.body.isNotBlank()) {
                    Text(
                        text = task.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onCancel != null) {
                IconButton(
                    modifier = Modifier
                        .testTag("background_task_cancel_${task.id}")
                        .semantics { contentDescription = "取消后台任务" },
                    onClick = onCancel,
                    enabled = enabled,
                ) {
                    SolinGlyph(
                        kind = SolinGlyphKind.Close,
                        tint = LocalContentColor.current,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

internal fun BackgroundTaskSummary.triggerLabel(): String {
    val prefix = when (type) {
        ScheduledTaskType.Reminder -> "触发"
        ScheduledTaskType.PeriodicCheck -> "下次允许检查"
    }
    return "$prefix ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(triggerAtMillis))}"
}

internal fun ScheduledTaskType.label(): String =
    when (this) {
        ScheduledTaskType.Reminder -> "提醒"
        ScheduledTaskType.PeriodicCheck -> "周期检查"
    }

internal fun ScheduledTaskStatus.label(): String =
    when (this) {
        ScheduledTaskStatus.Scheduled -> "运行中"
        ScheduledTaskStatus.Running -> "执行中"
        ScheduledTaskStatus.Delivered -> "已送达"
        ScheduledTaskStatus.Cancelled -> "已取消"
        ScheduledTaskStatus.Deleted -> "已删除"
        ScheduledTaskStatus.Failed -> "失败"
    }

internal fun AgentRunState.label(): String =
    when (this) {
        AgentRunState.Created -> "已创建"
        AgentRunState.LoadingContext -> "加载上下文"
        AgentRunState.Planning -> "规划中"
        AgentRunState.AwaitingUserConfirmation -> "待确认"
        AgentRunState.AwaitingUserAnswer -> "待用户回答"
        AgentRunState.ExecutingTool -> "执行工具"
        AgentRunState.RetryingTool -> "重试工具"
        AgentRunState.Observing -> "观察结果"
        AgentRunState.GeneratingAnswer -> "生成回答"
        AgentRunState.AwaitingExternalOutcome -> "待确认外部结果"
        AgentRunState.Completed -> "已完成"
        AgentRunState.Cancelled -> "已取消"
        AgentRunState.Failed -> "失败"
    }

