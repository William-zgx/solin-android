package com.bytedance.zgx.solin.multimodal

import java.io.InputStream
import java.util.zip.InflaterInputStream

object PdfTextPreviewReader {
    fun read(inputStream: InputStream, mimeType: String?): SharedTextPreview? {
        if (!canReadPdfTextPreviewFor(mimeType)) return null
        val (bytes, truncatedByBytes) = inputStream.readLimitedBytes(MAX_PDF_BYTES + 1)
        val pdfBytes = if (bytes.size > MAX_PDF_BYTES) bytes.copyOf(MAX_PDF_BYTES) else bytes
        val header = pdfBytes.copyOf(minOf(pdfBytes.size, PDF_HEADER_SCAN_BYTES)).toLatin1String()
        if (!header.contains("%PDF-")) return null

        val parts = mutableListOf<String>()
        var truncated = truncatedByBytes
        var previewChars = 0
        for (stream in pdfBytes.contentStreams().take(MAX_CONTENT_STREAMS)) {
            val decoded = if (stream.isFlateEncoded) {
                stream.bytes.inflateLimited(MAX_CONTENT_STREAM_BYTES)
            } else {
                stream.bytes to false
            } ?: continue
            truncated = truncated || decoded.second
            val streamParts = decoded.first.extractPdfTextParts()
            parts += streamParts
            previewChars += streamParts.sumOf { it.length }
            if (previewChars >= MAX_PREVIEW_CHARS) {
                truncated = true
                break
            }
        }

        val normalized = parts
            .joinToString(separator = "\n")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return sharedTextPreviewFrom(
            normalizedText = normalized,
            maxChars = MAX_PREVIEW_CHARS,
            truncated = truncated || normalized.length > MAX_PREVIEW_CHARS,
            source = SharedTextPreviewSource.PdfTextLayer,
        )
    }

    private fun ByteArray.contentStreams(): Sequence<PdfContentStream> = sequence {
        val raw = toLatin1String()
        var searchFrom = 0
        var emitted = 0
        while (searchFrom < raw.length && emitted < MAX_CONTENT_STREAMS) {
            val streamIndex = raw.indexOf("stream", startIndex = searchFrom)
            if (streamIndex < 0) break
            val dataStart = raw.streamDataStartAfter(streamIndex + "stream".length)
            if (dataStart < 0) break
            val endIndex = raw.indexOf("endstream", startIndex = dataStart)
            if (endIndex < 0) break
            val dataEnd = raw.streamDataEndBefore(endIndex, dataStart)
            val headerStart = maxOf(0, streamIndex - STREAM_HEADER_SCAN_CHARS)
            val streamHeader = raw.substring(headerStart, streamIndex)
            yield(
                PdfContentStream(
                    bytes = copyOfRange(dataStart, dataEnd),
                    isFlateEncoded = streamHeader.contains("/FlateDecode"),
                ),
            )
            emitted += 1
            searchFrom = endIndex + "endstream".length
        }
    }

    private fun String.streamDataStartAfter(index: Int): Int =
        when {
            index >= length -> -1
            startsWith("\r\n", startIndex = index) -> index + 2
            this[index] == '\n' || this[index] == '\r' -> index + 1
            else -> index
        }

    private fun String.streamDataEndBefore(index: Int, dataStart: Int): Int {
        var dataEnd = index
        if (dataEnd > dataStart && this[dataEnd - 1] == '\n') dataEnd -= 1
        if (dataEnd > dataStart && this[dataEnd - 1] == '\r') dataEnd -= 1
        return dataEnd
    }

    private fun ByteArray.extractPdfTextParts(): List<String> {
        val content = toLatin1String()
        val parts = mutableListOf<String>()
        var searchFrom = 0
        while (searchFrom < content.length) {
            val begin = content.indexOf("BT", startIndex = searchFrom)
            if (begin < 0) break
            val end = content.indexOf("ET", startIndex = begin + 2)
            if (end < 0) break
            parts += content.substring(begin + 2, end).extractPdfTextPartsFromTextObject()
            searchFrom = end + 2
        }
        return parts
    }

