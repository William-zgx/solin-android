package com.bytedance.zgx.solin.action

import com.bytedance.zgx.solin.tool.CalculationOutcome
import com.bytedance.zgx.solin.tool.LocalCalculator
import com.bytedance.zgx.solin.tool.MAX_SMS_BODY_CHARS
import com.bytedance.zgx.solin.tool.MeasurementUnit
import com.bytedance.zgx.solin.tool.PHONE_NUMBER_PATTERN

/*
 * Rule parsers for everyday assistant intents: phone dial / SMS drafts, media keys, the local
 * calculator and translation. Every parser matches the WHOLE request (after polite prefixes), so a
 * phrase embedded in a longer sentence ("提醒我明天给妈妈打电话", "怎么给10086打电话") never
 * triggers an action; unmatched requests fall through to the normal chat / model path.
 */

private const val POLITE_PREFIX = """(?:(?:请你?|帮我|麻烦你?|你|给我|能不能|可以)\s*)*"""
private const val NUMBER_TARGET = """\+?\(?\d[\d\s\-()]{1,28}\d"""
private const val NAME_TARGET = """[\p{IsHan}A-Za-z·]{1,10}?"""

private val phoneNumberRegex = Regex(PHONE_NUMBER_PATTERN)
private val phoneSeparatorRegex = Regex("""[\s\-()]""")
private val nonContactNames = setOf(
    "我", "你", "他", "她", "它", "他们", "她们", "我们", "你们", "谁", "这个", "那个", "这个人", "那个人",
    "对方", "人", "一个人", "别人", "电话", "号码", "手机", "转移", "等待", "键盘", "中心",
)
private val questionMarkers = listOf("吗", "呢", "?", "？", "怎么", "如何", "能否", "可不可以", "为什么", "多少钱")

/** A phone target: an already-normalized number, or a contact name that must be resolved first. */
internal sealed class PhoneTarget {
    data class Number(val value: String) : PhoneTarget()
    data class ContactName(val name: String) : PhoneTarget()

    companion object {
        fun parse(raw: String): PhoneTarget? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.any { it.isDigit() }) {
                // Only plain numbers: *, #, commas (pause) and letters never reach tel:/smsto:.
                val normalized = trimmed.replace(phoneSeparatorRegex, "")
                return PhoneTarget.Number(normalized).takeIf { normalized.matches(phoneNumberRegex) }
            }
            if (trimmed in nonContactNames) return null
            return ContactName(trimmed)
        }
    }
}

/**
 * Builds the existing `query_contacts` draft for a name target: the dialer / SMS draft needs a
 * number, so the number is looked up first (fail closed — never guess or pre-fill a name).
 */
private fun contactLookupDraft(name: String, purpose: String): ActionDraft =
    ActionDraft(
        functionName = MobileActionFunctions.QUERY_CONTACTS,
        title = "查询联系人",
        summary = "${purpose}需要先查到「$name」的电话号码：将读取通讯录中匹配「$name」的联系人摘要，确认号码后再继续。",
        parameters = mapOf("query" to name),
        requiresConfirmation = true,
    )

private fun String.looksLikeCommunicationNonAction(): Boolean =
    startsWithActionNegation() || looksLikeDiscussion() || questionMarkers.any { it in this }

internal object PhoneCallActionParser {
    private val chineseCallToPattern = Regex(
        """^$POLITE_PREFIX(?:打(?:个|一个|一通)?电话给|打给|拨打|拨通|呼叫)\s*(?<target>$NUMBER_TARGET|$NAME_TARGET)\s*(?:吧)?\s*[。.!！]?$""",
    )
    private val chineseNumberOnlyPattern = Regex(
        """^$POLITE_PREFIX(?:拨号(?:给)?|致电)\s*(?<target>$NUMBER_TARGET)\s*[。.!！]?$""",
    )
    private val chineseCallPattern = Regex(
        """^${POLITE_PREFIX}给\s*(?<target>$NUMBER_TARGET|$NAME_TARGET)\s*(?:打|拨)(?:个|一个|一通)?电话\s*(?:吧)?\s*[。.!！]?$""",
    )
    private val englishCallPattern = Regex(
        """^(?:please\s+)?(?:call|dial|phone)\s+(?<target>$NUMBER_TARGET)\s*[.!]?$""",
        RegexOption.IGNORE_CASE,
    )

