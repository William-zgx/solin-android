package com.bytedance.zgx.solin.data

import com.bytedance.zgx.solin.orchestration.DecisionModelRouting
import com.bytedance.zgx.solin.orchestration.DecisionModelRoutingSample
import com.bytedance.zgx.solin.orchestration.DecisionModelVerdict
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
import com.bytedance.zgx.solin.orchestration.RunPlacement
import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionModelRoutingControllerTest {
    private val settings = object : DecisionModelRoutingSettings {
        var storedMode = DecisionModelRouting.Off
        var key: String? = null
        override fun mode() = storedMode
        override fun setMode(mode: DecisionModelRouting) { storedMode = mode }
        override fun apiKey() = key
        override fun saveApiKey(key: String): Boolean { this.key = key.trim().ifBlank { null }; return true }
    }
    private val controller = DecisionModelRoutingController(settings)

    @Test
    fun routingNeedsAKeyAndClearingTheKeyTurnsItOff() {
        assertEquals(DecisionModelRouting.Off, controller.setMode(DecisionModelRouting.Active).mode)
        controller.saveApiKey("k")
        assertEquals(DecisionModelRouting.Shadow, controller.setMode(DecisionModelRouting.Shadow).mode)
        val cleared = controller.saveApiKey("")
        assertEquals(DecisionModelRouting.Off, cleared.mode)
        assertEquals(false, cleared.keyConfigured)
        settings.key = "k"
        assertEquals(DecisionModelRouting.Off, controller.effectiveMode())
    }

    @Test
    fun countsShadowOutcomesForThisSession() {
        controller.record(sample(PlacementReasonCode.AUTO_SIMPLE_LOCAL, DecisionModelVerdict.Decided(RunPlacement.Remote, 0.95)))
        controller.record(sample(PlacementReasonCode.AUTO_COMPLEX_REMOTE, DecisionModelVerdict.Decided(RunPlacement.Remote, 0.95)))
        controller.record(sample(PlacementReasonCode.AUTO_SIMPLE_LOCAL, DecisionModelVerdict.Unavailable("transport")))
        val state = controller.state()
        assertEquals(3, state.calls)
        assertEquals(1, state.unavailable)
        assertEquals(1, state.disagreedWithRules)
        assertEquals(0, state.applied)
    }

    private fun sample(rule: PlacementReasonCode, verdict: DecisionModelVerdict) =
        DecisionModelRoutingSample(DecisionModelRouting.Shadow, rule, verdict, applied = false)
}
