package com.bytedance.zgx.solin

enum class AdaptiveInferenceRollout {
    Off,
    Shadow,
    OptIn,
    Visible,
    ;

    val autoSelectable: Boolean
        get() = this == OptIn || this == Visible

    val evaluatesShadowPlacement: Boolean
        get() = this == Shadow

    fun sanitizePreference(mode: InferenceMode): InferenceMode =
        if (mode == InferenceMode.Auto && !autoSelectable) InferenceMode.Local else mode

    companion object {
        fun parse(raw: String?): AdaptiveInferenceRollout = when (raw?.trim()?.lowercase()) {
            "shadow" -> Shadow
            "opt_in" -> OptIn
            "visible" -> Visible
            else -> Off
        }
    }
}
