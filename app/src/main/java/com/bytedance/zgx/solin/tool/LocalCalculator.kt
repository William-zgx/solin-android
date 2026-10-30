package com.bytedance.zgx.solin.tool

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Pure, exact-decimal calculator behind the `calculate` tool.
 *
 * Canonical expression grammar (no eval, no reflection, no scripting engine):
 * ```
 * conversion := number unit "to" unit             e.g. "3.5 km to m", "100 F to C"
 * expr       := term (("+" | "-") term)*
 * term       := power (("*" | "/") power)*
 * power      := unary ("^" power)?                 right-associative, integer exponent |n| <= 64
 * unary      := ("+" | "-") unary | postfix
 * postfix    := primary "%"*                       percent = /100
 * primary    := number | "(" expr ")"
 * ```
 * Arithmetic runs on [BigDecimal] with 34 significant digits; exact results are printed exactly,
 * non-terminating ones are rounded for display and flagged approximate. Every failure is an
 * [CalculationOutcome.Invalid] with a user-facing reason — never an exception.
 */
internal sealed class CalculationOutcome {
    data class Value(
        val kind: Kind,
        val expressionDisplay: String,
        val resultDisplay: String,
        val approximate: Boolean,
    ) : CalculationOutcome() {
        val summary: String
            get() = "$expressionDisplay ${if (approximate) "≈" else "="} $resultDisplay"
    }

    data class Invalid(val reason: String) : CalculationOutcome()

    enum class Kind(val wireName: String) {
        Arithmetic("arithmetic"),
        UnitConversion("unit_conversion"),
    }
}

internal object LocalCalculator {
    const val MAX_EXPRESSION_CHARS = 200
    private const val MAX_NESTING_DEPTH = 32
    private const val MAX_EXPONENT = 64
    private const val DISPLAY_DECIMALS = 10
    private const val CONVERSION_DISPLAY_DECIMALS = 6
    private val mathContext = MathContext.DECIMAL128
    private val maxMagnitude = BigDecimal("1e30")

    private val conversionPattern =
        Regex("""^\s*([+-]?\d+(?:\.\d+)?)\s*([A-Za-z0-9]+)\s+to\s+([A-Za-z0-9]+)\s*$""", RegexOption.IGNORE_CASE)

    fun evaluate(expression: String): CalculationOutcome {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return CalculationOutcome.Invalid("表达式不能为空")
        if (trimmed.length > MAX_EXPRESSION_CHARS) return CalculationOutcome.Invalid("表达式过长")
        conversionPattern.matchEntire(trimmed)?.let { match ->
            return convert(
                amountText = match.groupValues[1],
                fromCode = match.groupValues[2],
                toCode = match.groupValues[3],
            )
        }
        return evaluateArithmetic(trimmed)
    }

    private fun evaluateArithmetic(expression: String): CalculationOutcome {
        val tokens = tokenize(expression) ?: return CalculationOutcome.Invalid("表达式包含不支持的字符")
        // A bare signed number ("+8613800138000", "-5") is not a calculation.
        val hasComputation = tokens.withIndex().any { (index, token) ->
            token == Token.Percent ||
                (token is Token.Operator && tokens.getOrNull(index - 1).let { previous ->
                    previous is Token.Number || previous == Token.Close || previous == Token.Percent
                })
        }
        if (!hasComputation) return CalculationOutcome.Invalid("表达式缺少运算符")
        val parser = Parser(tokens)
        val value = try {
            parser.parseExpression(depth = 0).also {
                if (!parser.atEnd()) throw CalculationException("表达式格式不正确")
            }
        } catch (error: CalculationException) {
            return CalculationOutcome.Invalid(error.message ?: "表达式格式不正确")
        } catch (_: ArithmeticException) {
            return CalculationOutcome.Invalid("计算结果超出范围")
        }
        if (value.abs() > maxMagnitude) return CalculationOutcome.Invalid("计算结果超出范围")
        val (display, approximate) = value.display(DISPLAY_DECIMALS)
        return CalculationOutcome.Value(
            kind = CalculationOutcome.Kind.Arithmetic,
            expressionDisplay = tokens.joinToString("") { it.display },
            resultDisplay = display,
            approximate = approximate,
        )
    }

