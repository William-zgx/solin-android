package com.bytedance.zgx.solin.mcp

/** Trust Center view of the MCP opt-in capability. */
data class McpCapabilityState(
    /** Persisted toggle (default OFF). */
    val enabled: Boolean,
    /** Whether the user already accepted the "data leaves the device" disclosure. */
    val disclosureAcknowledged: Boolean,
    /** Whether MCP tools were registered for this app session (toggle state at app start). */
    val activeThisSession: Boolean,
    val serverCount: Int,
    val approvedToolCount: Int,
) {
    /** Turning ON takes effect after restart because the tool registry is frozen at startup. */
    val restartRequired: Boolean get() = enabled && !activeThisSession
}

/**
 * Backs the Trust Center MCP toggle. Enabling requires the disclosure to be accepted (first time
 * only); disabling is always allowed and takes effect immediately for calls.
 */
class McpCapabilityController(
    private val settings: McpCapabilitySettings,
    private val activeThisSession: Boolean,
) {
    fun state(): McpCapabilityState {
        val servers = settings.servers()
        return McpCapabilityState(
            enabled = settings.isEnabled(),
            disclosureAcknowledged = settings.isDisclosureAcknowledged(),
            activeThisSession = activeThisSession,
            serverCount = servers.size,
            approvedToolCount = servers.sumOf { it.approvedTools.size },
        )
    }

    /**
     * Sets the toggle. When enabling for the first time, [disclosureAccepted] must be true (the
     * UI showed and the user confirmed the disclosure); otherwise the toggle stays OFF.
     */
    fun setEnabled(enabled: Boolean, disclosureAccepted: Boolean = false): McpCapabilityState {
        if (enabled && !settings.isDisclosureAcknowledged()) {
            if (!disclosureAccepted) return state()
            settings.acknowledgeDisclosure()
        }
        settings.setEnabled(enabled)
        return state()
    }
}
