package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.MobileActionPlanner
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Filler words are stripped only at segment edges, never inside a word: 目的地, 申请签证, 吗啡,
 * 请客 and 麻烦事 must survive both the rule parser and the provider keywordizer.
 */
class WebSearchQueryCleaningTest {
    private val planner = MobileActionPlanner()

    @Test
    fun ruleParserQueryKeepsWordsThatContainFillerCharacters() {
        val cases = listOf(
            "帮我搜一下 Kotlin 协程最新用法" to "Kotlin 协程最新用法",
            "网上查一下 Kotlin Flow debounce" to "Kotlin Flow debounce",
            "你能帮我搜一下 Kotlin 吗？" to "Kotlin",
            "北京天气怎么样" to "北京天气",
            "搜一下目的地" to "目的地",
            "搜一下申请签证需要什么材料" to "申请签证需要什么材料",
            "搜一下吗啡的副作用" to "吗啡的副作用",
            "搜一下请客吃饭的礼仪" to "请客吃饭的礼仪",
            "请帮我搜一下麻烦事怎么处理" to "麻烦事怎么处理",
            "麻烦你帮我搜一下可以吃的野菜有哪些" to "可以吃的野菜",
            "搜一下能不能带充电宝上飞机" to "能不能带充电宝上飞机",
            "百度一下 一下子是什么意思" to "一下子是什么意思",
            "搜一下 Python 是什么" to "Python",
            "请问能不能搜一下上海迪士尼门票多少钱呢" to "上海迪士尼门票多少钱",
        )
        cases.forEach { (input, expected) ->
            val draft = planner.plan(input).draft
            assertEquals(input, MobileActionFunctions.WEB_SEARCH, draft?.functionName)
            assertEquals(input, expected, draft?.parameters?.get("query"))
        }
    }

    @Test
    fun providerKeywordsStripOnlyEdgeFiller() {
        val cases = listOf(
            "目前中国和美国最新最火热的AI模型分别是什么" to "中国和美国最新最火热 AI 模型",
            "目的地" to "目的地",
            "旅行目的" to "旅行目的",
            "申请签证流程" to "申请签证流程",
            "吗啡" to "吗啡",
            "现在美元汇率是多少" to "美元汇率是",
            "Kotlin 协程是什么" to "Kotlin 协程",
            "OpenAI latest model release" to "OpenAI latest model release",
            "如何" to "如何",
        )
        cases.forEach { (query, expected) ->
            assertEquals(query, expected, query.optimizedWebSearchKeywords())
        }
    }

    @Test
    fun weatherLocationsStripOnlyEdgeFiller() {
        val cases = listOf(
            "北京今天下雨了吗？" to listOf("北京"),
            "上海明天会下雨吗" to listOf("上海"),
            "帮我查一下温州天气" to listOf("温州"),
            "北京和上海哪个更热" to listOf("北京", "上海"),
            "北京比上海热吗" to listOf("北京", "上海"),
        )
        cases.forEach { (query, expected) ->
            assertEquals(query, expected, query.weatherLocationQueries())
        }
    }

    @Test
    fun edgeFillersIterateAndRespectPolitePrefixes() {
        val fillers = CjkEdgeFillers(leading = listOf("帮我", "搜一下"), trailing = listOf("吗", "呢"))
        assertEquals("天气", fillers.strip("请帮我搜一下天气吗呢"))
        assertEquals("请客", fillers.strip("请客"))
        assertEquals("麻烦事", fillers.strip("麻烦事"))
        assertEquals("吗啡", fillers.strip("吗啡"))
        assertEquals("", fillers.strip("帮我搜一下"))
    }
}
