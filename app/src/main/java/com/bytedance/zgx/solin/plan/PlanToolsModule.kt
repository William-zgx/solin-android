package com.bytedance.zgx.solin.plan

import com.bytedance.zgx.solin.module.SolinModule
import com.bytedance.zgx.solin.module.SolinModuleRegistry

class PlanToolsModule(
    private val handler: PlanToolHandler,
) : SolinModule {
    override val moduleId: String get() = "plan:core"

    override fun register(registry: SolinModuleRegistry) {
        registry.addToolProvider { handler.toolSpecs() }
        registry.addToolHandler(PlanToolHandler.PLAN_WRITE_TOOL, handler.handler())
        registry.addToolHandler(PlanToolHandler.PLAN_READ_TOOL, handler.handler())
    }
}
