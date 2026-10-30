package com.bytedance.zgx.solin.memory

import com.bytedance.zgx.solin.MessagePrivacy
import java.util.Locale

/** An action-argument slot that may be filled from the user's own remembered facts. */
sealed interface MemoryArgumentSlot {
    /** Home address / location, e.g. for "导航回家". */
    data object HomeAddress : MemoryArgumentSlot

    /** Work address / location, e.g. for "导航去公司". */
    data object WorkAddress : MemoryArgumentSlot

    /** Phone number of a named contact or relation, e.g. "老婆", "妈妈", "Emily". */
    data class ContactPhone(val contact: String) : MemoryArgumentSlot

    /** Name of a relation, e.g. "老婆" -> "林小雨". */
    data class RelationName(val relation: String) : MemoryArgumentSlot
}

/**
 * A slot value taken from one explicit local memory record.
 *
 * Always [MessagePrivacy.LocalOnly]: callers must treat [value] like the memory record it came from
 * (never send it to a remote model or remote audit, and surface it in the action confirmation so
 * the user sees exactly what will be used).
 */
data class ResolvedMemoryArgument(
    val slot: MemoryArgumentSlot,
    val value: String,
    val recordId: String,
) {
    val privacy: MessagePrivacy = MessagePrivacy.LocalOnly
}

/**
 * Pure resolver that fills action-argument slots from explicit, user-stated local memory.
 *
 * Fail-closed rules:
 * - only [MemoryRecordType.UserFact] / [MemoryRecordType.Preference] records that are LocalOnly
 *   and not expired are considered (no conversation snippets, no task bookkeeping);
 * - when the records disagree (two different home addresses, two phone numbers for "老婆"), the
 *   resolver returns null instead of guessing.
 */
object MemoryArgumentResolver {
    fun resolve(
        slot: MemoryArgumentSlot,
        records: List<PersistedMemoryRecord>,
        nowMillis: Long = System.currentTimeMillis(),
    ): ResolvedMemoryArgument? {
        val facts = records
            .filter { record -> record.isResolvableFact(nowMillis) }
            .map { record -> record.id to record.text.withoutFactLabel() }
        val candidates = when (slot) {
            MemoryArgumentSlot.HomeAddress -> facts.mapNotNull { (id, text) -> extractFirst(text, HOME_PATTERNS)?.let { id to it } }
            MemoryArgumentSlot.WorkAddress -> facts.mapNotNull { (id, text) -> extractFirst(text, WORK_PATTERNS)?.let { id to it } }
            is MemoryArgumentSlot.ContactPhone -> contactPhoneCandidates(slot.contact, facts)
            is MemoryArgumentSlot.RelationName -> relationNameCandidates(slot.relation, facts)
        }
        val distinctValues = candidates.map { (_, value) -> value.normalizedValueKey() }.distinct()
        if (distinctValues.size != 1) return null
        val (recordId, value) = candidates.last()
        return ResolvedMemoryArgument(slot = slot, value = value, recordId = recordId)
    }

    /** Convenience over [LongTermMemoryControls]; storage failures resolve to null (fail-closed). */
    fun resolve(
        slot: MemoryArgumentSlot,
        controls: LongTermMemoryControls,
        nowMillis: Long = System.currentTimeMillis(),
    ): ResolvedMemoryArgument? =
        runCatching { controls.savedRecords() }.getOrNull()?.let { records -> resolve(slot, records, nowMillis) }

    /**
     * Maps a free-text place target (e.g. the `query` of a map search) to a memory slot:
     * "家" / "回家" / "home" -> [MemoryArgumentSlot.HomeAddress]; "公司" / "单位" / "work" / "office" ->
     * [MemoryArgumentSlot.WorkAddress]. Anything else (a real place name) returns null.
     */
    fun placeSlotFor(target: String): MemoryArgumentSlot? {
        val normalized = target.trim().lowercase(Locale.ROOT).trimEnd(*TRAILING_PUNCTUATION)
            .removePrefix("我的").removePrefix("我").trim()
        return when (normalized) {
            in HOME_TARGETS -> MemoryArgumentSlot.HomeAddress
            in WORK_TARGETS -> MemoryArgumentSlot.WorkAddress
            else -> null
        }
    }

    private fun contactPhoneCandidates(contact: String, facts: List<Pair<String, String>>): List<Pair<String, String>> {
        val names = contactAliases(contact) + relationNameCandidates(contact, facts).map { (_, name) -> name }
        val relations = relationsNamed(contact, facts)
        val keys = (names + relations.flatMap(::contactAliases)).map { it.lowercase(Locale.ROOT) }.toSet()
        return facts.mapNotNull { (id, text) ->
            phoneForAnyContact(text, keys)?.let { phone -> id to phone }
        }
    }

    private fun relationNameCandidates(relation: String, facts: List<Pair<String, String>>): List<Pair<String, String>> {
        val aliases = contactAliases(relation)
        return facts.mapNotNull { (id, text) ->
            aliases.firstNotNullOfOrNull { alias -> nameOfRelation(text, alias) }?.let { name -> id to name }
        }
    }

    /** Relations whose remembered name equals [name] ("林小雨" -> ["老婆"]). */
    private fun relationsNamed(name: String, facts: List<Pair<String, String>>): List<String> {
        val target = name.trim().lowercase(Locale.ROOT)
        return RELATION_GROUPS.flatten().filter { relation ->
            facts.any { (_, text) -> nameOfRelation(text, relation)?.lowercase(Locale.ROOT) == target }
        }
    }

