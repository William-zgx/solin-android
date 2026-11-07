package com.bytedance.zgx.solin.orchestration.collab

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.AppInteractionProfiles
import com.bytedance.zgx.solin.device.ScreenObservation
import com.bytedance.zgx.solin.device.UiActionFailureKind
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * What one milestone asks the device to reach. The set is closed and has no intent that pays,
 * sends, deletes, publishes, orders, transfers or authorizes: those controls stay with the user.
 */
enum class MilestoneIntent(val wireName: String, internal val slots: Set<MilestoneSlot>) {
    OpenApp("open_app", setOf(MilestoneSlot.App)),
    SearchInApp("search_in_app", setOf(MilestoneSlot.App, MilestoneSlot.Query)),

    /** Sort or filter the current results by a term from the request, e.g. 销量. */
    RefineResults("refine_results", setOf(MilestoneSlot.App, MilestoneSlot.Query)),

    /** Read the visible results. What is read is LocalOnly, so the answer is written on the device. */
    ReadResults("read_results", setOf(MilestoneSlot.App)),

    /** Type the user's own words into a field without submitting or sending them. */
    EnterDraftText("enter_draft_text", setOf(MilestoneSlot.App, MilestoneSlot.Query)),
    Navigate("navigate", setOf(MilestoneSlot.App, MilestoneSlot.Query)),

    /** Call one allowlisted tool; the device grounds its arguments from the request. */
    UseTool("use_tool", setOf(MilestoneSlot.Tool)),
}

internal enum class MilestoneSlot(val key: String) {
    App("app"),
    Query("query"),
    Tool("tool"),
}

/** One step of a [MilestonePlan]. Slots that [intent] does not use are null. */
data class Milestone(
    val intent: MilestoneIntent,
    /** Package name from [MilestoneVocabulary.apps]. */
    val app: String? = null,
    /** A span copied literally from the user's request. */
    val query: String? = null,
    /** Tool name from [MilestoneVocabulary.tools]. */
    val tool: String? = null,
)

/** A plan accepted by [MilestonePlanParser]. Executors act only on plans that came from the parser. */
data class MilestonePlan(val milestones: List<Milestone>)

/**
 * The public words a planner may use. It is static app data, never read from the phone: the
 * installed-app list and the foreground app are device-derived and stay local.
 */
data class MilestoneVocabulary(val apps: Set<String>, val tools: Set<String>) {
    companion object {
        /** Tools that read nothing private and hand no text to another app. */
        val DEFAULT_TOOLS: Set<String> = setOf(
            MobileActionFunctions.SET_SYSTEM_ALARM,
            MobileActionFunctions.SET_SYSTEM_TIMER,
            MobileActionFunctions.SCHEDULE_REMINDER,
            MobileActionFunctions.OPEN_WIFI_SETTINGS,
            MobileActionFunctions.OPEN_SYSTEM_SETTINGS,
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
            MobileActionFunctions.OPEN_CAMERA,
        )

        val DEFAULT: MilestoneVocabulary = MilestoneVocabulary(
            apps = AppInteractionProfiles.profiles.flatMapTo(mutableSetOf()) { profile -> profile.packageNames },
            tools = DEFAULT_TOOLS,
        )
    }
}

/** Why a plan was refused. Enum only, so a rejection can be audited without the planner's text. */
enum class MilestoneRejection {
    TooLarge,
    NotJsonObject,
    TrailingContent,
    UnsupportedVersion,
    UnexpectedKey,
    MissingKey,
    WrongType,
    EmptyPlan,
    TooManyMilestones,
    UnknownIntent,
    AppNotAllowed,
    ToolNotAllowed,
    InvalidQuery,
    QueryNotInRequest,
}

sealed interface MilestoneParseResult {
    data class Accepted(val plan: MilestonePlan) : MilestoneParseResult

    /** [index] is the offending milestone, or null for a plan-level problem. */
    data class Rejected(val reason: MilestoneRejection, val index: Int? = null) : MilestoneParseResult
}