    fun target(input: String): PhoneTarget? {
        val trimmed = input.trim()
        if (trimmed.looksLikeCommunicationNonAction()) return null
        val match = chineseCallToPattern.matchEntire(trimmed)
            ?: chineseCallPattern.matchEntire(trimmed)
            ?: chineseNumberOnlyPattern.matchEntire(trimmed)
            ?: englishCallPattern.matchEntire(trimmed)
            ?: return null
        return PhoneTarget.parse(match.groups["target"]?.value.orEmpty())
    }

    fun matches(input: String): Boolean = target(input) != null

    fun draft(input: String): ActionDraft? =
        when (val target = target(input)) {
            is PhoneTarget.Number -> draftFor(target.value)
            is PhoneTarget.ContactName -> contactLookupDraft(target.name, purpose = "拨号")
            null -> null
        }

    fun draftFor(phoneNumber: String): ActionDraft =
        ActionDraft(
            functionName = MobileActionFunctions.DIAL_PHONE_NUMBER,
            title = "拨号草稿",
            summary = "将打开拨号盘并填入 $phoneNumber，需要你在拨号界面手动拨出；不会直接拨打。",
            parameters = mapOf("phoneNumber" to phoneNumber),
            requiresConfirmation = true,
        )
}

internal object SmsDraftActionParser {
    private const val SMS_NOUN = """(?:短信|短消息)"""
    private const val COUNT = """(?:一条|一个|个|条)?"""
    private const val BODY = """(?:\s*(?:[,，:：]|说|内容是|内容为|告诉(?:他|她|ta)?|\s)\s*(?<body>.+?))?"""
    private val sendToPattern = Regex(
        """^${POLITE_PREFIX}发$COUNT$SMS_NOUN\s*给\s*(?<target>$NUMBER_TARGET|$NAME_TARGET)$BODY\s*$""",
    )
    private val toSendPattern = Regex(
        """^${POLITE_PREFIX}给\s*(?<target>$NUMBER_TARGET|$NAME_TARGET)\s*发$COUNT$SMS_NOUN$BODY\s*$""",
    )
    private val englishPattern = Regex(
        """^(?:please\s+)?(?:text|sms|send\s+(?:an?\s+)?(?:sms|text)\s+to)\s+(?<target>$NUMBER_TARGET)(?:\s*(?::|,|saying|that)\s*(?<body>.+?))?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    data class Parsed(val target: PhoneTarget, val body: String)

    fun parse(input: String): Parsed? {
        val trimmed = input.trim()
        if (trimmed.startsWithActionNegation() || trimmed.looksLikeDiscussion()) return null
        val match = sendToPattern.matchEntire(trimmed)
            ?: toSendPattern.matchEntire(trimmed)
            ?: englishPattern.matchEntire(trimmed)
            ?: return null
        val body = match.groups["body"]?.value?.trim().orEmpty()
        // The body is user text and may legitimately be a question; only the target part is checked.
        if (body.isEmpty() && questionMarkers.any { it in trimmed }) return null
        if (body.length > MAX_SMS_BODY_CHARS) return null
        val target = PhoneTarget.parse(match.groups["target"]?.value.orEmpty()) ?: return null
        return Parsed(target, body)
    }

    fun matches(input: String): Boolean = parse(input) != null

    fun draft(input: String): ActionDraft? {
        val parsed = parse(input) ?: return null
        return when (val target = parsed.target) {
            is PhoneTarget.Number -> draftFor(target.value, parsed.body)
            is PhoneTarget.ContactName -> contactLookupDraft(target.name, purpose = "发短信")
        }
    }

    fun draftFor(phoneNumber: String, body: String): ActionDraft =
        ActionDraft(
            functionName = MobileActionFunctions.COMPOSE_SMS,
            title = "短信草稿",
            summary = if (body.isBlank()) {
                "将打开短信应用并填入收件号码 $phoneNumber，需要你在短信应用中手动编辑并发送；不会直接发送。"
            } else {
                "将打开短信应用并填入收件号码 $phoneNumber 和正文，需要你在短信应用中手动发送；不会直接发送。"
            },
            parameters = buildMap {
                put("phoneNumber", phoneNumber)
                if (body.isNotBlank()) put("body", body)
            },
            requiresConfirmation = true,
        )
}

internal object MediaControlActionParser {
    private val commandPatterns: List<Pair<String, Regex>> = listOf(
        "next" to Regex("""^(?:播放|放|切到|切换到)?(?:下一首|下一曲|下首歌?)(?:歌|歌曲|音乐)?$|^(?:切歌|换一首|换首歌|换一首歌)$|^(?:next\s+(?:song|track)|skip\s+(?:this\s+)?(?:song|track))$"""),
        "previous" to Regex("""^(?:播放|放|切到|切换到)?(?:上一首|上一曲|上首歌?)(?:歌|歌曲|音乐)?$|^(?:previous|last)\s+(?:song|track)$"""),
        "pause" to Regex("""^(?:暂停|先暂停|暂停一下)(?:播放|音乐|歌曲|放歌)$|^(?:音乐|歌曲|播放)暂停(?:一下)?$|^pause\s+(?:the\s+)?(?:music|song|playback)$"""),
        "stop" to Regex("""^(?:停止|停掉|关掉|关闭)(?:播放|音乐|歌曲|放歌)$|^stop\s+(?:the\s+)?(?:music|song|playback)$"""),
        "play" to Regex("""^(?:继续|恢复|开始)(?:播放|放歌|放音乐|听歌)$|^(?:播放|放点?|放首|来点)(?:音乐|歌|歌曲)$|^(?:play|resume)\s+(?:the\s+)?(?:music|song|playback)$|^resume$"""),
    )
    private val leadingFillerRegex = Regex("""^(?:(?:请你?|帮我|麻烦你?|给我)\s*)+""")
    private val trailingFillerRegex = Regex("""(?:\s*(?:一下|吧|please))*\s*[。.!！]*$""", RegexOption.IGNORE_CASE)

    fun command(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.startsWithActionNegation()) return null
        val core = trimmed
            .replace(leadingFillerRegex, "")
            .replace(trailingFillerRegex, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .lowercase()
        if (core.isEmpty()) return null
        return commandPatterns.firstOrNull { (_, pattern) -> pattern.matches(core) }?.first
    }

    fun matches(input: String): Boolean = command(input) != null

    fun draft(input: String): ActionDraft? = command(input)?.let(::draftFor)

    fun draftFor(command: String): ActionDraft =
        ActionDraft(
            functionName = MobileActionFunctions.CONTROL_MEDIA,
            title = "媒体播放控制",
            summary = "将向当前正在播放的媒体应用发送「${mediaCommandLabel(command)}」按键；不会读取播放内容。",
            parameters = mapOf("command" to command),
            requiresConfirmation = true,
        )
}

/**
 * Maps a natural-language arithmetic or unit-conversion request to the canonical `calculate`
 * expression. Matches only when the whole request is a calculation AND it evaluates; anything else
 * falls through to the model.
 */
internal object CalculatorActionParser {
    private val fullWidthMap = mapOf(
        '＋' to '+', '－' to '-', '×' to '*', '＊' to '*', '÷' to '/', '／' to '/', '（' to '(', '）' to ')',
        '％' to '%', '＾' to '^', '．' to '.', '＝' to '=', '−' to '-', '·' to '*',
    )
    private val triggerPrefixRegex = Regex(
        """^$POLITE_PREFIX(?:计算一下|计算|算一下|算算|算|求)\s*[:：]?\s*""",
    )
    private val questionSuffixRegex = Regex(
        """\s*(?:(?:的结果)?(?:等于|是|得)?\s*(?:多少|几)(?:呢|啊|呀)?|=|等于)\s*[?？]?\s*$""",
    )
    private val trailingPunctuationRegex = Regex("""[\s。!！]+$""")
    private val powerWordRegex = Regex("""的\s*(\d+)\s*次方""")
    private val chineseOperatorWords = listOf(
        "加上" to "+", "减去" to "-", "乘以" to "*", "除以" to "/", "加" to "+", "减" to "-", "乘" to "*",
    )
    private val chineseNumeralRunRegex = Regex("""[${ChineseNumerals.NUMERAL_CLASS}]+""")
    private val percentWordRegex = Regex("""百分之\s*([0-9.]+)""")
    private val arithmeticCharsRegex = Regex("""^[0-9.+\-*/^%()\s]+$""")
    private val binaryOperatorRegex = Regex("""[\d)%]\s*[+*/^\-]\s*[\d(.+\-]""")
    private val strongOperatorRegex = Regex("""[\d)%]\s*[+*^]\s*[\d(.+\-]""")

    private val unitAliases: Map<String, MeasurementUnit> = buildMap {
        fun alias(unit: MeasurementUnit, vararg names: String) = names.forEach { put(it.lowercase(), unit) }
        alias(MeasurementUnit.Kilometer, "公里", "千米", "km", "kilometer", "kilometers", "kilometre", "kilometres")
        alias(MeasurementUnit.Centimeter, "厘米", "公分", "cm", "centimeter", "centimeters", "centimetre", "centimetres")
        alias(MeasurementUnit.Millimeter, "毫米", "mm", "millimeter", "millimeters", "millimetre", "millimetres")
        alias(MeasurementUnit.Meter, "米", "m", "meter", "meters", "metre", "metres")
        alias(MeasurementUnit.Mile, "英里", "mi", "mile", "miles")
        alias(MeasurementUnit.Foot, "英尺", "ft", "foot", "feet")
        alias(MeasurementUnit.Inch, "英寸", "inch", "inches")
        alias(MeasurementUnit.Yard, "码", "yd", "yard", "yards")
        alias(MeasurementUnit.NauticalMile, "海里", "nmi")
        alias(MeasurementUnit.Kilogram, "公斤", "千克", "kg", "kilogram", "kilograms")
        alias(MeasurementUnit.Milligram, "毫克", "mg", "milligram", "milligrams")
        alias(MeasurementUnit.Gram, "克", "g", "gram", "grams")
        alias(MeasurementUnit.Tonne, "吨", "tonne", "tonnes")
        alias(MeasurementUnit.Pound, "磅", "lb", "lbs", "pound", "pounds")
        alias(MeasurementUnit.Ounce, "盎司", "oz", "ounce", "ounces")
        alias(MeasurementUnit.Jin, "斤", "市斤")
        alias(MeasurementUnit.Liang, "两")
        alias(MeasurementUnit.Milliliter, "毫升", "ml", "milliliter", "milliliters", "millilitre", "millilitres")
        alias(MeasurementUnit.Liter, "升", "公升", "l", "liter", "liters", "litre", "litres")
        alias(MeasurementUnit.UsGallon, "加仑", "gal", "gallon", "gallons")
        alias(MeasurementUnit.Celsius, "摄氏度", "℃", "°c", "celsius")
        alias(MeasurementUnit.Fahrenheit, "华氏度", "℉", "°f", "fahrenheit")
        alias(MeasurementUnit.Kelvin, "开尔文", "kelvin")
    }
    private val unitAlternation = unitAliases.keys
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }
    private val amountPattern = """[+-]?\d+(?:\.\d+)?|[${ChineseNumerals.NUMERAL_CLASS}]+"""
    private val chineseConversionRegex = Regex(
        """^$POLITE_PREFIX(?:把|将)?\s*(?<amount>$amountPattern)\s*(?<from>$unitAlternation)\s*""" +
            """(?<verb>等于|是|合|有|折合|相当于|换算成|换算为|转换成|转换为|转成|换成|能换)?\s*(?<ask>多少|几)?\s*个?\s*""" +
            """(?<to>$unitAlternation)\s*(?:呢|啊|呀)?\s*[?？]?$""",
        RegexOption.IGNORE_CASE,
    )
    private val englishConversionRegex = Regex(
        """^(?:convert\s+)?(?<amount>[+-]?\d+(?:\.\d+)?)\s*(?<from>$unitAlternation)\s+(?:to|in|into)\s+(?<to>$unitAlternation)\s*[?.]?$""",
        RegexOption.IGNORE_CASE,
    )

    /** Canonical [LocalCalculator] expression for the request, or null when it is not a calculation. */
    fun expression(input: String): String? {
        val normalized = input.trim().map { fullWidthMap[it] ?: it }.joinToString("")
        if (normalized.isEmpty() || normalized.length > LocalCalculator.MAX_EXPRESSION_CHARS) return null
        if (normalized.startsWithActionNegation()) return null
        val candidate = conversionExpression(normalized) ?: arithmeticExpression(normalized) ?: return null
        return candidate.takeIf { LocalCalculator.evaluate(it) is CalculationOutcome.Value }
    }

    fun matches(input: String): Boolean = expression(input) != null

    fun draft(input: String): ActionDraft? = expression(input)?.let(::draftFor)

    fun draftFor(expression: String): ActionDraft =
        ActionDraft(
            functionName = MobileActionFunctions.CALCULATE,
            title = "本地计算",
            summary = conversionSummary(expression) ?: "将在本地计算：$expression",
            parameters = mapOf("expression" to expression),
            requiresConfirmation = false,
        )

    private val canonicalConversionRegex = Regex("""^(\S+) (\S+) to (\S+)$""")

    private fun conversionSummary(expression: String): String? {
        val match = canonicalConversionRegex.matchEntire(expression) ?: return null
        val from = MeasurementUnit.byCode(match.groupValues[2]) ?: return null
        val to = MeasurementUnit.byCode(match.groupValues[3]) ?: return null
        return "将在本地把 ${match.groupValues[1]} ${from.displayName}换算为${to.displayName}"
    }

    private fun conversionExpression(input: String): String? {
        val chinese = chineseConversionRegex.matchEntire(input)
        val match = chinese ?: englishConversionRegex.matchEntire(input) ?: return null
        if (chinese != null && match.groups["verb"] == null && match.groups["ask"] == null) return null
        val amountText = match.groups["amount"]?.value.orEmpty()
        val amount = amountText.toBigDecimalOrNull()?.toPlainString()
            ?: ChineseNumerals.parseInt(amountText)?.toString()
            ?: return null
        val from = unitAliases[match.groups["from"]?.value.orEmpty().lowercase()] ?: return null
        val to = unitAliases[match.groups["to"]?.value.orEmpty().lowercase()] ?: return null
        if (from == to) return null
        return "$amount ${from.code} to ${to.code}"
    }

    private fun arithmeticExpression(input: String): String? {
        var text = input
        val hasPrefixTrigger = triggerPrefixRegex.containsMatchIn(text)
        text = text.replace(triggerPrefixRegex, "")
        text = text.replace(trailingPunctuationRegex, "")
        val hasSuffixTrigger = questionSuffixRegex.containsMatchIn(text)
        text = text.replace(questionSuffixRegex, "")
        text = text.replace(percentWordRegex) { match -> "${match.groupValues[1]}%" }
        chineseOperatorWords.forEach { (word, operator) -> text = text.replace(word, operator) }
        text = text.replace("的平方", "^2").replace("的立方", "^3")
        var numeralFailed = false
        text = text.replace(chineseNumeralRunRegex) { match ->
            ChineseNumerals.parseInt(match.value)?.toString() ?: run {
                numeralFailed = true
                match.value
            }
        }
        if (numeralFailed) return null
        text = text.replace(powerWordRegex) { match -> "^${match.groupValues[1]}" }
        text = text.trim()
        if (text.isEmpty() || !arithmeticCharsRegex.matches(text) || text.none { it.isDigit() }) return null
        val triggered = hasPrefixTrigger || hasSuffixTrigger
        val hasOperator = if (triggered) {
            binaryOperatorRegex.containsMatchIn(text)
        } else {
            // Without a trigger word, '-' and '/' alone look like dates, ranges or phone numbers.
            strongOperatorRegex.containsMatchIn(text)
        }
        if (!hasOperator) return null
        return text.replace(Regex("""\s+"""), "")
    }
}

/**
 * Recognizes translation requests ("把…翻译成英文", "translate … into English", "…用英文怎么说").
 * These are NOT tool calls: the planners return no draft so the turn goes to the normal chat path
 * (local model by default; remote only when the user is in remote mode and the content passes the
 * existing remote-eligibility checks). [chatInstruction] is the instruction a caller may prepend.
 */
internal object TranslationIntentParser {
    data class TranslationRequest(val text: String, val targetLanguage: String?) {
        fun chatInstruction(): String =
            if (targetLanguage == null) {
                "请翻译以下内容（中文译成英文，其他语言译成中文），只输出译文：\n$text"
            } else {
                "请把以下内容翻译成$targetLanguage，只输出译文：\n$text"
            }
    }

    private const val CHINESE_LANGUAGE =
        """(?:简体中文|繁体中文|中文|汉语|英文|英语|日文|日语|韩文|韩语|法文|法语|德文|德语|西班牙文|西班牙语|俄文|俄语|意大利文|意大利语|葡萄牙文|葡萄牙语|阿拉伯文|阿拉伯语|粤语)"""
    private const val ENGLISH_LANGUAGE =
        """(?:english|chinese|japanese|korean|french|german|spanish|russian|italian|portuguese|arabic)"""

    private val putIntoPattern = Regex(
        """^$POLITE_PREFIX(?:把|将)\s*(?<text>.+?)\s*(?:翻译|翻|译)(?:成|为|到)\s*(?<lang>$CHINESE_LANGUAGE)\s*[。.!！]?$""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val translateIntoColonPattern = Regex(
        """^$POLITE_PREFIX(?:(?:把|将)\s*[^:：]{0,12}?)?\s*(?:翻译|译)(?:成|为|到)\s*(?<lang>$CHINESE_LANGUAGE)\s*[:：]\s*(?<text>.+)$""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val translateColonPattern = Regex(
        """^${POLITE_PREFIX}翻译(?:一下)?(?:这句话|这段话|下面的?(?:话|内容|句子)?)?\s*[:：]\s*(?<text>.+)$""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val howToSayPattern = Regex(
        """^(?<text>.+?)\s*(?:用|的)\s*(?<lang>$CHINESE_LANGUAGE)\s*(?:怎么说|怎么讲|怎么表达)\s*[?？]?$""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val englishPattern = Regex(
        """^(?:please\s+)?translate\s+(?<text>.+?)\s+(?:into|to)\s+(?<lang>$ENGLISH_LANGUAGE)\s*[.!]?$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val englishColonPattern = Regex(
        """^(?:please\s+)?translate\s+(?:(?:this|it)\s+)?(?:into|to)\s+(?<lang>$ENGLISH_LANGUAGE)\s*:\s*(?<text>.+)$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val quoteChars = "\"'“”‘’「」『』《》"

    fun parse(input: String): TranslationRequest? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.startsWithActionNegation()) return null
        val match = putIntoPattern.matchEntire(trimmed)
            ?: translateIntoColonPattern.matchEntire(trimmed)
            ?: translateColonPattern.matchEntire(trimmed)
            ?: howToSayPattern.matchEntire(trimmed)
            ?: englishPattern.matchEntire(trimmed)
            ?: englishColonPattern.matchEntire(trimmed)
            ?: return null
        val text = match.groups["text"]?.value?.trim()?.trim { it in quoteChars }?.trim().orEmpty()
        if (text.isEmpty()) return null
        // Not every pattern declares a language group; on the JVM groups[name] throws for unknown names.
        val language = runCatching { match.groups["lang"]?.value }.getOrNull()?.let(::displayLanguage)
        return TranslationRequest(text = text, targetLanguage = language)
    }

    fun matches(input: String): Boolean = parse(input) != null

    private fun displayLanguage(raw: String): String =
        when (raw.lowercase()) {
            "english", "英语" -> "英文"
            "chinese", "汉语" -> "中文"
            "japanese", "日语" -> "日文"
            "korean", "韩语" -> "韩文"
            "french", "法语" -> "法文"
            "german", "德语" -> "德文"
            "spanish", "西班牙语" -> "西班牙文"
            "russian", "俄语" -> "俄文"
            "italian", "意大利语" -> "意大利文"
            "portuguese", "葡萄牙语" -> "葡萄牙文"
            "arabic", "阿拉伯语" -> "阿拉伯文"
            else -> raw
        }
}
