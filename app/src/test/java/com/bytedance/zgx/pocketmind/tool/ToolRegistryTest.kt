package com.bytedance.zgx.pocketmind.tool

import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.action.AppDeepTargets
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
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
        assertTrue(ToolPermission.StartsExternalActivity in webSearchSpec.permissions)
        assertTrue(webSearchSpec.inputSchemaJson.contains("query"))

        val reminderSpec = registry.specFor(MobileActionFunctions.SCHEDULE_REMINDER)
        assertNotNull(reminderSpec)
        requireNotNull(reminderSpec)
        assertEquals(ToolCapability.BackgroundTask, reminderSpec.capability)
        assertTrue(ToolPermission.SchedulesBackgroundWork in reminderSpec.permissions)
        assertTrue(ToolPermission.PostsNotification in reminderSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission in reminderSpec.permissions)

        val cancelReminderSpec = registry.specFor(MobileActionFunctions.CANCEL_REMINDER)
        assertNotNull(cancelReminderSpec)
        requireNotNull(cancelReminderSpec)
        assertEquals(ToolCapability.BackgroundTask, cancelReminderSpec.capability)
        assertEquals(ConfirmationPolicy.Required, cancelReminderSpec.confirmationPolicy)
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

        val calendarAvailabilitySpec = registry.specFor(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY)
        assertNotNull(calendarAvailabilitySpec)
        requireNotNull(calendarAvailabilitySpec)
        assertEquals(ToolCapability.DeviceContext, calendarAvailabilitySpec.capability)
        assertEquals(RiskLevel.LowReadOnly, calendarAvailabilitySpec.riskLevel)
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
        assertTrue(ToolPermission.ReadsDeviceContext in recentFilesSpec.permissions)
        assertTrue(ToolPermission.ReadsFiles in recentFilesSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission in recentFilesSpec.permissions)
        assertTrue(recentFilesSpec.inputSchemaJson.contains("\"kind\""))
        assertTrue(recentFilesSpec.inputSchemaJson.contains("\"maxCount\""))
        assertTrue(recentFilesSpec.inputSchemaJson.contains("\"screenshots\""))
        assertTrue(recentFilesSpec.inputSchemaJson.contains("\"documents\""))
        assertTrue(recentFilesSpec.inputSchemaJson.contains("系统文件选择器"))
        assertTrue(recentFilesSpec.inputSchemaJson.contains("已授权媒体"))
        assertTrue(recentFilesSpec.description.contains("Android 13"))
        assertTrue(recentFilesSpec.description.contains("系统文件选择器"))

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
        assertTrue(currentScreenTextSpec.description.contains("不读取截图"))

        val recentNotificationSpec = registry.specFor(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS)
        assertNotNull(recentNotificationSpec)
        requireNotNull(recentNotificationSpec)
        assertEquals(ToolCapability.DeviceContext, recentNotificationSpec.capability)
        assertTrue(ToolPermission.ReadsDeviceContext in recentNotificationSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission !in recentNotificationSpec.permissions)
        assertTrue(recentNotificationSpec.description.contains("当前应用"))
        assertTrue(recentNotificationSpec.inputSchemaJson.contains("\"maximum\": 20"))

        val foregroundAppSpec = registry.specFor(MobileActionFunctions.QUERY_FOREGROUND_APP)
        assertNotNull(foregroundAppSpec)
        requireNotNull(foregroundAppSpec)
        assertEquals(ToolCapability.DeviceContext, foregroundAppSpec.capability)
        assertTrue(ToolPermission.ReadsDeviceContext in foregroundAppSpec.permissions)
        assertTrue(ToolPermission.RequiresAndroidRuntimePermission !in foregroundAppSpec.permissions)

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
            MobileActionFunctions.QUERY_CONTACTS to setOf("query", "contactsJson"),
            MobileActionFunctions.QUERY_FOREGROUND_APP to setOf("packageName", "appLabel"),
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR to recentImageOcrPrivateKeys,
            MobileActionFunctions.READ_RECENT_IMAGE_OCR to recentImageOcrPrivateKeys,
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT to setOf("screenText"),
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
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR to setOf("maxCount"),
            MobileActionFunctions.READ_RECENT_IMAGE_OCR to setOf("maxCount"),
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT to setOf("maxChars"),
            MobileActionFunctions.CANCEL_REMINDER to setOf("taskId"),
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
    fun reminderOutputSchemasExposeOnlyTaskRecoveryMetadata() {
        val forbiddenKeys = listOf("title", "body", "prompt", "summary", "text")
        listOf(
            MobileActionFunctions.SCHEDULE_REMINDER,
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
            MobileActionFunctions.QUERY_CONTACTS to setOf("query", "contactsJson"),
            MobileActionFunctions.QUERY_FOREGROUND_APP to setOf("packageName", "appLabel"),
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR to recentImageOcrPrivateKeys,
            MobileActionFunctions.READ_RECENT_IMAGE_OCR to recentImageOcrPrivateKeys,
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT to setOf("screenText"),
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
                arguments = mapOf("query" to "Kotlin coroutines Android"),
                reason = "test",
            ),
        )
        assertNull(valid)
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

}
