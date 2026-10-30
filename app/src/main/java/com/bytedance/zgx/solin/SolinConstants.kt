package com.bytedance.zgx.solin

/**
 * Centralized constants for the Solin Android application.
 *
 * Groups hardcoded configuration values by domain so that callers can reference a single
 * authoritative source instead of duplicating magic numbers across the codebase.
 */
object SolinConstants {

    /**
     * HTTP client and network-related timeouts used by remote model runtimes and connectivity probes.
     */
    object Network {

        /**
         * TCP connect timeout in seconds for the remote chat completion HTTP client.
         *
         * Used by [com.bytedance.zgx.solin.runtime.OkHttpRemoteChatRuntime]. A 15-second window
         * gives mobile networks on flaky connections enough time to establish a socket before
         * failing fast.
         */
        const val CHAT_CONNECT_TIMEOUT_SECONDS: Long = 15L

        /**
         * Read timeout in milliseconds for the remote chat completion HTTP client.
         *
         * Used by [com.bytedance.zgx.solin.runtime.OkHttpRemoteChatRuntime]. Set to 0 (infinite)
         * because the chat runtime consumes server-sent event streams that may be idle for long
         * periods between token deltas; an application-level watchdog handles true hangs.
         */
        const val CHAT_READ_TIMEOUT_MILLIS: Long = 0L

        /**
         * TCP connect timeout in seconds for the remote model connectivity probe.
         *
         * Used by [com.bytedance.zgx.solin.runtime.OkHttpRemoteModelConnectivityProbe]. The probe
         * is a quick pre-flight check, so a tighter 5-second bound avoids blocking the UI on
         * unreachable endpoints.
         */
        const val PROBE_CONNECT_TIMEOUT_SECONDS: Long = 5L

        /**
         * Read timeout in seconds for the remote model connectivity probe.
         *
         * Used by [com.bytedance.zgx.solin.runtime.OkHttpRemoteModelConnectivityProbe]. The probe
         * only fetches the lightweight `/models` endpoint; 8 seconds is generous for a healthy
         * server while still failing fast on a dead one.
         */
        const val PROBE_READ_TIMEOUT_SECONDS: Long = 8L

        /**
         * Maximum bytes to read from a remote model error response body for diagnostic logging.
         *
         * Used by [com.bytedance.zgx.solin.runtime.OkHttpRemoteChatRuntime]. Bounds the snippet
         * so a verbose server error cannot blow up memory or log output.
         */
        const val ERROR_BODY_SNIPPET_BYTES: Long = 1024L

        /**
         * Maximum number of attempts (initial + retries) for a remote chat generation.
         *
         * Used by [com.bytedance.zgx.solin.runtime.RemoteRetryPolicy]. Provider endpoints are not
         * always stable — transient 5xx, rate limits and dropped mobile sockets are common — so a
         * small bounded retry turns a hard run failure into a recoverable hiccup. Kept low so a
         * genuinely down provider still fails fast instead of stalling the user.
         */
        const val REMOTE_RETRY_MAX_ATTEMPTS: Int = 3

        /**
         * Base delay in milliseconds for remote retry exponential backoff.
         *
         * Used by [com.bytedance.zgx.solin.runtime.RemoteRetryPolicy]. Attempt N waits roughly
         * `base * 2^(N-1)` with jitter, unless the provider supplied a `Retry-After` hint.
         */
        const val REMOTE_RETRY_BASE_DELAY_MILLIS: Long = 500L

        /**
         * Upper bound in milliseconds for a single remote retry wait.
         *
         * Used by [com.bytedance.zgx.solin.runtime.RemoteRetryPolicy]. Also clamps a provider
         * `Retry-After` value so an erroneous or hostile header cannot stall the app.
         */
        const val REMOTE_RETRY_MAX_DELAY_MILLIS: Long = 4_000L
    }

    /**
     * Agent loop execution budgets and retry limits.
     */
    object AgentLoop {

        /**
         * Maximum number of retry attempts for a single tool call after a recoverable failure.
         *
         * Default constructor parameter of
         * [com.bytedance.zgx.solin.orchestration.AgentLoopRuntime]. A value of 1 means the agent
         * gets exactly one retry before the tool result is treated as terminal.
         */
        const val MAX_TOOL_RETRY_ATTEMPTS: Int = 1

        /**
         * Maximum number of tool-execution steps allowed within a single agent run.
         *
         * Default constructor parameter of
         * [com.bytedance.zgx.solin.orchestration.AgentLoopRuntime]. Once this budget is exhausted
         * the run fails with a step-budget-exceeded reason, preventing runaway tool loops.
         */
        const val MAX_RUN_TOOL_STEPS: Int = 10

