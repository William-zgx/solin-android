package com.bytedance.zgx.solin.runtime

import com.bytedance.zgx.solin.SolinConstants
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.min
import kotlin.math.pow

/**
 * Why a remote chat attempt failed, classified for retry decisions.
 *
 * Retry is only ever safe for failures that are *transient and provider-side*. Anything caused by
 * the request itself (bad auth, malformed body, unknown model) will fail identically on every
 * attempt, so retrying only burns time and quota.
 */
enum class RemoteFailureClass {
    /** Transport-level failure (DNS, connect, socket timeout, reset). Safe to retry. */
    Transport,

    /** Provider signalled rate limiting (HTTP 429). Safe to retry, ideally honouring Retry-After. */
    RateLimited,

    /** Provider-side error (HTTP 5xx, 408). Safe to retry. */
    ProviderUnavailable,

    /** Caller-side / permanent failure (4xx other than 408/429). Never retry. */
    Permanent,

    /** The call was cancelled by the app or user. Never retry. */
    Cancelled,
}

/**
 * A non-successful HTTP response from the chat completions endpoint.
 *
 * Carries the status code and any `Retry-After` hint so the retry policy can classify the failure
 * without re-reading the (already closed) response body. [message] stays the user-facing failure
 * text so existing error surfacing is unchanged.
 */
class RemoteHttpStatusFailure(
    val statusCode: Int,
    val retryAfterMillis: Long?,
    message: String,
) : IllegalStateException(message)

/**
 * Decision returned by [RemoteRetryPolicy]: either give up, or wait [delayMillis] and try again.
 */
sealed class RemoteRetryDecision {
    data object GiveUp : RemoteRetryDecision()

    data class Retry(
        val delayMillis: Long,
        val attempt: Int,
    ) : RemoteRetryDecision()
}

/**
 * Retry policy for remote chat generations.
 *
 * The provider endpoints Solin talks to are not always stable — transient 5xx, rate limits and
 * dropped sockets are common on mobile networks. Without a retry the whole agent run fails and the
 * user has to re-ask. This policy makes those failures recoverable while keeping three hard rules:
 *
 * 1. **Never retry a permanent failure.** 401/403 (credentials) and 4xx request errors are
 *    deterministic; retrying wastes the user's time and quota.
 * 2. **Never retry a cancellation.** A cancelled call is an explicit user/app decision.
 * 3. **Never retry after output has been emitted.** Streaming responses are emitted incrementally;
 *    replaying a request after partial output would duplicate text in the user's chat. Callers must
 *    pass `hasEmittedOutput = true` once the first chunk has been sent downstream.
 *
 * Backoff is exponential with jitter, and honours a provider-supplied `Retry-After` when present
 * (clamped, so a hostile/erroneous header cannot stall the app).
 */
class RemoteRetryPolicy(
    private val maxAttempts: Int = SolinConstants.Network.REMOTE_RETRY_MAX_ATTEMPTS,
    private val baseDelayMillis: Long = SolinConstants.Network.REMOTE_RETRY_BASE_DELAY_MILLIS,
    private val maxDelayMillis: Long = SolinConstants.Network.REMOTE_RETRY_MAX_DELAY_MILLIS,
    /** Jitter fraction in [0,1]; 0.25 means the delay is scaled by a random factor in [0.75, 1.25]. */
    private val jitterRatio: Double = 0.25,
    private val randomFraction: () -> Double = { Math.random() },
) {
    /**
     * Decide whether a failed attempt should be retried.
     *
     * @param failureClass how the attempt failed.
     * @param attempt 1-based index of the attempt that just failed.
     * @param hasEmittedOutput whether any output has already been emitted downstream for this
     *   request. When true the policy always gives up, so streamed text is never duplicated.
     * @param retryAfterMillis provider-supplied delay hint, if any.
     */
    fun decide(
        failureClass: RemoteFailureClass,
        attempt: Int,
        hasEmittedOutput: Boolean,
        retryAfterMillis: Long? = null,
    ): RemoteRetryDecision {
        if (hasEmittedOutput) return RemoteRetryDecision.GiveUp
        if (!failureClass.isRetryable()) return RemoteRetryDecision.GiveUp
        if (attempt >= maxAttempts) return RemoteRetryDecision.GiveUp

        val backoff = retryAfterMillis?.coerceIn(0L, maxDelayMillis) ?: exponentialBackoff(attempt)
        return RemoteRetryDecision.Retry(delayMillis = backoff, attempt = attempt + 1)
    }

    private fun exponentialBackoff(attempt: Int): Long {
        val exponential = baseDelayMillis.toDouble() * 2.0.pow((attempt - 1).coerceAtLeast(0))
        val capped = min(exponential, maxDelayMillis.toDouble())
        // randomFraction() in [0,1) -> factor in [1 - jitterRatio, 1 + jitterRatio)
        val factor = (1.0 - jitterRatio) + (randomFraction().coerceIn(0.0, 1.0) * 2.0 * jitterRatio)
        return (capped * factor).toLong().coerceIn(0L, maxDelayMillis)
    }

    companion object {
        /** Classify an HTTP status code from a non-successful chat completion response. */
        fun classifyHttpStatus(code: Int): RemoteFailureClass = when {
            code == 429 -> RemoteFailureClass.RateLimited
            code == 408 -> RemoteFailureClass.ProviderUnavailable
            code >= 500 -> RemoteFailureClass.ProviderUnavailable
            else -> RemoteFailureClass.Permanent
        }

        /** Classify a thrown failure from an in-flight call. */
        fun classifyThrowable(throwable: Throwable, callCancelled: Boolean): RemoteFailureClass = when {
            callCancelled -> RemoteFailureClass.Cancelled
            throwable is CancellationException -> RemoteFailureClass.Cancelled
            throwable is IOException -> RemoteFailureClass.Transport
            else -> RemoteFailureClass.Permanent
        }

        /**
         * Parse an HTTP `Retry-After` header. Supports the delta-seconds form (the form providers
         * use for rate limits); an HTTP-date form returns null so the caller falls back to backoff.
         */
        fun parseRetryAfterMillis(headerValue: String?): Long? {
            val seconds = headerValue?.trim()?.toLongOrNull() ?: return null
            if (seconds < 0L) return null
            return seconds * 1_000L
        }
    }
}

private fun RemoteFailureClass.isRetryable(): Boolean = when (this) {
    RemoteFailureClass.Transport,
    RemoteFailureClass.RateLimited,
    RemoteFailureClass.ProviderUnavailable,
    -> true

    RemoteFailureClass.Permanent,
    RemoteFailureClass.Cancelled,
    -> false
}
