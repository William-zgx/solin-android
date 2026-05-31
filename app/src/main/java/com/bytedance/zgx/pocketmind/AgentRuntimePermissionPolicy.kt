package com.bytedance.zgx.pocketmind

import android.Manifest
import android.os.Build
import android.provider.Settings
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions

data class RuntimePermissionRequirement(
    val permissions: List<String>,
    val title: String,
    val rationale: String,
    val requestable: Boolean = true,
)

data class SpecialAccessRequirement(
    val id: String,
    val title: String,
    val rationale: String,
    val settingsAction: String,
)

const val SPECIAL_ACCESS_USAGE_STATS = "usage_stats"
const val SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT = "accessibility_screen_text"

fun PendingAgentConfirmation.runtimePermissionsFor(apiLevel: Int = Build.VERSION.SDK_INT): List<String> {
    return runtimePermissionRequirementsFor(apiLevel)
        .flatMap { it.permissions }
        .distinct()
}

fun PendingAgentConfirmation.runtimePermissionRequirementsFor(
    apiLevel: Int = Build.VERSION.SDK_INT,
): List<RuntimePermissionRequirement> {
    val toolName = toolRequest?.toolName ?: draft.functionName
    return when (toolName) {
        MobileActionFunctions.SCHEDULE_REMINDER ->
            if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.POST_NOTIFICATIONS.requirement())
            } else {
                emptyList()
            }

        MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY ->
            listOf(Manifest.permission.READ_CALENDAR.requirement())

        MobileActionFunctions.QUERY_CONTACTS ->
            listOf(Manifest.permission.READ_CONTACTS.requirement())

        MobileActionFunctions.QUERY_RECENT_FILES ->
            recentFilePermissionRequirementsFor(
                kind = toolRequest?.arguments?.get("kind")
                    ?: draft.parameters["kind"]
                    ?: "all",
                apiLevel = apiLevel,
            )

        MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR ->
            recentImageOcrPermissionRequirementsFor(
                apiLevel = apiLevel,
                rationale = "用于在你确认后读取最近 1 张截图像素，并在本地提取 OCR 文本。",
            )

        MobileActionFunctions.READ_RECENT_IMAGE_OCR ->
            recentImageOcrPermissionRequirementsFor(
                apiLevel = apiLevel,
                rationale = "用于在你确认后最多扫描最近 3 张图片像素，并在本地提取第一条 OCR 文本。",
            )

        else -> emptyList()
    }
}

fun PendingAgentConfirmation.specialAccessRequirementsFor(): List<SpecialAccessRequirement> {
    val toolName = toolRequest?.toolName ?: draft.functionName
    return when (toolName) {
        MobileActionFunctions.QUERY_FOREGROUND_APP -> listOf(USAGE_ACCESS_REQUIREMENT)
        MobileActionFunctions.READ_CURRENT_SCREEN_TEXT -> listOf(ACCESSIBILITY_SCREEN_TEXT_REQUIREMENT)
        else -> emptyList()
    }
}

fun PendingAgentConfirmation.deniedRuntimePermissionsAfterGrantResult(
    grantResults: Map<String, Boolean>,
    apiLevel: Int = Build.VERSION.SDK_INT,
    hasRuntimePermission: (String) -> Boolean,
): List<String> =
    runtimePermissionsFor(apiLevel)
        .filterNot { permission ->
            grantResults[permission] == true || hasRuntimePermission(permission)
        }

fun runtimePermissionDenialSummary(permissions: List<String>): String =
    permissions
        .map { it.friendlyPermissionTitle() }
        .distinct()
        .joinToString()

private fun recentFilePermissionRequirementsFor(kind: String, apiLevel: Int): List<RuntimePermissionRequirement> {
    if (apiLevel < Build.VERSION_CODES.TIRAMISU) {
        return listOf(Manifest.permission.READ_EXTERNAL_STORAGE.requirement())
    }
    return when (kind.lowercase()) {
        "screenshots" -> listOf(Manifest.permission.READ_MEDIA_IMAGES.requirement())
        "images" -> listOf(Manifest.permission.READ_MEDIA_IMAGES.requirement())
        "videos" -> listOf(Manifest.permission.READ_MEDIA_VIDEO.requirement())
        "audio" -> listOf(Manifest.permission.READ_MEDIA_AUDIO.requirement())
        "all" -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES.requirement(),
            Manifest.permission.READ_MEDIA_VIDEO.requirement(),
            Manifest.permission.READ_MEDIA_AUDIO.requirement(),
        )
        else -> emptyList()
    }
}

private fun recentImageOcrPermissionRequirementsFor(
    apiLevel: Int,
    rationale: String,
): List<RuntimePermissionRequirement> {
    return if (apiLevel < Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE.requirement(rationale = rationale))
    } else {
        listOf(Manifest.permission.READ_MEDIA_IMAGES.requirement(rationale = rationale))
    }
}

private fun String.requirement(rationale: String? = null): RuntimePermissionRequirement =
    RuntimePermissionRequirement(
        permissions = listOf(this),
        title = friendlyPermissionTitle(),
        rationale = rationale ?: friendlyPermissionRationale(),
    )

private fun String.friendlyPermissionTitle(): String =
    when (this) {
        Manifest.permission.POST_NOTIFICATIONS -> "通知权限"
        Manifest.permission.READ_CALENDAR -> "日历权限"
        Manifest.permission.READ_CONTACTS -> "联系人权限"
        Manifest.permission.READ_EXTERNAL_STORAGE -> "文件读取权限"
        Manifest.permission.READ_MEDIA_IMAGES -> "照片和图片权限"
        Manifest.permission.READ_MEDIA_VIDEO -> "视频权限"
        Manifest.permission.READ_MEDIA_AUDIO -> "音频权限"
        else -> this
    }

private fun String.friendlyPermissionRationale(): String =
    when (this) {
        Manifest.permission.POST_NOTIFICATIONS -> "用于到点发送本地提醒通知。"
        Manifest.permission.READ_CALENDAR -> "用于只读查询忙闲时间段。"
        Manifest.permission.READ_CONTACTS -> "用于只读查询联系人摘要。"
        Manifest.permission.READ_EXTERNAL_STORAGE -> "用于读取最近文件的最小元数据。"
        Manifest.permission.READ_MEDIA_IMAGES -> "用于读取最近图片或截图的最小元数据。"
        Manifest.permission.READ_MEDIA_VIDEO -> "用于读取最近视频的最小元数据。"
        Manifest.permission.READ_MEDIA_AUDIO -> "用于读取最近音频的最小元数据。"
        else -> "用于执行你确认的本地工具。"
    }

private val USAGE_ACCESS_REQUIREMENT = SpecialAccessRequirement(
    id = SPECIAL_ACCESS_USAGE_STATS,
    title = "使用情况访问权限",
    rationale = "用于只读识别当前前台应用；需要在系统设置中手动开启。",
    settingsAction = Settings.ACTION_USAGE_ACCESS_SETTINGS,
)

private val ACCESSIBILITY_SCREEN_TEXT_REQUIREMENT = SpecialAccessRequirement(
    id = SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT,
    title = "无障碍屏幕文本权限",
    rationale = "用于在你确认后只读获取当前屏幕暴露的可访问文本；不会点击、控制设备或读取截图像素。",
    settingsAction = Settings.ACTION_ACCESSIBILITY_SETTINGS,
)
