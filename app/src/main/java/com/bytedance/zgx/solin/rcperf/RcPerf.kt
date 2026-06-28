package com.bytedance.zgx.solin.rcperf

import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.memory.MemoryIndex
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.runtime.LocalChatRuntime
import com.bytedance.zgx.solin.runtime.RuntimeBenchmarkResult

/**
 * Pure, JVM-testable RC perf logic shared by the harness. None of this code reads, deletes, or
 * resets real model files or user data; the harness wires it to the real runtime/repository
 * separately. Everything here is exercised by `testDebugUnitTest`.
 *
 * Hard constraint: throughput (`tokensPerSecond`) is only ever the raw LiteRT decode benchmark
 * value sourced from [RuntimeBenchmarkResult.Available]. It is never derived from character counts,
 * UI text length, or any other estimate. When the benchmark is unavailable, the result is a
 * diagnosable [RcPerfResult.Failure] rather than a fabricated number.
 */

/** GPU fallback outcome, mapped 1:1 onto the values accepted by `verify_perf_baseline.sh`. */
enum class GpuFallbackStatus(val wireValue: String) {
    /** The requested backend loaded directly (GPU succeeded, or CPU was requested to begin with). */
    NotNeeded("not-needed"),

    /** GPU load failed and the CPU fallback load succeeded. */
    CpuFallbackPassed("cpu-fallback-passed"),
    ;

