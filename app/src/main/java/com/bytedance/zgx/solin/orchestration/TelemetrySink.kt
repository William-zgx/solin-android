package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.resource.ThermalPressure
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Wave 1 telemetry surface for agent-loop observability.
 *
 * Design notes:
 * - [MetricSample] is a sealed hierarchy so new variants can be added without breaking `when`.
 * - Each sample carries [occurredAtMillis] (wall clock, [System.currentTimeMillis]) and an optional
 *   [runId] to let sinks correlate events within a single agent turn / orchestration run.
 * - [TelemetrySink] is intentionally narrow: [record] a sample, [snapshot] a point-in-time view.
 *   Statistical aggregation (p50/p95 with proper selection algorithms) is explicitly deferred —
 *   Wave 1 returns raw bounded buffers plus counts; richer stats land in a later wave.
 * - Two implementations are provided out of the box: [InMemoryTelemetrySink] for real use (thread
 *   safe, bounded memory) and [NoOpTelemetrySink] for tests / disabled builds.
 */

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

/**
 * Which model-serving family produced a generation. This is about transport/runtime class (local
 * LiteRT vs remote OpenAI-compatible vs remote custom endpoint), NOT about CPU-vs-GPU — that
 * axis lives in [com.bytedance.zgx.solin.BackendChoice] (different package, different dimension;
 * the distinct name avoids import collisions).
 */
enum class ServingBackend {
    LocalLiteRt,
    RemoteOpenAI,
    RemoteCustom,
}

/** Well-known monotonic counters tracked by the orchestration layer. */
enum class TelemetryCounter {
    ToolCalls,
    CompactionTriggered,
    SafetyBlocks,
    AskUserQuestions,
    ModelFallback,
    CacheHits,
    /** Incremented for each [SolinEvent.Agent.TextDelta] chunk received (count only; no text). */
    TextTokens,
    /** Incremented when a tool invocation terminates in a non-success terminal state. */
    ToolFailures,
    /** Incremented when an agent run fails terminally. */
    RunFailures,
    /** Incremented for each [SolinEvent.Audit] event (count only; no payload). */
    AuditEvents,
    /** Incremented for each [SolinEvent.Safety] event (count only; no payload). */
    SafetyEvents,
}

// ---------------------------------------------------------------------------
// Samples
// ---------------------------------------------------------------------------

/**
 * A single telemetry observation. Sealed so consumers can `when` over variants exhaustively.
 *
 * @property occurredAtMillis Epoch millis when the event occurred.
 * @property runId Optional id of the agent run this sample belongs to.
 */
sealed class MetricSample(
    open val occurredAtMillis: Long = System.currentTimeMillis(),
    open val runId: String? = null,
) {
    /** A generation request just completed (streaming or blocking). */
    data class GenerationComplete(
        val ttftMs: Long,
        val totalMs: Long,
        val inputTokens: Int,
        val outputTokens: Int,
        val thinkingTokens: Int = 0,
        val tps: Double,
        val backend: ServingBackend,
        val modelId: String,
        override val occurredAtMillis: Long = System.currentTimeMillis(),
        override val runId: String? = null,
    ) : MetricSample(occurredAtMillis, runId)

    /** A single tool invocation finished. */
    data class ToolCompleted(
        val toolName: String,
        val latencyMs: Long,
        val succeeded: Boolean,
        val retryCount: Int = 0,
        val requestId: String? = null,
        override val occurredAtMillis: Long = System.currentTimeMillis(),
        override val runId: String? = null,
    ) : MetricSample(occurredAtMillis, runId)

    /** A single orchestration step finished (planning, tool dispatch, observation, etc.). */
    data class StepLatency(
        val stepType: String,
        val latencyMs: Long,
        override val occurredAtMillis: Long = System.currentTimeMillis(),
        override val runId: String? = null,
    ) : MetricSample(occurredAtMillis, runId)

    /** Context compaction ran (truncation / summarization). */
    data class CompactionTriggered(
        val reason: String,
        val contextBefore: Int,
        val contextAfter: Int,
        override val occurredAtMillis: Long = System.currentTimeMillis(),
        override val runId: String? = null,
    ) : MetricSample(occurredAtMillis, runId)

    /** Periodic resource pressure sample (memory/CPU/thermal). */
    data class ResourceSnapshot(
        /** 0..100 composite pressure; see SystemResourceMonitor. */
        val pressurePercent: Int,
        /** Proportional Set Size in KiB. */
        val pssKb: Long,
        val nativeHeapKb: Long,
        val thermalStatus: ThermalPressure,
        override val occurredAtMillis: Long = System.currentTimeMillis(),
        override val runId: String? = null,
    ) : MetricSample(occurredAtMillis, runId)

    /** Increment a named monotonic counter. */
    data class CounterInc(
        val name: TelemetryCounter,
        val delta: Long = 1L,
        override val occurredAtMillis: Long = System.currentTimeMillis(),
        override val runId: String? = null,
    ) : MetricSample(occurredAtMillis, runId)
}

