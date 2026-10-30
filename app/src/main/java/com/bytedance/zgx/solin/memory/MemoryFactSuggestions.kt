package com.bytedance.zgx.solin.memory

import java.util.Locale

enum class MemoryFactSuggestionKind {
    Allergy,
    Diet,
    HomeLocation,
    WorkLocation,
    RelationName,
    ContactPhone,
    Birthday,
}

/**
 * A durable fact the user stated about themselves that Solin could offer to remember.
 *
 * Suggestions are never stored by the detector; [fact] is the exact first-person clause that will
 * be stored (through the explicit-memory routing) only after the user confirms it. [id] is stable
 * for the same normalized fact text so a dismissal can be remembered.
 */
data class MemoryFactSuggestion(
    val id: String,
    val kind: MemoryFactSuggestionKind,
    val fact: String,
)

/**
 * Pure detector of candidate durable facts in a user chat message ("我对花生过敏", "我家在望京",
 * "我老婆叫林小雨", "I'm allergic to shellfish").
 *
 * Conservative by design: explicit "记住…"/"忘记…" commands (already handled), questions,
 * hypotheticals, third-party statements and time-bound statements ("今天不吃辣") yield nothing.
 */
object MemoryFactSuggestionDetector {
    fun detect(message: String): List<MemoryFactSuggestion> {
        val trimmed = message.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_MESSAGE_LENGTH) return emptyList()
        if (
            explicitUserPreferenceFrom(trimmed) != null ||
            explicitUserFactFrom(trimmed) != null ||
            explicitUserPreferenceForgetFrom(trimmed) != null
        ) {
            return emptyList()
        }
        return CLAUSE_REGEX.findAll(trimmed)
            .mapNotNull { match -> suggestionFor(match.value) }
            .distinctBy { suggestion -> suggestion.id }
            .toList()
    }

    private fun suggestionFor(rawClause: String): MemoryFactSuggestion? {
        if (rawClause.trimEnd().lastOrNull() in QUESTION_MARKS) return null
        val clause = rawClause.trim().trimEnd(*CLAUSE_TERMINATORS).trim().removeLeadingFiller()
        if (clause.length < 3) return null
        val lower = clause.lowercase(Locale.ROOT)
        if (BLOCKING_TERMS.any { term -> term in lower }) return null
        if (ENGLISH_BLOCKING_WORDS.any { word -> Regex("""\b$word\b""").containsMatchIn(lower) }) return null
        val kind = PATTERNS.firstOrNull { (_, pattern) -> pattern.matches(clause) }?.first ?: return null
        return MemoryFactSuggestion(
            id = deterministicMemoryRecordId("memory-suggestion", lower.replace(WHITESPACE_RUN_REGEX, " ")),
            kind = kind,
            fact = clause,
        )
    }

    private fun String.removeLeadingFiller(): String {
        var current = this
        LEADING_FILLERS.forEach { filler ->
            if (current.lowercase(Locale.ROOT).startsWith(filler)) {
                current = current.substring(filler.length).trimStart(' ', '，', ',', '：', ':')
            }
        }
        return current
    }

    private const val MAX_MESSAGE_LENGTH = 200
    private const val RELATIONS_ZH = "老婆|老公|妻子|丈夫|太太|爱人|媳妇|女朋友|男朋友|儿子|女儿|孩子|妈妈|爸爸|母亲|父亲"
    private const val RELATIONS_EN = "wife|husband|partner|girlfriend|boyfriend|son|daughter|kid|mom|mother|dad|father"
    private const val PHONE = """\+?\d[\d\s-]{5,18}\d"""

    private val CLAUSE_REGEX = Regex("""[^。！!？?；;，,\n]+[。！!？?；;，,\n]?""")
    private val QUESTION_MARKS = setOf('？', '?')
    private val CLAUSE_TERMINATORS = charArrayOf('。', '！', '!', '；', ';', '，', ',', '.', ' ')

    private val LEADING_FILLERS = listOf("对了", "顺便说一下", "顺便说下", "另外", "其实", "by the way", "btw", "also", "fyi")

    /** Questions, hypotheticals, hedges, negated facts and time-bound statements. */
    private val BLOCKING_TERMS = setOf(
        "吗", "呢", "什么", "哪", "多少", "谁", "几", "是不是", "有没有", "能不能", "会不会",
        "如果", "假如", "要是", "万一", "可能", "也许", "好像", "据说", "听说", "应该",
        "今天", "明天", "昨天", "今晚", "刚才", "现在", "这次", "这周", "最近", "暂时",
        "不过敏", "没过敏",
    )
    private val ENGLISH_BLOCKING_WORDS = listOf("if", "maybe", "might", "today", "tonight", "tomorrow", "yesterday", "not")

    private val PATTERNS: List<Pair<MemoryFactSuggestionKind, Regex>> = listOf(
        MemoryFactSuggestionKind.Allergy to Regex("""^我(?:的)?(?:(?:$RELATIONS_ZH))?对.{1,12}过敏$"""),
        MemoryFactSuggestionKind.Allergy to Regex("""^i(?:'m| am) allergic to .{1,40}$""", RegexOption.IGNORE_CASE),
        MemoryFactSuggestionKind.Allergy to Regex("""^my (?:$RELATIONS_EN) is allergic to .{1,40}$""", RegexOption.IGNORE_CASE),
        MemoryFactSuggestionKind.Diet to Regex("""^我(?:(?:不吃|不能吃|戒了)[^了啦呢吧啊]{1,8}|吃素|是素食者)$"""),
        MemoryFactSuggestionKind.Diet to Regex("""^i(?:'m| am) (?:a )?(?:vegetarian|vegan)$""", RegexOption.IGNORE_CASE),
        MemoryFactSuggestionKind.HomeLocation to Regex("""^我家(?:住)?在.{2,40}$"""),
        MemoryFactSuggestionKind.HomeLocation to Regex("""^我住在.{2,40}$"""),
        MemoryFactSuggestionKind.HomeLocation to Regex("""^我(?:的)?(?:家庭住址|家庭地址|住址)(?:是|在).{2,40}$"""),
        MemoryFactSuggestionKind.HomeLocation to Regex("""^i live in .{2,60}$""", RegexOption.IGNORE_CASE),
        MemoryFactSuggestionKind.WorkLocation to Regex("""^我(?:的)?(?:公司|单位|办公室)(?:地址)?(?:在|是).{2,40}$"""),
        MemoryFactSuggestionKind.WorkLocation to Regex("""^我在(?!努力|认真|加班|继续|拼命|好好|正常|家)\S{2,30}(?:上班|工作)$"""),
        MemoryFactSuggestionKind.WorkLocation to Regex("""^i work at .{2,60}$""", RegexOption.IGNORE_CASE),
        MemoryFactSuggestionKind.ContactPhone to Regex("""^我(?:的)?(?:(?:$RELATIONS_ZH))?的?(?:手机号码|电话号码|手机号|电话|手机)(?:是|为)?[:：]?\s*$PHONE$"""),
        MemoryFactSuggestionKind.ContactPhone to Regex("""^my (?:(?:$RELATIONS_EN)'s )?(?:phone number|phone|mobile|number) is $PHONE$""", RegexOption.IGNORE_CASE),
        MemoryFactSuggestionKind.RelationName to Regex("""^我(?:的)?(?:$RELATIONS_ZH)(?:叫|名叫|的名字是).{1,10}$"""),
        MemoryFactSuggestionKind.RelationName to Regex("""^my (?:$RELATIONS_EN)'s name is .{1,40}$""", RegexOption.IGNORE_CASE),
        MemoryFactSuggestionKind.Birthday to Regex("""^我的?生日(?:是|在).{2,12}$"""),
        MemoryFactSuggestionKind.Birthday to Regex("""^my birthday is .{2,30}$""", RegexOption.IGNORE_CASE),
    )
}