    companion object {
        fun fromWireValue(value: String): GpuFallbackStatus? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Resolves the [GpuFallbackStatus] from the backend that was requested first and the backend that
 * actually loaded. Mirrors the runtime fallback policy in `SolinViewModel` (GPU load failure
 * retried on CPU), but only covers successful loads — a total load failure is surfaced as an
 * [RcPerfResult.Failure] by the harness, never as a passing fallback status.
 */
fun resolveGpuFallbackStatus(
    requestedBackend: BackendChoice,
    loadedBackend: BackendChoice,
): GpuFallbackStatus =
    if (requestedBackend == BackendChoice.GPU && loadedBackend == BackendChoice.CPU) {
        GpuFallbackStatus.CpuFallbackPassed
    } else {
        GpuFallbackStatus.NotNeeded
    }

/** Snapshot of the runtime timing surface captured right after a generation completes. */
data class RuntimeTimingSnapshot(
    val loadMs: Long?,
    val firstTokenMs: Long?,
    val benchmark: RuntimeBenchmarkResult,
)

/**
 * Captures the independent timing surface of [LocalChatRuntime] without touching model files. The
 * load/first-token values are reported even when the decode benchmark is missing, so the harness
 * can attribute a precise failure reason.
 */
fun LocalChatRuntime.captureRcPerfTiming(): RuntimeTimingSnapshot =
    RuntimeTimingSnapshot(
        loadMs = lastLoadMillis(),
        firstTokenMs = lastFirstTokenMillis(),
        benchmark = lastBenchmarkResult(),
    )

/** Harness-measured RC perf metrics. All numeric fields are positive, matching the perf gate. */
data class RcPerfMetrics(
    val modelId: String,
    val backend: BackendChoice,
    val modelLoadMs: Long,
    val firstTokenMs: Long,
    val tokenCount: Int,
    val tokensPerSecond: Double,
    val stopGenerationRecoveryMs: Long,
    val gpuFallbackStatus: GpuFallbackStatus,
    val visionInputMs: Long,
    val memorySearch5kMs: Long,
) {
    init {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(modelLoadMs > 0) { "modelLoadMs must be positive" }
        require(firstTokenMs > 0) { "firstTokenMs must be positive" }
        require(tokenCount > 0) { "tokenCount must be positive" }
        require(tokensPerSecond > 0.0 && !tokensPerSecond.isNaN() && !tokensPerSecond.isInfinite()) {
            "tokensPerSecond must be a positive finite value"
        }
        require(stopGenerationRecoveryMs > 0) { "stopGenerationRecoveryMs must be positive" }
        require(visionInputMs > 0) { "visionInputMs must be positive" }
        require(memorySearch5kMs > 0) { "memorySearch5kMs must be positive" }
    }
}

/** Outcome of an RC perf collection run. */
sealed interface RcPerfResult {
    data class Success(val metrics: RcPerfMetrics) : RcPerfResult
    data class Failure(val reason: String) : RcPerfResult
}

/**
 * Builds an [RcPerfMetrics] from the harness-measured timings plus the LiteRT benchmark. Returns
 * [RcPerfResult.Failure] (never fabricates throughput) when the benchmark is unavailable or any
 * required timing is missing/non-positive.
 */
fun buildRcPerfResult(
    modelId: String,
    requestedBackend: BackendChoice,
    loadedBackend: BackendChoice,
    timing: RuntimeTimingSnapshot,
    stopGenerationRecoveryMs: Long,
    visionInputMs: Long,
    memorySearch5kMs: Long,
): RcPerfResult {
    val benchmark = when (val result = timing.benchmark) {
        is RuntimeBenchmarkResult.Available -> result
        is RuntimeBenchmarkResult.Unavailable ->
            return RcPerfResult.Failure("tokensPerSecond unavailable: ${result.reason}")
    }
    val loadMs = timing.loadMs
        ?: return RcPerfResult.Failure("modelLoadMs unavailable: runtime reported no load time")
    val firstTokenMs = timing.firstTokenMs
        ?: return RcPerfResult.Failure("firstTokenMs unavailable: runtime reported no first token time")
    return runCatching {
        RcPerfMetrics(
            modelId = modelId,
            backend = loadedBackend,
            modelLoadMs = loadMs,
            firstTokenMs = firstTokenMs,
            tokenCount = benchmark.tokenCount,
            tokensPerSecond = benchmark.tokensPerSecond,
            stopGenerationRecoveryMs = stopGenerationRecoveryMs,
            gpuFallbackStatus = resolveGpuFallbackStatus(requestedBackend, loadedBackend),
            visionInputMs = visionInputMs,
            memorySearch5kMs = memorySearch5kMs,
        )
    }.fold(
        onSuccess = { RcPerfResult.Success(it) },
        onFailure = { RcPerfResult.Failure("invalid metric: ${it.message ?: "unknown"}") },
    )
}

/**
 * Stable `key=value` schema for the harness result file consumed by
 * `scripts/collect_rc_perf_from_device.sh`. Mirrors the perf baseline field names so the collector
 * can map them onto the `collect_perf_baseline.sh` environment without translation.
 */
object RcPerfResultFormatter {
    const val SCHEMA = "RcPerfResult/v1"
    const val MAX_VALUE_CHARS = 8_000

    const val KEY_SCHEMA = "rcPerfSchema"
    const val KEY_RESULT_TYPE = "resultType"
    const val KEY_REASON = "reason"
    const val KEY_MODEL_ID = "modelId"
    const val KEY_BACKEND = "backend"
    const val KEY_MODEL_LOAD_MS = "modelLoadMs"
    const val KEY_FIRST_TOKEN_MS = "firstTokenMs"
    const val KEY_TOKEN_COUNT = "tokenCount"
    const val KEY_TOKENS_PER_SECOND = "tokensPerSecond"
    const val KEY_STOP_RECOVERY_MS = "stopGenerationRecoveryMs"
    const val KEY_GPU_FALLBACK_STATUS = "gpuFallbackStatus"
    const val KEY_VISION_INPUT_MS = "visionInputMs"
    const val KEY_MEMORY_SEARCH_5K_MS = "memorySearch5kMs"

    const val RESULT_SUCCESS = "success"
    const val RESULT_FAILED = "failed"

    fun cleanValue(value: String): String =
        value.replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\u0000', ' ')
            .take(MAX_VALUE_CHARS)

    fun format(result: RcPerfResult): String =
        when (result) {
            is RcPerfResult.Success -> formatSuccess(result.metrics)
            is RcPerfResult.Failure -> formatFailure(result.reason)
        }

    private fun formatSuccess(metrics: RcPerfMetrics): String {
        val lines = listOf(
            line(KEY_SCHEMA, SCHEMA),
            line(KEY_RESULT_TYPE, RESULT_SUCCESS),
            line(KEY_MODEL_ID, metrics.modelId),
            line(KEY_BACKEND, metrics.backend.name),
            line(KEY_MODEL_LOAD_MS, metrics.modelLoadMs.toString()),
            line(KEY_FIRST_TOKEN_MS, metrics.firstTokenMs.toString()),
            line(KEY_TOKEN_COUNT, metrics.tokenCount.toString()),
            line(KEY_TOKENS_PER_SECOND, metrics.tokensPerSecond.toString()),
            line(KEY_STOP_RECOVERY_MS, metrics.stopGenerationRecoveryMs.toString()),
            line(KEY_GPU_FALLBACK_STATUS, metrics.gpuFallbackStatus.wireValue),
            line(KEY_VISION_INPUT_MS, metrics.visionInputMs.toString()),
            line(KEY_MEMORY_SEARCH_5K_MS, metrics.memorySearch5kMs.toString()),
        )
        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    private fun formatFailure(reason: String): String {
        val lines = listOf(
            line(KEY_SCHEMA, SCHEMA),
            line(KEY_RESULT_TYPE, RESULT_FAILED),
            line(KEY_REASON, reason),
        )
        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    private fun line(key: String, value: String): String = "$key=${cleanValue(value)}"

    /** Parses a formatted result back into an [RcPerfResult]; used for round-trip testing. */
    fun parse(text: String): RcPerfResult {
        val fields = text.lineSequence()
            .mapNotNull { rawLine ->
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val separator = trimmed.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                trimmed.substring(0, separator) to trimmed.substring(separator + 1)
            }
            .toMap()
        return when (fields[KEY_RESULT_TYPE]) {
            RESULT_SUCCESS -> parseSuccess(fields)
            RESULT_FAILED -> RcPerfResult.Failure(fields[KEY_REASON].orEmpty())
            else -> RcPerfResult.Failure("unrecognized resultType: ${fields[KEY_RESULT_TYPE]}")
        }
    }

    private fun parseSuccess(fields: Map<String, String>): RcPerfResult {
        return runCatching {
            RcPerfMetrics(
                modelId = fields.getValue(KEY_MODEL_ID),
                backend = BackendChoice.valueOf(fields.getValue(KEY_BACKEND)),
                modelLoadMs = fields.getValue(KEY_MODEL_LOAD_MS).toLong(),
                firstTokenMs = fields.getValue(KEY_FIRST_TOKEN_MS).toLong(),
                tokenCount = fields.getValue(KEY_TOKEN_COUNT).toInt(),
                tokensPerSecond = fields.getValue(KEY_TOKENS_PER_SECOND).toDouble(),
                stopGenerationRecoveryMs = fields.getValue(KEY_STOP_RECOVERY_MS).toLong(),
                gpuFallbackStatus = GpuFallbackStatus.fromWireValue(fields.getValue(KEY_GPU_FALLBACK_STATUS))
                    ?: error("unknown gpuFallbackStatus"),
                visionInputMs = fields.getValue(KEY_VISION_INPUT_MS).toLong(),
                memorySearch5kMs = fields.getValue(KEY_MEMORY_SEARCH_5K_MS).toLong(),
            )
        }.fold(
            onSuccess = { RcPerfResult.Success(it) },
            onFailure = { RcPerfResult.Failure("malformed success record: ${it.message ?: "unknown"}") },
        )
    }
}

/**
 * Builds and times the synthetic memory 5k search on isolated data. Uses a fresh
 * [MemoryRepository] with default NoOp stores and the lexical [HashingEmbeddingRuntime], so it
 * never reads or mutates the user's real memory database, and never calls `clear()` on a live index.
 */
object RcPerfSyntheticMemory {
    const val DEFAULT_RECORD_COUNT = 5_000
    const val QUERY = "synthetic memory benchmark record lookup"

    data class Measurement(val elapsedMs: Long, val recordCount: Int, val hitCount: Int)

    /** Generates deterministic synthetic records with overlapping tokens for the search query. */
    fun syntheticRecords(count: Int = DEFAULT_RECORD_COUNT): List<Pair<String, String>> {
        require(count > 0) { "synthetic record count must be positive" }
        return (0 until count).map { i ->
            "rc-perf-synthetic-$i" to
                "synthetic memory benchmark record $i lookup token sample alpha beta gamma delta entry"
        }
    }

    /**
     * Indexes [recordCount] synthetic records into a fresh isolated index, then measures a single
     * real [MemoryIndex.search] call. Returns the elapsed milliseconds plus diagnostics.
     */
    fun measure(
        recordCount: Int = DEFAULT_RECORD_COUNT,
        nanoClock: () -> Long = System::nanoTime,
        indexFactory: () -> MemoryIndex = { MemoryRepository() },
    ): Measurement {
        val index = indexFactory()
        syntheticRecords(recordCount).forEach { (id, text) -> index.index(id, text) }
        val startedAtNanos = nanoClock()
        val hits = index.search(QUERY, topK = 10)
        val elapsedMs = ((nanoClock() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        return Measurement(elapsedMs = elapsedMs, recordCount = recordCount, hitCount = hits.size)
    }
}
