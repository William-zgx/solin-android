package com.bytedance.zgx.solin.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.ReasoningEffort
import com.bytedance.zgx.solin.RemoteModelConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceSettingsStoreTest {
    @Test
    fun loadRemoteConfigReadsOnePreferencesSnapshot() {
        val dataStore = CountingPreferencesDataStore()
        val store = PreferenceSettingsStore(dataStore)
        store.saveRemoteConfig(
            RemoteModelConfig(
                baseUrl = "https://api.example.com/v1",
                modelName = "model-a",
                supportsVisionInput = true,
                supportsToolCalls = true,
                contextWindowTokens = 32_000,
            ),
        )
        dataStore.reads.set(0)

        val loaded = store.loadRemoteConfig(apiKey = "")

        assertEquals(1, dataStore.reads.get())
        assertEquals("https://api.example.com/v1", loaded.baseUrl)
        assertEquals("model-a", loaded.modelName)
        assertTrue(loaded.supportsVisionInput)
        assertTrue(loaded.supportsToolCalls)
        assertEquals(32_000, loaded.contextWindowTokens)
    }

    @Test
    fun loadGenerationParametersReadsOnePreferencesSnapshot() {
        val dataStore = CountingPreferencesDataStore()
        val store = PreferenceSettingsStore(dataStore)
        val saved = store.saveGenerationParameters(
            GenerationParameters(temperature = 0.3f, topP = 0.8f, topK = 20, reasoningEffort = ReasoningEffort.entries.last()),
        )
        dataStore.reads.set(0)

        val loaded = store.loadGenerationParameters()

        assertEquals(1, dataStore.reads.get())
        assertEquals(saved, loaded)
    }

    @Test
    fun defaultsAndRoundTripsAreUnchanged() {
        val store = PreferenceSettingsStore(CountingPreferencesDataStore())

        assertFalse(store.isSetupDismissed())
        assertTrue(store.isMemoryEnabled())
        assertFalse(store.reduceDeviceActionConfirmations())
        assertFalse(store.remoteGuiAutomationEnabled())
        assertEquals(GenerationParameters().normalized(), store.loadGenerationParameters())
        assertEquals(InferenceMode.Local, store.loadInferenceMode())
        assertEquals(BackendChoice.GPU, store.loadBackend())
        assertNull(store.activeSessionId())
        assertNull(store.selectedModelId())
        assertNull(store.activeInstalledModelId())
        assertEquals(0, store.migrationVersion())
        assertNull(store.loadRemoteConfig(apiKey = "").contextWindowTokens)

        store.markSetupDismissed()
        store.setMemoryEnabled(false)
        store.saveBackend(BackendChoice.CPU)
        store.saveActiveSessionId("session-1")
        store.setMigrationVersion(3)

        assertTrue(store.isSetupDismissed())
        assertFalse(store.isMemoryEnabled())
        assertEquals(BackendChoice.CPU, store.loadBackend())
        assertEquals("session-1", store.activeSessionId())
        assertEquals(3, store.migrationVersion())
    }

    private class CountingPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        val reads = AtomicInteger(0)

        override val data: Flow<Preferences>
            get() = state.onStart { reads.incrementAndGet() }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
