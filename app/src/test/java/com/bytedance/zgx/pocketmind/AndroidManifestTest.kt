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
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
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
