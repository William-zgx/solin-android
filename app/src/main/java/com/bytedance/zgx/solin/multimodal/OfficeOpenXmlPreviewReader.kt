package com.bytedance.zgx.solin.multimodal

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node

object OfficeOpenXmlPreviewReader {
    fun read(inputStream: InputStream, mimeType: String?): SharedTextPreview? {
        val entryPolicy = EntryPolicy.forMimeType(mimeType) ?: return null
        val parts = mutableListOf<String>()
        var truncated = false
        var consumedXmlBytes = 0
        var entryCount = 0

        ZipInputStream(BufferedInputStream(inputStream)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && entryCount < MAX_ZIP_ENTRIES && consumedXmlBytes < MAX_XML_BYTES) {
                entryCount += 1
                if (!entry.isDirectory && entryPolicy.includes(entry.name)) {
                    val remainingBytes = MAX_XML_BYTES - consumedXmlBytes
                    val entryLimit = minOf(MAX_XML_ENTRY_BYTES, remainingBytes)
                    val (entryBytes, entryOverLimit) = if (entryLimit <= 0) {
                        ByteArray(0) to true
                    } else {
                        zip.readLimitedBytes(entryLimit + 1)
                    }
                    val bytes = if (entryBytes.size > entryLimit) entryBytes.copyOf(entryLimit) else entryBytes
                    consumedXmlBytes += bytes.size
                    truncated = truncated || entryOverLimit || entryBytes.size > entryLimit
                    parts += visibleTextPartsFromXml(bytes)
                    if (parts.joinToString(separator = "\n").length >= MAX_PREVIEW_CHARS) {
                        truncated = true
                        break
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            if (entry != null || entryCount >= MAX_ZIP_ENTRIES || consumedXmlBytes >= MAX_XML_BYTES) {
                truncated = true
            }
        }

        val normalized = parts
            .joinToString(separator = "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return sharedTextPreviewFrom(
            normalizedText = normalized,
            maxChars = MAX_PREVIEW_CHARS,
            truncated = truncated || normalized.length > MAX_PREVIEW_CHARS,
            source = SharedTextPreviewSource.OfficeDocument,
        )
    }

    private fun visibleTextPartsFromXml(bytes: ByteArray): List<String> =
        runCatching {
            val documentBuilder = secureDocumentBuilderFactory().newDocumentBuilder()
            val document = documentBuilder.parse(ByteArrayInputStream(bytes))
            buildList {
                collectVisibleTextParts(document.documentElement, this)
            }
        }.getOrDefault(emptyList())

    private fun collectVisibleTextParts(node: Node, parts: MutableList<String>) {
        if (node.nodeType == Node.TEXT_NODE && node.parentNode?.localElementName() in VISIBLE_TEXT_ELEMENTS) {
            node.nodeValue
                ?.normalizedOfficeTextPart()
                ?.takeIf { it.isNotBlank() }
                ?.let(parts::add)
        }
        val children = node.childNodes
        for (index in 0 until children.length) {
            collectVisibleTextParts(children.item(index), parts)
        }
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setXIncludeAware(false) }
            runCatching { setExpandEntityReferences(false) }
        }

    private fun Node.localElementName(): String =
        localName ?: nodeName.substringAfter(':')

    private fun String.normalizedOfficeTextPart(): String =
        replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { char -> !char.isISOControl() || char == '\n' || char == '\t' }
            .replace(Regex("""[ \t\n]+"""), " ")
            .trim()

    private enum class EntryPolicy {
        WordDocument,
        Spreadsheet,
        Presentation;

        fun includes(entryName: String): Boolean =
            when (this) {
                WordDocument ->
                    entryName == "word/document.xml" ||
                        WORD_HEADER_FOOTER_ENTRY.matches(entryName)

                Spreadsheet ->
                    entryName == "xl/sharedStrings.xml" ||
                        SPREADSHEET_SHEET_ENTRY.matches(entryName)

                Presentation ->
                    PRESENTATION_SLIDE_ENTRY.matches(entryName) ||
                        PRESENTATION_NOTES_ENTRY.matches(entryName)
            }

        companion object {
            fun forMimeType(mimeType: String?): EntryPolicy? =
                when (mimeType.normalizedMediaType()) {
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> WordDocument
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> Spreadsheet
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> Presentation
                    else -> null
                }
        }
    }

    private const val MAX_ZIP_ENTRIES = 160
    private const val MAX_XML_ENTRY_BYTES = 96 * 1024
    private const val MAX_XML_BYTES = 256 * 1024
    private const val MAX_PREVIEW_CHARS = 4_000

    private val WORD_HEADER_FOOTER_ENTRY = Regex("""word/(?:header|footer)\d+\.xml""")
    private val SPREADSHEET_SHEET_ENTRY = Regex("""xl/worksheets/sheet\d+\.xml""")
    private val PRESENTATION_SLIDE_ENTRY = Regex("""ppt/slides/slide\d+\.xml""")
    private val PRESENTATION_NOTES_ENTRY = Regex("""ppt/notesSlides/notesSlide\d+\.xml""")
    private val VISIBLE_TEXT_ELEMENTS = setOf("t", "instrText", "delText")
}
