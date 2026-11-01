package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.MemoryEvidenceUiSummary
import com.bytedance.zgx.solin.PublicWebEvidencePack
import com.bytedance.zgx.solin.RunTimelineItemUiSummary
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.orchestration.AgentRecoveryAction
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors

/**
 * One quiet line for the memory that was recalled into this answer. Everything stays on this
 * phone, so the line carries a filled placement mark; tapping it reveals the evidence rows.
 */
@Composable
internal fun MemoryContextStrip(
    modifier: Modifier = Modifier,
    evidence: List<MemoryEvidenceUiSummary>,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        QuietStripLine(
            label = "引用本地记忆 ${evidence.size} 条",
            leavesPhone = false,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        if (expanded) {
            evidence.take(3).forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Text(
                    modifier = Modifier.padding(vertical = 6.dp),
                    text = memoryEvidenceDisplayText(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * One quiet line for public web sources. The content was fetched remotely, so the line carries
 * the ringed placement mark; tapping it reveals the source rows.
 */
@Composable
internal fun SourcesStrip(
    modifier: Modifier = Modifier,
    rows: List<PublicWebSourceDisplayRow>,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        QuietStripLine(
            label = "公开来源 ${rows.size} 条",
            leavesPhone = true,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        if (expanded) {
            rows.take(4).forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                SourceCard(row = row)
            }
        }
    }
}

@Composable
private fun QuietStripLine(
    label: String,
    leavesPhone: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val actionLabel = if (expanded) "收起" else "展开"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onToggle)
            .semantics { contentDescription = "$label，点按$actionLabel" }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlacementMark(leavesPhone = leavesPhone, modifier = Modifier.size(10.dp))
        Text(
            modifier = Modifier.weight(1f),
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One source as a row: title, where it came from, then the snippet. No card, no border. */
@Composable
internal fun SourceCard(row: PublicWebSourceDisplayRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = publicWebSourceMetaText(row),
            style = MaterialTheme.typography.labelSmall,
            color = LocalSolinColors.current.remote,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (row.snippet.isNotBlank()) {
            Text(
                text = row.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Vertical run steps: a dot per step, no header, newest four only. */
@Composable
internal fun RunTimelineStrip(
    modifier: Modifier = Modifier,
    items: List<RunTimelineItemUiSummary>,
) {
    val recent = items.takeLast(4)
    val visuals = runTimelineStepVisuals(recent)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        recent.forEachIndexed { index, item ->
            RunTimelineStep(item = item, visual = visuals[index])
        }
    }
}

internal enum class RunStepVisual { Done, Current, Pending }

private val TERMINAL_RUN_STATES = setOf("Completed", "Failed", "Cancelled", "Empty")

/**
 * The step the run is on now is the last one that has not finished; everything above it is done
 * and anything below it has not started. A finished run leaves every step done.
 */
internal fun runTimelineStepVisuals(items: List<RunTimelineItemUiSummary>): List<RunStepVisual> {
    val currentIndex = items.indexOfLast { item -> item.state !in TERMINAL_RUN_STATES }
    return items.mapIndexed { index, _ ->
        when {
            currentIndex == -1 -> RunStepVisual.Done
            index < currentIndex -> RunStepVisual.Done
            index == currentIndex -> RunStepVisual.Current
            else -> RunStepVisual.Pending
        }
    }
}

@Composable
private fun RunTimelineStep(item: RunTimelineItemUiSummary, visual: RunStepVisual) {
    val colors = LocalSolinColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The dot sits on the first text line; a soft ring marks the step the run is on now.
        val dotColor = when (visual) {
            RunStepVisual.Done -> colors.local
            RunStepVisual.Current -> colors.busy
            RunStepVisual.Pending -> MaterialTheme.colorScheme.outlineVariant
        }
        Surface(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(if (visual == RunStepVisual.Current) 14.dp else 7.dp),
            shape = CircleShape,
            color = if (visual == RunStepVisual.Current) colors.busyContainer else dotColor,
        ) {
            if (visual == RunStepVisual.Current) {
                Box(contentAlignment = Alignment.Center) {
                    Surface(modifier = Modifier.size(7.dp), shape = CircleShape, color = dotColor) {}
                }
            }
        }
        Text(
            modifier = Modifier.weight(1f),
            text = runTimelineItemDisplayText(item),
            style = MaterialTheme.typography.bodySmall,
            color = if (visual == RunStepVisual.Current) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (visual == RunStepVisual.Current) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun runTimelineItemDisplayText(item: RunTimelineItemUiSummary): String =
    listOf(item.label, item.detail, item.privacyLabel, item.riskLabel)
        .mapNotNull { value -> value?.safeStripDisplayText()?.takeIf { it.isNotBlank() } }
        .joinToString(" · ")

internal fun memoryEvidenceDisplayText(item: MemoryEvidenceUiSummary): String =
    listOf(item.typeLabel, item.recallLabel, item.reasonLabel, item.scoreLabel)
        .map { value -> value.safeStripDisplayText() }
        .filter { value -> value.isNotBlank() }
        .joinToString(" · ")

internal data class PublicWebSourceDisplayRow(
    val sourceId: String,
    val title: String,
    val source: String,
    val retrievedAt: String,
    val qualityLabel: String,
    val snippet: String,
)

internal fun publicWebEvidenceDisplayRows(packs: List<PublicWebEvidencePack>): List<PublicWebSourceDisplayRow> =
    packs
        .flatMap { pack ->
            val retrievedAt = pack.retrievedAt
            val packQuality = pack.quality
            pack.items.map { item ->
                val url = item.url
                PublicWebSourceDisplayRow(
                    sourceId = "",
                    title = item.title.safeStripDisplayText().ifBlank { "公开来源" },
                    source = item.sourceName.safeStripDisplayText()
                        .ifBlank { actionLinkDomain(url).orEmpty() }
                        .safeStripDisplayText()
                        .ifBlank { "公开网页" },
                    retrievedAt = retrievedAt.safeStripDisplayText(),
                    qualityLabel = item.qualityLabel
                        .ifBlank { packQuality }
                        .safeStripDisplayText(),
                    snippet = item.snippet.safeStripDisplayText(),
                )
            }
        }
        .distinctBy { row -> row.source to row.title }
        .take(8)
        .mapIndexed { index, row -> row.copy(sourceId = "S${index + 1}") }

internal fun publicWebSourceDisplayText(row: PublicWebSourceDisplayRow): String =
    listOf(row.title, publicWebSourceMetaText(row), row.snippet)
        .filter { value -> value.isNotBlank() }
        .joinToString("\n")

private fun publicWebSourceMetaText(row: PublicWebSourceDisplayRow): String =
    listOf(row.source, row.retrievedAt, row.qualityLabel)
        .filter { value -> value.isNotBlank() }
        .joinToString(" · ")

private fun String.safeStripDisplayText(): String {
    val normalized = trim().replace(Regex("\\s+"), " ")
    return if (PRIVATE_STRIP_TEXT_PATTERNS.any { pattern -> pattern.containsMatchIn(normalized) }) {
        "[redacted]"
    } else {
        normalized.take(80)
    }
}

private val PRIVATE_STRIP_TEXT_PATTERNS = listOf(
    Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
    Regex("(?i)\\b(password|passwd|secret|token|api[_ -]?key|credential|private)\\b\\s*[:=]?\\s*\\S*"),
    Regex("(?i)\\bresultsJson\\b"),
    Regex("^\\s*[\\[{].*[\\]}]\\s*$"),
)

/**
 * The one-tap entry that lets the user undo the last reversible action. It waits for the user,
 * so it uses the amber "needs you" colour, and the button says what happens: "撤销" for an undo,
 * otherwise the action's own name.
 */
@Composable
internal fun RecoveryActionEntry(
    action: AgentRecoveryAction,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSolinColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = colors.busyContainer,
        border = BorderStroke(1.dp, colors.busy.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics {
                    contentDescription = action.draft.title
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SolinGlyph(
                modifier = Modifier.size(18.dp),
                kind = SolinGlyphKind.Undo,
                tint = colors.busy,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = action.draft.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onBusyContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = action.draft.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onBusyContainer.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = recoveryActionButtonLabel(action),
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) {
                    colors.busy
                } else {
                    colors.onBusyContainer.copy(alpha = 0.48f)
                },
            )
        }
    }
}

/** "撤销" for an undo action; otherwise the action's own name so the button is specific. */
internal fun recoveryActionButtonLabel(action: AgentRecoveryAction): String =
    when (action.request.toolName) {
        MobileActionFunctions.CANCEL_REMINDER -> "撤销"
        else -> action.draft.title.trim().ifBlank { "确认" }
    }
