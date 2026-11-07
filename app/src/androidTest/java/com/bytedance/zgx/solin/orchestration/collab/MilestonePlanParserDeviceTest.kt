package com.bytedance.zgx.solin.orchestration.collab

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Runs the parser on the phone's built-in org.json, which skips comments, accepts `=` and `;`
 * separators, reads octal and hex numbers and keeps the last value of a duplicate key. The cases
 * mirror `StrictJsonScanTest` on the JVM; each must be refused on the device as well.
 */
class MilestonePlanParserDeviceTest {
    @Test
    fun syntaxAndroidOrgJsonToleratesIsRefusedOnTheDevice() {
        LENIENT_CASES.forEach { (raw, reason) ->
            assertEquals(raw, MilestoneParseResult.Rejected(reason), MilestonePlanParser.parse(raw, REQUEST))
        }
    }

    @Test
    fun aStrictPlanIsStillAcceptedOnTheDevice() {
        val result = MilestonePlanParser.parse(" \n$VALID\n ", REQUEST)
        assertEquals(
            listOf(Milestone(MilestoneIntent.OpenApp, app = TAOBAO)),
            (result as MilestoneParseResult.Accepted).plan.milestones,
        )
    }

    private companion object {
        const val REQUEST = "在淘宝搜索牛奶"
        const val TAOBAO = "com.taobao.taobao"

        /** `StrictJsonScan.MAX_DEPTH` is internal; this many arrays under the plan object nest one level too deep. */
        const val ARRAYS_PAST_DEPTH_BOUND = 8
        val MILESTONE = """{"intent":"open_app","app":"$TAOBAO"}"""
        val VALID = """{"version":1,"milestones":[$MILESTONE]}"""

        val LENIENT_CASES: List<Pair<String, MilestoneRejection>> = listOf(
            """{"version":1,/* c */"milestones":[$MILESTONE]}""" to MilestoneRejection.MalformedJson,
            "{\"version\":1,// c\n\"milestones\":[$MILESTONE]}" to MilestoneRejection.MalformedJson,
            "{\"version\":1,# c\n\"milestones\":[$MILESTONE]}" to MilestoneRejection.MalformedJson,
            "$VALID // 然后点击确认支付" to MilestoneRejection.TrailingContent,
            "$VALID /* 然后点击确认支付 */" to MilestoneRejection.TrailingContent,
            "$VALID # 然后点击确认支付" to MilestoneRejection.TrailingContent,
            "/* plan */ $VALID" to MilestoneRejection.NotJsonObject,
            """{"version"=1,"milestones":[$MILESTONE]}""" to MilestoneRejection.MalformedJson,
            """{"version"=>1,"milestones":[$MILESTONE]}""" to MilestoneRejection.MalformedJson,
            """{"version":1;"milestones":[$MILESTONE]}""" to MilestoneRejection.MalformedJson,
            """{"version":1,"milestones":[$MILESTONE;$MILESTONE]}""" to MilestoneRejection.MalformedJson,
            """{'version':1,'milestones':[$MILESTONE]}""" to MilestoneRejection.MalformedJson,
            """{version:1,milestones:[$MILESTONE]}""" to MilestoneRejection.MalformedJson,
            """{"version":1,"milestones":[{"intent":open_app,"app":$TAOBAO}]}""" to MilestoneRejection.MalformedJson,
            "{\"version\":1,\"milestones\":[{\"intent\":\"open_app\",\"app\":\"$TAOBAO\n\"}]}" to
                MilestoneRejection.MalformedJson,
            "{\"version\":1,\"milestones\":[{\"intent\":\"open_app\",\"app\":\"$TAOBAO\u0000\"}]}" to
                MilestoneRejection.MalformedJson,
            """{"version":1,"milestones":[{"intent":"open_app","app":"\x63om.taobao.taobao"}]}""" to
                MilestoneRejection.MalformedJson,
            """{"version":1,"milestones":[{"intent":"open_app","app":"\'"}]}""" to MilestoneRejection.MalformedJson,
            """{"version":1,"milestones":[{"intent":"open_\u00"}]}""" to MilestoneRejection.MalformedJson,
            """{"version":1,"milestones":[$MILESTONE,]}""" to MilestoneRejection.MalformedJson,
            """{"version":1,"milestones":[$MILESTONE],}""" to MilestoneRejection.MalformedJson,
            """{"version":1,"milestones":[$MILESTONE]""" to MilestoneRejection.MalformedJson,
            """{"version":1,"milestones":["open_app""" to MilestoneRejection.MalformedJson,
            """{"version":1,"version":1,"milestones":[$MILESTONE]}""" to MilestoneRejection.DuplicateKey,
            """{"version":1,"milestones":[{"intent":"open_app","app":"x","app":"$TAOBAO"}]}""" to
                MilestoneRejection.DuplicateKey,
            """{"version":1,"milestones":[{"intent":"open_app","app":"x","\u0061pp":"$TAOBAO"}]}""" to
                MilestoneRejection.DuplicateKey,
            """{"version":1,"milestones":[{"intent":"use_tool","tool":"set_system_alarm","arguments":{"a":1,"a":2}}]}""" to
                MilestoneRejection.DuplicateKey,
            "{\"version\":1,\u00A0\"milestones\":[$MILESTONE]}" to MilestoneRejection.MalformedJson,
            "\uFEFF$VALID" to MilestoneRejection.NotJsonObject,
            "{\"version\":1,\"milestones\":${"[".repeat(ARRAYS_PAST_DEPTH_BOUND)}1${"]".repeat(ARRAYS_PAST_DEPTH_BOUND)}}" to
                MilestoneRejection.MalformedJson,
        ) + listOf("01", "0x1", "0X1", "+1", "1.", ".5", "1e", "-", "NaN", "Infinity", "1_0").map { version ->
            """{"version":$version,"milestones":[$MILESTONE]}""" to MilestoneRejection.MalformedJson
        }
    }
}