// ---------------------------------------------------------------------------
// Snapshot
// ---------------------------------------------------------------------------

/**
 * Wave 1 point-in-time snapshot of a sink's contents.
 *
 * Statistical fields (p50Ms, p95Ms) are intentionally nullable and default to `null`; Wave 1 only
 * guarantees sample counts and raw bounded buffers. Subsequent waves will replace them with proper
 * percentile computations keyed by [MetricKeyStats].
 */
data class TelemetrySnapshot(
    val capturedAtMillis: Long = System.currentTimeMillis(),
    val totalSamples: Int,
    val countsByVariant: Map<String, Int>,
    val counterTotals: Map<TelemetryCounter, Long>,
    /**
     * Per-(variant,label) keyed aggregate stats. Labels are variant-specific:
     * - GenerationComplete -> modelId
     * - ToolCompleted      -> toolName
     * - StepLatency        -> stepType
     * - Other variants     -> "" (single key)
     *
     * Wave 1 fills [MetricKeyStats.sampleCount] only; latency percentiles are `null`.
     */
    val keyedStats: Map<String, MetricKeyStats>,
    /**
     * Raw bounded recent samples, intended for debug UI / export. Format is deliberately
     * variant-specific (each key holds the most recent samples up to the sink's max-per-key).
     * Wave 1 only includes counters, tool latencies, and step latencies here to keep memory
     * bounded; full raw retention is a later-wave feature.
     */
    val recentLatencies: Map<String, List<Long>>,
)

/** Aggregate stats for a single (variant, label) key. Wave 1: counts only. */
data class MetricKeyStats(
    val key: String,
    val sampleCount: Int,
    val p50Ms: Long? = null,
    val p95Ms: Long? = null,
)

// ---------------------------------------------------------------------------
// Sink interface
// ---------------------------------------------------------------------------

interface TelemetrySink {
    fun record(sample: MetricSample)
    fun snapshot(): TelemetrySnapshot
}

// ---------------------------------------------------------------------------
// In-memory implementation
// ---------------------------------------------------------------------------

/**
 * Thread-safe, bounded in-memory telemetry sink.
 *
 * - Samples are partitioned by a string key (variant name + variant-specific label) and stored in
 *   a ring buffer of at most [maxPerKey] entries per key. Oldest entries are dropped when the
 *   buffer overflows.
 * - [record] is non-blocking for the hot path: it uses [ConcurrentHashMap] plus per-list
 *   synchronized blocks. A single-writer executor is overkill for the expected write rate and
 *   would add dispatch latency to every generation/tool step.
 * - Monotonic counters and the global sample counter are tracked with [java.util.concurrent.atomic.AtomicInteger]
 *   (sufficient for Wave 1 per-process volumes; bounded to Int.MAX_VALUE which is unreachable in a single
 *   process lifetime).
 */
