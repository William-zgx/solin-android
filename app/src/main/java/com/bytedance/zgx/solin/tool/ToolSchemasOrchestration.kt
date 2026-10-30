package com.bytedance.zgx.solin.tool

/**
 * JSON Schemas for the agent's own control-flow and scheduling tools: reminders, alarms,
 * timers, periodic checks, ask-user, note, finish, and human take-over.
 *
 * See ToolSchemasUi.kt for why these are `internal`.
 * ToolSchemaContractTest guards the schema contract.
 */

internal val emptyObjectSchemaJson = """
    {
      "type": "object",
      "properties": {},
      "additionalProperties": false
    }
""".trimIndent()

internal val reminderSchemaJson = """
    {
      "type": "object",
      "required": ["title"],
      "properties": {
        "title": {
          "type": "string",
          "minLength": 1
        },
        "body": {
          "type": "string"
        },
        "delayMinutes": {
          "type": "integer",
          "minimum": 1
        },
        "triggerAtMillis": {
          "type": "integer",
          "minimum": 0
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val systemAlarmSchemaJson = """
    {
      "type": "object",
      "required": ["hour", "minutes"],
      "properties": {
        "hour": {
          "type": "integer",
          "minimum": 0,
          "maximum": 23
        },
        "minutes": {
          "type": "integer",
          "minimum": 0,
          "maximum": 59
        },
        "message": {
          "type": "string",
          "maxLength": 120
        },
        "recurrence": {
          "type": "string",
          "enum": ["once", "daily"]
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val systemTimerSchemaJson = """
    {
      "type": "object",
      "required": ["lengthSeconds"],
      "properties": {
        "lengthSeconds": {
          "type": "integer",
          "minimum": 1,
          "maximum": 86400
        },
        "message": {
          "type": "string",
          "maxLength": 120
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val periodicCheckSchemaJson = """
    {
      "type": "object",
      "required": ["enabled"],
      "properties": {
        "enabled": {
          "type": "boolean",
          "description": "true to enable the local reminder periodic check, false to disable it."
        },
        "intervalMinutes": {
          "type": "integer",
          "minimum": 60,
          "maximum": 1440
        },
        "minNotificationSpacingMinutes": {
          "type": "integer",
          "minimum": 60,
          "maximum": 1440
        },
        "overdueGraceMinutes": {
          "type": "integer",
          "minimum": 5,
          "maximum": 10080
        },
        "requiresBatteryNotLow": {
          "type": "boolean"
        },
        "requiresCharging": {
          "type": "boolean"
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val cancelReminderSchemaJson = """
    {
      "type": "object",
      "required": ["taskId"],
      "properties": {
        "taskId": {
          "type": "string",
          "minLength": 1,
          "pattern": "^task-[A-Za-z0-9_-]+$"
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val askUserInputSchemaJson = """
    {
      "type": "object",
      "required": ["prompt"],
      "properties": {
        "prompt": {
          "type": "string",
          "description": "The clarification question to present to the user.",
          "minLength": 1,
          "maxLength": 1000
        },
        "choices": {
          "type": "array",
          "description": "Optional list of short choice labels the user can tap to answer; omit for free-text reply.",
          "items": {
            "type": "string",
            "minLength": 1,
            "maxLength": 80
          },
          "maxItems": 8
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val askUserOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "answer", "privacy", "requiresLocalModel"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "questionId": {"type": "string", "minLength": 1},
        "answer": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean", "const": true}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val noteInputSchemaJson = """
    {
      "type": "object",
      "required": ["content"],
      "properties": {
        "content": {
          "type": "string",
          "description": "要记录到本次运行暂存笔记中的内容。建议简洁：页面标题、搜索结果摘要、验证证据等。后续步骤可引用这些笔记。",
          "minLength": 1,
          "maxLength": 2000
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val noteOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "content", "privacy", "requiresLocalModel"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "content": {"type": "string", "minLength": 1},
        "noteIndex": {"type": "integer", "minimum": 1},
        "totalNotes": {"type": "integer", "minimum": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean", "const": true}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val finishInputSchemaJson = """
    {
      "type": "object",
      "required": ["message"],
      "properties": {
        "message": {
          "type": "string",
          "description": "完成总结，向用户说明本次操作的结果。",
          "minLength": 1,
          "maxLength": 2000
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val finishOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "shouldFinish", "finishMessage"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "shouldFinish": {"type": "string", "enum": ["true"]},
        "finishMessage": {"type": "string", "minLength": 1}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val takeOverInputSchemaJson = """
    {
      "type": "object",
      "required": ["reason"],
      "properties": {
        "reason": {
          "type": "string",
          "description": "为什么需要人工接管的原因，例如：'需要登录'、'验证码'、'密码输入'、'身份验证'。",
          "minLength": 1,
          "maxLength": 200
        },
        "prompt": {
          "type": "string",
          "description": "向用户展示的提示文本，指导用户完成需要人工操作的步骤。",
          "minLength": 1,
          "maxLength": 1000
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val takeOverOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "shouldTakeOver", "takeOverReason", "privacy", "requiresLocalModel"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "shouldTakeOver": {"type": "string", "enum": ["true"]},
        "takeOverReason": {"type": "string", "minLength": 1},
        "takeOverPrompt": {"type": "string"},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean", "const": true}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val reminderOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "taskId", "taskStatus", "triggerAtMillis", "recoveryToolName", "recoveryTaskId"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "taskId": {"type": "string", "minLength": 1, "pattern": "^task-[A-Za-z0-9_-]+$"},
        "taskStatus": {"type": "string", "enum": ["Scheduled"]},
        "triggerAtMillis": {"type": "integer", "minimum": 0},
        "recoveryToolName": {"type": "string", "enum": ["cancel_reminder"]},
        "recoveryTaskId": {"type": "string", "minLength": 1, "pattern": "^task-[A-Za-z0-9_-]+$"}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val periodicCheckOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "enabled",
        "taskStatus",
        "intervalMinutes",
        "minNotificationSpacingMinutes",
        "overdueGraceMinutes",
        "requiresBatteryNotLow",
        "requiresCharging"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "enabled": {"type": "boolean"},
        "taskStatus": {"type": "string", "enum": ["Scheduled", "Cancelled", "Failed"]},
        "intervalMinutes": {"type": "integer", "minimum": 60, "maximum": 1440},
        "minNotificationSpacingMinutes": {"type": "integer", "minimum": 60, "maximum": 1440},
        "overdueGraceMinutes": {"type": "integer", "minimum": 5, "maximum": 10080},
        "requiresBatteryNotLow": {"type": "boolean"},
        "requiresCharging": {"type": "boolean"},
        "nextAllowedRunAtMillis": {"type": "integer", "minimum": 0},
        "updatedAtMillis": {"type": "integer", "minimum": 0},
        "recoveryToolName": {"type": "string", "enum": ["configure_periodic_check"]},
        "recoveryEnabled": {"type": "boolean"}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val cancelReminderOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "taskId", "taskStatus"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "taskId": {"type": "string", "minLength": 1, "pattern": "^task-[A-Za-z0-9_-]+$"},
        "taskStatus": {"type": "string", "enum": ["Cancelled"]}
      },
      "additionalProperties": false
    }
""".trimIndent()
