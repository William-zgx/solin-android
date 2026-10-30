package com.bytedance.zgx.solin.memory

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recall-quality fixture for the local memory ranker.
 *
 * Records are stored through the same parsers the chat path uses for explicit "记住…" / "remember …"
 * commands, so record types (UserFact vs Preference) and stored text prefixes match production.
 * Metrics:
 * - precision@1: fraction of positive queries whose top hit is an expected record.
 * - hit@3: fraction of positive queries with an expected record in the top 3 (the agent loop's topK).
 * - false-positive rate: fraction of unrelated queries that return any hit at all.
 * - noise@3: average number of unexpected records among the top 3 for positive queries.
 *
 * The asserted floors/ceilings are regression guards; the measured numbers are printed to stdout.
 */
class MemoryRecallQualityFixtureTest {
    @Test
    fun recallQualityMeetsFixtureFloors() {
        val metrics = measure(MemoryRepository())

        println(metrics.report())
        assertTrue("precision@1 ${metrics.precisionAt1}", metrics.precisionAt1 >= MIN_PRECISION_AT_1)
        assertTrue("hit@3 ${metrics.hitAt3}", metrics.hitAt3 >= MIN_HIT_AT_3)
        assertTrue("false-positive rate ${metrics.falsePositiveRate}", metrics.falsePositiveRate <= MAX_FALSE_POSITIVE_RATE)
        assertTrue("noise@3 ${metrics.noiseAt3}", metrics.noiseAt3 <= MAX_NOISE_AT_3)
    }

    data class RecallMetrics(
        val precisionAt1: Double,
        val hitAt3: Double,
        val falsePositiveRate: Double,
        val noiseAt3: Double,
        val details: List<String>,
    ) {
        fun report(): String =
            buildString {
                appendLine(
                    "memory-recall-fixture records=${RECORDS.size} positives=${POSITIVE_QUERIES.size} " +
                        "unrelated=${UNRELATED_QUERIES.size}",
                )
                appendLine(
                    "precision@1=%.3f hit@3=%.3f falsePositiveRate=%.3f noise@3=%.3f"
                        .format(precisionAt1, hitAt3, falsePositiveRate, noiseAt3),
                )
                details.forEach(::appendLine)
            }
    }

    data class FixtureRecord(val label: String, val text: String, val conversation: Boolean = false)

    data class FixtureQuery(val query: String, val expected: Set<String>)

    companion object {
        // Floors/ceilings guard against regressions; see the class kdoc for metric definitions.
        // Baseline (single-char CJK unigrams, max(lexical, semantic) fusion, no minimum score):
        // precision@1=0.929 hit@3=0.976 falsePositiveRate=0.389 noise@3=0.690
        const val MIN_PRECISION_AT_1 = 0.92
        const val MIN_HIT_AT_3 = 0.97
        const val MAX_FALSE_POSITIVE_RATE = 0.39
        const val MAX_NOISE_AT_3 = 0.70

        val RECORDS = listOf(
            FixtureRecord("home", "我家在北京市朝阳区望京西园三区"),
            FixtureRecord("work", "我的公司地址是海淀区中关村软件园二期"),
            FixtureRecord("wife_phone", "我老婆的电话是13812345678"),
            FixtureRecord("wife_name", "我老婆叫林小雨"),
            FixtureRecord("mom_phone", "我妈妈的手机号是13900001111"),
            FixtureRecord("peanut", "我对花生过敏"),
            FixtureRecord("penicillin", "我对青霉素过敏"),
            FixtureRecord("coffee", "我喜欢喝不加糖的美式咖啡"),
            FixtureRecord("spicy", "我不吃辣"),
            FixtureRecord("birthday", "我的生日是5月20日"),
            FixtureRecord("blood", "我的血型是O型"),
            FixtureRecord("plate", "我的车牌号是京A12345"),
            FixtureRecord("dog", "我的狗叫豆豆"),
            FixtureRecord("son_school", "我儿子在海淀实验小学上三年级"),
            FixtureRecord("gym", "我每周二和周四晚上去健身房"),
            FixtureRecord("wakeup", "我每天早上七点起床"),
            FixtureRecord("commute", "我通常坐地铁10号线上班"),
            FixtureRecord("reply_concise", "回答尽量简洁"),
            FixtureRecord("reply_chinese", "请用中文回答"),
            FixtureRecord("music", "我喜欢听周杰伦的歌"),
            FixtureRecord("dentist", "my dentist is Dr. Chen at Bright Smile Clinic"),
            FixtureRecord("aisle", "I prefer aisle seats on flights"),
            FixtureRecord("wifi", "我家的WiFi密码是sunny2024"),
            FixtureRecord("laptop", "我的电脑是MacBook Pro 14寸"),
            FixtureRecord("shoe", "我的鞋码是42"),
            FixtureRecord("hometown", "我老家是湖南长沙"),
            FixtureRecord("allergy_med", "我的过敏药是氯雷他定"),
            FixtureRecord("shellfish", "I am allergic to shellfish"),
            FixtureRecord("wife_en", "my wife's name is Emily"),
            FixtureRecord("live_en", "I live in Shanghai Pudong"),
            FixtureRecord("cuisine", "my favorite cuisine is Japanese food"),
            FixtureRecord("reading", "我在读《三体》第二部"),
            FixtureRecord("meeting", "每周一上午十点有组会"),
            FixtureRecord("tea", "我不喝绿茶，只喝红茶"),
            FixtureRecord("weight", "我的目标体重是65公斤"),
            FixtureRecord("parking", "我的车停在公司B2层"),
            FixtureRecord("japanese", "我在学日语"),
            FixtureRecord("daughter_milk", "我女儿对牛奶过敏"),
            FixtureRecord("conv_train", "用户：明天帮我查一下去上海的高铁", conversation = true),
            FixtureRecord("conv_hike", "用户：周末想去爬香山", conversation = true),
            FixtureRecord("conv_scifi", "用户：推荐几本科幻小说", conversation = true),
            FixtureRecord("conv_kotlin", "用户：Kotlin 协程怎么取消", conversation = true),
            FixtureRecord("conv_coffee", "用户：我昨天喝了太多咖啡睡不着", conversation = true),
            FixtureRecord("conv_misc", "用户：我的手机是不是该换了", conversation = true),
        )

        val POSITIVE_QUERIES = listOf(
            FixtureQuery("导航回家，我家在哪", setOf("home")),
            FixtureQuery("我家住哪里", setOf("home")),
            FixtureQuery("公司地址是什么", setOf("work")),
            FixtureQuery("给老婆打电话", setOf("wife_phone", "wife_name")),
            FixtureQuery("老婆的电话多少", setOf("wife_phone")),
            FixtureQuery("我老婆叫什么名字", setOf("wife_name")),
            FixtureQuery("妈妈手机号多少", setOf("mom_phone")),
            FixtureQuery("我对什么过敏", setOf("peanut", "penicillin", "daughter_milk", "allergy_med")),
            FixtureQuery("我能吃花生酱吗", setOf("peanut")),
            FixtureQuery("我能吃辣吗", setOf("spicy")),
            FixtureQuery("帮我点杯咖啡", setOf("coffee")),
            FixtureQuery("我生日是哪天", setOf("birthday")),
            FixtureQuery("我是什么血型", setOf("blood")),
            FixtureQuery("我的车牌号多少", setOf("plate")),
            FixtureQuery("我的狗叫什么", setOf("dog")),
            FixtureQuery("儿子在哪个小学", setOf("son_school")),
            FixtureQuery("我哪天去健身房", setOf("gym")),
            FixtureQuery("明天几点叫我起床", setOf("wakeup")),
            FixtureQuery("我坐哪条地铁上班", setOf("commute")),
            FixtureQuery("回答简短一点", setOf("reply_concise")),
            FixtureQuery("用什么语言回答", setOf("reply_chinese")),
            FixtureQuery("放一首周杰伦", setOf("music")),
            FixtureQuery("who is my dentist", setOf("dentist")),
            FixtureQuery("book flight seats for me", setOf("aisle")),
            FixtureQuery("WiFi密码是多少", setOf("wifi")),
            FixtureQuery("我的电脑型号", setOf("laptop")),
            FixtureQuery("我的鞋码多大", setOf("shoe")),
            FixtureQuery("我老家在哪", setOf("hometown")),
            FixtureQuery("过敏了吃什么药", setOf("allergy_med")),
            FixtureQuery("what am I allergic to", setOf("shellfish")),
            FixtureQuery("what is my wife's name", setOf("wife_en")),
            FixtureQuery("where do I live", setOf("live_en")),
            FixtureQuery("what food do I like", setOf("cuisine")),
            FixtureQuery("三体看到第几部了", setOf("reading")),
            FixtureQuery("组会几点开始", setOf("meeting")),
            FixtureQuery("我喝什么茶", setOf("tea")),
            FixtureQuery("我的目标体重", setOf("weight")),
            FixtureQuery("车停在哪", setOf("parking")),
            FixtureQuery("我在学什么外语", setOf("japanese")),
            FixtureQuery("女儿能喝牛奶吗", setOf("daughter_milk")),
            FixtureQuery("周末去爬香山", setOf("conv_hike")),
            FixtureQuery("Kotlin 协程取消", setOf("conv_kotlin")),
        )

        val UNRELATED_QUERIES = listOf(
            "今天天气怎么样",
            "讲个笑话",
            "帮我写一首关于秋天的诗",
            "一加一等于几",
            "翻译一下hello world",
            "现在几点了",
            "打开蓝牙",
            "什么是量子计算",
            "推荐一部电影",
            "how tall is mount everest",
            "explain recursion",
            "帮我搜索附近的餐厅",
            "中国的首都是哪里",
            "今天股市怎么样",
            "我想学做蛋糕",
            "这是什么意思",
            "我的问题是什么",
            "给我讲讲历史",
        )

        fun indexFixture(repository: MemoryRepository): Map<String, String> =
            RECORDS.associate { record ->
                if (record.conversation) {
                    repository.index(record.label, record.text)
                    return@associate record.label to record.label
                }
                val command = "记住${record.text}"
                val fact = explicitUserFactFrom(command)
                val id = if (fact != null) {
                    explicitUserFactRecordId(fact).also { id -> repository.indexUserFact(id, fact) }
                } else {
                    val preference = requireNotNull(explicitUserPreferenceFrom(command)) { record.text }
                    explicitUserPreferenceRecordId(preference).also { id -> repository.indexPreference(id, preference) }
                }
                id to record.label
            }

        fun measure(repository: MemoryRepository): RecallMetrics {
            val labelsById = indexFixture(repository)
            require(labelsById.size == RECORDS.size) { "fixture records must not conflict-replace each other" }
            val details = mutableListOf<String>()
            var top1 = 0
            var top3 = 0
            var noise = 0
            POSITIVE_QUERIES.forEach { fixtureQuery ->
                val labels = repository.search(fixtureQuery.query, topK = 3).map { hit -> labelsById[hit.id] ?: hit.id }
                if (labels.firstOrNull() in fixtureQuery.expected) top1++
                if (labels.any { it in fixtureQuery.expected }) top3++
                noise += labels.count { it !in fixtureQuery.expected }
                val mark = when {
                    labels.firstOrNull() in fixtureQuery.expected -> "OK  "
                    labels.any { it in fixtureQuery.expected } -> "TOP3"
                    else -> "MISS"
                }
                details += "$mark ${fixtureQuery.query} -> $labels expected=${fixtureQuery.expected}"
            }
            var falsePositives = 0
            UNRELATED_QUERIES.forEach { query ->
                val labels = repository.search(query, topK = 3).map { hit -> labelsById[hit.id] ?: hit.id }
                if (labels.isNotEmpty()) falsePositives++
                details += "${if (labels.isEmpty()) "NONE" else "FP  "} $query -> $labels"
            }
            return RecallMetrics(
                precisionAt1 = top1.toDouble() / POSITIVE_QUERIES.size,
                hitAt3 = top3.toDouble() / POSITIVE_QUERIES.size,
                falsePositiveRate = falsePositives.toDouble() / UNRELATED_QUERIES.size,
                noiseAt3 = noise.toDouble() / POSITIVE_QUERIES.size,
                details = details,
            )
        }
    }
}
