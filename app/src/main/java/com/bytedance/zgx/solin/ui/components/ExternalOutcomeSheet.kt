package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.PendingExternalOutcomeConfirmation
import com.bytedance.zgx.solin.orchestration.AgentExternalOutcome

@Composable
internal fun ExternalOutcomeSheet(
    pending: PendingExternalOutcomeConfirmation,
    onRecord: (AgentExternalOutcome) -> Unit,
) {
    TrustSheetSurface(modifier = Modifier.testTag("external_outcome_sheet")) {
        SheetPlacementHeader(
            leavesPhone = false,
            title = "外部操作完成了吗？",
            subtitle = pending.title,
        )
        Text(
            text = pending.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("external_outcome_not_completed_button"),
                onClick = { onRecord(AgentExternalOutcome.NotCompleted) },
            ) {
                Text("未完成")
            }
            Button(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("external_outcome_completed_button"),
                onClick = { onRecord(AgentExternalOutcome.Completed) },
            ) {
                Text("已完成")
            }
        }
        TextButton(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("external_outcome_opened_only_button"),
            onClick = { onRecord(AgentExternalOutcome.OpenedOnly) },
        ) {
            Text("只是打开了")
        }
    }
}