    private fun convert(amountText: String, fromCode: String, toCode: String): CalculationOutcome {
        val amount = amountText.toBigDecimalOrNull() ?: return CalculationOutcome.Invalid("数值无效")
        val from = MeasurementUnit.byCode(fromCode) ?: return CalculationOutcome.Invalid("不支持的单位：$fromCode")
        val to = MeasurementUnit.byCode(toCode) ?: return CalculationOutcome.Invalid("不支持的单位：$toCode")
        if (from.dimension != to.dimension) return CalculationOutcome.Invalid("单位类型不一致，无法换算")
        if (amount.abs() > maxMagnitude) return CalculationOutcome.Invalid("数值超出范围")
        val result = if (from.dimension == Dimension.Temperature) {
            fromKelvin(toKelvin(amount, from), to)
        } else {
            amount.multiply(from.factor, mathContext).divide(to.factor, mathContext)
        }
        val (display, approximate) = result.display(CONVERSION_DISPLAY_DECIMALS)
        return CalculationOutcome.Value(
            kind = CalculationOutcome.Kind.UnitConversion,
            expressionDisplay = "${amount.stripTrailingZeros().toPlainString()} ${from.displayName}",
            resultDisplay = "$display ${to.displayName}",
            approximate = approximate,
        )
    }

    private val kelvinOffset = BigDecimal("273.15")
    private val fahrenheitScale = BigDecimal("1.8")
    private val fahrenheitOffset = BigDecimal("32")

    private fun toKelvin(value: BigDecimal, unit: MeasurementUnit): BigDecimal =
        when (unit) {
            MeasurementUnit.Celsius -> value + kelvinOffset
            MeasurementUnit.Fahrenheit -> (value - fahrenheitOffset).divide(fahrenheitScale, mathContext) + kelvinOffset
            else -> value
        }

    private fun fromKelvin(kelvin: BigDecimal, unit: MeasurementUnit): BigDecimal =
        when (unit) {
            MeasurementUnit.Celsius -> kelvin - kelvinOffset
            MeasurementUnit.Fahrenheit -> (kelvin - kelvinOffset).multiply(fahrenheitScale, mathContext) + fahrenheitOffset
            else -> kelvin
        }

    /** Exact plain string when it fits [decimals]; otherwise rounded half-even and flagged approximate. */
    private fun BigDecimal.display(decimals: Int): Pair<String, Boolean> {
        val stripped = stripTrailingZeros()
        if (stripped.scale() <= decimals) return stripped.normalizedPlain() to false
        val rounded = setScale(decimals, RoundingMode.HALF_EVEN).stripTrailingZeros()
        return rounded.normalizedPlain() to true
    }

    private fun BigDecimal.normalizedPlain(): String =
        if (signum() == 0) "0" else toPlainString()

    private sealed class Token(val display: String) {
        class Number(val value: BigDecimal, text: String) : Token(text)
        class Operator(val symbol: Char) : Token(symbol.toString())
        object Open : Token("(")
        object Close : Token(")")
        object Percent : Token("%")
    }

    private fun tokenize(expression: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        var index = 0
        while (index < expression.length) {
            val character = expression[index]
            when {
                character.isWhitespace() -> index += 1
                character.isDigit() || character == '.' -> {
                    val start = index
                    while (index < expression.length && (expression[index].isDigit() || expression[index] == '.')) index += 1
                    val text = expression.substring(start, index)
                    val value = text.toBigDecimalOrNull() ?: return null
                    tokens += Token.Number(value, text)
                }
                character in "+-*/^" -> {
                    tokens += Token.Operator(character)
                    index += 1
                }
                character == '(' -> { tokens += Token.Open; index += 1 }
                character == ')' -> { tokens += Token.Close; index += 1 }
                character == '%' -> { tokens += Token.Percent; index += 1 }
                else -> return null
            }
        }
        return tokens
    }

    private class CalculationException(message: String) : Exception(message)

    private class Parser(private val tokens: List<Token>) {
        private var position = 0

        fun atEnd(): Boolean = position >= tokens.size

        private fun peek(): Token? = tokens.getOrNull(position)

        private fun peekOperator(vararg symbols: Char): Char? =
            (peek() as? Token.Operator)?.symbol?.takeIf { it in symbols }

        fun parseExpression(depth: Int): BigDecimal {
            if (depth > MAX_NESTING_DEPTH) throw CalculationException("括号嵌套过深")
            var value = parseTerm(depth)
            while (true) {
                val operator = peekOperator('+', '-') ?: return value
                position += 1
                val right = parseTerm(depth)
                value = if (operator == '+') value.add(right, mathContext) else value.subtract(right, mathContext)
            }
        }

