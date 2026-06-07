package com.bytedance.zgx.pocketmind.runtime

import com.bytedance.zgx.pocketmind.ChatImageAttachment
import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.GenerationParameters
import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.MessageRole
import com.bytedance.zgx.pocketmind.RemoteModelConfig
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
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
import org.json.JSONObject
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
        assertTrue(messages.getJSONObject(0).getString("content").contains("优先调用合适工具获取证据"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("多个独立工具调用"))
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
    fun buildChatCompletionBody_serializesToolSpecAsOpenAiFunctionTool() {
        val body = buildChatCompletionBody(
            prompt = "搜索 Kotlin",
            history = emptyList(),
            parameters = GenerationParameters(),
            config = RemoteModelConfig("https://api.example.com/v1", "model-a"),
            tools = ToolRegistry.fromSupportedActions(setOf(MobileActionFunctions.WEB_SEARCH)).specs(),
        )

        val tool = body.getJSONArray("tools").getJSONObject(0)
        val function = tool.getJSONObject("function")

        assertEquals("function", tool.getString("type"))
        assertEquals(MobileActionFunctions.WEB_SEARCH, function.getString("name"))
        assertTrue(function.getString("description").isNotBlank())
        assertTrue(function.getString("description").contains("多主体比较"))
        assertTrue(function.getString("description").contains("独立 web_search 工具调用"))
        assertEquals("object", function.getJSONObject("parameters").getString("type"))
        assertEquals("auto", body.getString("tool_choice"))
    }

    @Test
    fun buildChatCompletionBodySerializesImageAttachmentsAsOpenAiContentParts() {
        val body = buildChatCompletionBody(
            prompt = "描述这张图",
            history = emptyList(),
            parameters = GenerationParameters(),
            config = RemoteModelConfig("https://api.example.com/v1", "model-a", supportsVisionInput = true),
            imageAttachments = listOf(
                ChatImageAttachment(
                    mimeType = "image/png",
                    dataUrl = "data:image/png;base64,AA==",
                ),
            ),
        )

        val userMessage = body.getJSONArray("messages").getJSONObject(1)
        val content = userMessage.getJSONArray("content")

        assertEquals("user", userMessage.getString("role"))
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("描述这张图", content.getJSONObject(0).getString("text"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/png;base64,AA==",
            content.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun buildChatCompletionBodyRejectsImagesWhenRemoteProfileDisablesVision() {
        val error = runCatching {
            buildChatCompletionBody(
                prompt = "描述这张图",
                history = emptyList(),
                parameters = GenerationParameters(),
                config = RemoteModelConfig(
                    baseUrl = "https://api.example.com/v1",
                    modelName = "text-only",
                    supportsVisionInput = false,
                ),
                imageAttachments = listOf(
                    ChatImageAttachment(
                        mimeType = "image/png",
                        dataUrl = "data:image/png;base64,AA==",
                    ),
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("未启用图片输入能力"))
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
    fun parseChatCompletionEvents_readsNonStreamingToolCall() {
        val events = parseChatCompletionEvents(
            """
            {
              "choices": [
                {
                  "message": {
                    "tool_calls": [
                      {
                        "id": "call-1",
                        "type": "function",
                        "function": {
                          "name": "web_search",
                          "arguments": "{\"query\":\"Kotlin\"}"
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val toolCall = events.single() as RemoteChatEvent.ToolCall
        assertEquals("call-1", toolCall.request.id)
        assertEquals(MobileActionFunctions.WEB_SEARCH, toolCall.request.toolName)
        assertEquals(mapOf("query" to "Kotlin"), toolCall.request.arguments)
    }

    @Test
    fun parseChatCompletionEventsReadsNonStreamingMultipleToolCallsAsBatch() {
        val events = parseChatCompletionEvents(
            """
            {
              "choices": [
                {
                  "message": {
                    "tool_calls": [
                      {
                        "id": "call-beijing",
                        "type": "function",
                        "function": {
                          "name": "web_search",
                          "arguments": "{\"query\":\"北京天气\"}"
                        }
                      },
                      {
                        "id": "call-shanghai",
                        "type": "function",
                        "function": {
                          "name": "web_search",
                          "arguments": "{\"query\":\"上海天气\"}"
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val toolCalls = events.single() as RemoteChatEvent.ToolCalls
        assertEquals(2, toolCalls.requests.size)
        assertEquals(listOf("call-beijing", "call-shanghai"), toolCalls.requests.map { request -> request.id })
        assertEquals(
            listOf(MobileActionFunctions.WEB_SEARCH, MobileActionFunctions.WEB_SEARCH),
            toolCalls.requests.map { request -> request.toolName },
        )
        assertEquals(
            listOf(mapOf("query" to "北京天气"), mapOf("query" to "上海天气")),
            toolCalls.requests.map { request -> request.arguments },
        )
    }

    @Test
    fun parseChatCompletionEventsRejectsMixedToolCallFormats() {
        val events = parseChatCompletionEvents(
            """
            {
              "choices": [
                {
                  "message": {
                    "tool_calls": [
                      {
                        "id": "call-1",
                        "type": "function",
                        "function": {
                          "name": "web_search",
                          "arguments": "{\"query\":\"Kotlin\"}"
                        }
                      }
                    ],
                    "function_call": {
                      "name": "open_wifi_settings",
                      "arguments": "{}"
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val error = events.single() as RemoteChatEvent.ParseError
        assertTrue(error.summary.contains("多种工具调用格式"))
    }

    @Test
    fun remoteToolCallAccumulatorCombinesFragmentedArguments() {
        val accumulator = RemoteToolCallAccumulator()

        accumulator.absorb("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"web_search","arguments":""}}]}}]}""")
        accumulator.absorb("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\""}}]}}]}""")
        accumulator.absorb("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\"Kotlin\"}"}}]}}]}""")

        val toolCall = accumulator.finish().single() as RemoteChatEvent.ToolCall
        assertEquals("call-1", toolCall.request.id)
        assertEquals(MobileActionFunctions.WEB_SEARCH, toolCall.request.toolName)
        assertEquals(mapOf("query" to "Kotlin"), toolCall.request.arguments)
    }

    @Test
    fun remoteToolCallAccumulatorCombinesUnindexedContinuationFragments() {
        val accumulator = RemoteToolCallAccumulator()

        accumulator.absorb("""{"choices":[{"delta":{"tool_calls":[{"id":"call-1","type":"function","function":{"name":"web_search","arguments":""}}]}}]}""")
        accumulator.absorb("""{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{\"query\""}}]}}]}""")
        accumulator.absorb("""{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":":\"Kotlin\"}"}}]}}]}""")

        val toolCall = accumulator.finish().single() as RemoteChatEvent.ToolCall
        assertEquals("call-1", toolCall.request.id)
        assertEquals(MobileActionFunctions.WEB_SEARCH, toolCall.request.toolName)
        assertEquals(mapOf("query" to "Kotlin"), toolCall.request.arguments)
    }

    @Test
    fun remoteToolCallAccumulatorEmitsMultipleIndexedToolCallsAsBatch() {
        val accumulator = RemoteToolCallAccumulator()

        accumulator.absorb(
            """
            {"choices":[{"delta":{"tool_calls":[
              {"index":0,"id":"call-1","type":"function","function":{"name":"web_search","arguments":"{}"}},
              {"index":1,"id":"call-2","type":"function","function":{"name":"open_wifi_settings","arguments":"{}"}}
            ]}}]}
            """.trimIndent(),
        )

        val toolCalls = accumulator.finish().single() as RemoteChatEvent.ToolCalls
        assertEquals(2, toolCalls.requests.size)
        assertEquals(listOf("call-1", "call-2"), toolCalls.requests.map { request -> request.id })
        assertEquals(
            listOf(MobileActionFunctions.WEB_SEARCH, MobileActionFunctions.OPEN_WIFI_SETTINGS),
            toolCalls.requests.map { request -> request.toolName },
        )
    }

    @Test
    fun remoteToolCallAccumulatorEmitsUnindexedDistinctToolCallsAsBatch() {
        val accumulator = RemoteToolCallAccumulator()

        accumulator.absorb("""{"choices":[{"delta":{"tool_calls":[{"id":"call-1","type":"function","function":{"name":"web_search","arguments":"{}"}}]}}]}""")
        accumulator.absorb("""{"choices":[{"delta":{"tool_calls":[{"id":"call-2","type":"function","function":{"name":"open_wifi_settings","arguments":"{}"}}]}}]}""")

        val toolCalls = accumulator.finish().single() as RemoteChatEvent.ToolCalls
        assertEquals(2, toolCalls.requests.size)
        assertEquals(listOf("call-1", "call-2"), toolCalls.requests.map { request -> request.id })
        assertEquals(
            listOf(MobileActionFunctions.WEB_SEARCH, MobileActionFunctions.OPEN_WIFI_SETTINGS),
            toolCalls.requests.map { request -> request.toolName },
        )
    }

    @Test
    fun remoteToolCallAccumulatorRejectsAmbiguousUnindexedMultiToolArgumentFragments() {
        val accumulator = RemoteToolCallAccumulator()

        accumulator.absorb(
            """
            {"choices":[{"delta":{"tool_calls":[
              {"id":"call-1","type":"function","function":{"name":"web_search","arguments":"{\"query\""}},
              {"id":"call-2","type":"function","function":{"name":"web_search","arguments":"{\"query\""}}
            ]}}]}
            """.trimIndent(),
        )
        accumulator.absorb("""{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":":\"北京天气\"}"}}]}}]}""")

        val error = accumulator.finish().single() as RemoteChatEvent.ParseError
        assertTrue(error.summary.contains("缺少稳定 index"))
    }

    @Test
    fun remoteToolCallAccumulatorRejectsStreamingMixedToolCallFormats() {
        val accumulator = RemoteToolCallAccumulator()

        assertTrue(
            accumulator.absorb(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"web_search","arguments":"{}"}}]}}]}""",
            ).isEmpty(),
        )
        val error = accumulator.absorb(
            """{"choices":[{"delta":{"function_call":{"name":"web_search","arguments":"{}"}}}]}""",
        ).single() as RemoteChatEvent.ParseError
        assertTrue(error.summary.contains("多种工具调用格式"))

        val finishError = accumulator.finish().single() as RemoteChatEvent.ParseError
        assertTrue(finishError.summary.contains("多种工具调用格式"))
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
    fun sendWithImageUsesOpenAiVisionContentPartInHttpFixture() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body(
                        """
                        data: {"choices":[{"delta":{"content":"完成"}}]}

                        data: [DONE]

                        """.trimIndent(),
                    )
                    .build(),
            )
            server.start()
            val runtime = OkHttpRemoteChatRuntime(OkHttpClient())
            val dataUrl = "data:image/png;base64,AA=="

            val chunks = runtime.send(
                prompt = "描述图片",
                history = emptyList(),
                parameters = GenerationParameters(),
                config = RemoteModelConfig(
                    baseUrl = server.url("/v1").toString().trimEnd('/'),
                    modelName = "vision-model",
                    supportsVisionInput = true,
                ),
                imageAttachments = listOf(
                    ChatImageAttachment(
                        mimeType = "image/png",
                        dataUrl = dataUrl,
                    ),
                ),
            ).toList()

            assertEquals("完成", chunks.joinToString(separator = ""))
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.target)
            val body = JSONObject(request.body!!.utf8())
            assertTrue(body.getBoolean("stream"))
            val messages = body.getJSONArray("messages")
            val content = messages.getJSONObject(messages.length() - 1).getJSONArray("content")
            assertEquals(2, content.length())
            assertEquals("text", content.getJSONObject(0).getString("type"))
            assertEquals("描述图片", content.getJSONObject(0).getString("text"))
            assertEquals("image_url", content.getJSONObject(1).getString("type"))
            assertEquals(dataUrl, content.getJSONObject(1).getJSONObject("image_url").getString("url"))
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
    fun sendWithToolsStreamsToolCallsFromSse() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body(
                        """
                        data: {"choices":[{"delta":{"content":"我会准备搜索。"}}]}

                        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"web_search","arguments":""}}]}}]}

                        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":\"Kotlin\"}"}}]}}]}

                        data: [DONE]

                        """.trimIndent(),
                    )
                    .build(),
            )
            server.start()
            val runtime = OkHttpRemoteChatRuntime(OkHttpClient())

            val events = runtime.sendWithTools(
                prompt = "搜索 Kotlin",
                history = emptyList(),
                parameters = GenerationParameters(),
                config = RemoteModelConfig(server.url("/v1").toString().trimEnd('/'), "model-a"),
                tools = ToolRegistry.fromSupportedActions(setOf(MobileActionFunctions.WEB_SEARCH)).specs(),
            ).toList()

            assertEquals("我会准备搜索。", (events[0] as RemoteChatEvent.TextDelta).text)
            val toolCall = events[1] as RemoteChatEvent.ToolCall
            assertEquals("call-1", toolCall.request.id)
            assertEquals(MobileActionFunctions.WEB_SEARCH, toolCall.request.toolName)
            assertEquals(mapOf("query" to "Kotlin"), toolCall.request.arguments)
            val requestBody = server.takeRequest().body!!.utf8()
            assertTrue(requestBody.contains(""""tools""""))
            assertTrue(requestBody.contains(MobileActionFunctions.WEB_SEARCH))
        }
    }

    @Test
    fun sendWithToolsStreamsMultipleToolCallsAsBatchFromSse() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body(
                        """
                        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-beijing","type":"function","function":{"name":"web_search","arguments":""}},{"index":1,"id":"call-shanghai","type":"function","function":{"name":"web_search","arguments":""}}]}}]}

                        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":\"北京天气\"}"}},{"index":1,"function":{"arguments":"{\"query\":\"上海天气\"}"}}]}}]}

                        data: [DONE]

                        """.trimIndent(),
                    )
                    .build(),
            )
            server.start()
            val runtime = OkHttpRemoteChatRuntime(OkHttpClient())

            val events = runtime.sendWithTools(
                prompt = "北京和上海今天温差多少？",
                history = emptyList(),
                parameters = GenerationParameters(),
                config = RemoteModelConfig(server.url("/v1").toString().trimEnd('/'), "model-a"),
                tools = ToolRegistry.fromSupportedActions(setOf(MobileActionFunctions.WEB_SEARCH)).specs(),
            ).toList()

            val toolCalls = events.single() as RemoteChatEvent.ToolCalls
            assertEquals(2, toolCalls.requests.size)
            assertEquals(
                listOf(mapOf("query" to "北京天气"), mapOf("query" to "上海天气")),
                toolCalls.requests.map { request -> request.arguments },
            )
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
    fun sendWithImageReportsUnsupportedWithoutLeakingResponseBody() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(400)
                    .body("""{"error":"secret-image-body should not appear"}""")
                    .build(),
            )
            server.start()
            val runtime = OkHttpRemoteChatRuntime(OkHttpClient())

            val failure = runCatching {
                runtime.send(
                    prompt = "描述图片",
                    history = emptyList(),
                    parameters = GenerationParameters(),
                    config = RemoteModelConfig(
                        server.url("/v1").toString().trimEnd('/'),
                        "model-a",
                        supportsVisionInput = true,
                    ),
                    imageAttachments = listOf(
                        ChatImageAttachment(
                            mimeType = "image/png",
                            dataUrl = "data:image/png;base64,AA==",
                        ),
                    ),
                ).toList()
            }.exceptionOrNull()

            val message = requireNotNull(failure).message.orEmpty()
            assertTrue(message.contains("不支持图片输入"))
            assertTrue(message.contains("400"))
            assertFalse(message.contains("secret-image-body"))
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
