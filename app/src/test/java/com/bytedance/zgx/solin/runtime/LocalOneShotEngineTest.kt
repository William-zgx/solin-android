package com.bytedance.zgx.solin.runtime

import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.MessageRole
import com.google.ai.edge.litertlm.BenchmarkInfo
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOneShotEngineTest {
    private val factory = FakeLiteRtFactory()

    @Test
    fun oneShotFailsClosedWhenNoModelIsLoaded() {
        val result = runtime().generateOneShot(request())

        assertUnavailable(LocalOneShotUnavailableReason.ModelNotLoaded, result)
        assertTrue(factory.engines.isEmpty())
    }

    @Test
    fun oneShotFailsClosedWhenTheLoadedEngineServesAnotherModel() {
        val runtime = runtime().loaded()

        val result = runtime.generateOneShot(request(modelPath = "/tmp/other.litertlm"))

        assertUnavailable(LocalOneShotUnavailableReason.ModelNotLoaded, result)
        assertEquals(1, factory.engines.size)
        assertEquals("no one-shot conversation may be created", 1, factory.allConversations.size)
    }

    @Test
    fun oneShotRunsOnTheLoadedChatEngineWithAnIsolatedGreedySpec() {
        factory.reply = { spec -> if (spec.isOneShot()) FakeReply("call:", "open_app{}") else FakeReply("ok") }
        val runtime = runtime().loaded(history = listOf(ChatMessage(MessageRole.User, "earlier chat")))

        val result = runtime.generateOneShot(request(prompt = "plan this")) as LocalOneShotResult.Generated

        assertEquals("call:open_app{}", result.text)
        assertEquals(LocalEngineSource.SharedChatEngine, result.metrics.source)
        assertEquals(LocalAccelerator.CPU, result.metrics.accelerator)
        assertNull(result.metrics.loadMs)
        assertNotNull(result.metrics.firstTokenMs)
        assertEquals("one engine serves chat and one-shots", 1, factory.engines.size)
        val oneShot = factory.allConversations.single { it.spec.isOneShot() }
        assertTrue("one-shots never see chat history", oneShot.spec.history.isEmpty())
        assertEquals(0f, oneShot.spec.parameters.temperature)
        assertEquals(1, oneShot.spec.parameters.topK)
        assertEquals(listOf("plan this"), oneShot.sentPrompts)
        assertEquals(1, oneShot.closeCount)
    }

    @Test
    fun chatIsParkedForAOneShotAndRestoredFromItsTranscript() {
        val runtime = runtime().loaded(
            history = listOf(ChatMessage(MessageRole.User, "a"), ChatMessage(MessageRole.Assistant, "b")),
        )
        assertEquals("ok", runtime.chat("c"))
        val original = factory.allConversations.single()

        runtime.generateOneShot(request()) as LocalOneShotResult.Generated

        assertEquals("the engine hosts one conversation at a time", 1, original.closeCount)
        assertTrue("a parked chat still counts as loaded", runtime.isLoaded)
        assertEquals("ok", runtime.chat("e"))
        val restored = factory.allConversations.last()
        assertEquals(listOf("a", "b", "c", "ok"), restored.spec.initialMessages().map { it.text })
        assertNull(restored.spec.systemInstruction)
        assertEquals(GenerationParameters(), restored.spec.parameters)
        assertEquals(listOf("e"), restored.sentPrompts)
        assertEquals(1, factory.engines.single().openConversations().size)
    }

    @Test
    fun oneShotReturnsBusyInsteadOfPreemptingAChatGeneration() {
        val chatReply = FakeReply(listOf(LiteRtChunk("partial")), hangUntilCancelled = true)
        factory.reply = { spec -> if (spec.isOneShot()) FakeReply("plan") else chatReply }
        val runtime = runtime().loaded()
        val chatThread = thread { runCatching { runBlocking { runtime.send("hi").collect() } } }
        runBlocking { chatReply.started.await() }

        val result = runtime.generateOneShot(request())

        assertUnavailable(LocalOneShotUnavailableReason.Busy, result)
        val chat = factory.allConversations.single()
        assertEquals("the chat generation must not be cancelled", 0, chat.cancelCount)
        runtime.stop()
        chatThread.join(JOIN_TIMEOUT_MS)
        assertFalse(chatThread.isAlive)
    }

    @Test
    fun chatReclaimsTheEngineFromARunningOneShot() {
        val oneShotReply = FakeReply(listOf(LiteRtChunk("half a plan")), hangUntilCancelled = true)
        factory.reply = { spec -> if (spec.isOneShot()) oneShotReply else FakeReply("ok") }
        val runtime = runtime().loaded()
        val result = AtomicReference<LocalOneShotResult>()
        val oneShotThread = thread { result.set(runtime.generateOneShot(request())) }
        runBlocking { oneShotReply.started.await() }

        assertEquals("ok", runtime.chat("hi"))

        oneShotThread.join(JOIN_TIMEOUT_MS)
        assertUnavailable(LocalOneShotUnavailableReason.Busy, result.get())
        val oneShot = factory.allConversations.single { it.spec.isOneShot() }
        assertTrue(oneShot.cancelCount >= 1)
        assertEquals(1, oneShot.closeCount)
    }

    @Test
    fun oneShotCreationFailureIsReportedAndChatStillRestores() {
        val runtime = runtime().loaded()
        factory.nextConversationFailure = IllegalStateException("no room for a second conversation")

        val result = runtime.generateOneShot(request())

        assertUnavailable(LocalOneShotUnavailableReason.Failed, result)
        assertEquals("no room for a second conversation", (result as LocalOneShotResult.Unavailable).detail)
        assertTrue(runtime.isLoaded)
        assertEquals("ok", runtime.chat("hi"))
    }

    @Test
    fun oneShotOutputCapCancelsTheNativeDecode() {
        val endless = FakeReply(List(200) { LiteRtChunk("word ") }, hangUntilCancelled = true)
        factory.reply = { spec -> if (spec.isOneShot()) endless else FakeReply("ok") }
        val runtime = runtime().loaded()

        val result = runtime.generateOneShot(request(maxOutputTokens = 8)) as LocalOneShotResult.Generated

        val oneShot = factory.allConversations.single { it.spec.isOneShot() }
        assertEquals(1, oneShot.cancelCount)
        assertTrue(result.text.isNotEmpty())
        assertTrue("capped output must stop well short of the stream", result.text.length < 200 * "word ".length / 4)
    }

    @Test
    fun oneShotTimeoutCancelsTheDecodeAndFails() {
        factory.reply = { spec ->
            if (spec.isOneShot()) FakeReply(emptyList(), hangUntilCancelled = true) else FakeReply("ok")
        }
        val runtime = runtime(LocalEngineWaits(engineWaitMs = 200, reclaimWaitMs = 2_000, oneShotTimeoutMs = 100)).loaded()

        val result = runtime.generateOneShot(request())

        assertUnavailable(LocalOneShotUnavailableReason.Failed, result)
        assertTrue((result as LocalOneShotResult.Unavailable).detail.orEmpty().contains("timed out"))
        assertTrue(factory.allConversations.single { it.spec.isOneShot() }.cancelCount >= 1)
        assertEquals("ok", runtime.chat("hi"))
    }

    @Test
    fun oneShotCancelledFromAnotherThreadEndsTheDecodeAndRestoresChat() {
        val oneShotReply = FakeReply(listOf(LiteRtChunk("half")), hangUntilCancelled = true)
        factory.reply = { spec -> if (spec.isOneShot()) oneShotReply else FakeReply("ok") }
        val runtime = runtime().loaded()
        val request = request()
        val result = AtomicReference<LocalOneShotResult>()
        val oneShotThread = thread { result.set(runtime.generateOneShot(request)) }
        runBlocking { oneShotReply.started.await() }

        request.cancellation.cancel()

        oneShotThread.join(JOIN_TIMEOUT_MS)
        assertUnavailable(LocalOneShotUnavailableReason.Cancelled, result.get())
        val oneShot = factory.allConversations.single { it.spec.isOneShot() }
        assertTrue(oneShot.cancelCount >= 1)
        assertEquals(1, oneShot.closeCount)
        assertEquals("ok", runtime.chat("hi"))
    }

    @Test
    fun dedicatedOneShotCancelledFromAnotherThreadReturnsCancelled() {
        val hang = FakeReply(listOf(LiteRtChunk("half")), hangUntilCancelled = true)
        factory.reply = { hang }
        val engine = DedicatedLiteRtEngine(File("/tmp/cache"), factory)
        val request = request()
        val result = AtomicReference<LocalOneShotResult>()
        val oneShotThread = thread { result.set(engine.generateOneShot(request)) }
        runBlocking { hang.started.await() }

        request.cancellation.cancel()

        oneShotThread.join(JOIN_TIMEOUT_MS)
        assertUnavailable(LocalOneShotUnavailableReason.Cancelled, result.get())
        val conversation = factory.allConversations.single()
        assertTrue("dedicated native decode was not cancelled", conversation.cancelCount >= 1)
        assertEquals(1, conversation.closeCount)
    }

    @Test
    fun interruptedDedicatedOneShotCancelsNativeDecodeBeforeClose() {
        val hang = FakeReply(listOf(LiteRtChunk("half")), hangUntilCancelled = true)
        factory.reply = { hang }
        val engine = DedicatedLiteRtEngine(File("/tmp/cache"), factory)
        val result = AtomicReference<LocalOneShotResult>()
        val oneShotThread = thread { result.set(engine.generateOneShot(request())) }
        runBlocking { hang.started.await() }

        oneShotThread.interrupt()
        oneShotThread.join(JOIN_TIMEOUT_MS)

        assertFalse("interrupted dedicated one-shot is still running", oneShotThread.isAlive)
        assertUnavailable(LocalOneShotUnavailableReason.Cancelled, result.get())
        val conversation = factory.allConversations.single()
        assertTrue("abandoned dedicated decode was not cancelled", conversation.cancelCount >= 1)
        assertEquals(1, conversation.closeCount)
    }

    @Test
    fun oneShotCancelledBeforeItStartsLoadsNothing() {
        val cancelled = LocalOneShotCancellation().apply { cancel() }
        val engine = DedicatedLiteRtEngine(File("/tmp/cache"), factory)

        assertUnavailable(LocalOneShotUnavailableReason.Cancelled, engine.generateOneShot(request().copy(cancellation = cancelled)))
        assertTrue(factory.engines.isEmpty())

        val runtime = runtime().loaded()
        assertUnavailable(LocalOneShotUnavailableReason.Cancelled, runtime.generateOneShot(request().copy(cancellation = cancelled)))
        assertEquals("only the chat conversation was ever created", 1, factory.allConversations.size)
    }

    @Test
    fun interruptedOneShotWaitingForChatReturnsCancelled() {
        val chatReply = FakeReply(listOf(LiteRtChunk("partial")), hangUntilCancelled = true)
        factory.reply = { spec -> if (spec.isOneShot()) FakeReply("plan") else chatReply }
        val runtime = runtime(LocalEngineWaits(engineWaitMs = 5_000, reclaimWaitMs = 2_000, oneShotTimeoutMs = 5_000)).loaded()
        val chatThread = thread { runCatching { runBlocking { runtime.send("hi").collect() } } }
        runBlocking { chatReply.started.await() }
        val result = AtomicReference<LocalOneShotResult>()
        val oneShotThread = thread { result.set(runtime.generateOneShot(request())) }

        oneShotThread.interrupt()

        oneShotThread.join(JOIN_TIMEOUT_MS)
        assertUnavailable(LocalOneShotUnavailableReason.Cancelled, result.get())
        assertEquals("the chat generation must not be cancelled", 0, factory.allConversations.single().cancelCount)
        runtime.stop()
        chatThread.join(JOIN_TIMEOUT_MS)
    }

    @Test
    fun reclaimedOneShotIsBusyEvenWhenTheNativeDecodeEndsQuietly() {
        val oneShotReply = FakeReply(listOf(LiteRtChunk("half a plan")), hangUntilCancelled = true, endsQuietlyOnCancel = true)
        factory.reply = { spec -> if (spec.isOneShot()) oneShotReply else FakeReply("ok") }
        val runtime = runtime().loaded()
        val result = AtomicReference<LocalOneShotResult>()
        val oneShotThread = thread { result.set(runtime.generateOneShot(request())) }
        runBlocking { oneShotReply.started.await() }

        assertEquals("ok", runtime.chat("hi"))

        oneShotThread.join(JOIN_TIMEOUT_MS)
        assertUnavailable(LocalOneShotUnavailableReason.Busy, result.get())
    }

    @Test
    fun timedOutDecodeIsNeverReportedAsGenerated() {
        factory.reply = { spec ->
            if (spec.isOneShot()) {
                FakeReply(listOf(LiteRtChunk("trunc")), hangUntilCancelled = true, endsQuietlyOnCancel = true)
            } else {
                FakeReply("ok")
            }
        }
        val runtime = runtime(LocalEngineWaits(engineWaitMs = 200, reclaimWaitMs = 2_000, oneShotTimeoutMs = 100)).loaded()

        assertUnavailable(LocalOneShotUnavailableReason.Failed, runtime.generateOneShot(request()))
    }

    @Test
    fun consecutiveOneShotsNeverShareAConversation() {
        val runtime = runtime().loaded(history = listOf(ChatMessage(MessageRole.User, "private history")))

        runtime.generateOneShot(request()) as LocalOneShotResult.Generated
        runtime.generateOneShot(request()) as LocalOneShotResult.Generated

        val oneShots = factory.allConversations.filter { it.spec.isOneShot() }
        assertEquals(2, oneShots.size)
        assertTrue(oneShots.all { it.spec.history.isEmpty() && it.closeCount == 1 })
        assertNotSame(oneShots[0], oneShots[1])
    }

    @Test
    fun changedModelCapabilitiesRecreateTheConversation() {
        val runtime = runtime().loaded()
        runtime.chat("hi")

        runtime.configureModelCapabilities(LocalModelRuntimeCapabilities(contextWindowTokens = 4_096))
        runtime.recreateConversationForSend(emptyList(), "next", GenerationParameters(), null)

        assertFreshConversation()
    }

    @Test
    fun nativeToolCallsAreReturnedAndToolsAreOnlyDeclared() {
        val tools = listOf(LocalToolDeclaration("open_app", "Open an app", """{"type":"object"}"""))
        val call = LocalToolCall("open_app", """{"app":"Settings"}""")
        factory.reply = { spec ->
            if (spec.isOneShot()) FakeReply(listOf(LiteRtChunk("", toolCalls = listOf(call)))) else FakeReply("ok")
        }
        val runtime = runtime().loaded()

        val result = runtime.generateOneShot(request(tools = tools)) as LocalOneShotResult.Generated

        assertEquals(listOf(call), result.toolCalls)
        assertNotNull(result.metrics.firstTokenMs)
        assertEquals(tools, factory.allConversations.single { it.spec.isOneShot() }.spec.tools)
    }

    @Test
    fun matchingSendsReuseTheLiveConversation() {
        val runtime = runtime().loaded()
        val live = factory.allConversations.single()

        runtime.recreateConversationForSend(emptyList(), "hi", GenerationParameters(), maxInputTokens = null)
        runtime.chat("hi")
        assertTrue(runtime.lastCallMetrics()!!.conversationReused)
        runtime.recreateConversationForSend(
            history = listOf(ChatMessage(MessageRole.User, "hi"), ChatMessage(MessageRole.Assistant, "ok")),
            prompt = "next",
            parameters = GenerationParameters(),
            maxInputTokens = null,
        )
        runtime.chat("next")

        assertSame(live, factory.allConversations.single())
        assertEquals(listOf("hi", "next"), live.sentPrompts)
        assertTrue(runtime.lastCallMetrics()!!.conversationReused)
    }

    @Test
    fun changedSamplingRecreatesTheConversation() {
        val runtime = runtime().loaded()

        runtime.recreateConversationForSend(emptyList(), "hi", GenerationParameters(temperature = 0.2f), null)

        assertFreshConversation()
    }

    @Test
    fun divergedHistoryRecreatesTheConversation() {
        val runtime = runtime().loaded()
        runtime.chat("hi")

        runtime.recreateConversationForSend(
            history = listOf(ChatMessage(MessageRole.User, "hi"), ChatMessage(MessageRole.Assistant, "edited")),
            prompt = "next",
            parameters = GenerationParameters(),
            maxInputTokens = null,
        )

        assertFreshConversation()
    }

    @Test
    fun cancelledSendIsNeverReused() {
        val chatReply = FakeReply(listOf(LiteRtChunk("partial")), hangUntilCancelled = true)
        factory.reply = { chatReply }
        val runtime = runtime().loaded()
        val chatThread = thread { runCatching { runBlocking { runtime.send("hi").collect() } } }
        runBlocking { chatReply.started.await() }
        runtime.stop()
        chatThread.join(JOIN_TIMEOUT_MS)
        factory.reply = { FakeReply("ok") }

        runtime.recreateConversationForSend(emptyList(), "again", GenerationParameters(), null)

        assertFreshConversation()
    }

    @Test
    fun failedSendIsNeverReused() {
        factory.reply = { FakeReply(listOf(LiteRtChunk("par")), failure = IllegalStateException("decode failed")) }
        val runtime = runtime().loaded()
        assertTrue(runCatching { runtime.chat("hi") }.exceptionOrNull() is IllegalStateException)
        factory.reply = { FakeReply("ok") }

        runtime.recreateConversationForSend(emptyList(), "again", GenerationParameters(), null)

        assertFreshConversation()
    }

    @Test
    fun abandonedChatFlowCancelsTheNativeDecode() {
        factory.reply = { FakeReply(listOf(LiteRtChunk("a"), LiteRtChunk("b")), hangUntilCancelled = true) }
        val runtime = runtime().loaded()

        runBlocking {
            runCatching { runtime.send("hi").collect { throw CancellationException("ui left") } }
        }

        assertEquals(1, factory.allConversations.single().cancelCount)
    }

    @Test
    fun explicitRecreateAndNewLoadsAlwaysStartFresh() {
        val runtime = runtime().loaded()
        runtime.recreateConversation(emptyList(), GenerationParameters())
        assertFreshConversation()

        runtime.recreateConversationForSend(emptyList(), "hi", GenerationParameters(), null)
        runtime.load("/tmp/other.litertlm", BackendChoice.GPU, emptyList(), GenerationParameters())
        runtime.chat("hi")

        assertEquals(1, factory.engines.first().closeCount)
        assertFalse(runtime.lastCallMetrics()!!.conversationReused)
        assertEquals(LocalAccelerator.GPU, runtime.lastCallMetrics()!!.accelerator)
    }

    @Test
    fun oneShotParkInvalidatesReuse() {
        val runtime = runtime().loaded()
        runtime.generateOneShot(request()) as LocalOneShotResult.Generated

        runtime.recreateConversationForSend(emptyList(), "hi", GenerationParameters(), null)
        runtime.chat("hi")

        assertEquals(3, factory.allConversations.size)
        assertFalse(runtime.lastCallMetrics()!!.conversationReused)
    }

    @Test
    fun chatMetricsCarryNativeTokenCountsOnlyWhenBenchmarkModeIsOn() {
        val runtime = runtime().loaded()
        runtime.chat("hi")
        val withoutBenchmark = runtime.lastCallMetrics()!!
        assertEquals(LocalEngineSource.SharedChatEngine, withoutBenchmark.source)
        assertNotNull(withoutBenchmark.firstTokenMs)
        assertNull(withoutBenchmark.inputTokens)
        assertNull(withoutBenchmark.outputTokens)

        factory.benchmark = BenchmarkInfo(0.5, 0.1, 12, 3, 120.0, 30.0)
        runtime.recreateConversation(emptyList(), GenerationParameters())
        runtime.chat("hi")

        assertEquals(12, runtime.lastCallMetrics()!!.inputTokens)
        assertEquals(3, runtime.lastCallMetrics()!!.outputTokens)
    }

    @Test
    fun callMetricsCannotCarryPromptOrOutputText() {
        val textFields = LocalCallMetrics::class.java.declaredFields.filter { it.type == String::class.java }
        assertTrue("metrics must stay text-free: $textFields", textFields.isEmpty())
    }

    @Test
    fun interruptedOneShotDecodeClosesAndRestoresChat() {
        val hanging = FakeReply(listOf(LiteRtChunk("partial")), hangUntilCancelled = true)
        factory.reply = { spec -> if (spec.isOneShot()) hanging else FakeReply("chat") }
        val runtime = runtime().loaded()
        val result = AtomicReference<LocalOneShotResult>()
        val oneShotThread = thread { result.set(runtime.generateOneShot(request())) }
        runBlocking { hanging.started.await() }

        oneShotThread.interrupt()
        oneShotThread.join(JOIN_TIMEOUT_MS)

        assertFalse("interrupted one-shot is still running", oneShotThread.isAlive)
        assertUnavailable(LocalOneShotUnavailableReason.Cancelled, result.get())
        val oneShot = factory.allConversations.single { it.spec.isOneShot() }
        assertTrue(oneShot.cancelCount >= 1)
        assertEquals(1, oneShot.closeCount)
        assertEquals("chat", runtime.chat("after interrupt"))
    }

    @Test
    fun memoryAdapterUsesFreshEmptyConversations() = runBlocking {
        factory.reply = { spec -> if (spec.isOneShot()) FakeReply("summary") else FakeReply("chat") }
        val model = LocalEngineMemoryModel(
            runtime().loaded(history = listOf(ChatMessage(MessageRole.User, "private chat history"))),
            MODEL,
        )

        assertEquals("summary", model.generate(PLANNER_INSTRUCTION, "first memory call"))
        assertEquals("summary", model.generate(PLANNER_INSTRUCTION, "second memory call"))

        val oneShots = factory.allConversations.filter { it.spec.isOneShot() }
        assertEquals(2, oneShots.size)
        assertTrue(oneShots.all { it.spec.history.isEmpty() && it.closeCount == 1 })
        assertNotSame(oneShots[0], oneShots[1])
        assertEquals(listOf("first memory call", "second memory call"), oneShots.map { it.sentPrompts.single() })
    }

    @Test
    fun memoryAdapterCancellationStopsNativeDecode() = runBlocking {
        val hanging = FakeReply(listOf(LiteRtChunk("partial")), hangUntilCancelled = true)
        factory.reply = { spec -> if (spec.isOneShot()) hanging else FakeReply("chat") }
        val model = LocalEngineMemoryModel(runtime().loaded(), MODEL)
        val call = launch { model.generate(PLANNER_INSTRUCTION, "wait for cancellation") }
        hanging.started.await()

        withTimeout(JOIN_TIMEOUT_MS) { call.cancelAndJoin() }

        val oneShot = factory.allConversations.single { it.spec.isOneShot() }
        assertTrue("native decode was not cancelled", oneShot.cancelCount >= 1)
        withTimeout(JOIN_TIMEOUT_MS) {
            while (oneShot.closeCount == 0) delay(10)
        }
        assertEquals(1, oneShot.closeCount)
    }

    @Test
    fun dedicatedEngineLoadsOnceOnCpuWithModelDefaults() {
        val engine = DedicatedLiteRtEngine(File("/tmp/cache"), factory)

        val first = engine.generateOneShot(request()) as LocalOneShotResult.Generated
        val second = engine.generateOneShot(request()) as LocalOneShotResult.Generated

        assertEquals(LocalEngineSource.DedicatedEngine, first.metrics.source)
        assertEquals(LocalAccelerator.CPU, first.metrics.accelerator)
        assertNotNull(first.metrics.loadMs)
        assertNull("the second call reuses the loaded engine", second.metrics.loadMs)
        assertEquals(BackendChoice.CPU, factory.engines.single().backend)
        assertEquals(listOf<LocalModelRuntimeCapabilities?>(null), factory.requestedCapabilities.toList())
        assertTrue(factory.allConversations.all { it.closeCount == 1 })
    }

    @Test
    fun dedicatedEngineSwapsModelsAndReleasesOnClose() {
        val engine = DedicatedLiteRtEngine(File("/tmp/cache"), factory)
        engine.generateOneShot(request())
        engine.generateOneShot(request(modelPath = "/tmp/other.litertlm"))

        assertEquals(1, factory.engines[0].closeCount)
        engine.close()
        engine.close()
        assertEquals(1, factory.engines[1].closeCount)
    }

    @Test
    fun chatLoadReleasesTheDedicatedCopyOfTheSameModelOnly() {
        val fallback = DedicatedLiteRtEngine(File("/tmp/cache"), factory)
        fallback.generateOneShot(request()) as LocalOneShotResult.Generated
        val runtime = RealLiteRtRuntime(File("/tmp/cache"), null, factory, LocalEngineWaits(), fallback)

        runtime.load("/tmp/other.litertlm", BackendChoice.CPU, emptyList(), GenerationParameters())
        assertEquals("chat serves another model; the dedicated copy stays", 0, factory.engines[0].closeCount)

        runtime.load(MODEL, BackendChoice.CPU, emptyList(), GenerationParameters())
        assertEquals("chat now serves this model; no double hold", 1, factory.engines[0].closeCount)
    }

    @Test
    fun dedicatedEngineInitFailureLeavesNothingLoaded() {
        val engine = DedicatedLiteRtEngine(File("/tmp/cache"), factory)
        factory.nextInitializeFailure = IllegalStateException("model file unreadable")

        assertUnavailable(LocalOneShotUnavailableReason.Failed, engine.generateOneShot(request()))
        assertEquals(1, factory.engines.single().closeCount)

        engine.generateOneShot(request()) as LocalOneShotResult.Generated
        assertEquals(2, factory.engines.size)
    }

    private fun assertFreshConversation() {
        val conversations = factory.allConversations
        assertTrue("expected a recreated conversation", conversations.size >= 2)
        assertEquals("the replaced conversation is closed", 1, conversations[conversations.size - 2].closeCount)
        assertEquals(0, conversations.last().closeCount)
    }

    private fun assertUnavailable(expected: LocalOneShotUnavailableReason, result: LocalOneShotResult?) {
        assertTrue("expected $expected but was $result", result is LocalOneShotResult.Unavailable)
        assertEquals(expected, (result as LocalOneShotResult.Unavailable).reason)
    }

    private fun runtime(
        waits: LocalEngineWaits = LocalEngineWaits(engineWaitMs = 200, reclaimWaitMs = 2_000, oneShotTimeoutMs = 5_000),
    ) = RealLiteRtRuntime(File("/tmp/cache"), null, factory, waits)

    private fun RealLiteRtRuntime.loaded(history: List<ChatMessage> = emptyList()): RealLiteRtRuntime =
        apply { load(MODEL, BackendChoice.CPU, history, GenerationParameters()) }

    private fun RealLiteRtRuntime.chat(prompt: String): String =
        runBlocking { send(prompt).toList().joinToString(separator = "") }

    private fun LiteRtConversationSpec.isOneShot(): Boolean = systemInstruction == PLANNER_INSTRUCTION

    private fun request(
        modelPath: String = MODEL,
        prompt: String = "plan this",
        tools: List<LocalToolDeclaration> = emptyList(),
        maxOutputTokens: Int = LocalOneShotRequest.DEFAULT_MAX_OUTPUT_TOKENS,
    ) = LocalOneShotRequest(modelPath, PLANNER_INSTRUCTION, prompt, tools, maxOutputTokens)

    private companion object {
        const val MODEL = "/tmp/model.litertlm"
        const val PLANNER_INSTRUCTION = "planner"
        const val JOIN_TIMEOUT_MS = 5_000L
    }
}
