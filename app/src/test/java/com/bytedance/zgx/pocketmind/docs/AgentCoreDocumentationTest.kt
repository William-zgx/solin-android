package com.bytedance.zgx.pocketmind.docs

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
        assertTrue(regressionStrategy.contains("AgentCoreDocumentationTest"))
    }

    @Test
    fun documentedCoreModuleTestsReferenceExistingTestFiles() {
        val repoRoot = repoRoot()
        val testClassFiles = buildTestClassIndex(repoRoot)
        val documentedRefs = agentCoreSections()
            .filterKeys { title -> title != "Regression Strategy" }
            .values
            .flatMap(::documentedTestRefs)
            .distinct()

        assertFalse("agent core modules must document test coverage", documentedRefs.isEmpty())

        val missingClasses = documentedRefs
            .map { ref -> ref.substringBefore(".") }
            .distinct()
            .filterNot { className -> className in testClassFiles }

        assertTrue(
            "Documented test classes must exist: $missingClasses",
            missingClasses.isEmpty(),
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
        assertTrue(backgroundTasks.contains("`body`"))
        assertTrue(backgroundTasks.contains("omitted"))
        assertTrue(backgroundTasks.contains("tasksJson"))
        assertTrue(backgroundTasks.contains("policyJson"))
        assertTrue(
            backgroundTasks.contains(
                "DeviceContextToolExecutorTest.backgroundTasksQueryReturnsLocalOnlyTaskAndPolicyMetadataWithoutBodies",
            ),
        )
        assertTrue(
            backgroundTasks.contains(
                "AgentLoopRuntimeTest.backgroundTasksObservationRedactsTaskAndPolicyJson",
            ),
        )
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

    private fun buildTestClassIndex(repoRoot: File): Set<String> =
        listOf(
            File(repoRoot, "app/src/test/java"),
            File(repoRoot, "app/src/androidTest/java"),
        )
            .flatMap { root -> root.walkTopDown().filter { file -> file.extension == "kt" }.toList() }
            .map { file -> file.nameWithoutExtension }
            .toSet()

    private fun agentCoreDoc(): File =
        File(repoRoot(), "docs/agent_core_modules.md").also { file ->
            assertTrue("missing ${file.path}", file.isFile)
        }

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { file -> file.parentFile }
            .first { candidate -> File(candidate, "docs/agent_core_modules.md").isFile }
            .absoluteFile
}
