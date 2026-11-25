package com.bytedance.zgx.solin.device

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.CurrentScreenOcrGroundingCache
import com.bytedance.zgx.solin.tool.DeviceControlToolExecutor
import com.bytedance.zgx.solin.tool.OcrGroundingCurrentScreenControlProvider
import com.bytedance.zgx.solin.tool.ToolExecutor
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.ValidatingToolExecutor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM coverage of the snapshot-scoped ref contract. Real AccessibilityNodeInfo is unavailable here, so the
 * live-walk wiring (bounds vs. live node, executeUiAction mapping) is pinned by source-contract checks,
 * not behaviour; device acceptance stays pending.
 */
class SnapshotScopedRefTest {
    private val node = ScreenNode(
        id = "n0_abcd1234_f00dbeef",
        text = "搜索",
        contentDescription = "",
        className = "android.widget.EditText",
        bounds = ScreenBounds(0, 0, 100, 48),
        clickable = true,
        editable = true,
        scrollable = false,
        enabled = true
    )
    private val snapshot = ScreenStateSnapshot(
        id = "screen_1700_f00dbeef",
        packageName = "com.example.app",
        capturedAtMillis = 1700L,
        nodes = listOf(node),
        textSummary = "搜索",
        truncated = false
    )

    @Test
    fun parserAcceptsOnlyScopedShape() {
        assertEquals(
            SnapshotScopedRef("screen_1700_f00dbeef", "n0_abcd1234_f00dbeef"),
            SnapshotRefs.parse(" screen_1700_f00dbeef/n0_abcd1234_f00dbeef ")
        )
        assertEquals("n3_ab::affordance", SnapshotRefs.parse("s/n3_ab::affordance")?.nodeId)
        listOf("n0_abcd1234", "n0_abcd1234_f00dbeef", "设置/通知", "a/b/n0_x", "/n0_x", "s/n0", "s/node", "a b/n0_x", "", null)
            .forEach { assertNull(it, SnapshotRefs.parse(it)) }
    }

    private fun failure(block: () -> Unit): UiActionFailureKind {
        try {
            block()
        } catch (error: SnapshotRefException) {
            return error.failureKind
        }
        fail("expected SnapshotRefException")
        error("unreachable")
    }

    private fun validate(ref: String, snap: ScreenStateSnapshot? = snapshot, pkg: String? = "com.example.app") = SnapshotRefs.validate(SnapshotRefs.parse(ref)!!, snap, pkg)

    @Test
    fun validationFailuresAreClosedAndOrdered() {
        val ok = "screen_1700_f00dbeef/n0_abcd1234_f00dbeef"
        assertEquals(node, validate(ok))
        assertEquals(UiActionFailureKind.SnapshotUnknown, failure { validate(ok, snap = null) })
        assertEquals(UiActionFailureKind.RefRetired, failure { validate("screen_1/n0_abcd1234_f00dbeef") })
        assertEquals(UiActionFailureKind.RefNotInSnapshot, failure { validate("screen_1700_f00dbeef/n9_abcd1234") })
        assertEquals(UiActionFailureKind.PackageChanged, failure { validate(ok, pkg = "com.other") })
        assertEquals(UiActionFailureKind.PackageChanged, failure { validate(ok, pkg = null) })
        assertEquals(
            UiActionFailureKind.PackageChanged,
            failure { validate(ok, snap = snapshot.copy(packageName = null), pkg = null) }
        )
        assertEquals(
            UiActionFailureKind.PackageChanged,
            failure { validate(ok, snap = snapshot.copy(packageName = "  "), pkg = "  ") }
        )
    }

    @Test
    fun boundsChangeIsElementMoved() {
        SnapshotRefs.requireSameBounds(node, ScreenBounds(0, 0, 100, 48))
        assertEquals(
            UiActionFailureKind.ElementMoved,
            failure { SnapshotRefs.requireSameBounds(node, ScreenBounds(0, 10, 100, 58)) }
        )
        assertEquals(UiActionFailureKind.ElementMoved, failure { SnapshotRefs.requireSameBounds(node, null) })
        assertEquals(
            UiActionFailureKind.ElementMoved,
            failure { SnapshotRefs.requireSameBounds(node.copy(bounds = null), ScreenBounds(0, 0, 100, 48)) }
        )
    }

    @Test
    fun runtimeQueryValidatesAndBareTargetsAreUnchanged() {
        val bounds = ScreenBounds(0, 0, 1080, 2400)
        assertNull(RuntimeTargetQuery("搜索", bounds).scopedNode)
        assertNull(RuntimeTargetQuery("n0_abcd1234", bounds).scopedNode)
        val ref = "screen_1700_f00dbeef/n0_abcd1234_f00dbeef"
        assertEquals(node, RuntimeTargetQuery(ref, bounds, snapshot, "com.example.app").scopedNode)
        assertEquals(UiActionFailureKind.SnapshotUnknown, failure { RuntimeTargetQuery(ref, bounds) })
        assertEquals(
            UiActionFailureKind.PackageChanged,
            failure { RuntimeTargetQuery(ref, bounds, snapshot, "com.other") }
        )
    }

