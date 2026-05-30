package com.bytedance.zgx.pocketmind.runtime

import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.GenerationParameters
import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.MessageRole
import com.bytedance.zgx.pocketmind.RemoteModelConfig
import java.io.IOException
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChatRuntimeTest {
    @Test
    fun buildChatCompletionBody_requestsStreamingResponsesAndLimitsHistory() {
        val history = (0 until 25).map { index ->
            ChatMessage(MessageRole.User, "history-$index")
        } + ChatMessage(MessageRole.Assistant, "")
        val body = buildChatCompletionBody(
            prompt = "你好",
            history = history,
            parameters = GenerationParameters(),
            config = RemoteModelConfig("https://api.example.com/v1", "model-a"),
        )

        assertEquals("model-a", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals(22, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("PocketMind"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("用户当前输入一致的语言"))
        assertEquals("history-5", messages.getJSONObject(1).getString("content"))
        assertEquals("你好", messages.getJSONObject(21).getString("content"))
    }

    @Test
    fun buildChatCompletionBody_excludesLocalOnlyHistory() {
        val body = buildChatCompletionBody(
            prompt = "继续",
            history = listOf(
                ChatMessage(MessageRole.User, "普通历史"),
                ChatMessage(MessageRole.User, "分享来的本地内容", privacy = MessagePrivacy.LocalOnly),
                ChatMessage(MessageRole.Assistant, "本地工具摘要", privacy = MessagePrivacy.LocalOnly),
            ),
            parameters = GenerationParameters(),
            config = RemoteModelConfig("https://api.example.com/v1", "model-a"),
        )

        val encoded = body.toString()

        assertTrue(encoded.contains("普通历史"))
        assertFalse(encoded.contains("分享来的本地内容"))
        assertFalse(encoded.contains("本地工具摘要"))
    }

    @Test
    fun parseChatCompletionChunkText_readsDeltaContent() {
        assertEquals(
            "你",
            parseChatCompletionChunkText("""{"choices":[{"delta":{"content":"你"}}]}"""),
        )
        assertEquals(
            "",
            parseChatCompletionChunkText("""{"choices":[{"delta":{"content":null}}]}"""),
        )
        assertEquals(
            "",
            parseChatCompletionChunkText("""{"choices":[{"delta":{}}]}"""),
        )
    }

    @Test
    fun parseChatCompletionText_ignoresJsonNullContent() {
        assertEquals(
            "fallback",
            parseChatCompletionText("""{"choices":[{"message":{"content":null},"text":"fallback"}]}"""),
        )
    }

    @Test
    fun sendStreamsServerSentEvents() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body(
                        """
                        data: {"choices":[{"delta":{"content":null}}]}

                        data: {"choices":[{"delta":{"content":"你"}}]}

                        data: {"choices":[{"delta":{"content":"好"}}]}

                        data: [DONE]

                        """.trimIndent(),
                    )
                    .build(),
            )
            server.start()
            val runtime = OkHttpRemoteChatRuntime(OkHttpClient())

            val chunks = runtime.send(
                prompt = "你好",
                history = listOf(
                    ChatMessage(MessageRole.User, "可发送历史"),
                    ChatMessage(MessageRole.User, "仅本地历史", privacy = MessagePrivacy.LocalOnly),
                ),
                parameters = GenerationParameters(),
                config = RemoteModelConfig(server.url("/v1").toString().trimEnd('/'), "model-a"),
            ).toList()

            assertEquals("你好", chunks.joinToString(separator = ""))
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.target)
            val requestBody = request.body!!.utf8()
            assertTrue(requestBody.contains(""""stream":true"""))
            assertTrue(requestBody.contains("可发送历史"))
            assertFalse(requestBody.contains("仅本地历史"))
        }
    }

    @Test
    fun sendReadsNonSseJsonResponse() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"choices":[{"message":{"content":"完成"}}]}""")
                    .build(),
            )
            server.start()
            val runtime = OkHttpRemoteChatRuntime(OkHttpClient())

            val chunks = runtime.send(
                prompt = "继续",
                history = emptyList(),
                parameters = GenerationParameters(),
                config = RemoteModelConfig(server.url("/v1").toString().trimEnd('/'), "model-a"),
            ).toList()

            assertEquals(listOf("完成"), chunks)
        }
    }

    @Test
    fun sendRedactsNonSuccessResponseBody() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(401)
                    .body("""{"error":"secret-key should not appear"}""")
                    .build(),
            )
            server.start()
            val runtime = OkHttpRemoteChatRuntime(OkHttpClient())

            val failure = runCatching {
                runtime.send(
                    prompt = "继续",
                    history = emptyList(),
                    parameters = GenerationParameters(),
                    config = RemoteModelConfig(server.url("/v1").toString().trimEnd('/'), "model-a"),
                ).toList()
            }.exceptionOrNull()

            val message = requireNotNull(failure).message.orEmpty()
            assertTrue(message.contains("401"))
            assertFalse(message.contains("secret-key"))
        }
    }

    @Test
    fun flowCancellationCancelsUnderlyingCall() = runTest {
        val factory = BlockingCallFactory()
        val runtime = OkHttpRemoteChatRuntime(factory)

        val collection = async(Dispatchers.Default) {
            runtime.send(
                prompt = "取消",
                history = emptyList(),
                parameters = GenerationParameters(),
                config = RemoteModelConfig("https://api.example.com/v1", "model-a"),
            ).toList()
        }
        val call = factory.created.get(2, java.util.concurrent.TimeUnit.SECONDS)
        collection.cancelAndJoin()

        call.canceled.get(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    private class BlockingCallFactory : Call.Factory {
        val created = CompletableFuture<BlockingCall>()

        override fun newCall(request: Request): Call {
            val call = BlockingCall(request)
            created.complete(call)
            return call
        }
    }

    private class BlockingCall(
        private val request: Request,
    ) : Call {
        val canceled = CompletableFuture<Unit>()
        private var executed = false

        override fun request(): Request = request

        override fun execute(): Response {
            executed = true
            while (!isCanceled()) {
                Thread.sleep(10)
            }
            throw IOException("canceled")
        }

        override fun enqueue(responseCallback: Callback) {
            error("enqueue is not used")
        }

        override fun cancel() {
            canceled.complete(Unit)
        }

        override fun isExecuted(): Boolean = executed

        override fun isCanceled(): Boolean = canceled.isDone

        override fun timeout(): Timeout = Timeout.NONE

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()

        override fun clone(): Call = BlockingCall(request)
    }
}
