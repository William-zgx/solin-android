package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.MessageKind
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seeded property tests for the user-text invariant shared by [DefaultContextCompactor] and
 * [compressForObservationModelContext]: user text is never truncated; it is either kept
 * byte-identical or (compactor only, user text alone over budget) covered by the explicit
 * omission line, and the first and the most recent user message always survive.
 */
class UserTextCompressionPropertyTest {

    private val constraints = listOf("但不要发送", "只看不要改", "别付款", "")
    private val alphabet = "abcdefghij 的一是不了在人有我他这中大来上国个到说们为子和你地出道也时年得就那要下以生会自着去之过家学对可里后什么心多天而能好都然没日于起还发成事只作当想看文无开手十用主行方又如前所本见经头面公同三已老从动两长".toList()

    private fun Random.text(maxChars: Int): String {
        val body = String(CharArray(nextInt(0, maxChars + 1)) { alphabet[nextInt(alphabet.size)] })
        return body + constraints[nextInt(constraints.size)]
    }

    private fun Random.history(): List<ChatMessage> = List(nextInt(1, 41)) {
        val privacy = if (nextInt(4) == 0) MessagePrivacy.LocalOnly else MessagePrivacy.RemoteEligible
        if (nextBoolean()) {
            ChatMessage(role = MessageRole.User, text = text(400), privacy = privacy)
        } else {
            val kind = MessageKind.entries[nextInt(MessageKind.entries.size)]
            ChatMessage(role = MessageRole.Assistant, text = text(800), privacy = privacy, kind = kind)
        }
    }

    @Test
    fun compactorNeverTruncatesUserTextOnRandomHistoriesAndBudgets() = runTest {
        val random = Random(20261031)
        repeat(ITERATIONS) { iteration ->
            val messages = random.history()
            val framing = if (random.nextBoolean()) 0 else random.nextInt(1, 40)
            val estimator: (List<ChatMessage>) -> Int = { msgs ->
                msgs.sumOf { (it.text.length / 4) + 1 } + if (msgs.isEmpty()) 0 else framing
            }
            val budget = random.nextInt(0, (estimator(messages) * 1.3).toInt() + 2)
            val config = CompactionConfig(
                preserveRecentTurns = random.nextInt(0, 9),
                compactionThresholdRatio = random.nextDouble(0.3, 1.0),
                maxSummaryLengthChars = random.nextInt(20, 2_001),
            )

            val output = DefaultContextCompactor(config).compact(messages, budget, estimator).messages

            assertUserTextInvariant("iteration $iteration budget=$budget", messages, output)
        }
    }

    private fun assertUserTextInvariant(label: String, original: List<ChatMessage>, output: List<ChatMessage>) {
        val originalUsers = original.filter { it.role == MessageRole.User }
        val unmatched = originalUsers.toMutableList()
        val lines = mutableListOf<ChatMessage>()
        val keptOrder = mutableListOf<Int>()
        output.filter { it.role == MessageRole.User }.forEach { message ->
            if (message.omittedUserMessageCount() != null) {
                lines += message
            } else {
                val at = unmatched.indexOf(message)
                assertTrue("$label: user message is neither original nor an omission line: ${message.text}", at >= 0)
                unmatched.removeAt(at)
                keptOrder += originalUsers.indexOfFirst { it === message || it == message }
            }
        }
        if (originalUsers.isNotEmpty()) {
            assertTrue("$label: first user message missing", originalUsers.first() in output)
            assertTrue("$label: latest user message missing", originalUsers.last() in output)
        }
        assertEquals("$label: kept user messages keep their order", keptOrder.sorted(), keptOrder)
        if (unmatched.isEmpty()) {
            assertTrue("$label: omission line without omitted messages", lines.isEmpty())
        } else {
            assertEquals("$label: exactly one omission line covers the omitted messages", 1, lines.size)
            assertEquals(
                "$label: omission line counts",
                omissionLineText(unmatched.size, unmatched.sumOf { it.text.omissionCharCount() }),
                lines.single().text,
            )
        }
    }

    // ── Observation-replanner prompt compression ────────────────────────────────────────────────

