package com.bytedance.zgx.solin.module

import com.bytedance.zgx.solin.skill.SkillSource
import com.bytedance.zgx.solin.tool.ToolProvider
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult

/**
 * A compile-time registration unit for Solin extensions.
 *
 * Modules are discovered via a static listOf(...) in SolinAppContainer.
 * Third-party (out-of-process) extensions come later over Binder (#15);
 * this seam is for in-process first-party Kotlin modules only.
 *
 * TODO: when Hilt/Dagger lands, migrate to @IntoSet multibindings.
 */
interface SolinModule {
    val moduleId: String
    fun register(registry: SolinModuleRegistry)
}

interface SolinModuleRegistry {
    fun addToolProvider(provider: ToolProvider)
    fun addToolHandler(toolName: String, handler: ToolHandler)
    fun addSkillSource(source: SkillSource)
}

/**
 * Receives a ToolRequest and returns a ToolResult, or null to fall through to
 * the default built-in executor. Wire-up into ToolExecutor is deferred.
 *
 * Thread-safety: [ToolHandler.execute] may be invoked concurrently from multiple
 * coroutine contexts when the enclosing [com.bytedance.zgx.solin.tool.ToolExecutor]
 * runs independent tools in parallel (see
 * [com.bytedance.zgx.solin.tool.ToolExecutionMode.ConcurrentWhenIndependent]).
 * Implementations MUST be safe for concurrent invocation; any mutable state they
 * touch must be guarded (e.g. via `@Volatile`, `synchronized`, or thread-safe
 * collections). Handlers must also be prompt to honour cancellation — they will be
 * invoked inside `runInterruptible`-style contexts and should propagate
 * [kotlinx.coroutines.CancellationException].
 */
fun interface ToolHandler {
    suspend fun execute(request: ToolRequest): ToolResult?
}