/**
 * Strict reader for planner output, cloud or local. The whole reply must be one JSON object:
 *
 * ```
 * {"version":1,"milestones":[{"intent":"open_app","app":"com.taobao.taobao"},
 *   {"intent":"search_in_app","app":"com.taobao.taobao","query":"牛奶"}]}
 * ```
 *
 * Each milestone carries exactly the keys its intent needs. Apps and tools come from the vocabulary
 * and a query is copied literally from the user's request, so no text the planner invented reaches
 * the device. Anything else rejects the whole plan: no partial acceptance, no repair.
 */
object MilestonePlanParser {
    const val SCHEMA_VERSION = 1
    const val MAX_RAW_CHARS = 4_096
    const val MAX_MILESTONES = 8
    const val MAX_QUERY_CHARS = 64

    private const val KEY_VERSION = "version"
    private const val KEY_MILESTONES = "milestones"
    private const val KEY_INTENT = "intent"
    private const val END_OF_INPUT = '\u0000'
    private val PLAN_KEYS = setOf(KEY_VERSION, KEY_MILESTONES)

    fun parse(
        raw: String,
        userRequest: String,
        vocabulary: MilestoneVocabulary = MilestoneVocabulary.DEFAULT,
    ): MilestoneParseResult = try {
        MilestoneParseResult.Accepted(readPlan(raw, userRequest, vocabulary))
    } catch (rejection: PlanRejection) {
        MilestoneParseResult.Rejected(rejection.reason, rejection.index)
    }

    private fun readPlan(raw: String, userRequest: String, vocabulary: MilestoneVocabulary): MilestonePlan {
        if (raw.length > MAX_RAW_CHARS) reject(MilestoneRejection.TooLarge)
        val tokener = JSONTokener(raw)
        val root = runCatching { tokener.nextValue() }.getOrNull() as? JSONObject
            ?: reject(MilestoneRejection.NotJsonObject)
        if (runCatching { tokener.nextClean() }.getOrNull() != END_OF_INPUT) {
            reject(MilestoneRejection.TrailingContent)
        }
        root.requireExactKeys(PLAN_KEYS, index = null)
        val version = root.get(KEY_VERSION)
        if (version !is Int || version != SCHEMA_VERSION) reject(MilestoneRejection.UnsupportedVersion)
        val items = root.get(KEY_MILESTONES) as? JSONArray ?: reject(MilestoneRejection.WrongType)
        if (items.length() == 0) reject(MilestoneRejection.EmptyPlan)
        if (items.length() > MAX_MILESTONES) reject(MilestoneRejection.TooManyMilestones)
        return MilestonePlan(
            (0 until items.length()).map { index ->
                val item = items.get(index) as? JSONObject ?: reject(MilestoneRejection.WrongType, index)
                item.readMilestone(index, userRequest, vocabulary)
            },
        )
    }

    private fun JSONObject.readMilestone(
        index: Int,
        userRequest: String,
        vocabulary: MilestoneVocabulary,
    ): Milestone {
        val intentName = opt(KEY_INTENT) ?: reject(MilestoneRejection.MissingKey, index)
        val intent = MilestoneIntent.entries.firstOrNull { intent -> intent.wireName == intentName }
            ?: reject(
                if (intentName is String) MilestoneRejection.UnknownIntent else MilestoneRejection.WrongType,
                index,
            )
        requireExactKeys(intent.slots.mapTo(mutableSetOf(KEY_INTENT)) { slot -> slot.key }, index)

        val app = stringSlot(intent, MilestoneSlot.App, index)
        if (app != null && app !in vocabulary.apps) reject(MilestoneRejection.AppNotAllowed, index)
        val tool = stringSlot(intent, MilestoneSlot.Tool, index)
        if (tool != null && tool !in vocabulary.tools) reject(MilestoneRejection.ToolNotAllowed, index)
        val query = stringSlot(intent, MilestoneSlot.Query, index)
        if (query != null) {
            if (!query.isWellFormedQuery()) reject(MilestoneRejection.InvalidQuery, index)
            if (query !in userRequest) reject(MilestoneRejection.QueryNotInRequest, index)
        }
        return Milestone(intent = intent, app = app, query = query, tool = tool)
    }

