package com.bytedance.zgx.solin

import com.bytedance.zgx.solin.architecture.ProductionSourceScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the MainActivity -> ViewModel trust boundary: every SolinScreen callback that reaches
 * persistence, Keystore-backed secrets, the runtime, or the pending remote-send state must be
 * dispatched through [SolinViewModel.launchPersistenceWork] (IO dispatcher + the persistence
 * action mutex), so it is ordered against queued sends and disclosure confirmations and never runs
 * Keystore / EncryptedSecretStore work on the main thread.
 */
class MainActivityPersistenceDispatchTest {
    @Test
    fun screenCallbacksDoNotBindViewModelMethodsDirectly() {
        val source = ProductionSourceScanner.kotlinFile("MainActivity.kt").readText()
        val directBindings = DIRECT_VIEW_MODEL_BINDING.findAll(source)
            .map { match -> match.groupValues[1] to match.groupValues[2] }
            .toList()

        assertTrue("expected to find SolinScreen callback bindings", source.contains("SolinScreen("))
        val unexpected = directBindings.filterNot { (_, method) -> method in IN_MEMORY_ONLY_METHODS }
        assertEquals(
            "SolinScreen callbacks bound directly to ViewModel methods bypass launchPersistenceWork",
            emptyList<Pair<String, String>>(),
            unexpected,
        )
    }

    @Test
    fun inferenceModeAndRemoteConfigCallbacksAreSerializedThroughPersistenceWork() {
        val source = ProductionSourceScanner.kotlinFile("MainActivity.kt").readText()

        listOf(
            "onInferenceModeSelected" to "selectInferenceMode",
            "onRemoteModelConfigChanged" to "updateRemoteModelConfig",
        ).forEach { (callback, method) ->
            val binding = Regex("""$callback\s*=\s*\{[^}]*\}""").find(source)?.value
            assertTrue("$callback must be a lambda dispatching via launchPersistenceWork", binding != null)
            assertTrue(
                "$callback must call viewModel.$method inside launchPersistenceWork: $binding",
                Regex("""viewModel\.launchPersistenceWork\s*\{\s*viewModel\.$method\(""")
                    .containsMatchIn(binding!!),
            )
        }
    }

    private companion object {
        val DIRECT_VIEW_MODEL_BINDING = Regex("""\b(on\w+)\s*=\s*viewModel::(\w+)""")

        /** Pure in-memory UI-state updates that never touch persistence or the trust boundary. */
        val IN_MEMORY_ONLY_METHODS = setOf("consumeVoiceInputDraft")
    }
}
