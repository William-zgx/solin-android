package com.bytedance.zgx.solin.orchestration

/**
 * No-op implementation of [AgentHooks]. All methods return their default/proceed/keep values
 * so existing call sites that don't care about hooks have a concrete instance to pass.
 */
object NoOpAgentHooks : AgentHooks