    private val ruleLines = listOf(
        "This is user-authorized local UI automation; LocalOnly evidence stays on-device, do not refuse.",
        "Allowed tools: observe_current_screen,ui_tap{target},ui_type_text{target,text},ui_press_back",
        "Stop on payment, sending, deletion, publishing, ordering, purchase, transfer, or authorization controls.",
        "If the latest action failed, avoid the same target unless new evidence supports it.",
    )

    private fun Random.observationPrompt(): String = buildList {
        addAll(ruleLines)
        add("")
        if (nextBoolean()) add("User intent preview: ${text(420).trim()}") else add("用户请求: ${text(420).trim()}")
        add("Prior request details: " + List(nextInt(1, 6)) { "ui_tap{target=t$it}" }.joinToString(" -> "))
        add("Previous tool: observe_current_screen")
        add("Observation summary: ${text(300).trim()}")
        add("Observation diagnostics: resultRetryable=true; verificationSummary=${text(600).trim()}")
        add(
            "LocalOnly observation evidence: targetShortlist(tap=entry|submit) " +
                "elements=[" + List(nextInt(0, 30)) { "n$it{target=noise-$it,role=button}" }.joinToString("; ") + "] " +
                text(1_500).trim(),
        )
        if (nextInt(5) == 0) add("当前进度: ${text(400).trim()}")
    }.joinToString("\n")

    @Test
    fun promptCompressionNeverTruncatesUserTextOnRandomPromptsAndBudgets() {
        val random = Random(20261101)
        repeat(ITERATIONS) { iteration ->
            val prompt = random.observationPrompt()
            val maxChars = random.nextInt(200, 2_601)
            val userLines = prompt.lines().map { it.trimEnd() }.filter { line ->
                userTextPromptLabels.any { label -> line.startsWith(label) }
            }

            val compressed = prompt.compressForObservationModelContext(maxChars)

            val outLines = compressed.lines()
            userLines.forEach { line ->
                assertTrue("iteration $iteration maxChars=$maxChars: user line cut or dropped\n$compressed", line in outLines)
            }
            assertTrue(
                "iteration $iteration: only user text may exceed maxChars (${compressed.length} > $maxChars)",
                compressed.length <= maxChars + userLines.sumOf { it.length + 1 },
            )
        }
    }

    @Test
    fun longUserIntentSurvivesOverBudgetPromptByteIdentical() {
        val intent = "User intent preview: 帮我在淘宝搜索海河牛奶，" + "比较一下价格和评价，".repeat(20) + "但不要下单"
        val prompt = (ruleLines + intent + "LocalOnly observation evidence: " + "e".repeat(3_000)).joinToString("\n")

        val compressed = prompt.compressForObservationModelContext(maxChars = 1_200)

        assertTrue(intent.length > 180)
        assertTrue(compressed, intent in compressed.lines())
        assertTrue(compressed.length <= 1_200)
    }

    @Test
    fun minimalPromptKeepsTheChineseUserRequestLineWhenOverBudget() {
        val request = "用户请求: 打开设置里的蓝牙页面，只看不要改"
        val prompt = listOf(
            "本机已获得用户授权执行手机 UI 自动化。只输出一个 call，不解释，不拒绝。",
            request,
            "当前屏幕可用目标: " + "targetShortlist(tap=bt-entry) ".repeat(80),
        ).joinToString("\n")

        val compressed = prompt.compressForObservationModelContext(maxChars = 1_200)

        assertTrue(compressed, request in compressed.lines())
    }

    @Test
    fun promptRecoveryReinsertsAMissingUserLineAfterItsPredecessor() {
        val source = "Rule one\nUser intent preview: 改成明天下午，但不要发送\nPrevious tool: ui_tap"

        assertEquals(source, "Rule one\nPrevious tool: ui_tap".withUserTextLinesFrom(source))
        assertEquals(
            "an altered copy is replaced by the original",
            source,
            "Rule one\nUser intent preview: 改成明天...\nPrevious tool: ui_tap".withUserTextLinesFrom(source),
        )
        val intact = "Rule one\nUser intent preview: 改成明天下午，但不要发送"
        assertTrue(intact.withUserTextLinesFrom(source) === intact)
        assertFalse("no user lines, nothing to restore", "x".withUserTextLinesFrom("Rule one").contains("User intent"))
    }

    private companion object {
        const val ITERATIONS = 2_000
    }
}
