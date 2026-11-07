package com.bytedance.zgx.solin.memory

import com.bytedance.zgx.solin.common.optNullableString
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

enum class MemoryFactSuggestionKind {
    Allergy,
    Diet,
    HomeLocation,
    WorkLocation,
    RelationName,
    ContactPhone,
    Birthday,

    /** A lasting like, dislike or style ("我喜欢喝不加糖的拿铁"). Proposed only by [LocalModelMemoryFactExtractor]. */
    Preference,

    /** A recurring schedule habit ("我每天早上六点半起床"). Proposed only by [LocalModelMemoryFactExtractor]. */
    Routine,

    /** The user's job ("我是一名小学老师"). Proposed only by [LocalModelMemoryFactExtractor]. */
    Occupation,
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
        if (!isCandidateMessage(trimmed)) return emptyList()
        return CLAUSE_REGEX.findAll(trimmed)
            .mapNotNull { match -> suggestionFor(match.value) }
            .distinctBy { suggestion -> suggestion.id }
            .toList()
    }

    /**
     * Whether the trimmed message may carry suggestions at all: non-empty, at most
     * [MAX_MESSAGE_LENGTH] characters, and not an explicit remember/forget command.
     */
    internal fun isCandidateMessage(trimmed: String): Boolean =
        trimmed.isNotEmpty() &&
            trimmed.length <= MAX_MESSAGE_LENGTH &&
            explicitUserPreferenceFrom(trimmed) == null &&
            explicitUserFactFrom(trimmed) == null &&
            explicitUserPreferenceForgetFrom(trimmed) == null

    /** Clauses of [trimmed] with their ranges; each keeps its trailing terminator. */
    internal fun clauses(trimmed: String): Sequence<MatchResult> = CLAUSE_REGEX.findAll(trimmed)

    /**
     * [rawClause] without trailing terminators and leading fillers, or null when it is a question,
     * hypothetical, hedge, negated or time-bound statement, or shorter than three characters.
     */
    internal fun unblockedClause(rawClause: String): String? {
        if (rawClause.trimEnd().lastOrNull() in QUESTION_MARKS) return null
        val clause = rawClause.trim().trimEnd(*CLAUSE_TERMINATORS).trim().removeLeadingFiller()
        if (clause.length < 3) return null
        val lower = clause.lowercase(Locale.ROOT)
        if (BLOCKING_TERMS.any { term -> term in lower }) return null
        if (ENGLISH_BLOCKING_WORD_REGEXES.any { regex -> regex.containsMatchIn(lower) }) return null
        return clause
    }

    internal fun suggestionOf(kind: MemoryFactSuggestionKind, fact: String): MemoryFactSuggestion =
        MemoryFactSuggestion(
            id = deterministicMemoryRecordId(
                "memory-suggestion",
                fact.lowercase(Locale.ROOT).replace(WHITESPACE_RUN_REGEX, " "),
            ),
            kind = kind,
            fact = fact,
        )

    internal fun String.removeLeadingFiller(): String {
        var current = this
        LEADING_FILLERS.forEach { filler ->
            if (current.lowercase(Locale.ROOT).startsWith(filler)) {
                current = current.substring(filler.length).trimStart(' ', '，', ',', '：', ':')
            }
        }
        return current
    }

    private fun suggestionFor(rawClause: String): MemoryFactSuggestion? {
        val clause = unblockedClause(rawClause) ?: return null
        val kind = PATTERNS.firstOrNull { (_, pattern) -> pattern.matches(clause) }?.first ?: return null
        return suggestionOf(kind, clause)
    }

    private const val MAX_MESSAGE_LENGTH = 200
    private const val RELATIONS_ZH = "老婆|老公|妻子|丈夫|太太|爱人|媳妇|女朋友|男朋友|儿子|女儿|孩子|妈妈|爸爸|母亲|父亲"
    private const val RELATIONS_EN = "wife|husband|partner|girlfriend|boyfriend|son|daughter|kid|mom|mother|dad|father"
    internal const val PHONE = """\+?\d[\d\s-]{5,18}\d"""

    private val CLAUSE_REGEX = Regex("""[^。！!？?；;，,\n]+[。！!？?；;，,\n]?""")
    private val QUESTION_MARKS = setOf('？', '?')
    internal val CLAUSE_TERMINATORS = charArrayOf('。', '！', '!', '；', ';', '，', ',', '.', ' ')

    private val LEADING_FILLERS = listOf("对了", "顺便说一下", "顺便说下", "另外", "其实", "by the way", "btw", "also", "fyi")

    /** Questions, hypotheticals, hedges, negated facts and time-bound statements. */
    private val BLOCKING_TERMS = setOf(
        "吗", "呢", "什么", "哪", "多少", "谁", "几", "是不是", "有没有", "能不能", "会不会",
        "如果", "假如", "要是", "万一", "可能", "也许", "好像", "据说", "听说", "应该",
        "今天", "明天", "昨天", "今晚", "刚才", "现在", "这次", "这周", "最近", "暂时",
        "不过敏", "没过敏",
    )
    private val ENGLISH_BLOCKING_WORD_REGEXES =
        listOf("if", "maybe", "might", "today", "tonight", "tomorrow", "yesterday", "not")
            .map { word -> Regex("""\b$word\b""") }

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

