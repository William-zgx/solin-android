package com.bytedance.zgx.solin

import android.speech.RecognizerIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecognitionRouteTest {
    @Test
    fun prefersOnDeviceRecognizerOnApi31PlusWhenAvailable() {
        val route = selectVoiceRecognizerRoute(
            sdkInt = 31,
            onDeviceRecognitionAvailable = true,
            systemRecognitionAvailable = true,
        )

        assertEquals(VoiceRecognizerRoute.OnDevice, route)
        assertFalse(route.mayUseOnlineService)
    }

    @Test
    fun ignoresOnDeviceFlagBeforeApi31() {
        val route = selectVoiceRecognizerRoute(
            sdkInt = 30,
            onDeviceRecognitionAvailable = true,
            systemRecognitionAvailable = true,
        )

        assertEquals(VoiceRecognizerRoute.SystemPreferOffline, route)
    }

    @Test
    fun systemRecognizerRouteIsFlaggedAsPossiblyOnlineSoConsentNoteIsShown() {
        val route = selectVoiceRecognizerRoute(
            sdkInt = 34,
            onDeviceRecognitionAvailable = false,
            systemRecognitionAvailable = true,
        )

        assertEquals(VoiceRecognizerRoute.SystemPreferOffline, route)
        assertTrue(route.mayUseOnlineService)
        assertTrue(VOICE_INPUT_ONLINE_RECOGNITION_NOTE.contains("在线服务"))
    }

    @Test
    fun noRecognizerAtAllIsUnavailable() {
        assertEquals(
            VoiceRecognizerRoute.Unavailable,
            selectVoiceRecognizerRoute(
                sdkInt = 34,
                onDeviceRecognitionAvailable = false,
                systemRecognitionAvailable = false,
            ),
        )
    }

    @Test
    fun intentAlwaysPrefersOfflineRecognitionAndKeepsExistingExtras() {
        val extras = voiceRecognitionIntentExtras()

        assertEquals(true, extras[RecognizerIntent.EXTRA_PREFER_OFFLINE])
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, extras[RecognizerIntent.EXTRA_LANGUAGE_MODEL])
        assertEquals(true, extras[RecognizerIntent.EXTRA_PARTIAL_RESULTS])
        assertEquals(1, extras[RecognizerIntent.EXTRA_MAX_RESULTS])
    }
}
