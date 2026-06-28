package com.bytedance.zgx.solin.action

import android.content.Intent
import android.net.Uri
import android.provider.Settings

data class AppDeepTarget(
    val id: String,
    val title: String,
    val targetKind: String,
    val requiredArguments: Set<String>,
    val intentAction: String,
    val buildIntent: (Map<String, String>) -> Intent,
)

object AppDeepTargets {
    const val APP_DETAILS_SETTINGS_ID = "android_app_details_settings"
    const val PACKAGE_NAME_ARGUMENT = "packageName"
    const val TARGET_ID_ARGUMENT = "targetId"

    val all: List<AppDeepTarget> = listOf(
        AppDeepTarget(
            id = APP_DETAILS_SETTINGS_ID,
            title = "应用详情设置",
            targetKind = "AppDeepTarget",
            requiredArguments = setOf(PACKAGE_NAME_ARGUMENT),
            intentAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            buildIntent = { arguments ->
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", arguments.getValue(PACKAGE_NAME_ARGUMENT), null),
                )
            },
        ),
    )

    val ids: Set<String> = all.mapTo(linkedSetOf()) { it.id }

    fun requireTarget(id: String): AppDeepTarget =
        all.first { it.id == id }

    fun targetOrNull(id: String): AppDeepTarget? =
        all.firstOrNull { it.id == id }
}
