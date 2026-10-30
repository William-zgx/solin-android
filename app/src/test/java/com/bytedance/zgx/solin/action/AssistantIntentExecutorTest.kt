package com.bytedance.zgx.solin.action

import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executor contract for dial / SMS drafts, media keys and the DND / volume settings targets. */
class AssistantIntentExecutorTest {
    private fun request(toolName: String, arguments: Map<String, String>) =
        ToolRequest(id = "req-$toolName", toolName = toolName, arguments = arguments, reason = "test")

    @Test
    fun dialAndSmsOpenDraftsWithoutPayloadInResultMetadata() {
        val launched = mutableListOf<ExternalActivityLaunch>()
        val executor = ActionExecutor(context = null, externalActivityStarter = { launch -> launched += launch; true })

        val dial = executor.execute(request(MobileActionFunctions.DIAL_PHONE_NUMBER, mapOf("phoneNumber" to "10086")))
        val sms = executor.execute(
            request(MobileActionFunctions.COMPOSE_SMS, mapOf("phoneNumber" to "+8613800138000", "body" to "查询话费")),
        )

        assertEquals(ToolStatus.Succeeded, dial.status)
        assertEquals("PhoneDial", dial.data["targetKind"])
        assertEquals(Intent.ACTION_DIAL, dial.data["intentAction"])
        assertEquals(ToolStatus.Succeeded, sms.status)
        assertEquals("SmsDraft", sms.data["targetKind"])
        assertEquals(Intent.ACTION_SENDTO, sms.data["intentAction"])
        assertEquals(listOf(Intent.ACTION_DIAL, Intent.ACTION_SENDTO), launched.map { it.action })
        assertEquals(listOf("tel:10086", "smsto:+8613800138000"), launched.map { it.uri })
        assertEquals("tel", dial.data["targetUriScheme"])
        assertEquals("smsto", sms.data["targetUriScheme"])
        // Neither the number nor the SMS body is echoed into result data or summaries.
        listOf(dial, sms).forEach { result ->
            val rendered = result.data.values.joinToString() + result.summary
            assertTrue(rendered, "10086" !in rendered && "13800138000" !in rendered && "查询话费" !in rendered)
            assertEquals("false", result.data["rawPayloadIncluded"])
            assertEquals("false", result.data["completionVerified"])
        }
    }

    @Test
    fun dialAndSmsRejectNonNumericTargetsBeforeStartingActivity() {
        val launched = mutableListOf<ExternalActivityLaunch>()
        val executor = ActionExecutor(context = null, externalActivityStarter = { launch -> launched += launch; true })
        val invalid = listOf(
            MobileActionFunctions.DIAL_PHONE_NUMBER to mapOf("phoneNumber" to "*#06#"),
            MobileActionFunctions.DIAL_PHONE_NUMBER to mapOf("phoneNumber" to "10086,123"),
            MobileActionFunctions.DIAL_PHONE_NUMBER to mapOf("phoneNumber" to "妈妈"),
            MobileActionFunctions.DIAL_PHONE_NUMBER to mapOf("phoneNumber" to "12"),
            MobileActionFunctions.DIAL_PHONE_NUMBER to mapOf("phoneNumber" to "10086", "body" to "x"),
            MobileActionFunctions.COMPOSE_SMS to mapOf("phoneNumber" to "10086;evil"),
            MobileActionFunctions.COMPOSE_SMS to mapOf("phoneNumber" to "10086", "body" to "x".repeat(1_001)),
        )

        invalid.forEach { (toolName, arguments) ->
            val result = executor.execute(request(toolName, arguments))
            assertEquals(arguments.toString(), ToolErrorCode.InvalidRequest, result.error?.code)
        }
        assertTrue(launched.isEmpty())
    }

