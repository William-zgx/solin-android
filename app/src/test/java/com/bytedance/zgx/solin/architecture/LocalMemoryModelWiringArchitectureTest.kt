package com.bytedance.zgx.solin.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring gate for the local-model memory tasks (fact suggestions and compaction summaries).
 *
 * Threats: (1) a [com.bytedance.zgx.solin.memory.LocalMemoryModel] that is really an HTTP client, which
 * would send chat and memory text off the device; (2) a second, unreviewed caller that turns a
 * default-off flag on; (3) an allowlisted adapter that reaches a remote runtime.
 *
 * Scope: every non-test source set (`app/src/<set>/java|kotlin`, excluding `test*` and `androidTest*`).
 * Comments and string or character literals are blanked before matching, so a mention in a comment or a
 * string is not a use. The scanner is a line-oriented gate, not a parser: it does not follow template
 * expressions that contain quotes, so a rule it cannot see must be covered by review.
 */
class LocalMemoryModelWiringArchitectureTest {
    @Test
    fun localMemoryModelIsImplementedOnlyByAllowlistedAdapters() {
        val sources = productionSources()
        assertTrue(
            "the on-device adapter is present and scanned",
            sources.map { it.first }.containsAll(LOCAL_MODEL_ADAPTER_ALLOWLIST),
        )
        val violations = sources.flatMap { (path, text) ->
            wiringViolations(path, text, LOCAL_MODEL_ADAPTER_ALLOWLIST)
        }
        assertEquals("LocalMemoryModel implementations outside the allowlist", emptyList<String>(), violations)
    }

    @Test
    fun guardedConstructorsAreOnlyCalledFromTheContainer() {
        val violations = productionSources().flatMap { (path, text) ->
            wiringViolations(path, text, LOCAL_MODEL_ADAPTER_ALLOWLIST)
        }.filter { "constructed" in it }
        assertEquals("guarded constructions outside SolinAppContainer.kt", emptyList<String>(), violations)
    }

    @Test
    fun scannerIsNotVacuous() {
        val paths = productionSources().map { it.first }
        assertTrue("main sources are scanned", paths.any { it.endsWith("/memory/MemoryFactSuggestions.kt") })
        assertTrue("the container is scanned", paths.any { it.endsWith("/SolinAppContainer.kt") })
        assertTrue("the debug source set is scanned", paths.any { it.startsWith("debug/") })
        assertTrue("test source sets are not scanned", paths.none { it.startsWith("test/") || it.startsWith("androidTest/") })
    }

    @Test
    fun scannerFlagsPlantedViolationsAndIgnoresCommentsAndStrings() {
        val planted = mapOf(
            "class Fake : LocalMemoryModel { override suspend fun generate(s: String, p: String): String? = null }" to "implemented",
            "val m = LocalMemoryModel { _, _ -> \"x\" }" to "implemented",
            "val o = object : LocalMemoryModel { override suspend fun generate(s: String, p: String): String? = null }" to "implemented",
            "object Http : Base(), LocalMemoryModel by delegate" to "implemented",
            "class Http(private val c: Int) : Base(), LocalMemoryModel by delegate" to "implemented",
            "import com.bytedance.zgx.solin.memory.LocalMemoryModel as Local\nclass Http : Local { }" to "implemented",
            "typealias Local = com.bytedance.zgx.solin.memory.LocalMemoryModel\nclass Http : Local { }" to "implemented",
            "val c = DefaultContextCompactor(CompactionConfig(), model)" to "constructed",
            "val c = DefaultContextCompactor(config = cfg, summaryModel = m)" to "constructed",
            "val e = LocalModelMemoryFactExtractor(model, config)" to "constructed",
        )
        planted.forEach { (text, marker) ->
            val found = wiringViolations("main/java/com/bytedance/zgx/solin/planted/Planted.kt", text, emptySet())
            assertTrue("planted '$text' must be flagged", found.isNotEmpty())
            assertTrue("planted '$text' must be a $marker violation: $found", found.all { marker in it })
        }

        val allowlisted = "main/java/com/bytedance/zgx/solin/runtime/Adapter.kt"
        listOf(
            "import okhttp3.OkHttpClient\nclass A : LocalMemoryModel { }",
            "import com.bytedance.zgx.solin.runtime.OkHttpRemoteChatRuntime\nval x = 1",
            "import com.bytedance.zgx.solin.runtime.RemoteChatRuntime\nval x = 1",
            "val client = okhttp3.OkHttpClient()",
            "val runtime = OkHttpRemoteChatRuntime()",
            "val runtime = com.bytedance.zgx.solin.runtime.OkHttpRemoteChatRuntime()",
        ).forEach { text ->
            val found = wiringViolations(allowlisted, text, setOf(allowlisted))
            assertTrue("allowlisted remote import must be flagged: $text", found.any { "remote runtime" in it })
        }

        val benign = listOf(
            "// LocalMemoryModel { _, _ -> null }\n/* DefaultContextCompactor(a, b) */\nval s = \"LocalModelMemoryFactExtractor(x)\"",
            "fun interface LocalMemoryModel {\n    suspend fun generate(systemInstruction: String, prompt: String): String?\n}",
            "class Consumer(private val model: LocalMemoryModel?) { val c = DefaultContextCompactor(CompactionConfig()) }",
            "class DefaultContextCompactor(private val config: CompactionConfig = CompactionConfig(), private val summaryModel: LocalMemoryModel? = null)",
            "val doc = \"\"\"LocalMemoryModel { x }\"\"\"",
        )
        benign.forEach { text ->
            assertEquals("benign text must not be flagged: $text", emptyList<String>(), wiringViolations("main/java/com/bytedance/zgx/solin/Benign.kt", text, emptySet()))
        }
    }

