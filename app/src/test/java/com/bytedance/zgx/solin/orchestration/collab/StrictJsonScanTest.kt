package com.bytedance.zgx.solin.orchestration.collab

import com.bytedance.zgx.solin.common.StrictJsonRejection
import com.bytedance.zgx.solin.common.StrictJsonScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan is tested directly, so these cases fail closed whichever org.json is on the classpath.
 * `MilestonePlanParserDeviceTest` (androidTest) runs the same cases on the phone's lenient org.json.
 */
class StrictJsonScanTest {
    @Test
    fun syntaxAndroidOrgJsonToleratesIsRefusedBeforeOrgJsonRuns() {
        LENIENT_CASES.forEach { (raw, reason) ->
            assertEquals(raw, reason.toStrictJsonRejection(), StrictJsonScan.firstViolation(raw))
            assertEquals(raw, MilestoneParseResult.Rejected(reason), MilestonePlanParser.parse(raw, REQUEST))
        }
    }

    @Test
    fun everyStrictJsonFormIsAccepted() {
        val maxNesting = "{\"a\":" + "[".repeat(StrictJsonScan.MAX_DEPTH - 1) + "1" + "]".repeat(StrictJsonScan.MAX_DEPTH - 1) + "}"
        listOf(
            VALID,
            " \t\r\n$VALID \t\r\n",
            "{}",
            """{"s":"\" \\ \/ \b \f \n \r \t \u00e9 \uD83D\uDE00 牛奶","n":[0,-0,12,-12.5,1e3,1E+3,2.5e-3]}""",
            """{"l":[true,false,null],"o":{},"a":[]}""",
            """{"a":{"a":{"a":1}},"b":[{"a":1},{"a":2}]}""",
            maxNesting
        ).forEach { raw -> assertNull(raw, StrictJsonScan.firstViolation(raw)) }
        assertTrue(MilestonePlanParser.parse(" \n$VALID\n ", REQUEST) is MilestoneParseResult.Accepted)
    }

    @Test
    fun theDuplicateKeyRejectionDoesNotDependOnTheJvmOrgJson() {
        val lastValueIsValid = """{"version":1,"milestones":[{"intent":"open_app","app":"x","app":"$TAOBAO"}]}"""
        assertEquals(StrictJsonRejection.DuplicateKey, StrictJsonScan.firstViolation(lastValueIsValid))
        assertEquals(
            MilestoneParseResult.Rejected(MilestoneRejection.DuplicateKey),
            MilestonePlanParser.parse(lastValueIsValid, REQUEST)
        )
    }

    private fun MilestoneRejection.toStrictJsonRejection(): StrictJsonRejection = when (this) {
        MilestoneRejection.NotJsonObject -> StrictJsonRejection.NotJsonObject
        MilestoneRejection.MalformedJson -> StrictJsonRejection.MalformedJson
        MilestoneRejection.DuplicateKey -> StrictJsonRejection.DuplicateKey
        MilestoneRejection.TrailingContent -> StrictJsonRejection.TrailingContent
        else -> error("Not a strict-JSON syntax rejection: $this")
    }

    private companion object {
        const val REQUEST = "在淘宝搜索牛奶"
        const val TAOBAO = "com.taobao.taobao"
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
            "{\"version\":1,\"milestones\":${"[".repeat(StrictJsonScan.MAX_DEPTH)}1${"]".repeat(StrictJsonScan.MAX_DEPTH)}}" to
                MilestoneRejection.MalformedJson
        ) + listOf("01", "0x1", "0X1", "+1", "1.", ".5", "1e", "-", "NaN", "Infinity", "1_0").map { version ->
            """{"version":$version,"milestones":[$MILESTONE]}""" to MilestoneRejection.MalformedJson
        }
    }
}
