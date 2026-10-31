package com.bytedance.zgx.solin.data

import android.content.Context
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_REMOTE
import com.bytedance.zgx.solin.logging.solinD
import com.bytedance.zgx.solin.orchestration.DecisionModelRouting
import com.bytedance.zgx.solin.orchestration.DecisionModelRoutingSample
import com.bytedance.zgx.solin.orchestration.DecisionModelVerdict

private const val PREFS_NAME = "solin_decision_model_routing"
private const val KEY_MODE = "mode"
private const val SECRET_API_KEY = "decision_model_codiv_api_key"

/** Persisted choices for the Codiv OpenJev cloud/edge router. The API key lives in [SecretStore]. */
interface DecisionModelRoutingSettings {
    fun mode(): DecisionModelRouting
    fun setMode(mode: DecisionModelRouting)
    fun apiKey(): String?
    fun saveApiKey(key: String): Boolean
}

class SharedPreferencesDecisionModelRoutingSettings(
    context: Context,
    private val secretStore: SecretStore,
) : DecisionModelRoutingSettings {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun mode(): DecisionModelRouting = DecisionModelRouting.parse(prefs.getString(KEY_MODE, null))

    override fun setMode(mode: DecisionModelRouting) {
        prefs.edit().putString(KEY_MODE, mode.name).commit()
    }

    override fun apiKey(): String? = secretStore.loadString(SECRET_API_KEY).getOrNull()?.takeIf { it.isNotBlank() }

    override fun saveApiKey(key: String): Boolean = secretStore.saveString(SECRET_API_KEY, key.trim()).isSuccess
}

/** Trust Center view of the router. Counts cover this app session only. */
data class DecisionModelRoutingState(
    val mode: DecisionModelRouting,
    val keyConfigured: Boolean,
    val calls: Int,
    val unavailable: Int,
    val disagreedWithRules: Int,
    val applied: Int,
)

/**
 * Backs the Trust Center router setting. Off by default; Shadow and Active need a saved key, and
 * clearing the key turns routing off. [record] keeps per-session counts so Shadow can be judged
 * before switching to Active; nothing about the request is kept.
 */
class DecisionModelRoutingController(private val settings: DecisionModelRoutingSettings) {
    /** Guards the counters: Shadow samples arrive on a background thread while the UI reads [state]. */
    private val countsLock = Any()
    private var calls = 0
    private var unavailable = 0
    private var disagreed = 0
    private var applied = 0

    /** The mode in effect: Off whenever no key is saved. */
    fun effectiveMode(): DecisionModelRouting =
        if (settings.apiKey() == null) DecisionModelRouting.Off else settings.mode()

    fun apiKey(): String? = settings.apiKey()

    /** Thread-safe; the counts are one consistent snapshot. */
    fun state(): DecisionModelRoutingState {
        val mode = effectiveMode()
        val keyConfigured = settings.apiKey() != null
        return synchronized(countsLock) {
            DecisionModelRoutingState(
                mode = mode,
                keyConfigured = keyConfigured,
                calls = calls,
                unavailable = unavailable,
                disagreedWithRules = disagreed,
                applied = applied,
            )
        }
    }

    fun setMode(mode: DecisionModelRouting): DecisionModelRoutingState {
        if (mode == DecisionModelRouting.Off || settings.apiKey() != null) settings.setMode(mode)
        return state()
    }

    /** A blank key clears it and turns routing off. */
    fun saveApiKey(key: String): DecisionModelRoutingState {
        settings.saveApiKey(key)
        if (key.isBlank()) settings.setMode(DecisionModelRouting.Off)
        return state()
    }

    /**
     * Safe to call from any thread (Shadow samples arrive on a background executor). Logs only the
     * mode, rule reason and verdict; the sample never carries request text.
     */
    fun record(sample: DecisionModelRoutingSample) {
        val verdict = sample.verdict
        val disagrees = verdict is DecisionModelVerdict.Decided &&
            !sample.ruleReason.name.substringAfterLast('_').equals(verdict.placement.name, ignoreCase = true)
        synchronized(countsLock) {
            calls++
            when (verdict) {
                is DecisionModelVerdict.Unavailable -> unavailable++
                is DecisionModelVerdict.Decided -> {
                    if (disagrees) disagreed++
                    if (sample.applied) applied++
                }
            }
        }
        when (verdict) {
            is DecisionModelVerdict.Unavailable ->
                solinD(TAG_REMOTE, "decision-model ${sample.mode}: unavailable (${verdict.reason})")
            is DecisionModelVerdict.Decided -> {
                solinD(
                    TAG_REMOTE,
                    "decision-model ${sample.mode}: rule=${sample.ruleReason} model=${verdict.placement} " +
                        "confidence=${"%.3f".format(verdict.confidence)} applied=${sample.applied}",
                )
            }
        }
    }
}
