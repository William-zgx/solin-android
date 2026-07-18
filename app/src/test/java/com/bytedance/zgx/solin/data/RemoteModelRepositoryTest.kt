package com.bytedance.zgx.solin.data

import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_REMOTE_CONFIG_INTEGRITY_SHA256 = "remote_model_config_integrity_sha256"

class RemoteModelRepositoryTest {
    @Test
    fun saveConfigReturnsFailureWhenEncryptedSecretSaveFails() {
        val settingsStore = FakeSettingsStore()
        val repository = RemoteModelRepository(
            settingsStore = settingsStore,
            secretStore = FailingSecretStore,
        )

        val result = repository.saveConfig(
            RemoteModelConfig(
                baseUrl = " https://api.example.com/v1/ ",
                modelName = " model-a ",
                apiKey = "secret",
            ),
        )

        assertTrue(result.isFailure)
        assertEquals(RemoteModelConfig(), settingsStore.remoteConfig)
    }

    @Test
    fun saveConfigStoresNonSecretSettingsOnlyAfterSecretSaveSucceeds() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        val repository = RemoteModelRepository(settingsStore, secretStore)

        val result = repository.saveConfig(
            RemoteModelConfig(
                baseUrl = " https://api.example.com/v1/ ",
                modelName = " model-a ",
                apiKey = "secret",
                supportsVisionInput = false,
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals("https://api.example.com/v1", settingsStore.remoteConfig.baseUrl)
        assertEquals("model-a", settingsStore.remoteConfig.modelName)
        assertEquals(false, settingsStore.remoteConfig.supportsVisionInput)
        assertEquals("secret", secretStore.loadString("remote_model_api_key").getOrThrow())
        val integrity = secretStore.loadString(TEST_REMOTE_CONFIG_INTEGRITY_SHA256).getOrThrow()
        assertTrue(integrity.matches(Regex("[0-9a-f]{64}")))
        val revision = result.getOrThrow().profileRevision
        assertEquals(revision, UUID.fromString(revision).toString())
    }

    @Test
    fun saveConfigWithBlankApiKeyClearsPreviouslySavedSecret() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        var revision = 0
        val repository = RemoteModelRepository(
            settingsStore = settingsStore,
            secretStore = secretStore,
            revisionFactory = { "revision-${++revision}" },
        )

        val first = repository.saveConfig(
            RemoteModelConfig(
                baseUrl = "https://api.example.com/v1",
                modelName = "model-a",
                apiKey = "secret",
            ),
        ).getOrThrow()

        val cleared = repository.saveConfig(
            RemoteModelConfig(
                baseUrl = "https://api.example.com/v1",
                modelName = "model-a",
                apiKey = " ",
            ),
        ).getOrThrow()

        assertEquals("", secretStore.loadString("remote_model_api_key").getOrThrow())
        assertEquals("", repository.loadConfig().apiKey)
        assertNotEquals(first.profileRevision, cleared.profileRevision)
    }

    @Test
    fun materialCapabilityChangeRotatesRevisionAndInvalidatesConnectivity() {
        var now = 1_000L
        var revision = 0
        val repository = RemoteModelRepository(
            settingsStore = FakeSettingsStore(),
            secretStore = InMemorySecretStore(),
            elapsedRealtimeMillis = { now },
            revisionFactory = { "revision-${++revision}" },
        )
        val first = repository.saveConfig(config()).getOrThrow()
        repository.recordConnectivity(first, RemoteModelConnectivityStatus.Reachable)

        val unchanged = repository.saveConfig(
            first.copy(connectivityStatus = RemoteModelConnectivityStatus.AuthenticationFailed),
        ).getOrThrow()
        assertEquals(first.profileRevision, unchanged.profileRevision)
        assertEquals(RemoteModelConnectivityStatus.Reachable, repository.currentConnectivity(unchanged)?.status)

        val changed = repository.saveConfig(first.copy(supportsToolCalls = true)).getOrThrow()
        assertNotEquals(first.profileRevision, changed.profileRevision)
        assertNull(repository.currentConnectivity(changed))
    }

