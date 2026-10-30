package com.bytedance.zgx.solin.action

import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.skill.SkillStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Table-driven intent parsing for dial / SMS / media / calculator / settings / translation. */
class AssistantIntentParsersTest {
    private val planner = MobileActionPlanner()
    private val skills = BuiltInSkillRuntime()

    private fun draft(input: String): ActionDraft? = planner.plan(input).draft

    private fun assertDraft(input: String, functionName: String, parameters: Map<String, String>) {
        val draft = draft(input)
        assertEquals(input, functionName, draft?.functionName)
        assertEquals(input, parameters, draft?.parameters)
        val skillPlan = skills.plan(input)
        assertEquals("skill $input", functionName, skillPlan?.steps?.singleOrNull()?.let { step ->
            (step as? SkillStep.ToolStep)?.request?.toolName
        })
    }

    @Test
    fun phoneCallNumbersBecomeDialDraftsAndNamesLookUpContactsFirst() {
        val cases = listOf(
            "打电话给10086" to (MobileActionFunctions.DIAL_PHONE_NUMBER to mapOf("phoneNumber" to "10086")),
            "拨打 138-0013-8000" to (MobileActionFunctions.DIAL_PHONE_NUMBER to mapOf("phoneNumber" to "13800138000")),
            "帮我拨打 (021) 6666 8888" to (MobileActionFunctions.DIAL_PHONE_NUMBER to mapOf("phoneNumber" to "02166668888")),
            "给10086打个电话" to (MobileActionFunctions.DIAL_PHONE_NUMBER to mapOf("phoneNumber" to "10086")),
            "拨号 +86 138 0013 8000" to (MobileActionFunctions.DIAL_PHONE_NUMBER to mapOf("phoneNumber" to "+8613800138000")),
            "call 10086" to (MobileActionFunctions.DIAL_PHONE_NUMBER to mapOf("phoneNumber" to "10086")),
            "打电话给妈妈" to (MobileActionFunctions.QUERY_CONTACTS to mapOf("query" to "妈妈")),
            "给张三打电话" to (MobileActionFunctions.QUERY_CONTACTS to mapOf("query" to "张三")),
        )
        cases.forEach { (input, expected) -> assertDraft(input, expected.first, expected.second) }
        assertTrue(draft("打电话给10086")!!.requiresConfirmation)
    }

    @Test
    fun smsDraftsKeepUserBodyAndNeverSend() {
        val cases = listOf(
            "发短信给10086说查询话费" to mapOf("phoneNumber" to "10086", "body" to "查询话费"),
            "给10086发短信：CXLL" to mapOf("phoneNumber" to "10086", "body" to "CXLL"),
            "给 138 0013 8000 发条短信 我晚点到，你先吃吧？" to
                mapOf("phoneNumber" to "13800138000", "body" to "我晚点到，你先吃吧？"),
            "发短信给10086" to mapOf("phoneNumber" to "10086"),
            "text 10086: hello there" to mapOf("phoneNumber" to "10086", "body" to "hello there"),
        )
        cases.forEach { (input, parameters) -> assertDraft(input, MobileActionFunctions.COMPOSE_SMS, parameters) }
        assertDraft("给妈妈发短信说我到了", MobileActionFunctions.QUERY_CONTACTS, mapOf("query" to "妈妈"))
    }

    @Test
    fun phoneAndSmsFailClosedOnNegationDiscussionAndUnsafeNumbers() {
        val noDialOrSms = listOf(
            "不要打电话给10086",
            "怎么给10086打电话",
            "打电话给10086要钱吗",
            "拨打*#06#",
            "拨打 10086,,123",
            "打电话给我",
            "提醒我明天给妈妈打电话",
            "给妈妈打电话说我晚点回家",
            "拨号键盘",
            "呼叫转移",
            "不要给10086发短信",
            "短信怎么发",
        )
        noDialOrSms.forEach { input ->
            val functionName = draft(input)?.functionName
            assertTrue(
                "$input -> $functionName",
                functionName != MobileActionFunctions.DIAL_PHONE_NUMBER &&
                    functionName != MobileActionFunctions.COMPOSE_SMS &&
                    functionName != MobileActionFunctions.QUERY_CONTACTS,
            )
        }
    }

