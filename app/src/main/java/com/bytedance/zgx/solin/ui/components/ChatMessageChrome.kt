package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.MemoryEvidenceUiSummary
import com.bytedance.zgx.solin.PublicWebEvidencePack
import com.bytedance.zgx.solin.RunTimelineItemUiSummary
import com.bytedance.zgx.solin.orchestration.AgentRecoveryAction
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors

@Composable
internal fun MemoryContextStrip(
    modifier: Modifier = Modifier,
    evidence: List<MemoryEvidenceUiSummary>,
) {
    val semanticColors = LocalSolinColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = semanticColors.memoryContainer.copy(alpha = 0.72f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SolinGlyph(
                    modifier = Modifier.size(16.dp),
                    kind = SolinGlyphKind.Memory,
                    tint = semanticColors.onMemoryContainer,
                )
                Text(
                    text = "已引用本地记忆 ${evidence.size} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = semanticColors.onMemoryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            evidence.take(3).forEach { item ->
                Text(
                    text = memoryEvidenceDisplayText(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = semanticColors.onMemoryContainer.copy(alpha = 0.86f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun SourcesStrip(
    modifier: Modifier = Modifier,
    rows: List<PublicWebSourceDisplayRow>,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SolinGlyph(
                modifier = Modifier.size(16.dp),
                kind = SolinGlyphKind.Spark,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "公开来源 ${rows.size} 条",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        rows.take(4).forEach { row ->
            SourceCard(row = row)
        }
    }
}

@Composable
internal fun SourceCard(row: PublicWebSourceDisplayRow) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = publicWebSourceMetaText(row),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
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
}

@Composable
internal fun RunTimelineStrip(
    modifier: Modifier = Modifier,
    items: List<RunTimelineItemUiSummary>,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SolinGlyph(
                    modifier = Modifier.size(16.dp),
                    kind = SolinGlyphKind.Spark,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "当前进度",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items.takeLast(4).forEach { item ->
                Text(
                    text = runTimelineItemDisplayText(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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

@Composable
internal fun RecoveryActionEntry(
    action: AgentRecoveryAction,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics {
                    contentDescription = action.draft.title
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SolinGlyph(
                modifier = Modifier.size(18.dp),
                kind = SolinGlyphKind.Undo,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = action.draft.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = action.draft.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "确认",
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.52f)
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

