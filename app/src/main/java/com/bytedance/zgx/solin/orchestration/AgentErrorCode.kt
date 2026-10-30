package com.bytedance.zgx.solin.orchestration

sealed interface AgentErrorCode {
    val code: String

    data object ModelTimeout : AgentErrorCode {
        override val code: String = "model_timeout"
    }

    data object Validation : AgentErrorCode {
        override val code: String = "validation"
    }

    data class Unknown(val message: String) : AgentErrorCode {
        override val code: String = "unknown"
    }
}
