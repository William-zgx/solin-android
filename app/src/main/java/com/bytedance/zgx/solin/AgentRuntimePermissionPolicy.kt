package com.bytedance.zgx.solin

import android.Manifest
import android.os.Build
import android.provider.Settings
import com.bytedance.zgx.solin.tool.AndroidRuntimePermissionKind
import com.bytedance.zgx.solin.tool.AndroidRuntimePermissionSpec
import com.bytedance.zgx.solin.tool.ToolCapabilityTag
import com.bytedance.zgx.solin.tool.ToolPermission
import com.bytedance.zgx.solin.tool.ToolRegistry

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
const val SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL = "accessibility_device_control"

fun PendingAgentConfirmation.runtimePermissionsFor(
    apiLevel: Int = Build.VERSION.SDK_INT,
    toolRegistry: ToolRegistry = ToolRegistry(),
): List<String> {
    return runtimePermissionRequirementsFor(apiLevel, toolRegistry)
        .flatMap { it.permissions }
        .distinct()
}

internal fun PendingAgentConfirmation.matchesExecution(other: PendingAgentConfirmation): Boolean =
    runId == other.runId &&
        toolRequest == other.toolRequest &&
        draft.functionName == other.draft.functionName &&
        draft.parameters == other.draft.parameters

internal fun PendingAgentConfirmation.requiresRuntimePermissionResult(
    resultPermissions: Set<String>,
    apiLevel: Int = Build.VERSION.SDK_INT,
    toolRegistry: ToolRegistry = ToolRegistry(),
): Boolean {
    val expectedPermissions = runtimePermissionsFor(apiLevel, toolRegistry).toSet()
    if (expectedPermissions.isEmpty()) return false
    return resultPermissions.isEmpty() || resultPermissions.any { permission -> permission in expectedPermissions }
}

internal fun PendingAgentConfirmation.requiresCurrentScreenshotOcrConsent(
    toolRegistry: ToolRegistry = ToolRegistry(),
): Boolean {
    val toolName = toolRequest?.toolName ?: draft.functionName
    return toolRegistry.specFor(toolName)?.permissions?.contains(ToolPermission.RequiresMediaProjectionConsent) == true
}

fun PendingAgentConfirmation.runtimePermissionRequirementsFor(
    apiLevel: Int = Build.VERSION.SDK_INT,
    toolRegistry: ToolRegistry = ToolRegistry(),
): List<RuntimePermissionRequirement> {
    val toolName = toolRequest?.toolName ?: draft.functionName
    return toolRegistry.androidRuntimePermissionSpecsFor(toolName)
        .flatMap { spec -> runtimePermissionRequirementsFor(spec, apiLevel) }
        .distinctBy { requirement -> requirement.permissions to requirement.rationale }
}

fun PendingAgentConfirmation.specialAccessRequirementsFor(
    toolRegistry: ToolRegistry = ToolRegistry(),
): List<SpecialAccessRequirement> {
    val toolName = toolRequest?.toolName ?: draft.functionName
    return toolRegistry.specialAccessTagsFor(toolName)
        .mapNotNull { tag ->
            when (tag) {
                ToolCapabilityTag.UsageStatsSpecialAccess -> USAGE_ACCESS_REQUIREMENT
                ToolCapabilityTag.AccessibilityScreenTextSpecialAccess -> ACCESSIBILITY_SCREEN_TEXT_REQUIREMENT
                ToolCapabilityTag.AccessibilityDeviceControlSpecialAccess ->
                    ACCESSIBILITY_DEVICE_CONTROL_REQUIREMENT
                else -> null
            }
        }
        .distinctBy { requirement -> requirement.id }
}

internal fun restoredPendingSpecialAccessRequirement(
    requirementId: String?,
    pendingConfirmation: PendingAgentConfirmation?,
    toolRegistry: ToolRegistry = ToolRegistry(),
): SpecialAccessRequirement? =
    pendingConfirmation
        ?.specialAccessRequirementsFor(toolRegistry)
        ?.firstOrNull { requirement -> requirement.id == requirementId }

fun PendingAgentConfirmation.deniedRuntimePermissionsAfterGrantResult(
    grantResults: Map<String, Boolean>,
    apiLevel: Int = Build.VERSION.SDK_INT,
    hasRuntimePermission: (String) -> Boolean,
    toolRegistry: ToolRegistry = ToolRegistry(),
): List<String> =
    runtimePermissionsFor(apiLevel, toolRegistry)
        .filterNot { permission ->
            grantResults[permission] == true ||
                hasRuntimePermission(permission) ||
                isCoveredByAlternativeVisualMediaGrant(
                    permission = permission,
                    grantResults = grantResults,
                    apiLevel = apiLevel,
                    hasRuntimePermission = hasRuntimePermission,
                ) ||
                isCoveredByPartialRecentFileAllGrant(
                    permission = permission,
                    grantResults = grantResults,
                    apiLevel = apiLevel,
                    hasRuntimePermission = hasRuntimePermission,
                    toolRegistry = toolRegistry,
                )
        }