    @Test
    fun resolverReturnsClosedFailureInsteadOfRanking() {
        val cases = mapOf(
            "screen_9/n0_abcd1234_f00dbeef" to UiActionFailureKind.RefRetired,
            "screen_1700_f00dbeef/n5_deadbeef" to UiActionFailureKind.RefNotInSnapshot
        )
        cases.forEach { (ref, kind) ->
            val evidence = UiTargetResolver.explain(snapshot, UiTargetKind.SearchEntry, ref)
            assertEquals(kind, evidence.failureKind)
            assertNull(evidence.selectedNodeId)
            assertTrue(evidence.rankedCandidates.isEmpty())
        }
        val changed = UiTargetResolver.explain(
            snapshot,
            UiTargetKind.SearchEntry,
            "screen_1700_f00dbeef/n0_abcd1234_f00dbeef",
            livePackage = "com.other"
        )
        assertEquals(UiActionFailureKind.PackageChanged, changed.failureKind)
        assertNull(changed.selectedNodeId)
        val valid = UiTargetResolver.explain(snapshot, UiTargetKind.SearchEntry, "screen_1700_f00dbeef/n0_abcd1234_f00dbeef")
        assertNull(valid.failureKind)
        assertEquals(node.id, valid.selectedNodeId)

        // Non-semantic kinds (e.g. ResultItem) must also resolve directly for valid scoped refs.
        val resultItem = UiTargetResolver.explain(snapshot, UiTargetKind.ResultItem, "screen_1700_f00dbeef/n0_abcd1234_f00dbeef")
        assertNull(resultItem.failureKind)
        assertEquals(node.id, resultItem.selectedNodeId)

        // ScreenObservation overload must also fail closed on stale/missing scoped refs instead of text ranking.
        val observation = snapshot.toScreenObservation()
        assertEquals(
            UiActionFailureKind.RefRetired,
            UiTargetResolver.explain(observation, UiTargetKind.SearchEntry, "screen_9/n0_abcd1234_f00dbeef").failureKind
        )
        assertEquals(
            UiActionFailureKind.RefNotInSnapshot,
            UiTargetResolver.explain(observation, UiTargetKind.SearchEntry, "${snapshot.id}/n9_deadbeef").failureKind
        )
        val obsValid = UiTargetResolver.explain(observation, UiTargetKind.ResultItem, "${snapshot.id}/${node.id}")
        assertNull(obsValid.failureKind)
        assertEquals(node.id, obsValid.selectedNodeId)
    }

    @Test
    fun serviceSourceContractMapsRefExceptionAndKeepsGuards() {
        val root = File("src/main/java/com/bytedance/zgx/solin/device")
        val service = File(root, "SolinAccessibilityService.kt").readText()
        assertTrue(service.contains("error is SnapshotRefException"))
        assertTrue(service.contains("failureKind = error.failureKind"))
        // The published observation, not the fresh evidence `before`, is what scoped refs validate against.
        assertTrue(service.contains("runCatching { operation(SnapshotRefRegistry.publishedSnapshot) }"))
        assertTrue(!service.contains("SnapshotRefRegistry.publish("))
        // Cleared in onServiceConnected, onUnbind, and onDestroy.
        assertEquals(3, Regex("SnapshotRefRegistry.clear\\(\\)").findAll(service).count())
        val ocrProvider = File("src/main/java/com/bytedance/zgx/solin/tool/OcrGroundingCurrentScreenControlProvider.kt").readText()
        assertEquals(0, Regex("SnapshotRefRegistry.publish\\(").findAll(ocrProvider).count())
        val deviceExecutor = File("src/main/java/com/bytedance/zgx/solin/tool/DeviceControlToolExecutor.kt").readText()
        assertTrue(deviceExecutor.contains("SnapshotRefRegistry.publish("))
        val screenshotOcrExecutor = File("src/main/java/com/bytedance/zgx/solin/tool/CurrentScreenshotOcrToolExecutor.kt").readText()
        assertTrue(screenshotOcrExecutor.contains("SnapshotRefRegistry.publish("))
        assertTrue(service.contains("findTargetCandidate(target, snapshot = published)"))
        assertEquals(2, Regex("if \\(scopedRef\\) null else tapOcrGroundingHint").findAll(service).count())
        assertTrue(service.contains("!scopedRef || candidate.node.isClickable"))
        assertTrue(service.contains("candidate.node.isScrollable\n            }?.node"))
        assertTrue(service.contains("failureKind = UiActionFailureKind.SnapshotUnknown"))
        assertTrue(service.contains("CriticalControlTapRefused"))
        val queries = File(root, "AccessibilityNodeQueries.kt").readText()
        assertTrue(queries.contains("SnapshotRefs.requireSameBounds(scoped, screenNode.bounds)"))
    }

