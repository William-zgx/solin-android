package com.bytedance.zgx.solin.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * One execution entry for device input (docs/architecture_depth.md, action gate).
 *
 * The live critical-tap guard is the only thing that decides whether a gesture or IME action runs.
 * This test enforces that structurally, the way an executor would: it scans production Kotlin on disk
 * and fails when input is injected from anywhere else, or from a function inside the accessibility
 * service that never consults the guard.
 *
 * Two rules:
 *
 * 1. **Outside the service and the node-query helpers**, nothing may call `dispatchGesture(`,
 *    `performGlobalAction(`, or `performAction(` with a click / long-click / IME-enter / type action.
 *    Those are the calls that actually move the screen, and a caller outside the service has no live
 *    window to check them against.
 * 2. **Inside `SolinAccessibilityService.kt`**, every function that dispatches a gesture or performs
 *    such an action must call one of the guard functions (`refuseCriticalTapAt`,
 *    `refuseCriticalContainerClick`, `refuseCriticalImeAction`, `refuseSwipeThatClicks`) or carry an
 *    explicit `// action-gate: exempt (<reason>)` comment on its own or the previous line. The
 *    comment is a claim that this input cannot activate a control; it must name why.
 *
 * Rule 2 also covers the service's own pass-through dispatchers (`dispatchSwipeGesture`,
 * `dispatchLongPressGesture`, `dispatchGestureOutcome`), so a new gesture path cannot reach
 * `dispatchGesture` without a guard between it and the screen. `dispatchTapGesture` is not listed
 * because it always calls `refuseCriticalTapAt` itself.
 *
 * Typing (`ACTION_SET_TEXT`, `ACTION_PASTE`) is checked too and is expected to carry an exempt
 * comment: typing into an already-chosen editable never activates a control. Focus, selection and
 * scroll actions are excluded — they move a caret or content, never press a control.
 *
 * Limits, stated plainly:
 * - Per-function text matching, not a real call graph. A guard call anywhere in the function body
 *   counts even if one branch does not reach it, and a helper that dispatches from a different
 *   function is only caught when that helper is itself listed and guarded or exempt.
 * - Functions are delimited by indentation and by an unfinished parameter list / continuation line,
 *   so a nested callback counts as its own function. Comments and string literals are not stripped:
 *   a mention in a KDoc can satisfy the match. The scan errs toward false negatives rather than
 *   breaking on formatting.
 * - It sees the checked-in `app/src/main` tree only; reflection or code in another source set is
 *   invisible.
 */
class ActionEntryArchitectureTest {

