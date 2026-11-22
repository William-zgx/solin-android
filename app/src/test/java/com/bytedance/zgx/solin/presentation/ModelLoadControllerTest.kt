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
import com.bytedance.zgx.solin.InferenceMode
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
import org.junit.Assert.assertFalse
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

    @Test
    fun importContinuationNeverRunsOnTheCallerThread() {
        val recorder = ImportRecorder()
        val uiState = MutableStateFlow(ChatUiState())
        val controller = importController(recorder, uiState)

        runBlocking { withTimeout(5_000) { controller.launchImport { "/tmp/imported.litertlm" }.join() } }
        awaitChildren()

        assertEquals(
            setOf("dismiss", "state", "saveMode", "clearPending"),
            recorder.threads.keys.toSet()
        )
        recorder.threads.forEach { (step, thread) ->
            assertTrue("$step ran on $thread", thread.startsWith("import-io"))
        }
        assertEquals("/tmp/imported.litertlm", uiState.value.modelPath)
    }

    @Test
    fun importFailureEndsBusyWithoutSuccessSideEffects() {
        val recorder = ImportRecorder()
        val uiState = MutableStateFlow(ChatUiState(isBusy = true))
        val controller = importController(recorder, uiState)

        runBlocking {
            withTimeout(5_000) {
                controller.launchImport { throw java.io.IOException("boom") }.join()
            }
        }

        assertTrue(uiState.value.statusText.contains("导入失败：boom"))
        assertFalse(uiState.value.isBusy)
        assertTrue(recorder.threads.isEmpty())
    }

    @Test
    fun cancellationExceptionFromActiveImportEndsBusyAsFailure() {
        val recorder = ImportRecorder()
        val uiState = MutableStateFlow(ChatUiState(isBusy = true))
        val controller = importController(recorder, uiState)

        val job = controller.launchImport { throw CancellationException("provider future cancelled") }
        runBlocking { withTimeout(5_000) { job.join() } }

        assertFalse(job.isCancelled)
        assertTrue(uiState.value.statusText.contains("导入失败"))
        assertFalse(uiState.value.isBusy)
        assertTrue(recorder.threads.isEmpty())
    }

    @Test
    fun cancelledImportThatCommittedSkipsDismissalStateRefreshSuccessAndLoad() {
        val recorder = ImportRecorder()
        val uiState = MutableStateFlow(ChatUiState(isBusy = true))
        val controller = importController(recorder, uiState)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        val job = controller.launchImport {
            entered.complete(Unit)
            // The repository commit is uncancellable, so a cancelled import can still return a path.
            withContext(NonCancellable) { gate.await() }
            "/tmp/committed.litertlm"
        }
        runBlocking {
            withTimeout(5_000) { entered.await() }
            job.cancel()
            gate.complete(Unit)
            withTimeout(5_000) { job.join() }
        }

        assertTrue(job.isCancelled)
        assertTrue(recorder.threads.isEmpty())
        assertEquals(0, recorder.stateCalls.get())
        assertEquals(null, uiState.value.modelPath)
        assertFalse(uiState.value.statusText == "模型已导入")
        // Documents scope-teardown behaviour: nothing resets busy, which is not a goal for per-job cancellation.
        assertTrue(uiState.value.isBusy)
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

    private class ImportRecorder {
        val threads: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
        val stateCalls = AtomicInteger()
    }

    private fun namedExecutor(prefix: String): java.util.concurrent.ExecutorService {
        val counter = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "$prefix-${counter.incrementAndGet()}")
        }
        executors += executor
        return executor
    }

    private fun importController(
        recorder: ImportRecorder,
        uiState: MutableStateFlow<ChatUiState>
    ): ModelLoadController {
        val ioDispatcher = namedExecutor("import-io").asCoroutineDispatcher()
        val callerDispatcher = namedExecutor("import-caller").asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + callerDispatcher)
        scopes += scope
        val repository = Proxy.newProxyInstance(
            ModelRepositoryFacade::class.java.classLoader,
            arrayOf(ModelRepositoryFacade::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "currentState" -> {
                    recorder.stateCalls.incrementAndGet()
                    recorder.threads["state"] = Thread.currentThread().name
                    ModelSelectionState(DEFAULT_CHAT_MODEL_ID, null, null, emptyList())
                }
                else -> defaultFor(method.returnType)
            }
        } as ModelRepositoryFacade
        val remoteStore = Proxy.newProxyInstance(
            RemoteModelStore::class.java.classLoader,
            arrayOf(RemoteModelStore::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "saveMode" -> {
                    recorder.threads["saveMode"] = Thread.currentThread().name
                    InferenceMode.Local
                }
                else -> defaultFor(method.returnType)
            }
        } as RemoteModelStore
        val firstRun = Proxy.newProxyInstance(
            FirstRunSetupStore::class.java.classLoader,
            arrayOf(FirstRunSetupStore::class.java)
        ) { _, method, _ ->
            if (method.name == "markSetupDismissed") {
                recorder.threads["dismiss"] = Thread.currentThread().name
            }
            defaultFor(method.returnType)
        } as FirstRunSetupStore
        return ModelLoadController(
            modelRepository = repository,
            downloadService = stub<ModelDownloadClient>(),
            runtime = DisabledLiteRtRuntime,
            remoteModelRepository = remoteStore,
            huggingFaceAuthStore = stub<HuggingFaceAuthStore>(),
            firstRunSetupRepository = firstRun,
            generationParametersRepository = stub<GenerationParametersStore>(),
            sessionRepository = stub<SessionStore>(),
            bundledModelInstaller = stub<BundledModelInstaller>(),
            remoteConnectivityRefreshCoordinator = RemoteConnectivityRefreshCoordinator(
                remoteStore,
                stub<RemoteModelConnectivityProbe>(),
                scope,
                ioDispatcher
            ),
            semanticMemoryRuntimeController = null,
            ioDispatcher = ioDispatcher,
            scope = scope,
            uiState = uiState,
            runtimeLock = Mutex(),
            isArm64DeviceProvider = { true },
            skipStartupModelRuntimeWork = true,
            autoInferenceAuthorizationCoordinator = AutoInferenceAuthorizationCoordinator(
                AdaptiveInferenceRollout.Off,
                remoteStore
            ),
            clearPendingRemoteState = { recorder.threads["clearPending"] = Thread.currentThread().name },
            resetRemoteSendDisclosureSuppression = {},
            rebuildMemoryIndex = {}
        )
    }

    private fun controller(
        repository: FakeRepository,
        dispatcher: CoroutineDispatcher,
        uiState: MutableStateFlow<ChatUiState> = MutableStateFlow(ChatUiState())
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
                dispatcher
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
                remoteStore
            ),
            clearPendingRemoteState = {},
            resetRemoteSendDisclosureSuppression = {},
            rebuildMemoryIndex = {}
        )
    }

    private class FakeRepository(private val onVerify: (call: Int) -> Unit = {}) {
        val verifyCalls = AtomicInteger()
        val stateCalls = AtomicInteger()
        val facade: ModelRepositoryFacade = Proxy.newProxyInstance(
            ModelRepositoryFacade::class.java.classLoader,
            arrayOf(ModelRepositoryFacade::class.java)
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
        arrayOf(T::class.java)
    ) { _, method, _ -> defaultFor(method.returnType) } as T
}

private fun defaultFor(type: Class<*>): Any? = when (type) {
    java.lang.Boolean.TYPE -> false
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    else -> null
}
