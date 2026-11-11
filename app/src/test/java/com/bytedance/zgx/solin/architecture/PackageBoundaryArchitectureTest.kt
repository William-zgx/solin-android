package com.bytedance.zgx.solin.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Wave 1 package-boundary gates for AI-friendly architecture.
 *
 * These tests scan production sources on disk (app/src/main/java) so they stay
 * Android-free JVM unit tests. Add a direction rule only after that dependency has been made
 * one-way; the rule then prevents the cycle from returning.
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
                    }
                ),
                ForbiddenImport(
                    description = "action.ActionExecutor",
                    matches = { importFqName ->
                        importFqName == "com.bytedance.zgx.solin.action.ActionExecutor" ||
                            importFqName.startsWith("com.bytedance.zgx.solin.action.ActionExecutor.")
                    }
                )
            )
        )

        assertNoViolations(
            rule = "ui package must not import ToolExecutor / ActionExecutor implementation details",
            violations = violations
        )
    }

    @Test
    fun toolMustNotImportUiPackage() {
        val violations = forbiddenImportsIn(
            packageDir = "tool",
            forbiddenImportMatchers = listOf(
                ForbiddenImport(
                    description = "ui.*",
                    matches = { importFqName -> isSolinUiImport(importFqName) }
                )
            )
        )

        assertNoViolations(
            rule = "tool package must not import ui package",
            violations = violations
        )
    }

    @Test
    fun orchestrationMustNotImportUiPackage() {
        val violations = forbiddenImportsIn(
            packageDir = "orchestration",
            forbiddenImportMatchers = listOf(
                ForbiddenImport(
                    description = "ui.*",
                    matches = { importFqName -> isSolinUiImport(importFqName) }
                )
            )
        )

        assertNoViolations(
            rule = "orchestration package must not import ui package",
            violations = violations
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
                    matches = { importFqName -> importFqName.startsWith("androidx.compose.") }
                ),
                ForbiddenImport(
                    description = "android.app.Activity / androidx.activity",
                    matches = { importFqName ->
                        importFqName == "android.app.Activity" ||
                            importFqName.startsWith("android.app.Activity.") ||
                            importFqName.startsWith("androidx.activity.")
                    }
                )
            )
        )

        assertNoViolations(
            rule = "orchestration package must not import Compose or Activity UI frameworks",
            violations = violations
        )
    }

    @Test
    fun storageMustNotImportUiOrSolinViewModel() {
        val violations = forbiddenImportsIn(
            packageDir = "storage",
            forbiddenImportMatchers = listOf(
                ForbiddenImport(
                    description = "ui.*",
                    matches = { importFqName -> isSolinUiImport(importFqName) }
                ),
                ForbiddenImport(
                    description = "SolinViewModel",
                    matches = { importFqName ->
                        importFqName == "com.bytedance.zgx.solin.SolinViewModel" ||
                            importFqName.startsWith("com.bytedance.zgx.solin.SolinViewModel.")
                    }
                )
            )
        )

        assertNoViolations(
            rule = "storage package must not import ui package or SolinViewModel",
            violations = violations
        )
    }

    @Test
    fun presentationMustNotImportUiPackage() {
        val violations = forbiddenImportsIn(
            packageDir = "presentation",
            forbiddenImportMatchers = listOf(
                ForbiddenImport(
                    description = "ui.*",
                    matches = { importFqName -> isSolinUiImport(importFqName) }
                )
            )
        )

        assertNoViolations(
            rule = "presentation package must not import ui.* (presentation owns ViewModel facade; ui owns Compose)",
            violations = violations
        )
    }

    @Test
    fun toolMustNotImportModuleOrSafetyPackages() {
        val violations = forbiddenImportsIn(
            packageDir = "tool",
            forbiddenImportMatchers = listOf(
                ForbiddenImport(
                    description = "module.*",
                    matches = { importFqName -> isSolinPackageImport(importFqName, "module") }
                ),
                ForbiddenImport(
                    description = "safety.*",
                    matches = { importFqName -> isSolinPackageImport(importFqName, "safety") }
                )
            )
        )

        assertNoViolations(
            rule = "tool owns handler and authorization ports; implementations depend on tool, not vice versa",
            violations = violations
        )
    }

    @Test
    fun actionMustNotImportOrchestrationPackage() {
        val violations = forbiddenImportsIn(
            packageDir = "action",
            forbiddenImportMatchers = listOf(
                ForbiddenImport(
                    description = "orchestration.*",
                    matches = { importFqName -> isSolinPackageImport(importFqName, "orchestration") }
                )
            )
        )

        assertNoViolations(
            rule = "action depends on shared contracts and utilities, never the agent-loop owner",
            violations = violations
        )
    }

    @Test
    fun productionRoutingExecutorsKeepEvidenceBoundingDecorator() {
        val mainRoot = ProductionSourceScanner.solinPackageRoot()
        val debugRoot = File(
            ProductionSourceScanner.mainJavaRoot(),
            "../../debug/java/com/bytedance/zgx/solin"
        )
        val violations = listOf(mainRoot, debugRoot).flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "ToolExecutor.kt" }
                .mapNotNull { file ->
                    val source = file.readText()
                    if ("RoutingToolExecutor(" in source && "EvidenceBoundingToolExecutor(" !in source) {
                        file.path
                    } else {
                        null
                    }
                }
                .toList()
        }

        assertTrue(
            "Every main/debug RoutingToolExecutor needs an EvidenceBoundingToolExecutor decorator: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun androidLogCallsStayInsideLoggingFacade() {
        val root = ProductionSourceScanner.solinPackageRoot()
        val violations = ProductionSourceScanner.kotlinFilesUnder("")
            .filterNot { file -> file.relativeTo(root).path.startsWith("logging/") }
            .flatMap { file ->
                val relative = file.relativeTo(root).path
                file.readLines().mapIndexedNotNull { index, line ->
                    if (ANDROID_LOG_REFERENCE.containsMatchIn(line)) "$relative:${index + 1}" else null
                }
            }

        assertTrue(
            "Use SolinLog instead of android.util.Log outside logging/: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun rootPackageContainsOnlyDocumentedFiles() {
        val unexpected = ProductionSourceScanner.rootPackageKotlinFiles()
            .map { it.name }
            .filterNot { it in DOCUMENTED_ROOT_PACKAGE_FILES }

        assertTrue(
            "New feature code belongs in an owner package, not the root package: $unexpected",
            unexpected.isEmpty()
        )
    }

    @Test
    fun godObjectFilesMustStayUnderHighWatermarks() {
        // Growth guard only (docs/architecture_depth.md): depth, not file size, is the goal.
        val limits = listOf(
            GodObjectLimit(
                relativePath = "SolinViewModel.kt",
                maxLines = 852
            ),
            GodObjectLimit(
                relativePath = "ui/SolinScreen.kt",
                maxLines = 497
            ),
            GodObjectLimit(
                relativePath = "orchestration/AgentLoopRuntime.kt",
                maxLines = 1917
            ),
            GodObjectLimit(
                relativePath = "presentation/ChatController.kt",
                maxLines = 1425
            )
        )

        val failures = limits.mapNotNull { limit ->
            val file = ProductionSourceScanner.kotlinFile(limit.relativePath)
            val lines = ProductionSourceScanner.lineCount(file)
            if (lines > limit.maxLines) {
                "${limit.relativePath}: $lines lines exceeds max ${limit.maxLines} " +
                    "(move logic into a deep module instead of growing this file)"
            } else {
                null
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "God-object size guard failed:\n" +
                    failures.joinToString(separator = "\n") { " - $it" }
            )
        }
    }

    @Test
    fun productionFileCountOnlyGoesDown() {
        // Depth ratchet (docs/architecture_depth.md): merge pass-through files instead of adding
        // new ones. Lower this number whenever a change removes files; never raise it without a
        // new file that hides real logic behind a smaller interface than the code it replaced.
        val count = ProductionSourceScanner.kotlinFilesUnder("").size
        if (count > MAX_PRODUCTION_KOTLIN_FILES) {
            fail(
                "Production Kotlin files: $count exceeds the depth ratchet $MAX_PRODUCTION_KOTLIN_FILES. " +
                    "Merge a pass-through file instead of adding one (docs/architecture_depth.md)."
            )
        }
    }

    private fun forbiddenImportsIn(
        packageDir: String,
        forbiddenImportMatchers: List<ForbiddenImport>
    ): List<String> {
        val files = ProductionSourceScanner.kotlinFilesUnder(packageDir)
        assertTrue(
            "Expected production sources under package '$packageDir', but none were found " +
                "at ${File(ProductionSourceScanner.solinPackageRoot(), packageDir).absolutePath}",
            files.isNotEmpty()
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
                    violations.joinToString(separator = "\n") { " - $it" }
            )
        }
    }

    private fun isSolinUiImport(importFqName: String): Boolean = isSolinPackageImport(importFqName, "ui")

    private fun isSolinPackageImport(importFqName: String, packageName: String): Boolean {
        val packagePrefix = "com.bytedance.zgx.solin.$packageName"
        return importFqName == packagePrefix || importFqName.startsWith("$packagePrefix.")
    }

    private data class ForbiddenImport(
        val description: String,
        val matches: (String) -> Boolean
    )

    private companion object {
        val ANDROID_LOG_REFERENCE = Regex(
            """import android\.util\.Log|android\.util\.Log\.|\bLog\.[diwev]\("""
        )

        val DOCUMENTED_ROOT_PACKAGE_FILES = setOf(
            "AdaptiveInferenceRollout.kt",
            "AgentRuntimePermissionPolicy.kt",
            "ChatModels.kt",
            "ConfirmationPermissionCoordinator.kt",
            "DebugIntentHandler.kt",
            "LocalModelTokenLimits.kt",
            "MainActivity.kt",
            "ModelCatalog.kt",
            "RemoteModels.kt",
            "SolinAppContainer.kt",
            "SolinApplication.kt",
            "SolinConstants.kt",
            "SolinViewModel.kt",
            "VoiceRecognitionCoordinator.kt"
        )

        // Five capability-owning modules approved in docs/plans/mika-phone-capabilities.md:
        // input/stability verification, bounded composites, verified routes, background session/helper.
        // Two cloud-edge collaboration cores in orchestration/collab (docs/specs/20261107-cloud-edge-collaboration):
        // CollaborationPolicy.kt (role placement, escalation) and MilestonePlan.kt (plan parser with its
        // strict JSON scan, RunProgressGate, CloudPlannerRequest).
        // Plus tool/ToolRetriever.kt: first-step tool retrieval for local model planning.
        const val MAX_PRODUCTION_KOTLIN_FILES = 327
    }

    private data class GodObjectLimit(
        val relativePath: String,
        val maxLines: Int
    )
}