    @Test
    fun mediaCommandsRequireWholeInputMatch() {
        val cases = listOf(
            "下一首" to "next",
            "播放下一首" to "next",
            "切歌" to "next",
            "上一首" to "previous",
            "暂停音乐" to "pause",
            "请暂停播放" to "pause",
            "继续播放" to "play",
            "播放音乐" to "play",
            "放首歌吧" to "play",
            "停止播放" to "stop",
            "next song" to "next",
            "Pause the music" to "pause",
        )
        cases.forEach { (input, command) ->
            assertDraft(input, MobileActionFunctions.CONTROL_MEDIA, mapOf("command" to command))
        }
        listOf("暂停", "播放周杰伦的歌", "下一首歌叫什么", "不要暂停音乐", "暂停音乐的方法").forEach { input ->
            assertTrue(input, draft(input)?.functionName != MobileActionFunctions.CONTROL_MEDIA)
        }
    }

    @Test
    fun calculatorCanonicalizesArithmeticAndUnitConversions() {
        val cases = listOf(
            "23*17+5" to "23*17+5",
            "23×17＋5等于多少" to "23*17+5",
            "计算 (3+4)*5" to "(3+4)*5",
            "算一下100除以8" to "100/8",
            "三加五等于几" to "3+5",
            "12乘以12是多少？" to "12*12",
            "2的10次方等于多少" to "2^10",
            "1/3=" to "1/3",
            "100-37等于多少" to "100-37",
            "3.5公里等于多少米" to "3.5 km to m",
            "100华氏度是多少摄氏度" to "100 f to c",
            "1英寸多少厘米" to "1 in to cm",
            "两斤是多少克" to "2 jin to g",
            "把5英里换算成公里" to "5 mi to km",
            "convert 100 km to miles" to "100 km to mi",
            "37 celsius in fahrenheit" to "37 c to f",
        )
        cases.forEach { (input, expression) ->
            assertDraft(input, MobileActionFunctions.CALCULATE, mapOf("expression" to expression))
            assertEquals(input, false, draft(input)?.requiresConfirmation)
        }
    }

    @Test
    fun calculatorIgnoresDatesRangesPhoneNumbersAndNonsense() {
        listOf(
            "2026-06-14",
            "2026/06/14",
            "3-5个人",
            "+8613800138000",
            "100等于多少",
            "1公斤等于多少米",
            "今天气温多少度",
            "1/0等于多少",
            "不要算23*17",
        ).forEach { input ->
            assertTrue(input, draft(input)?.functionName != MobileActionFunctions.CALCULATE)
        }
    }

    @Test
    fun doNotDisturbVolumeAndRingerOpenSettingsPages() {
        val cases = listOf(
            "打开勿扰模式" to SystemSettingsTargets.DO_NOT_DISTURB,
            "开启免打扰" to SystemSettingsTargets.DO_NOT_DISTURB,
            "open do not disturb settings" to SystemSettingsTargets.DO_NOT_DISTURB,
            "调大音量" to SystemSettingsTargets.VOLUME,
            "打开音量面板" to SystemSettingsTargets.VOLUME,
            "打开音量设置" to SystemSettingsTargets.SOUND,
            "把手机调成静音模式" to SystemSettingsTargets.SOUND,
            "打开铃声设置" to SystemSettingsTargets.SOUND,
        )
        cases.forEach { (input, target) ->
            assertDraft(input, MobileActionFunctions.OPEN_SYSTEM_SETTINGS, mapOf("target" to target))
        }
        listOf("勿扰模式是什么", "不要打开勿扰模式").forEach { input ->
            assertTrue(input, draft(input)?.functionName != MobileActionFunctions.OPEN_SYSTEM_SETTINGS)
        }
    }

    @Test
    fun translationRequestsStayChatTurnsWithAnInstruction() {
        val cases = listOf(
            "把'提醒我明天9点开会'翻译成英文" to ("提醒我明天9点开会" to "英文"),
            "帮我把这句话翻译成日语：明天见" to ("明天见" to "日文"),
            "翻译成英文：打电话给10086" to ("打电话给10086" to "英文"),
            "你好用法语怎么说" to ("你好" to "法文"),
            "translate good morning into Chinese" to ("good morning" to "中文"),
            "翻译一下：How are you?" to ("How are you?" to null),
        )
        cases.forEach { (input, expected) ->
            val parsed = TranslationIntentParser.parse(input)
            assertEquals(input, expected.first, parsed?.text)
            assertEquals(input, expected.second, parsed?.targetLanguage)
            assertTrue(input, parsed!!.chatInstruction().endsWith(expected.first))
            assertNull(input, draft(input))
            assertNull(input, skills.plan(input))
            assertEquals(input, false, planner.isLikelyAction(input))
        }
        listOf("翻译软件哪个好", "不要翻译", "翻译成英文").forEach { input ->
            assertNull(input, TranslationIntentParser.parse(input))
        }
    }
}