/**
 * One-shot on-device text generation for memory tasks: fact suggestions
 * ([LocalModelMemoryFactExtractor]) and compaction summaries (`orchestration.DefaultContextCompactor`).
 *
 * Contract, which every implementation must meet:
 * - It runs fully on the device: the loaded on-device engine and nothing else. No HTTP client, remote
 *   chat runtime or other network path may be reached from it. Prompts carry chat and memory text that
 *   stays LocalOnly, so it never reaches a remote model, a log line or an audit event.
 * - Each call uses a fresh conversation with an empty history. Nothing from an earlier call, and
 *   nothing from the chat session, is visible to it.
 * - It is cancellable: a cancelled call stops the engine work and returns, so the callers' timeouts
 *   and the caller's own cancellation take effect.
 *
 * Return null when the engine is not loaded, is busy with another call, or fails; callers then keep
 * their heuristic result. Only the adapters in the architecture allowlist may implement this interface
 * (see `LocalMemoryModelWiringArchitectureTest`). `LocalEngineMemoryModel` is that adapter; it is
 * present but not wired into either default-off feature.
 */
fun interface LocalMemoryModel {
    suspend fun generate(systemInstruction: String, prompt: String): String?
}

/** Which path produced a memory-task result. */
enum class MemoryTaskPath {
    /** The model path is off, has no model, or was not needed: the heuristic result as before. */
    Heuristic,

    /** The local model's output passed every check and was used. */
    Model,

    /** The model was asked but its output was not used; [LocalMemoryModelFallback] says why. */
    HeuristicFallback,
}

/** Why a memory task kept its heuristic result after asking the local model. Never carries text. */
enum class LocalMemoryModelFallback {
    /** The model returned null: engine not loaded, busy or failing. */
    EngineUnavailable,
    Timeout,

    /** The model call threw. */
    EngineFailed,
    EmptyOutput,

    /** The output broke the task's output contract (for facts: the JSON shape). */
    Unparseable,

    /** The output is longer than the task allows (for summaries: over the cap or the budget). */
    OverLength,

    /** The output states something its source does not contain (for summaries: a number). */
    Ungrounded,

    /** The task input is too large for one model call. */
    InputTooLarge,
}

/** One [LocalMemoryModel] call after the fail-closed checks of [answerWithin]. */
internal sealed interface LocalMemoryModelAnswer {
    /** Non-blank output with thinking blocks and code-fence lines removed. */
    data class Text(val text: String) : LocalMemoryModelAnswer

    data class Failed(val reason: LocalMemoryModelFallback) : LocalMemoryModelAnswer
}

/**
 * Calls the model once within [timeoutMillis]. A null answer, an exception, a timeout or blank output
 * becomes [LocalMemoryModelAnswer.Failed]; an unclosed `<think>` block is unparseable, not text.
 * Cancellation of the caller still propagates.
 */
internal suspend fun LocalMemoryModel.answerWithin(
    timeoutMillis: Long,
    systemInstruction: String,
    prompt: String,
): LocalMemoryModelAnswer {
    val reply = try {
        withTimeoutOrNull(timeoutMillis) { Reply(generate(systemInstruction, prompt)) }
            ?: return LocalMemoryModelAnswer.Failed(LocalMemoryModelFallback.Timeout)
    } catch (_: CancellationException) {
        // Rethrows when the caller was cancelled; a cancellation inside the model is a failed call.
        currentCoroutineContext().ensureActive()
        return LocalMemoryModelAnswer.Failed(LocalMemoryModelFallback.EngineFailed)
    } catch (_: Exception) {
        return LocalMemoryModelAnswer.Failed(LocalMemoryModelFallback.EngineFailed)
    }
    val raw = reply.text ?: return LocalMemoryModelAnswer.Failed(LocalMemoryModelFallback.EngineUnavailable)
    // A <think> that is never closed means the answer was cut inside the reasoning: nothing in it is an answer.
    if (raw.replace(THINKING_REGEX, "").contains(THINK_OPEN)) {
        return LocalMemoryModelAnswer.Failed(LocalMemoryModelFallback.Unparseable)
    }
    val body = modelOutputBody(raw)
    return if (body.isEmpty()) {
        LocalMemoryModelAnswer.Failed(LocalMemoryModelFallback.EmptyOutput)
    } else {
        LocalMemoryModelAnswer.Text(body)
    }
}

