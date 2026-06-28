package com.bytedance.zgx.solin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidManifestTest {
    @Test
    fun appDisplayNameCombinesChineseNameWithEnglishBrand() {
        val manifest = readManifest()

        assertTrue(manifest.contains("""android:label="@string/app_name""""))
        assertEquals(EXPECTED_APP_DISPLAY_NAME, readMainStringResource("values/strings.xml", "app_name"))
        assertEquals(EXPECTED_APP_DISPLAY_NAME, readMainStringResource("values-en/strings.xml", "app_name"))
    }

    @Test
    fun declaresUsageStatsPermissionForSpecialAccessSettings() {
        val manifest = readManifest()

        assertTrue(manifest.contains("""android.permission.PACKAGE_USAGE_STATS"""))
    }

    @Test
    fun declaresMicrophonePermissionForVoiceInput() {
        val manifest = readManifest()

        assertTrue(manifest.contains("""android.permission.RECORD_AUDIO"""))
    }

    @Test
    fun declaresAndroid14SelectedVisualMediaPermission() {
        val manifest = readManifest()

        assertTrue(manifest.contains("""android.permission.READ_MEDIA_IMAGES"""))
        assertTrue(manifest.contains("""android.permission.READ_MEDIA_VIDEO"""))
        assertTrue(manifest.contains("""android.permission.READ_MEDIA_VISUAL_USER_SELECTED"""))
    }

    @Test
    fun declaresAccessibilityServiceForScreenStateAndConfirmedGestures() {
        val manifest = readManifest()
        val serviceConfig = readMainFile("res/xml/solin_accessibility_service.xml")

        assertTrue(manifest.contains(""".device.SolinAccessibilityService"""))
        assertTrue(manifest.contains("""android.permission.BIND_ACCESSIBILITY_SERVICE"""))
        assertTrue(manifest.contains("""android.accessibilityservice.AccessibilityService"""))
        assertTrue(manifest.contains("""@xml/solin_accessibility_service"""))
        assertTrue(serviceConfig.contains("""android:canRetrieveWindowContent="true""""))
        assertTrue(serviceConfig.contains("""typeWindowStateChanged|typeWindowContentChanged|typeWindowsChanged"""))
        assertTrue(serviceConfig.contains("""android:canPerformGestures="true""""))
        assertTrue(!serviceConfig.contains("""canTakeScreenshot="true""""))
    }

    @Test
    fun declaresDeviceControlForegroundSessionService() {
        val manifest = readManifest()
        val service = manifest.serviceBlockFor(".device.DeviceControlSessionService")

        assertTrue(manifest.contains("""android.permission.FOREGROUND_SERVICE"""))
        assertTrue(manifest.contains("""android.permission.FOREGROUND_SERVICE_SPECIAL_USE"""))
        assertTrue(service.contains("""android:exported="false""""))
        assertTrue(service.contains("""android:foregroundServiceType="specialUse""""))
        assertTrue(service.contains("""user_confirmed_device_control_session"""))
    }

    @Test
    fun reminderRecoveryReceiverHandlesBootAndPackageReplacement() {
        val manifest = readManifest()
        val receiver = manifest.receiverBlockFor(".background.ReminderBootReceiver")

        assertTrue(receiver.contains("""android:exported="true""""))
        assertTrue(receiver.contains("""android.intent.action.BOOT_COMPLETED"""))
        assertTrue(receiver.contains("""android.intent.action.MY_PACKAGE_REPLACED"""))
    }

    @Test
    fun debugRemoteConfigReceiverIsNotExported() {
        val manifest = readDebugManifest()
        val receiver = manifest.receiverDeclarationFor(".debug.DebugRemoteConfigReceiver")

        assertTrue(receiver.contains("""android:exported="false""""))
        assertFalse(receiver.contains("""android:exported="true""""))
    }

    @Test
    fun debugDeviceControlEvalReceiverIsInternalAndUsesProprietaryAction() {
        val manifest = readDebugManifest()
        val receiver = manifest.receiverDeclarationFor(".debug.DeviceControlEvalReceiver")

        assertTrue(receiver.contains("""android:exported="false""""))
        assertFalse(receiver.contains("""android:exported="true""""))
        assertTrue(receiver.contains("""com.bytedance.zgx.solin.debug.DEVICE_CONTROL_EVAL"""))
    }

    @Test
    fun shareTargetsAcceptPickerSupportedDocumentMimeTypes() {
        val manifest = readManifest()
        val sendFilter = manifest.intentFilterFor("android.intent.action.SEND")
        val sendMultipleFilter = manifest.intentFilterFor("android.intent.action.SEND_MULTIPLE")

        for (mimeType in SHARED_ATTACHMENT_MIME_TYPES) {
            assertTrue("ACTION_SEND missing $mimeType", sendFilter.contains("android:mimeType=\"$mimeType\""))
            assertTrue(
                "ACTION_SEND_MULTIPLE missing $mimeType",
                sendMultipleFilter.contains("android:mimeType=\"$mimeType\""),
            )
        }
        assertFalse(sendFilter.contains("android:mimeType=\"application/ld+json\""))
        assertFalse(sendMultipleFilter.contains("android:mimeType=\"application/ld+json\""))
    }

    @Test
    fun composerAttachmentPickerUsesShareTargetMimeTypes() {
        val mainActivity = readMainFile("java/com/bytedance/zgx/solin/MainActivity.kt")
        val pickerMimeTypes = Regex("""SHARED_ATTACHMENT_MIME_TYPES = arrayOf\(([\s\S]*?)\)""")
            .find(mainActivity)
            ?.groupValues
            ?.get(1)
            ?.let { block ->
                Regex(""""([^"]+)"""")
                    .findAll(block)
                    .map { match -> match.groupValues[1] }
                    .toList()
            }
            ?: error("SHARED_ATTACHMENT_MIME_TYPES array not found in MainActivity")

        assertEquals(SHARED_ATTACHMENT_MIME_TYPES, pickerMimeTypes)
    }

    private fun readManifest(): String {
        return readMainFile("AndroidManifest.xml")
    }

    private fun readDebugManifest(): String {
        return readSourceFile("debug", "AndroidManifest.xml")
    }

    private fun readMainFile(relativePath: String): String {
        return readSourceFile("main", relativePath)
    }

    private fun readSourceFile(sourceSet: String, relativePath: String): String {
        val manifestFile = listOf(
            File("src/$sourceSet/$relativePath"),
            File("app/src/$sourceSet/$relativePath"),
        ).first { it.isFile }
        return manifestFile.readText()
    }

    private fun readMainStringResource(valuesPath: String, name: String): String {
        val stringsXml = readMainFile("res/$valuesPath")
        return Regex("""<string\s+name="$name">([^<]*)</string>""")
            .find(stringsXml)
            ?.groupValues
            ?.get(1)
            ?: error("String resource $name not found in $valuesPath")
    }

    private fun String.intentFilterFor(actionName: String): String =
        Regex("""<intent-filter>[\s\S]*?</intent-filter>""")
            .findAll(this)
            .map { it.value }
            .first { it.contains("android:name=\"$actionName\"") }

    private fun String.receiverBlockFor(receiverName: String): String =
        Regex("""<receiver[\s\S]*?</receiver>""")
            .findAll(this)
            .map { it.value }
            .first { it.contains("""android:name="$receiverName"""") }

    private fun String.receiverDeclarationFor(receiverName: String): String =
        Regex("""<receiver\b[\s\S]*?(?:/>|</receiver>)""")
            .findAll(this)
            .map { it.value }
            .first { it.contains("""android:name="$receiverName"""") }

    private fun String.serviceBlockFor(serviceName: String): String =
        Regex("""<service\b[\s\S]*?(?:/>|</service>)""")
            .findAll(this)
            .map { it.value }
            .first { it.contains("""android:name="$serviceName"""") }

    private companion object {
        const val EXPECTED_APP_DISPLAY_NAME = "栖知 Solin"

        val SHARED_ATTACHMENT_MIME_TYPES = listOf(
            "text/*",
            "image/*",
            "audio/*",
            "video/*",
            "application/json",
            "application/xml",
            "application/yaml",
            "application/x-yaml",
            "application/pdf",
            "application/rtf",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        )
    }
}
