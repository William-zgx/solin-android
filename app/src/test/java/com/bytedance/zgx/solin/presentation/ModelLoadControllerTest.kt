package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AdaptiveInferenceRollout
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.DEFAULT_CHAT_MODEL_ID
import com.bytedance.zgx.solin.data.BundledModelInstaller
import com.bytedance.zgx.solin.data.FirstRunSetupStore
import com.bytedance.zgx.solin.data.GenerationParametersStore
import com.bytedance.zgx.solin.data.HuggingFaceAuthStore
import com.bytedance.zgx.solin.data.ModelRepositoryFacade
import com.bytedance.zgx.solin.data.ModelSelectionState
import com.bytedance.zgx.solin.data.RemoteModelStore
import com.bytedance.zgx.solin.data.SessionStore
import com.bytedance.zgx.solin.download.ModelDownloadClient
import com.bytedance.zgx.solin.runtime.DisabledLiteRtRuntime
import com.bytedance.zgx.solin.runtime.RemoteConnectivityRefreshCoordinator
import com.bytedance.zgx.solin.runtime.RemoteModelConnectivityProbe
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelLoadControllerTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val executors = mutableListOf<java.util.concurrent.ExecutorService>()

    @After
    fun tearDown() {
        scopes.forEach { it.coroutineContext[Job]?.cancel() }
        executors.forEach { it.shutdownNow() }
    }

    @Test
    fun closeBeforeQueuedStartupVerificationStartsPreventsRepositoryEntry() {
        val scheduler = TestCoroutineScheduler()
        val repository = FakeRepository()
        val controller = controller(repository, StandardTestDispatcher(scheduler))

        controller.verifyLegacyModelsOnStartup(skipModelRuntimeWork = true)
        controller.close()
        scheduler.advanceUntilIdle()

        assertEquals(0, repository.verifyCalls.get())
        assertEquals(0, repository.stateCalls.get())
    }

    @Test
    fun closeWhileVerificationIsBlockedPreventsStateReadAndUpdate() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val repository = FakeRepository(onVerify = {
            entered.countDown()
            gate.await(5, TimeUnit.SECONDS)
        })
        val uiState = MutableStateFlow(ChatUiState())
        val controller = controller(repository, singleThread(), uiState)

        controller.verifyLegacyModelsOnStartup(skipModelRuntimeWork = true)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        controller.close()
        gate.countDown()
        awaitChildren()

        assertEquals(1, repository.verifyCalls.get())
        assertEquals(0, repository.stateCalls.get())
        assertTrue(uiState.value.installedModels.isEmpty())
    }

    @Test
    fun closeCannotMissVerificationThatStartsInlineOnLaunch() {
        lateinit var controller: ModelLoadController
        val repository = FakeRepository(onVerify = { controller.close() })
        controller = controller(repository, Dispatchers.Unconfined)

        controller.verifyLegacyModelsOnStartup(skipModelRuntimeWork = true)
        awaitChildren()

        assertEquals(1, repository.verifyCalls.get())
        assertEquals(0, repository.stateCalls.get())
    }

    @Test
    fun freshControllerVerifiesAfterOldControllerIsClosed() {
        val oldRepository = FakeRepository()
        val old = controller(oldRepository, singleThread())
        old.close()

        val freshRepository = FakeRepository()
        val fresh = controller(freshRepository, singleThread())
        fresh.verifyLegacyModelsOnStartup(skipModelRuntimeWork = true)
        awaitChildren()

        assertEquals(0, oldRepository.verifyCalls.get())
        assertEquals(1, freshRepository.verifyCalls.get())
        assertEquals(1, freshRepository.stateCalls.get())
    }

    @Test
    fun repeatedStartupVerificationCancelsThePreviousJob() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val repository = FakeRepository(onVerify = {
            if (it == 1) {
                entered.countDown()
                gate.await(5, TimeUnit.SECONDS)
            }
        })
        val controller = controller(repository, singleThread(threads = 2))

        controller.verifyLegacyModelsOnStartup(skipModelRuntimeWork = true)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        controller.verifyLegacyModelsOnStartup(skipModelRuntimeWork = true)
        gate.countDown()
        awaitChildren()

        assertEquals(2, repository.verifyCalls.get())
        assertEquals(1, repository.stateCalls.get())
    }

    private fun awaitChildren() = runBlocking {
        withTimeout(5_000) {
            scopes.forEach { scope -> scope.coroutineContext[Job]!!.children.toList().joinAll() }
        }
    }

    private fun singleThread(threads: Int = 1): CoroutineDispatcher {
        val executor = Executors.newFixedThreadPool(threads)
        executors += executor
        return executor.asCoroutineDispatcher()
    }

    private fun controller(
        repository: FakeRepository,
        dispatcher: CoroutineDispatcher,
        uiState: MutableStateFlow<ChatUiState> = MutableStateFlow(ChatUiState()),
    ): ModelLoadController {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        scopes += scope
        val remoteStore = stub<RemoteModelStore>()
        return ModelLoadController(
            modelRepository = repository.facade,
            downloadService = stub<ModelDownloadClient>(),
            runtime = DisabledLiteRtRuntime,
            remoteModelRepository = remoteStore,
            huggingFaceAuthStore = stub<HuggingFaceAuthStore>(),
            firstRunSetupRepository = stub<FirstRunSetupStore>(),
            generationParametersRepository = stub<GenerationParametersStore>(),
            sessionRepository = stub<SessionStore>(),
            bundledModelInstaller = stub<BundledModelInstaller>(),
            remoteConnectivityRefreshCoordinator = RemoteConnectivityRefreshCoordinator(
                remoteStore,
                stub<RemoteModelConnectivityProbe>(),
                scope,
                dispatcher,
            ),
            semanticMemoryRuntimeController = null,
            ioDispatcher = dispatcher,
            scope = scope,
            uiState = uiState,
            runtimeLock = Mutex(),
            isArm64DeviceProvider = { true },
            skipStartupModelRuntimeWork = true,
            autoInferenceAuthorizationCoordinator = AutoInferenceAuthorizationCoordinator(
                AdaptiveInferenceRollout.Off,
                remoteStore,
            ),
            clearPendingRemoteState = {},
            resetRemoteSendDisclosureSuppression = {},
            rebuildMemoryIndex = {},
        )
    }

    private class FakeRepository(private val onVerify: (call: Int) -> Unit = {}) {
        val verifyCalls = AtomicInteger()
        val stateCalls = AtomicInteger()
        val facade: ModelRepositoryFacade = Proxy.newProxyInstance(
            ModelRepositoryFacade::class.java.classLoader,
            arrayOf(ModelRepositoryFacade::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "verifyLegacyRecommendedModels" -> {
                    onVerify(verifyCalls.incrementAndGet())
                    true
                }
                "currentState" -> {
                    stateCalls.incrementAndGet()
                    ModelSelectionState(DEFAULT_CHAT_MODEL_ID, null, null, emptyList())
                }
                else -> defaultFor(method.returnType)
            }
        } as ModelRepositoryFacade
    }

    private inline fun <reified T : Any> stub(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ -> defaultFor(method.returnType) } as T
}

private fun defaultFor(type: Class<*>): Any? = when (type) {
    java.lang.Boolean.TYPE -> false
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    else -> null
}
