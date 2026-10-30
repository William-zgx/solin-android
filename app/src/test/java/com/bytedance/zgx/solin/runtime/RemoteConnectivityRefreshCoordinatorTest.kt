package com.bytedance.zgx.solin.runtime

import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.data.RemoteModelRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import com.bytedance.zgx.solin.testing.FakeSettingsStore
import com.bytedance.zgx.solin.testing.InMemorySecretStore

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConnectivityRefreshCoordinatorTest {
    @Test
    fun probeResultUsesRecordConnectivityWithoutSavingConfigAgain() = runTest {
        val settings = FakeSettingsStore()
        val repository = repository(settings)
        val config = repository.saveConfig(config()).getOrThrow()
        val probe = FakeProbe()
        val coordinator = coordinator(repository, probe)

        val snapshot = coordinator.refresh(config).await()

        assertEquals(1, settings.saveCount)
        assertEquals(RemoteModelConnectivityStatus.Reachable, snapshot?.status)
        assertEquals(RemoteModelConnectivityStatus.Reachable, repository.currentConnectivity(config)?.status)
    }

    @Test
    fun freshConnectivitySkipsProbeUnlessRefreshIsForced() = runTest {
        val repository = repository()
        val config = repository.saveConfig(config()).getOrThrow()
        repository.recordConnectivity(config, RemoteModelConnectivityStatus.Reachable)
        val probe = FakeProbe(RemoteModelConnectivityStatus.Unreachable)
        val coordinator = coordinator(repository, probe)

        assertEquals(RemoteModelConnectivityStatus.Reachable, coordinator.refresh(config).await()?.status)
        assertEquals(0, probe.calls)
        assertEquals(RemoteModelConnectivityStatus.Unreachable, coordinator.refresh(config, force = true).await()?.status)
        assertEquals(1, probe.calls)
    }

    @Test
    fun concurrentRefreshesForOneRevisionShareOneProbe() = runTest {
        val repository = repository()
        val config = repository.saveConfig(config()).getOrThrow()
        val gate = CompletableDeferred<Unit>()
        val probe = FakeProbe(gate = gate)
        val coordinator = coordinator(repository, probe)

        val first = coordinator.refresh(config)
        val second = coordinator.refresh(config)
        assertSame(first, second)
        runCurrent()
        assertEquals(1, probe.calls)

        gate.complete(Unit)
        assertEquals(RemoteModelConnectivityStatus.Reachable, first.await()?.status)
        assertEquals(RemoteModelConnectivityStatus.Reachable, second.await()?.status)
    }

    @Test
    fun oldRevisionCompletionCannotOverwriteNewConfiguration() = runTest {
        val repository = repository()
        val firstConfig = repository.saveConfig(config(modelName = "model-a")).getOrThrow()
        val gate = CompletableDeferred<Unit>()
        val coordinator = coordinator(repository, FakeProbe(gate = gate))

        val oldRefresh = coordinator.refresh(firstConfig)
        runCurrent()
        val secondConfig = repository.saveConfig(firstConfig.copy(modelName = "model-b")).getOrThrow()
        gate.complete(Unit)

        assertNull(oldRefresh.await())
        assertEquals("model-b", repository.loadConfig().modelName)
        assertEquals(secondConfig.profileRevision, repository.loadConfig().profileRevision)
        assertNull(repository.currentConnectivity(firstConfig))
        assertNull(repository.currentConnectivity(secondConfig))
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        repository: RemoteModelRepository,
        probe: RemoteModelConnectivityProbe,
    ): RemoteConnectivityRefreshCoordinator = RemoteConnectivityRefreshCoordinator(
        remoteModelStore = repository,
        probe = probe,
        scope = this,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun repository(settingsStore: FakeSettingsStore = FakeSettingsStore()): RemoteModelRepository {
        var revision = 0
        return RemoteModelRepository(
            settingsStore = settingsStore,
            secretStore = InMemorySecretStore(),
            elapsedRealtimeMillis = { 1_000L },
            revisionFactory = { "revision-${++revision}" },
        )
    }

    private fun config(modelName: String = "model-a"): RemoteModelConfig = RemoteModelConfig(
        baseUrl = "https://api.example.com/v1",
        modelName = modelName,
        apiKey = "secret",
    )

    private class FakeProbe(
        private val status: RemoteModelConnectivityStatus = RemoteModelConnectivityStatus.Reachable,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : RemoteModelConnectivityProbe {
        var calls: Int = 0

        override suspend fun check(config: RemoteModelConfig): RemoteModelConnectivityStatus {
            calls += 1
            gate?.await()
            return status
        }
    }

}
