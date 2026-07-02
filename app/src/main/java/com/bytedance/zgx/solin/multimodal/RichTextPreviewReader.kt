package com.bytedance.zgx.solin.multimodal

import java.io.InputStream

object RichTextPreviewReader {
    fun read(inputStream: InputStream, mimeType: String?): SharedTextPreview? {
        if (!canReadRichTextPreviewFor(mimeType)) return null
        val (bytes, truncatedByBytes) = inputStream.readLimitedBytes(MAX_RTF_PREVIEW_BYTES + 1)
        val previewBytes = if (bytes.size > MAX_RTF_PREVIEW_BYTES) {
            bytes.copyOf(MAX_RTF_PREVIEW_BYTES)
        } else {
            bytes
        }
        val raw = String(previewBytes, Charsets.UTF_8)
        if (!raw.trimStart().startsWith("{\\rtf", ignoreCase = true)) return null
        val normalized = raw
            .toPlainTextFromRtf()
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return sharedTextPreviewFrom(
            normalizedText = normalized,
            maxChars = MAX_RTF_PREVIEW_CHARS,
            truncated = truncatedByBytes || normalized.length > MAX_RTF_PREVIEW_CHARS,
            source = SharedTextPreviewSource.RichTextDocument,
        )
    }

    private fun String.toPlainTextFromRtf(): String {
        val output = StringBuilder()
        val states = ArrayDeque<RtfState>()
        var state = RtfState()
        var index = 0
        while (index < length) {
            when (val char = this[index]) {
                '{' -> {
                    states.addLast(state)
                    state = state.copy()
                    index += 1
                }

                '}' -> {
                    state = states.removeLastOrNull() ?: RtfState()
                    index += 1
                }

                '\\' -> {
                    val consumed = consumeControl(index, state, output)
                    state = consumed.state
                    index = consumed.nextIndex
                }

                '\n', '\r' -> index += 1
                else -> {
                    if (!state.skipDestination && !char.isISOControl()) {
                        output.append(char)
                    }
                    index += 1
                }
            }
        }
        return output.toString()
    }

    private fun String.consumeControl(
        startIndex: Int,
        state: RtfState,
        output: StringBuilder,
    ): ConsumedControl {
        val nextIndex = startIndex + 1
        if (nextIndex >= length) return ConsumedControl(nextIndex, state)
        val marker = this[nextIndex]
        return when {
            marker == '\\' || marker == '{' || marker == '}' -> {
                if (!state.skipDestination) output.append(marker)
                ConsumedControl(nextIndex + 1, state)
            }

            marker == '~' -> {
                if (!state.skipDestination) output.append(' ')
                ConsumedControl(nextIndex + 1, state)
            }

            marker == '-' || marker == '_' -> ConsumedControl(nextIndex + 1, state)
            marker == '*' -> ConsumedControl(nextIndex + 1, state.copy(skipDestination = true))
            marker == '\'' && nextIndex + 2 < length -> {
                if (!state.skipDestination) {
                    val byte = substring(nextIndex + 1, nextIndex + 3).toIntOrNull(radix = 16)
                    byte?.takeIf { it >= 32 }?.let { output.append(it.toChar()) }
                }
                ConsumedControl(nextIndex + 3, state)
            }

            marker.isLetter() -> consumeWordControl(nextIndex, state, output)
            else -> ConsumedControl(nextIndex + 1, state)
        }
    }

    private fun String.consumeWordControl(
        wordStart: Int,
        state: RtfState,
        output: StringBuilder,
    ): ConsumedControl {
        var index = wordStart
        while (index < length && this[index].isLetter()) {
            index += 1
        }
        val word = substring(wordStart, index)
        var sign = 1
        if (index < length && this[index] == '-') {
            sign = -1
            index += 1
        }
        val numberStart = index
        while (index < length && this[index].isDigit()) {
            index += 1
        }
        val numeric = substring(numberStart, index).toIntOrNull()?.let { it * sign }
        val hasDelimiterSpace = index < length && this[index] == ' '
        if (hasDelimiterSpace) index += 1

        val nextState = when (word) {
            "fonttbl",
            "colortbl",
            "datastore",
            "filetbl",
            "info",
            "object",
            "pict",
            "stylesheet",
            "themedata",
            "xmlnstbl" -> state.copy(skipDestination = true)

            else -> state
        }
        if (!nextState.skipDestination) {
            when (word) {
                "line", "par", "row", "sect" -> output.append('\n')
                "tab" -> output.append('\t')
                "emdash" -> output.append('-')
                "endash" -> output.append('-')
                "bullet" -> output.append("* ")
                "u" -> numeric?.let { codePoint ->
                    if (codePoint >= 0) {
                        output.append(codePoint.toChar())
                    }
                }
            }
        }
        val finalState = if (word == "uc" && numeric != null) {
            nextState.copy(unicodeFallbackChars = numeric.coerceAtLeast(0))
        } else {
            nextState
        }
        val finalIndex = if (word == "u" && numeric != null) {
            (index + finalState.unicodeFallbackChars).coerceAtMost(length)
        } else {
            index
        }
        return ConsumedControl(finalIndex, finalState)
    }

    private data class RtfState(
        val skipDestination: Boolean = false,
        val unicodeFallbackChars: Int = 1,
    )

    private data class ConsumedControl(
        val nextIndex: Int,
        val state: RtfState,
    )

    private const val MAX_RTF_PREVIEW_BYTES = 96 * 1024
    private const val MAX_RTF_PREVIEW_CHARS = 4_000
}
