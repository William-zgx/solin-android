package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.action.AppDeepTargets
import com.bytedance.zgx.solin.action.SystemSettingsTargets
import com.bytedance.zgx.solin.device.CURRENT_SCREEN_TEXT_LOCAL_ONLY_POLICY
import com.bytedance.zgx.solin.device.DEVICE_CONTROL_METADATA_POLICY
import com.bytedance.zgx.solin.device.DEVICE_CONTROL_SOURCE_ACCESSIBILITY

/**
 * JSON Schemas for tools that hand off to something outside Solin: web search, system
 * settings, drafts (email / calendar / contact), share, deep links, and app launches.
 *
 * See ToolSchemasUi.kt for why these are `internal`.
 * ToolSchemaContractTest guards the schema contract.
 */

internal val systemSettingsSchemaJson = """
    {
      "type": "object",
      "required": ["target"],
      "properties": {
        "target": {
          "type": "string",
          "enum": [
            "${SystemSettingsTargets.GENERAL}",
            "${SystemSettingsTargets.BLUETOOTH}",
            "${SystemSettingsTargets.LOCATION}",
            "${SystemSettingsTargets.NOTIFICATION}",
            "${SystemSettingsTargets.DISPLAY}",
            "${SystemSettingsTargets.SOUND}",
            "${SystemSettingsTargets.BATTERY_SAVER}",
            "${SystemSettingsTargets.NETWORK}",
            "${SystemSettingsTargets.AIRPLANE_MODE}",
            "${SystemSettingsTargets.INPUT_METHOD}",
            "${SystemSettingsTargets.ACCESSIBILITY}"
          ]
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

// Aliases, not copies: the authoritative strings live next to the device-control models that
// produce them (device/ScreenControlModels.kt), and ToolSchemasUi.kt interpolates them into the
// declared output schemas. Re-declaring the literals here — which is what file-private constants
// forced — would let the schema a tool advertises drift away from the metadata it actually emits.
internal const val CURRENT_SCREEN_TEXT_SOURCE = DEVICE_CONTROL_SOURCE_ACCESSIBILITY
internal const val CURRENT_SCREEN_TEXT_METADATA_POLICY = CURRENT_SCREEN_TEXT_LOCAL_ONLY_POLICY
internal const val DEVICE_CONTROL_SOURCE = DEVICE_CONTROL_SOURCE_ACCESSIBILITY
internal const val DEVICE_CONTROL_METADATA_POLICY_SCHEMA_VALUE = DEVICE_CONTROL_METADATA_POLICY

internal val webSearchInputSchemaJson = """
    {
      "type": "object",
      "required": ["query"],
      "properties": {
        "query": {
          "type": "string",
          "description": "模型理解后的搜索关键词，不要直接复制用户原文；保留实体、主题、限定词和必要时效词，去掉“请帮我/是什么/有哪些”等寒暄和疑问词；比较或多主体问题优先拆成多次独立 web_search。",
          "minLength": 1
        },
        "searchMode": {
          "type": "string",
          "enum": ["general", "weather_current"]
        },
        "freshness": {
          "type": "string",
          "description": "搜索时效。查询含最新/目前/当前/现在/今日/热门/排行/latest/current/recent/trending/hottest 或当前年份等当前性语义时应使用 current；缺省时宿主也会按 query 推断。",
          "enum": ["any_time", "current"]
        },
        "maxResults": {
          "type": "integer",
          "minimum": 1,
          "maximum": 5
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val webSearchOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "privacy", "requiresLocalModel", "query", "source", "summaryText", "resultsJson"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["RemoteEligible"]},
        "requiresLocalModel": {"type": "boolean"},
        "query": {"type": "string", "minLength": 1},
        "source": {
          "type": "string",
          "enum": ["open_meteo", "duckduckgo", "duckduckgo_html", "duckduckgo_lite"]
        },
        "searchMode": {
          "type": "string",
          "enum": ["general", "weather_current"]
        },
        "retrievedAt": {
          "type": "string",
          "minLength": 1
        },
        "freshness": {
          "type": "string",
          "enum": ["any_time", "current"]
        },
        "maxResults": {
          "type": "integer",
          "minimum": 1,
          "maximum": 5
        },
        "summaryText": {
          "type": "string",
          "minLength": 1,
          "maxLength": 1203
        },
        "resultsJson": {
          "type": "string",
          "contentMediaType": "application/json",
          "minLength": 1,
          "maxLength": 4003
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val emailDraftSchemaJson = """
    {
      "type": "object",
      "required": ["body"],
      "properties": {
        "subject": {
          "type": "string"
        },
        "body": {
          "type": "string",
          "minLength": 1
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val calendarDraftSchemaJson = """
    {
      "type": "object",
      "required": ["title"],
      "properties": {
        "title": {
          "type": "string",
          "minLength": 1
        },
        "description": {
          "type": "string"
        },
        "beginTimeMillis": {
          "type": "integer",
          "minimum": 0
        },
        "endTimeMillis": {
          "type": "integer",
          "minimum": 0
        },
        "allDay": {
          "type": "boolean"
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val contactDraftSchemaJson = """
    {
      "type": "object",
      "required": ["name"],
      "properties": {
        "name": {
          "type": "string",
          "minLength": 1
        },
        "email": {
          "type": "string"
        },
        "phone": {
          "type": "string"
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val shareTextSchemaJson = """
    {
      "type": "object",
      "required": ["text"],
      "properties": {
        "text": {
          "type": "string",
          "minLength": 1,
          "maxLength": $MAX_SHARE_TEXT_CHARS
        },
        "title": {
          "type": "string",
          "maxLength": $MAX_SHARE_TITLE_CHARS
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val openDeepLinkSchemaJson = """
    {
      "type": "object",
      "required": ["uri"],
      "properties": {
        "uri": {
          "type": "string",
          "minLength": 1,
          "maxLength": 2048,
          "pattern": "^https://[^\\s/@]+(?:[:/].*)?$"
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val openAppIntentSchemaJson = """
    {
      "type": "object",
      "required": ["packageName"],
      "properties": {
        "packageName": {
          "type": "string",
          "minLength": 3,
          "maxLength": 255,
          "pattern": "^[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+$"
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val openAppByNameSchemaJson = """
    {
      "type": "object",
      "required": ["appName"],
      "properties": {
        "appName": {
          "type": "string",
          "minLength": 1,
          "maxLength": 80,
          "description": "用户可见的应用名，例如淘宝、拼多多、Chrome 或系统桌面显示的 App label；不能是 URI、Intent action、Activity 名或任意 extras。"
        },
        "followUpIntent": {
          "type": "string",
          "minLength": 1,
          "maxLength": 200,
          "description": "可选。打开该应用后要在应用内完成的本地意图文本（如“搜索跑鞋”），仅用于在本机引导本地动作规划模型；不是 URI、Intent action、Activity 名或 extras。"
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val openAppDeepTargetSchemaJson = """
    {
      "type": "object",
      "required": ["targetId", "packageName"],
      "properties": {
        "targetId": {
          "type": "string",
          "enum": ["${AppDeepTargets.APP_DETAILS_SETTINGS_ID}"]
        },
        "packageName": {
          "type": "string",
          "minLength": 3,
          "maxLength": 255,
          "pattern": "^[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+$"
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val externalActivityOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "completionState",
        "completionVerified",
        "externalOutcome",
        "externalOutcomeSource",
        "targetKind",
        "intentAction",
        "metadataPolicy",
        "rawPayloadIncluded"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "completionState": {"type": "string", "enum": ["ExternalActivityOpened"]},
        "completionVerified": {"type": "boolean"},
        "externalOutcome": {"type": "string", "enum": ["Unknown", "Completed", "NotCompleted", "OpenedOnly"]},
        "externalOutcomeSource": {"type": "string", "enum": ["Unknown", "UserConfirmed"]},
        "targetKind": {"type": "string", "minLength": 1},
        "intentAction": {"type": "string", "minLength": 1},
        "metadataPolicy": {"type": "string", "minLength": 1},
        "rawPayloadIncluded": {"type": "boolean"},
        "settingsAction": {"type": "string"},
        "specialAccess": {"type": "string"},
        "targetId": {"type": "string"},
        "targetPackage": {"type": "string"},
        "targetUriScheme": {"type": "string"},
        "targetUriHost": {"type": "string"},
        "targetUriPort": {"type": "integer"}
      },
      "additionalProperties": false
    }
""".trimIndent()
