package com.bytedance.zgx.solin.device

import java.io.File
import org.junit.Assert.assertEquals
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
        enabled = true,
    )
    private val snapshot = ScreenStateSnapshot(
        id = "screen_1700_f00dbeef",
        packageName = "com.example.app",
        capturedAtMillis = 1700L,
        nodes = listOf(node),
        textSummary = "搜索",
        truncated = false,
    )

    @Test
    fun parserAcceptsOnlyScopedShape() {
        assertEquals(
            SnapshotScopedRef("screen_1700_f00dbeef", "n0_abcd1234_f00dbeef"),
            SnapshotRefs.parse(" screen_1700_f00dbeef/n0_abcd1234_f00dbeef "),
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

    private fun validate(ref: String, snap: ScreenStateSnapshot? = snapshot, pkg: String? = "com.example.app") =
        SnapshotRefs.validate(SnapshotRefs.parse(ref)!!, snap, pkg)

    @Test
    fun validationFailuresAreClosedAndOrdered() {
        val ok = "screen_1700_f00dbeef/n0_abcd1234_f00dbeef"
        assertEquals(node, validate(ok))
        assertEquals(UiActionFailureKind.SnapshotUnknown, failure { validate(ok, snap = null) })
        assertEquals(UiActionFailureKind.RefRetired, failure { validate("screen_1/n0_abcd1234_f00dbeef") })
        assertEquals(UiActionFailureKind.RefNotInSnapshot, failure { validate("screen_1700_f00dbeef/n9_abcd1234") })
        assertEquals(UiActionFailureKind.PackageChanged, failure { validate(ok, pkg = "com.other") })
        assertEquals(UiActionFailureKind.PackageChanged, failure { validate(ok, pkg = null) })
    }

    @Test
    fun boundsChangeIsElementMoved() {
        SnapshotRefs.requireSameBounds(node, ScreenBounds(0, 0, 100, 48))
        assertEquals(
            UiActionFailureKind.ElementMoved,
            failure { SnapshotRefs.requireSameBounds(node, ScreenBounds(0, 10, 100, 58)) },
        )
        assertEquals(UiActionFailureKind.ElementMoved, failure { SnapshotRefs.requireSameBounds(node, null) })
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
            failure { RuntimeTargetQuery(ref, bounds, snapshot, "com.other") },
        )
    }

    @Test
    fun resolverReturnsClosedFailureInsteadOfRanking() {
        val cases = mapOf(
            "screen_9/n0_abcd1234_f00dbeef" to UiActionFailureKind.RefRetired,
            "screen_1700_f00dbeef/n5_deadbeef" to UiActionFailureKind.RefNotInSnapshot,
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
            livePackage = "com.other",
        )
        assertEquals(UiActionFailureKind.PackageChanged, changed.failureKind)
        assertNull(changed.selectedNodeId)
        val valid = UiTargetResolver.explain(snapshot, UiTargetKind.SearchEntry, "screen_1700_f00dbeef/n0_abcd1234_f00dbeef")
        assertNull(valid.failureKind)
        assertEquals(node.id, valid.selectedNodeId)
    }

    @Test
    fun serviceSourceContractMapsRefExceptionAndKeepsGuards() {
        val root = File("src/main/java/com/bytedance/zgx/solin/device")
        val service = File(root, "SolinAccessibilityService.kt").readText()
        assertTrue(service.contains("error is SnapshotRefException"))
        assertTrue(service.contains("failureKind = error.failureKind"))
        // The published observation, not the fresh evidence `before`, is what scoped refs validate against.
        assertTrue(service.contains("runCatching { operation(lastPublishedSnapshot) }"))
        assertTrue(service.contains("service.lastPublishedSnapshot = result.snapshot"))
        assertEquals(2, Regex("lastPublishedSnapshot = null").findAll(service).count())
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
    fun toolBoundaryAllowsEverySnapshotRefFailureKind() {
        val root = File("src/main/java/com/bytedance/zgx/solin/tool")
        val registry = File(root, "ToolRegistry.kt").readText()
        val schema = File(root, "ToolSchemasUi.kt").readText()
        listOf(
            UiActionFailureKind.SnapshotUnknown,
            UiActionFailureKind.RefRetired,
            UiActionFailureKind.RefNotInSnapshot,
            UiActionFailureKind.PackageChanged,
            UiActionFailureKind.ElementMoved,
        ).forEach { kind ->
            val quoted = "\"${kind.schemaValue}\""
            assertTrue(kind.schemaValue, registry.contains(quoted))
            assertTrue(kind.schemaValue, schema.contains(quoted))
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
}
