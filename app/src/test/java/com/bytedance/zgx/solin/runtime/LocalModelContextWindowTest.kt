package com.bytedance.zgx.solin.runtime

import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.LocalModelTokenLimits
import com.bytedance.zgx.solin.ModelBackendKind
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCapabilityProfile
import com.bytedance.zgx.solin.ModelFeature
import com.bytedance.zgx.solin.ModelInputModality
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.ExperimentalApi
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalModelContextWindowTest {
    @Test
    fun missingOrInvalidProfileWindowFallsBackToEightK() {
        assertEquals(8 * 1024, LocalModelTokenLimits.contextWindowTokensFor(null as Int?))
        assertEquals(8 * 1024, LocalModelTokenLimits.contextWindowTokensFor(0))
        assertEquals(8 * 1024, LocalModelTokenLimits.contextWindowTokensFor(-1))
        assertEquals(8 * 1024, LocalModelTokenLimits.contextWindowTokensFor(null as ModelCapabilityProfile?))
    }

    @Test
    fun profileWindowIsUsedAndOversizedClaimsAreCapped() {
        assertEquals(16 * 1024, LocalModelTokenLimits.contextWindowTokensFor(16 * 1024))
        assertEquals(4 * 1024, LocalModelTokenLimits.contextWindowTokensFor(4 * 1024))
        assertEquals(
            LocalModelTokenLimits.MAX_SUPPORTED_CONTEXT_WINDOW_TOKENS,
            LocalModelTokenLimits.contextWindowTokensFor(1_000_000),
        )
        assertEquals(16 * 1024, LocalModelTokenLimits.contextWindowTokensFor(chatProfile(16 * 1024)))
    }

    @Test
    fun inputBudgetIsWindowMinusOutputReserve() {
        assertEquals(LocalModelTokenLimits.MAX_INPUT_TOKENS, LocalModelTokenLimits.maxInputTokensFor(8 * 1024))
        assertEquals(14 * 1024, LocalModelTokenLimits.maxInputTokensFor(16 * 1024))
        assertEquals(0, LocalModelTokenLimits.maxInputTokensFor(1_024))
    }

    @Test
    fun runtimeCapabilitiesReadWindowFromProfile() {
        assertEquals(
            16 * 1024,
            LocalModelRuntimeCapabilities.fromProfile(chatProfile(16 * 1024)).contextWindowTokens,
        )
        assertEquals(
            LocalModelTokenLimits.MAX_TOTAL_TOKENS,
            LocalModelRuntimeCapabilities.fromProfile(null).contextWindowTokens,
        )
    }

    @Test
    fun adaptivePolicyCapsOversizedWindowAndFallsBackForInvalidOne() {
        val capped = AdaptiveGenerationPolicy.decide(
            AdaptiveGenerationPolicyInput(preferredBackend = BackendChoice.CPU, contextWindowTokens = 1_000_000),
        )
        val invalid = AdaptiveGenerationPolicy.decide(
            AdaptiveGenerationPolicyInput(preferredBackend = BackendChoice.CPU, contextWindowTokens = 0),
        )

        assertEquals(
            LocalModelTokenLimits.MAX_SUPPORTED_CONTEXT_WINDOW_TOKENS - LocalModelTokenLimits.OUTPUT_TOKEN_RESERVE,
            capped.maxInputTokens,
        )
        assertEquals(LocalModelTokenLimits.MAX_INPUT_TOKENS, invalid.maxInputTokens)
    }

    @Test
    fun runtimeHistoryBudgetDefaultsToProfileWindowInsteadOfStaticEightK() {
        val factory = RecordingEngineFactory()
        val runtime = RealLiteRtRuntime(File("/tmp"), contextCompactor = null, engineFactory = factory)
        runtime.configureModelCapabilities(LocalModelRuntimeCapabilities(contextWindowTokens = 16 * 1024))

        runtime.load("/tmp/model.litertlm", BackendChoice.CPU, emptyList(), GenerationParameters())
        runtime.recreateConversation(emptyList(), GenerationParameters())
        runtime.recreateConversationForSend(emptyList(), "hi", GenerationParameters(), maxInputTokens = 3_000)

        assertEquals(16 * 1024, factory.capabilities.single().contextWindowTokens)
        assertEquals(listOf(14 * 1024, 14 * 1024, 3_000), factory.specs.map { it.maxInputTokens })
    }

    private fun chatProfile(window: Int): ModelCapabilityProfile =
        ModelCapabilityProfile(
            id = "test-chat",
            displayName = "test",
            capability = ModelCapability.Chat,
            backendKind = ModelBackendKind.LocalLiteRt,
            inputModalities = setOf(ModelInputModality.Text),
            features = setOf(ModelFeature.TextGeneration),
            tokenBudget = window,
        )

    private class RecordingEngineFactory : LiteRtEngineFactory {
        val specs = mutableListOf<LiteRtConversationSpec>()
        val capabilities = mutableListOf<LocalModelRuntimeCapabilities>()

        override fun create(
            modelPath: String,
            backend: BackendChoice,
            cacheDir: File,
            capabilities: LocalModelRuntimeCapabilities,
        ): LiteRtEngineHandle {
            this.capabilities += capabilities
            return object : LiteRtEngineHandle {
                override fun initialize() = Unit

                override fun createConversation(config: LiteRtConversationSpec): LiteRtConversationHandle {
                    specs += config
                    return NoOpConversation
                }

                override fun close() = Unit
            }
        }
    }

    private object NoOpConversation : LiteRtConversationHandle {
        override fun sendMessageAsync(request: LocalModelRequest): Flow<String> = emptyFlow()

        @OptIn(ExperimentalApi::class)
        override fun benchmarkInfo(): BenchmarkInfo = error("not used")

        override fun cancelProcess() = Unit

        override fun close() = Unit
    }
}
