package com.bytedance.zgx.solin.docs

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTaskBenchmarkScriptTest {
    @Test
    fun runnerUnitTestsPass() {
        val output = run(
            "python3", "-I", "-B", "-m", "unittest", "discover",
            "-s", "scripts/tests", "-p", "test_run_phone_task_benchmark.py",
        )
        assertTrue(output, output.contains("OK"))
    }

    @Test
    fun dryRunValidatesTheFullTaskSchema() {
        val output = run("python3", "-I", "-B", "scripts/run_phone_task_benchmark.py", "--dry-run")
        assertTrue(output, output.startsWith("ok:"))
        assertTrue(output, output.contains("holdout"))
    }

    @Test
    fun benchmarkCreditsMikaAndDocumentsItsSafetyRules() {
        val root = repoRoot()
        val credit = "task categories and check types adapted from Mika (Apache-2.0)"
        assertTrue(File(root, "docs/phone_task_benchmark.json").readText().contains(credit))
        val section = File(root, "docs/phone_acceptance.md").readText()
            .substringAfter("## 手机任务基准")
            .substringBefore("\n## ")
        assertTrue(section.contains(credit))
        assertTrue(section.contains("从不确认"))
        assertTrue(section.contains("uiautomator dump"))
        assertTrue(section.contains("--allow-gated"))
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command)
            .directory(repoRoot())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val exitCode = process.waitFor()
        assertTrue("expected ${command.joinToString(" ")} to exit 0, got $exitCode\n$output", exitCode == 0)
        return output
    }

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { file -> file.parentFile }
            .first { candidate -> File(candidate, "docs/phone_task_benchmark.json").isFile }
            .absoluteFile
}
