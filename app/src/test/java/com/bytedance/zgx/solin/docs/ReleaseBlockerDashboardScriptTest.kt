package com.bytedance.zgx.solin.docs

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseBlockerDashboardScriptTest {
    @Test
    fun releaseBlockerDashboardScriptSummarizesCurrentBlockersWithoutValidationReport() {
        val root = repoRoot()
        val script = File(root, "scripts/generate_release_blocker_dashboard.sh")

        assertTrue("missing ${script.path}", script.isFile)
        assertTrue(
            "script should be executable or bash-runnable",
            script.canExecute() || script.readText().startsWith("#!/usr/bin/env bash"),
        )

        val scriptText = script.readText()
        assertTrue(scriptText.contains("docs/roadmap_gap_matrix.json"))
        assertTrue(scriptText.contains("docs/release_readiness.md"))
        assertFalse(scriptText.contains("docs/validation_report.md"))

        val output = runScript(root, script)
        val normalizedOutput = output.lowercase()

        assertTrue(output.contains("docs/roadmap_gap_matrix.json"))
        assertTrue(output.contains("docs/release_readiness.md"))
        assertTrue(normalizedOutput.contains("active blockers"))
        assertTrue(normalizedOutput.contains("deferred"))
        assertTrue(normalizedOutput.contains("human approval"))
        assertTrue(normalizedOutput.contains("physical hardware"))
        assertTrue(normalizedOutput.contains("next commands"))
        assertTrue(normalizedOutput.contains("physical validation"))
        assertTrue(normalizedOutput.contains("real-app search"))
        assertTrue(normalizedOutput.contains("perf baseline"))
        assertTrue(normalizedOutput.contains("privacy"))
        assertTrue(normalizedOutput.contains("store"))
        assertTrue(normalizedOutput.contains("release approvals"))
    }

    private fun runScript(root: File, script: File): String {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val process = ProcessBuilder("bash", script.path)
            .directory(root)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .start()

        process.inputStream.copyTo(stdout)
        process.errorStream.copyTo(stderr)
        val exitCode = process.waitFor()
        val output = stdout.toString(Charsets.UTF_8.name())
        val errors = stderr.toString(Charsets.UTF_8.name())

        assertTrue(
            "expected ${script.path} to exit 0, got $exitCode\nstdout:\n$output\nstderr:\n$errors",
            exitCode == 0,
        )
        return output
    }

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { file -> file.parentFile }
            .first { candidate -> File(candidate, "docs/roadmap_gap_matrix.json").isFile }
            .absoluteFile
}
