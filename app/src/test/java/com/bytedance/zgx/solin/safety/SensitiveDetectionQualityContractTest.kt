package com.bytedance.zgx.solin.safety

import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDetectionQualityContractTest {
    private val policy = SafetyPolicy()

    @Test
    fun recallMeetsPrivacyFloor() {
        val missed = sensitiveSamples.filterNot(policy::containsSensitivePersonalOrSecretContent)

        assertTrue(
            "Missed sensitive samples: ${missed.joinToString()}",
            missed.size <= sensitiveSamples.size - minimumSensitiveHits,
        )
    }

    @Test
    fun falsePositiveRateStaysUnderCeiling() {
        val wronglyFlagged = benignSamples.filter(policy::containsSensitivePersonalOrSecretContent)

        assertTrue(
            "Wrongly flagged benign samples: ${wronglyFlagged.joinToString()}",
            wronglyFlagged.isEmpty(),
        )
    }

    @Test
    fun baselineCorpusCoversBothClasses() {
        assertTrue("corpus must contain sensitive samples", sensitiveSamples.isNotEmpty())
        assertTrue("corpus must contain benign samples", benignSamples.isNotEmpty())
    }

    @Test
    fun contractHoldsEndToEnd() {
        recallMeetsPrivacyFloor()
        falsePositiveRateStaysUnderCeiling()
    }

    private companion object {
        const val minimumSensitiveHits = 15

        val sensitiveSamples = listOf(
            "我的邮箱是 zhang.san@example.com，帮我写封自我介绍",
            "reach me at john_doe+work@company.co.uk",
            "我的手机号是 13812345678，记一下",
            "call me on +44 7700 900123 tomorrow",
            "联系电话 010-8888-6666",
            "我的身份证号 11010119900307123X",
            "这是我的 key：sk-abcdef0123456789ABCDEF",
            "AWS key AKIAIOSFODNN7EXAMPLE 帮我检查",
            "password: hunter2supersecret",
            "api_key = 'Zm9vYmFyMTIzNDU2Nzg5'",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIE...",
            "我自己的银行卡密码忘了怎么办",
            "my personal employee id is needed for the form",
            "我本人最近确诊了抑郁，想找心理咨询",
            "find a bankruptcy lawyer near me",
            "附近有没有可以做孕检的医院",
        )

        val benignSamples = listOf(
            "帮我把这段话翻译成英文",
            "对比一下 2020 2021 2022 三年的营收趋势",
            "会议安排在 2026-06-09T14:30:00+08:00",
            "订单号 100002345 已经发货了吗",
            "升级到 version 2.10.4 之后崩溃了",
            "解释一下 HTTP 和 HTTPS 的区别",
            "写一个快速排序的 Kotlin 示例",
            "推荐几本科幻小说",
            "今天北京天气怎么样",
            "summarize the meeting notes I will paste next",
            "what is the capital of France",
            "give me a vegetarian dinner recipe",
        )
    }
}
