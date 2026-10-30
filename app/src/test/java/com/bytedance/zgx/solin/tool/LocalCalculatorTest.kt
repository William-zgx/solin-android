package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.MobileActionFunctions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCalculatorTest {
    @Test
    fun arithmeticIsExactDecimalWithPrecedence() {
        val cases = listOf(
            "23*17+5" to "23*17+5 = 396",
            "0.1+0.2" to "0.1+0.2 = 0.3",
            "2+3*4" to "2+3*4 = 14",
            "(2+3)*4" to "(2+3)*4 = 20",
            "2^10" to "2^10 = 1024",
            "2^3^2" to "2^3^2 = 512",
            "2^-2" to "2^-2 = 0.25",
            "-3*-3" to "-3*-3 = 9",
            "10/4" to "10/4 = 2.5",
            "1/3" to "1/3 ≈ 0.3333333333",
            "2/3" to "2/3 ≈ 0.6666666667",
            "200*15%" to "200*15% = 30",
            "1.50*2" to "1.50*2 = 3",
            "5-5" to "5-5 = 0",
            "1000+1" to "1000+1 = 1001",
        )
        cases.forEach { (expression, expected) ->
            val outcome = LocalCalculator.evaluate(expression)
            assertTrue("$expression -> $outcome", outcome is CalculationOutcome.Value)
            assertEquals(expression, expected, (outcome as CalculationOutcome.Value).summary)
        }
    }

    @Test
    fun unitConversionsUseExactFactors() {
        val cases = listOf(
            "3.5 km to m" to "3.5 公里 = 3500 米",
            "1 in to cm" to "1 英寸 = 2.54 厘米",
            "100 f to c" to "100 华氏度 ≈ 37.777778 摄氏度",
            "100 C to F" to "100 摄氏度 = 212 华氏度",
            "0 c to k" to "0 摄氏度 = 273.15 开尔文",
            "-40 f to c" to "-40 华氏度 = -40 摄氏度",
            "1 mi to km" to "1 英里 = 1.609344 公里",
            "1 lb to g" to "1 磅 = 453.59237 克",
            "2 jin to kg" to "2 斤 = 1 千克",
            "1 gal to l" to "1 加仑 ≈ 3.785412 升",
            "1 kg to liang" to "1 千克 = 20 两",
        )
        cases.forEach { (expression, expected) ->
            val outcome = LocalCalculator.evaluate(expression)
            assertTrue("$expression -> $outcome", outcome is CalculationOutcome.Value)
            assertEquals(expression, expected, (outcome as CalculationOutcome.Value).summary)
        }
    }

    @Test
    fun invalidInputFailsClosedWithoutThrowing() {
        val invalid = listOf(
            "",
            "1/0",
            "0^-1",
            "2^0.5",
            "2^65",
            "1+",
            "(1+2",
            "1+2)",
            "12",
            "+8613800138000",
            "1..2+1",
            "Runtime.exec(1)",
            "1 km to kg",
            "1 km to parsec",
            "9".repeat(40) + "*" + "9".repeat(40),
            "(".repeat(40) + "1" + ")".repeat(40) + "+1",
            "1+".repeat(150) + "1",
        )
        invalid.forEach { expression ->
            assertTrue(expression, LocalCalculator.evaluate(expression) is CalculationOutcome.Invalid)
        }
    }

    @Test
    fun executorReturnsLocalOnlyPrivateResultThatValidatesAgainstTheSpec() {
        val request = ToolRequest(
            id = "calc",
            toolName = MobileActionFunctions.CALCULATE,
            arguments = mapOf("expression" to "23*17+5"),
        )
        val registry = defaultBuiltInToolRegistry
        assertNull(registry.validate(request))

        val result = LocalCalculatorToolExecutor().execute(request)

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("23*17+5 = 396", result.summary)
        assertEquals("396", result.data["result"])
        assertEquals("arithmetic", result.data["kind"])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("true", result.data["requiresLocalModel"])
        assertNull(registry.validateResult(request, result))
        assertEquals("已完成本地计算", registry.redactedResultSummaryFor(MobileActionFunctions.CALCULATE))
    }

    @Test
    fun executorRejectsInvalidExpressionAsLocalOnlyFailure() {
        val request = ToolRequest(
            id = "calc",
            toolName = MobileActionFunctions.CALCULATE,
            arguments = mapOf("expression" to "1/0"),
        )

        val result = LocalCalculatorToolExecutor().execute(request)

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.InvalidRequest, result.error?.code)
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
    }
}