/** [raw] model output without `<think>` blocks and code-fence lines, trimmed. */
internal fun modelOutputBody(raw: String): String =
    raw.replace(THINKING_REGEX, "")
        .lineSequence()
        .filterNot { line -> line.trimStart().startsWith("```") }
        .joinToString("\n")
        .trim()

private class Reply(val text: String?)

/** Flag and budgets for [LocalModelMemoryFactExtractor]. [enabled] is OFF by default: only the regex detector runs. */
data class LocalModelMemoryExtractionConfig(
    val enabled: Boolean = false,
    /** Budget for one local model call; on timeout the regex detector's result is used. */
    val timeoutMillis: Long = 8_000L,
    /** At most this many model facts are added per message. */
    val maxFacts: Int = 3,
) {
    init {
        require(timeoutMillis > 0) { "timeoutMillis must be > 0" }
        require(maxFacts > 0) { "maxFacts must be > 0" }
    }
}

data class MemoryFactExtraction(
    /** Regex suggestions followed by the model facts they do not already cover. */
    val suggestions: List<MemoryFactSuggestion>,
    val path: MemoryTaskPath,
    /** Set only when [path] is [MemoryTaskPath.HeuristicFallback]. */
    val fallback: LocalMemoryModelFallback? = null,
    /** Model facts that passed every guard, before merging with the regex facts. */
    val modelSuggestions: List<MemoryFactSuggestion> = emptyList(),
    /** Fact items in the model output. */
    val proposedFacts: Int = 0,
    /** Proposed facts that do not occur verbatim in the message. */
    val ungroundedFacts: Int = 0,
    /** Proposed facts dropped for any reason: ungrounded, unknown kind, or a per-fact guard. */
    val droppedFacts: Int = 0,
)

/**
 * Optional local-model proposer of [MemoryFactSuggestion]s that also covers phrasings and kinds the
 * regex detector misses. Output is only ever a suggestion: nothing is stored until the user confirms
 * it in the memory panel. With [LocalModelMemoryExtractionConfig.enabled] off or no [model], [extract]
 * returns exactly [MemoryFactSuggestionDetector.detect] and the model is never called.
 *
 * Contract: the model answers `{"facts":[{"kind":"<kind>","fact":"<span>"}]}`, kind being a
 * [MemoryFactSuggestionKind] name. Validation fails closed at two levels:
 * - Whole output: no answer, timeout, exception, blank output, no JSON object, no `facts` array, or an
 *   item without string `kind`/`fact` discards the output; the regex result is returned
 *   ([MemoryTaskPath.HeuristicFallback]).
 * - Per fact (the fact is dropped): the fact must occur verbatim in the message under the
 *   [GroundingText] fold, and the kind must be known. The suggestion stores the user's text, never
 *   the model's: the matched span, extended left to the start of its clause when the span does not
 *   begin with a first-person subject (model "花生过敏" in "我对花生过敏，…" stores "我对花生过敏").
 *   The span stays inside one clause; that clause passes the regex detector's blocking rules
 *   (questions, hypotheticals, hedges, negations, time-bound words) plus past-time words; no colon or
 *   opening quote introduces it (reported speech, examples, translations); it starts with 我 / I / my
 *   and not with a third party (我朋友, my colleague); it contains its kind's anchor word, and
 *   location, relation, phone, birthday and occupation facts are not negated.
 * Whole-message gates are the regex detector's: empty, over-long and explicit remember/forget
 * messages yield nothing and the model is not called.
 */
