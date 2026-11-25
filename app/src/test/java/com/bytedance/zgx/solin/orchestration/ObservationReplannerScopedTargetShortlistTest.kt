package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model-facing targetShortlist emits `<observationId>/<nodeId>` only for genuine Accessibility nodes. */
class ObservationReplannerScopedTargetShortlistTest : ObservationReplannerTestFixtures() {
    private val obsId = "screen_1700000000000_ab12cd34"

    private fun resultWith(vararg data: Pair<String, String>) = ToolResult(
        requestId = "r1",
        status = ToolStatus.Succeeded,
        summary = "ok",
        data = mapOf("privacy" to "LocalOnly") + data
    )

    private fun searchEntryObservation(id: String = obsId, nodeId: String = "n3_ab12cd") = screenObservationFixtureJson(
        observationId = id,
        elements = arrayOf(
            accessibilityElement(nodeId, "搜索", "button", 10, 20, 110, 70, clickable = true),
            ocrElement("ocr:block:0", "继续", left = 10, top = 100, right = 110, bottom = 170)
        )
    )

    private fun shortlist(prompt: String): String = Regex("""targetShortlist\([^)]*\)""").find(prompt)?.value.orEmpty()

    @Test
    fun observeEmitsScopedRefForAccessibilityNodeAndLeavesOcrBare() {
        val prompt = resultWith("screenObservationJson" to searchEntryObservation())
            .localObservationEvidencePrompt("tap")
        assertTrue(prompt, prompt.contains("target=$obsId/n3_ab12cd,"))
        assertTrue(prompt, shortlist(prompt).contains("tap=$obsId/n3_ab12cd"))
        assertTrue(prompt, shortlist(prompt).contains("ocrFallback=ocr:block:0"))
        assertFalse(prompt, prompt.contains("$obsId/ocr:"))
        assertFalse(prompt, prompt.contains("$obsId/$obsId"))
        assertTrue(prompt, prompt.contains("id=$obsId/n3_ab12cd,text=搜索"))
    }

    @Test
    fun afterObservationEmitsScopedRefUnderItsOwnObservationId() {
        val prompt = resultWith(
            "afterScreenObservationJson" to searchEntryObservation(id = "screen_1700000000999_ffee0011", nodeId = "n7_9a8b7c")
        ).localObservationEvidencePrompt("tap")
        assertTrue(prompt, shortlist(prompt).contains("tap=screen_1700000000999_ffee0011/n7_9a8b7c"))
    }

    @Test
    fun ocrGroundedAccessibilityCandidateUsesScopedRef() {
        val json = screenObservationFixtureJson(
            observationId = obsId,
            elements = arrayOf(
                accessibilityElement("n5_cafe01", "", "button", 10, 20, 210, 120, clickable = true),
                ocrElement("ocr:block:0", "搜索", left = 20, top = 30, right = 200, bottom = 110)
            )
        )
        val prompt = resultWith("screenObservationJson" to json).localObservationEvidencePrompt("tap")
        assertTrue(prompt, prompt.contains("搜索{target=$obsId/n5_cafe01,modeTags=tap,source=accessibility+ocr"))
        assertTrue(prompt, shortlist(prompt).contains("tap=$obsId/n5_cafe01"))
        assertEquals(prompt, 1, Regex(Regex.escape("$obsId/n5_cafe01")).findAll(shortlist(prompt)).count())
    }

    @Test
    fun alreadyScopedIdIsNeverDoublePrefixed() {
        val json = searchEntryObservation(nodeId = "$obsId/n3_ab12cd")
        val prompt = resultWith("screenObservationJson" to json).localObservationEvidencePrompt("tap")
        assertTrue(prompt, shortlist(prompt).contains("tap=$obsId/n3_ab12cd"))
        assertFalse(prompt, prompt.contains("$obsId/$obsId"))
    }

    @Test
    fun nonNodeShapedAccessibilityIdsAndOcrOnlyStayUnchanged() {
        val legacy = screenObservationFixtureJson(
            observationId = obsId,
            elements = arrayOf(accessibilityElement("continue-button", "继续", "button", 10, 20, 110, 70, clickable = true))
        )
        assertTrue(
            resultWith("screenObservationJson" to legacy).localObservationEvidencePrompt("tap")
                .contains("targetShortlist(tap=continue-button)")
        )
        val ocrOnly = ocrOnlyObservationJson(obsId, listOf("继续"))
        val prompt = resultWith("screenObservationJson" to ocrOnly).localObservationEvidencePrompt("tap")
        assertTrue(prompt, shortlist(prompt).contains("ocrFallback=ocr:submit:0"))
        assertFalse(prompt, prompt.contains("$obsId/"))
    }

