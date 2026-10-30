package com.bytedance.zgx.solin.multimodal

/**
 * Boundary invariants for the opt-in remote-vision GUI automation screenshot path.
 *
 * This is the deliberate inverse of [CurrentScreenshotOcrContract]: where the OCR contract
 * fails closed on pixels ever leaving the device, this path — gated behind the
 * `remoteGuiAutomationEnabled` opt-in and a first-confirm gate — transmits transient JPEG pixels to
 * a remote vision model so it can "see" the current screen and decide a tap. The two contracts must
 * never be confused: the OCR capture stays LocalOnly with pixels recycled on-device; this capture
 * crosses the remote boundary.
 *
 * Capture mechanism: the already-connected [android.accessibilityservice.AccessibilityService]'s
 * `takeScreenshot` (API 30+) — NOT MediaProjection. There is no per-capture system screen-cast
 * dialog and no foreground service; screen-pixel egress consent is governed entirely by the in-app
 * opt-in toggle plus the run's first-confirm gate.
 *
 * Invariants enforced here (parallel to the OCR boundary, inverted where the meaning flips):
 * - [requiresRemoteVisionOptIn]: capture only runs when the user enabled remote GUI automation.
 * - [inAppScreenEgressConsentRequired]: screen-pixel egress is gated by the in-app opt-in + the
 *   run's first-confirm gate (not by any per-capture OS consent dialog).
 * - [firstSendConfirmationRequired]: the first remote send of a run is user-confirmed; sensitive
 *   content and dangerous taps stay confirmed on every send even within an authorized continuation.
 * - [crossesRemoteBoundary]: pixels ARE transmitted (the point of the feature) — must be true.
 * - [persistsPixels]: pixels must NEVER be persisted; bytes are transient and dropped after send.
 * - [producesSemanticVisualUnderstanding]: the remote model DOES produce visual semantics — must
 *   be true (this is exactly what the OCR contract forbids).
 * - [tapsExecutedLocally]: the model's decision is an untrusted suggestion; taps run locally
 *   through the unchanged device-control executor with all preflights (dangerous/foreground).
 */
object RemoteVisionScreenshotContract {
    const val CONSENT_REASON = "accessibility_remote_vision_gui_automation"
    const val SOURCE = "current_screen"

    fun validateBoundary(boundary: RemoteVisionScreenshotBoundary): List<String> {
        val errors = mutableListOf<String>()
        if (!boundary.requiresRemoteVisionOptIn) {
            errors += "remote vision screenshot must require the remote GUI automation opt-in"
        }
        if (!boundary.inAppScreenEgressConsentRequired) {
            errors += "remote vision screenshot must gate screen-pixel egress behind the in-app opt-in and first-confirm, not per-capture OS consent"
        }
        if (!boundary.firstSendConfirmationRequired) {
            errors += "remote vision screenshot must require first-send user confirmation"
        }
        if (!boundary.crossesRemoteBoundary) {
            errors += "remote vision screenshot must cross the remote boundary (that is its purpose)"
        }
        if (boundary.persistsPixels) {
            errors += "remote vision screenshot must not persist pixels; transient bytes only"
        }
        if (!boundary.producesSemanticVisualUnderstanding) {
            errors += "remote vision screenshot must produce visual semantics on the remote model"
        }
        if (!boundary.tapsExecutedLocally) {
            errors += "remote vision decisions must be executed locally through the device-control preflights"
        }
        return errors
    }
}

data class RemoteVisionScreenshotBoundary(
    val requiresRemoteVisionOptIn: Boolean,
    val inAppScreenEgressConsentRequired: Boolean,
    val firstSendConfirmationRequired: Boolean,
    val crossesRemoteBoundary: Boolean,
    val persistsPixels: Boolean,
    val producesSemanticVisualUnderstanding: Boolean,
    val tapsExecutedLocally: Boolean,
)
