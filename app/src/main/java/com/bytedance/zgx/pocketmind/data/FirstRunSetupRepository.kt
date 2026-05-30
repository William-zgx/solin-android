package com.bytedance.zgx.pocketmind.data

import android.content.Context

class FirstRunSetupRepository(
    private val settingsStore: SettingsStore,
) {
    constructor(context: Context) : this(PreferenceSettingsStore(context))

    fun isSetupDismissed(): Boolean =
        settingsStore.isSetupDismissed()

    fun markSetupDismissed() {
        settingsStore.markSetupDismissed()
    }

    fun isMemoryEnabled(): Boolean =
        settingsStore.isMemoryEnabled()

    fun setMemoryEnabled(enabled: Boolean) {
        settingsStore.setMemoryEnabled(enabled)
    }
}