    private fun String.extractPdfTextPartsFromTextObject(): List<String> {
        val parts = mutableListOf<String>()
        var index = 0
        while (index < length) {
            when (this[index]) {
                '[' -> {
                    val arrayEnd = findArrayEnd(index)
                    if (arrayEnd > index && operatorAfter(arrayEnd + 1) == "TJ") {
                        val text = substring(index + 1, arrayEnd)
                            .extractStringTokens()
                            .joinToString(separator = "")
                            .normalizedPdfText()
                        if (text.isNotBlank()) parts += text
                    }
                    index = if (arrayEnd > index) arrayEnd + 1 else index + 1
                }

                '(' -> {
                    val token = parseLiteralString(index)
                    if (token != null && operatorAfter(token.endIndex) in DIRECT_TEXT_SHOW_OPERATORS) {
                        val text = token.text.normalizedPdfText()
                        if (text.isNotBlank()) parts += text
                    }
                    index = token?.endIndex ?: index + 1
                }

                '<' -> {
                    val token = parseHexString(index)
                    if (token != null && operatorAfter(token.endIndex) in DIRECT_TEXT_SHOW_OPERATORS) {
                        val text = token.text.normalizedPdfText()
                        if (text.isNotBlank()) parts += text
                    }
                    index = token?.endIndex ?: index + 1
                }

                else -> index += 1
            }
        }
        return parts
    }

    private fun String.extractStringTokens(): List<String> {
        val tokens = mutableListOf<String>()
        var index = 0
        while (index < length) {
            val literal = parseLiteralString(index)
            if (literal != null) {
                tokens += literal.text
                index = literal.endIndex
                continue
            }
            val hex = parseHexString(index)
            if (hex != null) {
                tokens += hex.text
                index = hex.endIndex
                continue
            }
            index += 1
        }
        return tokens
    }

    private fun String.findArrayEnd(startIndex: Int): Int {
        var index = startIndex + 1
        while (index < length) {
            when (this[index]) {
                '(' -> index = parseLiteralString(index)?.endIndex ?: (index + 1)
                '<' -> index = parseHexString(index)?.endIndex ?: (index + 1)
                ']' -> return index
                else -> index += 1
            }
        }
        return -1
    }

    private fun String.operatorAfter(startIndex: Int): String? {
        var index = startIndex
        while (index < length && this[index].isWhitespace()) index += 1
        return DIRECT_TEXT_SHOW_OPERATORS.firstOrNull { operator ->
            startsWith(operator, startIndex = index)
        } ?: "TJ".takeIf { startsWith("TJ", startIndex = index) }
    }

    private fun String.parseLiteralString(startIndex: Int): ParsedPdfString? {
        if (startIndex >= length || this[startIndex] != '(') return null
        val bytes = mutableListOf<Byte>()
        var index = startIndex + 1
        var depth = 1
        while (index < length) {
            when (val char = this[index]) {
                '\\' -> {
                    val escaped = consumeEscapedPdfChar(index)
                    bytes += escaped.bytes
                    index = escaped.nextIndex
                }

                '(' -> {
                    depth += 1
                    bytes += char.toPdfByte()
                    index += 1
                }

                ')' -> {
                    depth -= 1
                    if (depth == 0) {
                        return ParsedPdfString(decodePdfStringBytes(bytes.toByteArray()), index + 1)
                    }
                    bytes += char.toPdfByte()
                    index += 1
                }

                else -> {
                    bytes += char.toPdfByte()
                    index += 1
                }
            }
        }
        return null
    }

