package com.bytedance.zgx.pocketmind.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPlannerTest {
    private val planner = MobileActionPlanner()

    @Test
    fun parsesWhitelistedCallOutputIntoConfirmedDraft() {
        val draft = planner.parseModelOutput(
            """call:compose_email{"subject":"Hi","body":"明天聊"}""",
        )

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.COMPOSE_EMAIL, draft.functionName)
        assertEquals("Hi", draft.parameters["subject"])
        assertEquals("明天聊", draft.parameters["body"])
        assertTrue(draft.requiresConfirmation)
    }

    @Test
    fun rejectsUnsupportedFunctionCalls() {
        assertNull(planner.parseModelOutput("""call:delete_contact{"name":"A"}"""))
    }

    @Test
    fun infersDraftForNaturalLanguageAction() {
        val plan = planner.plan("帮我打开 Wi-Fi 设置")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, plan.draft?.functionName)
    }
}
