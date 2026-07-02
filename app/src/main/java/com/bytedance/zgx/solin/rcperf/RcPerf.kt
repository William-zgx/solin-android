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
    val zvecMemoryIndex50kMs: Long,
    val zvecMemorySearch50kMs: Long,
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
        require(zvecMemoryIndex50kMs > 0) { "zvecMemoryIndex50kMs must be positive" }
        require(zvecMemorySearch50kMs > 0) { "zvecMemorySearch50kMs must be positive" }
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
    zvecMemoryIndex50kMs: Long,
    zvecMemorySearch50kMs: Long,
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
            zvecMemoryIndex50kMs = zvecMemoryIndex50kMs,
            zvecMemorySearch50kMs = zvecMemorySearch50kMs,
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
    private const val MAX_VALUE_CHARS = 8_000
    private const val RESULT_SUCCESS = "success"
    private const val RESULT_FAILED = "failed"
    private const val KEY_SCHEMA = "rcPerfSchema"
    private const val KEY_RESULT_TYPE = "resultType"
    private const val KEY_REASON = "reason"

    private data class MetricField(
        val key: String,
        val value: (RcPerfMetrics) -> String,
    )

    private val successFields = listOf(
        MetricField("modelId") { metrics -> metrics.modelId },
        MetricField("backend") { metrics -> metrics.backend.name },
        MetricField("modelLoadMs") { metrics -> metrics.modelLoadMs.toString() },
        MetricField("firstTokenMs") { metrics -> metrics.firstTokenMs.toString() },
        MetricField("tokenCount") { metrics -> metrics.tokenCount.toString() },
        MetricField("tokensPerSecond") { metrics -> metrics.tokensPerSecond.toString() },
        MetricField("stopGenerationRecoveryMs") { metrics -> metrics.stopGenerationRecoveryMs.toString() },
        MetricField("gpuFallbackStatus") { metrics -> metrics.gpuFallbackStatus.wireValue },
        MetricField("visionInputMs") { metrics -> metrics.visionInputMs.toString() },
        MetricField("memorySearch5kMs") { metrics -> metrics.memorySearch5kMs.toString() },
        MetricField("zvecMemoryIndex50kMs") { metrics -> metrics.zvecMemoryIndex50kMs.toString() },
        MetricField("zvecMemorySearch50kMs") { metrics -> metrics.zvecMemorySearch50kMs.toString() },
    )

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
        ) + successFields.map { field -> line(field.key, field.value(metrics)) }
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
                modelId = fields.getValue("modelId"),
                backend = BackendChoice.valueOf(fields.getValue("backend")),
                modelLoadMs = fields.getValue("modelLoadMs").toLong(),
                firstTokenMs = fields.getValue("firstTokenMs").toLong(),
                tokenCount = fields.getValue("tokenCount").toInt(),
                tokensPerSecond = fields.getValue("tokensPerSecond").toDouble(),
                stopGenerationRecoveryMs = fields.getValue("stopGenerationRecoveryMs").toLong(),
                gpuFallbackStatus = GpuFallbackStatus.fromWireValue(fields.getValue("gpuFallbackStatus"))
                    ?: error("unknown gpuFallbackStatus"),
                visionInputMs = fields.getValue("visionInputMs").toLong(),
                memorySearch5kMs = fields.getValue("memorySearch5kMs").toLong(),
                zvecMemoryIndex50kMs = fields.getValue("zvecMemoryIndex50kMs").toLong(),
                zvecMemorySearch50kMs = fields.getValue("zvecMemorySearch50kMs").toLong(),
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
    const val LARGE_RECORD_COUNT = 50_000
    const val QUERY = "synthetic memory benchmark record lookup"

    data class Measurement(
        val indexMs: Long,
        val searchMs: Long,
        val recordCount: Int,
        val hitCount: Int,
    )

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
        val records = syntheticRecords(recordCount)
        val indexStartedAtNanos = nanoClock()
        records.forEach { (id, text) -> index.index(id, text) }
        val indexMs = ((nanoClock() - indexStartedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        val startedAtNanos = nanoClock()
        val hits = index.search(QUERY, topK = 10)
        val searchMs = ((nanoClock() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        return Measurement(indexMs = indexMs, searchMs = searchMs, recordCount = recordCount, hitCount = hits.size)
    }
}
