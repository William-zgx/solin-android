package com.bytedance.zgx.pocketmind

import android.Manifest
import android.os.Build
import android.provider.Settings
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntime
import com.bytedance.zgx.pocketmind.tool.ToolPermission
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimePermissionPolicyTest {
    @Test
    fun reminderRequestsNotificationPermissionOnlyOnAndroid13Plus() {
        val confirmation = confirmationFor(MobileActionFunctions.SCHEDULE_REMINDER)

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S).isEmpty())
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU),
        )
    }

    @Test
    fun calendarAndContactToolsRequestTheirRuntimePermissions() {
        assertEquals(
            listOf(Manifest.permission.READ_CALENDAR),
            confirmationFor(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY).runtimePermissionsFor(),
        )
        assertEquals(
            listOf(Manifest.permission.READ_CONTACTS),
            confirmationFor(MobileActionFunctions.QUERY_CONTACTS).runtimePermissionsFor(),
        )
    }

    @Test
    fun contactLookupSkillFirstConfirmationStillRequestsContactsPermission() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.QUERY_CONTACTS,
            arguments = mapOf("query" to "Alice"),
            skillId = "contact_lookup_skill",
        )

        assertEquals(
            listOf(Manifest.permission.READ_CONTACTS),
            confirmation.runtimePermissionsFor(),
        )
        assertTrue(confirmation.specialAccessRequirementsFor().isEmpty())
    }

    @Test
    fun calendarAvailabilitySkillFirstConfirmationStillRequestsCalendarPermission() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
            arguments = mapOf(
                "start" to "2026-06-01T09:00:00Z",
                "end" to "2026-06-01T10:00:00Z",
            ),
            skillId = "calendar_availability_skill",
        )

        assertEquals(
            listOf(Manifest.permission.READ_CALENDAR),
            confirmation.runtimePermissionsFor(),
        )
        assertTrue(confirmation.specialAccessRequirementsFor().isEmpty())
    }

    @Test
    fun recentFilesUsesLegacyStoragePermissionBeforeAndroid13() {
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "images"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S),
        )
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "screenshots"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S),
        )
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            confirmationFor(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR)
                .runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S),
        )
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            confirmationFor(MobileActionFunctions.READ_RECENT_IMAGE_OCR)
                .runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S),
        )
    }

    @Test
    fun recentFilesUsesMediaSpecificPermissionsOnAndroid13Plus() {
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "images"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "screenshots"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            confirmationFor(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR)
                .runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            confirmationFor(MobileActionFunctions.READ_RECENT_IMAGE_OCR)
                .runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU),
        )
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            ),
            confirmationFor(MobileActionFunctions.QUERY_RECENT_FILES)
                .runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU),
        )
    }

    @Test
    fun recentNonMediaFilesDoNotPretendToHaveARequestableAndroid13Permission() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.QUERY_RECENT_FILES,
            arguments = mapOf("kind" to "documents"),
        )

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU).isEmpty())
    }

    @Test
    fun runtimePermissionRequirementsExposeFriendlyLabelsAndRationales() {
        val requirements = confirmationFor(MobileActionFunctions.QUERY_CONTACTS)
            .runtimePermissionRequirementsFor()

        assertEquals(1, requirements.size)
        assertEquals(listOf(Manifest.permission.READ_CONTACTS), requirements.single().permissions)
        assertEquals("联系人权限", requirements.single().title)
        assertTrue(requirements.single().rationale.contains("只读查询联系人"))
        assertEquals("联系人权限", runtimePermissionDenialSummary(listOf(Manifest.permission.READ_CONTACTS)))
    }

    @Test
    fun recentScreenshotOcrPermissionRationaleDisclosesPixelAndOcrRead() {
        val requirement = confirmationFor(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR)
            .runtimePermissionRequirementsFor(apiLevel = Build.VERSION_CODES.TIRAMISU)
            .single()

        assertEquals(listOf(Manifest.permission.READ_MEDIA_IMAGES), requirement.permissions)
        assertEquals("照片和图片权限", requirement.title)
        assertTrue(requirement.rationale.contains("读取最近 1 张截图像素"))
        assertTrue(requirement.rationale.contains("OCR 文本"))
    }

    @Test
    fun recentImageOcrPermissionRationaleDisclosesBoundedPixelAndOcrRead() {
        val requirement = confirmationFor(MobileActionFunctions.READ_RECENT_IMAGE_OCR)
            .runtimePermissionRequirementsFor(apiLevel = Build.VERSION_CODES.TIRAMISU)
            .single()

        assertEquals(listOf(Manifest.permission.READ_MEDIA_IMAGES), requirement.permissions)
        assertEquals("照片和图片权限", requirement.title)
        assertTrue(requirement.rationale.contains("最多扫描最近 3 张图片像素"))
        assertTrue(requirement.rationale.contains("第一条 OCR 文本"))
    }

    @Test
    fun runtimePermissionRequirementsCoverNotificationCalendarMediaAndLegacyStorage() {
        assertEquals(
            "通知权限",
            confirmationFor(MobileActionFunctions.SCHEDULE_REMINDER)
                .runtimePermissionRequirementsFor(apiLevel = Build.VERSION_CODES.TIRAMISU)
                .single()
                .title,
        )
        assertEquals(
            "日历权限",
            confirmationFor(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY)
                .runtimePermissionRequirementsFor()
                .single()
                .title,
        )
        assertEquals(
            listOf("照片和图片权限", "视频权限", "音频权限"),
            confirmationFor(MobileActionFunctions.QUERY_RECENT_FILES)
                .runtimePermissionRequirementsFor(apiLevel = Build.VERSION_CODES.TIRAMISU)
                .map { it.title },
        )
        assertEquals(
            "文件读取权限",
            confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "downloads"),
            )
                .runtimePermissionRequirementsFor(apiLevel = Build.VERSION_CODES.S)
                .single()
                .title,
        )
    }

    @Test
    fun deepLinkAndAppIntentDoNotRequestRuntimePermissions() {
        assertTrue(
            confirmationFor(
                toolName = MobileActionFunctions.OPEN_DEEP_LINK,
                arguments = mapOf("uri" to "https://example.com"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU).isEmpty(),
        )
        assertTrue(
            confirmationFor(
                toolName = MobileActionFunctions.OPEN_APP_INTENT,
                arguments = mapOf("packageName" to "com.example.app"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU).isEmpty(),
        )
        assertTrue(
            confirmationFor(
                toolName = MobileActionFunctions.OPEN_APP_DEEP_TARGET,
                arguments = mapOf(
                    "targetId" to "android_app_details_settings",
                    "packageName" to "com.example.app",
                ),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU).isEmpty(),
        )
    }

    @Test
    fun runtimePermissionRegistryMarkerMatchesPolicyTools() {
        val runtimePermissionTools = ToolRegistry().specs()
            .filter { ToolPermission.RequiresAndroidRuntimePermission in it.permissions }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf(
                MobileActionFunctions.SCHEDULE_REMINDER,
                MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
                MobileActionFunctions.QUERY_CONTACTS,
                MobileActionFunctions.QUERY_RECENT_FILES,
                MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            ),
            runtimePermissionTools,
        )
    }

    @Test
    fun foregroundAppDeclaresUsageAccessAsSpecialAccessNotRuntimePermission() {
        val confirmation = confirmationFor(MobileActionFunctions.QUERY_FOREGROUND_APP)
        val requirements = confirmation.specialAccessRequirementsFor()

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU).isEmpty())
        assertEquals(1, requirements.size)
        assertEquals(SPECIAL_ACCESS_USAGE_STATS, requirements.single().id)
        assertEquals("使用情况访问权限", requirements.single().title)
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, requirements.single().settingsAction)
    }

    @Test
    fun recentNotificationsDeclareNoRuntimePermissionOrSpecialAccess() {
        val confirmation = confirmationFor(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS)

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU).isEmpty())
        assertTrue(confirmation.specialAccessRequirementsFor().isEmpty())
    }

    @Test
    fun currentScreenTextDeclaresAccessibilityAsSpecialAccessNotRuntimePermission() {
        val confirmation = confirmationFor(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT)
        val requirements = confirmation.specialAccessRequirementsFor()

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU).isEmpty())
        assertEquals(1, requirements.size)
        assertEquals(SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT, requirements.single().id)
        assertEquals("无障碍屏幕文本权限", requirements.single().title)
        assertTrue(requirements.single().rationale.contains("当前屏幕"))
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, requirements.single().settingsAction)
    }

    @Test
    fun currentScreenTextSkillFirstConfirmationDeclaresAccessibilitySpecialAccessOnly() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            arguments = mapOf("maxChars" to "1200"),
            skillId = BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_CONTEXT_SKILL,
        )
        val requirements = confirmation.specialAccessRequirementsFor()

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU).isEmpty())
        assertEquals(1, requirements.size)
        assertEquals(SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT, requirements.single().id)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, requirements.single().settingsAction)
    }

    @Test
    fun pendingSpecialAccessRequirementRestoresFromCurrentPendingConfirmationOnly() {
        val usageConfirmation = confirmationFor(MobileActionFunctions.QUERY_FOREGROUND_APP)
        val screenTextConfirmation = confirmationFor(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT)

        assertEquals(
            SPECIAL_ACCESS_USAGE_STATS,
            restoredPendingSpecialAccessRequirement(
                requirementId = SPECIAL_ACCESS_USAGE_STATS,
                pendingConfirmation = usageConfirmation,
            )?.id,
        )
        assertEquals(
            SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT,
            restoredPendingSpecialAccessRequirement(
                requirementId = SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT,
                pendingConfirmation = screenTextConfirmation,
            )?.id,
        )
        assertNull(
            restoredPendingSpecialAccessRequirement(
                requirementId = SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT,
                pendingConfirmation = usageConfirmation,
            ),
        )
        assertNull(
            restoredPendingSpecialAccessRequirement(
                requirementId = SPECIAL_ACCESS_USAGE_STATS,
                pendingConfirmation = null,
            ),
        )
    }

    @Test
    fun deniedGrantResultKeepsToolFromExecutingUntilPermissionIsActuallyGranted() {
        val confirmation = confirmationFor(MobileActionFunctions.QUERY_CONTACTS)
        val permission = Manifest.permission.READ_CONTACTS

        assertEquals(
            listOf(permission),
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(permission to false),
                hasRuntimePermission = { false },
            ),
        )
        assertEquals(
            listOf(permission),
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = emptyMap(),
                hasRuntimePermission = { false },
            ),
        )
        assertTrue(
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(permission to true),
                hasRuntimePermission = { false },
            ).isEmpty(),
        )
        assertTrue(
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = emptyMap(),
                hasRuntimePermission = { it == permission },
            ).isEmpty(),
        )
    }

    private fun confirmationFor(
        toolName: String,
        arguments: Map<String, String> = emptyMap(),
        skillId: String? = null,
    ): PendingAgentConfirmation =
        PendingAgentConfirmation(
            runId = "run-1",
            draft = ActionDraft(
                functionName = toolName,
                title = "Test",
                summary = "Test",
                parameters = arguments,
            ),
            toolRequest = ToolRequest(
                id = "request-1",
                toolName = toolName,
                arguments = arguments,
                reason = "test",
            ),
            skillId = skillId,
            plannedByModel = false,
            fallbackReason = null,
        )
}
