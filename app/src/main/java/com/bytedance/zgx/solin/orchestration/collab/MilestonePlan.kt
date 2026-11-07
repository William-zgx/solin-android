package com.bytedance.zgx.solin.orchestration.collab

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.common.keysSet
import com.bytedance.zgx.solin.device.AppInteractionProfiles
import com.bytedance.zgx.solin.device.ScreenObservation
import com.bytedance.zgx.solin.device.UiActionFailureKind
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.json.JSONArray
import org.json.JSONObject

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

/**
 * A plan accepted by [MilestonePlanParser], so executors and [RunProgressGate] only see checked
 * plans. The constructor, and so `copy`, is private. The gated-type source scan in the collab tests
 * fails if a production file other than this one names a factory below.
 */
@ConsistentCopyVisibility
data class MilestonePlan private constructor(val milestones: List<Milestone>) {
    companion object {
        /** Only [MilestonePlanParser] may call this, after every check on the plan has passed. */
        internal fun fromValidated(milestones: List<Milestone>): MilestonePlan = MilestonePlan(milestones)

        /** Test fixtures only, including plans the parser would refuse. Banned in production sources. */
        internal fun forTest(milestones: List<Milestone>): MilestonePlan = MilestonePlan(milestones)
    }
}

/**
 * The public words a planner may use. Production uses only [DEFAULT], which is static app data,
 * never read from the phone: the installed-app list and the foreground app are device-derived and
 * stay local. The constructor, and so `copy`, is private, so production code cannot build another
 * vocabulary.
 */
@ConsistentCopyVisibility
data class MilestoneVocabulary private constructor(val apps: Set<String>, val tools: Set<String>) {
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

        /** Test fixtures only, to narrow the vocabulary. Banned in production sources. */
        internal fun forTest(apps: Set<String>, tools: Set<String>): MilestoneVocabulary =
            MilestoneVocabulary(apps, tools)
    }
}

/** Why a plan was refused. Enum only, so a rejection can be audited without the planner's text. */
enum class MilestoneRejection {
    TooLarge,
    NotJsonObject,

    /** Not strict RFC 8259 JSON, or nested deeper than [StrictJsonScan.MAX_DEPTH]. */
    MalformedJson,
    TrailingContent,
    DuplicateKey,
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
 * [StrictJsonScan] first checks the text against strict JSON grammar without org.json, so comments,
 * `=` or `;` separators, unquoted strings, non-decimal numbers, trailing content and duplicate keys
 * are refused whichever org.json the classpath carries. Each milestone must then carry exactly the
 * keys its intent needs. Apps and tools come from the vocabulary and a query is copied literally
 * from the user's request, so no text the planner invented reaches the device. Any violation
 * rejects the whole plan with one enum reason: no partial acceptance, no repair.
 */
object MilestonePlanParser {
    const val SCHEMA_VERSION = 1
    const val MAX_RAW_CHARS = 4_096
    const val MAX_MILESTONES = 8
    const val MAX_QUERY_CHARS = 64

