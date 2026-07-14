package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.PendingExternalOutcomeConfirmation
import com.bytedance.zgx.solin.orchestration.AgentExternalOutcome
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors

@Composable
internal fun ExternalOutcomeSheet(
    pending: PendingExternalOutcomeConfirmation,
    onRecord: (AgentExternalOutcome) -> Unit,
) {
    TrustSheetSurface(
        modifier = Modifier.testTag("external_outcome_sheet"),
        accentColor = LocalSolinColors.current.busy,
    ) {
        SectionTitle(
            text = "外部操作完成了吗？",
            subtitle = pending.title,
        )
        TrustSheetGroup {
            Text(
                text = pending.summary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("external_outcome_completed_button"),
            onClick = { onRecord(AgentExternalOutcome.Completed) },
        ) {
            SolinGlyph(
                kind = SolinGlyphKind.Check,
                modifier = Modifier.size(18.dp),
                tint = LocalContentColor.current,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("已完成")
        }
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("external_outcome_not_completed_button"),
            onClick = { onRecord(AgentExternalOutcome.NotCompleted) },
        ) {
            SolinGlyph(
                kind = SolinGlyphKind.Close,
                modifier = Modifier.size(18.dp),
                tint = LocalContentColor.current,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("未完成")
        }
        TextButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("external_outcome_opened_only_button"),
            onClick = { onRecord(AgentExternalOutcome.OpenedOnly) },
        ) {
            Text("只是打开了")
        }
    }
}