fun runtimePermissionDenialSummary(permissions: List<String>): String =
    permissions
        .map { it.friendlyPermissionTitle() }
        .distinct()
        .joinToString()

fun specialAccessDenialSummary(requirements: List<SpecialAccessRequirement>): String =
    requirements
        .map { it.title }
        .distinct()
        .joinToString()

private fun recentFilePermissionRequirementsFor(kind: String, apiLevel: Int): List<RuntimePermissionRequirement> {
    if (apiLevel < Build.VERSION_CODES.TIRAMISU) {
        return listOf(Manifest.permission.READ_EXTERNAL_STORAGE.requirement())
    }
    return when (kind.lowercase()) {
        "screenshots" -> visualMediaPermissionRequirementsFor(
            mediaPermission = Manifest.permission.READ_MEDIA_IMAGES,
            apiLevel = apiLevel,
        )
        "images" -> visualMediaPermissionRequirementsFor(
            mediaPermission = Manifest.permission.READ_MEDIA_IMAGES,
            apiLevel = apiLevel,
        )
        "videos" -> visualMediaPermissionRequirementsFor(
            mediaPermission = Manifest.permission.READ_MEDIA_VIDEO,
            apiLevel = apiLevel,
        )
        "audio" -> listOf(Manifest.permission.READ_MEDIA_AUDIO.requirement())
        "all" ->
            if (apiLevel < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                listOf(
                    Manifest.permission.READ_MEDIA_IMAGES.requirement(),
                    Manifest.permission.READ_MEDIA_VIDEO.requirement(),
                    Manifest.permission.READ_MEDIA_AUDIO.requirement(),
                )
            } else {
                listOf(
                    Manifest.permission.READ_MEDIA_IMAGES.requirement(),
                    Manifest.permission.READ_MEDIA_VIDEO.requirement(),
                    selectedVisualMediaPermissionRequirement(),
                    Manifest.permission.READ_MEDIA_AUDIO.requirement(),
                )
            }
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
        visualMediaPermissionRequirementsFor(
            mediaPermission = Manifest.permission.READ_MEDIA_IMAGES,
            apiLevel = apiLevel,
            rationale = rationale,
        )
    }
}

private fun visualMediaPermissionRequirementsFor(
    mediaPermission: String,
    apiLevel: Int,
    rationale: String? = null,
): List<RuntimePermissionRequirement> {
    val mediaRequirement = mediaPermission.requirement(rationale = rationale)
    if (apiLevel < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return listOf(mediaRequirement)
    }
    return listOf(
        mediaRequirement,
        selectedVisualMediaPermissionRequirement(),
    )
}

private fun selectedVisualMediaPermissionRequirement(): RuntimePermissionRequirement =
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED.requirement(
        rationale = "用于在 Android 14 及以上识别用户通过系统照片权限弹窗选择的图片或视频；这不是文档/下载文件读取授权。",
    )