        /**
         * Maximum number of observation-decision steps within a single agent run.
         *
         * Default constructor parameter of
         * [com.bytedance.zgx.solin.orchestration.AgentLoopRuntime]. Each model turn that produces
         * an [com.bytedance.zgx.solin.orchestration.AgentStep.ObservationDecided] counts toward
         * this cap, guarding against infinite observe-replan cycles.
         */
        const val MAX_OBSERVATION_DECISIONS: Int = 16

        /**
         * Maximum number of bounded auto-dismiss rounds attempted before a low-risk UI action when
         * a blocking promotional overlay/interstitial occludes the target.
         *
         * Each round detects a close/skip affordance (never a dangerous-action control), taps it,
         * re-observes, and stops as soon as the overlay clears. Kept small so overlay dismissal can
         * never dominate the run; on persistent (e.g. sticky) overlays the loop gives up and lets
         * the real action proceed rather than looping.
         */
        const val AD_DISMISS_MAX_ROUNDS: Int = 2

        /**
         * Hard wall-clock lifetime of a single agent run, in milliseconds.
         *
         * The step budgets above bound how MANY steps a run may take, not how LONG each one may
         * block: a local model generation has no generation timeout, and every tool step can burn
         * its own execution timeout plus a retry. Multiplying
         * [MAX_RUN_TOOL_STEPS] x (tool timeout + retry) by [MAX_OBSERVATION_DECISIONS] model turns
         * therefore leaves the total run duration effectively unbounded. This deadline is the
         * independent time-domain gate: once a run has been alive this long,
         * [com.bytedance.zgx.solin.orchestration.AgentRunBudget.runDeadlineExceeded] fails it
         * closed at the next observation checkpoint instead of letting it live forever.
         *
         * Deliberately generous (5 minutes) — a legitimate multi-step device-control run with user
         * confirmations can easily take a minute or two, and this is a runaway guard, not a UX
         * timeout.
         */
        const val MAX_RUN_WALL_CLOCK_MILLIS: Long = 5 * 60_000L

        /**
         * Maximum number of sequential action segments an explicit "do A, then B" request may be
         * expanded into by
         * [com.bytedance.zgx.solin.orchestration.SequentialActionObservationReplanner].
         *
         * Lives here rather than next to the replanner so the worst-case cost of one run (tool
         * steps x observation decisions x sequential tail x model replans) can be audited from a
         * single place.
         */
        const val MAX_SEQUENTIAL_ACTIONS: Int = 4

        /**
         * Maximum number of observation-driven replans a local action-planning model may contribute
         * to a single run
         * ([com.bytedance.zgx.solin.orchestration.ModelObservationReplanner] default).
         *
         * Counted from the trace via the replan request reason, so the budget survives process
         * restarts. Kept at 1 by default: a model that cannot make progress in one replan is far
         * more likely looping than converging. Composition roots may raise it explicitly for
         * model-driven app search.
         */
        const val MAX_MODEL_OBSERVATION_REPLANS: Int = 1
    }

    /**
     * UI display and interaction thresholds.
     */
    object Ui {

        /**
         * Fraction of the remote model's context window used as the compaction budget.
         *
         * Used by [com.bytedance.zgx.solin.SolinViewModel]. History is compacted when estimated
         * tokens exceed `contextWindow * REMOTE_COMPACTION_BUDGET_RATIO`, leaving headroom for
         * the next model response.
         */
        const val REMOTE_COMPACTION_BUDGET_RATIO: Double = 0.85

        /**
         * Maximum number of characters retained from a voice transcription before truncation.
         *
         * Used by [com.bytedance.zgx.solin.SolinViewModel]. Caps the transcript so a long
         * dictation does not balloon the message list or exceed model context limits.
         */
        const val MAX_VOICE_TRANSCRIPT_CHARS: Int = 2_000

        /**
         * Number of amplitude samples used to render the voice input waveform.
         *
         * Used by [com.bytedance.zgx.solin.SolinViewModel]. Determines the visual resolution of
         * the recording indicator.
         */
        const val VOICE_WAVEFORM_SAMPLE_COUNT: Int = 9

        /**
         * Maximum characters shown in the confirmation preview of a remote send prompt.
         *
         * Used by [com.bytedance.zgx.solin.SolinViewModel]. Truncates long prompts in the
         * "send to remote model" dialog so the UI stays readable.
         */
        const val REMOTE_SEND_PROMPT_PREVIEW_MAX_CHARS: Int = 240
    }

    /**
     * On-device text embedding runtime configuration.
     */
    object Embedding {

        /**
         * Timeout in seconds for a single embedding inference call.
         *
         * Used by [com.bytedance.zgx.solin.runtime.TfliteTextEmbeddingRuntimeFactory]. The
         * Gemma embedding model runs on-device; 30 seconds accommodates cold-start model loading
         * and slower CPUs without hanging the caller indefinitely.
         */
        const val EMBEDDING_TIMEOUT_SECONDS: Long = 30L
    }
}
