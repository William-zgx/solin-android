package com.bytedance.zgx.solin.rcperf

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.BuildConfig
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.LocalImageAttachment
import com.bytedance.zgx.solin.R
import com.bytedance.zgx.solin.data.ModelRepository
import com.bytedance.zgx.solin.runtime.LocalModelRequest
import com.bytedance.zgx.solin.runtime.LocalModelRuntimeCapabilities
import com.bytedance.zgx.solin.runtime.RealLiteRtRuntime
import com.bytedance.zgx.solin.runtime.RuntimeBenchmarkResult
import com.bytedance.zgx.solin.runtime.toRuntimeBenchmarkResult
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.benchmark
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Controlled RC performance harness entry point. Present only in the [rcPerfRelease] variant; the
 * production release never declares it (manifest lives in `app/src/rcPerfRelease/`) and the entry is
 * additionally gated behind [BuildConfig.RC_PERF_ENABLED].
 *
 * It exercises a real local model path already installed on the device and reports measured
 * metrics. By default it uses the active model; collectors may pass a specific installed model id
 * for a release profile without changing the user's active selection. It is strictly read-only with
 * respect to user data and model files:
 *  - it never calls `pm clear`, `clearAllTables`, `deleteInstalledModel`, or deletes model dirs;
 *  - it only reads installed model paths via [ModelRepository.currentState];
 *  - the synthetic memory 5k search runs on an isolated in-memory index, never the user's data.
 *
 * Throughput (`tokensPerSecond`) is only ever the raw LiteRT decode benchmark value; when the
 * benchmark is unavailable the run fails with a diagnosable reason instead of estimating.
 */
