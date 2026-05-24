package com.bytedance.zgx.pocketmind.data

import android.content.Context

private const val PREFS_NAME = "pocketmind"
private const val PREF_FIRST_RUN_SETUP_DISMISSED = "first_run_setup_dismissed"
private const val PREF_MEMORY_ENABLED = "memory_enabled"

class FirstRunSetupRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSetupDismissed(): Boolean =
        prefs.getBoolean(PREF_FIRST_RUN_SETUP_DISMISSED, false)

    fun markSetupDismissed() {
        prefs.edit().putBoolean(PREF_FIRST_RUN_SETUP_DISMISSED, true).apply()
    }

    fun isMemoryEnabled(): Boolean =
        prefs.getBoolean(PREF_MEMORY_ENABLED, true)

    fun setMemoryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_MEMORY_ENABLED, enabled).apply()
    }
}
