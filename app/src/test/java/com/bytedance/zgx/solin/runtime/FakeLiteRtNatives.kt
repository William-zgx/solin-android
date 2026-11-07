package com.bytedance.zgx.solin.runtime

import com.bytedance.zgx.solin.BackendChoice
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.ExperimentalApi
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * JVM stand-ins for the native LiteRT engine and conversation. Like the real 0.12.0 Flow, a reply
 * that hangs only ends when [FakeLiteRtConversation.cancelProcess] is called, and then ends with a
 * [CancellationException].
 */
internal class FakeLiteRtFactory : LiteRtEngineFactory {
    val engines = CopyOnWriteArrayList<FakeLiteRtEngine>()
    val requestedCapabilities = CopyOnWriteArrayList<LocalModelRuntimeCapabilities?>()
    @Volatile var nextInitializeFailure: Throwable? = null
    @Volatile var nextConversationFailure: Throwable? = null

    /** The reply each new conversation gives; consulted once per conversation. */
    @Volatile var reply: (LiteRtConversationSpec) -> FakeReply = { FakeReply("ok") }

    @Volatile var benchmark: BenchmarkInfo? = null

    override fun create(
        modelPath: String,
        backend: BackendChoice,
        cacheDir: File,
        capabilities: LocalModelRuntimeCapabilities?,
    ): LiteRtEngineHandle {
        requestedCapabilities += capabilities
        return FakeLiteRtEngine(this, modelPath, backend).also(engines::add)
    }

    val allConversations: List<FakeLiteRtConversation>
        get() = engines.flatMap { it.conversations }
}

internal class FakeLiteRtEngine(
    private val factory: FakeLiteRtFactory,
    val modelPath: String,
    val backend: BackendChoice,
) : LiteRtEngineHandle {
    val conversations = CopyOnWriteArrayList<FakeLiteRtConversation>()
    @Volatile var closeCount = 0

    override fun initialize() {
        factory.nextInitializeFailure?.let { failure ->
            factory.nextInitializeFailure = null
            throw failure
        }
    }

    override fun createConversation(config: LiteRtConversationSpec): LiteRtConversationHandle {
        check(closeCount == 0) { "conversation created on a closed engine" }
        factory.nextConversationFailure?.let { failure ->
            factory.nextConversationFailure = null
            throw failure
        }
        return FakeLiteRtConversation(config, factory.reply(config), factory.benchmark).also(conversations::add)
    }

    override fun close() {
        closeCount += 1
    }

    fun openConversations(): List<FakeLiteRtConversation> = conversations.filter { it.closeCount == 0 }
}

internal data class FakeReply(
    val chunks: List<LiteRtChunk>,
    val failure: Throwable? = null,
    val hangUntilCancelled: Boolean = false,
    /** Completed once the send starts, so a test can act mid-generation. */
    val started: CompletableDeferred<Unit> = CompletableDeferred(),
    /** After a cancel, end the Flow normally with the partial output instead of throwing. */
    val endsQuietlyOnCancel: Boolean = false,
) {
    constructor(vararg texts: String) : this(texts.map { LiteRtChunk(it) })
}

internal class FakeLiteRtConversation(
    val spec: LiteRtConversationSpec,
    private val reply: FakeReply,
    private val benchmark: BenchmarkInfo?,
) : LiteRtConversationHandle {
    val sentPrompts = CopyOnWriteArrayList<String>()
    @Volatile var cancelCount = 0
    @Volatile var closeCount = 0
    private val cancelled = CompletableDeferred<Unit>()

    override fun sendMessageAsync(request: LocalModelRequest): Flow<String> =
        sendOneShot(request.prompt).map { chunk -> chunk.text }

    override fun sendOneShot(prompt: String): Flow<LiteRtChunk> = flow {
        check(closeCount == 0) { "send on a closed conversation" }
        sentPrompts += prompt
        reply.started.complete(Unit)
        reply.chunks.forEach { chunk -> emit(chunk) }
        reply.failure?.let { failure -> throw failure }
        if (reply.hangUntilCancelled) {
            cancelled.await()
            if (reply.endsQuietlyOnCancel) return@flow
            throw CancellationException("cancelled natively")
        }
    }

    @OptIn(ExperimentalApi::class)
    override fun benchmarkInfo(): BenchmarkInfo = benchmark ?: error("benchmark mode is off")

    override fun cancelProcess() {
        cancelCount += 1
        cancelled.complete(Unit)
    }

    override fun close() {
        closeCount += 1
    }
}