    private fun nameOfRelation(text: String, relation: String): String? {
        val escaped = Regex.escape(relation)
        val patterns = listOf(
            Regex("""^我(?:的)?$escaped(?:叫|名叫|的名字(?:是|叫))\s*(.{1,20}?)$"""),
            Regex("""^my\s+$escaped(?:'s\s+name\s+is|\s+is\s+called|\s+is\s+named)\s+(.{1,40}?)$""", RegexOption.IGNORE_CASE),
        )
        return extractFirst(text, patterns)
    }

    private fun phoneForAnyContact(text: String, contactKeys: Set<String>): String? {
        val match = CONTACT_PHONE_ZH.find(text) ?: CONTACT_PHONE_EN.find(text) ?: return null
        val owner = match.groupValues[1].trim().lowercase(Locale.ROOT)
        if (owner !in contactKeys) return null
        return match.groupValues[2].replace(PHONE_SEPARATORS, "")
    }

    private fun contactAliases(contact: String): Set<String> {
        val normalized = contact.trim().lowercase(Locale.ROOT).removePrefix("我的").removePrefix("我").removePrefix("my ").trim()
        val group = RELATION_GROUPS.firstOrNull { normalized in it }
        return group ?: setOf(normalized)
    }

    private fun extractFirst(text: String, patterns: List<Regex>): String? =
        patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(text)?.groupValues?.getOrNull(1)?.trim()?.trimEnd(*TRAILING_PUNCTUATION)?.trim()?.takeIf { it.isNotBlank() }
        }

    private fun PersistedMemoryRecord.isResolvableFact(nowMillis: Long): Boolean =
        (type == MemoryRecordType.UserFact || type == MemoryRecordType.Preference) &&
            privacy == MessagePrivacy.LocalOnly &&
            (expiresAtMillis == null || expiresAtMillis > nowMillis)

    private fun String.withoutFactLabel(): String =
        trim().removePrefix("用户事实：").removePrefix("用户偏好：").trim().trimEnd(*TRAILING_PUNCTUATION).trim()

    private fun String.normalizedValueKey(): String =
        lowercase(Locale.ROOT).replace(WHITESPACE_RUN_REGEX, "")

    private val TRAILING_PUNCTUATION = charArrayOf('。', '.', '！', '!', '；', ';', '，', ',', ' ')
    private val PHONE_SEPARATORS = Regex("""[\s-]""")
    private const val PHONE = """(\+?\d[\d\s-]{4,18}\d)"""

    private val HOME_PATTERNS = listOf(
        Regex("""^我(?:的)?(?:家庭住址|家庭地址|住址|家的地址|家地址)(?:是|为|在)[:：]?\s*(.+)$"""),
        Regex("""^我家(?:住)?在\s*(.+)$"""),
        Regex("""^我(?:现在)?住在\s*(.+)$"""),
        Regex("""^my\s+(?:home\s+)?address\s+is\s+(.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^my\s+home\s+is\s+(?:in|at)\s+(.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^i\s+live\s+(?:in|at)\s+(.+)$""", RegexOption.IGNORE_CASE),
    )

    private val WORK_PATTERNS = listOf(
        Regex("""^我(?:的)?(?:公司|单位|办公室)(?:地址)?(?:是|为|在)[:：]?\s*(.+)$"""),
        Regex("""^我(?:的)?上班地点(?:是|在)\s*(.+)$"""),
        Regex("""^我在\s*(.+?)(?:上班|工作)$"""),
        Regex("""^my\s+(?:office|work)(?:\s+address)?\s+is\s+(?:in\s+|at\s+)?(.+)$""", RegexOption.IGNORE_CASE),
        Regex("""^i\s+work\s+(?:at|in)\s+(.+)$""", RegexOption.IGNORE_CASE),
    )

    private val CONTACT_PHONE_ZH = Regex(
        """^(?:我的?)?(.{1,10}?)的?(?:电话号码|手机号码|手机号|电话|手机|号码)(?:是|为)?[:：]?\s*$PHONE$""",
    )
    private val CONTACT_PHONE_EN = Regex(
        """^(?:my\s+)?(.{1,30}?)'s\s+(?:phone\s+number|phone|mobile(?:\s+number)?|number)\s+is\s+$PHONE$""",
        RegexOption.IGNORE_CASE,
    )

    private val RELATION_GROUPS: List<Set<String>> = listOf(
        setOf("老婆", "妻子", "太太", "媳妇", "爱人", "wife"),
        setOf("老公", "丈夫", "先生", "husband"),
        setOf("妈妈", "妈", "母亲", "老妈", "mom", "mother"),
        setOf("爸爸", "爸", "父亲", "老爸", "dad", "father"),
        setOf("儿子", "son"),
        setOf("女儿", "daughter"),
        setOf("女朋友", "女友", "girlfriend"),
        setOf("男朋友", "男友", "boyfriend"),
    )

    private val HOME_TARGETS = setOf("家", "回家", "家里", "home", "my home", "家里面")
    private val WORK_TARGETS = setOf("公司", "单位", "办公室", "上班的地方", "work", "office", "the office")
}