    @Test
    fun missingOrMalformedObservationIdKeepsBareNodeIdWithoutBogusRef() {
        listOf("", "bad id", "a/b").forEach { badId ->
            val prompt = resultWith("screenObservationJson" to searchEntryObservation(id = badId))
                .localObservationEvidencePrompt("tap")
            assertTrue(prompt, shortlist(prompt).contains("tap=n3_ab12cd"))
            assertFalse(prompt, prompt.contains("unknown/n3"))
            assertFalse(prompt, prompt.contains("/n3_ab12cd"))
        }
    }

    @Test
    fun resolverRankingRecognisesScopedValueWhenLabelsTie() {
        // Identical labels: the shared label rank ties, so only the node-id rank can order them.
        val json = screenObservationFixtureJson(
            observationId = obsId,
            elements = arrayOf(
                accessibilityElement("n1_aaaa01", "搜索", "button", 10, 900, 110, 960, clickable = true),
                accessibilityElement("n2_bbbb02", "搜索", "input", 10, 20, 410, 80, clickable = true, editable = true)
            )
        )
        val prompt = resultWith("screenObservationJson" to json).localObservationEvidencePrompt("搜索")
        val tap = shortlist(prompt).substringAfter("tap=").substringBefore(",").substringBefore(")")
        assertTrue(prompt, tap.startsWith("$obsId/n2_bbbb02|$obsId/n1_aaaa01"))
        assertTrue(prompt, shortlist(prompt).contains("type=n2_bbbb02"))
    }

    @Test
    fun compressionKeepsScopedRefsWhole() {
        val elements = (0 until 12).map { i ->
            accessibilityElement("n${i}_ab${i}f0", "按钮号码$i", "button", 10, 20 + i * 60, 110, 70 + i * 60, clickable = true)
        }.toTypedArray()
        val evidence = resultWith("screenObservationJson" to screenObservationFixtureJson(obsId, elements))
            .localObservationEvidencePrompt("tap")
        val wanted = evidence.targetShortlistTargetValues()
        assertTrue(evidence, wanted.isNotEmpty() && wanted.all { it.startsWith("$obsId/n") })
        val prompt = "Rule line\nLocalOnly observation evidence: $evidence\nObservation diagnostics: none"
            .compressForObservationModelContext(maxChars = 900)
        wanted.forEach { value -> assertTrue("lost $value in\n$prompt", prompt.contains(value)) }
        assertFalse(prompt, Regex("""$obsId/n\d+_\w*\.\.\.""").containsMatchIn(prompt))
    }

    private val registry = com.bytedance.zgx.solin.tool.ToolRegistry()

    private fun contextFor(vararg data: Pair<String, String>): AgentObservationReplanContext {
        val request = com.bytedance.zgx.solin.tool.ToolRequest(id = "o1", toolName = "observe_current_screen")
        return AgentObservationReplanContext(
            run = AgentRun(
                id = "run",
                input = "在淘宝搜索牛奶",
                state = AgentRunState.Observing,
                createdAtMillis = 1L,
                updatedAtMillis = 1L
            ),
            previousRequest = request,
            observedResult = resultWith(*data),
            priorRequests = listOf(request)
        )
    }

    @Test
    fun editableClickableNodeKeepsBareTypeAndScopedTapInOneShortlist() {
        val json = screenObservationFixtureJson(
            observationId = obsId,
            elements = arrayOf(
                accessibilityElement("n2_bbbb02", "搜索", "input", 10, 20, 410, 80, clickable = true, editable = true),
                accessibilityElement("n4_dddd04", "列表", "list", 10, 100, 410, 800, scrollable = true)
            )
        )
        val list = shortlist(resultWith("screenObservationJson" to json).localObservationEvidencePrompt("搜索"))
        assertTrue(list, list.contains("type=n2_bbbb02"))
        assertTrue(list, list.contains("tap=$obsId/n2_bbbb02"))
        assertTrue(list, list.contains("scroll=$obsId/n4_dddd04"))
        val minimal = contextFor("screenObservationJson" to json).minimalObservationModelPrompt(registry)
        assertTrue(minimal, minimal.contains("type=n2_bbbb02"))
        assertTrue(minimal, minimal.contains("tap=$obsId/n2_bbbb02"))
        assertFalse(minimal, minimal.contains("type=$obsId"))
    }

    @Test
    fun fusedEditableCandidateFollowsTheSamePerModeRule() {
        val json = screenObservationFixtureJson(
            observationId = obsId,
            elements = arrayOf(
                accessibilityElement("n5_cafe01", "", "input", 10, 20, 210, 120, clickable = true, editable = true),
                ocrElement("ocr:block:0", "搜索", left = 20, top = 30, right = 200, bottom = 110)
            )
        )
        val list = shortlist(resultWith("screenObservationJson" to json).localObservationEvidencePrompt("搜索"))
        assertTrue(list, list.contains("type=n5_cafe01"))
        assertTrue(list, list.contains("tap=$obsId/n5_cafe01"))
        assertFalse(list, list.contains("type=$obsId"))
    }