    private const val KEY_VERSION = "version"
    private const val KEY_MILESTONES = "milestones"
    private const val KEY_INTENT = "intent"
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
        StrictJsonScan.firstViolation(raw)?.let { reason -> reject(reason) }
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: reject(MilestoneRejection.MalformedJson)
        root.requireExactKeys(PLAN_KEYS, index = null)
        val version = root.get(KEY_VERSION)
        if (version !is Int || version != SCHEMA_VERSION) reject(MilestoneRejection.UnsupportedVersion)
        val items = root.get(KEY_MILESTONES) as? JSONArray ?: reject(MilestoneRejection.WrongType)
        if (items.length() == 0) reject(MilestoneRejection.EmptyPlan)
        if (items.length() > MAX_MILESTONES) reject(MilestoneRejection.TooManyMilestones)
        return MilestonePlan.fromValidated(
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
        val actual = keysSet()
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
 * Checks that text is exactly one strict RFC 8259 JSON object, without org.json. Android's built-in
 * org.json is lenient: it skips `#`, `//` and block comments, accepts `=`, `=>` and `;` separators,
 * unquoted and single-quoted strings, and hex or octal numbers, and keeps the last value of a
 * duplicate key. The org.json artifact on the JVM test classpath is stricter, so JVM tests alone
 * cannot show what the phone accepts.
 */
internal object StrictJsonScan {
    /** A plan needs 3 levels (plan, milestone list, milestone). */
    const val MAX_DEPTH = 8

    /** Null when [text] is one strict JSON object with unique keys at every depth. */
    fun firstViolation(text: String): MilestoneRejection? {
        val cursor = Cursor(text)
        cursor.skipWhitespace()
        if (cursor.current() != '{') return MilestoneRejection.NotJsonObject
        return try {
            cursor.readValue(depth = 0)
            cursor.skipWhitespace()
            if (cursor.atEnd()) null else MilestoneRejection.TrailingContent
        } catch (violation: Violation) {
            violation.reason
        }
    }

    private class Cursor(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        /** NUL past the end; a NUL inside the text fails wherever it appears, so the two never mix. */
        fun current(): Char = if (atEnd()) '\u0000' else text[index]

        fun skipWhitespace() {
            while (current() == ' ' || current() == '\t' || current() == '\n' || current() == '\r') index++
        }

        fun readValue(depth: Int) {
            when (val char = current()) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> readString()
                't' -> readLiteral("true")
                'f' -> readLiteral("false")
                'n' -> readLiteral("null")
                else -> if (char == '-' || char in '0'..'9') readNumber() else malformed()
            }
        }

        private fun readObject(depth: Int) {
            if (depth > MAX_DEPTH) malformed()
            index++
            skipWhitespace()
            if (current() == '}') {
                index++
                return
            }
            val keys = HashSet<String>()
            while (true) {
                skipWhitespace()
                if (current() != '"') malformed()
                if (!keys.add(readString())) throw Violation(MilestoneRejection.DuplicateKey)
                skipWhitespace()
                expect(':')
                skipWhitespace()
                readValue(depth)
                skipWhitespace()
                if (current() == ',') {
                    index++
                } else {
                    expect('}')
                    return
                }
            }
        }

        private fun readArray(depth: Int) {
            if (depth > MAX_DEPTH) malformed()
            index++
            skipWhitespace()
            if (current() == ']') {
                index++
                return
            }
            while (true) {
                skipWhitespace()
                readValue(depth)
                skipWhitespace()
                if (current() == ',') {
                    index++
                } else {
                    expect(']')
                    return
                }
            }
        }

        /** Returns the decoded text, so `"app"` and `"\u0061pp"` are the same key. */
        private fun readString(): String {
            index++
            val decoded = StringBuilder()
            while (true) {
                if (atEnd()) malformed()
                val char = text[index++]
                when {
                    char == '"' -> return decoded.toString()
                    char == '\\' -> decoded.append(readEscape())
                    char < ' ' -> malformed()
                    else -> decoded.append(char)
                }
            }
        }

        private fun readEscape(): Char {
            if (atEnd()) malformed()
            return when (val char = text[index++]) {
                '"', '\\', '/' -> char
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readHexChar()
                else -> malformed()
            }
        }

        private fun readHexChar(): Char {
            val digits = text.substring(index, minOf(index + 4, text.length))
            val isHex = digits.all { digit -> digit in '0'..'9' || digit in 'a'..'f' || digit in 'A'..'F' }
            if (digits.length != 4 || !isHex) malformed()
            index += 4
            return digits.toInt(16).toChar()
        }

        private fun readLiteral(word: String) {
            if (!text.startsWith(word, index)) malformed()
            index += word.length
        }

        /** `-?(0|[1-9][0-9]*)(.[0-9]+)?([eE][+-]?[0-9]+)?`: no hex, octal, `+1`, `.5`, `1.` or NaN. */
        private fun readNumber() {
            if (current() == '-') index++
            if (current() == '0') index++ else readDigits()
            if (current() == '.') {
                index++
                readDigits()
            }
            if (current() == 'e' || current() == 'E') {
                index++
                if (current() == '+' || current() == '-') index++
                readDigits()
            }
        }

        private fun readDigits() {
            if (current() !in '0'..'9') malformed()
            while (current() in '0'..'9') index++
        }

        private fun expect(char: Char) {
            if (current() != char) malformed()
            index++
        }

        private fun malformed(): Nothing = throw Violation(MilestoneRejection.MalformedJson)
    }

    private class Violation(val reason: MilestoneRejection) : RuntimeException(null, null, false, false)
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
)

/**
 * The only device-derived data that may leave. Enums and bounded counts: no field can hold text.
 * The constructor, and so `copy`, is private; the gated-type source scan in the collab tests fails
 * if a production file other than this one names [fromGate].
 */
@ConsistentCopyVisibility
data class RunProgress private constructor(
    val statuses: List<MilestoneStatus>,
    val lastStatus: ToolStatus?,
    val lastFailure: UiActionFailureKind?,
    val foreground: ForegroundRelation,
    val stepsUsed: Int,
) {
    companion object {
        /** Only [RunProgressGate.export] may call this, after every gate check has passed. */
        internal fun fromGate(
            statuses: List<MilestoneStatus>,
            lastStatus: ToolStatus?,
            lastFailure: UiActionFailureKind?,
            foreground: ForegroundRelation,
            stepsUsed: Int,
        ): RunProgress = RunProgress(statuses, lastStatus, lastFailure, foreground, stepsUsed)
    }
}

enum class ProgressWithheldReason {
    FlagOff,
    PlannerNotRemote,

