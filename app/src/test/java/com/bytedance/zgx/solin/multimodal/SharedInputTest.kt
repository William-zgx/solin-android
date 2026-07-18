package com.bytedance.zgx.solin.multimodal

import com.bytedance.zgx.solin.ChatImageAttachment
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.DEFAULT_CHAT_MODEL_ID
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.InstalledModelSummary
import com.bytedance.zgx.solin.LocalImageAttachment
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.data.ModelVerificationStatus
import com.bytedance.zgx.solin.evidence.EvidenceSourceType
import com.bytedance.zgx.solin.orchestration.PromptPrivacyPlanner
import com.bytedance.zgx.solin.orchestration.toPromptPrivacySegments
import com.bytedance.zgx.solin.presentation.ChatSharedInputSupport
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedInputTest {
    private companion object {
        const val MIME_PNG = "image/png"
        const val MIME_PDF = "application/pdf"
        const val MIME_TEXT = "text/plain"
        const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val IMAGE_DATA_URL = "data:image/png;base64,AA=="
    }

    @Test
    fun legacySharedInputMetadataFailsClosed() {
        val input = SharedInput(text = "legacy", attachments = emptyList())

        val plan = PromptPrivacyPlanner.build(input.toPromptPrivacySegments())

        assertTrue(input.sourcePrivacy.isEmpty())
        assertEquals(MessagePrivacy.LocalOnly, plan.aggregatePrivacy)
        assertTrue(plan.requiresLocalModel)
    }

    @Test
    fun sharedPayloadPrivacyComesFromReadSourceNotInferencePreference() {
        val localImage = imageAttachment(localImageAttachment = localPngAttachment())
        val remoteImage = imageAttachment(remoteImageAttachment = remotePngAttachment())
        val ocrDocument = documentAttachment(
            mimeType = MIME_PDF,
            displayName = "scan.pdf",
            textPreview = preview("local OCR", source = SharedTextPreviewSource.PdfImageOcr),
        )

        val localMetadata = sharedInputSourcePrivacyFor(
            text = "local shared text",
            attachments = listOf(localImage, ocrDocument),
        )
        val remoteMetadata = sharedInputSourcePrivacyFor(
            text = "",
            attachments = listOf(remoteImage),
        )

        assertEquals(
            listOf(
                SharedInputSource.Text,
                SharedInputSource.Image,
                SharedInputSource.ScreenOcr,
            ),
            localMetadata.map { it.source },
        )
        assertTrue(localMetadata.all {
            it.privacy == MessagePrivacy.LocalOnly && it.requiresLocalModel == true
        })
        assertEquals(
            listOf(SharedInputSourcePrivacy(SharedInputSource.Image, MessagePrivacy.RemoteEligible, false)),
            remoteMetadata,
        )
    }

    @Test
    fun autoStagesLocalImageWithCompleteFailClosedPrivacyMetadata() {
        val attachment = imageAttachment(localImageAttachment = localPngAttachment())
        val input = SharedInput(
            text = "",
            attachments = listOf(attachment),
            sourcePrivacy = sharedInputSourcePrivacyFor("", listOf(attachment)),
        )
        val state = MutableStateFlow(ChatUiState(inferenceMode = InferenceMode.Auto))
        val support = ChatSharedInputSupport(
            uiState = state,
            replaceActiveSessionMessages = { _, _ -> },
            isGenerationActive = { false },
            allocateVoiceInputDraftId = { 1L },
            sendMessageInternal = { _, _, _, _, _ -> },
        )

        support.stageSharedInput(input)

        val draft = requireNotNull(state.value.pendingSharedInputDraft)
        assertEquals(MessagePrivacy.LocalOnly, draft.privacy)
        assertTrue(draft.requiresLocalModel)
        assertEquals(0, draft.optionalHistoryFilteredCount)
        assertEquals(1, draft.localImageAttachments.size)
        assertTrue(draft.imageAttachments.isEmpty())
    }

    @Test
    fun autoPassesBothDestinationNeutralImageRepresentationsToPlacement() {
        val attachment = imageAttachment(
            remoteImageAttachment = remotePngAttachment(),
            localImageAttachment = localPngAttachment(),
        )
        val metadata = sharedInputSourcePrivacyFor("", listOf(attachment))
        val input = SharedInput(
            text = "",
            attachments = listOf(attachment),
            sourcePrivacy = metadata,
        )
        val state = MutableStateFlow(ChatUiState(inferenceMode = InferenceMode.Auto, isReady = true))
        var sentRemoteImages = 0
        var sentLocalImages = 0
        val support = ChatSharedInputSupport(
            uiState = state,
            replaceActiveSessionMessages = { _, _ -> },
            isGenerationActive = { false },
            allocateVoiceInputDraftId = { 1L },
            sendMessageInternal = { _, _, remoteImages, localImages, _ ->
                sentRemoteImages = remoteImages.size
                sentLocalImages = localImages.size
            },
        )

        support.stageSharedInput(input)

        assertEquals(
            listOf(SharedInputSourcePrivacy(SharedInputSource.Image, MessagePrivacy.RemoteEligible, false)),
            metadata,
        )
        val draft = requireNotNull(state.value.pendingSharedInputDraft)
        assertEquals(MessagePrivacy.RemoteEligible, draft.privacy)
        assertFalse(draft.requiresLocalModel)
        assertEquals(1, draft.imageAttachments.size)
        assertEquals(1, draft.localImageAttachments.size)
        assertEquals(0, input.toSharedEvidenceReceiptSummary().localOnlyEvidenceCardCount)

        support.sendPendingSharedInput()

        assertEquals(1, sentRemoteImages)
        assertEquals(1, sentLocalImages)
    }

    @Test
    fun manualModesKeepTheirExistingImageRepresentation() {
        val attachment = imageAttachment(
            remoteImageAttachment = remotePngAttachment(),
            localImageAttachment = localPngAttachment(),
        )
        val input = SharedInput(
            text = "",
            attachments = listOf(attachment),
            sourcePrivacy = sharedInputSourcePrivacyFor("", listOf(attachment)),
        )

        listOf(
            InferenceMode.Local to (0 to 1),
            InferenceMode.Remote to (1 to 0),
        ).forEach { (mode, expectedCounts) ->
            val state = MutableStateFlow(readyVisionState(mode))
            var sentRemoteImages = 0
            var sentLocalImages = 0
            val support = ChatSharedInputSupport(
                uiState = state,
                replaceActiveSessionMessages = { _, _ -> },
                isGenerationActive = { false },
                allocateVoiceInputDraftId = { 1L },
                sendMessageInternal = { _, _, remoteImages, localImages, _ ->
                    sentRemoteImages = remoteImages.size
                    sentLocalImages = localImages.size
                },
            )

            support.stageSharedInput(input)
            support.sendPendingSharedInput()

            assertEquals("$mode remote image count", expectedCounts.first, sentRemoteImages)
            assertEquals("$mode local image count", expectedCounts.second, sentLocalImages)
        }
    }

    @Test
    fun manualRemoteCannotSendDraftThatRequiresLocalModel() {
        val attachment = imageAttachment(
            remoteImageAttachment = remotePngAttachment(),
            localImageAttachment = localPngAttachment(),
        )
        val input = SharedInput(
            text = "",
            attachments = listOf(attachment),
            sourcePrivacy = listOf(
                SharedInputSourcePrivacy(
                    source = SharedInputSource.Image,
                    privacy = MessagePrivacy.LocalOnly,
                    requiresLocalModel = true,
                ),
            ),
        )
        val state = MutableStateFlow(
            ChatUiState(
                inferenceMode = InferenceMode.Auto,
                isReady = true,
            ),
        )
        var sendCount = 0
        val support = ChatSharedInputSupport(
            uiState = state,
            replaceActiveSessionMessages = { _, _ -> },
            isGenerationActive = { false },
            allocateVoiceInputDraftId = { 1L },
            sendMessageInternal = { _, _, _, _, _ -> sendCount += 1 },
        )

        support.stageSharedInput(input)
        assertEquals(1, requireNotNull(state.value.pendingSharedInputDraft).imageAttachments.size)
        assertTrue(requireNotNull(state.value.pendingSharedInputDraft).requiresLocalModel)
        state.value = state.value.copy(inferenceMode = InferenceMode.Remote)
        support.sendPendingSharedInput()

        assertEquals(0, sendCount)
        assertNotNull(state.value.pendingSharedInputDraft)
        assertEquals("此内容仅限本地处理", state.value.statusText)
    }

    @Test
    fun promptIncludesTextAndAttachmentMetadataWithoutContent() {
        val input = SharedInput(
            text = "请总结这张图",
            attachments = listOf(
                imageAttachment(sizeBytes = 42_000L),
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
                documentAttachment(
                    mimeType = MIME_TEXT,
                    displayName = "notes.txt",
                    textPreview = preview("第一行\n第二行", truncated = true),
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("文本摘录（已截断）"))
        assertTrue(prompt.contains("第一行"))
        assertTrue(prompt.contains("第二行"))
    }

    @Test
    fun textPreviewCarriesQualitySignalIntoPrompt() {
        val input = SharedInput(
            text = "请看摘录",
            attachments = listOf(
                documentAttachment(
                    mimeType = MIME_TEXT,
                    displayName = "noisy.txt",
                    textPreview = preview("???"),
                ),
            ),
        )

        val preview = requireNotNull(input.attachments.single().textPreview)
        val prompt = input.toPrompt()

        assertEquals(MultimodalQualityLevel.Low, preview.quality.level)
        assertTrue(preview.quality.reasons.contains("short_text"))
        assertTrue(prompt.contains("质量较低"))
    }

    @Test
    fun promptIncludesOcrPreviewForImageAttachment() {
        val input = SharedInput(
            text = "请总结图片",
            attachments = listOf(
                imageAttachment(
                    textPreview = preview(
                        text = "标题\n正文",
                        truncated = true,
                        source = SharedTextPreviewSource.ImageOcr,
                    ),
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("图片文字摘录（已截断）"))
        assertTrue(prompt.contains("标题"))
        assertTrue(prompt.contains("正文"))
    }

    @Test
    fun promptMentionsAttachedImageForRemoteVisionAttachment() {
        val input = SharedInput(
            text = "",
            attachments = listOf(
                imageAttachment(remoteImageAttachment = remotePngAttachment()),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("图片已随本次请求"))
        assertTrue(prompt.contains("提供给模型"))
        assertTrue(prompt.contains("不支持图片输入"))
        assertTrue(prompt.contains("图片会随本次模型请求提供"))
        assertTrue(prompt.contains("screen.png"))
        assertFalse(prompt.contains("只支持图片 OCR"))
    }

    @Test
    fun remoteImageAttachmentIgnoresStaleOcrPreviewInPromptAndReceiptSummary() {
        val input = SharedInput(
            text = "",
            attachments = listOf(
                imageAttachment(
                    textPreview = preview(
                        text = "private OCR text must not reach remote prompt or receipt",
                        source = SharedTextPreviewSource.ImageOcr,
                    ),
                    remoteImageAttachment = remotePngAttachment(),
                ),
            ),
        )

        val prompt = input.toPrompt()
        val summary = input.toSharedEvidenceReceiptSummary()

        assertTrue(prompt.contains("图片会随本次模型请求提供"))
        assertFalse(prompt.contains("private OCR text"))
        assertFalse(prompt.contains("\n   图片文字摘录"))
        assertEquals(1, summary.evidenceCardCount)
        assertEquals(0, summary.localOnlyEvidenceCardCount)
        assertEquals(listOf(EvidenceSourceType.ImageAttachment), summary.sourceTypes)
    }

    @Test
    fun localVisionPromptOmitsAttachmentMetadataAndDoesNotClaimUnsupported() {
        val input = SharedInput(
            text = "用户附带说明",
            attachments = listOf(
                imageAttachment(
                    displayName = "private-screen.png",
                    localImageAttachment = localPngAttachment(),
                ),
            ),
        )

        val prompt = input.toLocalVisionPrompt()

        assertTrue(prompt.contains("已附加 1 张图片"))
        assertTrue(prompt.contains("本地模型"))
        assertTrue(prompt.contains("不会发送远端"))
        assertTrue(prompt.contains("用户附带说明"))
        assertFalse(prompt.contains("private-screen.png"))
        assertFalse(prompt.contains("image/png"))
        assertFalse(prompt.contains("120"))
        assertFalse(prompt.contains("不会自动 OCR"))
        assertFalse(prompt.contains("data:image"))
        assertFalse(prompt.contains("base64"))
    }

    @Test
    fun localImageAttachmentIgnoresStaleOcrPreviewInPromptAndReceiptSummary() {
        val input = SharedInput(
            text = "",
            attachments = listOf(
                imageAttachment(
                    displayName = "private-screen.png",
                    textPreview = preview(
                        text = "private OCR text must not reach local vision prompt or receipt",
                        source = SharedTextPreviewSource.ImageOcr,
                    ),
                    localImageAttachment = localPngAttachment(),
                ),
            ),
        )

        val prompt = input.toLocalVisionPrompt()
        val summary = input.toSharedEvidenceReceiptSummary()

        assertTrue(prompt.contains("已附加 1 张图片"))
        assertFalse(prompt.contains("private OCR text"))
        assertFalse(prompt.contains("\n   图片文字摘录"))
        assertEquals(1, summary.evidenceCardCount)
        assertEquals(1, summary.localOnlyEvidenceCardCount)
        assertEquals(listOf(EvidenceSourceType.ImageAttachment), summary.sourceTypes)
    }

    @Test
    fun remoteVisionPromptOmitsAttachmentMetadataAndOcr() {
        val input = SharedInput(
            text = "",
            protectedSourceCount = 2,
            attachments = listOf(
                imageAttachment(
                    displayName = "private-screen.png",
                    textPreview = preview(
                        text = "private OCR text",
                        source = SharedTextPreviewSource.ImageOcr,
                    ),
                    remoteImageAttachment = remotePngAttachment(),
                ),
            ),
        )

        val prompt = input.toRemoteVisionPrompt()

        assertTrue(prompt.contains("已附加 1 张图片"))
        assertTrue(prompt.contains("不支持图片输入"))
        assertTrue(prompt.contains("2 个非图片或分享来源已被保护"))
        assertFalse(prompt.contains("private-screen.png"))
        assertFalse(prompt.contains("image/png"))
        assertFalse(prompt.contains("120"))
        assertFalse(prompt.contains("private OCR text"))
    }

    @Test
    fun promptIncludesOfficeOpenXmlTextPreviewForDocumentAttachment() {
        val input = SharedInput(
            text = "请总结文档",
            attachments = listOf(
                documentAttachment(
                    mimeType = MIME_DOCX,
                    displayName = "brief.docx",
                    textPreview = preview(
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
    fun promptIncludesPdfTextLayerPreviewForDocumentAttachment() {
        val input = SharedInput(
            text = "请总结 PDF",
            attachments = listOf(
                documentAttachment(
                    mimeType = MIME_PDF,
                    displayName = "brief.pdf",
                    textPreview = preview(
                        text = "PDF 第一段\nPDF 第二段",
                        truncated = true,
                        source = SharedTextPreviewSource.PdfTextLayer,
                    ),
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("RTF/PDF 文本层"))
        assertTrue(prompt.contains("PDF 文本摘录（已截断）"))
        assertTrue(prompt.contains("PDF 第一段"))
        assertTrue(prompt.contains("PDF 第二段"))
    }

    @Test
    fun promptIncludesPdfImageOcrPreviewForPdfDocumentAttachment() {
        val input = SharedInput(
            text = "请总结扫描 PDF",
            attachments = listOf(
                documentAttachment(
                    mimeType = MIME_PDF,
                    displayName = "scan.pdf",
                    textPreview = preview(
                        text = "第 1 页:\n扫描页文字",
                        truncated = true,
                        source = SharedTextPreviewSource.PdfImageOcr,
                    ),
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("PDF 扫描页 OCR"))
        assertTrue(prompt.contains("PDF 扫描页 OCR 摘录（已截断）"))
        assertTrue(prompt.contains("扫描页文字"))
    }

    @Test
    fun evidenceReceiptSummaryClassifiesTruncatedPdfImageOcrAsLocalOcrEvidence() {
        val summary = SharedInput(
            text = "",
            attachments = listOf(
                documentAttachment(
                    mimeType = MIME_PDF,
                    displayName = "scan.pdf",
                    sizeBytes = 512L,
                    textPreview = preview(
                        text = "private scanned pdf OCR excerpt",
                        truncated = true,
                        source = SharedTextPreviewSource.PdfImageOcr,
                    ),
                ),
            ),
        ).toSharedEvidenceReceiptSummary()

        assertEquals(1, summary.evidenceCardCount)
        assertEquals(1, summary.localOnlyEvidenceCardCount)
        assertEquals(1, summary.truncatedEvidenceCardCount)
        assertEquals(listOf(EvidenceSourceType.OcrText), summary.sourceTypes)
    }

    @Test
    fun promptIncludesRichTextPreviewForRtfAttachment() {
        val input = SharedInput(
            text = "请总结 RTF",
            attachments = listOf(
                documentAttachment(
                    mimeType = "application/rtf",
                    displayName = "notes.rtf",
                    textPreview = preview(
                        text = "标题\n正文",
                        truncated = true,
                        source = SharedTextPreviewSource.RichTextDocument,
                    ),
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("RTF/PDF 文本层"))
        assertTrue(prompt.contains("RTF 文本摘录（已截断）"))
        assertTrue(prompt.contains("标题"))
        assertTrue(prompt.contains("正文"))
    }

    @Test
    fun promptDoesNotIncludeRichTextPreviewForNonRtfOrNonDocumentAttachment() {
        val input = SharedInput(
            text = "",
            attachments = listOf(
                documentAttachment(
                    mimeType = MIME_TEXT,
                    displayName = "notes.txt",
                    textPreview = preview(
                        text = "plain text source mismatch secret",
                        source = SharedTextPreviewSource.RichTextDocument,
                    ),
                ),
                imageAttachment(
                    mimeType = "application/rtf",
                    displayName = "image.rtf",
                    sizeBytes = 121L,
                    textPreview = preview(
                        text = "non document kind secret",
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
    fun unsupportedTextPreviewSourcesRemainMetadataOnlyWithoutPreview() {
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
        assertFalse(prompt.contains("\n   图片文字摘录"))
        assertFalse(prompt.contains("\n   PDF 文本摘录"))
        assertFalse(prompt.contains("\n   Office 文本摘录"))
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
    fun pdfPreviewSourceIsIgnoredForNonPdfDocuments() {
        val input = SharedInput(
            text = "",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "application/msword",
                    displayName = "legacy.doc",
                    sizeBytes = 42L,
                    textPreview = SharedTextPreview(
                        text = "legacy pdf source secret",
                        truncated = false,
                        source = SharedTextPreviewSource.PdfTextLayer,
                    ),
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("legacy.doc"))
        assertFalse(prompt.contains("legacy pdf source secret"))
        assertFalse(prompt.contains("\n   PDF 文本摘录"))
    }

    @Test
    fun pdfImageOcrPreviewSourceIsIgnoredForNonPdfDocuments() {
        val input = SharedInput(
            text = "",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "application/msword",
                    displayName = "legacy.doc",
                    sizeBytes = 42L,
                    textPreview = SharedTextPreview(
                        text = "legacy pdf ocr source marker",
                        truncated = false,
                        source = SharedTextPreviewSource.PdfImageOcr,
                    ),
                ),
                SharedAttachment(
                    kind = SharedAttachmentKind.Image,
                    mimeType = "application/pdf",
                    displayName = "fake-image.pdf",
                    sizeBytes = 43L,
                    textPreview = SharedTextPreview(
                        text = "wrong kind pdf ocr source marker",
                        truncated = false,
                        source = SharedTextPreviewSource.PdfImageOcr,
                    ),
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("legacy.doc"))
        assertTrue(prompt.contains("fake-image.pdf"))
        assertFalse(prompt.contains("legacy pdf ocr source marker"))
        assertFalse(prompt.contains("wrong kind pdf ocr source marker"))
        assertFalse(prompt.contains("\n   PDF 扫描页 OCR 摘录"))
    }

    @Test
    fun protectedSharedInputBuildsValueFreePrompt() {
        val input = SharedInput(text = "", attachments = emptyList(), protectedSourceCount = 2)

        val prompt = input.toPrompt()

        assertFalse(input.isEmpty)
        assertEquals(
            "已收到受保护分享。为保护隐私，本次未读取分享文本、附件元数据、文本摘录或 OCR；请切换到本地模型后重新分享，或手动粘贴你愿意处理的内容。",
            prompt,
        )
        assertFalse(prompt.contains("2"))
    }

    @Test
    fun protectedImageSharedInputBuildsValueFreePrompt() {
        val input = SharedInput(text = "", attachments = emptyList(), protectedImageSourceCount = 2)

        val prompt = input.toPrompt()

        assertFalse(input.isEmpty)
        assertTrue(prompt.contains("已收到受保护图片"))
        assertTrue(prompt.contains("未读取、OCR 或发送图片内容"))
        assertTrue(prompt.contains("当前模型未启用视觉输入能力"))
        assertFalse(prompt.contains("2"))
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
    fun metadataOnlyDocumentAndGenericAttachmentsSayBodyWasNotRead() {
        val input = SharedInput(
            text = "",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Document,
                    mimeType = "application/pdf",
                    displayName = "report.pdf",
                    sizeBytes = 12_000L,
                ),
                SharedAttachment(
                    kind = SharedAttachmentKind.Other,
                    mimeType = "*/*",
                    displayName = "archive.bin",
                    sizeBytes = 4_096L,
                ),
            ),
        )

        val prompt = input.toPrompt()

        assertTrue(prompt.contains("文档正文未读取；当前仅有元数据。"))
        assertTrue(prompt.contains("附件正文未读取；当前仅有元数据。"))
    }

    @Test
    fun mapsMimeTypesToAttachmentKinds() {
        assertEquals(SharedAttachmentKind.Image, sharedAttachmentKindFor("image/jpeg"))
        assertEquals(SharedAttachmentKind.Image, sharedAttachmentKindFor(" IMAGE/PNG "))
        assertEquals(SharedAttachmentKind.Audio, sharedAttachmentKindFor("audio/mpeg"))
        assertEquals(SharedAttachmentKind.Video, sharedAttachmentKindFor("video/mp4"))
        assertEquals(SharedAttachmentKind.Document, sharedAttachmentKindFor("application/pdf"))
        assertEquals(SharedAttachmentKind.Document, sharedAttachmentKindFor("application/json"))
        assertEquals(SharedAttachmentKind.Document, sharedAttachmentKindFor("APPLICATION/XML; charset=utf-8"))
        assertEquals(SharedAttachmentKind.Document, sharedAttachmentKindFor("application/x-yaml"))
        assertEquals(SharedAttachmentKind.Other, sharedAttachmentKindFor("application/ld+json"))
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
    fun resolvesAttachmentMimeTypeFromDisplayNameWhenProviderTypeIsMissingOrGeneric() {
        assertEquals(
            "image/jpeg",
            resolveSharedAttachmentMimeType(
                resolverMimeType = null,
                displayName = "Receipt.JPG",
            ),
        )
        assertEquals(
            "application/pdf",
            resolveSharedAttachmentMimeType(
                resolverMimeType = "application/octet-stream",
                displayName = "report.pdf",
            ),
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            resolveSharedAttachmentMimeType(
                resolverMimeType = " application/octet-stream; charset=binary ",
                displayName = "/tmp/private/brief.DOCX",
            ),
        )
        assertEquals(
            "image/png",
            resolveSharedAttachmentMimeType(
                resolverMimeType = "image/png",
                displayName = "not-image.txt",
                intentMimeType = "application/octet-stream",
            ),
        )
    }

    @Test
    fun resolvesAttachmentMimeTypeFromExtensionBeforeWildcardOrAbstractTypes() {
        assertEquals(
            "application/pdf",
            resolveSharedAttachmentMimeType(
                resolverMimeType = "image/*",
                displayName = "report.pdf",
                intentMimeType = "application/*",
            ),
        )
        assertEquals(
            "text/plain",
            resolveSharedAttachmentMimeType(
                resolverMimeType = "*/*",
                displayName = "notes.txt",
                intentMimeType = "application/octet-stream",
            ),
        )
        assertEquals(
            "application/json",
            resolveSharedAttachmentMimeType(
                resolverMimeType = "application/*",
                displayName = "config.JSON",
                intentMimeType = "text/*",
            ),
        )
        assertEquals(
            "image/*",
            resolveSharedAttachmentMimeType(
                resolverMimeType = "image/*",
                displayName = "unknown-file",
                intentMimeType = null,
            ),
        )
    }

    @Test
    fun trustedRemoteImageMimeTypeRequiresConcreteProviderTypeOrKnownExtension() {
        assertEquals(
            "image/png",
            trustedRemoteImageMimeType(
                resolverMimeType = "image/png",
                displayName = "private.txt",
            ),
        )
        assertEquals(
            "image/jpeg",
            trustedRemoteImageMimeType(
                resolverMimeType = "image/*",
                displayName = "Receipt.JPG",
            ),
        )
        assertNull(
            trustedRemoteImageMimeType(
                resolverMimeType = null,
                displayName = "unknown-file",
            ),
        )
        assertNull(
            trustedRemoteImageMimeType(
                resolverMimeType = "text/plain",
                displayName = "Receipt.JPG",
            ),
        )
        assertNull(
            trustedRemoteImageMimeType(
                resolverMimeType = "image/*",
                displayName = "unknown-file",
            ),
        )
        assertNull(
            trustedRemoteImageMimeType(
                resolverMimeType = "image/svg+xml",
                displayName = "diagram.svg",
            ),
        )
    }

    @Test
    fun protectedRemoteImageSourceDetectionUsesOnlySafeTypeSignals() {
        assertTrue(
            isProtectedRemoteImageSource(
                resolverMimeType = "image/png",
                displayName = "private.txt",
                intentMimeType = null,
            ),
        )
        assertTrue(
            isProtectedRemoteImageSource(
                resolverMimeType = "image/*",
                displayName = "Receipt.JPG",
                intentMimeType = null,
            ),
        )
        assertTrue(
            isProtectedRemoteImageSource(
                resolverMimeType = null,
                displayName = "unknown-file",
                intentMimeType = "image/*",
            ),
        )
        assertFalse(
            isProtectedRemoteImageSource(
                resolverMimeType = "text/plain",
                displayName = "Receipt.JPG",
                intentMimeType = "image/*",
            ),
        )
    }

    @Test
    fun remoteImageBytesMustMatchDeclaredSupportedImageType() {
        val pngBytes = byteArrayOf(
            0x89.toByte(),
            'P'.code.toByte(),
            'N'.code.toByte(),
            'G'.code.toByte(),
            0x0D.toByte(),
            0x0A.toByte(),
            0x1A.toByte(),
            0x0A.toByte(),
            0x00,
        )
        val jpgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val webpBytes = "RIFF".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".encodeToByteArray()
        val gifBytes = "GIF89a".encodeToByteArray()
        val bmpBytes = "BM".encodeToByteArray() + byteArrayOf(0, 0)
        val heicBytes = byteArrayOf(0, 0, 0, 24) + "ftypheic".encodeToByteArray()

        assertTrue(remoteImageBytesMatchDeclaredMimeType("image/png", pngBytes))
        assertTrue(remoteImageBytesMatchDeclaredMimeType("image/jpeg", jpgBytes))
        assertTrue(remoteImageBytesMatchDeclaredMimeType("image/webp", webpBytes))
        assertTrue(remoteImageBytesMatchDeclaredMimeType("image/gif", gifBytes))
        assertTrue(remoteImageBytesMatchDeclaredMimeType("image/bmp", bmpBytes))
        assertTrue(remoteImageBytesMatchDeclaredMimeType("image/heic", heicBytes))

        assertFalse(remoteImageBytesMatchDeclaredMimeType("image/png", "not a png".encodeToByteArray()))
        assertFalse(remoteImageBytesMatchDeclaredMimeType("image/jpeg", pngBytes))
        assertFalse(remoteImageBytesMatchDeclaredMimeType("image/svg+xml", "<svg/>".encodeToByteArray()))
        assertFalse(remoteImageBytesMatchDeclaredMimeType("image/*", pngBytes))
    }

    @Test
    fun textPreviewReaderAllowsTextAndTextLikeApplicationMediaTypes() {
        assertTrue(canReadTextPreviewFor("text/plain"))
        assertTrue(canReadTextPreviewFor(" text/markdown; charset=utf-8 "))
        assertTrue(canReadTextPreviewFor("application/json"))
        assertTrue(canReadTextPreviewFor(" application/xml; charset=utf-8 "))
        assertTrue(canReadTextPreviewFor("application/yaml"))
        assertTrue(canReadTextPreviewFor("application/x-yaml"))
        assertFalse(canReadTextPreviewFor("text/rtf"))
        assertFalse(canReadTextPreviewFor("application/ld+json"))
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
    fun pdfPreviewReaderOnlyAllowsPdfMediaType() {
        assertTrue(canReadPdfTextPreviewFor("application/pdf"))
        assertTrue(canReadPdfTextPreviewFor(" application/pdf; charset=binary "))
        assertFalse(canReadPdfTextPreviewFor("text/plain"))
        assertFalse(canReadPdfTextPreviewFor("application/rtf"))
        assertFalse(canReadPdfTextPreviewFor("application/msword"))
        assertFalse(canReadPdfTextPreviewFor(null))
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
    fun textPreviewReaderRejectsMalformedUtf8BinaryInput() {
        val preview = TextAttachmentPreviewReader.read(
            byteArrayOf(
                'v'.code.toByte(),
                'a'.code.toByte(),
                0xFF.toByte(),
                'l'.code.toByte(),
                'u'.code.toByte(),
                'e'.code.toByte(),
            ).inputStream(),
        )

        assertNull(preview)
    }

    @Test
    fun textPreviewReaderKeepsValidPrefixWhenByteLimitCutsTrailingUtf8Character() {
        val partialMultibyteSuffix = byteArrayOf(0xE4.toByte(), 0xBD.toByte())
        val bytes = ByteArray(16 * 1024 - 1) { 'a'.code.toByte() } + partialMultibyteSuffix

        val preview = TextAttachmentPreviewReader.read(bytes.inputStream())

        assertNotNull(preview)
        assertEquals(4_000, preview!!.text.length)
        assertTrue(preview.text.all { char -> char == 'a' })
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
        )
        assertNull(mismatchedKindPreview)
        assertFalse(mismatchStreamOpened)
    }

    @Test
    fun sharedAttachmentTextPreviewDispatchesPdfTextLayer() {
        val preview = readSharedAttachmentTextPreview(
            mimeType = "application/pdf",
            kind = SharedAttachmentKind.Document,
            openInputStream = { pdfBytes("BT (PDF title) Tj ET").inputStream() },
            extractPdfImageText = { error("PDF OCR should not run when text layer is available") },
        )

        assertNotNull(preview)
        assertEquals("PDF title", preview!!.text)
        assertEquals(SharedTextPreviewSource.PdfTextLayer, preview.source)
    }

    @Test
    fun sharedAttachmentTextPreviewFallsBackToPdfImageOcrWhenTextLayerIsEmpty() {
        var streamOpened = false
        var pdfOcrUsed = false
        val preview = readSharedAttachmentTextPreview(
            mimeType = "application/pdf",
            kind = SharedAttachmentKind.Document,
            openInputStream = {
                streamOpened = true
                "%PDF-1.4\n%%EOF".byteInputStream()
            },
            extractPdfImageText = {
                pdfOcrUsed = true
                SharedTextPreview(
                    text = "第 1 页:\nscanned text",
                    truncated = false,
                    source = SharedTextPreviewSource.PdfImageOcr,
                )
            },
        )

        assertTrue(streamOpened)
        assertTrue(pdfOcrUsed)
        assertNotNull(preview)
        assertEquals("第 1 页:\nscanned text", preview!!.text)
        assertEquals(SharedTextPreviewSource.PdfImageOcr, preview.source)
    }

    @Test
    fun sharedAttachmentTextPreviewSkipsPdfOcrForNonPdfOrNonDocumentAttachments() {
        listOf(
            "application/msword" to SharedAttachmentKind.Document,
            "application/pdf" to SharedAttachmentKind.Image,
        ).forEach { (mimeType, kind) ->
            var streamOpened = false
            val preview = readSharedAttachmentTextPreview(
                mimeType = mimeType,
                kind = kind,
                openInputStream = {
                    streamOpened = true
                    "%PDF-1.4\n%%EOF".byteInputStream()
                },
                extractPdfImageText = { error("PDF OCR should not run for $kind $mimeType") },
            )

            assertNull(preview)
            assertFalse(streamOpened)
        }
    }

    @Test
    fun sharedAttachmentTextPreviewDoesNotOpenImageStreamsForImplicitOcr() {
        var streamOpened = false

        val preview = readSharedAttachmentTextPreview(
            mimeType = "image/png",
            kind = SharedAttachmentKind.Image,
            openInputStream = {
                streamOpened = true
                "private image bytes".byteInputStream()
            },
            extractPdfImageText = { error("PDF OCR should not run for image attachments") },
        )

        assertNull(preview)
        assertFalse(streamOpened)
    }

    @Test
    fun sharedAttachmentTextPreviewDispatchesTextLikeApplicationMediaTypes() {
        listOf(
            "application/json; charset=utf-8" to """{"title":"Release notes","items":["one","two"]}""",
            "application/xml" to "<release><title>Release notes</title></release>",
            "application/yaml" to "title: Release notes\nitems:\n  - one\n  - two",
            "application/x-yaml" to "title: Release notes\nitems:\n  - one\n  - two",
        ).forEach { (mimeType, text) ->
            val preview = readSharedAttachmentTextPreview(
                mimeType = mimeType,
                kind = SharedAttachmentKind.Document,
                openInputStream = { text.byteInputStream() },
            )

            assertNotNull(preview)
            assertEquals(text, preview!!.text)
            assertEquals(SharedTextPreviewSource.TextFile, preview.source)
        }
    }

    @Test
    fun sharedAttachmentTextPreviewSkipsBinaryApplicationMediaTypes() {
        listOf("application/octet-stream", "application/ld+json").forEach { mimeType ->
            var streamOpened = false
            val preview = readSharedAttachmentTextPreview(
                mimeType = mimeType,
                kind = SharedAttachmentKind.Other,
                openInputStream = {
                    streamOpened = true
                    "binary".byteInputStream()
                },
            )

            assertNull(preview)
            assertFalse(streamOpened)
        }
    }

    @Test
    fun protectedSharedAttachmentTextPreviewDoesNotOpenStreamsOrRunOcr() {
        listOf(SharedInputReadMode.ProtectedSignal, SharedInputReadMode.RemoteVisionUnsupportedSignal).forEach { mode ->
            listOf(
                "text/plain" to SharedAttachmentKind.Document,
                "application/json" to SharedAttachmentKind.Document,
                "application/rtf" to SharedAttachmentKind.Document,
                "application/pdf" to SharedAttachmentKind.Document,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to SharedAttachmentKind.Document,
                "image/png" to SharedAttachmentKind.Image,
            ).forEach { (mimeType, kind) ->
                val preview = readSharedAttachmentTextPreview(
                    mimeType = mimeType,
                    kind = kind,
                    openInputStream = { error("protected share must not open attachment stream") },
                    extractPdfImageText = { error("protected share must not run PDF OCR") },
                    mode = mode,
                )

                assertNull(preview)
            }
        }
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

    @Test
    fun pdfTextPreviewReaderExtractsLiteralArrayHexAndEscapedText() {
        val preview = PdfTextPreviewReader.read(
            pdfBytes(
                """
                BT
                (First paragraph) Tj
                [(Sec) 12 (ond)] TJ
                <504446> Tj
                (escaped \(value\)\nnext) Tj
                ET
                """.trimIndent(),
            ).inputStream(),
            "application/pdf",
        )

        assertNotNull(preview)
        assertEquals("First paragraph\nSecond\nPDF\nescaped (value) next", preview!!.text)
        assertEquals(SharedTextPreviewSource.PdfTextLayer, preview.source)
        assertFalse(preview.truncated)
    }

    @Test
    fun pdfTextPreviewReaderExtractsFlateEncodedTextLayer() {
        val preview = PdfTextPreviewReader.read(
            pdfBytes(
                content = "BT (compressed text layer) Tj ET",
                flateEncoded = true,
            ).inputStream(),
            "application/pdf",
        )

        assertNotNull(preview)
        assertEquals("compressed text layer", preview!!.text)
    }

    @Test
    fun pdfTextPreviewReaderRejectsUnsupportedOrImageOnlyInput() {
        assertNull(PdfTextPreviewReader.read("%PDF-1.4\n%%EOF".byteInputStream(), "application/pdf"))
        assertNull(PdfTextPreviewReader.read(pdfBytes("(secret) Tj").inputStream(), "text/plain"))
        assertNull(PdfTextPreviewReader.read("plain text".byteInputStream(), "application/pdf"))
    }

    @Test
    fun pdfTextPreviewReaderTruncatesLargeTextLayer() {
        val preview = PdfTextPreviewReader.read(
            pdfBytes("BT (${ "a".repeat(4_100) }) Tj ET").inputStream(),
            "application/pdf",
        )

        assertNotNull(preview)
        assertEquals(4_000, preview!!.text.length)
        assertTrue(preview.truncated)
    }

    private fun documentAttachment(
        mimeType: String? = MIME_PDF,
        displayName: String? = "report.pdf",
        sizeBytes: Long? = 120L,
        textPreview: SharedTextPreview? = null,
    ): SharedAttachment =
        SharedAttachment(
            kind = SharedAttachmentKind.Document,
            mimeType = mimeType,
            displayName = displayName,
            sizeBytes = sizeBytes,
            textPreview = textPreview,
        )

    private fun imageAttachment(
        mimeType: String? = MIME_PNG,
        displayName: String? = "screen.png",
        sizeBytes: Long? = 120L,
        textPreview: SharedTextPreview? = null,
        remoteImageAttachment: ChatImageAttachment? = null,
        localImageAttachment: LocalImageAttachment? = null,
    ): SharedAttachment =
        SharedAttachment(
            kind = SharedAttachmentKind.Image,
            mimeType = mimeType,
            displayName = displayName,
            sizeBytes = sizeBytes,
            textPreview = textPreview,
            imageAttachment = remoteImageAttachment,
            localImageAttachment = localImageAttachment,
        )

    private fun preview(
        text: String,
        truncated: Boolean = false,
        source: SharedTextPreviewSource = SharedTextPreviewSource.TextFile,
    ): SharedTextPreview =
        SharedTextPreview(
            text = text,
            truncated = truncated,
            source = source,
        )

    private fun remotePngAttachment(): ChatImageAttachment =
        ChatImageAttachment(
            mimeType = MIME_PNG,
            dataUrl = IMAGE_DATA_URL,
        )

    private fun localPngAttachment(bytes: ByteArray = byteArrayOf(1, 2, 3)): LocalImageAttachment =
        LocalImageAttachment(
            mimeType = MIME_PNG,
            bytes = bytes,
            sizeBytes = bytes.size.toLong(),
        )

    private fun readyVisionState(inferenceMode: InferenceMode): ChatUiState = ChatUiState(
        inferenceMode = inferenceMode,
        isReady = true,
        activeInstalledModelId = DEFAULT_CHAT_MODEL_ID,
        installedModels = listOf(
            InstalledModelSummary(
                id = DEFAULT_CHAT_MODEL_ID,
                displayName = "local vision",
                path = "/local/model.litertlm",
                fileBytes = 1L,
                recommendedModelId = DEFAULT_CHAT_MODEL_ID,
                verificationStatus = ModelVerificationStatus.VerifiedRecommended,
            ),
        ),
        remoteModelConfig = RemoteModelConfig(
            baseUrl = "https://example.com/v1",
            modelName = "remote vision",
            supportsVisionInput = true,
        ),
    )

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

    private fun pdfBytes(
        content: String,
        flateEncoded: Boolean = false,
    ): ByteArray {
        val streamBytes = if (flateEncoded) {
            val output = ByteArrayOutputStream()
            DeflaterOutputStream(output).use { deflater ->
                deflater.write(content.toByteArray(Charsets.ISO_8859_1))
            }
            output.toByteArray()
        } else {
            content.toByteArray(Charsets.ISO_8859_1)
        }
        val filter = if (flateEncoded) "/Filter /FlateDecode " else ""
        return (
            "%PDF-1.4\n" +
                "1 0 obj\n" +
                "<< $filter/Length ${streamBytes.size} >>\n" +
                "stream\n"
        ).toByteArray(Charsets.ISO_8859_1) +
            streamBytes +
            "\nendstream\nendobj\n%%EOF".toByteArray(Charsets.ISO_8859_1)
    }
}