    @Test
    fun connectivityExpiresAtSixtySecondsAndClockRollbackFailsClosed() {
        var now = 10_000L
        val repository = repository(elapsedRealtimeMillis = { now })
        val config = repository.saveConfig(config()).getOrThrow()
        repository.recordConnectivity(config, RemoteModelConnectivityStatus.Reachable)

        now = 9_999L
        assertNull(repository.currentConnectivity(config))

        now = 10_000L
        repository.recordConnectivity(config, RemoteModelConnectivityStatus.Reachable)
        now += 59_999L
        assertEquals(RemoteModelConnectivityStatus.Reachable, repository.currentConnectivity(config)?.status)
        assertEquals(RemoteModelConnectivityStatus.Reachable, repository.loadConfig().connectivityStatus)

        now += 1L
        assertNull(repository.currentConnectivity(config))
        assertEquals(RemoteModelConnectivityStatus.Unknown, repository.loadConfig().connectivityStatus)
    }

    @Test
    fun connectivityNeverRestoresAcrossRepositoryInstances() {
        var now = 10_000L
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        val firstRepository = repository(settingsStore, secretStore) { now }
        val config = firstRepository.saveConfig(config()).getOrThrow()
        firstRepository.recordConnectivity(config, RemoteModelConnectivityStatus.Reachable)
        assertEquals(RemoteModelConnectivityStatus.Reachable, firstRepository.loadConfig().connectivityStatus)
        settingsStore.remoteConfig = settingsStore.remoteConfig.copy(
            connectivityStatus = RemoteModelConnectivityStatus.Reachable,
        )

        val rebuiltRepository = repository(settingsStore, secretStore) { now }
        val rebuiltConfig = rebuiltRepository.loadConfig()

        assertNull(rebuiltRepository.currentConnectivity(config))
        assertTrue(rebuiltConfig.isConfigured)
        assertEquals(config.baseUrl, rebuiltConfig.baseUrl)
        assertEquals(config.modelName, rebuiltConfig.modelName)
        assertEquals(config.apiKey, rebuiltConfig.apiKey)
        assertEquals(config.profileRevision, rebuiltConfig.profileRevision)
        assertEquals(RemoteModelConnectivityStatus.Unknown, rebuiltConfig.connectivityStatus)
    }

    @Test
    fun connectivityRequiresMatchingNonBlankRevision() {
        val repository = repository()
        val config = repository.saveConfig(config()).getOrThrow()
        repository.recordConnectivity(config, RemoteModelConnectivityStatus.Reachable)

        assertNull(repository.currentConnectivity(config.copy(profileRevision = "other-revision")))

        val blankRevision = config.copy(profileRevision = "")
        repository.recordConnectivity(blankRevision, RemoteModelConnectivityStatus.Reachable)
        assertNull(repository.currentConnectivity(blankRevision))
    }

    @Test
    fun rejectedInitialSecretWriteKeepsPersistedConfigAndSnapshot() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        val repository = repository(settingsStore, secretStore)
        val first = repository.saveConfig(config()).getOrThrow()
        repository.recordConnectivity(first, RemoteModelConnectivityStatus.Reachable)
        secretStore.failOnWriteNumbers += secretStore.writeCount + 1

        val result = repository.saveConfig(first.copy(modelName = "model-b"))