    private fun productionSources(): List<Pair<String, String>> {
        // mainJavaRoot() is <app>/src/main/java, so two levels up is <app>/src, the parent of every source set.
        val srcRoot = ProductionSourceScanner.mainJavaRoot().absoluteFile.parentFile.parentFile
        val sets = srcRoot.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
        return sets.flatMap { set ->
            listOf("java", "kotlin").map { File(set, it) }.filter { it.isDirectory }.flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                    .map { file -> file.absoluteFile.relativeTo(srcRoot).invariantSeparatorsPath to file.readText(Charsets.UTF_8) }
                    .toList()
            }
        }.sortedBy { it.first }
    }

    private companion object {
        /** The only production on-device adapter; relative to `app/src`. */
        val LOCAL_MODEL_ADAPTER_ALLOWLIST: Set<String> = setOf(
            "main/java/com/bytedance/zgx/solin/runtime/LocalEngine.kt",
        )
    }
}

/** One rule hit as `path:line rule`; a construction rule contains the word "constructed". */
internal fun wiringViolations(path: String, source: String, allowlist: Set<String>): List<String> {
    val fileName = path.substringAfterLast('/')
    val stripped = stripCommentsAndLiterals(source)
    val found = mutableListOf<String>()
    fun report(index: Int, rule: String) {
        found += "$path:${lineOf(stripped, index)} $rule"
    }

    LOCAL_MEMORY_MODEL_ALIAS.findAll(stripped).forEach {
        report(it.range.first, "LocalMemoryModel implemented through an import alias or typealias")
    }

    if (path !in allowlist) {
        IMPLEMENTATION_PATTERNS.forEach { pattern ->
            pattern.findAll(stripped).forEach { report(it.range.first, "LocalMemoryModel implemented outside the allowlist") }
        }
    } else {
        IMPORT_LINE.findAll(source).forEach { match ->
            val name = match.groupValues[1]
            if (name.startsWith("okhttp3.") || name == "okhttp3" || REMOTE_RUNTIME_IMPORT.matches(name)) {
                report(match.range.first, "allowlisted adapter imports a remote runtime: $name")
            }
        }
        REMOTE_RUNTIME_REFERENCE.findAll(stripped).forEach { match ->
            report(match.range.first, "allowlisted adapter references a remote runtime: ${match.value}")
        }
    }

    if (fileName != CONTAINER_FILE_NAME) {
        DEFAULT_CONTEXT_COMPACTOR_CALL.findAll(stripped).forEach { match ->
            val arguments = argumentsAfter(stripped, match.range.last)
            if (hasTopLevelComma(arguments) || SUMMARY_MODEL_ARGUMENT.containsMatchIn(arguments)) {
                report(match.range.first, "DefaultContextCompactor with a summary model constructed outside SolinAppContainer.kt")
            }
        }
        LOCAL_MODEL_EXTRACTOR_CALL.findAll(stripped).forEach { match ->
            report(match.range.first, "LocalModelMemoryFactExtractor constructed outside SolinAppContainer.kt")
        }
    }
    return found
}

private const val CONTAINER_FILE_NAME = "SolinAppContainer.kt"

