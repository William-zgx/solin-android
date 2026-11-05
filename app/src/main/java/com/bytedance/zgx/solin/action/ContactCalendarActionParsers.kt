package com.bytedance.zgx.solin.action

import com.bytedance.zgx.solin.device.CalendarAvailabilityQuery
import com.bytedance.zgx.solin.device.CalendarAvailabilityQueryValidation

/*
 * Rule parsers for contact lookup, contact drafts and calendar availability.
 */

private val MATCHES_ZH1_REGEX = Regex("""(?:联系人|通讯录)(?:里|里的)?\s*(?:查询|查找|查|搜索|找)""")

private val MATCHES_ZH2_REGEX =
    Regex("""(?:前|最多)\s*\d{1,2}\s*(?:个|位|条)?\s*(?:联系人|通讯录)(?:里|里的)?\s*(?:查询|查找|查|搜索|找)""")

private val WHITESPACE_RUN_REGEX = Regex("\\s+")

private val MAX_COUNT_ZH1_REGEX = Regex("""(?:前|最多)(\d{1,2})(?:个|位|条)?""")

private val MAX_COUNT_TOP_FIRST_REGEX =
    Regex("""\b(?:top|first|max|limit)\s+(\d{1,2})\s*(?:contacts?|results?)?\b""")

private val CLEANED_CONTACT_QUERY_ZH1_REGEX =
    Regex("""^(?:前|最多)\s*\d{1,2}\s*(?:个|位|条)?\s*(?:联系人|通讯录)(?:里|里的)?\s*(?:查询|查找|查|搜索|找)\s*[:：]?\s*""")

private val CLEANED_CONTACT_QUERY_ZH2_REGEX =
    Regex("""^(?:联系人|通讯录)(?:里|里的)?\s*(?:查询|查找|查|搜索|找)\s*[:：]?\s*""")

private val CLEANED_CONTACT_QUERY_ZH3_REGEX = Regex(
    """^请?\s*(?:帮我\s*)?(?:查询|查找|查|搜索|找)\s*联系人\s*[:：]?\s*""",
    RegexOption.IGNORE_CASE,
)

private val CLEANED_CONTACT_QUERY_ZH4_REGEX = Regex("""^联系人查询\s*[:：]?\s*""")

private val CLEANED_CONTACT_QUERY_ZH5_REGEX =
    Regex("""^(?:从)?通讯录(?:里|里的)?(?:查询|查找|查|搜索|找)\s*[:：]?\s*""")

private val CLEANED_CONTACT_QUERY_ZH6_REGEX =
    Regex("""^(?:查询|查找|查|搜索|找)\s*通讯录(?:里|里的)?\s*[:：]?\s*""")

private val CLEANED_CONTACT_QUERY_FIND_SEARCH_REGEX =
    Regex("""^(?:find|search|look\s+up)\s+(?:my\s+)?contacts?\s*(?:for|named|called)?\s*[:：]?\s*""", RegexOption.IGNORE_CASE)

private val CLEANED_CONTACT_QUERY_FIND_SEARCH_2_REGEX =
    Regex("""^(?:find|search|look\s+up)\s+(.+?)\s+in\s+(?:my\s+)?contacts?\s*$""", RegexOption.IGNORE_CASE)

private val CLEANED_CONTACT_QUERY_FIND_CONTACT_REGEX =
    Regex("""^find\s+(.+?)\s+contact\s+(?:number|phone)\s*$""", RegexOption.IGNORE_CASE)

private val CLEANED_CONTACT_QUERY_CONTACTS_SEARCH_REGEX =
    Regex("""^contacts?\s+(?:search|lookup|query)\s*[:：]?\s*""", RegexOption.IGNORE_CASE)

private val CLEANED_CONTACT_QUERY_ZH7_REGEX =
    Regex("""(?:^|[\s，,;；]*)?(?:前|最多)\s*\d{1,2}\s*(?:个|位|条)?""")

private val CLEANED_CONTACT_QUERY_TOP_FIRST_REGEX =
    Regex("""(?:^|[\s,;]*)?(?:top|first|max|limit)\s*\d{1,2}\s*(?:contacts?|results?)?""", RegexOption.IGNORE_CASE)

private val CLEANED_CONTACT_QUERY_CONTACTS_REGEX =
    Regex("""\bcontacts?\b""", RegexOption.IGNORE_CASE)

private val CONTACT_QUERY_NON_ACTION_DO_NOT_REGEX =
    Regex("""\b(do\s+not|don't|dont)\s+(?:find|search|look\s+up)\s+(?:my\s+)?contacts?\b""")