        assertTrue(result.isFailure)
        assertEquals(first.profileRevision, settingsStore.remoteConfig.profileRevision)
        assertEquals("secret", secretStore.loadString("remote_model_api_key").getOrThrow())
        assertEquals(RemoteModelConnectivityStatus.Reachable, repository.currentConnectivity(first)?.status)
    }

    @Test
    fun partialInitialSecretWriteRestoresOldKeyAndInvalidatesSnapshot() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        val repository = repository(settingsStore, secretStore)
        val first = repository.saveConfig(config()).getOrThrow()
        repository.recordConnectivity(first, RemoteModelConnectivityStatus.Reachable)
        secretStore.failAfterWriteOnWriteNumbers += secretStore.writeCount + 1

        val result = repository.saveConfig(
            first.copy(modelName = "model-b", apiKey = "new-secret"),
        )

        assertTrue(result.isFailure)
        assertEquals("encrypted prefs write result unavailable", result.exceptionOrNull()?.message)
        assertEquals("secret", secretStore.loadString("remote_model_api_key").getOrThrow())
        assertEquals(first.profileRevision, settingsStore.remoteConfig.profileRevision)
        assertNull(repository.currentConnectivity(first))
        assertTrue(repository.loadConfig().isConfigured)
    }

    @Test
    fun unverifiableInitialSecretWriteAndFailedRollbackFailsClosedAcrossRebuild() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        val repository = repository(settingsStore, secretStore)
        val first = repository.saveConfig(config()).getOrThrow()
        repository.recordConnectivity(first, RemoteModelConnectivityStatus.Reachable)
        secretStore.failAfterWriteOnWriteNumbers += secretStore.writeCount + 1
        secretStore.failOnWriteNumbers += secretStore.writeCount + 2
        secretStore.failOnReadNumbers += secretStore.readCount + 4

        val result = repository.saveConfig(
            first.copy(modelName = "model-b", apiKey = "new-secret"),
        )

        assertTrue(result.isFailure)
        assertEquals("encrypted prefs write result unavailable", result.exceptionOrNull()?.message)
        assertEquals(2, result.exceptionOrNull()?.suppressed?.size)
        assertNull(repository.currentConnectivity(first))
        assertFalse(repository.loadConfig().isConfigured)

        val rebuiltRepository = repository(settingsStore, secretStore)
        assertFalse(rebuiltRepository.loadConfig().isConfigured)
        assertNull(rebuiltRepository.currentConnectivity(first))
    }

    @Test
    fun settingsWriteFailureRestoresSecretAndInvalidatesConnectivity() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        val repository = repository(settingsStore, secretStore)
        val first = repository.saveConfig(config()).getOrThrow()
        repository.recordConnectivity(first, RemoteModelConnectivityStatus.Reachable)
        val previousIntegrity = secretStore.loadString(TEST_REMOTE_CONFIG_INTEGRITY_SHA256).getOrThrow()
        settingsStore.failWrites = true

        val result = repository.saveConfig(
            first.copy(modelName = "model-b", apiKey = "new-secret"),
        )

        assertTrue(result.isFailure)
        assertEquals("secret", secretStore.loadString("remote_model_api_key").getOrThrow())
        assertEquals(
            previousIntegrity,
            secretStore.loadString(TEST_REMOTE_CONFIG_INTEGRITY_SHA256).getOrThrow(),
        )
        assertEquals(first.profileRevision, settingsStore.remoteConfig.profileRevision)
        assertNull(repository.currentConnectivity(first))
    }

    @Test
    fun revisionFactoryFailureHappensBeforeSecretWrite() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        var revisionCalls = 0
        val repository = RemoteModelRepository(
            settingsStore = settingsStore,
            secretStore = secretStore,
            elapsedRealtimeMillis = { 1_000L },
            revisionFactory = {
                if (++revisionCalls == 1) "revision-1" else error("revision unavailable")
            },
        )
        val first = repository.saveConfig(config()).getOrThrow()
        repository.recordConnectivity(first, RemoteModelConnectivityStatus.Reachable)
        val writesBeforeFailure = secretStore.writeCount
        val settingsSavesBeforeFailure = settingsStore.saveCount

        val result = repository.saveConfig(first.copy(modelName = "model-b", apiKey = "new-secret"))

        assertTrue(result.isFailure)
        assertEquals(writesBeforeFailure, secretStore.writeCount)
        assertEquals(settingsSavesBeforeFailure, settingsStore.saveCount)
        assertEquals("secret", secretStore.loadString("remote_model_api_key").getOrThrow())
        assertEquals(RemoteModelConnectivityStatus.Reachable, repository.currentConnectivity(first)?.status)
    }

    @Test
    fun failedSecretRollbackMakesRepositoryConfigUnusable() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        val repository = repository(settingsStore, secretStore)
        val first = repository.saveConfig(config()).getOrThrow()
        repository.recordConnectivity(first, RemoteModelConnectivityStatus.Reachable)
        settingsStore.failWrites = true
        secretStore.failOnWriteNumbers += secretStore.writeCount + 3

        val result = repository.saveConfig(
            first.copy(modelName = "model-b", apiKey = "new-secret"),
        )

        assertTrue(result.isFailure)
        assertFalse(repository.loadConfig().isConfigured)
        assertEquals(RemoteModelConnectivityStatus.Unknown, repository.loadConfig().connectivityStatus)
        assertNull(repository.currentConnectivity(first))

        val rebuiltRepository = repository(settingsStore, secretStore)
        assertFalse(rebuiltRepository.loadConfig().isConfigured)
        assertEquals(RemoteModelConnectivityStatus.Unknown, rebuiltRepository.loadConfig().connectivityStatus)
        assertNull(rebuiltRepository.currentConnectivity(first))
    }

    @Test
    fun failedSecretReadReturnsEmptyConfigAndInvalidatesSnapshot() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        val repository = repository(settingsStore, secretStore)
        val first = repository.saveConfig(config()).getOrThrow()
        repository.recordConnectivity(first, RemoteModelConnectivityStatus.Reachable)
        secretStore.failReads = true

        val loaded = repository.loadConfig()

        assertFalse(loaded.isConfigured)
        assertEquals(RemoteModelConnectivityStatus.Unknown, loaded.connectivityStatus)
        assertNull(repository.currentConnectivity(first))
    }

    @Test
    fun missingIntegrityMarkerFailsClosed() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        val repository = repository(settingsStore, secretStore)
        val first = repository.saveConfig(config()).getOrThrow()
        repository.recordConnectivity(first, RemoteModelConnectivityStatus.Reachable)
        secretStore.saveString(TEST_REMOTE_CONFIG_INTEGRITY_SHA256, "").getOrThrow()

        val loaded = repository.loadConfig()

        assertFalse(loaded.isConfigured)
        assertEquals(RemoteModelConnectivityStatus.Unknown, loaded.connectivityStatus)
        assertNull(repository.currentConnectivity(first))
        assertFalse(repository(settingsStore, secretStore).loadConfig().isConfigured)
    }

    @Test
    fun mismatchedIntegrityMarkerFailsClosed() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        val repository = repository(settingsStore, secretStore)
        val first = repository.saveConfig(config()).getOrThrow()
        repository.recordConnectivity(first, RemoteModelConnectivityStatus.Reachable)
        secretStore.saveString(TEST_REMOTE_CONFIG_INTEGRITY_SHA256, "0".repeat(64)).getOrThrow()

        val loaded = repository.loadConfig()

        assertFalse(loaded.isConfigured)
        assertEquals(RemoteModelConnectivityStatus.Unknown, loaded.connectivityStatus)
        assertNull(repository.currentConnectivity(first))
    }

    @Test
    fun integrityMarkerReadFailureFailsClosed() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        val repository = repository(settingsStore, secretStore)
        val first = repository.saveConfig(config()).getOrThrow()
        repository.recordConnectivity(first, RemoteModelConnectivityStatus.Reachable)
        secretStore.failReadNames += TEST_REMOTE_CONFIG_INTEGRITY_SHA256

        val loaded = repository.loadConfig()

        assertFalse(loaded.isConfigured)
        assertEquals(RemoteModelConnectivityStatus.Unknown, loaded.connectivityStatus)
        assertNull(repository.currentConnectivity(first))
    }

    @Test
    fun successfulEmptySecretRemainsAValidConfiguration() {
        val repository = repository()
        repository.saveConfig(config(apiKey = "")).getOrThrow()

        val loaded = repository.loadConfig()

        assertTrue(loaded.isConfigured)
        assertEquals("", loaded.apiKey)
    }

    @Test
    fun saveConfigWithoutApiKeyKeepsStoredSecretAndRevision() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        val repository = repository(settingsStore, secretStore)
        val first = repository.saveConfig(config(apiKey = "secret")).getOrThrow()
        repository.recordConnectivity(first, RemoteModelConnectivityStatus.Reachable)

        val savedWithoutKey = repository.saveConfigWithoutApiKey(first.copy(apiKey = "")).getOrThrow()
        val loaded = repository.loadConfig()

        assertEquals("secret", savedWithoutKey.apiKey)
        assertEquals("secret", secretStore.loadString("remote_model_api_key").getOrThrow())
        assertEquals(first.profileRevision, savedWithoutKey.profileRevision)
        assertEquals(savedWithoutKey.baseUrl, loaded.baseUrl)
        assertEquals(savedWithoutKey.modelName, loaded.modelName)
        assertEquals(savedWithoutKey.apiKey, loaded.apiKey)
        assertEquals(savedWithoutKey.profileRevision, loaded.profileRevision)
        assertEquals(RemoteModelConnectivityStatus.Reachable, repository.currentConnectivity(first)?.status)
    }

    @Test
    fun settingsFailureWithoutApiKeyRestoresIntegrityMarkerWithoutWritingApiKey() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        val repository = repository(settingsStore, secretStore)
        val first = repository.saveConfig(config(apiKey = "")).getOrThrow()
        val previousIntegrity = secretStore.loadString(TEST_REMOTE_CONFIG_INTEGRITY_SHA256).getOrThrow()
        val writesBeforeFailure = secretStore.writeCount
        val apiKeyWritesBeforeFailure = secretStore.writeNames.count { it == "remote_model_api_key" }
        settingsStore.failWrites = true

        val result = repository.saveConfigWithoutApiKey(first.copy(modelName = "model-b"))

        assertTrue(result.isFailure)
        assertEquals(writesBeforeFailure + 2, secretStore.writeCount)
        assertEquals(
            apiKeyWritesBeforeFailure,
            secretStore.writeNames.count { it == "remote_model_api_key" },
        )
        assertEquals("", secretStore.loadString("remote_model_api_key").getOrThrow())
        assertEquals(
            previousIntegrity,
            secretStore.loadString(TEST_REMOTE_CONFIG_INTEGRITY_SHA256).getOrThrow(),
        )
        assertEquals(first.profileRevision, settingsStore.remoteConfig.profileRevision)
    }

    @Test
    fun saveConfigWithoutApiKeyRejectsBindingStoredSecretToAnotherTarget() {
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        val repository = repository(settingsStore, secretStore)
        val first = repository.saveConfig(config()).getOrThrow()
        repository.recordConnectivity(first, RemoteModelConnectivityStatus.Reachable)

        val result = repository.saveConfigWithoutApiKey(
            first.copy(baseUrl = "https://other.example.com/v1", apiKey = ""),
        )

        assertTrue(result.isFailure)
        assertEquals(first.baseUrl, settingsStore.remoteConfig.baseUrl)
        assertEquals(first.profileRevision, settingsStore.remoteConfig.profileRevision)
        assertEquals("secret", secretStore.loadString("remote_model_api_key").getOrThrow())
        assertEquals(RemoteModelConnectivityStatus.Reachable, repository.currentConnectivity(first)?.status)
    }

    @Test
    fun remoteModelStoreDefaultWithoutApiKeySaveFailsClosed() {
        var saveCalls = 0
        val store = object : RemoteModelStore {
            override fun loadMode(): InferenceMode = InferenceMode.Local
            override fun saveMode(mode: InferenceMode): InferenceMode = mode
            override fun loadConfig(): RemoteModelConfig = RemoteModelConfig()
            override fun saveConfig(config: RemoteModelConfig): Result<RemoteModelConfig> {
                saveCalls += 1
                return Result.success(config)
            }
        }

        val result = store.saveConfigWithoutApiKey(config(apiKey = ""))

        assertTrue(result.isFailure)
        assertEquals(0, saveCalls)
    }

    @Test
    fun saveSerializesAgainstInFlightConnectivityRecord() {
        val clockEntered = CountDownLatch(1)
        val releaseClock = CountDownLatch(1)
        val blockClock = AtomicBoolean(false)
        val settingsStore = FakeSettingsStore()
        val secretStore = InMemorySecretStore()
        var revision = 0
        val repository = RemoteModelRepository(
            settingsStore = settingsStore,
            secretStore = secretStore,
            elapsedRealtimeMillis = {
                if (blockClock.get()) {
                    clockEntered.countDown()
                    check(releaseClock.await(5, TimeUnit.SECONDS))
                }
                1_000L
            },
            revisionFactory = { "revision-${++revision}" },
        )
        val first = repository.saveConfig(config()).getOrThrow()
        blockClock.set(true)
        val recordThread = thread(name = "remote-connectivity-record") {
            repository.recordConnectivity(first, RemoteModelConnectivityStatus.Reachable)
        }
        assertTrue(clockEntered.await(5, TimeUnit.SECONDS))
        val saveStarted = CountDownLatch(1)
        val saveCompleted = CountDownLatch(1)
        val saved = AtomicReference<Result<RemoteModelConfig>>()
        val saveThread = thread(name = "remote-config-save") {
            saveStarted.countDown()
            saved.set(repository.saveConfig(first.copy(modelName = "model-b")))
            saveCompleted.countDown()
        }
        assertTrue(saveStarted.await(5, TimeUnit.SECONDS))

        val saveWasBlocked = !saveCompleted.await(500, TimeUnit.MILLISECONDS)
        releaseClock.countDown()
        recordThread.join(5_000L)
        saveThread.join(5_000L)

        assertTrue(saveWasBlocked)
        assertFalse(recordThread.isAlive)
        assertFalse(saveThread.isAlive)
        val second = saved.get().getOrThrow()
        assertNull(repository.currentConnectivity(first))
        assertNull(repository.currentConnectivity(second))
    }

    private fun repository(
        settingsStore: FakeSettingsStore = FakeSettingsStore(),
        secretStore: InMemorySecretStore = InMemorySecretStore(),
        elapsedRealtimeMillis: () -> Long = { 1_000L },
    ): RemoteModelRepository {
        var revision = 0
        return RemoteModelRepository(
            settingsStore = settingsStore,
            secretStore = secretStore,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
            revisionFactory = { "revision-${++revision}" },
        )
    }

    private fun config(apiKey: String = "secret"): RemoteModelConfig =
        RemoteModelConfig(
            baseUrl = "https://api.example.com/v1",
            modelName = "model-a",
            apiKey = apiKey,
            supportsVisionInput = false,
            supportsToolCalls = false,
            contextWindowTokens = 8_192,
        )

    private class FakeSettingsStore : SettingsStore {
        @Volatile
        var remoteConfig: RemoteModelConfig = RemoteModelConfig()
        @Volatile
        var failWrites: Boolean = false
        var saveCount: Int = 0

        override fun isSetupDismissed(): Boolean = false
        override fun markSetupDismissed() = Unit
        override fun isMemoryEnabled(): Boolean = true
        override fun setMemoryEnabled(enabled: Boolean) = Unit
        override fun loadGenerationParameters(): GenerationParameters = GenerationParameters()
        override fun saveGenerationParameters(parameters: GenerationParameters): GenerationParameters = parameters
        override fun loadBackend(): BackendChoice = BackendChoice.GPU
        override fun saveBackend(backend: BackendChoice) = Unit
        override fun loadInferenceMode(): InferenceMode = InferenceMode.Local
        override fun saveInferenceMode(mode: InferenceMode): InferenceMode = mode

        @Synchronized
        override fun loadRemoteConfig(apiKey: String): RemoteModelConfig =
            remoteConfig.copy(apiKey = apiKey)

        @Synchronized
        override fun saveRemoteConfig(config: RemoteModelConfig): RemoteModelConfig {
            saveCount += 1
            check(!failWrites) { "settings unavailable" }
            remoteConfig = config.normalized().copy(
                apiKey = "",
                connectivityStatus = RemoteModelConnectivityStatus.Unknown,
            )
            return remoteConfig
        }

        override fun selectedModelId(): String? = null
        override fun saveSelectedModelId(modelId: String) = Unit
        override fun activeInstalledModelId(): String? = null
        override fun saveActiveInstalledModelId(modelId: String?) = Unit
    }

    private object FailingSecretStore : SecretStore {
        override fun loadString(name: String): Result<String> = Result.success("")
        override fun saveString(name: String, value: String): Result<Unit> =
            Result.failure(IllegalStateException("encrypted prefs unavailable"))
    }

    private class InMemorySecretStore(
        var failWrites: Boolean = false,
    ) : SecretStore {
        private val values = mutableMapOf<String, String>()
        var failReads: Boolean = false
        val failReadNames = mutableSetOf<String>()
        val failOnReadNumbers = mutableSetOf<Int>()
        val failOnWriteNumbers = mutableSetOf<Int>()
        val failAfterWriteOnWriteNumbers = mutableSetOf<Int>()
        val writeNames = mutableListOf<String>()
        var readCount: Int = 0
            private set
        var writeCount: Int = 0
            private set

        @Synchronized
        override fun loadString(name: String): Result<String> =
            runCatching {
                readCount += 1
                check(
                    !failReads && name !in failReadNames && readCount !in failOnReadNumbers,
                ) { "encrypted prefs unavailable" }
                values[name].orEmpty()
            }

        @Synchronized
        override fun saveString(name: String, value: String): Result<Unit> =
            runCatching {
                writeCount += 1
                writeNames += name
                check(!failWrites && writeCount !in failOnWriteNumbers) { "encrypted prefs unavailable" }
                if (value.isBlank()) {
                    values.remove(name)
                } else {
                    values[name] = value
                }
                check(writeCount !in failAfterWriteOnWriteNumbers) {
                    "encrypted prefs write result unavailable"
                }
            }
    }
}
