package com.bytedance.zgx.solin

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Intent hooks that only exist for ADB and instrumentation evidence runs. Every entry point is a
 * no-op outside debuggable builds, so release intents carrying these extras are ignored.
 */
internal class DebugIntentHandler(
    private val context: Context,
    private val viewModel: () -> SolinViewModel,
) {
    /** Must run off the main thread: it persists the remote model profile. */
    fun configureRemoteModelForScreenshotEvidenceIfPresent(intent: Intent) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val baseUrl = intent.getStringExtra(MainActivity.EXTRA_DEBUG_SCREENSHOT_REMOTE_BASE_URL) ?: return
        val modelName = intent.getStringExtra(MainActivity.EXTRA_DEBUG_SCREENSHOT_REMOTE_MODEL_NAME) ?: return
        val supportsVisionInput =
            intent.getBooleanExtra(MainActivity.EXTRA_DEBUG_SCREENSHOT_REMOTE_SUPPORTS_VISION_INPUT, false)
        viewModel().configureDebugRemoteModelForScreenshotEvidence(
            baseUrl = baseUrl,
            modelName = modelName,
            supportsVisionInput = supportsVisionInput,
        )
    }

    /**
     * If the launch or new intent carries a UTF-8 prompt extra, forward it straight into
     * [SolinViewModel.sendMessage], bypassing the IME. Lets ADB drive a real agent run (including
     * the remote-vision GUI loop) with text `adb shell input text` cannot type (e.g. Chinese) via
     * `am start ... --es <EXTRA_DEBUG_PROMPT> "打开小红书搜索咖啡"`, whether or not Solin is
     * already running. A launch intent restored from saved state (process death) is skipped so a
     * prompt is never resent.
     */
    fun dispatchPromptIfPresent(intent: Intent, restoredFromSavedState: Boolean = false) {
        val prompt = debugPromptOrNull(
            isDebugBuild = BuildConfig.DEBUG,
            restoredFromSavedState = restoredFromSavedState,
            rawPrompt = intent.getStringExtra(MainActivity.EXTRA_DEBUG_PROMPT),
        ) ?: return
        // Consume once so a re-delivered intent (configuration change) does not resend.
        intent.removeExtra(MainActivity.EXTRA_DEBUG_PROMPT)
        Log.i("Solin/DebugPrompt", "dispatching prompt len=${prompt.length}")
        viewModel().sendMessage(prompt)
    }
}

internal fun debugPromptOrNull(
    isDebugBuild: Boolean,
    restoredFromSavedState: Boolean,
    rawPrompt: String?,
): String? =
    rawPrompt?.trim()?.takeIf { isDebugBuild && !restoredFromSavedState && it.isNotEmpty() }
