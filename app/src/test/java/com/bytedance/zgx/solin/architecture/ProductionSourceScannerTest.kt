package com.bytedance.zgx.solin.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionSourceScannerTest {
    @Test
    fun referencesCoverAndroidAndSolinWithoutTreatingCommentsOrStringsAsCode() = withSource(
        listOf(
            "package example",
            "import android.app.Activity",
            "val quoted = \"com.bytedance.zgx.solin.safety.ForbiddenSafety\"",
            "val triple = \"\"\"com.bytedance.zgx.solin.module.ForbiddenModule\"\"\"",
            "val escaped = \"quote \\\" com.bytedance.zgx.solin.safety.ForbiddenSafety\"",
            "val char = '\\''",
            "// com.bytedance.zgx.solin.safety.ForbiddenSafety",
            "/* outer /* com.bytedance.zgx.solin.module.ForbiddenModule */ still comment */",
            "val activity = android.app.Activity()",
            "val compose = androidx.compose.ui.Modifier",
            "val registry = com.bytedance.zgx.solin.module.SolinModuleRegistryImpl()",
            "val template = \"\${com.bytedance.zgx.solin.module.BuiltInToolsModule()}\"",
            "val nestedTemplate = \"\${run { com.bytedance.zgx.solin.module.BuiltInToolProvider }}\"",
            "val tripleTemplate = \"\"\"\${androidx.compose.ui.Modifier}\"\"\"",
            "val trailing = 1 // com.bytedance.zgx.solin.safety.ForbiddenSafety"
        )
    ) { file ->
        val references = ProductionSourceScanner.sourceReferences(file)

        assertTrue("android.app.Activity" in references)
        assertTrue("androidx.compose.ui.Modifier" in references)
        assertTrue("com.bytedance.zgx.solin.module.SolinModuleRegistryImpl" in references)
        assertTrue("com.bytedance.zgx.solin.module.BuiltInToolsModule" in references)
        assertTrue("com.bytedance.zgx.solin.module.BuiltInToolProvider" in references)
        assertEquals(1, references.count { it == "android.app.Activity" })
        assertFalse(references.any { "Forbidden" in it })
        assertTrue("android.app.Activity" in ProductionSourceScanner.importStatements(file))
    }

    @Test
    fun productionSourceDiscoveryUsesOneSortedAppSrcTree() {
        val sources = ProductionSourceScanner.productionSourceFiles()
        val paths = sources.map { it.first }

        assertTrue("main/java/com/bytedance/zgx/solin/MainActivity.kt" in paths)
        assertEquals(paths.sorted(), paths)
        assertTrue(paths.none { it.startsWith("test/") || it.startsWith("androidTest/") })
        assertTrue(sources.all { (_, file) -> file.isFile && (file.extension == "kt" || file.extension == "java") })
    }

    private fun withSource(lines: List<String>, block: (File) -> Unit) {
        val file = File.createTempFile("production-source-scanner", ".kt")
        try {
            file.writeText(lines.joinToString("\n"))
            block(file)
        } finally {
            file.delete()
        }
    }
}
