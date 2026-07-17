package com.bytedance.zgx.solin.docs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCoreDocumentationTest {
    @Test
    fun agentCoreModulesDocumentEveryObjectiveArea() {
        val sections = agentCoreSections()

        assertEquals(
            listOf(
                "Tool Layer",
                "Agent Loop",
                "Skill Framework",
                "Device Context",
                "Execution Boundary",
                "Safety And Audit",
                "Memory",
                "Background Tasks",
                "Multimodal Inputs",
                "Structured Logging",
                "Centralized Constants",
                "Memory Controller",
                "Evidence Encryption",
                "Network Security",
                "Regression Strategy",
            ),
            sections.keys.toList(),
        )

        sections
            .filterKeys { title -> title != "Regression Strategy" }
            .forEach { (title, body) ->
                assertSectionHasPart(title, body, "Code:")
                assertSectionHasPart(title, body, "Responsibilities:")
                assertSectionHasPart(title, body, "Current status:")
                assertSectionHasPart(title, body, "Tests:")
                assertFalse("$title must list at least one test", documentedTestRefs(body).isEmpty())
            }

        val regressionStrategy = sections.getValue("Regression Strategy")
        assertSectionHasPart("Regression Strategy", regressionStrategy, "Local verification:")
        assertSectionHasPart("Regression Strategy", regressionStrategy, "Emulator regression:")
        assertTrue(regressionStrategy.contains("./gradlew :app:testDebugUnitTest"))
        assertTrue(regressionStrategy.contains("scripts/verify_emulator.sh"))
        assertTrue(regressionStrategy.contains("scripts/test_validation_scripts.sh"))
        assertTrue(regressionStrategy.contains("AgentCoreDocumentationTest"))
    }

    @Test
    fun readmeStartsWithProductContractBeforeImplementationDetails() {
        val readme = readRepoFile("README.md")

        val contractIndex = readme.indexOf("## Product Contract")
        val implementationIndex = readme.indexOf("## Implementation Highlights")
        val firstScreenIndex = readme.indexOf("## First Screen And Trust Flow")

        assertTrue("README must introduce the product contract", contractIndex >= 0)
        assertTrue("README must keep implementation details after the product contract", implementationIndex > contractIndex)
        assertTrue("README must document the first-screen trust flow", firstScreenIndex > implementationIndex)
        assertTrue(readme.contains("Local by default"))
        assertTrue(readme.contains("Remote is optional"))
        assertTrue(readme.contains("Actions are confirmed"))
        assertTrue(readme.contains("Users stay in control"))
        assertTrue(readme.contains("remote sends"))
        assertTrue(readme.contains("high-risk device actions still require confirmation"))
        assertTrue(readme.contains("Phone Control Scope"))
    }

    @Test
    fun diagramsDocumentAgentAndPrivacyBoundaries() {
        val readme = readRepoFile("README.md")
        val agentCore = readRepoFile("docs/agent_core_modules.md")
        val privacy = readRepoFile("docs/privacy_notice.md")
        val phoneAcceptance = readRepoFile("docs/phone_acceptance.md")

        assertTrue(readme.contains("```mermaid"))
        assertTrue(readme.contains("ToolRegistry validation"))
        assertTrue(readme.contains("SafetyPolicy"))
        assertTrue(readme.contains("LocalOnly"))
        assertTrue(readme.contains("5-step checkpoint"))

        assertTrue(agentCore.contains("ToolRegistry validation"))
        assertTrue(agentCore.contains("SafetyPolicy"))
        assertTrue(agentCore.contains("AwaitingUserConfirmation"))
        assertTrue(agentCore.contains("AwaitingExternalOutcome"))
        assertTrue(agentCore.contains("SolinModuleRegistry.freeze()"))
        assertTrue(agentCore.contains("SafetyPolicyToolExecutionAuthorizer"))
        assertTrue(agentCore.contains("session id, run id, and a monotonic generation token"))
        assertTrue(agentCore.contains("External Skill Packages are declarative, read-only resources"))
        assertTrue(agentCore.contains("Ed25519"))
        assertTrue(agentCore.contains("Unknown privacy metadata fails closed as `LocalOnly`"))

        assertTrue(privacy.contains("LocalOnly"))
        assertTrue(privacy.contains("requiresLocalModel=true"))
        assertTrue(privacy.contains("RemoteEligible"))
        assertTrue(privacy.contains("requiresLocalModel=false"))
        assertTrue(privacy.contains("Fail closed"))

        assertTrue(phoneAcceptance.contains("Debug receiver boundary"))
        assertTrue(phoneAcceptance.contains("not release validation"))
        assertTrue(phoneAcceptance.contains("adb install -r"))
    }

    @Test
    fun validationDocsUseRegressionEmulatorPassedArtifactAsFullRegressionContract() {
        listOf(
            "README.md" to readRepoFile("README.md"),
            "docs/phone_acceptance.md" to readRepoFile("docs/phone_acceptance.md"),
            "docs/validation_report.md" to readRepoFile("docs/validation_report.md"),
        ).forEach { (path, doc) ->
            assertTrue("$path must document the full emulator regression helper", doc.contains("scripts/regression_emulator.sh"))
            assertTrue("$path must document the full emulator regression artifact", doc.contains("regression-emulator.properties"))
            assertTrue("$path must require the passed artifact status", doc.contains("status=passed"))
        }
    }

    @Test
    fun validationReportDefinesStableRecordTemplate() {
        val report = readRepoFile("docs/validation_report.md")

        assertTrue(report.contains("## 记录模板"))
        assertTrue(report.contains("`本轮覆盖项：`"))
        assertTrue(report.contains("`验证命令：`"))
        assertTrue(report.contains("`结果：`"))
        assertTrue(report.contains("未执行的设备、模拟器或真机项必须明确说明"))
        assertTrue(report.contains("自动回归与必须手工验收的结论必须分开记录"))
        assertTrue(report.contains("语音输入、Android 系统文档选择器和"))
        assertTrue(report.contains("MediaProjection 前台同意不能因为脚本"))
    }

    @Test
    fun acceptanceDocsSeparateAutomaticRegressionFromManualSystemEntryChecks() {
        val phoneAcceptance = readRepoFile("docs/phone_acceptance.md")
        val releaseChecklist = readRepoFile("docs/release_checklist.md")
        val readme = readRepoFile("README.md")

        assertTrue(phoneAcceptance.contains("## 自动回归"))
        assertTrue(phoneAcceptance.contains("## 必须手工验收的系统入口"))
        assertTrue(phoneAcceptance.contains("语音输入必须在设备上点麦克风入口"))
        assertTrue(phoneAcceptance.contains("系统文档选择器必须从输入区附件按钮打开"))
        assertTrue(phoneAcceptance.contains("Android MediaProjection 前台同意弹窗"))
        assertTrue(phoneAcceptance.contains("不能用脚本通过、直接调用 ViewModel/reader"))

        assertTrue(releaseChecklist.contains("Manual acceptance records voice input"))
        assertTrue(releaseChecklist.contains("Android system document picker"))
        assertTrue(releaseChecklist.contains("MediaProjection consent separately"))

        assertTrue(readme.contains("Scripted regression and manual acceptance must be recorded separately"))
        assertTrue(readme.contains("Voice"))
        assertTrue(readme.contains("Android system document picker"))
        assertTrue(readme.contains("foreground"))
        assertTrue(readme.contains("MediaProjection consent sheet"))
    }

    @Test
    fun docsDoNotOverstateTypedToolResultCardsInChat() {
        val readme = readRepoFile("README.md")
        val phoneAcceptance = readRepoFile("docs/phone_acceptance.md")
        val agentCore = readRepoFile("docs/agent_core_modules.md")

        assertTrue(readme.contains("The chat surface only shows a safe result"))
        assertTrue(readme.contains("through the trace/audit surfaces, not a typed chat card"))
        assertTrue(phoneAcceptance.contains("聊天中只应追加安全摘要"))
        assertTrue(phoneAcceptance.contains("通过 Agent trace / audit 入口查看"))
        assertTrue(phoneAcceptance.contains("Agent trace / audit 应提供"))
        assertTrue(agentCore.contains("Surface safe execution summaries to the UI"))
        assertTrue(agentCore.contains("remain in Agent trace and audit"))

        assertFalse(phoneAcceptance.contains("聊天中应追加一条结构化执行结果"))
        assertFalse(phoneAcceptance.contains("聊天中应追加结构化工具结果"))
        assertFalse(readme.contains("Agent run trace, audit log, and chat session"))
    }

    @Test
    fun documentedCoreModuleTestsReferenceExistingTestFiles() {
        val repoRoot = repoRoot()
        val testClasses = buildTestClassIndex(repoRoot)
        val documentedRefs = agentCoreSections()
            .filterKeys { title -> title != "Regression Strategy" }
            .values
            .flatMap(::documentedTestRefs)
            .distinct()

        assertFalse("agent core modules must document test coverage", documentedRefs.isEmpty())

        val missingClasses = documentedRefs
            .map { ref -> ref.substringBefore(".") }
            .distinct()
            .filterNot { className -> className in testClasses }

        assertTrue(
            "Documented test classes must exist: $missingClasses",
            missingClasses.isEmpty(),
        )

        val missingMethods = documentedRefs
            .mapNotNull { ref ->
                val className = ref.substringBefore(".")
                val methodName = ref.substringAfter(".", missingDelimiterValue = "")
                if (methodName.isBlank()) return@mapNotNull null
                ref.takeUnless { methodName in testClasses[className].orEmpty() }
            }

        assertTrue(
            "Documented test methods must exist: $missingMethods",
            missingMethods.isEmpty(),
        )
    }

    @Test
    fun backgroundTaskQueryToolBoundaryIsDocumented() {
        val backgroundTasks = agentCoreSections().getValue("Background Tasks")

        assertTrue(backgroundTasks.contains("query_background_tasks"))
        assertTrue(backgroundTasks.contains("read-only"))
        assertTrue(backgroundTasks.contains("LocalOnly"))
        assertTrue(backgroundTasks.contains("requiresLocalModel=true"))
        assertTrue(backgroundTasks.contains("never calls schedule/cancel/set/disable"))
        assertTrue(backgroundTasks.contains("title/body"))
        assertTrue(backgroundTasks.contains("omitted"))
        assertTrue(backgroundTasks.contains("tasksJson"))
        assertTrue(backgroundTasks.contains("policyJson"))
        assertTrue(
            backgroundTasks.contains(
                "DeviceContextToolExecutorTest.backgroundTasksQueryReturnsLocalOnlyTaskAndPolicyMetadataWithoutReminderContent",
            ),
        )
        assertTrue(
            backgroundTasks.contains(
                "AgentLoopRuntimeTest.backgroundTasksObservationRedactsTaskAndPolicyJson",
            ),
        )
    }

    @Test
    fun mockTargetAppSearchEvalCanPrepareBundledModelsBeforeModelDrivenDebugEval() {
        val script = readRepoFile("scripts/run_mock_target_app_search_eval.sh")

        assertTrue(script.contains("RUN_MODEL_DRIVEN_APP_SEARCH_EVAL"))
        assertTrue(script.contains("PREPARE_BUNDLED_MODELS"))
        assertTrue(script.contains("scripts/package_bundled_models.sh"))
        assertTrue(script.contains("model_driven_app_search"))
        assertTrue(script.contains("modelPlannedStepCount"))
        assertTrue(script.contains("searchVerificationStatus=verified"))
        assertTrue(script.contains("files/device_control_eval_result_${'$'}{request_id}.properties"))

        val prepareIndex = script.indexOf("scripts/package_bundled_models.sh")
        val debugInstallIndex = script.indexOf("\"${'$'}{ADB[@]}\" install -r \"${'$'}DEBUG_APK\"")
        val accessibilityIndex = script.indexOf("solin_accessibility_enabled")
        val modelDrivenIndex = script.indexOf("run_model_driven_case taobao")

        assertTrue("bundled model package must be prepared before debug APK reinstall", prepareIndex in 1 until debugInstallIndex)
        assertTrue("debug APK reinstall must happen before accessibility check", debugInstallIndex in 1 until accessibilityIndex)
        assertTrue("model-driven case must run after primitive mock cases are defined", modelDrivenIndex > script.indexOf("run_case gaode"))
    }

    private fun agentCoreSections(): Map<String, String> {
        val doc = agentCoreDoc().readText()
        val headings = Regex("(?m)^## (.+)$").findAll(doc).toList()
        return headings.mapIndexed { index, match ->
            val title = match.groupValues[1].trim()
            val start = match.range.last + 1
            val end = headings.getOrNull(index + 1)?.range?.first ?: doc.length
            title to doc.substring(start, end)
        }.toMap()
    }

    private fun assertSectionHasPart(title: String, body: String, label: String) {
        assertTrue("$title must document $label", body.contains(label))
    }

    private fun documentedTestRefs(sectionBody: String): List<String> {
        val testsStart = sectionBody.indexOf("Tests:")
        if (testsStart < 0) return emptyList()
        return Regex("""(?m)^- `([^`]+)`""")
            .findAll(sectionBody.substring(testsStart))
            .map { match -> match.groupValues[1].substringBefore(" ") }
            .toList()
    }

    private fun buildTestClassIndex(repoRoot: File): Map<String, Set<String>> =
        listOf(
            File(repoRoot, "app/src/test/java"),
            File(repoRoot, "app/src/androidTest/java"),
        )
            .flatMap { root -> root.walkTopDown().filter { file -> file.extension == "kt" }.toList() }
            .associate { file ->
                file.nameWithoutExtension to Regex("""(?m)^\s*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
                    .findAll(file.readText())
                    .map { match -> match.groupValues[1] }
                    .toSet()
            }

    private fun agentCoreDoc(): File =
        File(repoRoot(), "docs/agent_core_modules.md").also { file ->
            assertTrue("missing ${file.path}", file.isFile)
        }

    private fun readRepoFile(path: String): String =
        File(repoRoot(), path).also { file ->
            assertTrue("missing ${file.path}", file.isFile)
        }.readText()

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { file -> file.parentFile }
            .first { candidate -> File(candidate, "docs/agent_core_modules.md").isFile }
            .absoluteFile
}