    @Test
    fun toolBoundaryAllowsEveryUiActionFailureKind() {
        val registry = ToolRegistry()
        val request = ToolRequest(
            id = "tap-failure",
            toolName = MobileActionFunctions.UI_TAP,
            arguments = mapOf("target" to "搜索"),
            reason = "test"
        )
        UiActionFailureKind.entries.forEach { kind ->
            val result = ToolResult(
                requestId = request.id,
                status = ToolStatus.Failed,
                summary = "failed",
                data = mapOf(
                    "toolName" to request.toolName,
                    "privacy" to "LocalOnly",
                    "requiresLocalModel" to "true",
                    "actionType" to "tap",
                    "status" to "failed",
                    "retryable" to "false",
                    "summary" to "failed",
                    "failureKind" to kind.schemaValue
                )
            )
            val sanitized = ValidatingToolExecutor(
                delegate = object : ToolExecutor {
                    override fun execute(request: ToolRequest): ToolResult = result
                },
                registry = registry
            ).execute(request)
            assertNull("schema validation rejected ${kind.schemaValue}: ${sanitized.error}", sanitized.error)
            assertEquals(kind.schemaValue, sanitized.data["failureKind"])
        }
    }

    @Test
    fun publishedObservationIdIsAcceptedByTheNextAction() {
        // The id the model saw is the id validated: same snapshot object, so no ref_retired.
        val published = snapshot
        val ref = "${published.id}/${node.id}"
        assertEquals(node, SnapshotRefs.validate(SnapshotRefs.parse(ref)!!, published, "com.example.app"))
        // A fresh evidence snapshot has a new id; validating against it is what used to retire every ref.
        val fresh = published.copy(id = "screen_1701_deadbeef")
        assertEquals(UiActionFailureKind.RefRetired, failure { SnapshotRefs.validate(SnapshotRefs.parse(ref)!!, fresh, "com.example.app") })
    }

    private class FakeProvider(
        var next: ScreenStateSnapshot,
        var tapResult: UiActionReadResult = UiActionReadResult.Failed("fake")
    ) : CurrentScreenControlProvider {
        override fun observeCurrentScreen(maxTextChars: Int, maxNodes: Int) = ScreenStateReadResult.Available(next)
        override fun tap(target: String, timeoutMillis: Long): UiActionReadResult = tapResult
        override fun typeText(text: String, target: String?, timeoutMillis: Long, allowClipboardPasteFallback: Boolean): UiActionReadResult = UiActionReadResult.Failed("fake")
        override fun submitSearch(timeoutMillis: Long): UiActionReadResult = UiActionReadResult.Failed("fake")
        override fun scroll(direction: UiScrollDirection, target: String?, timeoutMillis: Long): UiActionReadResult = UiActionReadResult.Failed("fake")
        override fun pressBack(timeoutMillis: Long): UiActionReadResult = UiActionReadResult.Failed("fake")
        override fun waitForScreen(timeoutMillis: Long): UiActionReadResult = UiActionReadResult.Failed("fake")
        override fun tapByNormalizedCoords(normalizedX: Int, normalizedY: Int, timeoutMillis: Long): UiActionReadResult = UiActionReadResult.Failed("fake")
    }

    @Test
    fun wrappedTapValidationReadDoesNotReplacePublishedObservation() {
        SnapshotRefRegistry.clear()
        try {
            val delegate = FakeProvider(snapshot)
            val provider = OcrGroundingCurrentScreenControlProvider(delegate, CurrentScreenOcrGroundingCache { 0L })
            val executor = DeviceControlToolExecutor(provider)
            val observe = ToolRequest(
                id = "obs-1",
                toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                reason = "test"
            )
            assertEquals(ToolStatus.Succeeded, executor.execute(observe).status)
            assertEquals(snapshot.id, SnapshotRefRegistry.publishedSnapshot?.id)
            // The tap path's internal validation read sees a fresh, differently-salted snapshot.
            delegate.next = snapshot.copy(id = "screen_1701_deadbeef")
            provider.tap("搜索")
            assertEquals(snapshot.id, SnapshotRefRegistry.publishedSnapshot?.id)
            // Failed observe with wrong expectedPackageName must NOT retire the published observation.
            val wrongPackageObserve = executor.execute(
                ToolRequest(
                    id = "obs-wrong-pkg",
                    toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                    arguments = mapOf("expectedPackageName" to "com.example.other"),
                    reason = "test"
                )
            )
            assertEquals(ToolStatus.Failed, wrongPackageObserve.status)
            assertEquals(snapshot.id, SnapshotRefRegistry.publishedSnapshot?.id)
            // A later succeeding model observation does publish the new one.
            assertEquals(ToolStatus.Succeeded, executor.execute(observe.copy(id = "obs-2")).status)
            assertEquals("screen_1701_deadbeef", SnapshotRefRegistry.publishedSnapshot?.id)
        } finally {
            SnapshotRefRegistry.clear()
        }
        assertNull(SnapshotRefRegistry.publishedSnapshot)
    }