    @Test
    fun onlyTheAccessibilityServiceInjectsInput() {
        val allowedRelativePaths = setOf(
            SERVICE_RELATIVE_PATH,
            "device/AccessibilityNodeQueries.kt",
        )
        val violations = mutableListOf<String>()
        for (file in productionFiles()) {
            val relative = file.relativeTo(ProductionSourceScanner.solinPackageRoot()).path
            if (relative in allowedRelativePaths) continue
            file.useLines { lines ->
                lines.forEachIndexed { index, rawLine ->
                    val line = rawLine.trim()
                    if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed
                    val violation = rawInjectionViolation(line)
                    if (violation != null) {
                        violations += "$relative:${index + 1} $violation"
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                "Device input must be injected only by $SERVICE_RELATIVE_PATH " +
                    "(the one entry that runs the live critical-tap guard):\n" +
                    violations.joinToString(separator = "\n") { " - $it" },
            )
        }
    }

    @Test
    fun everyInputFunctionInTheServiceIsGuardedOrExplicitlyExempt() {
        val file = ProductionSourceScanner.kotlinFile(SERVICE_RELATIVE_PATH)
        val functions = kotlinFunctions(file)
        val inputFunctions = functions.filter { it.performsInput() }

        // Guard against a scanner that silently matches nothing: these are the functions the gate
        // map (docs/architecture_depth.md) relies on. If one is renamed, update the map and this list.
        val missingKnownFunctions = KNOWN_INPUT_FUNCTIONS - inputFunctions.map { it.name }.toSet()
        if (missingKnownFunctions.isNotEmpty()) {
            fail(
                "The scanner did not recognize known input functions in $SERVICE_RELATIVE_PATH: " +
                    missingKnownFunctions.joinToString() +
                    ". The scan is stale; fix it rather than trusting a green run.",
            )
        }

        // A function is covered when it calls a guard directly, carries an explicit exemption, or
        // calls another *guarded* input function. The closure is what lets a guarded entry
        // (dispatchSwipeGesture) satisfy the rule for its callers. An exempt function covers only
        // itself: its exemption says "this particular input cannot activate a control", which says
        // nothing about a caller that reaches it, so it deliberately does not propagate.
        val guarded = mutableSetOf<String>()
        var changed = true
        while (changed) {
            changed = false
            inputFunctions
                .filterNot { it.name in guarded }
                .filter { function ->
                    function.callsGuard() ||
                        function.calledInputFunctions(inputFunctions).any { callee -> callee in guarded }
                }
                .forEach { function ->
                    guarded += function.name
                    changed = true
                }
        }

        val violations = inputFunctions
            .filterNot { it.isExempt() || it.name in guarded }
            .map { function ->
                "$SERVICE_RELATIVE_PATH:${function.startLine} `${function.name}` performs device input " +
                    "without reaching a guard call or an `$EXEMPT_MARKER (<reason>)` comment"
            }
        if (violations.isNotEmpty()) {
            fail(
                "Every function that injects input must reach the live critical-tap guard " +
                    "(docs/architecture_depth.md):\n" +
                    violations.joinToString(separator = "\n") { " - $it" },
            )
        }
    }

    private fun productionFiles(): List<File> =
        ProductionSourceScanner.solinPackageRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.absolutePath }
            .toList()

    private fun rawInjectionViolation(line: String): String? {
        RAW_INJECTION_CALLS.firstOrNull { token -> line.contains(token) }?.let { token ->
            return "calls `$token` outside the accessibility service"
        }
        if (line.contains("performAction(")) {
            ACTIVATION_ACTION_IDS.firstOrNull { id -> line.contains(id) }?.let { id ->
                return "performs `performAction($id)` outside the accessibility service"
            }
        }
        return null
    }

    /**
     * Splits a Kotlin file into functions.
     *
     * A declaration owns every following line until the first non-blank line at the same or lower
     * indentation — unless the parameter list is still open, or the previous line obviously
     * continues (`=`, `(`, `,`, `->`, `&&`, `||`), or the line starts with a closing parenthesis.
     * That keeps a multi-line signature and an expression-bodied function in one piece, while a
     * nested callback counts as its own function.
     */
    private fun kotlinFunctions(file: File): List<KotlinFunction> {
        val lines = file.readLines()
        return lines.mapIndexedNotNull { index, line ->
            val match = FUN_DECLARATION.find(line) ?: return@mapIndexedNotNull null
            Declaration(
                index = index,
                indent = line.indentWidth(),
                name = match.groupValues[1],
            )
        }.map { declaration ->
            KotlinFunction(
                name = declaration.name,
                startLine = declaration.index + 1,
                body = lines.subList(
                    declaration.index,
                    bodyEndExclusive(lines, declaration),
                ),
                previousLine = lines.getOrNull(declaration.index - 1).orEmpty(),
            )
        }
    }

    private fun bodyEndExclusive(lines: List<String>, declaration: Declaration): Int {
        var parenDepth = 0
        var index = declaration.index
        while (index < lines.size) {
            val line = lines[index]
            if (index > declaration.index &&
                parenDepth == 0 &&
                line.isNotBlank() &&
                line.indentWidth() <= declaration.indent &&
                !line.trimStart().startsWith(")") &&
                !lines[index - 1].trimEnd().endsWithContinuation()
            ) {
                return index
            }
            parenDepth += line.count { it == '(' } - line.count { it == ')' }
            index += 1
        }
        return lines.size
    }

    private fun String.indentWidth(): Int = takeWhile { it == ' ' || it == '\t' }.length

    private fun String.endsWithContinuation(): Boolean =
        CONTINUATION_SUFFIXES.any { suffix -> endsWith(suffix) }

    private data class Declaration(val index: Int, val indent: Int, val name: String)

    private data class KotlinFunction(
        val name: String,
        val startLine: Int,
        val body: List<String>,
        val previousLine: String,
    ) {
        /** Lines that inject input or fire an activation action. */
        fun performsInput(): Boolean = body.any { line ->
            val trimmed = line.trim()
            !trimmed.startsWith("//") &&
                !trimmed.startsWith("*") &&
                SERVICE_INPUT_TOKENS.any { token -> trimmed.contains(token) }
        }

        /** Whether this body calls the live critical-tap guard itself. */
        fun callsGuard(): Boolean =
            body.any { line -> GUARD_CALLS.any { guard -> line.contains(guard) } }

        /** Input functions this body calls, resolved by name against the file's declarations. */
        fun calledInputFunctions(functions: List<KotlinFunction>): List<String> =
            functions.filter { callee ->
                callee.name != name &&
                    body.any { line -> line.contains("${callee.name}(") }
            }.map { callee -> callee.name }

        /** The comment may sit on the declaration line itself or the line directly above it. */
        fun isExempt(): Boolean =
            body.firstOrNull()?.contains(EXEMPT_MARKER) == true ||
                previousLine.contains(EXEMPT_MARKER)
    }

    private companion object {
        const val SERVICE_RELATIVE_PATH = "device/SolinAccessibilityService.kt"
        const val EXEMPT_MARKER = "// action-gate: exempt"

        /**
         * Functions the gate map says perform input. Kept as a self-check so a renamed or split
         * function cannot make the scan pass vacuously.
         */
        val KNOWN_INPUT_FUNCTIONS = setOf(
            "activateCandidate",
            "dispatchGestureOutcome",
            "dispatchLongPressGesture",
            "dispatchSwipeGesture",
            "dispatchTapGesture",
            "dismissTransientSearchOverlay",
            "globalActionPrimitive",
            "imeEnterPrimitive",
            "longPressByNormalizedCoords",
            "pasteTextIntoEditable",
            "pressBack",
            "scrollBySwipeFallback",
            "setTextDirectly",
            "submitSearch",
            "swipeByNormalizedCoords",
        )

        /** Raw APIs that may only be called from the accessibility service. */
        val RAW_INJECTION_CALLS = listOf(
            "dispatchGesture(",
            "performGlobalAction(",
        )

        /** Node actions that activate or type, as written at the call site. */
        val ACTIVATION_ACTION_IDS = listOf(
            "ACTION_CLICK",
            "ACTION_LONG_CLICK",
            "ACTION_IME_ENTER",
            "ACTION_SET_TEXT",
            "ACTION_PASTE",
        )

        /**
         * Everything the service itself must gate: the raw APIs, its own stroke dispatchers, IME
         * submit, and the activating node actions.
         */
        val SERVICE_INPUT_TOKENS = listOf(
            "dispatchGesture(",
            "dispatchGestureOutcome(",
            "dispatchLongPressGesture(",
            "dispatchSwipeGesture(",
            "performGlobalAction(",
            "performImeSearchAction(",
            "ACTION_CLICK",
            "ACTION_LONG_CLICK",
            "ACTION_IME_ENTER",
            "ACTION_SET_TEXT",
            "ACTION_PASTE",
        )

        /** Entry points that mean "this function consulted the live critical-tap rule". */
        val GUARD_CALLS = listOf(
            "refuseCriticalTapAt(",
            "refuseCriticalContainerClick(",
            "refuseCriticalImeAction(",
            "refuseSwipeThatClicks(",
        )

        val CONTINUATION_SUFFIXES = listOf("=", "(", ",", "->", "&&", "||")

        val FUN_DECLARATION = Regex(
            """^\s*(?:private|internal|protected|public|override|suspend)?\s*""" +
                """(?:inline\s+)?fun\s+(?:<[^>]+>\s*)?([A-Za-z_][A-Za-z0-9_]*)""",
        )
    }
}
