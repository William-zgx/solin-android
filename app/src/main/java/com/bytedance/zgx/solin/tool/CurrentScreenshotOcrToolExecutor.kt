package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.CurrentScreenControlProvider
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.UiActionFailureKind
import com.bytedance.zgx.solin.device.toScreenObservationJsonString
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrContract
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrProvider
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrReadResult
import com.bytedance.zgx.solin.multimodal.OcrTextBlock
import com.bytedance.zgx.solin.multimodal.toOcrBlocksJsonString

class CurrentScreenshotOcrToolExecutor(
    private val provider: CurrentScreenshotOcrProvider?,
    private val currentScreenControlProvider: CurrentScreenControlProvider? = null,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        if (request.toolName != MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR) {
            return request.unknownToolFailure()
        }

        val nowMillis = clockMillis()
        val screenObservationBeforeCapture = provider
            ?.takeIf { candidate -> candidate.hasOneShotConsent(request.id, nowMillis) }
            ?.let {
                currentScreenControlProvider?.observeCurrentScreen()
            }
        return when (val result = provider?.captureCurrentScreenshotOcr(request.id, nowMillis)) {
            null ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = "当前屏幕截图 OCR 服务不可用",
                    retryable = true,
                    data = request.localOnlyData(),
                )

            CurrentScreenshotOcrReadResult.MissingConsent ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "需要完成 Android MediaProjection 前台同意后，才能单次截取当前屏幕并本地提取 OCR 文本。",
                    retryable = false,
                    data = request.localOnlyData() + mapOf(
                        "specialAccess" to CurrentScreenshotOcrContract.CONSENT_REASON,
                    ),
                )

            is CurrentScreenshotOcrReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = result.reason,
                    retryable = true,
                    data = request.currentScreenshotOcrBaseData(),
                )

            is CurrentScreenshotOcrReadResult.Available -> {
                val ocrText = result.text?.takeIf { it.isNotBlank() }
                val screenObservationData = currentScreenControlProvider.screenObservationData(
                    ocrBlocks = result.ocrBlocks,
                    beforeCapture = screenObservationBeforeCapture,
                )
                request.succeeded(
                    summary = if (ocrText == null) {
                        "已完成当前屏幕单次 OCR，未识别到可用文字。"
                    } else {
                        "已从当前屏幕单次截图提取 ${ocrText.length} 个字符的本地 OCR 摘录。"
                    },
                    data = request.currentScreenshotOcrBaseData() +
                        mapOf(
                            "truncated" to result.truncated.toString(),
                            "ocrTextIncluded" to (ocrText != null).toString(),
                        ) +
                        ocrText?.let { mapOf("ocrText" to it) }.orEmpty() +
                        result.ocrBlocks.takeIf { it.isNotEmpty() }
                            ?.let { mapOf("ocrBlocksJson" to it.toOcrBlocksJsonString()) }
                            .orEmpty() +
                        screenObservationData,
                )
            }
        }
    }

    private fun CurrentScreenControlProvider?.screenObservationData(
        ocrBlocks: List<OcrTextBlock>,
        beforeCapture: ScreenStateReadResult?,
    ): Map<String, String> {
        val provider = this ?: return mapOf(
            "screenObservationIncluded" to false.toString(),
            "screenObservationFailureKind" to "provider_unavailable",
        )
        return when (val beforeResult = beforeCapture ?: provider.observeCurrentScreen()) {
            is ScreenStateReadResult.Available ->
                when (val afterResult = provider.observeCurrentScreen()) {
                    is ScreenStateReadResult.Available -> {
                        val beforeSignature = beforeResult.snapshot.toOcrGroundingScreenSignature()
                        val afterSignature = afterResult.snapshot.toOcrGroundingScreenSignature()
                        if (beforeSignature != afterSignature) {
                            mapOf(
                                "screenObservationIncluded" to false.toString(),
                                "screenObservationFailureKind" to "page_changed",
                            )
                        } else {
                            mapOf(
                                "screenObservationIncluded" to true.toString(),
                                "screenObservationJson" to afterResult.snapshot.toScreenObservationJsonString(
                                    ocrBlocks = ocrBlocks,
                                ),
                            )
                        }
                    }

                    is ScreenStateReadResult.PermissionDenied ->
                        mapOf(
                            "screenObservationIncluded" to false.toString(),
                            "screenObservationFailureKind" to UiActionFailureKind.PermissionMissing.schemaValue,
                        )

                    is ScreenStateReadResult.Failed ->
                        mapOf(
                            "screenObservationIncluded" to false.toString(),
                            "screenObservationFailureKind" to afterResult.failureKind.schemaValue,
                        )
                }

            is ScreenStateReadResult.PermissionDenied ->
                mapOf(
                    "screenObservationIncluded" to false.toString(),
                    "screenObservationFailureKind" to UiActionFailureKind.PermissionMissing.schemaValue,
                )

            is ScreenStateReadResult.Failed ->
                mapOf(
                    "screenObservationIncluded" to false.toString(),
                    "screenObservationFailureKind" to beforeResult.failureKind.schemaValue,
                )
        }
    }
}

private fun ToolRequest.currentScreenshotOcrBaseData(): Map<String, String> =
    localOnlyData() + mapOf(
        "source" to CurrentScreenshotOcrContract.SOURCE,
        "captureMode" to CurrentScreenshotOcrContract.CAPTURE_MODE,
        "metadataPolicy" to CurrentScreenshotOcrContract.OUTPUT_METADATA_POLICY,
        "rawPayloadIncluded" to false.toString(),
    )