class LocalModelMemoryFactExtractor(
    private val model: LocalMemoryModel?,
    private val config: LocalModelMemoryExtractionConfig = LocalModelMemoryExtractionConfig(),
) {
    suspend fun detect(message: String): List<MemoryFactSuggestion> = extract(message).suggestions

    suspend fun extract(message: String): MemoryFactExtraction {
        val trimmed = message.trim()
        val regexSuggestions = MemoryFactSuggestionDetector.detect(trimmed)
        if (!config.enabled || model == null || !MemoryFactSuggestionDetector.isCandidateMessage(trimmed)) {
            return MemoryFactExtraction(regexSuggestions, MemoryTaskPath.Heuristic)
        }
        val answer = model.answerWithin(config.timeoutMillis, MEMORY_FACT_EXTRACTION_INSTRUCTION, memoryFactExtractionPrompt(trimmed))
        return when (answer) {
            is LocalMemoryModelAnswer.Failed -> fallback(regexSuggestions, answer.reason)
            is LocalMemoryModelAnswer.Text -> interpret(trimmed, answer.text, regexSuggestions)
        }
    }

    private fun interpret(
        message: String,
        output: String,
        regexSuggestions: List<MemoryFactSuggestion>,
    ): MemoryFactExtraction {
        val facts = parseMemoryFactModelOutput(output)
            ?: return fallback(regexSuggestions, LocalMemoryModelFallback.Unparseable)
        val source = GroundingText(message)
        var ungrounded = 0
        val kept = facts.mapNotNull { fact ->
            val span = source.find(fact.fact)
            if (span == null) ungrounded++
            val kind = fact.kind
            if (span == null || kind == null) return@mapNotNull null
            guardedFact(message, span, kind)?.let { text -> MemoryFactSuggestionDetector.suggestionOf(kind, text) }
        }
        val modelSuggestions = kept.distinctBy { it.id }
        val regexKeys = regexSuggestions.map { it.fact.groundingFold() }
        val added = modelSuggestions
            .filterNot { suggestion ->
                val key = suggestion.fact.groundingFold()
                regexKeys.any { regexKey -> key in regexKey || regexKey in key }
            }
            .take(config.maxFacts)
        return MemoryFactExtraction(
            suggestions = (regexSuggestions + added).distinctBy { it.id },
            path = MemoryTaskPath.Model,
            modelSuggestions = modelSuggestions,
            proposedFacts = facts.size,
            ungroundedFacts = ungrounded,
            droppedFacts = facts.size - kept.size,
        )
    }

    private fun fallback(regexSuggestions: List<MemoryFactSuggestion>, reason: LocalMemoryModelFallback) =
        MemoryFactExtraction(regexSuggestions, MemoryTaskPath.HeuristicFallback, fallback = reason)
}

/** One fact item of a model answer; [kind] is null when [kindName] is not a [MemoryFactSuggestionKind]. */
internal data class RawModelFact(val kindName: String, val fact: String) {
    val kind: MemoryFactSuggestionKind? =
        MemoryFactSuggestionKind.entries.firstOrNull { it.name.equals(kindName.trim(), ignoreCase = true) }
}

/**
 * The fact items of a `{"facts":[…]}` answer, or null when it breaks the contract. The first JSON
 * object in [output] is read; text after it is ignored.
 */
internal fun parseMemoryFactModelOutput(output: String): List<RawModelFact>? {
    val start = output.indexOf('{')
    if (start < 0) return null
    val root = try {
        JSONTokener(output.substring(start)).nextValue() as? JSONObject
    } catch (_: JSONException) {
        null
    } ?: return null
    val items = root.optJSONArray("facts") ?: return null
    return (0 until items.length()).map { index ->
        val item = items.optJSONObject(index) ?: return null
        val kind = item.optNullableString("kind") ?: return null
        val fact = item.optNullableString("fact") ?: return null
        RawModelFact(kind, fact)
    }
}

internal fun memoryFactExtractionPrompt(message: String): String = "Message: $message"

