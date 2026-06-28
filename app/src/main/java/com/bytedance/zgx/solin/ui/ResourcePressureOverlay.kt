package com.bytedance.zgx.solin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bytedance.zgx.solin.resource.SYSTEM_RESOURCE_SAMPLE_INTERVAL_MS
import com.bytedance.zgx.solin.resource.SystemResourceSnapshot
import kotlinx.coroutines.delay

@Composable
fun ResourcePressureOverlay(
    resourceSampler: (suspend () -> SystemResourceSnapshot?)?,
    modifier: Modifier = Modifier,
) {
    var resourceSnapshot by remember { mutableStateOf<SystemResourceSnapshot?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(resourceSampler, lifecycleOwner) {
        val sampler = resourceSampler
        if (sampler == null) {
            resourceSnapshot = null
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                runCatching { sampler() }.getOrNull()?.let { resourceSnapshot = it }
                delay(SYSTEM_RESOURCE_SAMPLE_INTERVAL_MS)
            }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        resourceSnapshot?.let { snapshot ->
            ResourcePressureBadge(snapshot = snapshot)
        }
    }
}
