package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.device.ContactSummaryProvider
import com.bytedance.zgx.solin.device.ContactSummaryReadResult
import com.bytedance.zgx.solin.device.CurrentScreenTextProvider
import com.bytedance.zgx.solin.device.CurrentScreenTextReadResult
import com.bytedance.zgx.solin.device.ForegroundAppProvider
import com.bytedance.zgx.solin.device.ForegroundAppReadResult
import com.bytedance.zgx.solin.device.NotificationSummaryProvider
import com.bytedance.zgx.solin.device.NotificationSummaryReadResult
import com.bytedance.zgx.solin.device.RecentFileProvider
import com.bytedance.zgx.solin.device.RecentFileReadResult
import com.bytedance.zgx.solin.device.RecentImageTextProvider
import com.bytedance.zgx.solin.device.RecentImageTextReadResult
import org.json.JSONObject

internal fun ToolRegistry.builtInSpec(toolName: String): ToolSpec =
    requireNotNull(specFor(toolName)) { "Missing built-in tool spec: $toolName" }

internal fun toolRequest(
    toolName: String,
    id: String = toolName,
    arguments: Map<String, String> = emptyMap(),
    reason: String = "test",
): ToolRequest =
    ToolRequest(
        id = id,
        toolName = toolName,
        arguments = arguments,
        reason = reason,
    )

internal fun foregroundAppProvider(result: ForegroundAppReadResult): ForegroundAppProvider =
    ForegroundAppProvider { result }

internal fun notificationSummaryProvider(result: NotificationSummaryReadResult): NotificationSummaryProvider =
    object : NotificationSummaryProvider {
        override fun recentNotifications(maxCount: Int): NotificationSummaryReadResult = result
    }

internal fun contactSummaryProvider(result: ContactSummaryReadResult): ContactSummaryProvider =
    object : ContactSummaryProvider {
        override fun queryContacts(query: String, maxCount: Int): ContactSummaryReadResult = result
    }

internal fun recentFileProvider(result: RecentFileReadResult): RecentFileProvider =
    object : RecentFileProvider {
        override fun recentFiles(kind: String, maxCount: Int): RecentFileReadResult = result
    }

internal fun recentImageTextProvider(result: RecentImageTextReadResult): RecentImageTextProvider =
    object : RecentImageTextProvider {
        override fun extractRecentImageText(kind: String, maxCount: Int): RecentImageTextReadResult = result
    }

internal fun currentScreenTextProvider(result: CurrentScreenTextReadResult): CurrentScreenTextProvider =
    object : CurrentScreenTextProvider {
        override fun currentScreenText(maxChars: Int): CurrentScreenTextReadResult = result
    }

internal fun JSONObject.keysSet(): Set<String> {
    val result = linkedSetOf<String>()
    val iterator = keys()
    while (iterator.hasNext()) {
        result += iterator.next()
    }
    return result
}