internal val MEMORY_FACT_EXTRACTION_INSTRUCTION: String = """
    You find durable personal facts that the user states about themselves in one chat message.
    Answer with JSON only: {"facts":[{"kind":"KIND","fact":"TEXT"}]}
    KIND is exactly one of: Allergy, Diet, HomeLocation, WorkLocation, RelationName, ContactPhone, Birthday, Preference, Routine, Occupation.
    Allergy: what the user or a family member is allergic to. Diet: food the user never eats, or a diet such as vegetarian.
    HomeLocation: where the user lives. WorkLocation: where the user works. RelationName: the name of a family member.
    ContactPhone: a phone number of the user or a family member. Birthday: a birthday of the user or a family member.
    Preference: a lasting like, dislike or style. Routine: a recurring schedule habit. Occupation: the user's job.
    TEXT must be copied character for character from the message: one clause that starts with 我, I or my. Never translate, reword or add words.
    Skip a clause that asks or requests something, is hypothetical (如果, if) or unsure (可能, maybe), is negated (不过敏, not allergic), is about someone else (朋友, colleague), is temporary, past or one-time (今天, 现在, 最近, 以前, today, this week), or quotes or translates text. Answer {"facts":[]} when no clause is left.
    Message: 先说一句，我对虾过敏，帮我挑个餐厅
    {"facts":[{"kind":"Allergy","fact":"我对虾过敏"}]}
    Message: I'm a dentist and I live in Austin
    {"facts":[{"kind":"Occupation","fact":"I'm a dentist"},{"kind":"HomeLocation","fact":"I live in Austin"}]}
    Message: 我今天不想喝咖啡
    {"facts":[]}
    Message: 我同事对猫过敏
    {"facts":[]}
""".trimIndent()

/**
 * The guarded, user-worded fact for a grounded [span] of [message], or null when a per-fact guard
 * trips (see [LocalModelMemoryFactExtractor]).
 */
private fun guardedFact(message: String, span: IntRange, kind: MemoryFactSuggestionKind): String? {
    val clause = MemoryFactSuggestionDetector.clauses(message).firstOrNull { span.first in it.range } ?: return null
    if (span.last > clause.range.last) return null
    MemoryFactSuggestionDetector.unblockedClause(clause.value) ?: return null
    if (MODEL_PAST_TERMS.any { term -> term in clause.value.lowercase(Locale.ROOT) }) return null
    val prefix = message.substring(clause.range.first, span.first)
    if (prefix.any { it == ':' || it == '：' } || prefix.trimEnd().lastOrNull() in OPENING_QUOTES) return null
    val spanText = message.substring(span.first, span.last + 1)
    val raw = if (spanText.isFirstPerson()) spanText else message.substring(clause.range.first, span.last + 1)
    val fact = with(MemoryFactSuggestionDetector) {
        raw.trim().trimEnd(*CLAUSE_TERMINATORS).trim().removeLeadingFiller()
    }
    if (fact.length !in MIN_FACT_LENGTH..MAX_FACT_LENGTH) return null
    if (!fact.isFirstPerson() || THIRD_PARTY_REGEX.containsMatchIn(fact.groundingLower())) return null
    if (!kind.anchoredIn(fact)) return null
    return fact
}

private fun String.isFirstPerson(): Boolean = FIRST_PERSON_REGEX.containsMatchIn(groundingLower())

private fun MemoryFactSuggestionKind.anchoredIn(fact: String): Boolean {
    val lower = fact.groundingLower()
    if (this in NEGATION_SENSITIVE_KINDS && NEGATION_REGEX.containsMatchIn(lower)) return false
    return when (this) {
        MemoryFactSuggestionKind.ContactPhone -> PHONE_ANCHOR_REGEX.containsMatchIn(lower)
        MemoryFactSuggestionKind.Occupation ->
            if (lower.startsWith("我")) CHINESE_OCCUPATION_REGEXES.any { regex -> regex.containsMatchIn(lower) }
            else KIND_ANCHORS.getValue(this).any { anchor -> anchor in lower }
        else -> KIND_ANCHORS[this].orEmpty().any { anchor -> anchor in lower }
    }
}

/** Occupation nouns that end a job phrase ("我是程序员", "我在医院当护士", "我在大学读研究生"). */
private const val OCCUPATION_NOUNS =
    "老师|医生|护士|工程师|程序员|律师|会计|会计师|司机|厨师|学生|研究生|教授|记者|经理|码农|警察|军人|农民|作家|演员|画家|研究员|科学家|护工"

/**
 * The shapes a Chinese occupation fact must have: 我是一名/一位/一个/个…, 我做…工作, 我在…上班, or
 * 我是/我在/我当 followed by an occupation noun. Anything else ("我是糖尿病患者") is not a job, so the
 * fact is dropped.
 */
private val CHINESE_OCCUPATION_REGEXES = listOf(
    Regex("""^我是(?:一名|一位|一个|个)(?!人)\S{1,12}$"""),
    Regex("""^我做\S{1,12}工作$"""),
    Regex("""^我在(?!家)\S{2,20}上班$"""),
    Regex("""^我(?:是|在|当)\S{0,20}(?:$OCCUPATION_NOUNS)$"""),
)

