package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeChildAuthorizationTest {
    private val store = InMemoryAgentTraceStore(clockMillis = { 1_000L })
    private var criticalOnly = true
    private val policy = ConfirmationBypassPolicy(defaultBuiltInToolRegistry, store,
        AgentTraceQueries(store), { criticalOnly })
    private val parent = ToolRequest(id = "parent", toolName = "ui_set_wheel",
        arguments = mapOf("target" to "小时", "value" to "9", "expectedPackageName" to "example.clock"))

    private fun activeRun(): String {
        val run = store.createRun("把小时设为9")
        store.appendStep(run.id, AgentStep.ToolRequested(parent,
            ActionDraft(parent.toolName, "设置滚轮", "设置滚轮", parent.arguments)))
        store.updateState(run.id, AgentRunState.ExecutingTool)
        return run.id
    }

    private fun child(tool: String = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
        extra: Map<String, String> = emptyMap()) = ToolRequest(id = "parent:child:1", toolName = tool,
        arguments = mapOf("expectedPackageName" to "example.clock") + extra)

    @Test fun activeCriticalOnlyRunCanObserveItsBoundPackage() {
        assertTrue(policy.canAuthorizeCompositeChild(activeRun(), parent, child()))
    }

    @Test fun parentConfirmationDoesNotAuthorizeChildrenInPerStepMode() {
        val id = activeRun()
        criticalOnly = false
        store.appendStep(id, AgentStep.UserConfirmed(parent.id))
        assertFalse(policy.canAuthorizeCompositeChild(id, parent, child()))
    }

    @Test fun terminatedRunAndUnboundPackageAreRejected() {
        val id = activeRun()
        assertFalse(policy.canAuthorizeCompositeChild(id, parent,
            child(extra = mapOf("expectedPackageName" to "other.app"))))
        store.updateState(id, AgentRunState.Cancelled)
        assertFalse(policy.canAuthorizeCompositeChild(id, parent, child()))
    }

    @Test fun compositeCannotSmuggleTapOrForeignRequest() {
        val id = activeRun()
        assertFalse(policy.canAuthorizeCompositeChild(id, parent,
            child(MobileActionFunctions.UI_TAP, mapOf("target" to "发送"))))
        assertFalse(policy.canAuthorizeCompositeChild(id, parent, child().copy(id = "other:child:1")))
        assertFalse(policy.canAuthorizeCompositeChild(id, parent.copy(id = "other"), child()))
    }

    @Test fun privateReadCannotAuthorizeTextAbsentFromUserRequest() {
        val id = activeRun()
        assertFalse(policy.canAuthorizeCompositeChild(id, parent,
            child(MobileActionFunctions.UI_TYPE_TEXT, mapOf("text" to "private content"))))
        assertTrue(policy.canAuthorizeCompositeChild(id, parent,
            child(MobileActionFunctions.UI_TYPE_TEXT, mapOf("text" to "9"))))
    }
}
