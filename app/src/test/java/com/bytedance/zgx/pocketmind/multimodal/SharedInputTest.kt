package com.bytedance.zgx.pocketmind.multimodal

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedInputTest {
    @Test
    fun promptIncludesTextAndAttachmentMetadataWithoutContent() {
        val input = SharedInput(
            text = "请总结这张图",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Image,
                    mimeType = "image/png",
                    displayName = "screen.png",
                    sizeBytes = 42_000L,
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("请总结这张图"))
        assertTrue(prompt.contains("图片"))
        assertTrue(prompt.contains("screen.png"))
        assertTrue(prompt.contains("image/png"))
        assertTrue(prompt.contains("默认只读取元数据"))
        assertFalse(prompt.contains("\n   文本摘录"))
    }

    @Test
    fun directSharedTextIsSanitizedAndBounded() {
        val input = SharedInput(
            text = "开头\u0000\r\n" + "内".repeat(4_100) + "尾部",
            attachments = emptyList(),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.startsWith("分享文本（已截断）：\n开头\n"))
        assertFalse(prompt.contains("\u0000"))
        assertFalse(prompt.contains("尾部"))
        assertTrue(prompt.length <= "分享文本（已截断）：\n".length + 4_000)
        assertTrue(SharedInput(text = "\u0000\r", attachments = emptyList()).isEmpty)
    }

    @Test
    fun promptIncludesTextPreviewForTextAttachment() {
        val input = SharedInput(
            text = "请总结文档",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "text/plain",
                    displayName = "notes.txt",
                    sizeBytes = 120L,
                    textPreview = SharedTextPreview(
                        text = "第一行\n第二行",
                        truncated = true,
                    ),
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("文本摘录（已截断）"))
        assertTrue(prompt.contains("第一行"))
        assertTrue(prompt.contains("第二行"))
    }

    @Test
    fun promptIncludesOcrPreviewForImageAttachment() {
        val input = SharedInput(
            text = "请总结图片",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Image,
                    mimeType = "image/png",
                    displayName = "screen.png",
                    sizeBytes = 120L,
                    textPreview = SharedTextPreview(
                        text = "标题\n正文",
                        truncated = true,
                        source = SharedTextPreviewSource.ImageOcr,
                    ),
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("用户主动提供的 image/* 附件"))
        assertTrue(prompt.contains("图片文字摘录（已截断）"))
        assertTrue(prompt.contains("标题"))
        assertTrue(prompt.contains("正文"))
    }

    @Test
    fun promptIncludesOfficeOpenXmlTextPreviewForDocumentAttachment() {
        val input = SharedInput(
            text = "请总结文档",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    displayName = "brief.docx",
                    sizeBytes = 120L,
                    textPreview = SharedTextPreview(
                        text = "第一段\n第二段",
                        truncated = true,
                        source = SharedTextPreviewSource.OfficeDocument,
                    ),
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("Office Open XML 文档"))
        assertTrue(prompt.contains("Office 文本摘录（已截断）"))
        assertTrue(prompt.contains("第一段"))
        assertTrue(prompt.contains("第二段"))
    }

    @Test
    fun promptIncludesRichTextPreviewForRtfAttachment() {
        val input = SharedInput(
            text = "请总结 RTF",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "application/rtf",
                    displayName = "notes.rtf",
                    sizeBytes = 120L,
                    textPreview = SharedTextPreview(
                        text = "标题\n正文",
                        truncated = true,
                        source = SharedTextPreviewSource.RichTextDocument,
                    ),
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("RTF 文档"))
        assertTrue(prompt.contains("RTF 文本摘录（已截断）"))
        assertTrue(prompt.contains("标题"))
        assertTrue(prompt.contains("正文"))
    }

    @Test
    fun promptDoesNotIncludeRichTextPreviewForNonRtfOrNonDocumentAttachment() {
        val input = SharedInput(
            text = "",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "text/plain",
                    displayName = "notes.txt",
                    sizeBytes = 120L,
                    textPreview = SharedTextPreview(
                        text = "plain text source mismatch secret",
                        truncated = false,
                        source = SharedTextPreviewSource.RichTextDocument,
                    ),
                ),
                SharedAttachment(
                    kind = SharedAttachmentKind.Image,
                    mimeType = "application/rtf",
                    displayName = "image.rtf",
                    sizeBytes = 121L,
                    textPreview = SharedTextPreview(
                        text = "non document kind secret",
                        truncated = false,
                        source = SharedTextPreviewSource.RichTextDocument,
                    ),
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("notes.txt"))
        assertTrue(prompt.contains("image.rtf"))
        assertFalse(prompt.contains("plain text source mismatch secret"))
        assertFalse(prompt.contains("non document kind secret"))
        assertFalse(prompt.contains("\n   RTF 文本摘录"))
    }

    @Test
    fun binaryAndRichDocumentAttachmentsRemainMetadataOnlyWithoutPreview() {
        val input = SharedInput(
            text = "",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "application/pdf",
                    displayName = "report.pdf",
                    sizeBytes = 42L,
                    textPreview = SharedTextPreview(
                        text = "must not leak",
                        truncated = false,
                    ),
                ),
                SharedAttachment(
                    kind = SharedAttachmentKind.Image,
                    mimeType = "image/png",
                    displayName = "screen.png",
                    sizeBytes = 43L,
                    textPreview = SharedTextPreview(
                        text = "image text file preview must not leak",
                        truncated = false,
                    ),
                ),
                SharedAttachment(
                    kind = SharedAttachmentKind.Audio,
                    mimeType = "audio/mpeg",
                    displayName = "memo.mp3",
                    sizeBytes = 44L,
                ),
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    displayName = "doc.docx",
                    sizeBytes = 45L,
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("report.pdf"))
        assertTrue(prompt.contains("screen.png"))
        assertTrue(prompt.contains("memo.mp3"))
        assertTrue(prompt.contains("doc.docx"))
        assertFalse(prompt.contains("\n   文本摘录"))
        assertFalse(prompt.contains("must not leak"))
        assertFalse(prompt.contains("image text file preview must not leak"))
    }

    @Test
    fun promptDoesNotIncludeOcrPreviewForNonImageAttachment() {
        val input = SharedInput(
            text = "",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "application/pdf",
                    displayName = "scan.pdf",
                    sizeBytes = 42L,
                    textPreview = SharedTextPreview(
                        text = "ocr secret",
                        truncated = false,
                        source = SharedTextPreviewSource.ImageOcr,
                    ),
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("scan.pdf"))
        assertFalse(prompt.contains("ocr secret"))
        assertFalse(prompt.contains("\n   图片文字摘录"))
    }

    @Test
    fun legacyOfficeAndPdfAttachmentsRemainMetadataOnlyWithoutPreview() {
        val richDocumentMimeTypes = listOf(
            "application/pdf",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
        )
        richDocumentMimeTypes.forEachIndexed { index, mimeType ->
            val input = SharedInput(
                text = "",
                attachments = listOf(
                    SharedAttachment(
                        kind = SharedAttachmentKind.Document,
                        mimeType = mimeType,
                        displayName = "document-$index",
                        sizeBytes = 1_000L + index,
                        textPreview = SharedTextPreview(
                            text = "rich document secret $index",
                            truncated = false,
                        ),
                    ),
                ),
            )
            val prompt = input.toPrompt()

            assertTrue(prompt.contains("document-$index"))
            assertTrue(prompt.contains(mimeType))
            assertFalse(prompt.contains("rich document secret $index"))
            assertFalse(prompt.contains("\n   文本摘录"))
        }
    }

    @Test
    fun officePreviewSourceIsIgnoredForNonOpenXmlDocuments() {
        val input = SharedInput(
            text = "",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "application/pdf",
                    displayName = "report.pdf",
                    sizeBytes = 42L,
                    textPreview = SharedTextPreview(
                        text = "pdf secret",
                        truncated = false,
                        source = SharedTextPreviewSource.OfficeDocument,
                    ),
                ),
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "application/msword",
                    displayName = "legacy.doc",
                    sizeBytes = 43L,
                    textPreview = SharedTextPreview(
                        text = "legacy office secret",
                        truncated = false,
                        source = SharedTextPreviewSource.OfficeDocument,
                    ),
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("report.pdf"))
        assertTrue(prompt.contains("legacy.doc"))
        assertFalse(prompt.contains("pdf secret"))
        assertFalse(prompt.contains("legacy office secret"))
        assertFalse(prompt.contains("\n   Office 文本摘录"))
    }

    @Test
    fun promptUsesSafeAttachmentNameOnly() {
        val input = SharedInput(
            text = "",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "application/pdf",
                    displayName = "/storage/emulated/0/Download/private/report.pdf\u0000",
                    sizeBytes = 12_000L,
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("report.pdf"))
        assertFalse(prompt.contains("/storage"))
        assertFalse(prompt.contains("Download/private"))
        assertFalse(prompt.contains("\u0000"))
    }

    @Test
    fun attachmentOnlyShareBuildsPrompt() {
        val input = SharedInput(
            text = "",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "application/pdf",
                    displayName = "report.pdf",
                    sizeBytes = null,
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertFalse(input.isEmpty)
        assertTrue(prompt.startsWith("请根据我分享的附件信息"))
        assertTrue(prompt.contains("report.pdf"))
        assertTrue(prompt.contains("未知大小"))
    }

    @Test
    fun mapsMimeTypesToAttachmentKinds() {
        assertEquals(SharedAttachmentKind.Image, sharedAttachmentKindFor("image/jpeg"))
        assertEquals(SharedAttachmentKind.Image, sharedAttachmentKindFor(" IMAGE/PNG "))
        assertEquals(SharedAttachmentKind.Audio, sharedAttachmentKindFor("audio/mpeg"))
        assertEquals(SharedAttachmentKind.Video, sharedAttachmentKindFor("video/mp4"))
        assertEquals(SharedAttachmentKind.Document, sharedAttachmentKindFor("application/pdf"))
        assertEquals(SharedAttachmentKind.Document, sharedAttachmentKindFor("application/msword"))
        assertEquals(
            SharedAttachmentKind.Document,
            sharedAttachmentKindFor("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        )
        assertEquals(
            SharedAttachmentKind.Document,
            sharedAttachmentKindFor("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        )
        assertEquals(SharedAttachmentKind.Document, sharedAttachmentKindFor("text/plain"))
        assertEquals(SharedAttachmentKind.Document, sharedAttachmentKindFor("text/plain; charset=utf-8"))
        assertEquals(SharedAttachmentKind.Other, sharedAttachmentKindFor("application/octet-stream"))
    }

    @Test
    fun textPreviewReaderOnlyAllowsTextMediaTypes() {
        assertTrue(canReadTextPreviewFor("text/plain"))
        assertTrue(canReadTextPreviewFor(" text/markdown; charset=utf-8 "))
        assertFalse(canReadTextPreviewFor("text/rtf"))
        assertFalse(canReadTextPreviewFor("application/json"))
        assertFalse(canReadTextPreviewFor("application/pdf"))
        assertFalse(canReadTextPreviewFor("image/png"))
        assertFalse(canReadTextPreviewFor("audio/mpeg"))
        assertFalse(canReadTextPreviewFor("video/mp4"))
        assertFalse(canReadTextPreviewFor("application/octet-stream"))
        assertFalse(canReadTextPreviewFor(null))
    }

    @Test
    fun richTextPreviewReaderOnlyAllowsRtfMediaTypes() {
        assertTrue(canReadRichTextPreviewFor("application/rtf"))
        assertTrue(canReadRichTextPreviewFor(" text/rtf; charset=utf-8 "))
        assertFalse(canReadRichTextPreviewFor("text/plain"))
        assertFalse(canReadRichTextPreviewFor("application/pdf"))
        assertFalse(canReadRichTextPreviewFor("application/msword"))
        assertFalse(canReadRichTextPreviewFor(null))
    }

    @Test
    fun officeOpenXmlPreviewReaderOnlyAllowsOpenXmlOfficeMediaTypes() {
        assertTrue(
            canReadOfficeOpenXmlTextPreviewFor(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ),
        )
        assertTrue(
            canReadOfficeOpenXmlTextPreviewFor(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet; charset=binary",
            ),
        )
        assertTrue(
            canReadOfficeOpenXmlTextPreviewFor(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            ),
        )
        assertFalse(canReadOfficeOpenXmlTextPreviewFor("application/pdf"))
        assertFalse(canReadOfficeOpenXmlTextPreviewFor("application/rtf"))
        assertFalse(canReadOfficeOpenXmlTextPreviewFor("application/msword"))
        assertFalse(canReadOfficeOpenXmlTextPreviewFor("text/plain"))
        assertFalse(canReadOfficeOpenXmlTextPreviewFor(null))
    }

    @Test
    fun imageTextPreviewReaderOnlyAllowsImageMediaTypes() {
        assertTrue(canReadImageTextPreviewFor("image/png"))
        assertTrue(canReadImageTextPreviewFor(" image/jpeg; charset=binary "))
        assertFalse(canReadImageTextPreviewFor("text/plain"))
        assertFalse(canReadImageTextPreviewFor("application/pdf"))
        assertFalse(canReadImageTextPreviewFor("audio/mpeg"))
        assertFalse(canReadImageTextPreviewFor(null))
    }

    @Test
    fun textPreviewReaderCleansControlsAndNormalizesNewlines() {
        val preview = TextAttachmentPreviewReader.read(
            "第一\r第二\r\n第三\u0000\u0007\t尾\n\n\n\n结束"
                .byteInputStream(),
        )

        assertNotNull(preview)
        assertEquals("第一\n第二\n第三\t尾\n\n结束", preview!!.text)
        assertFalse(preview.truncated)
    }

    @Test
    fun textPreviewReaderTruncatesByByteAndCharacterLimits() {
        val preview = TextAttachmentPreviewReader.read(
            ByteArray(16 * 1024 + 1) { 'a'.code.toByte() }
                .inputStream(),
        )

        assertNotNull(preview)
        assertEquals(4_000, preview!!.text.length)
        assertTrue(preview.truncated)
    }

    @Test
    fun imageTextPreviewReaderCleansControlsAndTruncates() {
        val preview = ImageTextPreviewReader.fromText(
            "第一\r第二\r\n第三\u0000\u0007\t尾\n\n\n\n结束",
        )
        val truncated = ImageTextPreviewReader.fromText("a".repeat(4_001))

        assertNotNull(preview)
        assertEquals("第一\n第二\n第三\t尾\n\n结束", preview!!.text)
        assertEquals(SharedTextPreviewSource.ImageOcr, preview.source)
        assertFalse(preview.truncated)
        assertNotNull(truncated)
        assertEquals(4_000, truncated!!.text.length)
        assertTrue(truncated.truncated)
    }

    @Test
    fun richTextPreviewReaderExtractsBoundedPlainText() {
        val preview = RichTextPreviewReader.read(
            """{\rtf1\ansi\uc1{\fonttbl{\f0 Arial;}}\b Title\b0\par Body \tab text \u20320?}"""
                .byteInputStream(),
            "application/rtf",
        )

        assertNotNull(preview)
        assertEquals("Title\nBody \ttext 你", preview!!.text)
        assertEquals(SharedTextPreviewSource.RichTextDocument, preview.source)
        assertFalse(preview.truncated)
    }

    @Test
    fun richTextPreviewReaderSkipsMetadataAndRejectsUnsupportedInput() {
        val preview = RichTextPreviewReader.read(
            """{\rtf1\ansi{\info{\title private title}} Visible text}""".byteInputStream(),
            "application/rtf",
        )

        assertNotNull(preview)
        assertEquals("Visible text", preview!!.text)
        assertNull(RichTextPreviewReader.read("plain text".byteInputStream(), "application/rtf"))
        assertNull(RichTextPreviewReader.read("""{\rtf1 secret}""".byteInputStream(), "application/pdf"))
    }

    @Test
    fun richTextPreviewReaderTruncatesLargeText() {
        val preview = RichTextPreviewReader.read(
            """{\rtf1 ${"a".repeat(4_100)}}""".byteInputStream(),
            "application/rtf",
        )

        assertNotNull(preview)
        assertEquals(4_000, preview!!.text.length)
        assertTrue(preview.truncated)
    }

    @Test
    fun sharedAttachmentTextPreviewDispatchesRtfBeforeGenericTextPreview() {
        listOf("application/rtf", "text/rtf; charset=utf-8").forEach { mimeType ->
            var streamOpened = false
            val preview = readSharedAttachmentTextPreview(
                mimeType = mimeType,
                kind = SharedAttachmentKind.Document,
                openInputStream = {
                    streamOpened = true
                    """{\rtf1\ansi Visible RTF text}""".byteInputStream()
                },
                extractImageText = { error("image OCR should not be used for RTF") },
            )

            assertTrue(streamOpened)
            assertNotNull(preview)
            assertEquals("Visible RTF text", preview!!.text)
            assertEquals(SharedTextPreviewSource.RichTextDocument, preview.source)
            assertFalse(preview.text.contains("""{\rtf"""))
        }

        var mismatchStreamOpened = false
        val mismatchedKindPreview = readSharedAttachmentTextPreview(
            mimeType = "application/rtf",
            kind = SharedAttachmentKind.Image,
            openInputStream = {
                mismatchStreamOpened = true
                """{\rtf1 should not be read}""".byteInputStream()
            },
            extractImageText = { error("image OCR should not be used for application/rtf") },
        )
        assertNull(mismatchedKindPreview)
        assertFalse(mismatchStreamOpened)
    }

    @Test
    fun officeOpenXmlPreviewReaderExtractsWordBodyText() {
        val preview = OfficeOpenXmlPreviewReader.read(
            officeZip(
                "word/document.xml" to """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body>
                        <w:p><w:r><w:t>第一段</w:t></w:r></w:p>
                        <w:p><w:r><w:t>第二段</w:t></w:r></w:p>
                      </w:body>
                    </w:document>
                """.trimIndent(),
            ).inputStream(),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )

        assertNotNull(preview)
        assertEquals("第一段\n第二段", preview!!.text)
        assertEquals(SharedTextPreviewSource.OfficeDocument, preview.source)
        assertFalse(preview.truncated)
    }

    @Test
    fun officeOpenXmlPreviewReaderExtractsSpreadsheetAndPresentationText() {
        val spreadsheet = OfficeOpenXmlPreviewReader.read(
            officeZip(
                "xl/sharedStrings.xml" to """
                    <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <si><t>表格标题</t></si>
                      <si><t>单元格内容</t></si>
                    </sst>
                """.trimIndent(),
            ).inputStream(),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )
        val presentation = OfficeOpenXmlPreviewReader.read(
            officeZip(
                "ppt/slides/slide1.xml" to """
                    <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                        xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                      <p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>第一页标题</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld>
                    </p:sld>
                """.trimIndent(),
            ).inputStream(),
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        )

        assertNotNull(spreadsheet)
        assertEquals("表格标题\n单元格内容", spreadsheet!!.text)
        assertNotNull(presentation)
        assertEquals("第一页标题", presentation!!.text)
    }

    @Test
    fun officeOpenXmlPreviewReaderRejectsMissingVisibleTextOrUnsupportedMime() {
        assertNull(
            OfficeOpenXmlPreviewReader.read(
                officeZip("docProps/core.xml" to "<coreProperties><title>metadata secret</title></coreProperties>")
                    .inputStream(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ),
        )
        assertNull(
            OfficeOpenXmlPreviewReader.read(
                officeZip("word/document.xml" to "<w:document><w:t>secret</w:t></w:document>").inputStream(),
                "application/pdf",
            ),
        )
    }

    @Test
    fun officeOpenXmlPreviewReaderTruncatesLargeText() {
        val preview = OfficeOpenXmlPreviewReader.read(
            officeZip(
                "word/document.xml" to """
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>${"a".repeat(4_100)}</w:t></w:r></w:p></w:body>
                    </w:document>
                """.trimIndent(),
            ).inputStream(),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )

        assertNotNull(preview)
        assertEquals(4_000, preview!!.text.length)
        assertTrue(preview.truncated)
    }

    private fun officeZip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
