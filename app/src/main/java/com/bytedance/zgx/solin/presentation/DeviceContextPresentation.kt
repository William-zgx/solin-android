package com.bytedance.zgx.solin.presentation

import android.Manifest
import android.os.Build
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT
import com.bytedance.zgx.solin.SPECIAL_ACCESS_USAGE_STATS
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.DeviceContextAuthorizationSnapshot
import com.bytedance.zgx.solin.device.DeviceContextSnapshot
import com.bytedance.zgx.solin.device.DeviceContextToolReadiness
import com.bytedance.zgx.solin.device.DeviceContextToolReadinessState
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrContract

/**
 * Device-context snapshot / tool-readiness helpers extracted from SolinViewModel (Wave 6 Track C6b).
 */

internal fun ChatUiState.toDeviceContextSnapshot(
    authorization: DeviceContextAuthorizationSnapshot,
): DeviceContextSnapshot =
    DeviceContextSnapshot(
        isArm64Supported = isArm64Supported,
        inferenceMode = inferenceMode.name,
        installedCapabilities = installedCapabilities,
        memoryEnabled = memoryEnabled,
        availableStorageBytes = availableModelStorageBytes,
        activeSessionId = activeSessionId,
        hasPendingConfirmation = pendingConfirmation != null,
        toolReadiness = deviceContextToolReadiness(authorization),
    )

internal fun deviceContextToolReadiness(
    authorization: DeviceContextAuthorizationSnapshot,
): List<DeviceContextToolReadiness> =
    listOf(
        DeviceContextToolReadiness(
            toolName = MobileActionFunctions.READ_CLIPBOARD,
            state = DeviceContextToolReadinessState.Available,
            reason = "requires explicit tool confirmation before reading clipboard text",
        ),
        authorization.runtimePermissionReadiness(
            toolName = MobileActionFunctions.QUERY_CONTACTS,
            permissions = listOf(Manifest.permission.READ_CONTACTS),
            availableReason = "contacts can be read after explicit tool confirmation",
            missingReason = "contacts are read only after confirmation and Android permission grant",
        ),
        authorization.runtimePermissionReadiness(
            toolName = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
            permissions = listOf(Manifest.permission.READ_CALENDAR),
            availableReason = "calendar availability can be read after explicit tool confirmation",
            missingReason = "calendar availability is read only after confirmation and Android permission grant",
        ),
        authorization.specialAccessReadiness(
            toolName = MobileActionFunctions.QUERY_FOREGROUND_APP,
            specialAccessId = SPECIAL_ACCESS_USAGE_STATS,
            availableReason = "foreground app metadata can be estimated after explicit tool confirmation",
            missingReason = "foreground app metadata requires Usage Access special access",
        ),
        DeviceContextToolReadiness(
            toolName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
            state = DeviceContextToolReadinessState.Available,
            reason = "returns bounded current-app notification summaries after confirmation",
        ),
        authorization.recentFilesReadiness(),
        DeviceContextToolReadiness(
            toolName = MobileActionFunctions.QUERY_BACKGROUND_TASKS,
            state = DeviceContextToolReadinessState.Available,
            reason = "reads local scheduled task metadata only after confirmation",
        ),
        authorization.visualMediaReadiness(
            toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            availableReason = "reads one recent screenshot for local OCR only after confirmation",
            missingReason = "reads one recent screenshot for local OCR only after confirmation and media permission",
        ),
        authorization.visualMediaReadiness(
            toolName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            availableReason = "scans recent images for local OCR only after confirmation",
            missingReason = "scans recent images for local OCR only after confirmation and media permission",
        ),
        authorization.specialAccessReadiness(
            toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            specialAccessId = SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT,
            availableReason = "current screen Accessibility text can be read after explicit tool confirmation",
            missingReason = "current screen text uses Accessibility text nodes, not screenshots or OCR",
        ),
        DeviceContextToolReadiness(
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            state = DeviceContextToolReadinessState.RequiresForegroundConsent,
            reason = "current screenshot OCR requires one-shot foreground MediaProjection consent after tool confirmation",
            specialAccessId = CurrentScreenshotOcrContract.CONSENT_REASON,
        ),
    )