/** Lower case, NFKC and unified apostrophes, whitespace collapsed; used by the shape guards. */
private fun String.groundingLower(): String =
    Normalizer.normalize(this, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace(WHITESPACE_RUN_REGEX, " ")
        .trim()

private const val MIN_FACT_LENGTH = 3
private const val MAX_FACT_LENGTH = 80
private const val THINK_OPEN = "<think>"
private val THINKING_REGEX = Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL)
private val OPENING_QUOTES = setOf('"', '“', '\'', '‘', '「', '『')
private val FIRST_PERSON_REGEX = Regex("""^(?:我|my\s|i\s|i'm\s|i've\s)""")
/**
 * A clause whose subject is a third party: 我/我们/我的 (with at most six characters before the noun, so
 * 我们公司的同事, 我的好朋友 and 我们老板 match) or `my` + at most six characters. `我是…` is a
 * self-description ("我是一名小学老师", "我是老板"), so it is not a third-party subject.
 */
private val THIRD_PARTY_REGEX = Regex(
    """^(?:(?!我是)我[们的]?.{0,6}?(?:朋友|同事|同学|室友|舍友|邻居|老板|领导|上司|客户|网友|老师|同桌)|""" +
        """my\s+.{0,6}?(?:friend|colleague|coworker|co-worker|roommate|flatmate|neighbou?r|boss|manager|client|teacher|classmate)s?\b)""",
)
private val MODEL_PAST_TERMS =
    listOf("小时候", "以前", "之前", "曾经", "去年", "上周", "上个月", "used to", "last year", "last week", "last month", "when i was")
private val NEGATION_SENSITIVE_KINDS = setOf(
    MemoryFactSuggestionKind.HomeLocation,
    MemoryFactSuggestionKind.WorkLocation,
    MemoryFactSuggestionKind.RelationName,
    MemoryFactSuggestionKind.ContactPhone,
    MemoryFactSuggestionKind.Birthday,
    MemoryFactSuggestionKind.Occupation,
)
private val NEGATION_REGEX = Regex("""[不没别]|\bnever\b|n't\b|\bno longer\b|\banymore\b""")
private val PHONE_ANCHOR_REGEX = Regex(MemoryFactSuggestionDetector.PHONE)

/** Likes, dislikes and recurring habits: one vocabulary for Preference and Routine, which models often swap. */
private val HABIT_ANCHORS = listOf(
    "喜欢", "爱吃", "爱喝", "爱看", "爱听", "爱玩", "热爱", "讨厌", "偏好", "偏爱", "习惯", "最爱", "受不了",
    "每天", "每周", "每晚", "每早", "每个", "每月", "每年", "平时", "通常", "一般", "总是", "经常", "工作日", "周末",
    "prefer", "like", "love", "hate", "favorite", "favourite", "enjoy", "can't stand",
    "every", "usually", "always", "daily", "weekly", "weekday", "weekend",
)

/** A fact of each kind must contain one of its anchors (matched on the lower-cased fact). */
private val KIND_ANCHORS: Map<MemoryFactSuggestionKind, List<String>> = mapOf(
    MemoryFactSuggestionKind.Allergy to listOf("过敏", "allerg"),
    MemoryFactSuggestionKind.Diet to listOf(
        "不吃", "不能吃", "不喝", "戒了", "戒酒", "忌口", "吃素", "素食", "清真",
        "vegetarian", "vegan", "don't eat", "do not eat", "never eat", "can't eat", "cannot eat", "don't drink",
        "do not drink", "halal", "kosher",
    ),
    MemoryFactSuggestionKind.HomeLocation to listOf("住", "家在", "家是", "老家", "live in", "live at", "live on", "live near", "home"),
    MemoryFactSuggestionKind.WorkLocation to listOf("上班", "工作", "公司", "单位", "办公", "work at", "work in", "work for", "office"),
    MemoryFactSuggestionKind.RelationName to listOf("叫", "名字", "name", "called"),
    MemoryFactSuggestionKind.Birthday to listOf("生日", "出生", "birthday", "born"),
    MemoryFactSuggestionKind.Preference to HABIT_ANCHORS,
    MemoryFactSuggestionKind.Routine to HABIT_ANCHORS,
    // Chinese occupation facts are matched by CHINESE_OCCUPATION_REGEXES instead of these anchors.
    MemoryFactSuggestionKind.Occupation to listOf(
        "i'm a", "i am a", "i'm an", "i am an", "work as", "my job", "for a living", "student",
    ),
)
