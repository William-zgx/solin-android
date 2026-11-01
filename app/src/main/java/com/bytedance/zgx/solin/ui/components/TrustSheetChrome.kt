package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Section heading inside a sheet. Sheet-level titles use `titleLarge` directly; sections stay
 * one step below them so the hierarchy reads 19 / 16 / 15.
 */
@Composable
internal fun SectionTitle(
    text: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = text,
            style = titleStyle,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Body of a modal sheet: flat background, 20dp edge padding, no border and no nested card.
 * Scrolls so tall disclosures stay reachable, and pads for the IME and navigation bar.
 */
@Composable
internal fun TrustSheetSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        content()
    }
}

/** A group of rows inside a sheet: spacing only, no border. Callers separate rows with [SheetRowDivider]. */
@Composable
internal fun TrustSheetGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** The 1dp row separator used instead of nested cards. */
@Composable
internal fun SheetRowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** A titled block inside the model manager. Flat: no border, no card. */
@Composable
internal fun PanelSurface(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

@Composable
internal fun EmptyPanelText(text: String) {
    Text(
        modifier = Modifier.padding(vertical = 8.dp),
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Scrollable bottom-sheet body with the standard edge padding. */
@Composable
internal fun SheetColumn(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
internal fun CloseIconButton(
    testTag: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        modifier = Modifier
            .testTag(testTag)
            .semantics { this.contentDescription = contentDescription },
        onClick = onClick,
        enabled = enabled,
    ) {
        SolinGlyph(
            kind = SolinGlyphKind.Close,
            tint = LocalContentColor.current,
            modifier = Modifier.size(20.dp),
        )
    }
}
