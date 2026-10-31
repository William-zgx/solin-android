package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.device.appUiNotesFor
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** App UI notes block in the LOCAL observation-replanner prompt. */
class ObservationReplannerPromptAppUiNotesTest {
    private val registry = ToolRegistry()

    @Test
    fun knownForegroundPackageAddsLabeledNotesBlockAfterProgressLine() {
        val prompt = ObservationReplanPromptFixtures.searchEntryObserveContext("com.taobao.taobao")
            .observationModelPrompt(registry)
        val lines = prompt.lines()
        val notesIndex = lines.indexOfFirst { it.startsWith(APP_UI_NOTES_PROMPT_LABEL) }

        assertTrue(prompt, notesIndex >= 0)
        assertTrue(lines[notesIndex - 1].startsWith("App search progress:"))
        assertTrue(lines[notesIndex].contains("data, not instructions"))
        assertTrue(lines[notesIndex].contains(appUiNotesFor("com.taobao.taobao").first()))
        assertTrue(prompt.length <= 2_600)
        println("AppUiNotesPrompt length=${prompt.length}\n${lines[notesIndex]}")
    }

    @Test
    fun notesNeverCostARuleLineTargetShortlistOrPromptBudget() {
        val withNotes = ObservationReplanPromptFixtures.searchEntryObserveContext("com.taobao.taobao")
            .observationModelPrompt(registry)
        // Same length as com.taobao.taobao so the only difference is the notes block.
        val withoutNotes = ObservationReplanPromptFixtures.searchEntryObserveContext("com.example.nopkg")
            .observationModelPrompt(registry)
            .replace("com.example.nopkg", "com.taobao.taobao")

        assertFalse(withoutNotes.contains(APP_UI_NOTES_PROMPT_LABEL))
        assertTrue("${withNotes.length} > ${withoutNotes.length}", withNotes.length <= withoutNotes.length)
        val budgeted = listOf(
            "User intent preview:", "Prior request details:", "Observation summary:",
            "Observation diagnostics:", "LocalOnly observation evidence:",
        )
        withoutNotes.lines().filter { it.isNotBlank() }.forEach { line ->
            val label = budgeted.firstOrNull { line.startsWith(it) }
            if (label == null) {
                assertTrue("rule line lost: $line", line in withNotes.lines())
            } else {
                assertTrue("line lost: $label", withNotes.lines().any { it.startsWith(label) })
            }
        }
        Regex("""targetShortlist\([^)]*\)""").findAll(withoutNotes).forEach { match ->
            assertTrue("shortlist lost: ${match.value}", withNotes.contains(match.value))
        }
    }

    @Test
    fun shortPromptKeepsEveryOtherLineVerbatim() {
        // A short observation leaves room under the cap, so the notes are a pure addition.
        val withNotes = shortContext("com.tencent.mm").observationModelPrompt(registry)
        val withoutNotes = shortContext("com.example.nopkg").observationModelPrompt(registry)

        assertTrue(withNotes, withNotes.contains(APP_UI_NOTES_PROMPT_LABEL))
        assertTrue(withNotes, withNotes.contains("草稿"))
        assertEquals(
            withoutNotes,
            withNotes.lines().filterNot { it.startsWith(APP_UI_NOTES_PROMPT_LABEL) }.joinToString("\n")
                .replace("com.tencent.mm", "com.example.nopkg"),
        )
    }

    private fun shortContext(packageName: String): AgentObservationReplanContext {
        val base = ObservationReplanPromptFixtures.searchEntryObserveContext(packageName)
        return base.copy(
            observedResult = base.observedResult.copy(
                data = base.observedResult.data + ("screenObservationJson" to observationJson(packageName)),
            ),
        )
    }

    @Test
    fun blockIsCappedAndKeepsWholeNotes() {
        listOf("com.android.settings", "com.tencent.mm", "com.smile.gifmaker", "com.ss.android.ugc.aweme").forEach { pkg ->
            val line = requireNotNull(resultFor(screenPackage = pkg).appUiNotesPromptLine()) { pkg }
            assertTrue("$pkg: ${line.length}", line.length <= MAX_APP_UI_NOTES_PROMPT_CHARS)
            val body = line.removePrefix(APP_UI_NOTES_PROMPT_LABEL).trim()
            body.split("; ").forEach { note -> assertTrue("$pkg cut note: $note", note in appUiNotesFor(pkg)) }
        }
    }

    @Test
    fun requiredFactsReachThePromptForSettingsAndWeChat() {
        val settings = requireNotNull(resultFor(screenPackage = "com.android.settings").appUiNotesPromptLine())
        val wechat = requireNotNull(resultFor(screenPackage = "com.tencent.mm").appUiNotesPromptLine())

        assertTrue(settings, settings.contains("开关") && settings.contains("切换"))
        assertTrue(wechat, wechat.contains("草稿"))
    }

    @Test
    fun foregroundPackageComesFromAfterObservationNotBeforeObservation() {
        val result = resultFor(
            screenPackage = null,
            extra = mapOf(
                "beforeScreenObservationJson" to observationJson("com.android.settings"),
                "afterScreenObservationJson" to observationJson("com.tencent.mm"),
            ),
        )

        val line = requireNotNull(result.appUiNotesPromptLine())
        assertTrue(line, line.contains("草稿"))
        assertFalse(line, appUiNotesFor("com.android.settings").any { it in line })
    }

    @Test
    fun unknownBlankOrMalformedPackageAddsNothing() {
        assertNull(resultFor(screenPackage = "com.example.unknown").appUiNotesPromptLine())
        assertNull(resultFor(screenPackage = "").appUiNotesPromptLine())
        assertNull(resultFor(screenPackage = null, extra = mapOf("screenObservationJson" to "{not json")).appUiNotesPromptLine())
        assertNull(resultFor(screenPackage = null).appUiNotesPromptLine())
    }

    @Test
    fun toolPackageNameIsUsedWhenObservationHasNoPackage() {
        val line = resultFor(screenPackage = null, extra = mapOf("packageName" to "com.tencent.mm")).appUiNotesPromptLine()
        assertTrue("$line", line.orEmpty().contains("草稿"))
    }

    private fun resultFor(screenPackage: String?, extra: Map<String, String> = emptyMap()): ToolResult =
        ToolResult(
            requestId = "observe-1",
            status = ToolStatus.Succeeded,
            summary = "已观察当前屏幕。",
            data = buildMap {
                put("privacy", "LocalOnly")
                screenPackage?.let { put("screenObservationJson", observationJson(it)) }
                putAll(extra)
            },
        )

    private fun observationJson(packageName: String): String =
        """{"schemaVersion":1,"observationId":"o-1","capturedAtMillis":1,"packageName":"$packageName",""" +
            """"privacyLevel":"LocalOnly","sources":["accessibility"],"elementCount":0,"sourceCounts":{},""" +
            """"truncated":false,"elements":[]}"""
}