    private fun JSONObject.stringSlot(intent: MilestoneIntent, slot: MilestoneSlot, index: Int): String? {
        if (slot !in intent.slots) return null
        return get(slot.key) as? String ?: reject(MilestoneRejection.WrongType, index)
    }

    private fun JSONObject.requireExactKeys(expected: Set<String>, index: Int?) {
        val actual = keys().asSequence().toSet()
        if (actual.any { key -> key !in expected }) reject(MilestoneRejection.UnexpectedKey, index)
        if (expected.any { key -> key !in actual }) reject(MilestoneRejection.MissingKey, index)
    }

    private fun String.isWellFormedQuery(): Boolean =
        isNotBlank() &&
            length <= MAX_QUERY_CHARS &&
            this == trim() &&
            none { char -> char.isISOControl() || char.category == CharCategory.FORMAT }

    private fun reject(reason: MilestoneRejection, index: Int? = null): Nothing =
        throw PlanRejection(reason, index)

    private class PlanRejection(val reason: MilestoneRejection, val index: Int?) :
        RuntimeException(null, null, false, false)
}

/**
 * Experimental switch for the derived-progress relaxation (spec.md § Derived progress egress).
 * [Off] in every build: nothing derived from the device reaches the cloud planner.
 */
enum class DerivedProgressEgress {
    Off,

    /** Only the enumerated [RunProgress] fields may reach a planner the run already placed Remote. */
    ClosedVocabulary,
    ;

    companion object {
        val DEFAULT: DerivedProgressEgress = Off
    }
}

/** How far one milestone got. Set by deterministic device verification, never by model free text. */
enum class MilestoneStatus(val wireName: String) {
    Pending("pending"),
    Active("active"),
    Reached("reached"),
    Failed("failed"),
}

/** The foreground app relative to the plan. The package name itself never leaves. */
enum class ForegroundRelation(val wireName: String) {
    PlannedApp("planned_app"),
    OtherApp("other_app"),
    Unknown("unknown"),
}

/** What the device knows after a step. Every field is LocalOnly. */
data class DeviceStepRecord(
    /** One status per plan milestone, in plan order. */
    val statuses: List<MilestoneStatus>,
    val lastResult: ToolResult?,
    val observation: ScreenObservation?,
    val stepsUsed: Int,
    /** Progress reports already released to the cloud planner in this run. */
    val replansSoFar: Int,
)

/** The only device-derived data that may leave. Enums and bounded counts: no field can hold text. */
data class RunProgress(
    val statuses: List<MilestoneStatus>,
    val lastStatus: ToolStatus?,
    val lastFailure: UiActionFailureKind?,
    val foreground: ForegroundRelation,
    val stepsUsed: Int,
)

enum class ProgressWithheldReason {
    FlagOff,
    PlannerNotRemote,
    StatusCountMismatch,
    StepsOutOfRange,
    ReplanBudgetExhausted,
}

sealed interface ProgressExport {
    data class Withheld(val reason: ProgressWithheldReason) : ProgressExport

    data class Released(val progress: RunProgress) : ProgressExport
}

/**
 * Derives [RunProgress] from a LocalOnly [DeviceStepRecord]. It reads only the tool status, the
 * `failureKind` code (mapped through [UiActionFailureKind]) and the observation's package name
 * (compared with the plan); summaries, data values, errors and screen elements are never read.
 */
object RunProgressGate {
    const val MAX_STEPS = 32

    /** Bounds how many progress reports, and so how many derived bits, one run can send. */
    const val MAX_REPLANS = 3

