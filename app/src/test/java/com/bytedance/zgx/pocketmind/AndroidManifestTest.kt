package com.bytedance.zgx.pocketmind

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidManifestTest {
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
    fun declaresAccessibilityServiceForCurrentScreenTextSpecialAccess() {
        val manifest = readManifest()
        val serviceConfig = readMainFile("res/xml/pocketmind_accessibility_service.xml")

        assertTrue(manifest.contains(""".device.PocketMindAccessibilityService"""))
        assertTrue(manifest.contains("""android.permission.BIND_ACCESSIBILITY_SERVICE"""))
        assertTrue(manifest.contains("""android.accessibilityservice.AccessibilityService"""))
        assertTrue(manifest.contains("""@xml/pocketmind_accessibility_service"""))
        assertTrue(serviceConfig.contains("""android:canRetrieveWindowContent="true""""))
        assertTrue(serviceConfig.contains("""typeWindowStateChanged|typeWindowContentChanged|typeWindowsChanged"""))
        assertTrue(!serviceConfig.contains("""canPerformGestures="true""""))
        assertTrue(!serviceConfig.contains("""canTakeScreenshot="true""""))
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
        val mainActivity = readMainFile("java/com/bytedance/zgx/pocketmind/MainActivity.kt")
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

    private companion object {
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
