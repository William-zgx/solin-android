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
         * Maximum characters to include in the user-facing failure message derived from an error body.
         *
         * Used by [com.bytedance.zgx.solin.runtime.OkHttpRemoteChatRuntime]. Keeps the surfaced
         * reason single-line and log-safe.
         */
        const val ERROR_BODY_SNIPPET_CHARS: Int = 512
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