        private fun parseTerm(depth: Int): BigDecimal {
            var value = parsePower(depth)
            while (true) {
                val operator = peekOperator('*', '/') ?: return value
                position += 1
                val right = parsePower(depth)
                value = if (operator == '*') {
                    value.multiply(right, mathContext)
                } else {
                    if (right.signum() == 0) throw CalculationException("除数不能为零")
                    value.divide(right, mathContext)
                }
            }
        }

        private fun parsePower(depth: Int): BigDecimal {
            val base = parseUnary(depth)
            if (peekOperator('^') == null) return base
            position += 1
            val exponent = parsePower(depth)
            val exponentInt = try {
                exponent.intValueExact()
            } catch (_: ArithmeticException) {
                throw CalculationException("只支持整数次幂")
            }
            if (exponentInt !in -MAX_EXPONENT..MAX_EXPONENT) throw CalculationException("指数过大")
            if (exponentInt < 0) {
                if (base.signum() == 0) throw CalculationException("除数不能为零")
                return BigDecimal.ONE.divide(base.pow(-exponentInt, mathContext), mathContext)
            }
            return base.pow(exponentInt, mathContext)
        }

        private fun parseUnary(depth: Int): BigDecimal {
            val operator = peekOperator('+', '-')
            if (operator != null) {
                position += 1
                val value = parseUnary(depth + 1)
                return if (operator == '-') value.negate() else value
            }
            return parsePostfix(depth)
        }

        private fun parsePostfix(depth: Int): BigDecimal {
            var value = parsePrimary(depth)
            while (peek() == Token.Percent) {
                position += 1
                value = value.divide(BigDecimal(100), mathContext)
            }
            return value
        }

        private fun parsePrimary(depth: Int): BigDecimal {
            return when (val token = peek() ?: throw CalculationException("表达式不完整")) {
                is Token.Number -> {
                    position += 1
                    token.value
                }
                Token.Open -> {
                    position += 1
                    val value = parseExpression(depth + 1)
                    if (peek() != Token.Close) throw CalculationException("括号不匹配")
                    position += 1
                    value
                }
                else -> throw CalculationException("表达式格式不正确")
            }
        }
    }
}

internal enum class Dimension { Length, Mass, Volume, Temperature }

/** Units with exact SI factors (international inch/foot/mile/pound definitions; 市斤 = 0.5 kg). */
internal enum class MeasurementUnit(
    val code: String,
    val displayName: String,
    val dimension: Dimension,
    val factor: BigDecimal,
) {
    Meter("m", "米", Dimension.Length, BigDecimal.ONE),
    Kilometer("km", "公里", Dimension.Length, BigDecimal("1000")),
    Centimeter("cm", "厘米", Dimension.Length, BigDecimal("0.01")),
    Millimeter("mm", "毫米", Dimension.Length, BigDecimal("0.001")),
    Mile("mi", "英里", Dimension.Length, BigDecimal("1609.344")),
    Foot("ft", "英尺", Dimension.Length, BigDecimal("0.3048")),
    Inch("in", "英寸", Dimension.Length, BigDecimal("0.0254")),
    Yard("yd", "码", Dimension.Length, BigDecimal("0.9144")),
    NauticalMile("nmi", "海里", Dimension.Length, BigDecimal("1852")),
    Kilogram("kg", "千克", Dimension.Mass, BigDecimal.ONE),
    Gram("g", "克", Dimension.Mass, BigDecimal("0.001")),
    Milligram("mg", "毫克", Dimension.Mass, BigDecimal("0.000001")),
    Tonne("t", "吨", Dimension.Mass, BigDecimal("1000")),
    Pound("lb", "磅", Dimension.Mass, BigDecimal("0.45359237")),
    Ounce("oz", "盎司", Dimension.Mass, BigDecimal("0.028349523125")),
    Jin("jin", "斤", Dimension.Mass, BigDecimal("0.5")),
    Liang("liang", "两", Dimension.Mass, BigDecimal("0.05")),
    Liter("l", "升", Dimension.Volume, BigDecimal.ONE),
    Milliliter("ml", "毫升", Dimension.Volume, BigDecimal("0.001")),
    UsGallon("gal", "加仑", Dimension.Volume, BigDecimal("3.785411784")),
    Celsius("c", "摄氏度", Dimension.Temperature, BigDecimal.ONE),
    Fahrenheit("f", "华氏度", Dimension.Temperature, BigDecimal.ONE),
    Kelvin("k", "开尔文", Dimension.Temperature, BigDecimal.ONE),
    ;

    companion object {
        private val byLowerCode = entries.associateBy { it.code }

        fun byCode(code: String): MeasurementUnit? = byLowerCode[code.lowercase()]
    }
}
