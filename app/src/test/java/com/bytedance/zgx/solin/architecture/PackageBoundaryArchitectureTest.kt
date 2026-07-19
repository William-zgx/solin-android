package com.bytedance.zgx.solin.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Wave 1 package-boundary gates for AI-friendly architecture.
 *
 * These tests scan production sources on disk (app/src/main/java) so they stay
 * Android-free JVM unit tests. Prefer rules that pass on the current tree;
 * tighten further once presentation / module splits land.
 */
class PackageBoundaryArchitectureTest {

    @Test
    fun uiMustNotImportToolExecutorOrActionExecutor() {
        val violations = forbiddenImportsIn(
            packageDir = "ui",
            forbiddenImportMatchers = listOf(
                ForbiddenImport(
                    description = "tool.ToolExecutor",
                    matches = { importFqName ->
                        importFqName == "com.bytedance.zgx.solin.tool.ToolExecutor" ||
                            importFqName.startsWith("com.bytedance.zgx.solin.tool.ToolExecutor.")
                    },
                ),
                ForbiddenImport(
                    description = "action.ActionExecutor",
                    matches = { importFqName ->
                        importFqName == "com.bytedance.zgx.solin.action.ActionExecutor" ||
                            importFqName.startsWith("com.bytedance.zgx.solin.action.ActionExecutor.")
                    },
                ),
            ),
        )

        assertNoViolations(
            rule = "ui package must not import ToolExecutor / ActionExecutor implementation details",
            violations = violations,
        )
    }

    @Test
    fun toolMustNotImportUiPackage() {
        val violations = forbiddenImportsIn(
            packageDir = "tool",
            forbiddenImportMatchers = listOf(
                ForbiddenImport(
                    description = "ui.*",
                    matches = { importFqName -> isSolinUiImport(importFqName) },
                ),
            ),
        )

        assertNoViolations(
            rule = "tool package must not import ui package",
            violations = violations,
        )
    }

    @Test
    fun orchestrationMustNotImportUiPackage() {
        val violations = forbiddenImportsIn(
            packageDir = "orchestration",
            forbiddenImportMatchers = listOf(
                ForbiddenImport(
                    description = "ui.*",
                    matches = { importFqName -> isSolinUiImport(importFqName) },
                ),
            ),
        )

        assertNoViolations(
            rule = "orchestration package must not import ui package",
            violations = violations,
        )
    }

    @Test
    fun orchestrationMustNotImportAndroidUiFramework() {
        // High-value freeze from Wave 1 plan: orchestration stays free of Compose/Activity.
        val violations = forbiddenImportsIn(
            packageDir = "orchestration",
            forbiddenImportMatchers = listOf(
                ForbiddenImport(
                    description = "androidx.compose.*",
                    matches = { importFqName -> importFqName.startsWith("androidx.compose.") },
                ),
                ForbiddenImport(
                    description = "android.app.Activity / androidx.activity",
                    matches = { importFqName ->
                        importFqName == "android.app.Activity" ||
                            importFqName.startsWith("android.app.Activity.") ||
                            importFqName.startsWith("androidx.activity.")
                    },
                ),
            ),
        )

        assertNoViolations(
            rule = "orchestration package must not import Compose or Activity UI frameworks",
            violations = violations,
        )
    }

    @Test
    fun storageMustNotImportUiOrSolinViewModel() {
        val violations = forbiddenImportsIn(
            packageDir = "storage",
            forbiddenImportMatchers = listOf(
                ForbiddenImport(
                    description = "ui.*",
                    matches = { importFqName -> isSolinUiImport(importFqName) },
                ),
                ForbiddenImport(
                    description = "SolinViewModel",
                    matches = { importFqName ->
                        importFqName == "com.bytedance.zgx.solin.SolinViewModel" ||
                            importFqName.startsWith("com.bytedance.zgx.solin.SolinViewModel.")
                    },
                ),
            ),
        )

        assertNoViolations(
            rule = "storage package must not import ui package or SolinViewModel",
            violations = violations,
        )
    }

    @Test
    fun presentationMustNotImportUiPackageWhenPresent() {
        // presentation/ is a target package in the AI-friendly architecture plan.
        // When it does not exist yet, the rule is vacuously satisfied.
        if (!ProductionSourceScanner.packageDirExists("presentation")) {
            assertTrue(
                "presentation package is absent; boundary rule reserved for future split",
                true,
            )
            return
        }

        val violations = forbiddenImportsIn(
            packageDir = "presentation",
            forbiddenImportMatchers = listOf(
                ForbiddenImport(
                    description = "ui.*",
                    matches = { importFqName -> isSolinUiImport(importFqName) },
                ),
            ),
        )

        assertNoViolations(
            rule = "presentation package must not import ui.* (presentation owns ViewModel facade; ui owns Compose)",
            violations = violations,
        )
    }

    @Test
    fun godObjectFilesMustStayUnderHighWatermarks() {
        // Soft growth guard: keep this close to current reality; tighten as each split lands.
        val limits = listOf(
            GodObjectLimit(
                relativePath = "SolinViewModel.kt",
                maxLines = 1550,
            ),
            GodObjectLimit(
                relativePath = "ui/SolinScreen.kt",
                maxLines = 1650,
            ),
            GodObjectLimit(
                relativePath = "orchestration/AgentLoopRuntime.kt",
                maxLines = 3600,
            ),
            GodObjectLimit(
                relativePath = "presentation/ChatController.kt",
                maxLines = 2050,
            ),
        )

        val failures = limits.mapNotNull { limit ->
            val file = ProductionSourceScanner.kotlinFile(limit.relativePath)
            val lines = ProductionSourceScanner.lineCount(file)
            if (lines > limit.maxLines) {
                "${limit.relativePath}: $lines lines exceeds max ${limit.maxLines} " +
                    "(split further before growing this god object)"
            } else {
                null
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "God-object size guard failed:\n" +
                    failures.joinToString(separator = "\n") { " - $it" },
            )
        }
    }

    private fun forbiddenImportsIn(
        packageDir: String,
        forbiddenImportMatchers: List<ForbiddenImport>,
    ): List<String> {
        val files = ProductionSourceScanner.kotlinFilesUnder(packageDir)
        assertTrue(
            "Expected production sources under package '$packageDir', but none were found " +
                "at ${File(ProductionSourceScanner.solinPackageRoot(), packageDir).absolutePath}",
            files.isNotEmpty(),
        )

        val violations = mutableListOf<String>()
        for (file in files) {
            val relative = file.relativeTo(ProductionSourceScanner.solinPackageRoot()).path
            for (importFqName in ProductionSourceScanner.importStatements(file)) {
                for (matcher in forbiddenImportMatchers) {
                    if (matcher.matches(importFqName)) {
                        violations += "$relative imports `$importFqName` (forbidden: ${matcher.description})"
                    }
                }
            }
        }
        return violations
    }

    private fun assertNoViolations(rule: String, violations: List<String>) {
        if (violations.isNotEmpty()) {
            fail(
                "Package boundary violated: $rule\n" +
                    violations.joinToString(separator = "\n") { " - $it" },
            )
        }
    }

    private fun isSolinUiImport(importFqName: String): Boolean {
        // Match com.bytedance.zgx.solin.ui and subpackages, but not a hypothetical solin.uiFoo.
        return importFqName == "com.bytedance.zgx.solin.ui" ||
            importFqName.startsWith("com.bytedance.zgx.solin.ui.")
    }

    private data class ForbiddenImport(
        val description: String,
        val matches: (String) -> Boolean,
    )

    private data class GodObjectLimit(
        val relativePath: String,
        val maxLines: Int,
    )
}