private val CONTACT_QUERY_NON_ACTION_CONTACTS_PERMISSION_REGEX =
    Regex("""\b(contacts?\s+(permission|permissions|form|page|component|api|screen|tracing|support|provider)|contactscontract|delete\s+contacts?|edit\s+contacts?|export\s+contacts?|all\s+contacts?|contacts?\s+list)\b""")

private val CONTACTS_DISCUSSION_REGEX =
    Regex("""\b(how\s+do\s+i|how\s+to|implement|design)\b.*\bcontacts?\b""")

internal object ContactQueryActionParser {
    private val englishPattern =
        Regex(
            """\b(?:find|search|look\s+up)\s+(?:my\s+)?contacts?\b|\b(?:find|search|look\s+up)\s+.+\s+in\s+(?:my\s+)?contacts?\b|\bfind\s+.+\s+contact\s+(?:number|phone)\b|\bcontacts?\s+(?:search|lookup|query)\b""",
            RegexOption.IGNORE_CASE,
        )

    fun matches(input: String): Boolean {
        if (input.looksLikeContactQueryNonAction()) return false
        val normalized = input.lowercase()
        val hasChineseTrigger = listOf(
            "查询联系人",
            "查联系人",
            "查找联系人",
            "搜索联系人",
            "找联系人",
            "联系人查询",
            "通讯录找",
            "通讯录里找",
            "搜索通讯录",
            "查通讯录",
        ).any { it in input } ||
            MATCHES_ZH1_REGEX.containsMatchIn(input) ||
            MATCHES_ZH2_REGEX.containsMatchIn(input)
        if (!hasChineseTrigger && !englishPattern.containsMatchIn(normalized)) return false
        return cleanedContactQuery(input).isMeaningfulContactQuery()
    }

    fun draft(input: String): ActionDraft {
        val parameters = parameters(input)
        val query = parameters.getValue("query")
        return ActionDraft(
            functionName = MobileActionFunctions.QUERY_CONTACTS,
            title = "查询联系人",
            summary = summary(query, parameters["maxCount"]),
            parameters = parameters,
            requiresConfirmation = true,
        )
    }

    private fun parameters(input: String): Map<String, String> {
        val maxCount = maxCount(input)
        val query = cleanedContactQuery(input)
        return buildMap {
            put("query", query)
            if (maxCount != null) {
                put("maxCount", maxCount)
            }
        }
    }

    private fun maxCount(input: String): String? {
        val cleaned = input.replace(WHITESPACE_RUN_REGEX, "")
        val normalized = input.lowercase()
        val match = MAX_COUNT_ZH1_REGEX.find(cleaned)
            ?: MAX_COUNT_TOP_FIRST_REGEX.find(normalized)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }?.toString()
    }

    private fun cleanedContactQuery(input: String): String {
        val cleaned = cleanedObject(input)
        return cleaned
            .replace(
                CLEANED_CONTACT_QUERY_ZH1_REGEX,
                "",
            )
            .replace(
                CLEANED_CONTACT_QUERY_ZH2_REGEX,
                "",
            )
            .replace(
                CLEANED_CONTACT_QUERY_ZH3_REGEX,
                "",
            )
            .replace(CLEANED_CONTACT_QUERY_ZH4_REGEX, "")
            .replace(CLEANED_CONTACT_QUERY_ZH5_REGEX, "")
            .replace(CLEANED_CONTACT_QUERY_ZH6_REGEX, "")
            .replace(
                CLEANED_CONTACT_QUERY_FIND_SEARCH_REGEX,
                "",
            )
            .replace(
                CLEANED_CONTACT_QUERY_FIND_SEARCH_2_REGEX,
                "$1",
            )
            .replace(
                CLEANED_CONTACT_QUERY_FIND_CONTACT_REGEX,
                "$1",
            )
            .replace(CLEANED_CONTACT_QUERY_CONTACTS_SEARCH_REGEX, "")
            .replace(CLEANED_CONTACT_QUERY_ZH7_REGEX, "")
            .replace(CLEANED_CONTACT_QUERY_TOP_FIRST_REGEX, "")
            .replace(CLEANED_CONTACT_QUERY_CONTACTS_REGEX, "")
            .replace("联系人", "")
            .trim()
    }

    private fun summary(query: String, maxCount: String?): String =
        if (maxCount.isNullOrBlank()) {
            "将按“$query”查询联系人。"
        } else {
            "将按“$query”查询最多 ${maxCount} 个联系人。"
        }

    private fun String.isMeaningfulContactQuery(): Boolean {
        val normalized = lowercase()
        return isNotBlank() &&
            normalized !in setOf("contact", "contacts", "联系人", "permission", "permissions") &&
            this !in setOf("权限", "联系人权限")
    }

    private fun String.looksLikeContactQueryNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
            "联系人权限",
            "联系人列表权限",
            "通讯录权限",
            "联系人页面",
            "联系人组件",
            "联系人接口",
            "联系人列表",
            "通讯录列表",
            "读取所有联系人",
            "所有联系人",
            "导出通讯录",
            "不要查联系人",
            "不要查询联系人",
            "别查联系人",
            "编辑联系人",
            "怎么实现",
            "如何实现",
            "怎么设计",
        ).any { it in this } ||
            normalized.contains(CONTACT_QUERY_NON_ACTION_DO_NOT_REGEX) ||
            normalized.contains(CONTACT_QUERY_NON_ACTION_CONTACTS_PERMISSION_REGEX) ||
            normalized.contains(CONTACTS_DISCUSSION_REGEX)
    }
}

