package com.bytedance.zgx.pocketmind

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidManifestTest {
    @Test
    fun declaresUsageStatsPermissionForSpecialAccessSettings() {
        val manifest = readManifest()

        assertTrue(manifest.contains("""android.permission.PACKAGE_USAGE_STATS"""))
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
    }

    private fun readManifest(): String {
        return readMainFile("AndroidManifest.xml")
    }

    private fun readMainFile(relativePath: String): String {
        val manifestFile = listOf(
            File("src/main/$relativePath"),
            File("app/src/main/$relativePath"),
        ).first { it.isFile }
        return manifestFile.readText()
    }

    private fun String.intentFilterFor(actionName: String): String =
        Regex("""<intent-filter>[\s\S]*?</intent-filter>""")
            .findAll(this)
            .map { it.value }
            .first { it.contains("android:name=\"$actionName\"") }

    private companion object {
        val SHARED_ATTACHMENT_MIME_TYPES = listOf(
            "text/*",
            "image/*",
            "audio/*",
            "video/*",
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
