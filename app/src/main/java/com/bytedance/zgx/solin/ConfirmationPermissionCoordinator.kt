package com.bytedance.zgx.solin

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.bytedance.zgx.solin.device.DeviceContextAuthorizationSnapshot
import com.bytedance.zgx.solin.device.SolinAccessibilityService

/**
 * Activity-bound gate between a confirmed [PendingAgentConfirmation] and the system dialogs it
 * needs first: runtime permissions, special-access settings screens, and the one-shot
 * MediaProjection consent for current-screenshot OCR. Only after every gate passes is the
 * confirmation forwarded to [SolinViewModel.confirmAgentConfirmation]; any denial or unmatched
 * result rejects the confirmation (fail closed).
 *
 * Must be constructed while the activity is being initialized so the result launchers register
 * before the activity reaches STARTED.
 */
internal class ConfirmationPermissionCoordinator(
    private val activity: ComponentActivity,
    private val viewModel: () -> SolinViewModel,
    private val appContainer: () -> SolinAppContainer,
) {
    private var pendingRuntimePermissionConfirmation: PendingAgentConfirmation? = null
    private var pendingMediaProjectionConfirmation: PendingAgentConfirmation? = null
    private var pendingSpecialAccessRequirement: SpecialAccessRequirement? = null

    private val runtimePermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantResults ->
        val confirmation = pendingRuntimePermissionConfirmationForResult(grantResults.keys)
        pendingRuntimePermissionConfirmation = null
        syncDeviceContextAuthorizationSnapshot()
        if (confirmation == null) {
            rejectCurrentRuntimePermissionPendingAfterUnmatchedResult(grantResults.keys)
            return@registerForActivityResult
        }
        val deniedPermissions = confirmation.deniedRuntimePermissionsAfterGrantResult(
            grantResults = grantResults,
            hasRuntimePermission = ::hasRuntimePermission,
            toolRegistry = appContainer().toolRegistry,
        )
        if (deniedPermissions.isEmpty()) {
            confirmAgentConfirmationWithPermissions(confirmation)
        } else {
            viewModel().launchPersistenceWork {
                viewModel().rejectAgentConfirmationForRuntimePermissionDenial(
                    confirmation = confirmation,
                    deniedPermissions = deniedPermissions,
                )
            }
        }
    }
    private val specialAccessLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val requirement = pendingSpecialAccessRequirement ?: return@registerForActivityResult
        pendingSpecialAccessRequirement = null
        syncDeviceContextAuthorizationSnapshot()
        viewModel().launchPersistenceWork {
            viewModel().reportSpecialAccessResult(
                requirement = requirement,
                granted = hasSpecialAccess(requirement.id),
            )
        }
    }
    private val currentScreenshotOcrLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val confirmation = pendingMediaProjectionConfirmationForResult()
        pendingMediaProjectionConfirmation = null
        if (confirmation == null) {
            rejectCurrentMediaProjectionPendingAfterUnmatchedResult()
            return@registerForActivityResult
        }
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val requestId = confirmation.toolRequest?.id
            if (requestId == null) {
                viewModel().launchPersistenceWork {
                    viewModel().rejectAgentConfirmationForMediaProjectionDenial(confirmation)
                }
            } else {
                // Storing the consent also starts the short-lived mediaProjection foreground
                // service while this activity is still in the foreground (Android 14 requires
                // it after consent and before getMediaProjection). The capture stops it; a
                // service start failure surfaces as a distinct tool failure, not as a denial.
                appContainer().currentScreenshotOcrProvider.setOneShotConsent(
                    requestId = requestId,
                    resultCode = result.resultCode,
                    data = result.data,
                )
                viewModel().launchPersistenceWork {
                    viewModel().confirmAgentConfirmation(confirmation)
                }
            }
        } else {
            viewModel().launchPersistenceWork {
                viewModel().rejectAgentConfirmationForMediaProjectionDenial(confirmation)
            }
        }
    }

    fun saveState(outState: Bundle) {
        pendingSpecialAccessRequirement?.id?.let {
            outState.putString(KEY_PENDING_SPECIAL_ACCESS_REQUIREMENT_ID, it)
        }
    }

    fun restorePendingSpecialAccessRequirement(savedInstanceState: Bundle?) {
        pendingSpecialAccessRequirement = restoredPendingSpecialAccessRequirement(
            requirementId = savedInstanceState?.getString(KEY_PENDING_SPECIAL_ACCESS_REQUIREMENT_ID),
            pendingConfirmation = viewModel().uiState.value.pendingConfirmation,
            toolRegistry = appContainer().toolRegistry,
        )
    }

    fun openSpecialAccessSettings(requirement: SpecialAccessRequirement) {
        pendingSpecialAccessRequirement = requirement
        runCatching {
            specialAccessLauncher.launch(Intent(requirement.settingsAction))
        }.onFailure {
            pendingSpecialAccessRequirement = null
            viewModel().reportSystemSettingsUnavailable("当前设备无法打开${requirement.title}设置")
        }
    }

    fun confirmAgentConfirmationWithPermissions(confirmation: PendingAgentConfirmation) {
        syncDeviceContextAuthorizationSnapshot()
        if (
            pendingRuntimePermissionConfirmation != null ||
            pendingMediaProjectionConfirmation != null ||
            pendingSpecialAccessRequirement != null
        ) return
        val toolRegistry = appContainer().toolRegistry
        val missingPermissions = confirmation.runtimePermissionsFor(toolRegistry = toolRegistry)
            .filterNot(::hasRuntimePermission)
        if (missingPermissions.isNotEmpty()) {
            pendingRuntimePermissionConfirmation = confirmation
            runtimePermissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }
        val missingSpecialAccess = confirmation.specialAccessRequirementsFor(toolRegistry = toolRegistry)
            .filterNot { requirement -> hasSpecialAccess(requirement.id) }
        if (missingSpecialAccess.isNotEmpty()) {
            openSpecialAccessSettings(missingSpecialAccess.first())
            return
        }
        if (confirmation.requiresCurrentScreenshotOcrConsent(toolRegistry = toolRegistry)) {
            requestCurrentScreenshotOcrConsent(confirmation)
            return
        }
        viewModel().launchPersistenceWork {
            viewModel().confirmAgentConfirmation(confirmation)
        }
    }

    fun syncDeviceContextAuthorizationSnapshot() {
        viewModel().updateDeviceContextAuthorizationSnapshot(
            DeviceContextAuthorizationSnapshot(
                grantedRuntimePermissions = DEVICE_CONTEXT_RUNTIME_PERMISSIONS
                    .filter(::hasRuntimePermission)
                    .toSet(),
                grantedSpecialAccessIds = DEVICE_CONTEXT_SPECIAL_ACCESS_IDS
                    .filter(::hasSpecialAccess)
                    .toSet(),
            ),
        )
    }

    private fun requestCurrentScreenshotOcrConsent(confirmation: PendingAgentConfirmation) {
        val mediaProjectionManager =
            activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mediaProjectionManager == null) {
            viewModel().launchPersistenceWork {
                viewModel().rejectAgentConfirmationForMediaProjectionDenial(confirmation)
            }
            return
        }
        pendingMediaProjectionConfirmation = confirmation
        runCatching {
            currentScreenshotOcrLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }.onFailure {
            pendingMediaProjectionConfirmation = null
            viewModel().launchPersistenceWork {
                viewModel().rejectAgentConfirmationForMediaProjectionDenial(confirmation)
            }
        }
    }

    private fun hasRuntimePermission(permission: String): Boolean =
        activity.hasRuntimePermission(permission)

    private fun hasSpecialAccess(id: String): Boolean =
        when (id) {
            SPECIAL_ACCESS_USAGE_STATS -> hasUsageStatsAccess()
            SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT -> hasAccessibilityScreenTextAccess()
            SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL -> hasAccessibilityScreenTextAccess()
            else -> false
        }

    private fun pendingRuntimePermissionConfirmationForResult(
        resultPermissions: Set<String>,
    ): PendingAgentConfirmation? {
        val toolRegistry = appContainer().toolRegistry
        val current = viewModel().uiState.value.pendingConfirmation
        val remembered = pendingRuntimePermissionConfirmation
        if (current != null &&
            current.requiresRuntimePermissionResult(resultPermissions, toolRegistry = toolRegistry)
        ) {
            return current
        }
        return remembered?.takeIf { pending ->
            current == null && pending.requiresRuntimePermissionResult(resultPermissions, toolRegistry = toolRegistry)
        }
    }

    private fun rejectCurrentRuntimePermissionPendingAfterUnmatchedResult(
        resultPermissions: Set<String>,
    ) {
        val toolRegistry = appContainer().toolRegistry
        val current = viewModel().uiState.value.pendingConfirmation
            ?.takeIf { pending ->
                pending.requiresRuntimePermissionResult(resultPermissions, toolRegistry = toolRegistry) ||
                    pending.runtimePermissionsFor(toolRegistry = toolRegistry).isNotEmpty()
            }
            ?: return
        val deniedPermissions = current.runtimePermissionsFor(toolRegistry = toolRegistry)
            .filterNot(::hasRuntimePermission)
            .ifEmpty { current.runtimePermissionsFor(toolRegistry = toolRegistry) }
        viewModel().launchPersistenceWork {
            viewModel().rejectAgentConfirmationForRuntimePermissionDenial(
                confirmation = current,
                deniedPermissions = deniedPermissions,
            )
        }
    }

    private fun pendingMediaProjectionConfirmationForResult(): PendingAgentConfirmation? {
        val toolRegistry = appContainer().toolRegistry
        val current = viewModel().uiState.value.pendingConfirmation
        val remembered = pendingMediaProjectionConfirmation
        if (current != null && current.requiresCurrentScreenshotOcrConsent(toolRegistry = toolRegistry)) {
            return current
        }
        return remembered?.takeIf { pending ->
            current == null && pending.requiresCurrentScreenshotOcrConsent(toolRegistry = toolRegistry)
        }
    }

    private fun rejectCurrentMediaProjectionPendingAfterUnmatchedResult() {
        val current = viewModel().uiState.value.pendingConfirmation
            ?.takeIf { it.requiresCurrentScreenshotOcrConsent(toolRegistry = appContainer().toolRegistry) }
            ?: return
        viewModel().launchPersistenceWork {
            viewModel().rejectAgentConfirmationForMediaProjectionDenial(current)
        }
    }

    private fun hasUsageStatsAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    activity.packageName,
                )
            } else {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    activity.packageName,
                )
            }
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasAccessibilityScreenTextAccess(): Boolean {
        val enabledServices = Settings.Secure.getString(
            activity.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val target = ComponentName(activity, SolinAccessibilityService::class.java).flattenToString()
        return enabledServices
            .split(':')
            .any { enabled ->
                ComponentName.unflattenFromString(enabled)
                    ?.flattenToString()
                    ?.equals(target, ignoreCase = true) == true
            }
    }

    private companion object {
        const val KEY_PENDING_SPECIAL_ACCESS_REQUIREMENT_ID =
            "com.bytedance.zgx.solin.state.PENDING_SPECIAL_ACCESS_REQUIREMENT_ID"
        val DEVICE_CONTEXT_RUNTIME_PERMISSIONS = buildList {
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        }
        val DEVICE_CONTEXT_SPECIAL_ACCESS_IDS = listOf(
            SPECIAL_ACCESS_USAGE_STATS,
            SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT,
            SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL,
        )
    }
}

/** POST_NOTIFICATIONS is implicitly granted below Android 13, where it does not exist. */
internal fun Context.hasRuntimePermission(permission: String): Boolean {
    if (permission == Manifest.permission.POST_NOTIFICATIONS &&
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    ) {
        return true
    }
    return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
