package com.bytedance.zgx.pocketmind.tool

import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.action.AppDeepTargets
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    private val registry = ToolRegistry()

    @Test
    fun rejectsUnknownTool() {
        val rejection = registry.validate(
            ToolRequest(
                id = "request-1",
                toolName = "delete_contact",
                reason = "test",
            ),
        )

        assertNotNull(rejection)
        requireNotNull(rejection)
        assertEquals(ToolStatus.Rejected, rejection.status)
        assertEquals(ToolErrorCode.UnknownTool, rejection.error?.code)
        assertTrue(rejection.summary.contains("Unknown tool"))
        assertEquals("delete_contact", rejection.data["toolName"])
    }

    @Test
    fun exposesSpecsForSupportedActionsWithConfirmationRequired() {
        val specNames = registry.specs().map { it.name }.toSet()

        assertTrue(specNames.containsAll(MobileActionFunctions.supported))

        val wifiSpec = registry.specFor(MobileActionFunctions.OPEN_WIFI_SETTINGS)
        assertNotNull(wifiSpec)
        requireNotNull(wifiSpec)
        assertEquals(ToolCapability.DeviceSettings, wifiSpec.capability)
        assertTrue(ToolPermission.StartsExternalActivity in wifiSpec.permissions)
        assertEquals(RiskLevel.MediumDraftOrNavigation, wifiSpec.riskLevel)
        assertEquals(ConfirmationPolicy.Required, wifiSpec.confirmationPolicy)

        val usageAccessSpec = registry.specFor(MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS)
        assertNotNull(usageAccessSpec)
        requireNotNull(usageAccessSpec)
        assertEquals(ToolCapability.DeviceSettings, usageAccessSpec.capability)
        assertTrue(ToolPermission.StartsExternalActivity in usageAccessSpec.permissions)
        assertTrue(ToolPermission.ReadsDeviceContext !in usageAccessSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission !in usageAccessSpec.permissions)
        assertEquals(RiskLevel.MediumDraftOrNavigation, usageAccessSpec.riskLevel)
        assertEquals(ConfirmationPolicy.Required, usageAccessSpec.confirmationPolicy)

        val webSearchSpec = registry.specFor(MobileActionFunctions.WEB_SEARCH)
        assertNotNull(webSearchSpec)
        requireNotNull(webSearchSpec)
        assertEquals(ToolCapability.WebSearch, webSearchSpec.capability)
        assertFalse(ToolPermission.StartsExternalActivity in webSearchSpec.permissions)
        assertEquals(RiskLevel.LowReadOnly, webSearchSpec.riskLevel)
        assertEquals(ConfirmationPolicy.NotRequired, webSearchSpec.confirmationPolicy)
        assertEquals(ToolResultContinuationPolicy.PublicEvidence, webSearchSpec.resultContinuationPolicy)
        assertTrue(webSearchSpec.inputSchemaJson.contains("query"))
        assertTrue(webSearchSpec.inputSchemaJson.contains("模型理解后的搜索关键词"))
        assertTrue(webSearchSpec.description.contains("不要直接复制用户原文"))
        assertTrue(webSearchSpec.inputSchemaJson.contains("weather_current"))
        assertTrue(webSearchSpec.inputSchemaJson.contains("maxResults"))
        assertTrue(webSearchSpec.inputSchemaJson.contains("freshness"))
        assertFalse(webSearchSpec.inputSchemaJson.contains("\"local\""))
        assertTrue(webSearchSpec.outputSchemaJson.contains("summaryText"))
        assertTrue(webSearchSpec.outputSchemaJson.contains("resultsJson"))

        val reminderSpec = registry.specFor(MobileActionFunctions.SCHEDULE_REMINDER)
        assertNotNull(reminderSpec)
        requireNotNull(reminderSpec)
        assertEquals(ToolCapability.BackgroundTask, reminderSpec.capability)
        assertTrue(ToolPermission.SchedulesBackgroundWork in reminderSpec.permissions)
        assertTrue(ToolPermission.PostsNotification in reminderSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission in reminderSpec.permissions)

        val periodicCheckSpec = registry.specFor(MobileActionFunctions.CONFIGURE_PERIODIC_CHECK)
        assertNotNull(periodicCheckSpec)
        requireNotNull(periodicCheckSpec)
        assertEquals(ToolCapability.BackgroundTask, periodicCheckSpec.capability)
        assertEquals(RiskLevel.MediumDraftOrNavigation, periodicCheckSpec.riskLevel)
        assertEquals(ConfirmationPolicy.Required, periodicCheckSpec.confirmationPolicy)
        assertTrue(ToolPermission.SchedulesBackgroundWork in periodicCheckSpec.permissions)
        assertTrue(ToolPermission.PostsNotification in periodicCheckSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission in periodicCheckSpec.permissions)
        assertTrue(periodicCheckSpec.inputSchemaJson.contains("\"enabled\""))
        assertTrue(periodicCheckSpec.inputSchemaJson.contains("\"intervalMinutes\""))
        assertTrue(periodicCheckSpec.description.contains("不执行后台聊天"))

        val backgroundTasksSpec = registry.specFor(MobileActionFunctions.QUERY_BACKGROUND_TASKS)
        assertNotNull(backgroundTasksSpec)
        requireNotNull(backgroundTasksSpec)
        assertEquals(ToolCapability.BackgroundTask, backgroundTasksSpec.capability)
        assertEquals(RiskLevel.LowReadOnly, backgroundTasksSpec.riskLevel)
        assertEquals(ConfirmationPolicy.Required, backgroundTasksSpec.confirmationPolicy)
        assertEquals(ToolResultContinuationPolicy.LocalEvidence, backgroundTasksSpec.resultContinuationPolicy)
        assertTrue(ToolPermission.ReadsDeviceContext in backgroundTasksSpec.permissions)
        assertTrue(ToolPermission.SchedulesBackgroundWork !in backgroundTasksSpec.permissions)
        assertTrue(ToolPermission.PostsNotification !in backgroundTasksSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission !in backgroundTasksSpec.permissions)
        assertTrue(backgroundTasksSpec.inputSchemaJson.contains("\"scope\""))
        assertTrue(backgroundTasksSpec.inputSchemaJson.contains("\"maxCount\""))
        assertTrue(backgroundTasksSpec.outputSchemaJson.contains("background_tasks_local_only_no_reminder_body"))
        assertTrue(backgroundTasksSpec.description.contains("不会返回提醒正文"))

        val cancelReminderSpec = registry.specFor(MobileActionFunctions.CANCEL_REMINDER)
        assertNotNull(cancelReminderSpec)
        requireNotNull(cancelReminderSpec)
        assertEquals(ToolCapability.BackgroundTask, cancelReminderSpec.capability)
        assertEquals(ConfirmationPolicy.Required, cancelReminderSpec.confirmationPolicy)
        assertTrue(ToolPermission.SchedulesBackgroundWork in cancelReminderSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission !in cancelReminderSpec.permissions)
        assertTrue(cancelReminderSpec.inputSchemaJson.contains("taskId"))

        val clipboardSpec = registry.specFor(MobileActionFunctions.READ_CLIPBOARD)
        assertNotNull(clipboardSpec)
        requireNotNull(clipboardSpec)
        assertEquals(ToolCapability.DeviceContext, clipboardSpec.capability)
        assertTrue(ToolPermission.ReadsDeviceContext in clipboardSpec.permissions)
        assertTrue(ToolPermission.ReadsClipboard in clipboardSpec.permissions)

        val shareSpec = registry.specFor(MobileActionFunctions.SHARE_TEXT)
        assertNotNull(shareSpec)
        requireNotNull(shareSpec)
        assertEquals(ToolCapability.ExternalShare, shareSpec.capability)
        assertTrue(ToolPermission.StartsExternalActivity in shareSpec.permissions)
        assertTrue(ToolPermission.SendsTextToExternalApp in shareSpec.permissions)
        assertTrue(shareSpec.inputSchemaJson.contains("\"maxLength\": $MAX_SHARE_TEXT_CHARS"))
        assertTrue(shareSpec.inputSchemaJson.contains("\"maxLength\": $MAX_SHARE_TITLE_CHARS"))

        val calendarAvailabilitySpec = registry.specFor(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY)
        assertNotNull(calendarAvailabilitySpec)
        requireNotNull(calendarAvailabilitySpec)
        assertEquals(ToolCapability.DeviceContext, calendarAvailabilitySpec.capability)
        assertEquals(RiskLevel.LowReadOnly, calendarAvailabilitySpec.riskLevel)
        assertEquals(ToolResultContinuationPolicy.LocalEvidence, calendarAvailabilitySpec.resultContinuationPolicy)
        assertTrue(ToolPermission.ReadsDeviceContext in calendarAvailabilitySpec.permissions)
        assertTrue(ToolPermission.ReadsCalendar in calendarAvailabilitySpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission in calendarAvailabilitySpec.permissions)
        assertTrue(calendarAvailabilitySpec.inputSchemaJson.contains("\"start\""))
        assertTrue(calendarAvailabilitySpec.inputSchemaJson.contains("\"end\""))
        assertTrue(calendarAvailabilitySpec.inputSchemaJson.contains("31 days"))

        val contactsSpec = registry.specFor(MobileActionFunctions.QUERY_CONTACTS)
        assertNotNull(contactsSpec)
        requireNotNull(contactsSpec)
        assertEquals(ToolCapability.DeviceContext, contactsSpec.capability)
        assertEquals(RiskLevel.LowReadOnly, contactsSpec.riskLevel)
        assertEquals(ToolResultContinuationPolicy.LocalEvidence, contactsSpec.resultContinuationPolicy)
        assertTrue(ToolPermission.ReadsDeviceContext in contactsSpec.permissions)
        assertTrue(ToolPermission.ReadsContacts in contactsSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission in contactsSpec.permissions)
        assertTrue(contactsSpec.inputSchemaJson.contains("\"maximum\": 20"))

        val contactDraftSpec = registry.specFor(MobileActionFunctions.CREATE_CONTACT_DRAFT)
        assertNotNull(contactDraftSpec)
        requireNotNull(contactDraftSpec)
        assertEquals(ToolCapability.ExternalDraft, contactDraftSpec.capability)
        assertTrue(ToolPermission.StartsExternalActivity in contactDraftSpec.permissions)
        assertTrue(ToolPermission.SendsTextToExternalApp in contactDraftSpec.permissions)
        assertTrue(ToolPermission.ReadsContacts !in contactDraftSpec.permissions)
        assertTrue(ToolPermission.ReadsDeviceContext !in contactDraftSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission !in contactDraftSpec.permissions)

        val recentFilesSpec = registry.specFor(MobileActionFunctions.QUERY_RECENT_FILES)
        assertNotNull(recentFilesSpec)
        requireNotNull(recentFilesSpec)
        assertEquals(ToolCapability.DeviceContext, recentFilesSpec.capability)
        assertEquals(RiskLevel.LowReadOnly, recentFilesSpec.riskLevel)
        assertEquals(ToolResultContinuationPolicy.LocalEvidence, recentFilesSpec.resultContinuationPolicy)
        assertTrue(ToolPermission.ReadsDeviceContext in recentFilesSpec.permissions)
        assertTrue(ToolPermission.ReadsFiles in recentFilesSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission in recentFilesSpec.permissions)
        assertTrue(recentFilesSpec.inputSchemaJson.contains("\"kind\""))
        assertTrue(recentFilesSpec.inputSchemaJson.contains("\"maxCount\""))
        assertTrue(recentFilesSpec.inputSchemaJson.contains("\"screenshots\""))
        assertFalse(recentFilesSpec.inputSchemaJson.contains("\"documents\""))
        assertFalse(recentFilesSpec.inputSchemaJson.contains("\"downloads\""))
        assertFalse(recentFilesSpec.inputSchemaJson.contains("\"others\""))
        assertTrue(recentFilesSpec.inputSchemaJson.contains("系统文件选择器"))
        assertTrue(recentFilesSpec.inputSchemaJson.contains("已授权媒体"))
        assertTrue(recentFilesSpec.description.contains("Android 13"))
        assertTrue(recentFilesSpec.description.contains("系统文件选择器"))
        val recentFilesOutputProperties = JSONObject(recentFilesSpec.outputSchemaJson).getJSONObject("properties")
        assertTrue(recentFilesOutputProperties.has("mediaAccessScope"))
        assertTrue(
            recentFilesOutputProperties
                .getJSONObject("mediaAccessScope")
                .getJSONArray("enum")
                .containsString("user_selected_visual_media"),
        )

        val screenshotOcrSpec = registry.specFor(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR)
        assertNotNull(screenshotOcrSpec)
        requireNotNull(screenshotOcrSpec)
        assertEquals(ToolCapability.DeviceContext, screenshotOcrSpec.capability)
        assertEquals(RiskLevel.MediumDraftOrNavigation, screenshotOcrSpec.riskLevel)
        assertEquals(ConfirmationPolicy.Required, screenshotOcrSpec.confirmationPolicy)
        assertTrue(ToolPermission.ReadsDeviceContext in screenshotOcrSpec.permissions)
        assertTrue(ToolPermission.ReadsFiles in screenshotOcrSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission in screenshotOcrSpec.permissions)
        assertTrue(screenshotOcrSpec.inputSchemaJson.contains("\"maximum\": 1"))
        assertTrue(screenshotOcrSpec.description.contains("不保存 URI"))

        val imageOcrSpec = registry.specFor(MobileActionFunctions.READ_RECENT_IMAGE_OCR)
        assertNotNull(imageOcrSpec)
        requireNotNull(imageOcrSpec)
        assertEquals(ToolCapability.DeviceContext, imageOcrSpec.capability)
        assertEquals(RiskLevel.MediumDraftOrNavigation, imageOcrSpec.riskLevel)
        assertEquals(ConfirmationPolicy.Required, imageOcrSpec.confirmationPolicy)
        assertTrue(ToolPermission.ReadsDeviceContext in imageOcrSpec.permissions)
        assertTrue(ToolPermission.ReadsFiles in imageOcrSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission in imageOcrSpec.permissions)
        assertTrue(imageOcrSpec.inputSchemaJson.contains("\"maximum\": 3"))
        assertTrue(imageOcrSpec.description.contains("不保存 URI"))

        val currentScreenTextSpec = registry.specFor(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT)
        assertNotNull(currentScreenTextSpec)
        requireNotNull(currentScreenTextSpec)
        assertEquals(ToolCapability.DeviceContext, currentScreenTextSpec.capability)
        assertEquals(RiskLevel.MediumDraftOrNavigation, currentScreenTextSpec.riskLevel)
        assertEquals(ConfirmationPolicy.Required, currentScreenTextSpec.confirmationPolicy)
        assertTrue(ToolPermission.ReadsDeviceContext in currentScreenTextSpec.permissions)
        assertTrue(ToolPermission.ReadsAccessibilityText in currentScreenTextSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission !in currentScreenTextSpec.permissions)
        assertTrue(currentScreenTextSpec.inputSchemaJson.contains("\"maximum\": 4000"))
        assertTrue(currentScreenTextSpec.description.contains("Accessibility"))
        assertTrue(currentScreenTextSpec.description.contains("不是截图"))

        val observeScreenSpec = registry.specFor(MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
        assertNotNull(observeScreenSpec)
        requireNotNull(observeScreenSpec)
        assertEquals(ToolCapability.DeviceControl, observeScreenSpec.capability)
        assertEquals(ToolResultContinuationPolicy.LocalEvidence, observeScreenSpec.resultContinuationPolicy)
        assertEquals(ConfirmationPolicy.Required, observeScreenSpec.confirmationPolicy)
        assertTrue(ToolPermission.ReadsDeviceContext in observeScreenSpec.permissions)
        assertTrue(ToolPermission.ReadsAccessibilityText in observeScreenSpec.permissions)
        assertTrue(ToolPermission.PerformsAccessibilityGesture !in observeScreenSpec.permissions)
        assertTrue(observeScreenSpec.inputSchemaJson.contains("\"maxNodes\""))
        assertTrue(observeScreenSpec.outputSchemaJson.contains("\"nodesJson\""))
        assertTrue(observeScreenSpec.description.contains("短期节点 id"))
        assertFalse(observeScreenSpec.isRemoteModelPlanningEligible())

        val uiTapSpec = registry.specFor(MobileActionFunctions.UI_TAP)
        assertNotNull(uiTapSpec)
        requireNotNull(uiTapSpec)
        assertEquals(ToolCapability.DeviceControl, uiTapSpec.capability)
        assertEquals(ToolResultContinuationPolicy.LocalEvidence, uiTapSpec.resultContinuationPolicy)
        assertEquals(ConfirmationPolicy.Required, uiTapSpec.confirmationPolicy)
        assertTrue(ToolPermission.ReadsDeviceContext in uiTapSpec.permissions)
        assertTrue(ToolPermission.ReadsAccessibilityText in uiTapSpec.permissions)
        assertTrue(ToolPermission.PerformsAccessibilityGesture in uiTapSpec.permissions)
        assertTrue(uiTapSpec.outputSchemaJson.contains("\"afterNodesJson\""))
        assertFalse(uiTapSpec.isRemoteModelPlanningEligible())

        val currentScreenshotOcrSpec = registry.specFor(MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR)
        assertNotNull(currentScreenshotOcrSpec)
        requireNotNull(currentScreenshotOcrSpec)
        assertEquals(ToolCapability.DeviceContext, currentScreenshotOcrSpec.capability)
        assertEquals(RiskLevel.MediumDraftOrNavigation, currentScreenshotOcrSpec.riskLevel)
        assertEquals(ConfirmationPolicy.Required, currentScreenshotOcrSpec.confirmationPolicy)
        assertTrue(ToolPermission.ReadsDeviceContext in currentScreenshotOcrSpec.permissions)
        assertTrue(ToolPermission.RequiresMediaProjectionConsent in currentScreenshotOcrSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission !in currentScreenshotOcrSpec.permissions)
        assertTrue(currentScreenshotOcrSpec.inputSchemaJson.contains("\"captureMode\""))
        assertTrue(currentScreenshotOcrSpec.outputSchemaJson.contains("capture_current_screenshot_ocr"))
        assertTrue(currentScreenshotOcrSpec.outputSchemaJson.contains("\"truncated\""))
        assertTrue(currentScreenshotOcrSpec.description.contains("MediaProjection"))
        assertTrue(currentScreenshotOcrSpec.description.contains("不保存图片"))

        val recentNotificationSpec = registry.specFor(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS)
        assertNotNull(recentNotificationSpec)
        requireNotNull(recentNotificationSpec)
        assertEquals(ToolCapability.DeviceContext, recentNotificationSpec.capability)
        assertEquals(ToolResultContinuationPolicy.LocalEvidence, recentNotificationSpec.resultContinuationPolicy)
        assertTrue(ToolPermission.ReadsDeviceContext in recentNotificationSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission !in recentNotificationSpec.permissions)
        assertTrue(recentNotificationSpec.description.contains("当前应用"))
        assertTrue(recentNotificationSpec.inputSchemaJson.contains("\"maximum\": 20"))

        val foregroundAppSpec = registry.specFor(MobileActionFunctions.QUERY_FOREGROUND_APP)
        assertNotNull(foregroundAppSpec)
        requireNotNull(foregroundAppSpec)
        assertEquals(ToolCapability.DeviceContext, foregroundAppSpec.capability)
        assertEquals(ToolResultContinuationPolicy.LocalEvidence, foregroundAppSpec.resultContinuationPolicy)
        assertTrue(ToolPermission.ReadsDeviceContext in foregroundAppSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission !in foregroundAppSpec.permissions)
        assertTrue(foregroundAppSpec.description.contains("UsageStats"))
        assertTrue(foregroundAppSpec.description.contains("估计"))
        assertTrue(foregroundAppSpec.outputSchemaJson.contains("usage_stats_estimate"))

        val deepLinkSpec = registry.specFor(MobileActionFunctions.OPEN_DEEP_LINK)
        assertNotNull(deepLinkSpec)
        requireNotNull(deepLinkSpec)
        assertEquals(ToolCapability.ExternalNavigation, deepLinkSpec.capability)
        assertEquals(RiskLevel.MediumDraftOrNavigation, deepLinkSpec.riskLevel)
        assertTrue(ToolPermission.StartsExternalActivity in deepLinkSpec.permissions)
        assertTrue(deepLinkSpec.inputSchemaJson.contains("\"uri\""))

        val appIntentSpec = registry.specFor(MobileActionFunctions.OPEN_APP_INTENT)
        assertNotNull(appIntentSpec)
        requireNotNull(appIntentSpec)
        assertEquals(ToolCapability.ExternalNavigation, appIntentSpec.capability)
        assertEquals(RiskLevel.MediumDraftOrNavigation, appIntentSpec.riskLevel)
        assertTrue(ToolPermission.StartsExternalActivity in appIntentSpec.permissions)
        assertTrue(appIntentSpec.description.contains("packageName"))
        assertTrue(!appIntentSpec.description.contains("activityClass"))
        assertTrue(!appIntentSpec.description.contains("data Uri"))
        assertTrue(appIntentSpec.inputSchemaJson.contains("\"packageName\""))
        assertTrue(!appIntentSpec.inputSchemaJson.contains("\"targetId\""))
        assertTrue(!appIntentSpec.inputSchemaJson.contains("\"activityClass\""))

        val appDeepTargetSpec = registry.specFor(MobileActionFunctions.OPEN_APP_DEEP_TARGET)
        assertNotNull(appDeepTargetSpec)
        requireNotNull(appDeepTargetSpec)
        assertEquals(ToolCapability.ExternalNavigation, appDeepTargetSpec.capability)
        assertEquals(RiskLevel.MediumDraftOrNavigation, appDeepTargetSpec.riskLevel)
        assertTrue(ToolPermission.StartsExternalActivity in appDeepTargetSpec.permissions)
        assertTrue(appDeepTargetSpec.inputSchemaJson.contains("\"targetId\""))
        assertTrue(appDeepTargetSpec.inputSchemaJson.contains("\"packageName\""))
        assertTrue(appDeepTargetSpec.inputSchemaJson.contains(AppDeepTargets.APP_DETAILS_SETTINGS_ID))
        assertTrue(!appDeepTargetSpec.inputSchemaJson.contains("\"activityClass\""))
    }

    @Test
    fun publicEvidenceBatchEligibilityOnlyAllowsSafePublicReadOnlyTools() {
        val eligibleWebSearch = registry.specFor(MobileActionFunctions.WEB_SEARCH)
        assertNotNull(eligibleWebSearch)
        requireNotNull(eligibleWebSearch)

        assertTrue(eligibleWebSearch.isPublicEvidenceBatchEligible())

        val blockedTools = listOf(
            MobileActionFunctions.READ_CLIPBOARD,
            MobileActionFunctions.QUERY_CONTACTS,
            MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
            MobileActionFunctions.QUERY_FOREGROUND_APP,
            MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
            MobileActionFunctions.QUERY_RECENT_FILES,
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SCROLL,
            MobileActionFunctions.UI_PRESS_BACK,
            MobileActionFunctions.UI_WAIT,
            MobileActionFunctions.OPEN_WIFI_SETTINGS,
            MobileActionFunctions.SEARCH_MAPS,
            MobileActionFunctions.SHARE_TEXT,
            MobileActionFunctions.COMPOSE_EMAIL,
            MobileActionFunctions.SCHEDULE_REMINDER,
            MobileActionFunctions.QUERY_BACKGROUND_TASKS,
        )

        blockedTools.forEach { toolName ->
            val spec = registry.specFor(toolName)
            assertNotNull(spec)
            requireNotNull(spec)
            assertFalse("$toolName must not be eligible for public evidence batch", spec.isPublicEvidenceBatchEligible())
        }
    }

    @Test
    fun remoteToolExposureRequiresExplicitReviewedAllowlist() {
        val publicEvidenceTools = registry.specs()
            .filter { spec -> spec.isPublicEvidenceBatchEligible() }
            .map { spec -> spec.name }
        val remotePlanningTools = registry.specs()
            .filter { spec -> spec.isRemoteModelPlanningEligible() }
            .map { spec -> spec.name }

        assertEquals(
            listOf(MobileActionFunctions.WEB_SEARCH),
            publicEvidenceTools,
        )
        assertEquals(
            listOf(
                MobileActionFunctions.OPEN_WIFI_SETTINGS,
                MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
                MobileActionFunctions.SEARCH_MAPS,
                MobileActionFunctions.WEB_SEARCH,
                MobileActionFunctions.COMPOSE_EMAIL,
                MobileActionFunctions.CREATE_CALENDAR_EVENT,
                MobileActionFunctions.CREATE_CONTACT_DRAFT,
                MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
                MobileActionFunctions.SCHEDULE_REMINDER,
                MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
                MobileActionFunctions.SHARE_TEXT,
                MobileActionFunctions.OPEN_DEEP_LINK,
                MobileActionFunctions.OPEN_APP_INTENT,
                MobileActionFunctions.OPEN_APP_DEEP_TARGET,
                MobileActionFunctions.CANCEL_REMINDER,
            ),
            remotePlanningTools,
        )
    }

    @Test
    fun remoteModelPlanningEligibleToolsStillRequireUserConfirmation() {
        // Issue 4: even though ExternalShare / ExternalNavigation / BackgroundTask capabilities are
        // exposed to remote-model planning, the remote model must never be able to invoke those
        // action tools without an explicit user confirmation. The only planning-eligible tool that
        // may skip confirmation is the read-only public-evidence batch path (web_search); every
        // other planning-eligible tool MUST require confirmation. This test pins that invariant so
        // no future action tool can join the planning surface with a weaker confirmation policy.
        val privilegedPlanningSpecs = registry.specs()
            .filter { spec -> spec.isRemoteModelPlanningEligible() && !spec.isPublicEvidenceBatchEligible() }
        assertTrue(
            "expected at least one privileged remote-planning-eligible tool",
            privilegedPlanningSpecs.isNotEmpty(),
        )
        privilegedPlanningSpecs.forEach { spec ->
            assertEquals(
                "remote-planning action tool ${spec.name} must still require user confirmation",
                ConfirmationPolicy.Required,
                spec.confirmationPolicy,
            )
        }

        // Spot-check the highest-risk external egress tool explicitly.
        val shareSpec = registry.specFor(MobileActionFunctions.SHARE_TEXT)
        assertNotNull(shareSpec)
        requireNotNull(shareSpec)
        assertEquals(ToolCapability.ExternalShare, shareSpec.capability)
        assertTrue(shareSpec.isRemoteModelPlanningEligible())
        assertEquals(ConfirmationPolicy.Required, shareSpec.confirmationPolicy)
    }

    @Test
    fun recentNotificationSchemaRejectsUnsupportedMaxCount() {
        val rejection = registry.validate(
            ToolRequest(
                id = "recent-notifications-too-many",
                toolName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
                arguments = mapOf("maxCount" to "21"),
                reason = "schema contract",
            ),
        )

        assertNotNull(rejection)
        requireNotNull(rejection)
        assertEquals(ToolStatus.Rejected, rejection.status)
        assertTrue(rejection.summary.contains("at most 20"))
    }

    @Test
    fun shareTextSchemaRejectsOversizedPayloads() {
        val oversizedText = registry.validate(
            ToolRequest(
                id = "share-text-too-long",
                toolName = MobileActionFunctions.SHARE_TEXT,
                arguments = mapOf("text" to "x".repeat(MAX_SHARE_TEXT_CHARS + 1)),
                reason = "schema contract",
            ),
        )
        val oversizedTitle = registry.validate(
            ToolRequest(
                id = "share-title-too-long",
                toolName = MobileActionFunctions.SHARE_TEXT,
                arguments = mapOf(
                    "text" to "hello",
                    "title" to "x".repeat(MAX_SHARE_TITLE_CHARS + 1),
                ),
                reason = "schema contract",
            ),
        )

        assertNotNull(oversizedText)
        requireNotNull(oversizedText)
        assertEquals(ToolStatus.Rejected, oversizedText.status)
        assertTrue(oversizedText.summary.contains("at most $MAX_SHARE_TEXT_CHARS"))
        assertNotNull(oversizedTitle)
        requireNotNull(oversizedTitle)
        assertEquals(ToolStatus.Rejected, oversizedTitle.status)
        assertTrue(oversizedTitle.summary.contains("at most $MAX_SHARE_TITLE_CHARS"))
    }

    @Test
    fun backgroundTasksQuerySchemaRejectsUnsupportedScopeAndCount() {
        val invalidScope = registry.validate(
            ToolRequest(
                id = "background-tasks-scope",
                toolName = MobileActionFunctions.QUERY_BACKGROUND_TASKS,
                arguments = mapOf("scope" to "secret"),
                reason = "schema contract",
            ),
        )
        val invalidCount = registry.validate(
            ToolRequest(
                id = "background-tasks-count",
                toolName = MobileActionFunctions.QUERY_BACKGROUND_TASKS,
                arguments = mapOf("maxCount" to "51"),
                reason = "schema contract",
            ),
        )

        assertNotNull(invalidScope)
        requireNotNull(invalidScope)
        assertEquals(ToolStatus.Rejected, invalidScope.status)
        assertTrue(invalidScope.summary.contains("invalid value"))
        assertNotNull(invalidCount)
        requireNotNull(invalidCount)
        assertEquals(ToolStatus.Rejected, invalidCount.status)
        assertTrue(invalidCount.summary.contains("at most 50"))
    }

    @Test
    fun periodicCheckSchemaRejectsInvalidValues() {
        val invalidEnabled = registry.validate(
            ToolRequest(
                id = "periodic-check-invalid-enabled",
                toolName = MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
                arguments = mapOf("enabled" to "yes"),
                reason = "schema contract",
            ),
        )
        assertNotNull(invalidEnabled)
        requireNotNull(invalidEnabled)
        assertEquals(ToolStatus.Rejected, invalidEnabled.status)
        assertTrue(invalidEnabled.summary.contains("true or false"))

        val invalidInterval = registry.validate(
            ToolRequest(
                id = "periodic-check-invalid-interval",
                toolName = MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
                arguments = mapOf("enabled" to "true", "intervalMinutes" to "30"),
                reason = "schema contract",
            ),
        )
        assertNotNull(invalidInterval)
        requireNotNull(invalidInterval)
        assertEquals(ToolStatus.Rejected, invalidInterval.status)
        assertTrue(invalidInterval.summary.contains("at least 60"))

        assertNull(
            registry.validate(
                ToolRequest(
                    id = "periodic-check-valid-disable",
                    toolName = MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
                    arguments = mapOf("enabled" to "false"),
                    reason = "schema contract",
                ),
            ),
        )
    }

    @Test
    fun contactSchemaRejectsMissingQueryAndUnsupportedMaxCount() {
        val missingQuery = registry.validate(
            ToolRequest(
                id = "contacts-missing-query",
                toolName = MobileActionFunctions.QUERY_CONTACTS,
                arguments = mapOf("maxCount" to "2"),
                reason = "schema contract",
            ),
        )
        assertNotNull(missingQuery)
        requireNotNull(missingQuery)
        assertEquals(ToolStatus.Rejected, missingQuery.status)
        assertTrue(missingQuery.summary.contains("requires argument"))

        val tooMany = registry.validate(
            ToolRequest(
                id = "contacts-too-many",
                toolName = MobileActionFunctions.QUERY_CONTACTS,
                arguments = mapOf("query" to "Alice", "maxCount" to "21"),
                reason = "schema contract",
            ),
        )
        assertNotNull(tooMany)
        requireNotNull(tooMany)
        assertEquals(ToolStatus.Rejected, tooMany.status)
        assertTrue(tooMany.summary.contains("at most 20"))
    }

    @Test
    fun allToolInputSchemasAreParseableAndClosed() {
        registry.specs().forEach { spec ->
            assertTrue("${spec.name} schema should declare object type", spec.inputSchemaJson.contains("\"object\""))
            assertTrue(
                "${spec.name} schema should reject undeclared arguments",
                spec.inputSchemaJson.contains("\"additionalProperties\": false"),
            )
        }
    }

    @Test
    fun privateDeviceReadToolsMustRequireConfirmation() {
        val privateReadPermissions = setOf(
            ToolPermission.ReadsClipboard,
            ToolPermission.ReadsContacts,
            ToolPermission.ReadsFiles,
            ToolPermission.ReadsCalendar,
            ToolPermission.ReadsAccessibilityText,
            ToolPermission.RequiresMediaProjectionConsent,
            ToolPermission.ReadsDeviceContext,
        )
        val privateReadSpecs = registry.specs()
            .filter { spec -> spec.permissions.any { permission -> permission in privateReadPermissions } }

        assertTrue(privateReadSpecs.isNotEmpty())
        privateReadSpecs.forEach { spec ->
            assertEquals(
                "${spec.name} reads private device data and must require confirmation",
                ConfirmationPolicy.Required,
                spec.confirmationPolicy,
            )
        }
    }

    @Test
    fun privateToolOutputsAreDeclaredByToolPolicy() {
        val recentImageOcrPrivateKeys = setOf("name", "mimeType", "sizeBytes", "lastModifiedMillis", "ocrText")
        val expectedPrivateOutputs = mapOf(
            MobileActionFunctions.READ_CLIPBOARD to setOf("text"),
            MobileActionFunctions.QUERY_CONTACTS to setOf("query", "contactCount", "contactsJson"),
            MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY to
                setOf("start", "end", "busyBlockCount", "freeBlockCount", "blocksJson"),
            MobileActionFunctions.QUERY_FOREGROUND_APP to setOf("packageName", "appLabel", "lastTimeUsedMillis"),
            MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS to setOf("notificationCount", "notificationsJson"),
            MobileActionFunctions.QUERY_RECENT_FILES to setOf("fileCount", "filesJson"),
            MobileActionFunctions.QUERY_BACKGROUND_TASKS to
                setOf("activeTaskCount", "historyTaskCount", "tasksJson", "policyJson"),
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR to recentImageOcrPrivateKeys,
            MobileActionFunctions.READ_RECENT_IMAGE_OCR to recentImageOcrPrivateKeys,
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT to
                setOf("capturedAtMillis", "nodeCount", "screenText", "packageName", "structureSummary"),
            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR to setOf("ocrText"),
        )

        expectedPrivateOutputs.forEach { (toolName, privateKeys) ->
            val spec = registry.specFor(toolName)
            assertNotNull(spec)
            requireNotNull(spec)
            assertEquals(privateKeys, spec.privateOutputKeys)
            assertEquals(privateKeys, registry.privateOutputKeysFor(toolName))
            assertNotNull(spec.redactedResultSummary)
            assertEquals(ConfirmationPolicy.Required, spec.confirmationPolicy)
        }

        val shareSpec = registry.specFor(MobileActionFunctions.SHARE_TEXT)
        assertNotNull(shareSpec)
        requireNotNull(shareSpec)
        assertTrue(shareSpec.privateOutputKeys.isEmpty())
        assertNull(registry.redactedResultSummaryFor(MobileActionFunctions.SHARE_TEXT))
    }

    @Test
    fun pendingArgumentAllowlistsAreDeclaredByToolPolicy() {
        val expectedAllowlists = mapOf(
            MobileActionFunctions.OPEN_APP_INTENT to setOf("packageName"),
            MobileActionFunctions.OPEN_APP_DEEP_TARGET to setOf("targetId", "packageName"),
            MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY to setOf("start", "end"),
            MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS to setOf("maxCount"),
            MobileActionFunctions.QUERY_RECENT_FILES to setOf("kind", "maxCount"),
            MobileActionFunctions.QUERY_BACKGROUND_TASKS to setOf("scope", "maxCount"),
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR to setOf("maxCount"),
            MobileActionFunctions.READ_RECENT_IMAGE_OCR to setOf("maxCount"),
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT to setOf("maxChars"),
            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR to setOf("captureMode"),
            MobileActionFunctions.CANCEL_REMINDER to setOf("taskId"),
            MobileActionFunctions.CONFIGURE_PERIODIC_CHECK to setOf(
                "enabled",
                "intervalMinutes",
                "minNotificationSpacingMinutes",
                "overdueGraceMinutes",
                "requiresBatteryNotLow",
                "requiresCharging",
            ),
        )

        expectedAllowlists.forEach { (toolName, allowlist) ->
            assertEquals(allowlist, registry.specFor(toolName)?.pendingArgumentAllowlist)
            assertEquals(allowlist, registry.pendingArgumentAllowlistFor(toolName))
        }

        val payloadBearingTools = setOf(
            MobileActionFunctions.SEARCH_MAPS,
            MobileActionFunctions.WEB_SEARCH,
            MobileActionFunctions.COMPOSE_EMAIL,
            MobileActionFunctions.CREATE_CALENDAR_EVENT,
            MobileActionFunctions.CREATE_CONTACT_DRAFT,
            MobileActionFunctions.QUERY_CONTACTS,
            MobileActionFunctions.SCHEDULE_REMINDER,
            MobileActionFunctions.SHARE_TEXT,
            MobileActionFunctions.OPEN_DEEP_LINK,
        )
        payloadBearingTools.forEach { toolName ->
            assertTrue(registry.pendingArgumentAllowlistFor(toolName).isEmpty())
        }
    }

    @Test
    fun allToolSpecsDeclareClosedOutputSchemas() {
        registry.specs().forEach { spec ->
            val schema = JSONObject(spec.outputSchemaJson)
            val properties = schema.optJSONObject("properties") ?: JSONObject()

            assertEquals("${spec.name} output schema must be an object", "object", schema.getString("type"))
            assertFalse("${spec.name} output schema must reject undeclared fields", schema.optBoolean("additionalProperties", true))
            assertTrue("${spec.name} output schema must declare toolName", properties.has("toolName"))
        }
    }

    @Test
    fun localOnlyDeviceContextOutputsRequireLocalModelMetadata() {
        val localOnlyDeviceTools = listOf(
            MobileActionFunctions.READ_CLIPBOARD,
            MobileActionFunctions.QUERY_CONTACTS,
            MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
            MobileActionFunctions.QUERY_FOREGROUND_APP,
            MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
            MobileActionFunctions.QUERY_RECENT_FILES,
            MobileActionFunctions.QUERY_BACKGROUND_TASKS,
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
        )

        localOnlyDeviceTools.forEach { toolName ->
            val spec = registry.specFor(toolName)
            assertNotNull(spec)
            requireNotNull(spec)

            val schema = JSONObject(spec.outputSchemaJson)
            val required = schema.optJSONArray("required")
            val properties = schema.getJSONObject("properties")

            assertTrue("$toolName output must require privacy", required.containsString("privacy"))
            assertTrue("$toolName output must require requiresLocalModel", required.containsString("requiresLocalModel"))
            assertEquals("boolean", properties.getJSONObject("requiresLocalModel").getString("type"))
            assertTrue(
                "$toolName privacy must be LocalOnly",
                properties.getJSONObject("privacy").getJSONArray("enum").containsString(MessagePrivacy.LocalOnly.name),
            )
        }
    }

    @Test
    fun publicEvidenceOutputSchemasRequireRemotePrivacyDeclaration() {
        val publicEvidenceTools = registry.specs()
            .filter { spec -> spec.resultContinuationPolicy == ToolResultContinuationPolicy.PublicEvidence }

        assertEquals(listOf(MobileActionFunctions.WEB_SEARCH), publicEvidenceTools.map { spec -> spec.name })
        publicEvidenceTools.forEach { spec ->
            val schema = JSONObject(spec.outputSchemaJson)
            val required = schema.optJSONArray("required")
            val properties = schema.getJSONObject("properties")

            assertTrue("${spec.name} output must require privacy", required.containsString("privacy"))
            assertTrue("${spec.name} output must require requiresLocalModel", required.containsString("requiresLocalModel"))
            assertEquals("boolean", properties.getJSONObject("requiresLocalModel").getString("type"))
            assertTrue(
                "${spec.name} privacy must be RemoteEligible",
                properties.getJSONObject("privacy").getJSONArray("enum").containsString(MessagePrivacy.RemoteEligible.name),
            )
        }
    }

    @Test
    fun currentScreenTextSchemaLocksAccessibilitySourceAndMetadataPolicy() {
        val spec = registry.specFor(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT)
        assertNotNull(spec)
        requireNotNull(spec)

        assertEquals("读取当前屏幕 Accessibility 可访问文本快照", spec.title)
        assertTrue(spec.description.contains("Accessibility 可访问文本快照"))
        assertTrue(spec.description.contains("不是截图"))
        assertTrue(spec.description.contains("OCR"))
        assertTrue(spec.description.contains("视觉/VLM"))
        assertTrue(spec.description.contains("语义屏幕理解"))

        val inputMaxChars = JSONObject(spec.inputSchemaJson)
            .getJSONObject("properties")
            .getJSONObject("maxChars")
        assertTrue(inputMaxChars.getString("description").contains("Accessibility 可访问文本快照"))
        assertTrue(inputMaxChars.getString("description").contains("不是截图"))

        val outputProperties = JSONObject(spec.outputSchemaJson).getJSONObject("properties")
        val sourceEnum = outputProperties.getJSONObject("source").getJSONArray("enum")
        assertEquals(1, sourceEnum.length())
        assertTrue(sourceEnum.containsString("accessibility_active_window"))
        assertFalse(sourceEnum.containsString("screenshot"))
        assertTrue(outputProperties.getJSONObject("source").getString("description").contains("never screenshot"))
        assertTrue(outputProperties.getJSONObject("structureSummary").getString("description").contains("Coarse"))
        assertTrue(outputProperties.getJSONObject("structureSummary").getString("description").contains("no node ids"))

        val metadataPolicyEnum = outputProperties.getJSONObject("metadataPolicy").getJSONArray("enum")
        assertEquals(1, metadataPolicyEnum.length())
        assertTrue(
            metadataPolicyEnum.containsString(
                "accessibility_text_local_only_no_node_ids_bounds_or_hierarchy_persisted",
            ),
        )

        val request = ToolRequest(
            id = "current-screen-output-contract",
            toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            reason = "schema contract",
        )
        val validData = mapOf(
            "toolName" to MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            "privacy" to MessagePrivacy.LocalOnly.name,
            "requiresLocalModel" to "true",
            "source" to "accessibility_active_window",
            "maxChars" to "1200",
            "capturedAtMillis" to "1000",
            "nodeCount" to "3",
            "screenText" to "当前屏幕可访问文本",
            "packageName" to "com.example.app",
            "truncated" to "false",
            "screenTextIncluded" to "true",
            "structureSummary" to "nodeCount=3; visibleTextItemCount=2; textSnapshotIncluded=true",
            "structureSummaryIncluded" to "true",
            "rawTreeIncluded" to "false",
            "metadataPolicy" to "accessibility_text_local_only_no_node_ids_bounds_or_hierarchy_persisted",
        )

        assertNull(
            registry.validateResult(
                request = request,
                result = request.succeeded(summary = "read current Accessibility text", data = validData),
            ),
        )
        assertInvalidResult(
            registry.validateResult(
                request = request,
                result = request.succeeded(
                    summary = "wrong current screen source",
                    data = validData + ("source" to "screenshot"),
                ),
            ),
            "source",
        )
        assertInvalidResult(
            registry.validateResult(
                request = request,
                result = request.succeeded(
                    summary = "wrong current screen metadata policy",
                    data = validData + ("metadataPolicy" to "ocr_text_local_only_no_uri_path_or_pixels_persisted"),
                ),
            ),
            "metadataPolicy",
        )
    }

    @Test
    fun reminderOutputSchemasExposeOnlyTaskRecoveryMetadata() {
        val forbiddenKeys = listOf("title", "body", "prompt", "summary", "text")
        listOf(
            MobileActionFunctions.SCHEDULE_REMINDER,
            MobileActionFunctions.QUERY_BACKGROUND_TASKS,
            MobileActionFunctions.CANCEL_REMINDER,
        ).forEach { toolName ->
            val spec = registry.specFor(toolName)
            assertNotNull(spec)
            requireNotNull(spec)
            val properties = JSONObject(spec.outputSchemaJson).getJSONObject("properties")

            forbiddenKeys.forEach { key ->
                assertFalse("$toolName output schema must not expose $key", properties.has(key))
            }
        }
    }

    @Test
    fun privateDeviceOutputKeysRemainDeclaredInOutputSchemas() {
        val recentImageOcrPrivateKeys = setOf("name", "mimeType", "sizeBytes", "lastModifiedMillis", "ocrText")
        val expectedPrivateOutputs = mapOf(
            MobileActionFunctions.READ_CLIPBOARD to setOf("text"),
            MobileActionFunctions.QUERY_CONTACTS to setOf("query", "contactCount", "contactsJson"),
            MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY to
                setOf("start", "end", "busyBlockCount", "freeBlockCount", "blocksJson"),
            MobileActionFunctions.QUERY_FOREGROUND_APP to setOf("packageName", "appLabel", "lastTimeUsedMillis"),
            MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS to setOf("notificationCount", "notificationsJson"),
            MobileActionFunctions.QUERY_RECENT_FILES to setOf("fileCount", "filesJson"),
            MobileActionFunctions.QUERY_BACKGROUND_TASKS to
                setOf("activeTaskCount", "historyTaskCount", "tasksJson", "policyJson"),
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR to recentImageOcrPrivateKeys,
            MobileActionFunctions.READ_RECENT_IMAGE_OCR to recentImageOcrPrivateKeys,
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT to
                setOf("capturedAtMillis", "nodeCount", "screenText", "packageName", "structureSummary"),
            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR to setOf("ocrText"),
        )

        expectedPrivateOutputs.forEach { (toolName, privateKeys) ->
            val spec = registry.specFor(toolName)
            assertNotNull(spec)
            requireNotNull(spec)

            val properties = JSONObject(spec.outputSchemaJson).getJSONObject("properties")
            privateKeys.forEach { privateKey ->
                assertTrue("$toolName private output key $privateKey must be represented in output schema", properties.has(privateKey))
            }
            assertEquals(privateKeys, spec.privateOutputKeys)
            assertEquals(privateKeys, registry.privateOutputKeysFor(toolName))
        }
    }

    @Test
    fun externalActivityOutputRequiresClosedOutcomeMetadata() {
        val spec = registry.specFor(MobileActionFunctions.OPEN_DEEP_LINK)
        assertNotNull(spec)
        requireNotNull(spec)
        val properties = JSONObject(spec.outputSchemaJson).getJSONObject("properties")
        assertTrue(properties.getJSONObject("externalOutcome").getJSONArray("enum").containsString("Unknown"))
        assertTrue(properties.getJSONObject("externalOutcome").getJSONArray("enum").containsString("Completed"))
        assertTrue(properties.getJSONObject("externalOutcome").getJSONArray("enum").containsString("NotCompleted"))
        assertTrue(properties.getJSONObject("externalOutcome").getJSONArray("enum").containsString("OpenedOnly"))
        assertTrue(properties.getJSONObject("externalOutcomeSource").getJSONArray("enum").containsString("Unknown"))
        assertTrue(properties.getJSONObject("externalOutcomeSource").getJSONArray("enum").containsString("UserConfirmed"))

        val request = ToolRequest(
            id = "external-output-contract",
            toolName = MobileActionFunctions.OPEN_DEEP_LINK,
            arguments = mapOf("uri" to "https://example.com/search?q=Kotlin"),
            reason = "schema contract",
        )
        val launchOnlyData = externalActivityOutputData(request.toolName)
        assertNull(
            registry.validateResult(
                request = request,
                result = request.succeeded(summary = "opened search", data = launchOnlyData),
            ),
        )
        assertNull(
            registry.validateResult(
                request = request,
                result = request.succeeded(
                    summary = "completed search",
                    data = launchOnlyData + mapOf(
                        "completionVerified" to "true",
                        "externalOutcome" to "Completed",
                        "externalOutcomeSource" to "UserConfirmed",
                    ),
                ),
            ),
        )
        assertInvalidResult(
            registry.validateResult(
                request = request,
                result = request.succeeded(
                    summary = "invalid unknown verified",
                    data = launchOnlyData + ("completionVerified" to "true"),
                ),
            ),
            "externalOutcome",
        )
        assertInvalidResult(
            registry.validateResult(
                request = request,
                result = request.succeeded(
                    summary = "invalid completed source",
                    data = launchOnlyData + mapOf(
                        "completionVerified" to "true",
                        "externalOutcome" to "Completed",
                        "externalOutcomeSource" to "Unknown",
                    ),
                ),
            ),
            "externalOutcome",
        )
        assertInvalidResult(
            registry.validateResult(
                request = request,
                result = request.succeeded(
                    summary = "invalid outcome enum",
                    data = launchOnlyData + ("externalOutcome" to "Opened"),
                ),
            ),
            "externalOutcome",
        )
    }

    @Test
    fun validateResultRejectsMissingOrWrongSuccessOutputData() {
        val request = ToolRequest(
            id = "clipboard-output-contract",
            toolName = MobileActionFunctions.READ_CLIPBOARD,
            reason = "schema contract",
        )

        assertNull(
            registry.validateResult(
                request = request,
                result = request.succeeded(
                    summary = "read clipboard",
                    data = mapOf(
                        "toolName" to request.toolName,
                        "privacy" to MessagePrivacy.LocalOnly.name,
                        "requiresLocalModel" to "true",
                        "text" to "clipboard text",
                        "truncated" to "false",
                    ),
                ),
            ),
        )

        val missingPrivateOutput = registry.validateResult(
            request = request,
            result = request.succeeded(
                summary = "read clipboard",
                data = mapOf(
                    "toolName" to request.toolName,
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to "true",
                    "truncated" to "false",
                ),
            ),
        )
        assertNotNull(missingPrivateOutput)
        requireNotNull(missingPrivateOutput)
        assertEquals(ToolStatus.Failed, missingPrivateOutput.status)
        assertEquals(ToolErrorCode.InvalidResult, missingPrivateOutput.error?.code)
        assertFalse(missingPrivateOutput.retryable)
        assertTrue(missingPrivateOutput.summary.contains("output") || missingPrivateOutput.summary.contains("result"))
        assertTrue(missingPrivateOutput.summary.contains("text"))

        val wrongOutputType = registry.validateResult(
            request = request,
            result = request.succeeded(
                summary = "read clipboard",
                data = mapOf(
                    "toolName" to request.toolName,
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to "true",
                    "text" to "clipboard text",
                    "truncated" to "maybe",
                ),
            ),
        )
        assertNotNull(wrongOutputType)
        requireNotNull(wrongOutputType)
        assertEquals(ToolStatus.Failed, wrongOutputType.status)
        assertEquals(ToolErrorCode.InvalidResult, wrongOutputType.error?.code)
        assertFalse(wrongOutputType.retryable)
        assertTrue(wrongOutputType.summary.contains("truncated"))
        assertTrue(wrongOutputType.summary.contains("true or false"))
    }

    @Test
    fun validateResultAcceptsFreeDuckDuckGoPageSearchSources() {
        val request = ToolRequest(
            id = "web-search-output-contract",
            toolName = MobileActionFunctions.WEB_SEARCH,
            reason = "schema contract",
        )

        listOf("duckduckgo_html", "duckduckgo_lite").forEach { source ->
            val validated = registry.validateResult(
                request = request,
                result = request.succeeded(
                    summary = "web search",
                    data = mapOf(
                        "toolName" to MobileActionFunctions.WEB_SEARCH,
                        "privacy" to MessagePrivacy.RemoteEligible.name,
                        "requiresLocalModel" to "false",
                        "query" to "AI model ranking",
                        "source" to source,
                        "searchMode" to "general",
                        "retrievedAt" to "2026-06-13T12:00:00Z",
                        "freshness" to "any_time",
                        "maxResults" to "3",
                        "summaryText" to "AI model ranking: current roundup",
                        "resultsJson" to """{"kind":"web_search_evidence","results":[{"url":"https://example.com"}]}""",
                    ),
                ),
            )

            assertNull(validated)
        }
    }

    @Test
    fun validateResultRejectsInvalidJsonContentMediaType() {
        val request = ToolRequest(
            id = "web-search-output-contract",
            toolName = MobileActionFunctions.WEB_SEARCH,
            reason = "schema contract",
        )

        val invalidJson = registry.validateResult(
            request = request,
            result = request.succeeded(
                summary = "web search",
                data = mapOf(
                    "toolName" to MobileActionFunctions.WEB_SEARCH,
                    "privacy" to MessagePrivacy.RemoteEligible.name,
                    "requiresLocalModel" to "false",
                    "query" to "北京天气",
                    "source" to "duckduckgo",
                    "summaryText" to "北京天气摘要",
                    "resultsJson" to "[{\"title\":\"weather\"}",
                ),
            ),
        )

        assertInvalidResult(invalidJson, "resultsJson")
        assertTrue(invalidJson?.summary.orEmpty().contains("valid JSON"))
    }

    @Test
    fun validateResultRejectsPrivateOutputWithRequiresLocalModelFalse() {
        val request = ToolRequest(
            id = "clipboard-local-model-boundary",
            toolName = MobileActionFunctions.READ_CLIPBOARD,
            reason = "schema contract",
        )
        val validData = mapOf(
            "toolName" to request.toolName,
            "privacy" to MessagePrivacy.LocalOnly.name,
            "requiresLocalModel" to "true",
            "text" to "clipboard text",
            "truncated" to "false",
        )

        assertNull(
            registry.validateResult(
                request = request,
                result = request.succeeded(summary = "read clipboard", data = validData),
            ),
        )

        assertInvalidResult(
            registry.validateResult(
                request = request,
                result = request.succeeded(
                    summary = "read clipboard without local model boundary",
                    data = validData + ("requiresLocalModel" to "false"),
                ),
            ),
            "requiresLocalModel=true",
        )
        val invalid = registry.validateResult(
            request = request,
            result = request.succeeded(
                summary = "read clipboard without local model boundary",
                data = validData + ("requiresLocalModel" to "false"),
            ),
        )
        requireNotNull(invalid)
        assertEquals(MessagePrivacy.LocalOnly.name, invalid.data["privacy"])
        assertEquals(true.toString(), invalid.data["requiresLocalModel"])
    }

    @Test
    fun validateResultSanitizesSensitiveNonSucceededResultData() {
        val request = ToolRequest(
            id = "foreground-failed-output-contract",
            toolName = MobileActionFunctions.QUERY_FOREGROUND_APP,
            reason = "schema contract",
        )

        val sanitized = registry.validateResult(
            request = request,
            result = ToolResult(
                requestId = "unexpected-request",
                status = ToolStatus.Failed,
                summary = "foreground failed with com.example.private",
                data = mapOf(
                    "toolName" to request.toolName,
                    "privacy" to "Remote",
                    "requiresLocalModel" to "false",
                    "packageName" to "com.example.private",
                    "failureKind" to "permission",
                    "debugPayload" to "raw private detail",
                    "specialAccess" to "usage_stats",
                    "settingsAction" to "android.settings.USAGE_ACCESS_SETTINGS",
                    "recoveryToolName" to MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
                ),
                error = ToolError(ToolErrorCode.PermissionDenied, "foreground failed with com.example.private"),
                retryable = true,
            ),
        )

        assertNotNull(sanitized)
        requireNotNull(sanitized)
        assertEquals(request.id, sanitized.requestId)
        assertEquals(ToolStatus.Failed, sanitized.status)
        assertEquals(ToolErrorCode.PermissionDenied, sanitized.error?.code)
        assertTrue(sanitized.retryable)
        assertEquals(request.toolName, sanitized.data["toolName"])
        assertEquals(MessagePrivacy.LocalOnly.name, sanitized.data["privacy"])
        assertEquals(true.toString(), sanitized.data["requiresLocalModel"])
        assertEquals("usage_stats", sanitized.data["specialAccess"])
        assertEquals("android.settings.USAGE_ACCESS_SETTINGS", sanitized.data["settingsAction"])
        assertEquals(MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS, sanitized.data["recoveryToolName"])
        assertFalse(sanitized.data.containsKey("packageName"))
        assertFalse(sanitized.data.containsKey("failureKind"))
        assertFalse(sanitized.data.containsKey("debugPayload"))
        assertFalse(sanitized.summary.contains("com.example.private"))
        assertFalse(sanitized.error?.message.orEmpty().contains("com.example.private"))
    }

    @Test
    fun validateResultRejectsUnsafeReminderRecoveryMetadata() {
        val request = ToolRequest(
            id = "reminder-output-contract",
            toolName = MobileActionFunctions.SCHEDULE_REMINDER,
            arguments = mapOf(
                "title" to "喝水",
                "delayMinutes" to "15",
            ),
            reason = "schema contract",
        )
        val validData = mapOf(
            "toolName" to MobileActionFunctions.SCHEDULE_REMINDER,
            "taskId" to "task-1",
            "taskStatus" to "Scheduled",
            "triggerAtMillis" to "10000",
            "recoveryToolName" to MobileActionFunctions.CANCEL_REMINDER,
            "recoveryTaskId" to "task-1",
        )

        assertNull(
            registry.validateResult(
                request = request,
                result = request.succeeded(summary = "scheduled", data = validData),
            ),
        )

        assertInvalidResult(
            registry.validateResult(
                request = request,
                result = request.succeeded(
                    summary = "wrong recovery tool",
                    data = validData + ("recoveryToolName" to MobileActionFunctions.SHARE_TEXT),
                ),
            ),
            "recoveryToolName",
        )
        assertInvalidResult(
            registry.validateResult(
                request = request,
                result = request.succeeded(
                    summary = "unsafe recovery task id raw=value",
                    data = validData + ("recoveryTaskId" to "raw=value"),
                ),
            ),
            "recoveryTaskId",
        )
        assertInvalidResult(
            registry.validateResult(
                request = request,
                result = request.succeeded(
                    summary = "wrong reminder task status",
                    data = validData + ("taskStatus" to "Delivered"),
                ),
            ),
            "taskStatus",
        )

        val cancelRequest = ToolRequest(
            id = "cancel-reminder-output-contract",
            toolName = MobileActionFunctions.CANCEL_REMINDER,
            arguments = mapOf("taskId" to "task-1"),
            reason = "schema contract",
        )
        val cancelData = mapOf(
            "toolName" to MobileActionFunctions.CANCEL_REMINDER,
            "taskId" to "task-1",
            "taskStatus" to "Cancelled",
        )
        assertNull(
            registry.validateResult(
                request = cancelRequest,
                result = cancelRequest.succeeded(summary = "cancelled", data = cancelData),
            ),
        )
        assertInvalidResult(
            registry.validateResult(
                request = cancelRequest,
                result = cancelRequest.succeeded(
                    summary = "unsafe cancel task id raw=value",
                    data = cancelData + ("taskId" to "raw=value"),
                ),
            ),
            "taskId",
        )
        assertInvalidResult(
            registry.validateResult(
                request = cancelRequest,
                result = cancelRequest.succeeded(
                    summary = "wrong cancel status",
                    data = cancelData + ("taskStatus" to "Scheduled"),
                ),
            ),
            "taskStatus",
        )
    }

    @Test
    fun validatesWebSearchQueryArgument() {
        val missingQuery = registry.validate(
            ToolRequest(
                id = "request-2",
                toolName = MobileActionFunctions.WEB_SEARCH,
                reason = "test",
            ),
        )
        assertNotNull(missingQuery)
        requireNotNull(missingQuery)
        assertEquals(ToolStatus.Rejected, missingQuery.status)
        assertTrue(missingQuery.summary.contains("query"))

        val blankQuery = registry.validate(
            ToolRequest(
                id = "request-3",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to " "),
                reason = "test",
            ),
        )
        assertNotNull(blankQuery)
        requireNotNull(blankQuery)
        assertEquals(ToolStatus.Rejected, blankQuery.status)
        assertTrue(blankQuery.summary.contains("query"))

        val valid = registry.validate(
            ToolRequest(
                id = "request-4",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf(
                    "query" to "Kotlin coroutines Android",
                    "searchMode" to "weather_current",
                    "freshness" to "current",
                    "maxResults" to "3",
                ),
                reason = "test",
            ),
        )
        assertNull(valid)

        val invalidSearchMode = registry.validate(
            ToolRequest(
                id = "request-5",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf(
                    "query" to "Kotlin coroutines Android",
                    "searchMode" to "local",
                ),
                reason = "test",
            ),
        )
        assertNotNull(invalidSearchMode)
        requireNotNull(invalidSearchMode)
        assertEquals(ToolStatus.Rejected, invalidSearchMode.status)
        assertTrue(invalidSearchMode.summary.contains("searchMode"))

        val invalidMaxResults = registry.validate(
            ToolRequest(
                id = "request-6",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf(
                    "query" to "Kotlin coroutines Android",
                    "maxResults" to "6",
                ),
                reason = "test",
            ),
        )
        assertNotNull(invalidMaxResults)
        requireNotNull(invalidMaxResults)
        assertEquals(ToolStatus.Rejected, invalidMaxResults.status)
        assertTrue(invalidMaxResults.summary.contains("maxResults"))
    }

    @Test
    fun rejectsUnknownArguments() {
        val rejection = registry.validate(
            ToolRequest(
                id = "request-extra",
                toolName = MobileActionFunctions.COMPOSE_EMAIL,
                arguments = mapOf(
                    "body" to "明天聊",
                    "sendNow" to "true",
                ),
                reason = "test",
            ),
        )

        assertNotNull(rejection)
        requireNotNull(rejection)
        assertEquals(ToolStatus.Rejected, rejection.status)
        assertTrue(rejection.summary.contains("sendNow"))
    }

    @Test
    fun validatesRequiredArgumentsForDraftTools() {
        val requiredArgumentsByTool = mapOf(
            MobileActionFunctions.COMPOSE_EMAIL to "body",
            MobileActionFunctions.CREATE_CALENDAR_EVENT to "title",
            MobileActionFunctions.CREATE_CONTACT_DRAFT to "name",
            MobileActionFunctions.SEARCH_MAPS to "query",
            MobileActionFunctions.WEB_SEARCH to "query",
            MobileActionFunctions.SCHEDULE_REMINDER to "title",
            MobileActionFunctions.CANCEL_REMINDER to "taskId",
            MobileActionFunctions.SHARE_TEXT to "text",
            MobileActionFunctions.OPEN_DEEP_LINK to "uri",
            MobileActionFunctions.OPEN_APP_INTENT to "packageName",
            MobileActionFunctions.OPEN_APP_DEEP_TARGET to "targetId",
        )

        requiredArgumentsByTool.forEach { (toolName, requiredArgument) ->
            val rejection = registry.validate(
                ToolRequest(
                    id = "request-$toolName",
                    toolName = toolName,
                    arguments = mapOf(requiredArgument to " "),
                    reason = "test",
                ),
            )

            assertNotNull("Expected blank $requiredArgument to reject $toolName", rejection)
            requireNotNull(rejection)
            assertEquals(ToolStatus.Rejected, rejection.status)
            assertTrue(rejection.summary.contains(requiredArgument))
        }
    }

    @Test
    fun acceptsOpenWifiSettingsWithoutArguments() {
        val rejection = registry.validate(
            ToolRequest(
                id = "request-5",
                toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                reason = "test",
            ),
        )

        assertNull(rejection)
    }

    @Test
    fun rejectsArgumentsDisallowedByEmptyObjectSchema() {
        val rejection = registry.validate(
            ToolRequest(
                id = "request-wifi-extra",
                toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                arguments = mapOf("enabled" to "true"),
                reason = "test",
            ),
        )

        assertNotNull(rejection)
        requireNotNull(rejection)
        assertEquals(ToolStatus.Rejected, rejection.status)
        assertTrue(rejection.summary.contains("enabled"))
    }

    @Test
    fun validatesDeepLinkAndAppIntentPatterns() {
        val unsafeDeepLink = registry.validate(
            ToolRequest(
                id = "request-unsafe-deep-link",
                toolName = MobileActionFunctions.OPEN_DEEP_LINK,
                arguments = mapOf("uri" to "http://example.com"),
                reason = "test",
            ),
        )
        assertNotNull(unsafeDeepLink)
        requireNotNull(unsafeDeepLink)
        assertTrue(unsafeDeepLink.summary.contains("uri"))

        val invalidPackage = registry.validate(
            ToolRequest(
                id = "request-invalid-package",
                toolName = MobileActionFunctions.OPEN_APP_INTENT,
                arguments = mapOf("packageName" to "not a package"),
                reason = "test",
            ),
        )
        assertNotNull(invalidPackage)
        requireNotNull(invalidPackage)
        assertTrue(invalidPackage.summary.contains("packageName"))

        val invalidData = registry.validate(
            ToolRequest(
                id = "request-invalid-intent-data",
                toolName = MobileActionFunctions.OPEN_APP_INTENT,
                arguments = mapOf(
                    "packageName" to "com.example.app",
                    "data" to "file:///sdcard/private.txt",
                ),
                reason = "test",
            ),
        )
        assertNotNull(invalidData)
        requireNotNull(invalidData)
        assertTrue(invalidData.summary.contains("data"))

        val invalidTarget = registry.validate(
            ToolRequest(
                id = "request-invalid-intent-target",
                toolName = MobileActionFunctions.OPEN_APP_DEEP_TARGET,
                arguments = mapOf(
                    "targetId" to "arbitrary_activity",
                    "packageName" to "com.example.app",
                ),
                reason = "test",
            ),
        )
        assertNotNull(invalidTarget)
        requireNotNull(invalidTarget)
        assertTrue(invalidTarget.summary.contains("target"))

        val invalidTargetPackage = registry.validate(
            ToolRequest(
                id = "request-invalid-intent-target-package",
                toolName = MobileActionFunctions.OPEN_APP_DEEP_TARGET,
                arguments = mapOf(
                    "targetId" to AppDeepTargets.APP_DETAILS_SETTINGS_ID,
                    "packageName" to "not a package",
                ),
                reason = "test",
            ),
        )
        assertNotNull(invalidTargetPackage)
        requireNotNull(invalidTargetPackage)
        assertTrue(invalidTargetPackage.summary.contains("packageName"))

        val invalidTargetExtra = registry.validate(
            ToolRequest(
                id = "request-invalid-intent-target-extra",
                toolName = MobileActionFunctions.OPEN_APP_DEEP_TARGET,
                arguments = mapOf(
                    "targetId" to AppDeepTargets.APP_DETAILS_SETTINGS_ID,
                    "packageName" to "com.example.app",
                    "uri" to "package:com.example.app/private",
                ),
                reason = "test",
            ),
        )
        assertNotNull(invalidTargetExtra)
        requireNotNull(invalidTargetExtra)
        assertTrue(invalidTargetExtra.summary.contains("uri"))

        val validAppTarget = registry.validate(
            ToolRequest(
                id = "request-app-details-target",
                toolName = MobileActionFunctions.OPEN_APP_DEEP_TARGET,
                arguments = mapOf(
                    "targetId" to AppDeepTargets.APP_DETAILS_SETTINGS_ID,
                    "packageName" to "com.example.app",
                ),
                reason = "test",
            ),
        )
        assertNull(validAppTarget)
    }

    @Test
    fun validatesReminderDelayMinutesAsPositiveInteger() {
        val invalid = registry.validate(
            ToolRequest(
                id = "request-reminder-invalid",
                toolName = MobileActionFunctions.SCHEDULE_REMINDER,
                arguments = mapOf(
                    "title" to "喝水",
                    "delayMinutes" to "0",
                ),
                reason = "test",
            ),
        )
        assertNotNull(invalid)
        requireNotNull(invalid)
        assertTrue(invalid.summary.contains("delayMinutes"))

        val nonInteger = registry.validate(
            ToolRequest(
                id = "request-reminder-non-integer",
                toolName = MobileActionFunctions.SCHEDULE_REMINDER,
                arguments = mapOf(
                    "title" to "喝水",
                    "delayMinutes" to "1.5",
                ),
                reason = "test",
            ),
        )
        assertNotNull(nonInteger)
        requireNotNull(nonInteger)
        assertTrue(nonInteger.summary.contains("delayMinutes"))

        val valid = registry.validate(
            ToolRequest(
                id = "request-reminder-valid",
                toolName = MobileActionFunctions.SCHEDULE_REMINDER,
                arguments = mapOf(
                    "title" to "喝水",
                    "body" to "提醒我喝水",
                    "delayMinutes" to "15",
                ),
                reason = "test",
            ),
        )
        assertNull(valid)
    }

    @Test
    fun validatesCancelReminderTaskId() {
        val blank = registry.validate(
            ToolRequest(
                id = "request-cancel-reminder-blank",
                toolName = MobileActionFunctions.CANCEL_REMINDER,
                arguments = mapOf("taskId" to " "),
                reason = "test",
            ),
        )
        assertNotNull(blank)
        requireNotNull(blank)
        assertTrue(blank.summary.contains("taskId"))

        val invalidPrefix = registry.validate(
            ToolRequest(
                id = "request-cancel-reminder-invalid-prefix",
                toolName = MobileActionFunctions.CANCEL_REMINDER,
                arguments = mapOf("taskId" to "abc-1"),
                reason = "test",
            ),
        )
        assertNotNull(invalidPrefix)
        requireNotNull(invalidPrefix)
        assertTrue(invalidPrefix.summary.contains("pattern"))

        val valid = registry.validate(
            ToolRequest(
                id = "request-cancel-reminder-valid",
                toolName = MobileActionFunctions.CANCEL_REMINDER,
                arguments = mapOf("taskId" to "task-1"),
                reason = "test",
            ),
        )
        assertNull(valid)
    }

    @Test
    fun acceptsReadClipboardWithoutArguments() {
        val rejection = registry.validate(
            ToolRequest(
                id = "request-clipboard",
                toolName = MobileActionFunctions.READ_CLIPBOARD,
                reason = "test",
            ),
        )

        assertNull(rejection)
    }

    @Test
    fun validatesCalendarAvailabilityStartAndEndArguments() {
        val missingEnd = registry.validate(
            ToolRequest(
                id = "request-calendar-missing",
                toolName = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
                arguments = mapOf("start" to "2026-06-01T09:00:00Z"),
                reason = "test",
            ),
        )
        assertNotNull(missingEnd)
        requireNotNull(missingEnd)
        assertEquals(ToolStatus.Rejected, missingEnd.status)
        assertTrue(missingEnd.summary.contains("end"))

        val invalidStart = registry.validate(
            ToolRequest(
                id = "request-calendar-invalid-start",
                toolName = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
                arguments = mapOf(
                    "start" to "tomorrow morning",
                    "end" to "2026-06-01T10:00:00Z",
                ),
                reason = "test",
            ),
        )
        assertNotNull(invalidStart)
        requireNotNull(invalidStart)
        assertEquals(ToolStatus.Rejected, invalidStart.status)
        assertTrue(invalidStart.summary.contains("start"))
        assertTrue(invalidStart.summary.contains("date-time"))

        val valid = registry.validate(
            ToolRequest(
                id = "request-calendar-valid",
                toolName = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
                arguments = mapOf(
                    "start" to "2026-06-01T09:00:00Z",
                    "end" to "2026-06-01T10:00:00Z",
                ),
                reason = "test",
            ),
        )

        assertNull(valid)
    }

    private fun externalActivityOutputData(toolName: String): Map<String, String> =
        mapOf(
            "toolName" to toolName,
            "completionState" to "ExternalActivityOpened",
            "completionVerified" to "false",
            "externalOutcome" to "Unknown",
            "externalOutcomeSource" to "Unknown",
            "targetKind" to "HttpsUri",
            "intentAction" to "android.intent.action.VIEW",
            "metadataPolicy" to "AllowlistedCompletionMetadata",
            "rawPayloadIncluded" to "false",
        )

    private fun assertInvalidResult(result: ToolResult?, expectedField: String) {
        assertNotNull(result)
        requireNotNull(result)
        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.InvalidResult, result.error?.code)
        assertFalse(result.retryable)
        assertTrue(result.summary.contains(expectedField))
    }
}

private fun JSONArray?.containsString(value: String): Boolean {
    if (this == null) return false
    for (index in 0 until length()) {
        if (optString(index) == value) return true
    }
    return false
}