private val CLEANED_CONTACT_NAME_ZH1_REGEX = Regex("""^(?:新建|创建|添加)(?:一个|一位)?联系人\s*[:：]?\s*""")

private val CLEANED_CONTACT_NAME_ZH2_REGEX = Regex("""^联系人草稿\s*[:：]?\s*""")

private val CLEANED_CONTACT_NAME_CREATE_ADD_REGEX =
    Regex("""^(?:create|add|new)\s+(?:a\s+)?contacts?\s*[:：]?\s*""", RegexOption.IGNORE_CASE)

private val CLEANED_CONTACT_NAME_CONTACTS_DRAFT_REGEX =
    Regex("""^contacts?\s+draft\s*[:：]?\s*""", RegexOption.IGNORE_CASE)

private val CONTACT_DRAFT_NON_ACTION_DO_NOT_REGEX =
    Regex("""\b(do\s+not|don't|dont)\s+(?:create|add|new)\s+(?:a\s+)?contacts?\b""")

private val CONTACT_DRAFT_NON_ACTION_CONTACTS_PERMISSION_REGEX =
    Regex("""\b(contacts?\s+(permission|permissions|form|page|component|api|screen|tracing|support|provider|list)|contactscontract|delete\s+contacts?|edit\s+contacts?|export\s+contacts?|all\s+contacts?)\b""")

internal object ContactDraftActionParser {
    private val englishPattern =
        Regex("""\b(?:create|add|new)\s+(?:a\s+)?contacts?\b|\bcontacts?\s+draft\b""", RegexOption.IGNORE_CASE)
    private val emailPattern =
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val phonePattern =
        Regex("""(?:\+?\d[\d\s().-]{5,}\d)""")

    fun matches(input: String): Boolean {
        if (input.looksLikeContactDraftNonAction()) return false
        val normalized = input.lowercase()
        val hasTrigger = listOf("新建联系人", "创建联系人", "添加联系人", "联系人草稿")
            .any { it in input } ||
            englishPattern.containsMatchIn(normalized)
        return hasTrigger && cleanedContactName(input).isMeaningfulContactDraftName()
    }

    fun draft(input: String): ActionDraft {
        val parameters = parameters(input)
        return ActionDraft(
            functionName = MobileActionFunctions.CREATE_CONTACT_DRAFT,
            title = "联系人草稿",
            summary = "将打开联系人新建页面。",
            parameters = parameters,
            requiresConfirmation = true,
        )
    }

    private fun parameters(input: String): Map<String, String> {
        val email = emailPattern.find(input)?.value
        val phone = phonePattern.find(input)?.value?.trim()
        val name = cleanedContactName(input)
        return buildMap {
            put("name", name)
            if (!email.isNullOrBlank()) put("email", email)
            if (!phone.isNullOrBlank()) put("phone", phone)
        }
    }

    private fun cleanedContactName(input: String): String =
        cleanedObject(input)
            .replace(CLEANED_CONTACT_NAME_ZH1_REGEX, "")
            .replace(CLEANED_CONTACT_NAME_ZH2_REGEX, "")
            .replace(CLEANED_CONTACT_NAME_CREATE_ADD_REGEX, "")
            .replace(CLEANED_CONTACT_NAME_CONTACTS_DRAFT_REGEX, "")
            .replace(emailPattern, "")
            .replace(phonePattern, "")
            .trim()

    private fun String.isMeaningfulContactDraftName(): Boolean {
        val normalized = lowercase()
        return isNotBlank() &&
            normalized !in setOf("contact", "contacts", "联系人", "draft") &&
            this !in setOf("权限", "联系人权限")
    }

