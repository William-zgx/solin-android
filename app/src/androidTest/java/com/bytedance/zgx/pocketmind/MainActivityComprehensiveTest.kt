package com.bytedance.zgx.pocketmind

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class MainActivityComprehensiveTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun emulatorFeatureWalkthroughCoversRemoteSessionsMemoryActionsAndCustomDownload() {
        LocalOpenAiServer().use { server ->
            dismissFirstRunSetupIfPresent()
            configureRemoteModel(server.baseUrl)

            assertPlaintextRemoteApiKeyIsNotInLegacyPrefs()

            sendPrompt("请记住：蓝色机器人喜欢端侧 AI")
            composeRule.waitForText("已记住这条本地偏好", substring = true)
            server.assertNoPost()

            sendPrompt("用一句话介绍端侧 AI")
            composeRule.waitForText("模拟器回答")
            val firstRequest = server.awaitPost()
            assertTrue(firstRequest.path.endsWith("/v1/chat/completions"))
            assertTrue(firstRequest.body.contains("用一句话介绍端侧 AI"))
            assertFalse(firstRequest.body.contains("请记住"))
            assertFalse(firstRequest.body.contains("已记住这条本地偏好"))

            sendPrompt("蓝色机器人偏好是什么")
            val memoryRequest = server.awaitPost()
            assertFalse(memoryRequest.body.contains("本地记忆："))
            assertFalse(memoryRequest.body.contains("设备上下文："))
            assertFalse(memoryRequest.body.contains("请记住"))
            assertFalse(memoryRequest.body.contains("蓝色机器人喜欢端侧 AI"))
            assertFalse(memoryRequest.body.contains("已记住这条本地偏好"))
            composeRule.waitForText("记忆回答")

            sendPrompt("打开 Wi-Fi 设置")
            composeRule.waitForText("规则回退", substring = true)
            composeRule.waitForText("打开 Wi-Fi 设置")
            composeRule.onNodeWithTag("action_dismiss_button").performClick()

            sendPrompt("请慢慢回答")
            composeRule.waitForText("慢")
            val streamingRequest = server.awaitPost()
            assertFalse(streamingRequest.body.contains("请记住"))
            assertFalse(streamingRequest.body.contains("已记住这条本地偏好"))
            assertFalse(streamingRequest.body.contains("打开 Wi-Fi 设置"))
            assertFalse(streamingRequest.body.contains("动作草稿"))
            composeRule.onNodeWithTag("composer_send_button").performClick()
            composeRule.waitForText("远程可用")

            createAndSwitchSessions()
            exerciseModelManagerControlsAndCustomDownload(server)
        }
    }

    @Test
    fun remoteToolCallShowsConfirmationBeforeAndroidToolExecution() {
        LocalOpenAiServer().use { server ->
            dismissFirstRunSetupIfPresent()
            configureRemoteModel(server.baseUrl)

            sendPrompt(REMOTE_TOOL_CALL_PROMPT)
            val request = server.awaitPost()
            assertTrue(request.path.endsWith("/v1/chat/completions"))
            assertRemoteToolRequestBody(request)

            composeRule.waitForText("已准备远程动作草稿：Web 搜索", timeoutMillis = 15_000)
            composeRule.waitForText("Web 搜索 · 远程模型请求")
            composeRule.waitForText("query: $REMOTE_TOOL_CALL_QUERY")
            composeRule.onNodeWithTag("action_confirm_button").assertIsDisplayed()
            composeRule.onNodeWithTag("action_dismiss_button").performClick()
            server.assertNoPost(timeoutMillis = 500)
        }
    }

    private fun assertRemoteToolRequestBody(request: RequestRecord) {
        val body = JSONObject(request.body)
        assertTrue(body.getBoolean("stream"))
        assertEquals("mock-model", body.getString("model"))
        assertTrue(request.body.contains(REMOTE_TOOL_CALL_PROMPT))
        assertEquals("auto", body.getString("tool_choice"))
        val webSearchTool = body.getJSONArray("tools").functionTool("web_search")
        assertEquals("function", webSearchTool.getString("type"))
        val webSearchFunction = webSearchTool.getJSONObject("function")
        assertTrue(webSearchFunction.getString("description").isNotBlank())
        val parameters = webSearchFunction.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        assertTrue(parameters.getJSONObject("properties").has("query"))
    }

    private fun dismissFirstRunSetupIfPresent() {
        val setupVisible = composeRule.waitForOptionalText("准备基础能力包", timeoutMillis = 3_000)
        if (!setupVisible) return
        composeRule.onNodeWithTag("first_run_model_chat-e2b").assertIsOn()
        composeRule.onNodeWithTag("first_run_model_memory-embedding-300m").assertIsOff()
        composeRule.onNodeWithTag("first_run_model_mobile-action-270m").assertIsOff()
        composeRule.onNodeWithText("先跳过").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("准备基础能力包").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun configureRemoteModel(baseUrl: String) {
        composeRule.onNodeWithTag("top_model_button").performClick()
        composeRule.waitForTag("model_manager_sheet")
        composeRule.onNodeWithTag("model_tab_remote").performClick()
        composeRule.replaceTaggedText("remote_base_url_input", baseUrl)
        composeRule.replaceTaggedText("remote_model_name_input", "mock-model")
        composeRule.onNodeWithTag("model_tab_current").performClick()
        composeRule.onNodeWithTag("inference_remote_chip").performClick()
        closeSheet("model_manager_sheet")
    }

    private fun sendPrompt(prompt: String) {
        composeRule.onNodeWithTag("composer_input")
            .performTextClearance()
        composeRule.onNodeWithTag("composer_input").performTextInput(prompt)
        composeRule.onNodeWithTag("composer_send_button").performClick()
    }

    private fun createAndSwitchSessions() {
        composeRule.onNodeWithTag("top_session_button").performClick()
        composeRule.waitForTag("session_manager_title")
        composeRule.onNodeWithTag("session_create_button").performClick()
        composeRule.waitForTextGone("记忆回答")

        composeRule.onNodeWithTag("top_session_button").performClick()
        composeRule.waitForText("请记住：蓝色机器人喜欢端侧 AI", substring = true)
        composeRule.onAllNodesWithText("请记住：蓝色机器人喜欢端侧 AI", substring = true)
            .onFirst()
            .performClick()
        composeRule.waitForText("记忆回答")

        composeRule.onNodeWithTag("top_session_button").performClick()
        composeRule.waitForTag("session_manager_title")
        composeRule.onNodeWithTag("session_create_button").performClick()
        composeRule.waitForTextGone("记忆回答")
        composeRule.onNodeWithTag("top_session_button").performClick()
        composeRule.waitForTag("session_delete_button")
        composeRule.onNodeWithTag("session_delete_button").performClick()
        closeSheet("session_manager_title")
    }

    private fun exerciseModelManagerControlsAndCustomDownload(server: LocalOpenAiServer) {
        composeRule.onNodeWithTag("top_model_button").performClick()
        composeRule.waitForTag("model_manager_sheet")

        composeRule.onNodeWithTag("inference_local_chip").performScrollTo().performClick()
        composeRule.onNodeWithTag("backend_cpu_chip").performScrollTo().performClick()
        composeRule.onNodeWithTag("inference_remote_chip").performScrollTo().performClick()
        composeRule.onNodeWithTag("model_tab_advanced").performClick()
        composeRule.waitForText("Temperature · 创造性")
        composeRule.waitForText("当前使用本地轻量索引；可补装记忆模型资产。")

        composeRule.onNodeWithTag("model_tab_models").performClick()
        composeRule.replaceTaggedText("custom_model_url_input", "ftp://bad-model.litertlm")
        composeRule.onNodeWithTag("custom_model_download_button").performScrollTo().performClick()

        val customDownloadUrl = "${server.baseOrigin}/gemma-4-E2B-it.litertlm"
        composeRule.replaceTaggedText("custom_model_url_input", customDownloadUrl)
        composeRule.onNodeWithTag("custom_model_download_button").performScrollTo().performClick()
        assertTrue(server.awaitDownloadGet().path.endsWith("gemma-4-E2B-it.litertlm"))
        waitForSystemDownload(customDownloadUrl)
        composeRule.onNodeWithText("本地模型").performScrollTo()
        composeRule.waitForText("自定义未校验", timeoutMillis = 20_000, substring = true)
        composeRule.waitForText("gemma-4-E2B-it.litertlm", timeoutMillis = 20_000, substring = true)
    }

    private fun assertPlaintextRemoteApiKeyIsNotInLegacyPrefs() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = targetContext.getSharedPreferences("pocketmind", Context.MODE_PRIVATE)
        assertFalse(prefs.contains("remote_model_api_key"))
    }

    private fun waitForSystemDownload(downloadUrl: String) {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val downloadManager = targetContext.getSystemService(DownloadManager::class.java)
        var lastStatus = "not found"
        val completed = runCatching {
            composeRule.waitUntil(timeoutMillis = 20_000) {
                downloadManager.query(DownloadManager.Query())?.use { cursor ->
                    cursor.findDownload(downloadUrl)?.let { status ->
                        lastStatus = status
                        status == "successful"
                    } ?: false
                } ?: false
            }
            true
        }.getOrDefault(false)
        if (!completed) {
            fail("Timed out waiting for DownloadManager success for $downloadUrl; last status: $lastStatus")
        }
    }

    private fun closeSheet(tag: String) {
        if (composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) return
        if (tag == "model_manager_sheet") {
            composeRule.onNodeWithTag("model_manager_close_button").performClick()
        } else if (tag == "session_manager_title") {
            composeRule.onNodeWithTag("session_manager_close_button").performClick()
        } else {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun ComposeTestRule.waitForTag(tag: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeTestRule.waitForText(
        text: String,
        timeoutMillis: Long = 10_000,
        substring: Boolean = false,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeTestRule.waitForTextGone(
        text: String,
        timeoutMillis: Long = 10_000,
        substring: Boolean = false,
    ) {
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun ComposeTestRule.waitForOptionalText(text: String, timeoutMillis: Long): Boolean =
        runCatching {
            waitForText(text, timeoutMillis = timeoutMillis)
            true
        }.getOrDefault(false)

    private fun ComposeTestRule.replaceTaggedText(tag: String, text: String) {
        onNodeWithTag(tag).performScrollTo()
        onNodeWithTag(tag).performTextReplacement(text)
        waitForIdle()
    }
}

private class LocalOpenAiServer : Closeable {
    private val serverSocket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
    private val requests = LinkedBlockingQueue<RequestRecord>()
    private val downloadRequests = LinkedBlockingQueue<RequestRecord>()
    private val worker = thread(start = true, isDaemon = true, name = "pocketmind-test-server") {
        while (!serverSocket.isClosed) {
            val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
            thread(start = true, isDaemon = true, name = "pocketmind-test-connection") {
                handle(socket)
            }
        }
    }

    val baseOrigin: String = "http://127.0.0.1:${serverSocket.localPort}"
    val baseUrl: String = "$baseOrigin/v1"

    fun awaitPost(): RequestRecord =
        requests.poll(10, TimeUnit.SECONDS) ?: error("Timed out waiting for remote request")

    fun assertNoPost(timeoutMillis: Long = 1_000) {
        val request = requests.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: return
        fail("Expected no remote request, but received ${request.method} ${request.path}: ${request.body}")
    }

    fun awaitDownloadGet(): RequestRecord {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val request = downloadRequests.poll(250, TimeUnit.MILLISECONDS) ?: continue
            if (request.method == "GET") return request
        }
        error("Timed out waiting for model download GET request")
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty()
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                CharArray(contentLength).also { reader.read(it) }.concatToString()
            } else {
                ""
            }
            if ((method == "GET" || method == "HEAD") && path.endsWith(".litertlm")) {
                val bytes = "not-a-real-litertlm-model".toByteArray(StandardCharsets.UTF_8)
                downloadRequests.offer(RequestRecord(method, path, headers, body))
                if (method == "HEAD") {
                    client.getOutputStream().writeFixedHeaders(
                        contentType = "application/octet-stream",
                        contentLength = bytes.size,
                    )
                } else {
                    client.getOutputStream().writeFixedResponse(
                        contentType = "application/octet-stream",
                        body = bytes,
                    )
                }
                return
            }
            requests.offer(RequestRecord(method, path, headers, body))
            if (body.contains(REMOTE_TOOL_CALL_PROMPT)) {
                client.getOutputStream().writeSseToolCall(
                    toolName = "web_search",
                    argumentsJson = JSONObject().put("query", REMOTE_TOOL_CALL_QUERY).toString(),
                )
                return
            }
            if (body.contains("慢慢")) {
                client.getOutputStream().writeSsePrefix()
                client.getOutputStream().writeSseChunk("慢")
                Thread.sleep(30_000)
                return
            }
            val text = if (body.contains("偏好")) "记忆回答" else "模拟器回答"
            client.getOutputStream().writeSseResponse(text)
        }
    }

    override fun close() {
        serverSocket.close()
        worker.interrupt()
    }
}

private data class RequestRecord(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

private const val REMOTE_TOOL_CALL_PROMPT = "远程模型工具调用夹具 7c9e"
private const val REMOTE_TOOL_CALL_QUERY = "Kotlin coroutine guide"

private fun Cursor.findDownload(downloadUrl: String): String? {
    val uriIndex = getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
    val statusIndex = getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
    val reasonIndex = getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
    while (moveToNext()) {
        if (getString(uriIndex) != downloadUrl) continue
        return when (val status = getInt(statusIndex)) {
            DownloadManager.STATUS_SUCCESSFUL -> "successful"
            DownloadManager.STATUS_FAILED -> "failed:${getInt(reasonIndex)}"
            DownloadManager.STATUS_PAUSED -> "paused:${getInt(reasonIndex)}"
            DownloadManager.STATUS_PENDING -> "pending"
            DownloadManager.STATUS_RUNNING -> "running"
            else -> "status:$status reason:${getInt(reasonIndex)}"
        }
    }
    return null
}

private fun OutputStream.writeFixedHeaders(contentType: String, contentLength: Int) {
    write(
        (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: $contentLength\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        ).toByteArray(StandardCharsets.UTF_8),
    )
    flush()
}

private fun OutputStream.writeFixedResponse(contentType: String, body: ByteArray) {
    writeFixedHeaders(
        contentType = contentType,
        contentLength = body.size,
    )
    write(body)
    flush()
}

private fun OutputStream.writeSseResponse(text: String) {
    writeSsePrefix()
    writeSseChunk(text)
    write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
    flush()
}

private fun OutputStream.writeSsePrefix() {
    write(
        (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        ).toByteArray(StandardCharsets.UTF_8),
    )
    flush()
}

private fun OutputStream.writeSseChunk(text: String) {
    write("""data: {"choices":[{"delta":{"content":"$text"}}]}""".toByteArray(StandardCharsets.UTF_8))
    write("\n\n".toByteArray(StandardCharsets.UTF_8))
    flush()
}

private fun OutputStream.writeSseToolCall(toolName: String, argumentsJson: String) {
    writeSsePrefix()
    val chunk = JSONObject()
        .put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "delta",
                    JSONObject().put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put("id", "call-remote-web-search")
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", toolName)
                                        .put("arguments", argumentsJson),
                                ),
                        ),
                    ),
                ),
            ),
        )
    write("data: $chunk\n\n".toByteArray(StandardCharsets.UTF_8))
    write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
    flush()
}

private fun JSONArray.functionTool(name: String): JSONObject {
    for (index in 0 until length()) {
        val tool = optJSONObject(index) ?: continue
        val function = tool.optJSONObject("function") ?: continue
        if (function.optString("name") == name) return tool
    }
    throw AssertionError("Expected tool function $name in $this")
}
