package com.bytedance.zgx.solin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugIntentHandlerTest {
    @Test
    fun debugPromptIsTrimmedAndOnlyDispatchedInDebugBuilds() {
        assertEquals(
            "打开小红书搜索咖啡",
            debugPromptOrNull(isDebugBuild = true, restoredFromSavedState = false, rawPrompt = "  打开小红书搜索咖啡 "),
        )
        assertNull(debugPromptOrNull(isDebugBuild = false, restoredFromSavedState = false, rawPrompt = "hello"))
        assertNull(debugPromptOrNull(isDebugBuild = true, restoredFromSavedState = false, rawPrompt = "   "))
        assertNull(debugPromptOrNull(isDebugBuild = true, restoredFromSavedState = false, rawPrompt = null))
    }

    @Test
    fun launchIntentRestoredFromSavedStateDoesNotResendDebugPrompt() {
        // Process death / recreation re-delivers the original launch intent with the extra intact.
        assertNull(debugPromptOrNull(isDebugBuild = true, restoredFromSavedState = true, rawPrompt = "hello"))
    }
}