    fun export(
        flag: DerivedProgressEgress,
        planner: RolePlacementDecision,
        plan: MilestonePlan,
        record: DeviceStepRecord,
    ): ProgressExport {
        if (flag != DerivedProgressEgress.ClosedVocabulary) return withheld(ProgressWithheldReason.FlagOff)
        if (
            planner.role != CollaborationRole.Planner ||
            planner.constraint != RoleConstraint.None ||
            planner.placement != RunPlacement.Remote
        ) {
            return withheld(ProgressWithheldReason.PlannerNotRemote)
        }
        if (record.statuses.size != plan.milestones.size) {
            return withheld(ProgressWithheldReason.StatusCountMismatch)
        }
        if (record.stepsUsed !in 0..MAX_STEPS) return withheld(ProgressWithheldReason.StepsOutOfRange)
        if (record.replansSoFar !in 0 until MAX_REPLANS) {
            return withheld(ProgressWithheldReason.ReplanBudgetExhausted)
        }
        return ProgressExport.Released(
            RunProgress(
                statuses = record.statuses.toList(),
                lastStatus = record.lastResult?.status,
                lastFailure = record.lastResult?.failureKind(),
                foreground = record.observation.foregroundRelation(plan),
                stepsUsed = record.stepsUsed,
            ),
        )
    }

    private fun ToolResult.failureKind(): UiActionFailureKind? {
        if (status == ToolStatus.Succeeded) return null
        val code = data["failureKind"] ?: return null
        return UiActionFailureKind.entries.firstOrNull { kind -> kind.schemaValue == code }
            ?: UiActionFailureKind.Unknown
    }

    private fun ScreenObservation?.foregroundRelation(plan: MilestonePlan): ForegroundRelation {
        val packageName = this?.packageName?.takeIf { name -> name.isNotBlank() } ?: return ForegroundRelation.Unknown
        val plannedApps = plan.milestones.mapNotNullTo(mutableSetOf()) { milestone -> milestone.app }
        return if (packageName in plannedApps) ForegroundRelation.PlannedApp else ForegroundRelation.OtherApp
    }

    private fun withheld(reason: ProgressWithheldReason): ProgressExport = ProgressExport.Withheld(reason)
}

/**
 * The exact text sent to a remote planner. [userRequest] must be the RemoteEligible request the
 * Planner role was placed on; the rest is public vocabulary plus, only when released, [RunProgress].
 * Without a [ProgressExport.Released] the output does not depend on the device at all.
 */
object CloudPlannerRequest {
    fun render(
        userRequest: String,
        vocabulary: MilestoneVocabulary = MilestoneVocabulary.DEFAULT,
        progress: ProgressExport? = null,
    ): String = buildString {
        append("{\"version\":").append(MilestonePlanParser.SCHEMA_VERSION)
        append(",\"request\":").append(JSONObject.quote(userRequest))
        append(",\"intents\":").appendStrings(MilestoneIntent.entries.map { intent -> intent.wireName })
        append(",\"apps\":").appendStrings(vocabulary.apps.sorted())
        append(",\"tools\":").appendStrings(vocabulary.tools.sorted())
        (progress as? ProgressExport.Released)?.let { released ->
            append(",\"progress\":").appendProgress(released.progress)
        }
        append('}')
    }

    private fun StringBuilder.appendProgress(progress: RunProgress) {
        append("{\"milestones\":").appendStrings(progress.statuses.map { status -> status.wireName })
        append(",\"lastStatus\":").appendNullable(progress.lastStatus?.wireName())
        append(",\"lastFailure\":").appendNullable(progress.lastFailure?.schemaValue)
        append(",\"foreground\":").append(JSONObject.quote(progress.foreground.wireName))
        append(",\"stepsUsed\":").append(progress.stepsUsed)
        append('}')
    }

    private fun StringBuilder.appendStrings(values: List<String>): StringBuilder =
        append(values.joinToString(separator = ",", prefix = "[", postfix = "]") { value -> JSONObject.quote(value) })

    private fun StringBuilder.appendNullable(value: String?): StringBuilder =
        append(value?.let(JSONObject::quote) ?: "null")

    private fun ToolStatus.wireName(): String = when (this) {
        ToolStatus.Succeeded -> "succeeded"
        ToolStatus.Failed -> "failed"
        ToolStatus.Rejected -> "rejected"
        ToolStatus.Cancelled -> "cancelled"
    }
}