    private fun String.looksLikeContactDraftNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
            "不要新建联系人",
            "不要创建联系人",
            "不要添加联系人",
            "别新建联系人",
            "别创建联系人",
            "别添加联系人",
            "联系人权限",
            "通讯录权限",
            "联系人页面",
            "联系人组件",
            "联系人接口",
            "联系人列表",
            "通讯录列表",
            "读取联系人",
            "查询联系人",
            "查联系人",
            "搜索联系人",
            "编辑联系人",
            "删除联系人",
            "导出联系人",
            "所有联系人",
            "全量联系人",
            "怎么实现",
            "如何实现",
            "怎么设计",
        ).any { it in this } ||
            normalized.contains(CONTACT_DRAFT_NON_ACTION_DO_NOT_REGEX) ||
            normalized.contains(CONTACT_DRAFT_NON_ACTION_CONTACTS_PERMISSION_REGEX) ||
            normalized.contains(CONTACTS_DISCUSSION_REGEX)
    }
}

private val CALENDAR_AVAILABILITY_NON_ACTION_DO_NOT_REGEX =
    Regex("""\b(do\s+not|don't|dont)\s+(?:check|query|read)\s+(?:calendar\s+)?(?:availability|free/busy|free busy)\b""")

private val CALENDAR_AVAILABILITY_NON_ACTION_WHAT_IS_REGEX =
    Regex("""\b(what\s+is\s+free/busy|calendar\s+availability\s+(api|screen|component|implementation|design|schema|tests?|parser|docs?)|free/busy\s+(api|schema|tests?|parser|docs?)|availability\s+api)\b""")

private val CALENDAR_AVAILABILITY_NON_ACTION_EVENT_DETAILS_REGEX =
    Regex("""\b(event\s+(details?|titles?|locations?|attendees?)|export\s+calendar|all\s+calendar)\b""")

private val CALENDAR_AVAILABILITY_NON_ACTION_HOW_DO_REGEX =
    Regex("""\b(how\s+do\s+i|how\s+to|implement|design)\b.*\b(calendar\s+availability|free/busy|free busy|availability)\b""")

internal object CalendarAvailabilityActionParser {
    private val isoOffsetDateTimePattern =
        Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})""")
    private val englishPattern =
        Regex(
            """\b(calendar\s+availability|calendar\s+free/busy|calendar\s+free busy|free/busy|free busy|am\s+i\s+(?:free|available))\b""",
            RegexOption.IGNORE_CASE,
        )

    fun matches(input: String): Boolean {
        if (input.looksLikeCalendarAvailabilityNonAction()) return false
        val normalized = input.lowercase()
        val hasTrigger = listOf("忙闲", "空闲", "有空", "日历可用", "日历占用").any { it in input } ||
            englishPattern.containsMatchIn(normalized)
        return hasTrigger && parameters(input) != null
    }

    fun draft(input: String): ActionDraft {
        val parameters = parameters(input).orEmpty()
        return ActionDraft(
            functionName = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
            title = "查询日历忙闲",
            summary = "将只读查询本机日历忙闲：${parameters["start"].orEmpty()} 到 ${parameters["end"].orEmpty()}",
            parameters = parameters,
            requiresConfirmation = true,
        )
    }

    private fun parameters(input: String): Map<String, String>? {
        val matches = isoOffsetDateTimePattern.findAll(input).map { it.value }.take(2).toList()
        if (matches.size < 2) return null
        val validation = CalendarAvailabilityQuery.parseWindow(matches[0], matches[1])
        if (validation !is CalendarAvailabilityQueryValidation.Valid) return null
        return mapOf(
            "start" to matches[0],
            "end" to matches[1],
        )
    }

    private fun String.looksLikeCalendarAvailabilityNonAction(): Boolean {
        val normalized = lowercase()
        return startsWithActionNegation() ||
            listOf(
            "日历权限",
            "不要查忙闲",
            "不要查询忙闲",
            "别查忙闲",
            "不要查日历忙闲",
            "别查日历忙闲",
            "读取事件",
            "事件详情",
            "事件标题",
            "日历标题",
            "日历地点",
            "参与人",
            "导出日历",
            "全量日历",
            "忙闲是什么意思",
            "忙闲怎么实现",
            "日历忙闲怎么实现",
            "怎么设计",
            "如何实现",
        ).any { it in this } ||
            normalized.contains(CALENDAR_AVAILABILITY_NON_ACTION_DO_NOT_REGEX) ||
            normalized.contains(CALENDAR_AVAILABILITY_NON_ACTION_WHAT_IS_REGEX) ||
            normalized.contains(CALENDAR_AVAILABILITY_NON_ACTION_EVENT_DETAILS_REGEX) ||
            normalized.contains(CALENDAR_AVAILABILITY_NON_ACTION_HOW_DO_REGEX)
    }
}
