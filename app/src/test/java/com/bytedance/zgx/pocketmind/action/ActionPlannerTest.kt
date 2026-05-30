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
    fun parsesHermesStyleWebSearchCallOutput() {
        val draft = planner.parseModelOutput(
            """call:web_search{"query":"Kotlin coroutines Android"}""",
        )

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.WEB_SEARCH, draft.functionName)
        assertEquals("Kotlin coroutines Android", draft.parameters["query"])
        assertTrue(draft.summary.contains("浏览器"))
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

    @Test
    fun infersDraftForNaturalLanguageWebSearch() {
        val plan = planner.plan("帮我搜一下 Kotlin 协程最新用法")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.WEB_SEARCH, plan.draft?.functionName)
        assertEquals("Kotlin 协程最新用法", plan.draft?.parameters?.get("query"))
    }
}
