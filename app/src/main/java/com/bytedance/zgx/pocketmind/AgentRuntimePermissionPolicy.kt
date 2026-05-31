package com.bytedance.zgx.pocketmind

import android.Manifest
import android.os.Build
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions

fun PendingAgentConfirmation.runtimePermissionsFor(apiLevel: Int = Build.VERSION.SDK_INT): List<String> {
    val toolName = toolRequest?.toolName ?: draft.functionName
    return when (toolName) {
        MobileActionFunctions.SCHEDULE_REMINDER ->
            if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyList()
            }

        MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY ->
            listOf(Manifest.permission.READ_CALENDAR)

        MobileActionFunctions.QUERY_CONTACTS ->
            listOf(Manifest.permission.READ_CONTACTS)

        MobileActionFunctions.QUERY_RECENT_FILES ->
            recentFilePermissionsFor(
                kind = toolRequest?.arguments?.get("kind")
                    ?: draft.parameters["kind"]
                    ?: "all",
                apiLevel = apiLevel,
            )

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

private fun recentFilePermissionsFor(kind: String, apiLevel: Int): List<String> {
    if (apiLevel < Build.VERSION_CODES.TIRAMISU) {
        return listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return when (kind.lowercase()) {
        "screenshots" -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
        "images" -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
        "videos" -> listOf(Manifest.permission.READ_MEDIA_VIDEO)
        "audio" -> listOf(Manifest.permission.READ_MEDIA_AUDIO)
        "all" -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
        else -> emptyList()
    }
}
