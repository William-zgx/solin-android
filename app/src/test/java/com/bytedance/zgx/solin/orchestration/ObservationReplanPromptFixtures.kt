package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus

/** Shared LocalOnly accessibility observation fixtures for observation-replanner prompt tests. */
internal object ObservationReplanPromptFixtures {
    private fun element(id: String, text: String, role: String, editable: Boolean, top: Int): String =
        """
            {
              "id": "$id",
              "source": "accessibility",
              "bounds": {"left": 20, "top": $top, "right": 820, "bottom": ${top + 60}},
              "text": "$text",
              "role": "$role",
              "clickability": {"clickable": true, "editable": $editable, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
        """.trimIndent()

    private fun observation(id: String, elements: List<String>, packageName: String): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "$id",
          "capturedAtMillis": 2,
          "packageName": "$packageName",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility"],
          "elementCount": ${elements.size},
          "sourceCounts": {"accessibility": ${elements.size}},
          "truncated": false,
          "elements": [${elements.joinToString(",")}]
        }
        """.trimIndent()

    /** A typical app home screen: a search entry plus a handful of feed/tab controls. */
    fun searchEntryObservationJson(packageName: String = "com.taobao.taobao"): String =
        observation(
            "screen-home",
            listOf(
                element("search-entry", "搜索", "button", editable = false, top = 32),
                element("tab-home", "首页", "tab", editable = false, top = 1800),
                element("tab-cart", "购物车", "tab", editable = false, top = 1800),
                element("tab-me", "我的", "tab", editable = false, top = 1800),
                element("banner", "双11 活动", "image", editable = false, top = 200),
                element("feed-1", "海河牛奶 220ml", "text", editable = false, top = 600),
            ),
            packageName,
        )

    /** Post-launch observe context for "search in Taobao", the canonical in-app loop step. */
    fun searchEntryObserveContext(packageName: String = "com.taobao.taobao"): AgentObservationReplanContext {
        val previousRequest = ToolRequest(id = "observe-1", toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
        return AgentObservationReplanContext(
            run = AgentRun(
                id = "run-prompt-size",
                input = "在淘宝搜索海河牛奶",
                state = AgentRunState.Observing,
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
            previousRequest = previousRequest,
            observedResult = ToolResult(
                requestId = previousRequest.id,
                status = ToolStatus.Succeeded,
                summary = "已观察当前屏幕。",
                data = mapOf(
                    "toolName" to previousRequest.toolName,
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to true.toString(),
                    "screenObservationJson" to searchEntryObservationJson(packageName),
                ),
            ),
            priorRequests = listOf(previousRequest),
        )
    }
}
