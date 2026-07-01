package com.bytedance.zgx.solin.device

data class AppSearchProgressSignal(
    val uiActionOutcome: String,
    val uiActionOutcomeReason: String,
    val appSearchProgressStage: String,
) {
    fun toData(): Map<String, String> =
        mapOf(
            "uiActionOutcome" to uiActionOutcome,
            "uiActionOutcomeReason" to uiActionOutcomeReason,
            "appSearchProgressStage" to appSearchProgressStage,
        )

    fun toPromptText(): String =
        "stage=$appSearchProgressStage outcome=$uiActionOutcome reason=$uiActionOutcomeReason"
}

object AppSearchProgressEvidence {
    private const val OUTCOME_ADVANCED = "advanced"
    private const val OUTCOME_NO_CHANGE = "no_change"
    private const val OUTCOME_WRONG_SURFACE = "wrong_surface"
    private const val OUTCOME_BLOCKED = "blocked"
    private const val OUTCOME_VERIFIED = "verified"
    private const val OUTCOME_UNKNOWN = "unknown"

    private const val STAGE_OPENED = "opened"
    private const val STAGE_OBSERVED_ENTRY = "observed_entry"
    private const val STAGE_ENTRY_TAPPED = "entry_tapped"
    private const val STAGE_INPUT_READY = "input_ready"
    private const val STAGE_QUERY_TYPED = "query_typed"
    private const val STAGE_SUBMITTED = "submitted"
    private const val STAGE_VERIFIED = "verified"
    private const val STAGE_BLOCKED = "blocked"
    private const val STAGE_UNKNOWN = "unknown"

    fun fromData(data: Map<String, String>): AppSearchProgressSignal {
        val failureKind = data["failureKind"].orEmpty()
        val searchVerificationStatus = data["searchVerificationStatus"].orEmpty()
        val diffSummary = data["screenObservationDiffSummary"].orEmpty()
        val actionType = data["actionType"].orEmpty()
        val status = data["status"].orEmpty()
        val toolName = data["toolName"].orEmpty()

        val outcome = when {
            searchVerificationStatus == "verified" -> OUTCOME_VERIFIED
            failureKind == "app_not_foreground" || data.hasPackageMismatch() -> OUTCOME_WRONG_SURFACE
            failureKind == "permission_missing" || failureKind == "dangerous_action" -> OUTCOME_BLOCKED
            diffSummary.containsChanged(false) -> OUTCOME_NO_CHANGE
            diffSummary.containsChanged(true) -> OUTCOME_ADVANCED
            actionType == "type_text" && status.isSucceededStatus() -> OUTCOME_ADVANCED
            actionType == "submit_search" && status.isSucceededStatus() -> OUTCOME_ADVANCED
            status.isSucceededStatus() && (actionType.isNotBlank() || toolName == "open_app_by_name") ->
                OUTCOME_ADVANCED
            else -> OUTCOME_UNKNOWN
        }
        val stage = data.progressStage(actionType, status, toolName, outcome)
        return AppSearchProgressSignal(
            uiActionOutcome = outcome,
            uiActionOutcomeReason = data.outcomeReason(outcome, actionType, status, diffSummary),
            appSearchProgressStage = stage,
        )
    }

    private fun Map<String, String>.progressStage(
        actionType: String,
        status: String,
        toolName: String,
        outcome: String,
    ): String =
        when {
            outcome == OUTCOME_VERIFIED -> STAGE_VERIFIED
            outcome == OUTCOME_BLOCKED -> STAGE_BLOCKED
            toolName == "open_app_by_name" && status.isSucceededStatus() -> STAGE_OPENED
            actionType == "submit_search" && status.isSucceededStatus() -> STAGE_SUBMITTED
            actionType == "type_text" && status.isSucceededStatus() -> STAGE_QUERY_TYPED
            actionType == "tap" && status.isSucceededStatus() && currentObservation()?.hasEditableTarget() == true ->
                STAGE_INPUT_READY
            actionType == "tap" && status.isSucceededStatus() -> STAGE_ENTRY_TAPPED
            currentObservation()?.hasEditableTarget() == true -> STAGE_INPUT_READY
            currentObservation()?.hasSearchEntryTarget() == true -> STAGE_OBSERVED_ENTRY
            else -> STAGE_UNKNOWN
        }

    private fun Map<String, String>.outcomeReason(
        outcome: String,
        actionType: String,
        status: String,
        diffSummary: String,
    ): String =
        when (outcome) {
            OUTCOME_VERIFIED -> "search_verified"
            OUTCOME_WRONG_SURFACE -> "app_not_foreground"
            OUTCOME_BLOCKED -> when (this["failureKind"]) {
                "dangerous_action" -> "dangerous_action"
                else -> "permission_missing"
            }
            OUTCOME_NO_CHANGE -> "changed_false"
            OUTCOME_ADVANCED -> when {
                diffSummary.containsChanged(true) -> "screen_changed"
                actionType == "type_text" && status.isSucceededStatus() -> "type_text_succeeded"
                actionType == "submit_search" && status.isSucceededStatus() -> "submit_search_succeeded"
                else -> "status_succeeded"
            }
            else -> "unknown"
        }

    private fun Map<String, String>.hasPackageMismatch(): Boolean {
        val expected = this["expectedPackageName"]?.takeIf { it.isNotBlank() } ?: return false
        val actual = this["actualPackageName"]?.takeIf { it.isNotBlank() } ?: return false
        return expected != actual
    }

    private fun Map<String, String>.currentObservation(): ScreenObservation? =
        dataObservation("afterScreenObservationJson")
            ?: dataObservation("screenObservationJson")
            ?: dataObservation("beforeScreenObservationJson")

    private fun Map<String, String>.dataObservation(key: String): ScreenObservation? =
        this[key]
            ?.takeIf { it.isNotBlank() }
            ?.let { screenObservationFromJsonStringOrNull(it) }

    private fun ScreenObservation.hasEditableTarget(): Boolean =
        UiTargetResolver.explain(this, UiTargetKind.EditableField)
            .rankedCandidates
            .any { candidate -> candidate.enabled && candidate.editable }

    private fun ScreenObservation.hasSearchEntryTarget(): Boolean =
        UiTargetResolver.explain(this, UiTargetKind.SearchEntry)
            .rankedCandidates
            .any { candidate -> candidate.enabled && (candidate.clickable || candidate.editable) }

    private fun String.containsChanged(value: Boolean): Boolean =
        contains(Regex("""(?:^|;)changed=$value(?:;|$)"""))

    private fun String.isSucceededStatus(): Boolean =
        equals("succeeded", ignoreCase = true)
}
