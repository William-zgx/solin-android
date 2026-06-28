package com.bytedance.zgx.solin

import android.Manifest
import android.os.Build
import android.provider.Settings
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.tool.AndroidRuntimePermissionKind
import com.bytedance.zgx.solin.tool.AndroidRuntimePermissionSpec
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolCapabilityTag
import com.bytedance.zgx.solin.tool.ToolPermission
import com.bytedance.zgx.solin.tool.ToolProvider
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimePermissionPolicyTest {
    @Test
    fun backgroundNotificationToolsRequestNotificationPermissionOnlyOnAndroid13Plus() {
        val confirmation = confirmationFor(MobileActionFunctions.SCHEDULE_REMINDER)

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S).isEmpty())
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU),
        )

        val periodicCheckConfirmation = confirmationFor(MobileActionFunctions.CONFIGURE_PERIODIC_CHECK)
        assertTrue(periodicCheckConfirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S).isEmpty())
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            periodicCheckConfirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU),
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
    fun contactDraftSkillFirstConfirmationDoesNotRequestContactsPermission() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.CREATE_CONTACT_DRAFT,
            arguments = mapOf("name" to "Alice"),
            skillId = BuiltInSkillRuntime.CONTACT_DRAFT_SKILL,
        )

        assertTrue(confirmation.runtimePermissionsFor().isEmpty())
        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU).isEmpty())
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
        assertEquals(
            "用于只读查询忙闲时间段，不读取标题、地点或参与人。",
            confirmation.runtimePermissionRequirementsFor().single().rationale,
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
    fun recentVisualMediaModelsSelectedPhotoAccessOnAndroid14Plus() {
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "images"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "videos"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            confirmationFor(MobileActionFunctions.READ_RECENT_IMAGE_OCR)
                .runtimePermissionsFor(apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.READ_MEDIA_AUDIO,
            ),
            confirmationFor(MobileActionFunctions.QUERY_RECENT_FILES)
                .runtimePermissionsFor(apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
    }

    @Test
    fun recentNonMediaFilesDoNotPretendToHaveARequestableAndroid13Permission() {
        listOf("documents", "downloads", "others").forEach { kind ->
            val confirmation = confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to kind),
            )

            assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU).isEmpty())
            assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE).isEmpty())
        }
    }

    @Test
    fun recentScreenshotOcrSkillFirstConfirmationStillRequestsImageReadPermission() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            arguments = mapOf("maxCount" to "1"),
            skillId = BuiltInSkillRuntime.RECENT_SCREENSHOT_OCR_CONTEXT_SKILL,
        )

        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU),
        )
        assertTrue(confirmation.specialAccessRequirementsFor().isEmpty())
    }

    @Test
    fun recentImageOcrSkillFirstConfirmationStillRequestsImageReadPermission() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            arguments = mapOf("maxCount" to "3"),
            skillId = BuiltInSkillRuntime.RECENT_IMAGE_OCR_CONTEXT_SKILL,
        )

        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU),
        )
        assertTrue(confirmation.specialAccessRequirementsFor().isEmpty())
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
                toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
                arguments = mapOf("appName" to "淘宝"),
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
        val registry = ToolRegistry()
        val runtimePermissionTools = registry.specs()
            .filter { ToolPermission.RequiresAndroidRuntimePermission in it.permissions }
            .map { it.name }
            .toSet()
        val descriptorTools = registry.specs()
            .filter { it.androidRuntimePermissions.isNotEmpty() }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf(
                MobileActionFunctions.SCHEDULE_REMINDER,
                MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
                MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
                MobileActionFunctions.QUERY_CONTACTS,
                MobileActionFunctions.QUERY_RECENT_FILES,
                MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            ),
            runtimePermissionTools,
        )
        assertEquals(
            "Android runtime permission marker and descriptor must stay in lockstep",
            runtimePermissionTools,
            descriptorTools,
        )
    }

    @Test
    fun runtimePermissionRequirementsCanComeFromRegistryProviderDescriptors() {
        val toolName = "custom_contact_context"
        val confirmation = confirmationFor(toolName)
        val registry = ToolRegistry(
            ToolProvider {
                listOf(
                    ToolSpec(
                        name = toolName,
                        title = "Custom contact context",
                        description = "Custom contact context",
                        inputSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}""",
                        capability = ToolCapability.DeviceContext,
                        permissions = setOf(ToolPermission.RequiresAndroidRuntimePermission),
                        androidRuntimePermissions = listOf(
                            AndroidRuntimePermissionSpec(AndroidRuntimePermissionKind.ReadContacts),
                        ),
                    ),
                )
            },
        )

        assertEquals(
            listOf(Manifest.permission.READ_CONTACTS),
            confirmation.runtimePermissionsFor(toolRegistry = registry),
        )
        assertEquals(
            "联系人权限",
            confirmation.runtimePermissionRequirementsFor(toolRegistry = registry).single().title,
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
        assertEquals(
            "用于通过 UsageStats 估计当前前台应用名和包名；不是窗口真值，不读取使用历史或屏幕内容，需要在系统设置中手动开启。",
            requirements.single().rationale,
        )
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, requirements.single().settingsAction)
    }

    @Test
    fun usageAccessSettingsDeclaresNoRuntimePermissionOrSpecialAccess() {
        val confirmation = confirmationFor(MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS)

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU).isEmpty())
        assertTrue(confirmation.specialAccessRequirementsFor().isEmpty())
    }

    @Test
    fun recentNotificationsDeclareNoRuntimePermissionOrSpecialAccess() {
        val confirmation = confirmationFor(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS)

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU).isEmpty())
        assertTrue(confirmation.specialAccessRequirementsFor().isEmpty())
    }

    @Test
    fun backgroundTasksQueryDeclaresNoRuntimePermissionOrSpecialAccess() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.QUERY_BACKGROUND_TASKS,
            arguments = mapOf("scope" to "all"),
            skillId = BuiltInSkillRuntime.BACKGROUND_TASKS_CONTEXT_SKILL,
        )

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU).isEmpty())
        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S).isEmpty())
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
    fun specialAccessRequirementsCanComeFromRegistryProviderTags() {
        val toolName = "custom_accessibility_tool"
        val confirmation = confirmationFor(toolName)
        val registry = ToolRegistry(
            ToolProvider {
                listOf(
                    ToolSpec(
                        name = toolName,
                        title = "Custom accessibility tool",
                        description = "Custom accessibility tool",
                        inputSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}""",
                        capability = ToolCapability.DeviceControl,
                        tags = setOf(ToolCapabilityTag.AccessibilityDeviceControlSpecialAccess),
                    ),
                )
            },
        )

        val requirements = confirmation.specialAccessRequirementsFor(registry)

        assertEquals(1, requirements.size)
        assertEquals(SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL, requirements.single().id)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, requirements.single().settingsAction)
    }

    @Test
    fun deviceControlToolsDeclareAccessibilityControlSpecialAccessOnly() {
        val deviceControlTools = listOf(
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_SCROLL,
            MobileActionFunctions.UI_PRESS_BACK,
            MobileActionFunctions.UI_WAIT,
        )

        deviceControlTools.forEach { toolName ->
            val confirmation = confirmationFor(
                toolName = toolName,
                arguments = when (toolName) {
                    MobileActionFunctions.UI_TAP -> mapOf("target" to "Continue")
                    MobileActionFunctions.UI_TYPE_TEXT -> mapOf("text" to "hello")
                    MobileActionFunctions.UI_SCROLL -> mapOf("direction" to "down")
                    else -> emptyMap()
                },
            )
            val requirements = confirmation.specialAccessRequirementsFor()

            assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU).isEmpty())
            assertEquals(1, requirements.size)
            assertEquals(SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL, requirements.single().id)
            assertEquals("无障碍设备控制权限", requirements.single().title)
            assertTrue(requirements.single().rationale.contains("点击"))
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, requirements.single().settingsAction)
        }
    }

    @Test
    fun currentScreenshotOcrDeclaresMediaProjectionConsentNotRuntimePermission() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU).isEmpty())
        assertTrue(confirmation.specialAccessRequirementsFor().isEmpty())
        val spec = requireNotNull(ToolRegistry().specFor(MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR))
        assertTrue(spec.permissions.contains(ToolPermission.RequiresMediaProjectionConsent))
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission !in spec.permissions)
        assertTrue(ToolPermission.ReadsAccessibilityText !in spec.permissions)
    }

    @Test
    fun specialAccessDenialSummaryUsesRequirementTitles() {
        assertEquals(
            "使用情况访问权限, 无障碍屏幕文本权限",
            specialAccessDenialSummary(
                listOf(
                    confirmationFor(MobileActionFunctions.QUERY_FOREGROUND_APP)
                        .specialAccessRequirementsFor()
                        .single(),
                    confirmationFor(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT)
                        .specialAccessRequirementsFor()
                        .single(),
                    confirmationFor(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT)
                        .specialAccessRequirementsFor()
                        .single(),
                ),
            ),
        )
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

    @Test
    fun android14VisualMediaGrantAcceptsEitherFullOrUserSelectedAccess() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.QUERY_RECENT_FILES,
            arguments = mapOf("kind" to "images"),
        )
        val imagePermission = Manifest.permission.READ_MEDIA_IMAGES
        val selectedVisualPermission = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED

        assertTrue(
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(
                    imagePermission to true,
                    selectedVisualPermission to false,
                ),
                apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                hasRuntimePermission = { false },
            ).isEmpty(),
        )
        assertTrue(
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(
                    imagePermission to false,
                    selectedVisualPermission to true,
                ),
                apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                hasRuntimePermission = { false },
            ).isEmpty(),
        )
        assertEquals(
            listOf(imagePermission, selectedVisualPermission),
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(
                    imagePermission to false,
                    selectedVisualPermission to false,
                ),
                apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                hasRuntimePermission = { false },
            ),
        )
    }

    @Test
    fun android14RecentFilesAllAcceptsPartialMediaGrantWithoutAudio() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.QUERY_RECENT_FILES,
            arguments = mapOf("kind" to "all"),
        )
        val imagePermission = Manifest.permission.READ_MEDIA_IMAGES
        val videoPermission = Manifest.permission.READ_MEDIA_VIDEO
        val selectedVisualPermission = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        val audioPermission = Manifest.permission.READ_MEDIA_AUDIO

        assertTrue(
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(
                    imagePermission to false,
                    videoPermission to false,
                    selectedVisualPermission to true,
                    audioPermission to false,
                ),
                apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                hasRuntimePermission = { false },
            ).isEmpty(),
        )
        assertTrue(
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(
                    imagePermission to false,
                    videoPermission to false,
                    selectedVisualPermission to false,
                    audioPermission to true,
                ),
                apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                hasRuntimePermission = { false },
            ).isEmpty(),
        )
        assertEquals(
            listOf(imagePermission, videoPermission, selectedVisualPermission, audioPermission),
            confirmation.deniedRuntimePermissionsAfterGrantResult(
                grantResults = mapOf(
                    imagePermission to false,
                    videoPermission to false,
                    selectedVisualPermission to false,
                    audioPermission to false,
                ),
                apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                hasRuntimePermission = { false },
            ),
        )
    }

    @Test
    fun runtimePermissionResultCanMatchCurrentPendingAfterActivityRecreation() {
        val contacts = confirmationFor(MobileActionFunctions.QUERY_CONTACTS)
        val screenshot = confirmationFor(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR)

        assertTrue(
            contacts.requiresRuntimePermissionResult(
                resultPermissions = setOf(Manifest.permission.READ_CONTACTS),
                apiLevel = Build.VERSION_CODES.TIRAMISU,
            ),
        )
        assertTrue(
            contacts.requiresRuntimePermissionResult(
                resultPermissions = emptySet(),
                apiLevel = Build.VERSION_CODES.TIRAMISU,
            ),
        )
        assertTrue(
            contacts.matchesExecution(contacts.copy()),
        )
        assertTrue(
            !contacts.matchesExecution(screenshot),
        )
        assertTrue(
            !contacts.requiresRuntimePermissionResult(
                resultPermissions = setOf(Manifest.permission.READ_MEDIA_IMAGES),
                apiLevel = Build.VERSION_CODES.TIRAMISU,
            ),
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
