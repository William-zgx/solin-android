package com.bytedance.zgx.solin.orchestration

sealed interface ToolErrorCode {
    val code: String

    data object InvalidArgs : ToolErrorCode {
        override val code: String = "invalid_args"
    }

    data object ToolNotFound : ToolErrorCode {
        override val code: String = "tool_not_found"
    }

    data object ExecutionFailed : ToolErrorCode {
        override val code: String = "execution_failed"
    }

    data object SafetyRejected : ToolErrorCode {
        override val code: String = "safety_rejected"
    }

    data object Timeout : ToolErrorCode {
        override val code: String = "timeout"
    }

    data object Cancelled : ToolErrorCode {
        override val code: String = "cancelled"
    }

    data class Unknown(val message: String) : ToolErrorCode {
        override val code: String = "unknown"
    }
}