    @Test
    fun mediaControlDispatchesOneKeyPerCommand() {
        val cases = listOf(
            "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
            "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
            "play_pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
            "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
        )
        cases.forEach { (command, keyCode) ->
            val dispatched = mutableListOf<Int>()
            val executor = ActionExecutor(context = null, mediaKeyDispatcher = { code -> dispatched += code; true })

            val result = executor.execute(request(MobileActionFunctions.CONTROL_MEDIA, mapOf("command" to command)))

            assertEquals(command, ToolStatus.Succeeded, result.status)
            assertEquals(listOf(keyCode), dispatched)
            assertEquals(command, result.data["command"])
            assertEquals("KeyEventDispatched", result.data["dispatchState"])
            assertEquals("false", result.data["completionVerified"])
            assertNull(defaultBuiltInToolRegistry.validateResult(
                request(MobileActionFunctions.CONTROL_MEDIA, mapOf("command" to command)),
                result,
            ))
        }
    }

    @Test
    fun mediaControlFailsClosedWithoutDispatcherOrOnUnknownCommand() {
        val unavailable = ActionExecutor(context = null)
            .execute(request(MobileActionFunctions.CONTROL_MEDIA, mapOf("command" to "play")))
        val refused = ActionExecutor(context = null, mediaKeyDispatcher = { false })
            .execute(request(MobileActionFunctions.CONTROL_MEDIA, mapOf("command" to "next")))
        val throwing = ActionExecutor(context = null, mediaKeyDispatcher = { error("boom") })
            .execute(request(MobileActionFunctions.CONTROL_MEDIA, mapOf("command" to "next")))
        val dispatched = mutableListOf<Int>()
        val unknown = ActionExecutor(context = null, mediaKeyDispatcher = { code -> dispatched += code; true })
            .execute(request(MobileActionFunctions.CONTROL_MEDIA, mapOf("command" to "volume_up")))

        assertEquals(ToolErrorCode.ExecutionFailed, unavailable.error?.code)
        assertEquals(ToolErrorCode.ExecutionFailed, refused.error?.code)
        assertEquals(ToolErrorCode.ExecutionFailed, throwing.error?.code)
        assertEquals(ToolErrorCode.InvalidRequest, unknown.error?.code)
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun doNotDisturbAndVolumeFallBackToSoundSettingsWhenPrimaryPageIsMissing() {
        val executor = ActionExecutor(context = null)

        assertEquals(
            listOf(
                "android.settings.ZEN_MODE_SETTINGS",
                Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS,
                Settings.ACTION_SOUND_SETTINGS,
            ),
            executor.systemSettingsActionsFor(SystemSettingsTargets.DO_NOT_DISTURB),
        )
        assertEquals(
            listOf("android.settings.panel.action.VOLUME", Settings.ACTION_SOUND_SETTINGS),
            executor.systemSettingsActionsFor(SystemSettingsTargets.VOLUME),
        )
        assertEquals(listOf(Settings.ACTION_SOUND_SETTINGS), executor.systemSettingsActionsFor(SystemSettingsTargets.SOUND))
    }

    @Test
    fun doNotDisturbAndVolumeExposeTheirSettingsActions() {
        val launches = mutableListOf<ExternalActivityLaunch>()
        val executor = ActionExecutor(context = null, externalActivityStarter = { launch -> launches += launch; true })

        executor.execute(request(MobileActionFunctions.OPEN_SYSTEM_SETTINGS, mapOf("target" to SystemSettingsTargets.DO_NOT_DISTURB)))
        executor.execute(request(MobileActionFunctions.OPEN_SYSTEM_SETTINGS, mapOf("target" to SystemSettingsTargets.VOLUME)))
        executor.execute(request(MobileActionFunctions.OPEN_SYSTEM_SETTINGS, mapOf("target" to SystemSettingsTargets.SOUND)))

        assertEquals(
            listOf("android.settings.ZEN_MODE_SETTINGS", "android.settings.panel.action.VOLUME", Settings.ACTION_SOUND_SETTINGS),
            launches.map { it.action },
        )
    }
}
