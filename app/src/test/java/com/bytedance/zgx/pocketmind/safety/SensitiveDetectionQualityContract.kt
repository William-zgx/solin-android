package com.bytedance.zgx.pocketmind.safety

/**
 * Quantitative quality guardrail for outbound sensitive-content detection (P3).
 *
 * Mirrors `MemoryQualityContract`: instead of leaving the [SafetyPolicy] regexes to drift
 * silently whenever they are tightened or loosened, this contract pins a labeled baseline corpus
 * of realistic queries and asserts measurable precision/recall floors against it. Any future edit
 * to the detection patterns that regresses past these thresholds will fail the unit test rather
 * than ship a silent over-block (privacy theatre / annoyance) or under-block (privacy leak).
 *
 * The corpus deliberately distinguishes two failure modes:
 * - **Recall** protects the privacy floor: real sensitive payloads must keep being flagged.
 * - **Precision** protects usability: ordinary, non-sensitive queries must not be flagged, or
 *   users learn to click through every disclosure and the tiered policy loses its value.
 */
object SensitiveDetectionQualityContract {
    /** Of the truly-sensitive samples, at least this share must be detected (privacy floor). */
    const val RECALL_TARGET_PERCENT = 90

    /** Of the benign samples, at most this share may be wrongly flagged (false-positive ceiling). */
    const val FALSE_POSITIVE_MAX_PERCENT = 5

    /**
     * A single labeled query in the baseline corpus.
     *
     * @property text the query as a user might type it.
     * @property isSensitive ground-truth label: true iff the query SHOULD trigger a sensitive hit.
     * @property note short rationale, kept for human review when a sample's label is debated.
     */
    data class Sample(
        val text: String,
        val isSensitive: Boolean,
        val note: String = "",
    )

    /** Result of scoring the [SafetyPolicy] against [baselineCorpus]. */
    data class QualityReport(
        val truePositives: Int,
        val falseNegatives: Int,
        val trueNegatives: Int,
        val falsePositives: Int,
        val falseNegativeSamples: List<Sample>,
        val falsePositiveSamples: List<Sample>,
    ) {
        val sensitiveTotal: Int get() = truePositives + falseNegatives
        val benignTotal: Int get() = trueNegatives + falsePositives

        /** Detected-sensitive / actually-sensitive, in whole percent. 100 when there are none. */
        val recallPercent: Int
            get() = if (sensitiveTotal == 0) 100 else truePositives * 100 / sensitiveTotal

        /** Wrongly-flagged-benign / actually-benign, in whole percent. 0 when there are none. */
        val falsePositivePercent: Int
            get() = if (benignTotal == 0) 0 else falsePositives * 100 / benignTotal

        val meetsRecallTarget: Boolean get() = recallPercent >= RECALL_TARGET_PERCENT
        val meetsFalsePositiveCeiling: Boolean
            get() = falsePositivePercent <= FALSE_POSITIVE_MAX_PERCENT
        val meetsContract: Boolean get() = meetsRecallTarget && meetsFalsePositiveCeiling
    }

    /**
     * Labeled baseline distribution. Sensitive samples cover every [SafetyCategory]; benign
     * samples deliberately include the historically tricky look-alikes (year lists, ISO
     * timestamps, order ids, version strings) that earlier, looser patterns mis-flagged.
     */
    val baselineCorpus: List<Sample> = listOf(
        // --- Sensitive: structured PII ---
        Sample("我的邮箱是 zhang.san@example.com，帮我写封自我介绍", true, "email"),
        Sample("reach me at john_doe+work@company.co.uk", true, "email"),
        Sample("我的手机号是 13812345678，记一下", true, "cn mobile"),
        Sample("call me on +44 7700 900123 tomorrow", true, "intl phone"),
        Sample("联系电话 010-8888-6666", true, "grouped phone"),
        Sample("我的身份证号 11010119900307123X", true, "cn id"),
        // --- Sensitive: secrets / credentials ---
        Sample("这是我的 key：sk-abcdef0123456789ABCDEF", true, "openai-style token"),
        Sample("AWS key AKIAIOSFODNN7EXAMPLE 帮我检查", true, "cloud secret"),
        Sample("password: hunter2supersecret", true, "secret assignment"),
        Sample("api_key = 'Zm9vYmFyMTIzNDU2Nzg5'", true, "secret assignment"),
        Sample("-----BEGIN RSA PRIVATE KEY-----\nMIIE...", true, "private key block"),
        // --- Sensitive: personal-context keywords / sensitive domains ---
        Sample("我自己的银行卡密码忘了怎么办", true, "personal identity"),
        Sample("my personal employee id is needed for the form", true, "personal identity"),
        Sample("我本人最近确诊了抑郁，想找心理咨询", true, "sensitive domain"),
        Sample("find a bankruptcy lawyer near me", true, "sensitive domain (location)"),
        Sample("附近有没有可以做孕检的医院", true, "sensitive domain (location)"),
        // --- Benign: ordinary requests that must NOT be flagged ---
        Sample("帮我把这段话翻译成英文", false, "plain request"),
        Sample("对比一下 2020 2021 2022 三年的营收趋势", false, "year list, not a phone"),
        Sample("会议安排在 2026-06-09T14:30:00+08:00", false, "iso datetime, not a phone"),
        Sample("订单号 100002345 已经发货了吗", false, "order id, not sensitive"),
        Sample("升级到 version 2.10.4 之后崩溃了", false, "version string"),
        Sample("解释一下 HTTP 和 HTTPS 的区别", false, "tech term"),
        Sample("写一个快速排序的 Kotlin 示例", false, "coding request"),
        Sample("推荐几本科幻小说", false, "recommendation"),
        Sample("今天北京天气怎么样", false, "weather, public location"),
        Sample("summarize the meeting notes I will paste next", false, "plain request"),
        Sample("what is the capital of France", false, "general knowledge"),
        Sample("give me a vegetarian dinner recipe", false, "recipe"),
    )

    /**
     * Scores [policy] against [corpus], returning a [QualityReport]. Pure and deterministic so it
     * can back both the unit-test guardrail and any future in-app diagnostics screen.
     */
    fun evaluate(
        policy: SafetyPolicy = SafetyPolicy(),
        corpus: List<Sample> = baselineCorpus,
    ): QualityReport {
        var truePositives = 0
        var falseNegatives = 0
        var trueNegatives = 0
        var falsePositives = 0
        val falseNegativeSamples = mutableListOf<Sample>()
        val falsePositiveSamples = mutableListOf<Sample>()

        for (sample in corpus) {
            val flagged = policy.detectSensitiveCategories(sample.text).isNotEmpty()
            when {
                sample.isSensitive && flagged -> truePositives++
                sample.isSensitive && !flagged -> {
                    falseNegatives++
                    falseNegativeSamples += sample
                }
                !sample.isSensitive && flagged -> {
                    falsePositives++
                    falsePositiveSamples += sample
                }
                else -> trueNegatives++
            }
        }

        return QualityReport(
            truePositives = truePositives,
            falseNegatives = falseNegatives,
            trueNegatives = trueNegatives,
            falsePositives = falsePositives,
            falseNegativeSamples = falseNegativeSamples,
            falsePositiveSamples = falsePositiveSamples,
        )
    }
}
