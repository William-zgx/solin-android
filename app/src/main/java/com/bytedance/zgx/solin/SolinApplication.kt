package com.bytedance.zgx.solin

import android.app.Application
import com.bytedance.zgx.solin.data.SolinDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SolinApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val containerHolders = mutableMapOf<Boolean, ContainerHolder>()

    @Synchronized
    fun appContainerState(
        skipLocalModelRuntime: Boolean,
    ): StateFlow<SolinAppContainerInitialization> {
        val holder = containerHolders[skipLocalModelRuntime]
            ?: startContainerInitialization(skipLocalModelRuntime)
        return holder.state.asStateFlow()
    }

    @Synchronized
    fun readyAppContainer(skipLocalModelRuntime: Boolean): SolinAppContainer? =
        (containerHolders[skipLocalModelRuntime]?.state?.value
            as? SolinAppContainerInitialization.Ready)
            ?.container

    internal fun clearContainersForInstrumentation() {
        val holders = synchronized(this) {
            containerHolders.values.toList().also { containerHolders.clear() }
        }
        holders.forEach { holder -> holder.initializationJob?.cancel() }
        holders
            .mapNotNull { holder ->
                (holder.state.value as? SolinAppContainerInitialization.Ready)?.container
            }
            .forEach(SolinAppContainer::close)
        SolinDatabase.closeForInstrumentation()
    }

    @Synchronized
    private fun startContainerInitialization(skipLocalModelRuntime: Boolean): ContainerHolder {
        val holder = ContainerHolder()
        containerHolders[skipLocalModelRuntime] = holder
        holder.initializationJob = applicationScope.launch {
            val result = runCatching {
                SolinAppContainer(
                    context = this@SolinApplication,
                    skipLocalModelRuntime = skipLocalModelRuntime,
                )
            }
            val staleContainer = synchronized(this@SolinApplication) {
                if (containerHolders[skipLocalModelRuntime] !== holder) {
                    result.getOrNull()
                } else {
                    holder.state.value = result.fold(
                        onSuccess = SolinAppContainerInitialization::Ready,
                        onFailure = SolinAppContainerInitialization::Failed,
                    )
                    null
                }
            }
            staleContainer?.close()
        }
        return holder
    }

    private class ContainerHolder {
        val state = MutableStateFlow<SolinAppContainerInitialization>(
            SolinAppContainerInitialization.Loading,
        )
        var initializationJob: Job? = null
    }
}

sealed interface SolinAppContainerInitialization {
    data object Loading : SolinAppContainerInitialization

    data class Ready(
        val container: SolinAppContainer,
    ) : SolinAppContainerInitialization

    data class Failed(
        val cause: Throwable,
    ) : SolinAppContainerInitialization
}
