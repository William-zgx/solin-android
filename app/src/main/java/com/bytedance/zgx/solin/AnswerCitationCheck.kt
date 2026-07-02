package com.bytedance.zgx.solin

private val ANSWER_CITATION_REGEX = Regex("""\[(S\d+)]""")
private val OVER_CERTAIN_PUBLIC_EVIDENCE_REGEX =
    Regex("""(?i)(已证实|证实了|确认了|可以确定|毫无疑问|一定是|confirmed|proven|definitely|certainly)""")

enum class AnswerCitationCheckStatus {
    Pass,
    Fail,
    Warning,
}

data class AnswerCitationCheck(
    val status: AnswerCitationCheckStatus,
    val reason: String,
    val missingSourceIds: Set<String> = emptySet(),
) {
    val shouldRetry: Boolean
        get() = status == AnswerCitationCheckStatus.Fail
}

fun checkPublicWebAnswerCitations(
    answer: String,
    sourceIds: Set<String>,
    hasLowQualityEvidence: Boolean,
    hasVerifiableUrl: Boolean,
): AnswerCitationCheck {
    if (sourceIds.isEmpty()) {
        return AnswerCitationCheck(AnswerCitationCheckStatus.Pass, "no_public_web_evidence")
    }
    val citedIds = ANSWER_CITATION_REGEX.findAll(answer)
        .map { match -> match.groupValues[1] }
        .toSet()
    if (citedIds.isEmpty()) {
        return AnswerCitationCheck(AnswerCitationCheckStatus.Fail, "missing_citation")
    }
    val invalidIds = citedIds - sourceIds
    if (invalidIds.isNotEmpty()) {
        return AnswerCitationCheck(
            status = AnswerCitationCheckStatus.Fail,
            reason = "unknown_source_id",
            missingSourceIds = invalidIds,
        )
    }
    if (!hasVerifiableUrl && OVER_CERTAIN_PUBLIC_EVIDENCE_REGEX.containsMatchIn(answer)) {
        return AnswerCitationCheck(AnswerCitationCheckStatus.Fail, "unverifiable_confirmation_claim")
    }
    if (hasLowQualityEvidence && OVER_CERTAIN_PUBLIC_EVIDENCE_REGEX.containsMatchIn(answer)) {
        return AnswerCitationCheck(AnswerCitationCheckStatus.Warning, "low_quality_over_certainty")
    }
    return AnswerCitationCheck(AnswerCitationCheckStatus.Pass, "citations_valid")
}