class InMemoryTelemetrySink(
    private val maxPerKey: Int = DEFAULT_MAX_PER_KEY,
) : TelemetrySink {

    init {
        require(maxPerKey > 0) { "maxPerKey must be positive" }
    }

    // Variant name -> ring buffer of latencies (where applicable).
    private val buffers: ConcurrentHashMap<String, ArrayDeque<Long>> = ConcurrentHashMap()

    // Counter name -> running total.
    private val counters: ConcurrentHashMap<TelemetryCounter, Long> = ConcurrentHashMap()

    // Total number of samples accepted (overflowed entries still count).
    private val totalSamples = AtomicInteger(0)

    // Counts by sealed-class variant simple name.
    private val variantCounts: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    override fun record(sample: MetricSample) {
        val variantKey = sample.variantLabel()
        val bucketKey = sample.bucketKey()

        // Counters first: cheap, atomic, always recorded.
        if (sample is MetricSample.CounterInc) {
            counters.merge(sample.name, sample.delta) { a, b -> a + b }
        }

        // Total and variant counts.
        totalSamples.incrementAndGet()
        variantCounts.merge(variantKey, 1) { a, b -> a + b }

        // Latency-bearing samples go into a bounded ring buffer per bucket key.
        val latency = sample.latencyMsOrNull()
        if (latency != null) {
            val deque = buffers.getOrPut(bucketKey) { ArrayDeque(maxPerKey) }
            synchronized(deque) {
                if (deque.size >= maxPerKey) {
                    deque.removeFirst()
                }
                deque.addLast(latency)
            }
        }
    }

    override fun snapshot(): TelemetrySnapshot {
        // Snapshot under a consistent-but-not-global lock: copy each buffer under its own lock.
        val latenciesCopy = HashMap<String, List<Long>>(buffers.size)
        for ((key, deque) in buffers) {
            val copy = synchronized(deque) { deque.toList() }
            latenciesCopy[key] = copy
        }

        val countsCopy = variantCounts.toMap()
        val countersCopy = counters.toMap()

        val keyedStats = LinkedHashMap<String, MetricKeyStats>()
        for ((key, samples) in latenciesCopy) {
            keyedStats[key] = MetricKeyStats(
                key = key,
                sampleCount = samples.size,
                p50Ms = null, // Wave 1: deferred
                p95Ms = null, // Wave 1: deferred
            )
        }
        // Also surface counter-only keys (no latency buffer) so dashboards see zero-latency
        // counters like SafetyBlocks / CacheHits with accurate counts.
        for ((counter, total) in countersCopy) {
            val key = "Counter:${counter.name}"
            keyedStats[key] = MetricKeyStats(
                key = key,
                sampleCount = total.coerceAtLeast(0L).toInt(),
                p50Ms = null,
                p95Ms = null,
            )
        }

        return TelemetrySnapshot(
            totalSamples = totalSamples.get(),
            countsByVariant = countsCopy,
            counterTotals = countersCopy,
            keyedStats = keyedStats,
            recentLatencies = latenciesCopy,
        )
    }

    companion object {
        const val DEFAULT_MAX_PER_KEY: Int = 200
    }
}

// ---------------------------------------------------------------------------
// No-op implementation
// ---------------------------------------------------------------------------

object NoOpTelemetrySink : TelemetrySink {
    override fun record(sample: MetricSample) { /* intentionally empty */ }
    override fun snapshot(): TelemetrySnapshot =
        TelemetrySnapshot(
            totalSamples = 0,
            countsByVariant = emptyMap(),
            counterTotals = emptyMap(),
            keyedStats = emptyMap(),
            recentLatencies = emptyMap(),
        )
}

// ---------------------------------------------------------------------------
// Private helpers: variant metadata for routing into bucketed ring buffers
// ---------------------------------------------------------------------------

private fun MetricSample.variantLabel(): String = this::class.simpleName ?: "Unknown"

/**
 * Bucketing key for ring buffers. Combines variant name with a variant-specific label (model id,
 * tool name, step type) so that latencies for different tools / models do not get mixed.
 */
private fun MetricSample.bucketKey(): String =
    when (this) {
        is MetricSample.GenerationComplete -> "GenerationComplete:$modelId"
        is MetricSample.ToolCompleted -> "ToolCompleted:$toolName"
        is MetricSample.StepLatency -> "StepLatency:$stepType"
        is MetricSample.CompactionTriggered -> "CompactionTriggered:$reason"
        is MetricSample.ResourceSnapshot -> "ResourceSnapshot:${thermalStatus.name}"
        is MetricSample.CounterInc -> "Counter:${name.name}"
    }

/** The latency value (in ms) a variant contributes to ring buffers, or `null` if not latency-bearing. */
private fun MetricSample.latencyMsOrNull(): Long? =
    when (this) {
        is MetricSample.GenerationComplete -> totalMs
        is MetricSample.ToolCompleted -> latencyMs
        is MetricSample.StepLatency -> latencyMs
        is MetricSample.CompactionTriggered -> null
        is MetricSample.ResourceSnapshot -> null
        is MetricSample.CounterInc -> null
    }

// ---------------------------------------------------------------------------
// Bus wiring: convert SolinEvent stream into MetricSample records.
//
// PRIVACY CONTRACT: subscribers MUST NOT record message text, tool summaries,
// plan titles, evidence content, prompts, or any user-authored strings. Only
// numeric latencies, counts, enum/code names, and coarse metadata are recorded.
// ---------------------------------------------------------------------------

private val UNKNOWN_THERMAL = setOf("", null, "unknown", "null")

/**
 * Subscribe to [bus] and translate each [SolinEvent] into zero-or-more numeric [MetricSample]
 * records, dispatched to [this] sink. The collector is launched in [scope]; cancellation of
 * [scope] detaches the subscriber.
 *
 * This is a convenience wiring for production containers; callers with bespoke routing needs can
 * subscribe manually using [SolinEventBus.subscribe]. [NoOpTelemetrySink] still runs the collector
 * (cheap — record() is a no-op); to avoid even that, skip calling [attachTo] entirely.
 */