private fun DeviceContextAuthorizationSnapshot.runtimePermissionReadiness(
    toolName: String,
    permissions: List<String>,
    availableReason: String,
    missingReason: String,
): DeviceContextToolReadiness {
    val missingPermissions = permissions.filterNot(::hasRuntimePermission)
    return DeviceContextToolReadiness(
        toolName = toolName,
        state = if (missingPermissions.isEmpty()) {
            DeviceContextToolReadinessState.Available
        } else {
            DeviceContextToolReadinessState.RequiresRuntimePermission
        },
        reason = if (missingPermissions.isEmpty()) availableReason else missingReason,
        runtimePermissions = missingPermissions.map { it.androidPermissionName() },
    )
}

private fun DeviceContextAuthorizationSnapshot.specialAccessReadiness(
    toolName: String,
    specialAccessId: String,
    availableReason: String,
    missingReason: String,
): DeviceContextToolReadiness {
    val available = hasSpecialAccess(specialAccessId)
    return DeviceContextToolReadiness(
        toolName = toolName,
        state = if (available) {
            DeviceContextToolReadinessState.Available
        } else {
            DeviceContextToolReadinessState.RequiresSpecialAccess
        },
        reason = if (available) availableReason else missingReason,
        specialAccessId = if (available) null else specialAccessId,
    )
}

private fun DeviceContextAuthorizationSnapshot.recentFilesReadiness(): DeviceContextToolReadiness {
    val available = hasAnyRecentFileMediaAccess()
    return DeviceContextToolReadiness(
        toolName = MobileActionFunctions.QUERY_RECENT_FILES,
        state = if (available) {
            DeviceContextToolReadinessState.Available
        } else {
            DeviceContextToolReadinessState.RequiresRuntimePermission
        },
        reason = if (available) {
            "recent media metadata can use currently granted media scopes; documents/downloads/other files require the system file picker"
        } else {
            "recent media metadata requires Android media permission; documents/downloads/other files require the system file picker"
        },
        runtimePermissions = if (available) emptyList() else recentFileRuntimePermissionHints(),
    )
}

private fun DeviceContextAuthorizationSnapshot.visualMediaReadiness(
    toolName: String,
    availableReason: String,
    missingReason: String,
): DeviceContextToolReadiness {
    val available = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        hasRuntimePermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    } else {
        hasVisualMediaAccess(Manifest.permission.READ_MEDIA_IMAGES)
    }
    return DeviceContextToolReadiness(
        toolName = toolName,
        state = if (available) {
            DeviceContextToolReadinessState.Available
        } else {
            DeviceContextToolReadinessState.RequiresRuntimePermission
        },
        reason = if (available) availableReason else missingReason,
        runtimePermissions = if (available) emptyList() else visualMediaPermissionHints(),
    )
}

private fun DeviceContextAuthorizationSnapshot.hasAnyRecentFileMediaAccess(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return hasRuntimePermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return hasVisualMediaAccess(Manifest.permission.READ_MEDIA_IMAGES) ||
        hasVisualMediaAccess(Manifest.permission.READ_MEDIA_VIDEO) ||
        hasRuntimePermission(Manifest.permission.READ_MEDIA_AUDIO)
}

private fun DeviceContextAuthorizationSnapshot.hasVisualMediaAccess(
    fullMediaPermission: String,
): Boolean =
    hasRuntimePermission(fullMediaPermission) ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            hasRuntimePermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

private fun recentFileRuntimePermissionHints(): List<String> =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE.androidPermissionName())
    } else {
        buildList {
            add(Manifest.permission.READ_MEDIA_IMAGES.androidPermissionName())
            add(Manifest.permission.READ_MEDIA_VIDEO.androidPermissionName())
            add(Manifest.permission.READ_MEDIA_AUDIO.androidPermissionName())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED.androidPermissionName())
            }
        }
    }

private fun visualMediaPermissionHints(): List<String> =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE.androidPermissionName())
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        listOf(
            Manifest.permission.READ_MEDIA_IMAGES.androidPermissionName(),
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED.androidPermissionName(),
        )
    } else {
        listOf(Manifest.permission.READ_MEDIA_IMAGES.androidPermissionName())
    }

private fun String.androidPermissionName(): String =
    substringAfterLast('.')
