package com.bytedance.zgx.pocketmind.data

import android.content.Context

class FirstRunSetupRepository(
    private val settingsStore: SettingsStore,
) : FirstRunSetupStore {
    constructor(context: Context) : this(PreferenceSettingsStore(context))

    override fun isSetupDismissed(): Boolean =
        settingsStore.isSetupDismissed()

    override fun markSetupDismissed() {
        settingsStore.markSetupDismissed()
    }

    override fun isMemoryEnabled(): Boolean =
        settingsStore.isMemoryEnabled()

    override fun setMemoryEnabled(enabled: Boolean) {
        settingsStore.setMemoryEnabled(enabled)
    }

    override fun reduceDeviceActionConfirmations(): Boolean =
        settingsStore.reduceDeviceActionConfirmations()

    override fun setReduceDeviceActionConfirmations(enabled: Boolean) {
        settingsStore.setReduceDeviceActionConfirmations(enabled)
    }
}
