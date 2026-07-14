package com.bytedance.zgx.solin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.GenerationStats
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.isUsable
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.ui.splitMessageSegments
import com.bytedance.zgx.solin.ui.theme.LocalSolinColors
import java.util.Locale

@Composable
internal fun MessageBubble(
    message: ChatMessage,
    isStreaming: Boolean,
) {
    val isUser = message.role == MessageRole.User
    val semanticColors = LocalSolinColors.current
    val bubbleColor = if (isUser) {
        semanticColors.local
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.94f)
    }
    val textColor = if (isUser) {
        semanticColors.onLocal
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (isUser) {
        RoundedCornerShape(20.dp, 8.dp, 20.dp, 20.dp)
    } else {
        RoundedCornerShape(8.dp, 20.dp, 20.dp, 20.dp)
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.84f else 0.96f),
            shape = shape,
            color = bubbleColor,
            border = if (isUser) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f))
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f))
            },
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = when {
                        isUser -> "你"
                        isStreaming -> "Solin · 生成中"
                        else -> "Solin"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = if (isUser) 0.82f else 0.64f),
                    fontWeight = FontWeight.SemiBold,
                )
                if (isStreaming && message.text.isBlank()) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                SelectionContainer {
                    MessageContent(
                        text = message.text.ifBlank { if (isStreaming) "正在思考..." else "..." },
                        isUser = isUser,
                        color = textColor,
                        renderMarkdown = !isStreaming,
                    )
                }
                if (!isUser && !isStreaming && message.generationStats?.isUsable() == true) {
                    Text(
                        text = formatGenerationStats(message.generationStats),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.54f),
                    )
                }
            }
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
    isUser: Boolean,
    color: Color,
    renderMarkdown: Boolean = true,
) {
    if (isUser || !renderMarkdown) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        splitMessageSegments(text).forEach { segment ->
            if (segment.isCode) {
                CodeBlock(segment.text)
            } else if (segment.text.isNotBlank()) {
                Text(
                    text = segment.text.toMessageAnnotatedString(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = color,
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
            .background(semanticColors.codeSurface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
                shape = MaterialTheme.shapes.medium,
            ),
    ) {
        if (hasLanguage) {
            Text(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                text = maybeLanguage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider()
        }
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

