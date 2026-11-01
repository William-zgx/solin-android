package com.bytedance.zgx.solin.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.GenerationStats
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.isUsable
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.ui.splitMessageSegments
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors
import java.util.Locale

/**
 * One chat message. The user's words are a quiet block on the right; the assistant answers as
 * plain text on the background with no bubble, border or role label. Generation stats stay hidden
 * until the user long-presses the message, which also offers "复制".
 */
@Composable
internal fun MessageBubble(
    message: ChatMessage,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.User
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val statsLine = message.generationStats
        ?.takeIf { it.isUsable() }
        ?.let { stats -> formatGenerationStats(stats) }
    var menuExpanded by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // User messages hug their text but never take more than 80% of the row.
        val userBubbleMaxWidth = maxWidth * 0.8f
        val openMenu: (Offset) -> Unit = { position ->
            menuOffset = with(density) { DpOffset(position.x.toDp(), position.y.toDp()) }
            menuExpanded = true
        }
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            if (isUser) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = userBubbleMaxWidth)
                        .pointerInput(message.id) {
                            detectTapGestures(onLongPress = openMenu)
                        },
                    shape = RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomEnd = 6.dp,
                        bottomStart = 20.dp,
                    ),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(message.id) {
                            detectTapGestures(onLongPress = openMenu)
                        },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isStreaming && message.text.isBlank()) {
                        TypingIndicator()
                    }
                    if (message.text.isNotBlank()) {
                        MessageContent(text = message.text, renderMarkdown = !isStreaming)
                    }
                }
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                offset = menuOffset,
                shape = MaterialTheme.shapes.medium,
            ) {
                DropdownMenuItem(
                    text = { Text("复制") },
                    onClick = {
                        clipboard.setText(AnnotatedString(message.text))
                        menuExpanded = false
                    },
                )
                if (statsLine != null) {
                    Text(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        text = statsLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A small three-dot pulse: the assistant is generating but has no text yet. */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing_indicator")
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 0.85f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 180),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "typing_dot_$index",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)),
            )
        }
    }
}

private fun formatGenerationStats(stats: GenerationStats): String =
    buildList {
        stats.backend?.let { backend -> add(backend.label()) }
        stats.firstTokenMs?.let { firstTokenMs -> add("首 token ${firstTokenMs}ms") }
        stats.loadMs?.let { loadMs -> add("加载 ${loadMs}ms") }
        add("${stats.tokenCount} tokens")
        add("${String.format(Locale.US, "%.1f", stats.tokensPerSecond)} tokens/s")
    }.joinToString(separator = " · ")

@Composable
private fun MessageContent(
    text: String,
    renderMarkdown: Boolean,
) {
    if (!renderMarkdown) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        return
    }

    // Markdown parsing depends only on [text]; skip it when the message recomposes unchanged.
    val renderedSegments = remember(text) {
        splitMessageSegments(text).map { segment ->
            val annotated = if (!segment.isCode && segment.text.isNotBlank()) {
                segment.text.toMessageAnnotatedString()
            } else {
                null
            }
            segment to annotated
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        renderedSegments.forEach { (segment, annotated) ->
            if (segment.isCode) {
                CodeBlock(segment.text)
            } else if (annotated != null) {
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    val semanticColors = LocalSolinColors.current
    val cleaned = code.trim('\n')
    val lines = cleaned.lines()
    val maybeLanguage = lines.firstOrNull().orEmpty()
    val hasLanguage = maybeLanguage.length in 1..24 &&
        maybeLanguage.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '+' || it == '#' }
    val codeText = if (hasLanguage) lines.drop(1).joinToString("\n") else cleaned

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(semanticColors.codeSurface),
    ) {
        if (hasLanguage) {
            Text(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                text = maybeLanguage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        SelectionContainer {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
                text = codeText.ifBlank { "..." },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                textAlign = TextAlign.Start,
            )
        }
    }
}

private fun String.toMessageAnnotatedString(): AnnotatedString =
    buildAnnotatedString {
        lines().forEachIndexed { index, rawLine ->
            if (index > 0) append('\n')
            appendMarkdownLine(rawLine)
        }
    }

private fun AnnotatedString.Builder.appendMarkdownLine(rawLine: String) {
    val trimmedStart = rawLine.trimStart()
    val normalized = when {
        trimmedStart.startsWith("### ") -> trimmedStart.removePrefix("### ").trim()
        trimmedStart.startsWith("## ") -> trimmedStart.removePrefix("## ").trim()
        trimmedStart.startsWith("# ") -> trimmedStart.removePrefix("# ").trim()
        trimmedStart.startsWith("- ") -> "• ${trimmedStart.removePrefix("- ").trim()}"
        trimmedStart.startsWith("* ") -> "• ${trimmedStart.removePrefix("* ").trim()}"
        else -> rawLine
    }
    val boldRegex = Regex("""\*\*(.+?)\*\*""")
    var cursor = 0
    boldRegex.findAll(normalized).forEach { match ->
        append(normalized.substring(cursor, match.range.first))
        pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
        append(match.groupValues[1])
        pop()
        cursor = match.range.last + 1
    }
    append(normalized.substring(cursor))
}
