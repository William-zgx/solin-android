package com.bytedance.zgx.pocketmind.multimodal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun officeAndRtfAttachmentsRemainMetadataOnlyWithoutPreview() {
        val richDocumentMimeTypes = listOf(
            "application/rtf",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
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
        assertFalse(canReadTextPreviewFor("application/json"))
        assertFalse(canReadTextPreviewFor("application/pdf"))
        assertFalse(canReadTextPreviewFor("image/png"))
        assertFalse(canReadTextPreviewFor("audio/mpeg"))
        assertFalse(canReadTextPreviewFor("video/mp4"))
        assertFalse(canReadTextPreviewFor("application/octet-stream"))
        assertFalse(canReadTextPreviewFor(null))
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
}