    @Test
    fun dangerousPreflightBlocksScopedRefAgainstPublishedSnapshot() {
        SnapshotRefRegistry.clear()
        try {
            val payNode = node.copy(id = "n2_deadbeef_f00dbeef", text = "立即支付", editable = false)
            val snap = snapshot.copy(nodes = listOf(node, payNode))
            SnapshotRefRegistry.publish(snap)
            var tapped = false
            val provider = object : CurrentScreenControlProvider by FakeProvider(snap) {
                override fun tap(target: String, timeoutMillis: Long): UiActionReadResult {
                    tapped = true
                    return UiActionReadResult.Failed("should not reach tap")
                }
            }
            val result = DeviceControlToolExecutor(provider).execute(
                ToolRequest(
                    id = "tap-pay",
                    toolName = MobileActionFunctions.UI_TAP,
                    arguments = mapOf("target" to "${snap.id}/${payNode.id}"),
                    reason = "test"
                )
            )
            assertEquals(ToolStatus.Failed, result.status)
            assertEquals(UiActionFailureKind.DangerousAction.schemaValue, result.data["failureKind"])
            assertFalse("dangerous scoped tap must be refused before provider.tap", tapped)
        } finally {
            SnapshotRefRegistry.clear()
        }
    }

    @Test
    fun scopedTypeTextFailsClosedBeforePreflightSideEffects() {
        var observeCalls = 0
        var typeCalls = 0
        val provider = object : CurrentScreenControlProvider by FakeProvider(snapshot) {
            override fun observeCurrentScreen(maxTextChars: Int, maxNodes: Int): ScreenStateReadResult {
                observeCalls++
                return ScreenStateReadResult.Available(snapshot)
            }

            override fun typeText(
                text: String,
                target: String?,
                timeoutMillis: Long,
                allowClipboardPasteFallback: Boolean
            ): UiActionReadResult {
                typeCalls++
                return UiActionReadResult.Failed("should not reach typeText")
            }
        }
        val result = DeviceControlToolExecutor(provider).execute(
            ToolRequest(
                id = "type-scoped",
                toolName = MobileActionFunctions.UI_TYPE_TEXT,
                arguments = mapOf("target" to "${snapshot.id}/${node.id}", "text" to "耳机"),
                reason = "test"
            )
        )
        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(UiActionFailureKind.SnapshotUnknown.schemaValue, result.data["failureKind"])
        assertEquals(0, observeCalls)
        assertEquals(0, typeCalls)
    }

    @Test
    fun tapResultPublishesAfterSnapshotSoNextScopedRefIsNotRetired() {
        SnapshotRefRegistry.clear()
        try {
            val afterNode = node.copy(id = "n1_cafe5678_beef0001", text = "结果")
            val after = snapshot.copy(id = "screen_1800_beef0001", nodes = listOf(afterNode))
            SnapshotRefRegistry.publish(snapshot)
            val provider = FakeProvider(
                next = snapshot,
                tapResult = UiActionReadResult.Available(
                    UiActionExecutionResult(
                        status = UiActionStatus.Succeeded,
                        before = snapshot,
                        after = after,
                        summary = "ok",
                        retryable = false
                    )
                )
            )
            val result = DeviceControlToolExecutor(provider).execute(
                ToolRequest(
                    id = "tap-1",
                    toolName = MobileActionFunctions.UI_TAP,
                    arguments = mapOf("target" to "搜索"),
                    reason = "test"
                )
            )
            assertEquals(result.summary, ToolStatus.Succeeded, result.status)
            assertEquals(after.id, result.data["afterObservationId"])
            assertEquals(after, SnapshotRefRegistry.publishedSnapshot)
            val ref = SnapshotRefs.parse("${after.id}/${afterNode.id}")!!
            assertEquals(afterNode, SnapshotRefs.validate(ref, SnapshotRefRegistry.publishedSnapshot, "com.example.app"))
        } finally {
            SnapshotRefRegistry.clear()
        }
    }
}