class RcPerfHarnessService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestIntent = intent ?: Intent()
        val appContext = applicationContext
        val requestId = requestIntent.getStringExtra(EXTRA_REQUEST_ID)
            ?.takeIf { it.isNotBlank() }
            ?: "rc-perf-${System.currentTimeMillis()}"
        startForegroundCompat(buildNotification())
        serviceScope.launch {
            try {
                logProgress(requestId, "start")
                val result = runCatching {
                    runHarness(appContext, requestIntent, requestId)
                }.getOrElse { throwable ->
                    RcPerfResult.Failure(failureReason(throwable))
                }
                writeResult(appContext, requestId, result)
            } catch (throwable: Throwable) {
                Log.e(LOG_TAG, "rcPerfUnhandled requestId=${cleanRequestToken(requestId)}", throwable)
                runCatching {
                    writeResult(
                        appContext = appContext,
                        requestId = requestId,
                        result = RcPerfResult.Failure(failureReason(throwable)),
                    )
                }
            } finally {
                stopForegroundCompat()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Solin RC 性能采集",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Solin RC 版本性能采集运行中"
            },
        )
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let { intent ->
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag(),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Solin正在采集 RC 性能")
            .setContentText("正在运行本地模型性能采集")
            .setOngoing(true)
            .setShowWhen(false)
            .apply {
                pendingIntent?.let(::setContentIntent)
            }
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun pendingIntentImmutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private suspend fun runHarness(appContext: Context, intent: Intent, requestId: String): RcPerfResult {
        if (!BuildConfig.RC_PERF_ENABLED) {
            return RcPerfResult.Failure("rc perf harness disabled: BuildConfig.RC_PERF_ENABLED is false")
        }
        val requestedBackend = intent.getStringExtra(EXTRA_BACKEND)
            ?.let { runCatching { BackendChoice.valueOf(it.uppercase()) }.getOrNull() }
            ?: BackendChoice.GPU
        val requestedModelId = intent.getStringExtra(EXTRA_MODEL_ID)?.takeIf { it.isNotBlank() }

        logProgress(requestId, "model-state")
        val selection = ModelRepository(appContext).currentState()
        val selectedModel = if (requestedModelId != null) {
            selection.installedModels.firstOrNull { it.id == requestedModelId }
                ?: return RcPerfResult.Failure("requested model not installed on device: $requestedModelId")
        } else {
            val activeId = selection.activeInstalledModelId
                ?: return RcPerfResult.Failure("no active installed model id")
            selection.installedModels.firstOrNull { it.id == activeId }
                ?: return RcPerfResult.Failure("active installed model summary missing: $activeId")
        }
        val modelId = selectedModel.id
        val modelPath = selectedModel.path.takeIf { it.isNotBlank() }
            ?: return RcPerfResult.Failure("installed model path missing on device: $modelId")
        if (!File(modelPath).exists()) {
            return RcPerfResult.Failure("selected model path missing on device: $modelPath")
        }
        val runtimeCapabilities = LocalModelRuntimeCapabilities.fromProfile(selectedModel.capabilityProfile)
        if (!runtimeCapabilities.supportsVisionInput) {
            return RcPerfResult.Failure("vision unsupported: selected model $modelId has no vision support")
        }

        val parameters = GenerationParameters()
        val runtime = RealLiteRtRuntime(appContext.cacheDir)
        return try {
            runtime.configureModelCapabilities(runtimeCapabilities)
            logProgress(requestId, "load-start-${requestedBackend.name.lowercase()}")
            val loadedBackend = loadWithFallback(runtime, modelPath, requestedBackend, parameters)
            logProgress(requestId, "load-done-${loadedBackend.name.lowercase()}")

            // 1) Real text generation: drives load/first-token/benchmark timing.
            logProgress(requestId, "text-start")
            collectGeneration(
                runtime = runtime,
                prompt = TEXT_PROMPT,
                parameters = parameters,
                recreate = false,
                timeoutMs = TEXT_GENERATION_TIMEOUT_MS,
                timeoutStep = "text generation",
                maxInputTokens = runtimeCapabilities.contextWindowTokens,
            )
            logProgress(requestId, "text-done")
            val textTiming = runtime.captureRcPerfTiming()

            // 2) Vision input: synthetic tiny image through the real vision input chain.
            logProgress(requestId, "vision-start")
            val visionInputMs = measureVisionInput(runtime, parameters, runtimeCapabilities)
                ?: return RcPerfResult.Failure("visionInputMs unavailable: no first token from vision request")
            logProgress(requestId, "vision-done")

            // 3) Stop generation recovery: trigger a long generation and stop it.
            logProgress(requestId, "stop-start")
            val stopRecoveryMs = measureStopRecovery(runtime, parameters, runtimeCapabilities)
            logProgress(requestId, "stop-done")

            // 4) Synthetic memory searches on isolated data (never user memory).
            logProgress(requestId, "memory-5k-start")
            val memory5k = RcPerfSyntheticMemory.measure()
            val memorySearch5kMs = memory5k.searchMs.coerceAtLeast(1L)
            logProgress(requestId, "memory-5k-done")
            logProgress(requestId, "memory-50k-start")
            val memory50k = RcPerfSyntheticMemory.measure(recordCount = RcPerfSyntheticMemory.LARGE_RECORD_COUNT)
            val zvecMemoryIndex50kMs = memory50k.indexMs.coerceAtLeast(1L)
            val zvecMemorySearch50kMs = memory50k.searchMs.coerceAtLeast(1L)
            logProgress(requestId, "memory-50k-done")
            val benchmarkResult = resolveDecodeBenchmark(
                conversationBenchmark = textTiming.benchmark,
                modelPath = modelPath,
                loadedBackend = loadedBackend,
                cacheDir = appContext.cacheDir,
                requestId = requestId,
                closeRuntime = { runtime.close() },
            )

            buildRcPerfResult(
                modelId = modelId,
                requestedBackend = requestedBackend,
                loadedBackend = loadedBackend,
                timing = textTiming.copy(benchmark = benchmarkResult),
                stopGenerationRecoveryMs = stopRecoveryMs.coerceAtLeast(1L),
                visionInputMs = visionInputMs.coerceAtLeast(1L),
                memorySearch5kMs = memorySearch5kMs,
                zvecMemoryIndex50kMs = zvecMemoryIndex50kMs,
                zvecMemorySearch50kMs = zvecMemorySearch50kMs,
            )
        } catch (throwable: Throwable) {
            RcPerfResult.Failure(failureReason(throwable))
        } finally {
            runCatching { runtime.close() }
        }
    }

    /** Loads on [requested]; on failure retries CPU (mirrors runtime fallback). Returns loaded backend. */
    private fun loadWithFallback(
        runtime: RealLiteRtRuntime,
        modelPath: String,
        requested: BackendChoice,
        parameters: GenerationParameters,
    ): BackendChoice {
        val firstAttempt = runCatching {
            runtime.load(modelPath, requested, history = emptyList(), parameters = parameters)
        }
        if (firstAttempt.isSuccess) return requested
        if (requested == BackendChoice.GPU) {
            runtime.load(modelPath, BackendChoice.CPU, history = emptyList(), parameters = parameters)
            return BackendChoice.CPU
        }
        throw firstAttempt.exceptionOrNull() ?: IllegalStateException("model load failed")
    }

    private suspend fun collectGeneration(
        runtime: RealLiteRtRuntime,
        prompt: String,
        parameters: GenerationParameters,
        recreate: Boolean,
        timeoutMs: Long,
        timeoutStep: String,
        maxInputTokens: Int,
        imageAttachments: List<LocalImageAttachment> = emptyList(),
    ) {
        try {
            withTimeout(timeoutMs) {
                if (recreate) {
                    runtime.recreateConversationForSend(
                        history = emptyList(),
                        prompt = prompt,
                        parameters = parameters,
                        maxInputTokens = maxInputTokens,
                    )
                }
                runtime.send(LocalModelRequest(prompt = prompt, imageAttachments = imageAttachments)).collect { }
            }
        } catch (timeout: TimeoutCancellationException) {
            val stopFailure = runtime.stopWithResult().exceptionOrNull()
            throw HarnessTimeoutException(timeoutStep, timeoutMs, stopFailure)
        }
    }

    private suspend fun measureVisionInput(
        runtime: RealLiteRtRuntime,
        parameters: GenerationParameters,
        capabilities: LocalModelRuntimeCapabilities,
    ): Long? {
        collectGeneration(
            runtime = runtime,
            prompt = VISION_PROMPT,
            parameters = parameters,
            recreate = true,
            timeoutMs = VISION_GENERATION_TIMEOUT_MS,
            timeoutStep = "vision generation",
            maxInputTokens = capabilities.contextWindowTokens,
            imageAttachments = listOf(syntheticImageAttachment()),
        )
        return runtime.lastFirstTokenMillis()
    }

    private suspend fun measureStopRecovery(
        runtime: RealLiteRtRuntime,
        parameters: GenerationParameters,
        capabilities: LocalModelRuntimeCapabilities,
    ): Long {
        val generationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstChunk = CompletableDeferred<Unit>()
        val generationFailure = CompletableDeferred<Throwable?>()
        val job = generationScope.launch {
            try {
                runtime.recreateConversationForSend(
                    history = emptyList(),
                    prompt = STOP_PROMPT,
                    parameters = parameters,
                    maxInputTokens = capabilities.contextWindowTokens,
                )
                runtime.send(LocalModelRequest(prompt = STOP_PROMPT)).collect {
                    if (!firstChunk.isCompleted) firstChunk.complete(Unit)
                }
                if (!firstChunk.isCompleted) {
                    firstChunk.completeExceptionally(
                        IllegalStateException("stop generation completed without first chunk"),
                    )
                }
                generationFailure.complete(null)
            } catch (throwable: Throwable) {
                generationFailure.complete(throwable)
                if (!firstChunk.isCompleted) firstChunk.completeExceptionally(throwable)
            }
        }
        try {
            val firstChunkArrived = withTimeoutOrNull(STOP_FIRST_CHUNK_TIMEOUT_MS) {
                firstChunk.await()
                true
            } == true
            if (!firstChunkArrived) {
                stopAndCancel(runtime, job)
                throw HarnessTimeoutException("stop generation first chunk", STOP_FIRST_CHUNK_TIMEOUT_MS)
            }

            if (job.isCompleted) {
                generationFailure.await()?.let { throw it }
                throw IllegalStateException("stop generation completed before stop request")
            }

            val startedAtNanos = System.nanoTime()
            runtime.stopWithResult().getOrElse { throwable ->
                throw IllegalStateException(
                    "stop generation cancel failed: ${throwable.message ?: throwable::class.java.simpleName}",
                    throwable,
                )
            }
            val stopped = withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) {
                job.join()
                true
            } == true
            if (!stopped) {
                stopAndCancel(runtime, job)
                throw HarnessTimeoutException("stop generation join", STOP_JOIN_TIMEOUT_MS)
            }
            return ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        } finally {
            if (job.isActive) {
                stopAndCancel(runtime, job)
            }
            generationScope.cancel()
        }
    }

    private suspend fun stopAndCancel(runtime: RealLiteRtRuntime, job: kotlinx.coroutines.Job) {
        runtime.stopWithResult()
        job.cancel()
        withTimeoutOrNull(JOB_CLEANUP_TIMEOUT_MS) {
            job.join()
        }
    }

    private fun failureReason(throwable: Throwable): String =
        when (throwable) {
            is HarnessTimeoutException -> throwable.message ?: "harness timed out"
            else -> "harness error: ${throwable.javaClass.simpleName}:${throwable.message.orEmpty()}"
        }

    @OptIn(ExperimentalApi::class)
    private fun resolveDecodeBenchmark(
        conversationBenchmark: RuntimeBenchmarkResult,
        modelPath: String,
        loadedBackend: BackendChoice,
        cacheDir: File,
        requestId: String,
        closeRuntime: () -> Unit,
    ): RuntimeBenchmarkResult {
        if (conversationBenchmark is RuntimeBenchmarkResult.Available) return conversationBenchmark
        logProgress(requestId, "benchmark-start")
        closeRuntime()
        val standalone = runCatching {
            benchmark(
                modelPath,
                loadedBackend.toLiteRtBenchmarkBackend(),
                STANDALONE_BENCHMARK_PREFILL_TOKENS,
                STANDALONE_BENCHMARK_DECODE_TOKENS,
                cacheDir.absolutePath,
            ).toRuntimeBenchmarkResult()
        }.getOrElse { throwable ->
            RuntimeBenchmarkResult.Unavailable(
                "standalone benchmark failed: ${throwable.message ?: throwable::class.java.simpleName}",
            )
        }
        if (standalone is RuntimeBenchmarkResult.Available) {
            logProgress(requestId, "benchmark-done")
            return standalone
        }
        val conversationReason = (conversationBenchmark as? RuntimeBenchmarkResult.Unavailable)?.reason
            ?: "conversation benchmark unavailable"
        val standaloneReason = (standalone as RuntimeBenchmarkResult.Unavailable).reason
        return RuntimeBenchmarkResult.Unavailable("$conversationReason; $standaloneReason")
    }

    private fun BackendChoice.toLiteRtBenchmarkBackend(): Backend =
        when (this) {
            BackendChoice.CPU -> Backend.CPU()
            BackendChoice.GPU -> Backend.GPU()
        }

    private fun writeResult(appContext: Context, requestId: String, result: RcPerfResult) {
        val text = RcPerfResultFormatter.format(result)
        val token = cleanRequestToken(requestId)
        val target = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        runCatching {
            File(target, "$RESULT_FILE_PREFIX$token.properties").writeText(text)
            File(target, LEGACY_RESULT_FILE_NAME).writeText(text)
        }
        // Also emit to logcat so the device collector can scrape results without file pull.
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        Log.i(LOG_TAG, "rcPerfResultBase64 requestId=$token payload=$encoded")
    }

    private fun logProgress(requestId: String, stage: String) {
        Log.i(LOG_TAG, "rcPerfProgress requestId=${cleanRequestToken(requestId)} stage=$stage")
    }

    private fun cleanRequestToken(requestId: String): String {
        return RcPerfResultFormatter.cleanValue(requestId)
            .map { ch -> if (ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.') ch else '_' }
            .joinToString(separator = "")
            .take(120)
            .ifBlank { "missing" }
    }

    /** Minimal valid 1x1 PNG so the synthetic image traverses the real decode/vision path. */
    private fun syntheticImageAttachment(): LocalImageAttachment =
        LocalImageAttachment(mimeType = "image/png", bytes = ONE_BY_ONE_PNG)

    private companion object {
        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_MODEL_ID = "modelId"
        const val RESULT_FILE_PREFIX = "rc_perf_result_"
        const val LEGACY_RESULT_FILE_NAME = "rc_perf_result.properties"
        const val LOG_TAG = "RcPerfHarness"
        const val TEXT_PROMPT = "用一句话介绍今天的天气。"
        const val VISION_PROMPT = "用一句话描述这张图片。"
        const val STOP_PROMPT = "请写一篇较长的散文，至少包含十个自然段，描述四季的变化。"
        const val TEXT_GENERATION_TIMEOUT_MS = 180_000L
        const val VISION_GENERATION_TIMEOUT_MS = 180_000L
        const val STOP_FIRST_CHUNK_TIMEOUT_MS = 120_000L
        const val STOP_JOIN_TIMEOUT_MS = 30_000L
        const val JOB_CLEANUP_TIMEOUT_MS = 5_000L
        const val STANDALONE_BENCHMARK_PREFILL_TOKENS = 256
        const val STANDALONE_BENCHMARK_DECODE_TOKENS = 256
        const val CHANNEL_ID = "solin_rc_perf_collection"
        const val NOTIFICATION_ID = 41_205

        // 1x1 transparent PNG.
        val ONE_BY_ONE_PNG: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(), 0xC0.toByte(),
            0xF0.toByte(),
            0x1F, 0x00, 0x05, 0x00, 0x01, 0xFF.toByte(), 0x89.toByte(), 0x99.toByte(),
            0x3D, 0x1D, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45,
            0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }

    private class HarnessTimeoutException(
        step: String,
        timeoutMs: Long,
        stopFailure: Throwable? = null,
    ) : RuntimeException(
        "$step timed out after ${timeoutMs}ms" + stopFailure?.let {
            "; stop failed: ${it.message ?: it::class.java.simpleName}"
        }.orEmpty(),
        stopFailure,
    )
}