    private fun String.consumeEscapedPdfChar(startIndex: Int): EscapedPdfChar {
        val markerIndex = startIndex + 1
        if (markerIndex >= length) return EscapedPdfChar(emptyList(), markerIndex)
        val marker = this[markerIndex]
        return when (marker) {
            'n' -> EscapedPdfChar(listOf('\n'.code.toByte()), markerIndex + 1)
            'r' -> EscapedPdfChar(listOf('\r'.code.toByte()), markerIndex + 1)
            't' -> EscapedPdfChar(listOf('\t'.code.toByte()), markerIndex + 1)
            'b' -> EscapedPdfChar(listOf(8.toByte()), markerIndex + 1)
            'f' -> EscapedPdfChar(listOf(12.toByte()), markerIndex + 1)
            '(', ')', '\\' -> EscapedPdfChar(listOf(marker.toPdfByte()), markerIndex + 1)
            '\r' -> {
                val next = if (markerIndex + 1 < length && this[markerIndex + 1] == '\n') {
                    markerIndex + 2
                } else {
                    markerIndex + 1
                }
                EscapedPdfChar(emptyList(), next)
            }
            '\n' -> EscapedPdfChar(emptyList(), markerIndex + 1)
            else -> {
                if (marker in '0'..'7') {
                    var index = markerIndex
                    val digits = StringBuilder()
                    while (index < length && digits.length < 3 && this[index] in '0'..'7') {
                        digits.append(this[index])
                        index += 1
                    }
                    EscapedPdfChar(listOf(digits.toString().toInt(8).toByte()), index)
                } else {
                    EscapedPdfChar(listOf(marker.toPdfByte()), markerIndex + 1)
                }
            }
        }
    }

    private fun String.parseHexString(startIndex: Int): ParsedPdfString? {
        if (startIndex >= length || this[startIndex] != '<') return null
        if (startIndex + 1 < length && this[startIndex + 1] == '<') return null
        val endIndex = indexOf('>', startIndex = startIndex + 1)
        if (endIndex < 0) return null
        val hex = substring(startIndex + 1, endIndex)
            .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            .let { value -> if (value.length % 2 == 0) value else value + "0" }
        if (hex.isBlank()) return ParsedPdfString("", endIndex + 1)
        val bytes = ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return ParsedPdfString(decodePdfStringBytes(bytes), endIndex + 1)
    }

    private fun ByteArray.inflateLimited(limit: Int): Pair<ByteArray, Boolean>? =
        runCatching {
            InflaterInputStream(inputStream()).use { input ->
                val (bytes, truncated) = input.readLimitedBytes(limit + 1)
                if (bytes.size > limit) bytes.copyOf(limit) to true else bytes to truncated
            }
        }.getOrNull()

    private fun decodePdfStringBytes(bytes: ByteArray): String =
        when {
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16BE)

            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16LE)

            else -> String(bytes, Charsets.ISO_8859_1)
        }

    private fun String.normalizedPdfText(): String =
        replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { char -> !char.isISOControl() || char == '\n' || char == '\t' }
            .replace(Regex("""[ \t\n]+"""), " ")
            .trim()

    private fun ByteArray.toLatin1String(): String = String(this, Charsets.ISO_8859_1)

    private fun Char.toPdfByte(): Byte = (code and 0xff).toByte()

    private data class PdfContentStream(
        val bytes: ByteArray,
        val isFlateEncoded: Boolean,
    )

    private data class ParsedPdfString(
        val text: String,
        val endIndex: Int,
    )

    private data class EscapedPdfChar(
        val bytes: List<Byte>,
        val nextIndex: Int,
    )

    private const val MAX_PDF_BYTES = 512 * 1024
    private const val MAX_CONTENT_STREAM_BYTES = 256 * 1024
    private const val MAX_CONTENT_STREAMS = 32
    private const val MAX_PREVIEW_CHARS = 4_000
    private const val PDF_HEADER_SCAN_BYTES = 1_024
    private const val STREAM_HEADER_SCAN_CHARS = 512
    private val DIRECT_TEXT_SHOW_OPERATORS = listOf("Tj", "'", "\"")
}
