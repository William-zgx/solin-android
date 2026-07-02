package com.bytedance.zgx.solin.audit

class InMemoryToolAuditSink : ToolAuditSink {
    private val mutableEvents = mutableListOf<ToolAuditEvent>()

    val events: List<ToolAuditEvent>
        get() = mutableEvents.toList()

    override fun record(event: ToolAuditEvent) {
        mutableEvents += event.redactedForAudit()
    }
}
