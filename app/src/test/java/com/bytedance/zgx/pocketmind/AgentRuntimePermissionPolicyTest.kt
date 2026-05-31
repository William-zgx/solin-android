package com.bytedance.zgx.pocketmind

import android.Manifest
import android.os.Build
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimePermissionPolicyTest {
    @Test
    fun reminderRequestsNotificationPermissionOnlyOnAndroid13Plus() {
        val confirmation = confirmationFor(MobileActionFunctions.SCHEDULE_REMINDER)

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S).isEmpty())
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU),
        )
    }

    @Test
    fun calendarAndContactToolsRequestTheirRuntimePermissions() {
        assertEquals(
            listOf(Manifest.permission.READ_CALENDAR),
            confirmationFor(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY).runtimePermissionsFor(),
        )
        assertEquals(
            listOf(Manifest.permission.READ_CONTACTS),
            confirmationFor(MobileActionFunctions.QUERY_CONTACTS).runtimePermissionsFor(),
        )
    }

    @Test
    fun recentFilesUsesLegacyStoragePermissionBeforeAndroid13() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.QUERY_RECENT_FILES,
            arguments = mapOf("kind" to "images"),
        )

        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.S),
        )
    }

    @Test
    fun recentFilesUsesMediaSpecificPermissionsOnAndroid13Plus() {
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES),
            confirmationFor(
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "images"),
            ).runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU),
        )
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            ),
            confirmationFor(MobileActionFunctions.QUERY_RECENT_FILES)
                .runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU),
        )
    }

    @Test
    fun recentNonMediaFilesDoNotPretendToHaveARequestableAndroid13Permission() {
        val confirmation = confirmationFor(
            toolName = MobileActionFunctions.QUERY_RECENT_FILES,
            arguments = mapOf("kind" to "documents"),
        )

        assertTrue(confirmation.runtimePermissionsFor(apiLevel = Build.VERSION_CODES.TIRAMISU).isEmpty())
    }

    private fun confirmationFor(
        toolName: String,
        arguments: Map<String, String> = emptyMap(),
    ): PendingAgentConfirmation =
        PendingAgentConfirmation(
            runId = "run-1",
            draft = ActionDraft(
                functionName = toolName,
                title = "Test",
                summary = "Test",
                parameters = arguments,
            ),
            toolRequest = ToolRequest(
                id = "request-1",
                toolName = toolName,
                arguments = arguments,
                reason = "test",
            ),
            skillId = null,
            plannedByModel = false,
            fallbackReason = null,
        )
}