private fun isCoveredByAlternativeVisualMediaGrant(
    permission: String,
    grantResults: Map<String, Boolean>,
    apiLevel: Int,
    hasRuntimePermission: (String) -> Boolean,
): Boolean {
    if (apiLevel < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    val visualMediaPermissions = setOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )
    val hasSelectedVisualGrant =
        grantResults[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true ||
            hasRuntimePermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    val hasFullVisualGrant =
        visualMediaPermissions.any { mediaPermission ->
            grantResults[mediaPermission] == true || hasRuntimePermission(mediaPermission)
        }
    return when (permission) {
        in visualMediaPermissions -> hasSelectedVisualGrant
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED -> hasFullVisualGrant
        else -> false
    }
}

private fun PendingAgentConfirmation.isCoveredByPartialRecentFileAllGrant(
    permission: String,
    grantResults: Map<String, Boolean>,
    apiLevel: Int,
    hasRuntimePermission: (String) -> Boolean,
    toolRegistry: ToolRegistry,
): Boolean {
    if (apiLevel < Build.VERSION_CODES.TIRAMISU) return false
    val toolName = toolRequest?.toolName ?: draft.functionName
    val recentFilesSpec = toolRegistry.androidRuntimePermissionSpecsFor(toolName)
        .firstOrNull { spec -> spec.kind == AndroidRuntimePermissionKind.RecentFiles }
        ?: return false
    val kind = (runtimePermissionArgument(recentFilesSpec.argumentName) ?: "all").lowercase()
    if (kind != "all") return false
    val mediaPermissions = buildSet {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        add(Manifest.permission.READ_MEDIA_AUDIO)
        if (apiLevel >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
    }
    if (permission !in mediaPermissions) return false
    return mediaPermissions.any { mediaPermission ->
        grantResults[mediaPermission] == true || hasRuntimePermission(mediaPermission)
    }
}

private fun PendingAgentConfirmation.runtimePermissionRequirementsFor(
    spec: AndroidRuntimePermissionSpec,
    apiLevel: Int,
): List<RuntimePermissionRequirement> =
    when (spec.kind) {
        AndroidRuntimePermissionKind.PostNotifications ->
            if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.POST_NOTIFICATIONS.requirement(rationale = spec.rationale))
            } else {
                emptyList()
            }

        AndroidRuntimePermissionKind.ReadCalendar ->
            listOf(Manifest.permission.READ_CALENDAR.requirement(rationale = spec.rationale))

        AndroidRuntimePermissionKind.ReadContacts ->
            listOf(Manifest.permission.READ_CONTACTS.requirement(rationale = spec.rationale))

        AndroidRuntimePermissionKind.RecentFiles ->
            recentFilePermissionRequirementsFor(
                kind = runtimePermissionArgument(spec.argumentName) ?: "all",
                apiLevel = apiLevel,
            )

        AndroidRuntimePermissionKind.RecentImages ->
            recentImageOcrPermissionRequirementsFor(
                apiLevel = apiLevel,
                rationale = spec.rationale ?: "用于在你确认后读取最近图片像素，并在本地提取 OCR 文本。",
            )
    }

private fun PendingAgentConfirmation.runtimePermissionArgument(name: String?): String? {
    if (name.isNullOrBlank()) return null
    return toolRequest?.arguments?.get(name) ?: draft.parameters[name]
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
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED -> "部分照片和视频访问权限"
        Manifest.permission.READ_MEDIA_AUDIO -> "音频权限"
        else -> this
    }

private fun String.friendlyPermissionRationale(): String =
    when (this) {
        Manifest.permission.POST_NOTIFICATIONS -> "用于到点发送本地提醒通知。"
        Manifest.permission.READ_CALENDAR -> "用于只读查询忙闲时间段，不读取标题、地点或参与人。"
        Manifest.permission.READ_CONTACTS -> "用于只读查询联系人摘要。"
        Manifest.permission.READ_EXTERNAL_STORAGE -> "用于读取最近文件的最小元数据。"
        Manifest.permission.READ_MEDIA_IMAGES -> "用于读取最近图片或截图的最小元数据。"
        Manifest.permission.READ_MEDIA_VIDEO -> "用于读取最近视频的最小元数据。"
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED -> "用于读取你通过 Android 照片权限弹窗选择的图片或视频元数据。"
        Manifest.permission.READ_MEDIA_AUDIO -> "用于读取最近音频的最小元数据。"
        else -> "用于执行你确认的本地工具。"
    }

private val USAGE_ACCESS_REQUIREMENT = SpecialAccessRequirement(
    id = SPECIAL_ACCESS_USAGE_STATS,
    title = "使用情况访问权限",
    rationale = "用于通过 UsageStats 估计当前前台应用名和包名；不是窗口真值，不读取使用历史或屏幕内容，需要在系统设置中手动开启。",
    settingsAction = Settings.ACTION_USAGE_ACCESS_SETTINGS,
)

private val ACCESSIBILITY_SCREEN_TEXT_REQUIREMENT = SpecialAccessRequirement(
    id = SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT,
    title = "无障碍屏幕文本权限",
    rationale = "用于在你确认后只读获取当前屏幕暴露的可访问文本；不会点击、控制设备或读取截图像素。",
    settingsAction = Settings.ACTION_ACCESSIBILITY_SETTINGS,
)

private val ACCESSIBILITY_DEVICE_CONTROL_REQUIREMENT = SpecialAccessRequirement(
    id = SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL,
    title = "无障碍设备控制权限",
    rationale = "用于在你确认后观察当前屏幕的可访问状态并执行点击、输入、滚动、返回或等待；不读取截图像素，不保存原始节点树。",
    settingsAction = Settings.ACTION_ACCESSIBILITY_SETTINGS,
)