    /** The decision says Remote Planner, but its prompt was not RemoteEligible without a local-model need. */
    PlannerPrivacyNotRemoteEligible,
    PlanSizeOutOfRange,
    StatusCountMismatch,
    StepsOutOfRange,
    ReplanBudgetExhausted,
}

sealed interface ProgressExport {
    data class Withheld(val reason: ProgressWithheldReason) : ProgressExport

    /** The constructor, and so `copy`, is private; only [RunProgressGate] releases progress. */
    @ConsistentCopyVisibility
    data class Released private constructor(val progress: RunProgress) : ProgressExport {
        companion object {
            /** Only [RunProgressGate.export] may call this, with the [RunProgress] it just derived. */
            internal fun fromGate(progress: RunProgress): Released = Released(progress)
        }
    }
}

/**
 * Derives [RunProgress] from a LocalOnly [DeviceStepRecord]. Create one per agent run: the gate owns
 * the run's report counter, so a caller cannot reset or understate it. Every report released into a
 * [CloudPlannerRequest] counts, whether or not that request is then sent.
 *
 * On the device it reads the tool status, the `failureKind` code and the observation's package name.
 * Only enums leave: the code through the pinned [RELEASABLE_FAILURE_CODES], the package name as a
 * [ForegroundRelation]. Summaries, other data values, errors and screen elements are never read.
 */
class RunProgressGate(private val flag: DerivedProgressEgress = DerivedProgressEgress.DEFAULT) {
    private var released = 0

    /** Progress reports this run has released so far, at most [MAX_REPLANS]. */
    val reportsReleased: Int
        @Synchronized get() = released

