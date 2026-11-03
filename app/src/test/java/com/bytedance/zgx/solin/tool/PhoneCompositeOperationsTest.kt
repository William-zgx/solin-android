package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.action.MobileActionFunctions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneCompositeOperationsTest {
    private val registry get() = defaultBuiltInToolRegistry

    @Test fun overlappingPagesKeepSeparateEqualMessages() {
        assertEquals(listOf("same", "middle", "same", "end"),
            PhoneCompositeOperations.appendPage(listOf("same", "middle"), listOf("middle", "same", "end")))
        assertEquals(listOf("a", "b", "c"), PhoneCompositeOperations.appendPage(listOf("a", "b"), listOf("b", "c")))
    }

    @Test fun collectionDeliversBoundedLocalEvidenceWithoutClaimingCompletion() = runBlocking {
        val fake = FakeExecutor(listOf(page("a", "b"), page("b", "a", "c")))
        val parent = request(MobileActionFunctions.UI_COLLECT, "pages" to "2")
        val result = boundary(fake).execute(parent, userConfirmed = true)
        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("a\nb\na\nc", result.data["text"])
        assertEquals("false", result.data["completed"])
        assertEquals("true", result.data["partial"])
        assertEquals("true", result.data["truncated"])
        assertEquals("LocalOnly", result.data["privacy"])
        assertEquals("true", result.data["requiresLocalModel"])
        assertEquals("page_limit", result.data["stopReason"])
        assertNull(registry.validateResult(parent, result))
        assertEquals(1, fake.actions.size)
        assertTrue(fake.requests.all { it.id.startsWith(parent.id + ":child:") })
    }

    @Test fun parentApprovalDoesNotAuthorizeChildAndNoScreenIsRead() = runBlocking {
        val fake = FakeExecutor(listOf(page("private")))
        val result = boundary(fake, permitChildren = false).execute(request(MobileActionFunctions.UI_COLLECT), true)
        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("authorization_required", result.data["stopReason"])
        assertEquals("false", result.data["completed"])
        assertEquals("observe_before_continuing", result.data["remaining"])
        assertTrue(fake.requests.isEmpty())
        assertFalse(result.retryable)
        assertFalse(result.data.containsKey("text"))
    }

    @Test fun observerUpdatesPolicyBeforeNextSubrequest() = runBlocking {
        val fake = FakeExecutor(listOf(page("private")))
        var privateRead = false
        val boundary = TimeoutToolExecutionBoundary(fake, dispatcher = Dispatchers.Unconfined, toolRegistry = registry,
            executionAuthorizer = authorizer(),
            compositeChildAuthorizer = { _, child -> !privateRead || child.toolName == MobileActionFunctions.OBSERVE_CURRENT_SCREEN },
            compositeChildObserver = { _, child, result ->
                if (child.toolName == MobileActionFunctions.OBSERVE_CURRENT_SCREEN && result.status == ToolStatus.Succeeded) privateRead = true
            })
        val result = boundary.execute(request(MobileActionFunctions.UI_FIND, "text" to "target"), true)
        assertTrue(privateRead)
        assertTrue(fake.actions.isEmpty())
        assertEquals("authorization_required", result.data["stopReason"])
        assertEquals("true", result.data["partial"])
    }

    @Test fun changedPackageStopsBeforeAnotherWrite() = runBlocking {
        val fake = FakeExecutor(listOf(page("a"), page("b")), packages = listOf("test.app", "other.app"))
        val result = boundary(fake).execute(request(MobileActionFunctions.UI_FIND, "text" to "missing"), true)
        assertEquals("app_changed", result.data["stopReason"])
        assertEquals(1, fake.actions.size)
        assertEquals("test.app", fake.actions.single().arguments["expectedPackageName"])
        val observations = fake.requests.filter { it.toolName == MobileActionFunctions.OBSERVE_CURRENT_SCREEN }
        assertEquals("test.app", observations.last().arguments["expectedPackageName"])
        assertFalse(result.retryable)
    }

    @Test fun findReturnsUniqueCandidateWithoutTapping() = runBlocking {
        val fake = FakeExecutor(listOf(page("a"), page("wanted")))
        val result = boundary(fake).execute(request(MobileActionFunctions.UI_FIND, "text" to "wanted"), true)
        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("true", result.data["completed"])
        assertEquals("[\"node0\"]", result.data["candidateIds"])
        assertEquals(listOf(MobileActionFunctions.UI_SCROLL), fake.actions.map { it.toolName })
    }

    @Test fun duplicateCandidatesAreNotClicked() = runBlocking {
        val fake = FakeExecutor(listOf(page("wanted", "wanted")))
        val result = boundary(fake).execute(request(MobileActionFunctions.UI_FIND, "text" to "wanted"), true)
        assertEquals("ambiguous_target", result.data["stopReason"])
        assertTrue(fake.actions.isEmpty())
    }

    @Test fun fivePageLimitStopsChangingContent() = runBlocking {
        val fake = FakeExecutor((1..6).map { page("item$it") })
        val result = boundary(fake).execute(request(MobileActionFunctions.UI_FIND, "text" to "absent"), true)
        assertEquals("page_limit", result.data["stopReason"])
        assertEquals(4, fake.actions.size)
        assertEquals("5", result.data["pagesInspected"])
    }

    @Test fun compositeSchemasActuallyRejectUndeclaredArguments() {
        for (tool in listOf(MobileActionFunctions.UI_COLLECT, MobileActionFunctions.UI_FIND, MobileActionFunctions.UI_SET_WHEEL)) {
            val required = when (tool) {
                MobileActionFunctions.UI_FIND -> mapOf("text" to "target")
                MobileActionFunctions.UI_SET_WHEEL -> mapOf("target" to "wheel", "value" to "9")
                else -> emptyMap()
            }
            val result = registry.validate(ToolRequest(toolName = tool, arguments = required + ("undeclared" to "value")))
            assertEquals(ToolStatus.Rejected, result?.status)
            assertTrue(result!!.summary.contains("does not accept argument"))
        }
    }

    @Test fun malformedArgumentsNeverExecute() = runBlocking {
        val fake = FakeExecutor(listOf(page("a")))
        val result = boundary(fake).execute(request(MobileActionFunctions.UI_COLLECT, "pages" to "200"), true)
        assertEquals(ToolStatus.Rejected, result.status)
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun cancellationPropagatesWithoutReplayingAction() = runBlocking {
        val fake = FakeExecutor(listOf(page("a")), cancelAction = true)
        try {
            boundary(fake).execute(request(MobileActionFunctions.UI_FIND, "text" to "absent"), true)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(1, fake.actions.size)
        }
    }

    @Test fun wheelChecksActualReadbackAndNeverInventsMiddleValue() = runBlocking {
        val fake = FakeExecutor(listOf(wheelPage(9), wheelPage(10)))
        val result = boundary(fake).execute(request(MobileActionFunctions.UI_SET_WHEEL, "target" to "wheel", "value" to "10"), true)
        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("10", result.data["actualValue"])
        assertEquals("verified", result.data["stopReason"])
        assertEquals(listOf(MobileActionFunctions.UI_TYPE_TEXT), fake.actions.map { it.toolName })
        val noValue = FakeExecutor(listOf(JSONArray().put(wheelNode()).toString()))
        val failed = boundary(noValue).execute(request(MobileActionFunctions.UI_SET_WHEEL, "target" to "wheel", "value" to "10"), true)
        assertEquals("wheel_unreadable", failed.data["stopReason"])
        assertTrue(noValue.actions.isEmpty())
    }

    @Test fun wheelUnchangedAfterWriteStopsWithoutRepeating() = runBlocking {
        val fake = FakeExecutor(listOf(wheelPage(9), wheelPage(9)))
        val result = boundary(fake).execute(request(MobileActionFunctions.UI_SET_WHEEL, "target" to "wheel", "value" to "10"), true)
        assertEquals("no_change", result.data["stopReason"])
        assertEquals(1, fake.actions.size)
        assertFalse(result.retryable)
    }

    @Test fun circularWheelDistanceUsesShortestExplicitRange() {
        assertEquals(1, PhoneCompositeOperations.wheelDistance(59, 0, 60))
        assertEquals(-1, PhoneCompositeOperations.wheelDistance(0, 59, 60))
        assertEquals(-59, PhoneCompositeOperations.wheelDistance(59, 0, null))
    }

    @Test fun failedInputKeepsOnlyValidVerificationEnums() {
        val request = request(MobileActionFunctions.UI_TYPE_TEXT, "text" to "test")
        val raw = request.failed(ToolErrorCode.ExecutionFailed, "Private failure", false,
            mapOf("inputVerification" to "mismatch", "screenStability" to "timed_out", "text" to "private"))
        val sanitized = registry.validateResult(request, raw)!!
        assertEquals("mismatch", sanitized.data["inputVerification"])
        assertEquals("timed_out", sanitized.data["screenStability"])
        assertFalse(sanitized.data.containsKey("text"))
        val injected = raw.copy(data = mapOf("inputVerification" to "private", "screenStability" to "private"))
        val rejected = registry.validateResult(request, injected)!!
        assertFalse(rejected.data.containsKey("inputVerification"))
        assertFalse(rejected.data.containsKey("screenStability"))
    }

    @Test fun totalTimeoutNeverRetriesOrAuthorizesALateChild() = runBlocking {
        val fake = FakeExecutor(listOf(page("a")))
        val boundary = TimeoutToolExecutionBoundary(fake, dispatcher = Dispatchers.Unconfined,
            deviceControlTimeoutMillis = 20, toolRegistry = registry, executionAuthorizer = authorizer(),
            compositeChildAuthorizer = { _, _ -> delay(200); true })
        val result = boundary.execute(request(MobileActionFunctions.UI_COLLECT), true)
        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("timeout", result.data["stopReason"])
        assertFalse(result.retryable)
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun compositesCannotEnterRetryableBatchExecution() = runBlocking {
        val fake = FakeExecutor(listOf(page("a")))
        val results = boundary(fake).executeBatch(listOf(request(MobileActionFunctions.UI_COLLECT)), true)
        assertEquals(ToolStatus.Rejected, results.single().status)
        assertTrue(fake.requests.isEmpty())
    }

    private fun boundary(fake: FakeExecutor, permitChildren: Boolean = true) = TimeoutToolExecutionBoundary(
        fake, dispatcher = Dispatchers.Unconfined, toolRegistry = registry,
        executionAuthorizer = authorizer(), compositeChildAuthorizer = { _, _ -> permitChildren },
    )

    private fun authorizer() = ToolExecutionAuthorizer { request, approved -> if (approved) null else request.rejected("Confirmation required") }
    private fun request(name: String, vararg args: Pair<String, String>) = ToolRequest(toolName = name, arguments = args.toMap())

    private class FakeExecutor(
        private val screens: List<String>, private val packages: List<String> = listOf("test.app"),
        private val cancelAction: Boolean = false,
    ) : ToolExecutor {
        val requests = mutableListOf<ToolRequest>()
        val actions = mutableListOf<ToolRequest>()
        private var reads = 0
        override fun execute(request: ToolRequest): ToolResult {
            requests += request
            if (request.toolName == MobileActionFunctions.OBSERVE_CURRENT_SCREEN) {
                val index = reads++
                val nodesJson = screens[index.coerceAtMost(screens.lastIndex)]
                val nodes = JSONArray(nodesJson)
                val summary = (0 until nodes.length()).map { nodes.getJSONObject(it).optString("text") }
                    .filter(String::isNotBlank).joinToString("\n").ifBlank { "当前屏幕无可见文字" }
                val result = request.succeeded("Observed", request.localOnlyData() + mapOf(
                    "source" to DEVICE_CONTROL_SOURCE, "metadataPolicy" to DEVICE_CONTROL_METADATA_POLICY_SCHEMA_VALUE,
                    "observationId" to "screen$index", "capturedAtMillis" to "1", "nodeCount" to nodes.length().toString(),
                    "actionableNodeCount" to "1", "textSummary" to summary, "truncated" to "false",
                    "nodesJson" to nodesJson, "screenObservationJson" to "{}",
                    "maxNodes" to "80", "maxTextChars" to "4000", "packageName" to packages[index.coerceAtMost(packages.lastIndex)],
                ))
                assertNull("Observation fixture must satisfy production output schema", defaultBuiltInToolRegistry.validateResult(request, result))
                return result
            }
            actions += request
            if (cancelAction) throw CancellationException("cancelled during write")
            return request.succeeded("Action executed", request.localOnlyData() + mapOf(
                "source" to DEVICE_CONTROL_SOURCE, "metadataPolicy" to DEVICE_CONTROL_METADATA_POLICY_SCHEMA_VALUE,
                "actionType" to if (request.toolName == MobileActionFunctions.UI_SCROLL) "scroll" else "type_text",
                "status" to "succeeded", "retryable" to "false", "summary" to "Action executed",
                "beforeObservationId" to "before", "afterObservationId" to "after", "verificationSummary" to "Observed",
            ))
        }
    }

    private fun page(vararg lines: String) = JSONArray().apply {
        lines.forEachIndexed { index, text -> put(node("node$index", text, "android.widget.TextView", index * 30, index * 30 + 20)) }
    }.toString()

    private fun node(id: String, text: String, type: String, top: Int, bottom: Int, editable: Boolean = false) = JSONObject()
        .put("id", id).put("text", text).put("className", type).put("enabled", true).put("editable", editable)
        .put("bounds", JSONObject().put("left", 0).put("right", 100).put("top", top).put("bottom", bottom))

    private fun wheelNode() = node("wheel", "", "android.widget.NumberPicker", 0, 100)
    private fun wheelPage(value: Int) = JSONArray().put(wheelNode())
        .put(node("value", value.toString(), "android.widget.EditText", 40, 60, editable = true)).toString()
}
