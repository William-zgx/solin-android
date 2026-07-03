package com.bytedance.zgx.solin.orchestration

sealed interface AgentErrorCode {
    val code: String

    data object Network : AgentErrorCode {
        override val code: String = "network"
    }

    data object ContextOverflow : AgentErrorCode {
        override val code: String = "context_overflow"
    }

    data object SafetyBlocked : AgentErrorCode {
        override val code: String = "safety_blocked"
    }

    data object ToolNotFound : AgentErrorCode {
        override val code: String = "tool_not_found"
    }

    data object PermissionDenied : AgentErrorCode {
        override val code: String = "permission_denied"
    }

    data object ModelTimeout : AgentErrorCode {
        override val code: String = "model_timeout"
    }

    data object Cancelled : AgentErrorCode {
        override val code: String = "cancelled"
    }

    data object Validation : AgentErrorCode {
        override val code: String = "validation"
    }

    data object PlanWriteFailed : AgentErrorCode {
        override val code: String = "plan_write_failed"
    }

    data object UndoFailed : AgentErrorCode {
        override val code: String = "undo_failed"
    }

    data object UndoExpired : AgentErrorCode {
        override val code: String = "undo_expired"
    }

    data class Unknown(val message: String) : AgentErrorCode {
        override val code: String = "unknown"
    }
}
