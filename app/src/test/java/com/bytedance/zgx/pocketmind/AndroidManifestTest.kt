package com.bytedance.zgx.pocketmind

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidManifestTest {
    @Test
    fun declaresUsageStatsPermissionForSpecialAccessSettings() {
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.isFile }
        val manifest = manifestFile.readText()

        assertTrue(manifest.contains("""android.permission.PACKAGE_USAGE_STATS"""))
    }
}
