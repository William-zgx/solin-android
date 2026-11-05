package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.action.MobileActionFunctions

/**
 * Everyday assistant intents: phone dial / SMS drafts, media transport keys and the local
 * calculator. Appended LAST to [builtInToolSpecs] so the pre-existing registry order (planning
 * prompts, capability matrix, BuiltInToolSpecSnapshotTest) is unchanged.
 *
 * Risk reasoning:
 * - dial_phone_number / compose_sms only pre-fill the system dialer / SMS composer
 *   (ACTION_DIAL / ACTION_SENDTO). The user still has to press call / send in the external app,
 *   so they are drafts: MediumDraftOrNavigation with Required confirmation, never ACTION_CALL and
 *   never the CALL_PHONE / SEND_SMS permissions. The number is not persisted for restart
 *   recovery (empty pendingArgumentAllowlist).
 * - control_media sends a system media key (no Android permission, no data leaves the device,
 *   trivially reversible) but has an audible side effect on another app, so it is a device
 *   action, not a read-only one: MediumDraftOrNavigation + Required confirmation, tagged
 *   [ToolCapabilityTag.LowRiskDeviceAction] so only the user's explicit "reduce device action
 *   confirmations" opt-in may skip the sheet — the same convention as open_wifi_settings.
 * - calculate is pure arithmetic over its own arguments: LowReadOnly, no confirmation. The
 *   expression and result stay LocalOnly private outputs (they can contain personal numbers such
 *   as salary or rent) and the answer is shown as-is without model continuation.
 */
/** Digits with an optional leading +; separators are normalized away by the parser. */
internal const val PHONE_NUMBER_PATTERN = "^[+]?[0-9]{3,20}\$"
internal const val MAX_SMS_BODY_CHARS = 1_000

internal val mediaControlCommands = listOf("play", "pause", "play_pause", "next", "previous", "stop")

internal val phoneDialSchemaJson = """
    {
      "type": "object",
      "required": ["phoneNumber"],
      "properties": {
        "phoneNumber": {"type": "string", "pattern": "$PHONE_NUMBER_PATTERN"}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val smsDraftSchemaJson = """
    {
      "type": "object",
      "required": ["phoneNumber"],
      "properties": {
        "phoneNumber": {"type": "string", "pattern": "$PHONE_NUMBER_PATTERN"},
        "body": {"type": "string", "maxLength": $MAX_SMS_BODY_CHARS}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val mediaControlSchemaJson = """
    {
      "type": "object",
      "required": ["command"],
      "properties": {
        "command": {
          "type": "string",
          "enum": [${mediaControlCommands.joinToString(", ") { "\"$it\"" }}]
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val mediaControlOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "command", "dispatchState", "completionVerified"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "command": {
          "type": "string",
          "enum": [${mediaControlCommands.joinToString(", ") { "\"$it\"" }}]
        },
        "dispatchState": {"type": "string", "enum": ["KeyEventDispatched"]},
        "completionVerified": {"type": "boolean"}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val calculateSchemaJson = """
    {
      "type": "object",
      "required": ["expression"],
      "properties": {
        "expression": {"type": "string", "minLength": 1, "maxLength": ${LocalCalculator.MAX_EXPRESSION_CHARS}}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val calculateOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "privacy", "requiresLocalModel", "kind", "expression", "result", "approximate"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "kind": {"type": "string", "enum": ["arithmetic", "unit_conversion"]},
        "expression": {"type": "string", "minLength": 1},
        "result": {"type": "string", "minLength": 1},
        "approximate": {"type": "boolean"}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val assistantIntentToolSpecs: List<ToolSpec> = listOf(
    ToolSpec(
        name = MobileActionFunctions.DIAL_PHONE_NUMBER,
        title = "拨号草稿",
        description = "打开系统拨号盘并预填电话号码，由用户在拨号界面手动拨出；不会直接拨打电话。phoneNumber 只能是数字号码（可带 + 前缀），联系人姓名需要先用 query_contacts 查到号码。",
        inputSchemaJson = phoneDialSchemaJson,
        outputSchemaJson = externalActivityOutputSchemaJson,
        capability = ToolCapability.ExternalDraft,
        permissions = setOf(
            ToolPermission.StartsExternalActivity,
            ToolPermission.SendsTextToExternalApp,
        ),
        riskLevel = RiskLevel.MediumDraftOrNavigation,
        confirmationPolicy = ConfirmationPolicy.Required,
    ),
    ToolSpec(
        name = MobileActionFunctions.COMPOSE_SMS,
        title = "短信草稿",
        description = "打开系统短信应用并预填收件号码和正文，由用户在短信应用中手动发送；不会直接发送短信。phoneNumber 只能是数字号码（可带 + 前缀），联系人姓名需要先用 query_contacts 查到号码。",
        inputSchemaJson = smsDraftSchemaJson,
        outputSchemaJson = externalActivityOutputSchemaJson,
        capability = ToolCapability.ExternalDraft,
        permissions = setOf(
            ToolPermission.StartsExternalActivity,
            ToolPermission.SendsTextToExternalApp,
        ),
        riskLevel = RiskLevel.MediumDraftOrNavigation,
        confirmationPolicy = ConfirmationPolicy.Required,
        egressTextArguments = setOf("body"),
    ),
    ToolSpec(
        name = MobileActionFunctions.CONTROL_MEDIA,
        title = "媒体播放控制",
        description = "向当前正在播放媒体的应用发送系统媒体按键：播放、暂停、播放/暂停切换、下一首、上一首或停止；不读取播放内容，也不打开任何应用。",
        inputSchemaJson = mediaControlSchemaJson,
        outputSchemaJson = mediaControlOutputSchemaJson,
        capability = ToolCapability.MediaControl,
        permissions = setOf(ToolPermission.DispatchesMediaKeyEvent),
        riskLevel = RiskLevel.MediumDraftOrNavigation,
        confirmationPolicy = ConfirmationPolicy.Required,
        pendingArgumentAllowlist = setOf("command"),
        tags = setOf(ToolCapabilityTag.LowRiskDeviceAction),
    ),
    ToolSpec(
        name = MobileActionFunctions.CALCULATE,
        title = "本地计算",
        description = "在设备本地精确计算四则运算、括号、百分号和整数次幂，或换算长度、重量、容量和温度单位；不联网、不调用模型。expression 使用 ASCII 运算符（+ - * / ^ %），单位换算写成 \"<数值> <单位> to <单位>\"，单位代码：m km cm mm mi ft in yd nmi kg g mg t lb oz jin liang l ml gal c f k。",
        inputSchemaJson = calculateSchemaJson,
        outputSchemaJson = calculateOutputSchemaJson,
        capability = ToolCapability.LocalCompute,
        riskLevel = RiskLevel.LowReadOnly,
        confirmationPolicy = ConfirmationPolicy.NotRequired,
        privateOutputKeys = setOf("expression", "result"),
        redactedResultSummary = "已完成本地计算",
        resultContinuationPolicy = ToolResultContinuationPolicy.None,
    ),
)