    @Test
    fun beforeObservationStaysBareAndAfterTargetsWinAnchorsAndCompression() {
        val before = searchEntryObservation(id = "screen_1700000000001_00000000", nodeId = "n3_ab12cd")
        val after = searchEntryObservation(id = "screen_1700000000002_11111111", nodeId = "n8_ef34ab")
        val data = arrayOf("beforeScreenObservationJson" to before, "afterScreenObservationJson" to after)
        val evidence = resultWith(*data).localObservationEvidencePrompt("tap")
        assertFalse(evidence, evidence.contains("screen_1700000000001_00000000/"))
        assertTrue(evidence, evidence.contains("tap=n3_ab12cd"))
        assertTrue(evidence, evidence.contains("tap=screen_1700000000002_11111111/n8_ef34ab"))
        assertEquals(listOf("screen_1700000000002_11111111/n8_ef34ab", "ocr:block:0"), evidence.targetShortlistTargetValues())
        val minimal = contextFor(*data).minimalObservationModelPrompt(registry)
        assertTrue(minimal, minimal.contains("shortlistTargets=[target=screen_1700000000002_11111111/n8_ef34ab"))
    }

    @Test
    fun productionLengthIdsSurviveMinimalPromptWhole() {
        val snapshot = "screen_1731000000123_3f9a1c7e"
        val tapId = "n57_9f3a1c7e5b_3f9a1c7e"
        val scrollId = "n61_0a1b2c3d4e_3f9a1c7e"
        val elements = (0 until 7).map { i ->
            accessibilityElement("n${i}_aa${i}bb${i}cc_3f9a1c7e", "条目$i", "button", 10, 20 + i * 60, 110, 70 + i * 60, clickable = true)
        } + listOf(
            accessibilityElement(tapId, "搜索", "button", 10, 900, 110, 960, clickable = true),
            accessibilityElement(scrollId, "列表", "list", 10, 100, 410, 800, scrollable = true)
        )
        val json = screenObservationFixtureJson(snapshot, elements.toTypedArray())
        val ctx = contextFor("afterScreenObservationJson" to json)
        val minimal = ctx.minimalObservationModelPrompt(registry)
        assertTrue(minimal, minimal.contains("$snapshot/$scrollId"))
        assertTrue(minimal, minimal.contains("scroll=$snapshot/$scrollId"))
        assertTrue(minimal, minimal.contains("tap=$snapshot/$tapId"))
        assertTrue(minimal.length.toString(), minimal.length <= 1_200 + 5 * 3 * "$snapshot/".length)
        // Every evidence value stays whole in the compressed model prompt too.
        val full = ctx.observationModelPrompt(registry)
        assertTrue(full, full.contains("scroll=$snapshot/$scrollId"))
    }

    @Test
    fun afterObservationPrecedesBeforeSoLargeEvidenceRetainsActiveScopedRefs() {
        val beforeId = "screen_1731000000100_11111111"
        val afterId = "screen_1731000000200_22222222"
        val beforeElements = (0 until 10).map { i ->
            accessibilityElement("n${i}_bb${i}bb${i}_11111111", "旧页面条目长标签内容$i", "button", 10, 20 + i * 60, 410, 70 + i * 60, clickable = true)
        }.toTypedArray()
        val afterTarget = "n0_aa0aa0_22222222"
        val afterElements = arrayOf(
            accessibilityElement(afterTarget, "搜索", "button", 10, 20, 210, 80, clickable = true)
        ) + (1 until 9).map { i ->
            accessibilityElement("n${i}_aa${i}aa${i}_22222222", "新页面结果卡片$i", "button", 10, 90 + i * 60, 410, 140 + i * 60, clickable = true)
        }.toTypedArray()
        val evidence = resultWith(
            "beforeScreenObservationJson" to screenObservationFixtureJson(beforeId, beforeElements),
            "afterScreenObservationJson" to screenObservationFixtureJson(afterId, afterElements)
        ).localObservationEvidencePrompt("搜索")
        assertTrue("after scoped ref must survive 2000-char local evidence cap:\n$evidence", evidence.contains("$afterId/$afterTarget"))
        assertTrue(evidence.targetShortlistTargetValues().any { it.startsWith("$afterId/") })
    }

    @Test
    fun emptyAfterObservationDoesNotFallBackToBeforeShortlist() {
        val beforeId = "screen_1731000000100_11111111"
        val afterId = "screen_1731000000200_22222222"
        val before = searchEntryObservation(id = beforeId, nodeId = "n3_ab12cd")
        val emptyAfter = screenObservationFixtureJson(observationId = afterId, elements = emptyArray())
        val evidence = resultWith(
            "beforeScreenObservationJson" to before,
            "afterScreenObservationJson" to emptyAfter
        ).localObservationEvidencePrompt("tap")
        assertTrue(
            "empty after observation must not expose stale before shortlist targets: ${evidence.targetShortlistTargetValues()}",
            evidence.targetShortlistTargetValues().isEmpty()
        )
    }
}
