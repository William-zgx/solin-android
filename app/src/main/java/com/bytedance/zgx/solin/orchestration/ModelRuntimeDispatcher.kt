package com.bytedance.zgx.solin.orchestration

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

interface RunningModelRuntimeCall<T> : RuntimeStopHandle {
    suspend fun await(): T
}

internal class ExactlyOnceRunningModelRuntimeCall<T>(
    private val delegate: RunningModelRuntimeCall<T>,
) : RunningModelRuntimeCall<T> {
    private val stopped = AtomicBoolean(false)

    override suspend fun await(): T = delegate.await()

    override fun stop() {
        if (stopped.compareAndSet(false, true)) delegate.stop()
    }
}

/**
 * Runtime factories synchronously register and return a stop handle. They must be short and
 * non-blocking, must not call back into the dispatcher or placement store, and may throw only
 * before producing external side effects. Suspended runtime work belongs in [RunningModelRuntimeCall.await].
 */
data class ModelRuntimeAdapters<T>(
    val local: (ModelRuntimeInvocation) -> RunningModelRuntimeCall<T>,
    val remote: (ModelRuntimeInvocation) -> RunningModelRuntimeCall<T>,
)

class PlacementDispatchException(
    runId: String,
    message: String = "Serving placement unavailable",
) : IllegalStateException("$message for run $runId")

class ModelRuntimeDispatcher(
    private val bindingStore: RunPlacementBindingStore,
) {
    fun stop(
        runId: String,
        state: AgentRunState,
        updatedAtMillis: Long,
    ): Boolean {
        val terminalized = bindingStore.terminalizeRun(runId, state, updatedAtMillis)
            as? TerminalizeRunResult.Terminalized
            ?: return false
        terminalized.stopHandle?.stop()
        return true
    }

    suspend fun <T> dispatch(
        permit: ActiveRunPlacementPermit,
        receipt: RunDataReceipt,
        adapters: ModelRuntimeAdapters<T>,
    ): T {
        val claim = bindingStore.claimForDispatch(permit, receipt)
        if (claim !is ClaimInvocationResult.Started) {
            throw PlacementDispatchException(permit.binding.runId)
        }
        val invocation = claim.invocation
        val adapter = when (invocation.placement) {
            RunPlacement.Local -> adapters.local
            RunPlacement.Remote -> adapters.remote
        }
        val call = try {
            bindingStore.startInvocation(permit, invocation) { adapter(invocation) }
        } catch (throwable: Throwable) {
            finishFailure(invocation)?.let(throwable::addSuppressed)
            throw throwable
        } ?: throw PlacementDispatchException(
            invocation.runId,
            "Serving invocation terminated before runtime start",
        )
        var callbackFailure: Throwable? = null
        try {
            return call.await()
        } catch (cancelled: CancellationException) {
            callbackFailure = cancelled
            runCatching { call.stop() }
                .exceptionOrNull()
                ?.let(cancelled::addSuppressed)
            throw cancelled
        } catch (throwable: Throwable) {
            callbackFailure = throwable
            throw throwable
        } finally {
            finishFailure(invocation)?.let { finishFailure ->
                if (callbackFailure == null) {
                    throw finishFailure
                } else {
                    callbackFailure.addSuppressed(finishFailure)
                }
            }
        }
    }

    private fun finishFailure(invocation: ModelRuntimeInvocation): Throwable? =
        runCatching { bindingStore.finishInvocation(invocation) }.fold(
            onSuccess = { finished ->
                if (finished) {
                    null
                } else {
                    PlacementDispatchException(
                        invocation.runId,
                        "Serving invocation finish CAS failed",
                    )
                }
            },
            onFailure = { it },
        )
}