fun TelemetrySink.attachTo(bus: SolinEventBus, scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch {
        bus.subscribe(SolinEvent::class).collect { event ->
            event.toMetricSamples().forEach(::record)
        }
    }
}

/**
 * Map a single [SolinEvent] to zero or more privacy-safe metric samples.
 *
 * All string-bearing samples use only stable code/enum names (toolName, ToolErrorCode.code,
 * AgentErrorCode.code, ThermalPressure name). User content (text, summaries, prompts, plan titles,
 * evidence snippets) is NEVER included — TextDelta/Audit/Safety events increment a counter only.
 */
internal fun SolinEvent.toMetricSamples(): List<MetricSample> {
    val runId = runId
    val out = ArrayList<MetricSample>(2)
    when (this) {
        // Agent lifecycle
        is SolinEvent.Agent.RunStarted -> Unit // latency derived at RunEnded
        is SolinEvent.Agent.TurnStarted -> Unit
        is SolinEvent.Agent.ToolPlanned -> Unit
        is SolinEvent.Agent.ToolStarted -> Unit
        is SolinEvent.Agent.ToolProgress -> Unit // noisy; ignore

        is SolinEvent.Agent.ToolSucceeded -> {
            val dur = durationMs
            if (dur != null && dur > 0L) {
                out += MetricSample.ToolCompleted(
                    toolName = toolName,
                    latencyMs = dur,
                    succeeded = true,
                    occurredAtMillis = occurredAtMillis,
                    runId = runId,
                )
            }
            out += MetricSample.CounterInc(
                name = TelemetryCounter.ToolCalls,
                occurredAtMillis = occurredAtMillis,
                runId = runId,
            )
        }

        is SolinEvent.Agent.ToolFailed -> {
            // Record latency against ToolCompleted with succeeded=false (use 0 when unknown;
            // durationMs is not on ToolFailed event itself, but callers also emit ToolCompleted
            // from afterToolCall with the measured duration, so this is an existence marker).
            out += MetricSample.ToolCompleted(
                toolName = toolName,
                latencyMs = 0L,
                succeeded = false,
                occurredAtMillis = occurredAtMillis,
                runId = runId,
            )
            out += MetricSample.CounterInc(
                name = TelemetryCounter.ToolFailures,
                occurredAtMillis = occurredAtMillis,
                runId = runId,
            )
        }

        is SolinEvent.Agent.TextDelta -> {
            // PRIVACY: do NOT record event.text; only a unitless count.
            out += MetricSample.CounterInc(
                name = TelemetryCounter.TextTokens,
                occurredAtMillis = occurredAtMillis,
                runId = runId,
            )
        }

        is SolinEvent.Agent.TurnEnded -> {
            out += MetricSample.GenerationComplete(
                ttftMs = ttftMs,
                totalMs = durationMs,
                inputTokens = tokensIn,
                outputTokens = tokensOut,
                thinkingTokens = reasoningTokens,
                tps = if (durationMs > 0L) {
                    tokensOut.toDouble() * 1000.0 / durationMs.toDouble()
                } else {
                    0.0
                },
                backend = ServingBackend.LocalLiteRt,
                // TurnEnded does not currently carry modelId; use empty label. Callers that
                // have a modelId can emit GenerationComplete directly from afterToolCall or
                // the streaming runtime, which supersedes this aggregated sample.
                modelId = "",
                occurredAtMillis = occurredAtMillis,
                runId = runId,
            )
        }

        is SolinEvent.Agent.RunEnded -> {
            val dur = durationMs
            if (dur != null && dur > 0L) {
                out += MetricSample.StepLatency(
                    stepType = "run_total",
                    latencyMs = dur,
                    occurredAtMillis = occurredAtMillis,
                    runId = runId,
                )
            }
        }

        is SolinEvent.Agent.RunFailed -> {
            out += MetricSample.CounterInc(
                name = TelemetryCounter.RunFailures,
                occurredAtMillis = occurredAtMillis,
                runId = runId,
            )
            out += MetricSample.StepLatency(
                stepType = "run_failed:${code.code}",
                latencyMs = 0L,
                occurredAtMillis = occurredAtMillis,
                runId = runId,
            )
        }

        is SolinEvent.Agent.RunCancelled -> Unit
        is SolinEvent.Agent.RunSteered -> Unit

        is SolinEvent.Agent.UserQuestionAsked -> {
            out += MetricSample.CounterInc(
                name = TelemetryCounter.AskUserQuestions,
                occurredAtMillis = occurredAtMillis,
                runId = runId,
            )
        }

        is SolinEvent.Agent.UserQuestionAnswered -> Unit
        is SolinEvent.Agent.UserConfirmed -> Unit
        is SolinEvent.Agent.UserRejected -> Unit
        is SolinEvent.Agent.PlanUpdated -> Unit
        is SolinEvent.Agent.UndoPushed -> Unit

        // Runtime
        is SolinEvent.Runtime.ModelLoaded -> {
            out += MetricSample.StepLatency(
                stepType = "model_load",
                latencyMs = loadMs,
                occurredAtMillis = occurredAtMillis,
                runId = runId,
            )
        }

        is SolinEvent.Runtime.FirstToken -> {
            // FirstToken has ttftMs but no duration; emit as a StepLatency keyed by a sanitized
            // model bucket. PRIVACY: modelLabel may contain filesystem paths or model-version
            // strings that reveal device state; bucket to a short enum-style tag so telemetry
            // keys never carry user-identifying or path information.
            out += MetricSample.StepLatency(
                stepType = "ttft:${sanitizeModelLabel(modelLabel)}",
                latencyMs = ttftMs,
                occurredAtMillis = occurredAtMillis,
                runId = runId,
            )
        }

        is SolinEvent.Runtime.ResourceSample -> {
            val thermal = thermalPressureLabel.let { label ->
                if (label in UNKNOWN_THERMAL) {
                    com.bytedance.zgx.solin.resource.ThermalPressure.Unknown
                } else {
                    runCatching {
                        com.bytedance.zgx.solin.resource.ThermalPressure.valueOf(label!!)
                    }.getOrDefault(com.bytedance.zgx.solin.resource.ThermalPressure.Unknown)
                }
            }
            out += MetricSample.ResourceSnapshot(
                pressurePercent = pressurePercent,
                pssKb = appPssBytes / BYTES_PER_KIB,
                nativeHeapKb = 0L,
                thermalStatus = thermal,
                occurredAtMillis = occurredAtMillis,
                runId = runId,
            )
            // CPU is informational and optional (nullable); surface via counter when elevated.
            if (cpuPercent != null && cpuPercent > 80) {
                out += MetricSample.CounterInc(
                    name = TelemetryCounter.ModelFallback,
                    occurredAtMillis = occurredAtMillis,
                    runId = runId,
                )
            }
        }

        is SolinEvent.Runtime.DownloadProgress -> Unit

        // Audit: record count only — never summary/payload.
        is SolinEvent.Audit.ToolAudited,
        is SolinEvent.Audit.RemoteSendAudited,
        -> {
            out += MetricSample.CounterInc(
                name = TelemetryCounter.AuditEvents,
                occurredAtMillis = occurredAtMillis,
                runId = runId,
            )
        }

        // Safety: count only.
        is SolinEvent.Safety.ConfirmationRequested -> {
            out += MetricSample.CounterInc(
                name = TelemetryCounter.SafetyEvents,
                occurredAtMillis = occurredAtMillis,
                runId = runId,
            )
        }

        is SolinEvent.Safety.PolicyTriggered -> {
            out += MetricSample.CounterInc(
                name = TelemetryCounter.SafetyEvents,
                occurredAtMillis = occurredAtMillis,
                runId = runId,
            )
            if (blocked) {
                out += MetricSample.CounterInc(
                    name = TelemetryCounter.SafetyBlocks,
                    occurredAtMillis = occurredAtMillis,
                    runId = runId,
                )
            }
        }
    }
    return out
}

private const val BYTES_PER_KIB = 1024L

/**
 * Sanitize a runtime model label (which may be a filesystem path like `/data/.../model.tflite`,
 * a remote model id, or a developer-provided free-form string) into a short, enum-style tag
 * safe for use as a metric key. Never returns the raw label if it looks path-like; buckets
 * unknown/local/empty labels under "local" and strips directory + extension segments.
 */
internal fun sanitizeModelLabel(raw: String?): String {
    if (raw.isNullOrBlank()) return "local"
    val trimmed = raw.trim()
    // Anything containing path separators is almost certainly a filesystem path; bucket to local.
    if (trimmed.contains('/') || trimmed.contains('\\')) return "local"
    // Drop common file extensions that shouldn't appear in metric keys.
    val withoutExt = trimmed.removeSuffix(".tflite").removeSuffix(".bin").removeSuffix(".gguf")
    // Restrict to a tight character class to avoid control chars / whitespace / metric-separator chars.
    val sanitized = withoutExt.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
    if (sanitized.isEmpty()) return "local"
    // Cap length to avoid unbounded keys (remote model ids are often long).
    return sanitized.take(32)
}