private val IMPORT_LINE = Regex("""^\s*import\s+([\w.]+)\s*$""", RegexOption.MULTILINE)
private val REMOTE_RUNTIME_IMPORT = Regex("""com\.bytedance\.zgx\.solin\.runtime\.(?:\*|\w*Remote\w*)""")

private val IMPLEMENTATION_PATTERNS = listOf(
    Regex("""\bobject\s*:\s*LocalMemoryModel\b"""),
    // The `fun interface` declaration is not an implementation; a SAM lambda or `LocalMemoryModel {` is.
    Regex("""(?<!interface )\bLocalMemoryModel\s*\{"""),
    Regex("""\b(?:class|object)\s+\w+(?:<[^>{]*>)?\s*(?:\([^{)]*\))?\s*:\s*(?:[\w.<>]+(?:\([^)]*\))?\s*,\s*)*LocalMemoryModel\b"""),
    Regex("""\bnew\s+LocalMemoryModel\s*\("""),
)

private val DEFAULT_CONTEXT_COMPACTOR_CALL = Regex("""(?<!class )\bDefaultContextCompactor\s*\(""")
private val LOCAL_MEMORY_MODEL_ALIAS = Regex(
    """(?m)^\s*(?:import\s+[\w.]*LocalMemoryModel\s+as\b|typealias\s+\w+\s*=\s*[\w.]*LocalMemoryModel\b)""",
)
private val REMOTE_RUNTIME_REFERENCE = Regex(
    """\b(?:okhttp3\s*\.|(?:[\w.]*\.)?(?:OkHttpRemote\w*|RemoteChatRuntime)\b)""",
)

private val LOCAL_MODEL_EXTRACTOR_CALL = Regex("""(?<!class )\bLocalModelMemoryFactExtractor\s*\(""")
private val SUMMARY_MODEL_ARGUMENT = Regex("""\bsummaryModel\b""")

/** Argument text of the call whose `(` is just before [openEnd] (the index after the callee name). */
private fun argumentsAfter(text: String, openEnd: Int): String {
    var depth = 0
    for (index in openEnd until text.length) {
        when (text[index]) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> {
                depth--
                if (depth == 0) return text.substring(openEnd + 1, index)
            }
        }
    }
    return text.substring(openEnd + 1)
}

/** Whether [arguments] has two or more arguments (a trailing comma after one argument does not count). */
private fun hasTopLevelComma(arguments: String): Boolean {
    val trimmed = arguments.trim().removeSuffix(",")
    var depth = 0
    for (char in trimmed) {
        when (char) {
            '(', '[', '{', '<' -> depth++
            ')', ']', '}', '>' -> depth--
            ',' -> if (depth == 0) return true
        }
    }
    return false
}

private fun lineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1

/**
 * [source] with line and block comments, string literals (including raw strings) and character
 * literals replaced by spaces. Newlines are kept, so line numbers still match the original.
 */
internal fun stripCommentsAndLiterals(source: String): String {
    val out = StringBuilder(source.length)
    fun blank(from: Int, to: Int) {
        for (index in from until to) out.append(if (source[index] == '\n') '\n' else ' ')
    }
    var index = 0
    while (index < source.length) {
        when {
            source.startsWith("//", index) -> {
                val end = source.indexOf('\n', index).let { if (it < 0) source.length else it }
                blank(index, end)
                index = end
            }
            source.startsWith("/*", index) -> {
                val end = source.indexOf("*/", index + 2).let { if (it < 0) source.length else it + 2 }
                blank(index, end)
                index = end
            }
            source.startsWith("\"\"\"", index) -> {
                val end = source.indexOf("\"\"\"", index + 3).let { if (it < 0) source.length else it + 3 }
                blank(index, end)
                index = end
            }
            source[index] == '"' -> {
                var end = index + 1
                while (end < source.length && source[end] != '"' && source[end] != '\n') {
                    end += if (source[end] == '\\') 2 else 1
                }
                end = minOf(end + 1, source.length)
                blank(index, end)
                index = end
            }
            source[index] == '\'' -> {
                var end = index + 1
                while (end < source.length && source[end] != '\'' && source[end] != '\n') {
                    end += if (source[end] == '\\') 2 else 1
                }
                end = minOf(end + 1, source.length)
                blank(index, end)
                index = end
            }
            else -> {
                out.append(source[index])
                index++
            }
        }
    }
    return out.toString()
}