    @Synchronized
    internal fun export(planner: RolePlacementDecision, plan: MilestonePlan, record: DeviceStepRecord): ProgressExport {
        if (flag != DerivedProgressEgress.ClosedVocabulary) return withheld(ProgressWithheldReason.FlagOff)
        if (
            planner.role != CollaborationRole.Planner ||
            planner.constraint != RoleConstraint.None ||
            planner.placement != RunPlacement.Remote
        ) {
            return withheld(ProgressWithheldReason.PlannerNotRemote)
        }
        if (planner.privacy.aggregatePrivacy != MessagePrivacy.RemoteEligible || planner.privacy.requiresLocalModel) {
            return withheld(ProgressWithheldReason.PlannerPrivacyNotRemoteEligible)
        }
        if (plan.milestones.size !in 1..MilestonePlanParser.MAX_MILESTONES) {
            return withheld(ProgressWithheldReason.PlanSizeOutOfRange)
        }
        if (record.statuses.size != plan.milestones.size) {
            return withheld(ProgressWithheldReason.StatusCountMismatch)
        }
        if (record.stepsUsed !in 0..MAX_STEPS) return withheld(ProgressWithheldReason.StepsOutOfRange)
        if (released >= MAX_REPLANS) return withheld(ProgressWithheldReason.ReplanBudgetExhausted)
        released += 1
        return ProgressExport.Released.fromGate(
            RunProgress.fromGate(
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
        return RELEASABLE_FAILURE_CODES[code] ?: UiActionFailureKind.Unknown
    }

    private fun ScreenObservation?.foregroundRelation(plan: MilestonePlan): ForegroundRelation {
        val packageName = this?.packageName?.takeIf { name -> name.isNotBlank() } ?: return ForegroundRelation.Unknown
        val plannedApps = plan.milestones.mapNotNullTo(mutableSetOf()) { milestone -> milestone.app }
        return if (packageName in plannedApps) ForegroundRelation.PlannedApp else ForegroundRelation.OtherApp
    }

    private fun withheld(reason: ProgressWithheldReason): ProgressExport = ProgressExport.Withheld(reason)

    companion object {
        const val MAX_STEPS = 32

        /** Bounds how many progress reports, and so how many derived bits, one run can send. */
        const val MAX_REPLANS = 3

        /**
         * The failure kinds a report may carry, keyed by their schema code and listed by hand. A
         * [UiActionFailureKind] entry added later leaves as `unknown` until someone reviews it and
         * lists it here.
         */
        internal val RELEASABLE_FAILURE_CODES: Map<String, UiActionFailureKind> = listOf(
            UiActionFailureKind.NodeNotFound,
            UiActionFailureKind.PageChanged,
            UiActionFailureKind.PermissionMissing,
            UiActionFailureKind.KeyboardObscured,
            UiActionFailureKind.Timeout,
            UiActionFailureKind.AppNotForeground,
            UiActionFailureKind.SearchEntryNotFound,
            UiActionFailureKind.EditableNotFound,
            UiActionFailureKind.SubmitNotFound,
            UiActionFailureKind.ResultNotVerified,
            UiActionFailureKind.DangerousAction,
            UiActionFailureKind.Unknown,
        ).associateBy { kind -> kind.schemaValue }
    }
}

/**
 * The exact text sent to a remote planner. [MilestoneVocabulary.DEFAULT] is always the vocabulary,
 * and device data enters only as the one [RunProgress] a [RunProgressGate] releases, so a caller
 * cannot add anything else.
 */
object CloudPlannerRequest {
    /**
     * [text] is what is sent. [progressReleased] is true only when [text] carries progress a
     * [RunProgressGate] released; it is the receipt's `derivedProgressReleased`.
     */
    data class Rendered(val text: String, val progressReleased: Boolean)

    /**
     * The first request of a run. [userRequest] must be the RemoteEligible request the Planner role
     * was placed on. The output does not depend on the device at all.
     */
    fun render(userRequest: String): Rendered =
        Rendered(compose(userRequest, progress = null), progressReleased = false)

    /**
     * A replan request. [gate] decides whether this step's progress is added; when it withholds,
     * the result equals `render(userRequest)`, text byte for byte.
     */
    fun render(
        userRequest: String,
        gate: RunProgressGate,
        planner: RolePlacementDecision,
        plan: MilestonePlan,
        record: DeviceStepRecord,
    ): Rendered {
        val released = gate.export(planner, plan, record) as? ProgressExport.Released
        return Rendered(compose(userRequest, released?.progress), progressReleased = released != null)
    }

    private fun compose(userRequest: String, progress: RunProgress?): String = buildString {
        val vocabulary = MilestoneVocabulary.DEFAULT
        append("{\"version\":").append(MilestonePlanParser.SCHEMA_VERSION)
        append(",\"request\":").append(JSONObject.quote(userRequest))
        append(",\"intents\":").appendStrings(MilestoneIntent.entries.map { intent -> intent.wireName })
        append(",\"apps\":").appendStrings(vocabulary.apps.sorted())
        append(",\"tools\":").appendStrings(vocabulary.tools.sorted())
        progress?.let { released -> append(",\"progress\":").appendProgress(released) }
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
