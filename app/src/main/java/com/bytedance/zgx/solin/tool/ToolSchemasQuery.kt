package com.bytedance.zgx.solin.tool

/**
 * JSON Schemas for the read-only local query tools (calendar availability, contacts,
 * notifications, recent files, recent-image OCR, clipboard, background tasks, foreground app).
 *
 * See ToolSchemasUi.kt for why these are `internal`.
 * ToolSchemaContractTest guards the schema contract.
 */

internal val querySchemaJson = """
    {
      "type": "object",
      "required": ["query"],
      "properties": {
        "query": {
          "type": "string",
          "description": "搜索关键词，不要直接复制用户原文；保留实体、主题、限定词，去掉“请帮我/是什么/有哪些”等寒暄和疑问词。",
          "minLength": 1
        },
        "searchMode": {
          "type": "string",
          "enum": ["general", "local"]
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val contactQuerySchemaJson = """
    {
      "type": "object",
      "required": ["query"],
      "properties": {
        "query": {
          "type": "string",
          "minLength": 1
        },
        "maxCount": {
          "type": "integer",
          "minimum": 1,
          "maximum": 20
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val backgroundTasksQuerySchemaJson = """
    {
      "type": "object",
      "properties": {
        "scope": {
          "type": "string",
          "description": "查询范围：active=已安排/运行中的后台任务，history=最近完成/取消/失败历史，policy=周期检查策略，all=同时返回任务摘要与周期检查策略。默认 active。",
          "enum": ["active", "history", "policy", "all"]
        },
        "maxCount": {
          "type": "integer",
          "minimum": 1,
          "maximum": 50
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val recentNotificationSchemaJson = """
    {
      "type": "object",
      "properties": {
        "maxCount": {
          "type": "integer",
          "minimum": 1,
          "maximum": 20
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val recentFilesSchemaJson = """
    {
      "type": "object",
      "properties": {
        "kind": {
          "type": "string",
          "description": "文件类别。该工具只直接查询已授权媒体；Android 13 及以上不提供 documents/downloads/others 的可执行直接读取路径，非媒体文件应由用户通过系统文件选择器或分享入口主动提供。",
          "enum": ["all", "screenshots", "images", "videos", "audio"]
        },
        "maxCount": {
          "type": "integer",
          "minimum": 1,
          "maximum": 50
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val recentScreenshotOcrSchemaJson = """
    {
      "type": "object",
      "properties": {
        "maxCount": {
          "type": "integer",
          "minimum": 1,
          "maximum": 1
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val recentImageOcrSchemaJson = """
    {
      "type": "object",
      "properties": {
        "maxCount": {
          "type": "integer",
          "minimum": 1,
          "maximum": 3
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val calendarAvailabilitySchemaJson = """
    {
      "type": "object",
      "required": ["start", "end"],
      "properties": {
        "start": {
          "type": "string",
          "format": "date-time",
          "description": "Inclusive ISO-8601 start time with timezone."
        },
        "end": {
          "type": "string",
          "format": "date-time",
          "description": "Exclusive ISO-8601 end time with timezone. Window must be at most 31 days."
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val calendarAvailabilityOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "start",
        "end",
        "busyBlockCount",
        "freeBlockCount",
        "blocksJson"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "start": {"type": "string", "minLength": 1},
        "end": {"type": "string", "minLength": 1},
        "busyBlockCount": {"type": "integer", "minimum": 0},
        "freeBlockCount": {"type": "integer", "minimum": 0},
        "blocksJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val foregroundAppOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "source",
        "confidence",
        "packageName",
        "appLabel",
        "lastTimeUsedMillis"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "source": {
          "type": "string",
          "description": "How the current app estimate was derived.",
          "enum": ["usage_stats_estimate"]
        },
        "confidence": {
          "type": "string",
          "description": "UsageStats can only approximate the current foreground app.",
          "enum": ["estimate"]
        },
        "packageName": {"type": "string", "minLength": 1},
        "appLabel": {"type": "string", "minLength": 1},
        "lastTimeUsedMillis": {"type": "integer"}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val contactsOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "privacy", "requiresLocalModel", "query", "maxCount", "contactCount", "contactsJson"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "query": {"type": "string"},
        "maxCount": {"type": "integer", "minimum": 1, "maximum": 20},
        "contactCount": {"type": "integer", "minimum": 0},
        "contactsJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val notificationsOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "privacy", "requiresLocalModel", "maxCount", "notificationCount", "notificationsJson"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "maxCount": {"type": "integer", "minimum": 1, "maximum": 20},
        "notificationCount": {"type": "integer", "minimum": 0},
        "notificationsJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val recentFilesOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "kind",
        "maxCount",
        "mediaAccessScope",
        "fileCount",
        "filesJson"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "kind": {"type": "string", "minLength": 1},
        "maxCount": {"type": "integer", "minimum": 1, "maximum": 50},
        "mediaAccessScope": {
          "type": "string",
          "description": "Whether MediaStore was queried through legacy storage, full visual media, user-selected visual media, or currently granted media-only access.",
          "enum": ["legacy_storage", "full_visual_media", "user_selected_visual_media", "granted_media_only"]
        },
        "fileCount": {"type": "integer", "minimum": 0},
        "filesJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val recentScreenshotOcrOutputSchemaJson =
    recentOcrOutputSchemaJson(maxCountMaximum = 1)

internal val recentImageOcrOutputSchemaJson =
    recentOcrOutputSchemaJson(maxCountMaximum = 3)

internal fun recentOcrOutputSchemaJson(maxCountMaximum: Int): String = """
    {
      "type": "object",
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "source",
        "maxCount",
        "scannedCount",
        "mediaAccessScope",
        "ocrTextIncluded",
        "rawPayloadIncluded",
        "metadataPolicy"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "source": {"type": "string", "minLength": 1},
        "maxCount": {"type": "integer", "minimum": 1, "maximum": $maxCountMaximum},
        "scannedCount": {"type": "integer", "minimum": 0},
        "mediaAccessScope": {
          "type": "string",
          "description": "Whether OCR image candidates came from legacy storage, full visual media, user-selected visual media, or currently granted media-only access.",
          "enum": ["legacy_storage", "full_visual_media", "user_selected_visual_media", "granted_media_only"]
        },
        "ocrText": {"type": "string", "minLength": 1},
        "truncated": {"type": "boolean"},
        "ocrTextIncluded": {"type": "boolean"},
        "rawPayloadIncluded": {"type": "boolean"},
        "metadataPolicy": {"type": "string", "minLength": 1}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val clipboardOutputSchemaJson = """
    {
      "type": "object",
      "required": ["toolName", "privacy", "requiresLocalModel", "text", "truncated"],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "text": {"type": "string", "minLength": 1},
        "truncated": {"type": "boolean"}
      },
      "additionalProperties": false
    }
""".trimIndent()

internal val backgroundTasksOutputSchemaJson = """
    {
      "type": "object",
      "required": [
        "toolName",
        "privacy",
        "requiresLocalModel",
        "scope",
        "source",
        "metadataPolicy",
        "rawPayloadIncluded"
      ],
      "properties": {
        "toolName": {"type": "string", "minLength": 1},
        "privacy": {"type": "string", "enum": ["LocalOnly"]},
        "requiresLocalModel": {"type": "boolean"},
        "scope": {"type": "string", "enum": ["active", "history", "policy", "all"]},
        "source": {"type": "string", "enum": ["local_store"]},
        "maxCount": {"type": "integer", "minimum": 1, "maximum": 50},
        "activeTaskCount": {"type": "integer", "minimum": 0},
        "historyTaskCount": {"type": "integer", "minimum": 0},
        "tasksJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"},
        "policyJson": {"type": "string", "minLength": 1, "contentMediaType": "application/json"},
        "metadataPolicy": {"type": "string", "enum": ["background_tasks_local_only_no_reminder_body"]},
        "rawPayloadIncluded": {"type": "boolean"}
      },
      "additionalProperties": false
    }
""".trimIndent()
