package com.bytedance.zgx.solin.orchestration

data class ModelRuntimeAdapters<T>(
    val local: suspend (ModelRuntimeInvocation) -> T,
    val remote: suspend (ModelRuntimeInvocation) -> T,
)

data class ModelRuntimeStops(
    val local: () -> Unit,
    val remote: () -> Unit,
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
        stops: ModelRuntimeStops,
    ): Boolean {
        val terminalized = bindingStore.terminalizeRun(runId, state, updatedAtMillis)
            as? TerminalizeRunResult.Terminalized
            ?: return false
        when (terminalized.placement) {
            RunPlacement.Local -> stops.local()
            RunPlacement.Remote -> stops.remote()
            null -> Unit
        }
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
        var callbackFailure: Throwable? = null
        try {
            return adapter(invocation)
        } catch (throwable: Throwable) {
            callbackFailure = throwable
            throw throwable
        } finally {
            if (!bindingStore.finishInvocation(invocation)) {
                val finishFailure = PlacementDispatchException(
                    invocation.runId,
                    "Serving invocation finish CAS failed",
                )
                if (callbackFailure == null) {
                    throw finishFailure
                } else {
                    callbackFailure.addSuppressed(finishFailure)
                }
            }
        }
    }
}
