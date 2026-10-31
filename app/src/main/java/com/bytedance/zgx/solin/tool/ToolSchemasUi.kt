package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrContract

/**
 * JSON Schemas for the on-screen observation and UI-automation (device control) tools.
 *
 * These are `internal`, not `private`, only because the specs that reference them live in
 * another file of the same module. ToolSchemaContractTest guards the schema contract.
 */

/**
 * The trailing property pair shared by every package-guarded UI action schema. Interpolated after
 * the 8-space indent, so its first line carries no indent of its own and the rest carry 8 spaces;
 * the enclosing trimIndent() then yields the same JSON text as an inline copy.
 */
private val foregroundPackageGuardProperties = """
"expectedPackageName": {
          "type": "string",
          "description": "Optional foreground package that must still be active before executing this UI action.",
          "minLength": 1,
          "maxLength": 200
        },
        "targetPackageName": {
          "type": "string",
          "description": "Optional alias for expectedPackageName, used by external planners to bind the foreground app.",
          "minLength": 1,
          "maxLength": 200
        }
""".removeSurrounding("\n")

internal val observeCurrentScreenSchemaJson = """
    {
      "type": "object",
      "properties": {
        "maxTextChars": {
          "type": "integer",
          "description": "Maximum characters returned from the active-window Accessibility text summary.",
          "minimum": 1,
          "maximum": 4000
        },
        "maxNodes": {
          "type": "integer",
          "description": "Maximum visible Accessibility nodes returned with transient node ids and bounds.",
          "minimum": 1,
          "maximum": 120
        },
        "expectedPackageName": {
          "type": "string",
          "description": "Post-launch continuation only: the launched target package the observe should wait to reach the foreground before reading, so it does not read a cross-app transition window."
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val uiTapSchemaJson = """
    {
      "type": "object",
      "properties": {
        "target": {
          "type": "string",
          "description": "Transient node id from observe_current_screen, or visible text/contentDescription to match. Provide this OR both targetX and targetY.",
          "minLength": 1,
          "maxLength": 200
        },
        "targetX": {
          "type": "integer",
          "description": "Normalized 0-1000 horizontal coordinate (left=0, right=1000). Requires targetY. Used by the remote vision planner for coordinate taps; resolution-agnostic.",
          "minimum": 0,
          "maximum": 1000
        },
        "targetY": {
          "type": "integer",
          "description": "Normalized 0-1000 vertical coordinate (top=0, bottom=1000). Requires targetX.",
          "minimum": 0,
          "maximum": 1000
        },
        "timeoutMillis": {
          "type": "integer",
          "minimum": 100,
          "maximum": 10000
        },
        ${foregroundPackageGuardProperties}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val uiTypeTextSchemaJson = """
    {
      "type": "object",
      "required": ["text"],
      "properties": {
        "text": {
          "type": "string",
          "minLength": 1,
          "maxLength": 2000
        },
        "target": {
          "type": "string",
          "description": "Optional transient node id or visible label for the editable field.",
          "minLength": 1,
          "maxLength": 200
        },
        "timeoutMillis": {
          "type": "integer",
          "minimum": 100,
          "maximum": 10000
        },
        "allowClipboardPasteFallback": {
          "type": "boolean",
          "description": "When true, allows falling back to temporary clipboard paste if direct Accessibility text setting fails. Defaults to false."
        },
        ${foregroundPackageGuardProperties}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val uiSubmitSearchSchemaJson = """
    {
      "type": "object",
      "properties": {
        "timeoutMillis": {
          "type": "integer",
          "minimum": 100,
          "maximum": 10000
        },
        ${foregroundPackageGuardProperties}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val uiScrollSchemaJson = """
    {
      "type": "object",
      "required": ["direction"],
      "properties": {
        "direction": {
          "type": "string",
          "enum": ["up", "down", "left", "right", "forward", "backward"]
        },
        "target": {
          "type": "string",
          "description": "Optional transient node id or visible label for the scroll container.",
          "minLength": 1,
          "maxLength": 200
        },
        "timeoutMillis": {
          "type": "integer",
          "minimum": 100,
          "maximum": 10000
        },
        ${foregroundPackageGuardProperties}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val uiBackOrWaitSchemaJson = """
    {
      "type": "object",
      "properties": {
        "timeoutMillis": {
          "type": "integer",
          "minimum": 100,
          "maximum": 10000
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val uiSwipeSchemaJson = """
    {
      "type": "object",
      "required": ["startXNorm", "startYNorm", "endXNorm", "endYNorm"],
      "properties": {
        "startXNorm": { "type": "integer", "minimum": 0, "maximum": 1000 },
        "startYNorm": { "type": "integer", "minimum": 0, "maximum": 1000 },
        "endXNorm": { "type": "integer", "minimum": 0, "maximum": 1000 },
        "endYNorm": { "type": "integer", "minimum": 0, "maximum": 1000 },
        "durationMillis": {
          "type": "integer",
          "minimum": 20,
          "maximum": 3000,
          "description": "Swipe duration in ms; longer is slower/more deliberate."
        },
        "timeoutMillis": { "type": "integer", "minimum": 100, "maximum": 10000 },
        ${foregroundPackageGuardProperties}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val uiLongPressSchemaJson = """
    {
      "type": "object",
      "required": ["xNorm", "yNorm"],
      "properties": {
        "xNorm": { "type": "integer", "minimum": 0, "maximum": 1000 },
        "yNorm": { "type": "integer", "minimum": 0, "maximum": 1000 },
        "holdMillis": {
          "type": "integer",
          "minimum": 300,
          "maximum": 3000,
          "description": "Long-press hold duration in ms."
        },
        "timeoutMillis": { "type": "integer", "minimum": 100, "maximum": 10000 },
        ${foregroundPackageGuardProperties}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val uiPressKeySchemaJson = """
    {
      "type": "object",
      "required": ["key"],
      "properties": {
        "key": {
          "type": "string",
          "enum": ["home", "recents", "enter", "delete"],
          "description": "Whitelisted system key. No arbitrary keycodes are accepted."
        },
        "timeoutMillis": { "type": "integer", "minimum": 100, "maximum": 10000 },
        ${foregroundPackageGuardProperties}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val uiWaitSchemaJson = """
    {
      "type": "object",
      "properties": {
        "timeoutMillis": {
          "type": "integer",
          "description": "Settle wait in ms (at most 10000). With a wait condition: how long to poll for it, default 8000, clamped to 500-20000.",
          "minimum": 100,
          "maximum": 20000
        },
        "expectText": {
          "type": "string",
          "description": "Optional wait condition: text that must appear on screen. Whitespace and case are ignored.",
          "minLength": 1,
          "maxLength": 200
        },
        "expectGoneText": {
          "type": "string",
          "description": "Optional wait condition: text that must no longer be on screen. Whitespace and case are ignored.",
          "minLength": 1,
          "maxLength": 200
        },
        "expectPackage": {
          "type": "string",
          "description": "Optional wait condition: package that must become the foreground app.",
          "minLength": 1,
          "maxLength": 200
        },
        "verifySearchQuery": {
          "type": "string",
          "description": "Optional low-risk search query that must be visible or produce recognizable result evidence after waiting.",
          "minLength": 1,
          "maxLength": 200
        },
        "expectedPackageName": {
          "type": "string",
          "description": "Optional foreground package that must be active after waiting and while verifying search results.",
          "minLength": 1,
          "maxLength": 200
        },
        "targetPackageName": {
          "type": "string",
          "description": "Optional alias for expectedPackageName, used by external planners to bind the foreground app.",
          "minLength": 1,
          "maxLength": 200
        },
        "expectedAppName": {
          "type": "string",
          "description": "Optional app name alias used only for local profile-based result verification.",
          "minLength": 1,
          "maxLength": 80
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val observeCurrentScreenOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "source",
        "metadataPolicy",
        "observationId",
        "capturedAtMillis",
        "nodeCount",
        "actionableNodeCount",
        "textSummary",
        "truncated",
        "nodesJson",
        "screenObservationJson",
        "maxTextChars",
        "maxNodes"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "source": {"type": "string", "enum": ["$DEVICE_CONTROL_SOURCE"]},
        "metadataPolicy": {"type": "string", "enum": ["$DEVICE_CONTROL_METADATA_POLICY_SCHEMA_VALUE"]},
        "observationId": {"type": "string", "minLength": 1},
        "packageName": {"type": "string"},
        "capturedAtMillis": {"type": "integer", "minimum": 0},
        "nodeCount": {"type": "integer", "minimum": 0},
        "actionableNodeCount": {"type": "integer", "minimum": 0},
        "textSummary": {"type": "string"},
        "truncated": {"type": "boolean"},
        "nodesJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"},
        "screenObservationJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"},
        "maxTextChars": {"type": "integer", "minimum": 1, "maximum": 4000},
        "maxNodes": {"type": "integer", "minimum": 1, "maximum": 120},
        "screenWidthPx": {"type": "integer", "minimum": 0},
        "screenHeightPx": {"type": "integer", "minimum": 0},
        "screenshotPerception": {"type": "string"}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val uiActionOutputSchemaJson = uiActionOutputSchema()

/** `ui_wait` also reports whether its wait condition held; absent when no condition was given. */
internal val uiWaitOutputSchemaJson = uiActionOutputSchema(
    extraProperties = """,
        "conditionMet": {"type": "boolean"}""",
)

private fun uiActionOutputSchema(extraProperties: String = ""): String = """
    {
      "type": "object",
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "source",
        "metadataPolicy",
        "actionType",
        "status",
        "retryable",
        "summary",
        "beforeObservationId",
        "afterObservationId",
        "verificationSummary"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "source": {"type": "string", "enum": ["$DEVICE_CONTROL_SOURCE"]},
        "metadataPolicy": {"type": "string", "enum": ["$DEVICE_CONTROL_METADATA_POLICY_SCHEMA_VALUE"]},
        "actionType": {"type": "string", "enum": ["tap", "tap_normalized", "type_text", "submit_search", "scroll", "swipe", "long_press", "press_key", "press_back", "wait"]},
        "target": {"type": "string"},
        "direction": {"type": "string", "enum": ["up", "down", "left", "right", "forward", "backward"]},
        "status": {"type": "string", "enum": ["succeeded", "failed"]},
        "retryable": {"type": "boolean"},
        "summary": {"type": "string", "minLength": 1},
        "failureKind": {
          "type": "string",
          "enum": [
            "node_not_found",
            "page_changed",
            "permission_missing",
            "keyboard_obscured",
            "timeout",
            "app_not_foreground",
            "search_entry_not_found",
            "editable_not_found",
            "submit_not_found",
            "result_not_verified",
            "dangerous_action",
            "unknown"
          ]
        },
        "beforeObservationId": {"type": "string"},
        "afterObservationId": {"type": "string"},
        "verificationSummary": {"type": "string", "minLength": 1},
        "screenObservationDiffSummary": {
          "type": "string",
          "description": "Bounded LocalOnly before/after Accessibility observation diff summary for local action replanning.",
          "minLength": 1
        },
        "searchVerificationStatus": {"type": "string", "enum": ["verified", "not_verified"]},
        "searchVerificationEvidence": {"type": "string", "maxLength": 80},
        "uiActionOutcome": {
          "type": "string",
          "enum": ["advanced", "no_change", "wrong_surface", "blocked", "verified", "unknown"]
        },
        "uiActionOutcomeReason": {
          "type": "string",
          "enum": [
            "screen_changed",
            "changed_false",
            "app_not_foreground",
            "permission_missing",
            "dangerous_action",
            "search_verified",
            "type_text_succeeded",
            "submit_search_succeeded",
            "status_succeeded",
            "unknown"
          ]
        },
        "appSearchProgressStage": {
          "type": "string",
          "enum": [
            "opened",
            "observed_entry",
            "entry_tapped",
            "input_ready",
            "query_typed",
            "submitted",
            "verified",
            "blocked",
            "unknown"
          ]
        },
        "beforePackageName": {"type": "string"},
        "beforeCapturedAtMillis": {"type": "integer", "minimum": 0},
        "beforeNodeCount": {"type": "integer", "minimum": 0},
        "beforeActionableNodeCount": {"type": "integer", "minimum": 0},
        "beforeTextSummary": {"type": "string"},
        "beforeTruncated": {"type": "boolean"},
        "beforeNodesJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"},
        "beforeScreenObservationJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"},
        "afterPackageName": {"type": "string"},
        "afterCapturedAtMillis": {"type": "integer", "minimum": 0},
        "afterNodeCount": {"type": "integer", "minimum": 0},
        "afterActionableNodeCount": {"type": "integer", "minimum": 0},
        "afterTextSummary": {"type": "string"},
        "afterTruncated": {"type": "boolean"},
        "afterNodesJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"},
        "afterScreenObservationJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"},
        "key": {"type": "string", "enum": ["home", "recents", "enter", "delete"]},
        "screenWidthPx": {"type": "integer", "minimum": 0},
        "screenHeightPx": {"type": "integer", "minimum": 0},
        "beforeScreenWidthPx": {"type": "integer", "minimum": 0},
        "beforeScreenHeightPx": {"type": "integer", "minimum": 0},
        "beforeScreenshotPerception": {"type": "string"},
        "afterScreenWidthPx": {"type": "integer", "minimum": 0},
        "afterScreenHeightPx": {"type": "integer", "minimum": 0},
        "afterScreenshotPerception": {"type": "string"}$extraProperties
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val currentScreenshotOcrSchemaJson = CurrentScreenshotOcrContract.INPUT_SCHEMA_JSON.trimIndent()

internal val currentScreenshotOcrOutputSchemaJson = CurrentScreenshotOcrContract.OUTPUT_SCHEMA_JSON.trimIndent()

internal val currentScreenTextSchemaJson = """
    {
      "type": "object",
      "properties": {
        "maxChars": {
          "type": "integer",
          "description": "Maximum characters returned from the active-window Accessibility 可访问文本快照；不是截图、OCR、视觉/VLM 或语义屏幕理解。",
          "minimum": 1,
          "maximum": 4000
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val currentScreenTextOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "source",
        "maxChars",
        "capturedAtMillis",
        "nodeCount",
        "truncated",
        "screenTextIncluded",
        "structureSummaryIncluded",
        "rawTreeIncluded",
        "metadataPolicy"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "source": {
          "type": "string",
          "description": "Fixed source for current active-window Accessibility 可访问文本快照；never screenshot, OCR, visual/VLM, or semantic screen understanding.",
          "enum": ["$CURRENT_SCREEN_TEXT_SOURCE"]
        },
        "maxChars": {"type": "integer", "minimum": 1, "maximum": 4000},
        "capturedAtMillis": {"type": "integer"},
        "nodeCount": {"type": "integer", "minimum": 0},
        "screenText": {
          "type": "string",
          "description": "Text exposed by Accessibility from the active window; not screenshot pixels, OCR output, visual/VLM output, or inferred screen semantics.",
          "minLength": 1
        },
        "packageName": {"type": "string"},
        "truncated": {"type": "boolean"},
        "screenTextIncluded": {"type": "boolean"},
        "structureSummary": {
          "type": "string",
          "description": "Coarse Accessibility node/text-item metadata only; no node ids, bounds, hierarchy, screenshots, OCR, or inferred visual semantics.",
          "minLength": 1
        },
        "structureSummaryIncluded": {"type": "boolean"},
        "rawTreeIncluded": {"type": "boolean"},
        "metadataPolicy": {
          "type": "string",
          "enum": ["$CURRENT_SCREEN_TEXT_METADATA_POLICY"]
        }
      },
      "additionalProperties": false
    }
""".trimIndent()
