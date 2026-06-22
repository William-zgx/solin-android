package com.bytedance.zgx.pocketmind.multimodal

object CurrentScreenshotOcrContract {
    const val TOOL_NAME = "capture_current_screenshot_ocr"
    const val OUTPUT_METADATA_POLICY = "ocr_text_local_only_no_uri_path_or_pixels_persisted"
    const val CONSENT_REASON = "media_projection_one_shot_current_screen_ocr"
    const val SOURCE = "current_screen"
    const val CAPTURE_MODE = "current_screen"

    const val INPUT_SCHEMA_JSON = """
{
  "type": "object",
  "required": ["captureMode"],
  "properties": {
    "captureMode": {
      "type": "string",
      "enum": ["current_screen"],
      "description": "One-shot capture of the currently visible screen after foreground user confirmation and MediaProjection consent."
    }
  },
  "additionalProperties": false
}
"""

    const val OUTPUT_SCHEMA_JSON = """
{
  "type": "object",
  "required": [
    "toolName",
    "source",
    "captureMode",
    "truncated",
    "ocrTextIncluded",
    "rawPayloadIncluded",
    "metadataPolicy",
    "privacy",
    "requiresLocalModel"
  ],
  "properties": {
    "toolName": {"type": "string", "const": "capture_current_screenshot_ocr"},
    "source": {"type": "string", "enum": ["current_screen"]},
    "captureMode": {"type": "string", "enum": ["current_screen"]},
    "ocrText": {"type": "string", "minLength": 1},
    "truncated": {"type": "boolean"},
    "ocrTextIncluded": {"type": "boolean"},
    "rawPayloadIncluded": {"type": "boolean", "const": false},
    "metadataPolicy": {
      "type": "string",
      "enum": ["ocr_text_local_only_no_uri_path_or_pixels_persisted"]
    },
    "privacy": {"type": "string", "enum": ["LocalOnly"]},
    "requiresLocalModel": {"type": "boolean", "const": true}
  },
  "additionalProperties": false
}
"""

    fun validateBoundary(boundary: CurrentScreenshotOcrBoundary): List<String> {
        val errors = mutableListOf<String>()
        if (!boundary.foregroundToolConfirmationRequired) {
            errors += "current screenshot OCR must require foreground tool confirmation"
        }
        if (!boundary.mediaProjectionConsentRequired) {
            errors += "current screenshot OCR must require Android MediaProjection consent"
        }
        if (!boundary.oneShotOnly) {
            errors += "current screenshot OCR must be one-shot, not continuous capture"
        }
        if (!boundary.localOnly || !boundary.requiresLocalModel) {
            errors += "current screenshot OCR must stay LocalOnly and require a local model"
        }
        if (boundary.persistsPixels || boundary.persistsUriPathOrWindowTitle) {
            errors += "current screenshot OCR must not persist pixels, URI/path, or window title metadata"
        }
        if (boundary.producesSemanticVisualUnderstanding) {
            errors += "current screenshot OCR may produce OCR text only, not visual semantics"
        }
        return errors
    }
}

data class CurrentScreenshotOcrBoundary(
    val foregroundToolConfirmationRequired: Boolean,
    val mediaProjectionConsentRequired: Boolean,
    val oneShotOnly: Boolean,
    val localOnly: Boolean,
    val requiresLocalModel: Boolean,
    val persistsPixels: Boolean,
    val persistsUriPathOrWindowTitle: Boolean,
    val producesSemanticVisualUnderstanding: Boolean,
)
