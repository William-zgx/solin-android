package com.bytedance.zgx.solin.orchestration.collab

import com.bytedance.zgx.solin.architecture.ProductionSourceScanner
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only the owner file may construct or copy a gated type. The private constructors (and so private
 * `copy`) enforce that at compile time. This scan covers the `internal` factories, which the compiler
 * lets every file in the module call. It reads every non-test source set after blanking comments and
 * string literals, but keeps the code inside `${...}` templates. It cannot see reflection by class-name
 * string; the compiler, not this scan, rejects `copy`.
 */
class GatedTypeSourceScanTest {
    @Test
    fun noProductionSourceOutsideAnOwnerFileNamesAGatedFactory() {
        val files = productionSourceFiles()
        val sets = files.map { (path, _) -> path.substringBefore('/') }.toSet()
        assertTrue("$sets", "main" in sets && "debug" in sets)
        assertTrue("${files.size} files", files.size > 100)
        assertTrue(files.map { (path, _) -> path }.containsAll(listOf(GatedTypeScan.PLAN_OWNER, GatedTypeScan.POLICY_OWNER)))
        val violations = files.flatMap { (path, file) -> GatedTypeScan.violations(path, file.readText()) }
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun everyKnownBypassIsFlaggedAndMentionsInCommentsOrStringsAreNot() {
        val flagged = listOf(
            UI to "val plan = MilestonePlan.fromValidated(milestones)",
            DEBUG to "val plan = MilestonePlan.forTest(milestones)",
            DEBUG to "val vocabulary = MilestoneVocabulary.forTest(installedApps, emptySet())",
            UI to "val decision = RolePlacementDecision.forTest(role, constraint, privacy, chosenRemote)",
            UI to "val progress = RunProgress.fromGate(statuses, null, null, foreground, 0)",
            UI to "val export = ProgressExport.Released.fromGate(progress)",
            COLLAB_OTHER to "val decision = RolePlacementDecision.fromPolicy(role, constraint, privacy, chosen)",
            GatedTypeScan.POLICY_OWNER to "val plan = MilestonePlan.fromValidated(milestones)",
            GatedTypeScan.PLAN_OWNER to "val plan = MilestonePlan.forTest(milestones)",
            UI to "val plan = MilestonePlan.`fromValidated`(milestones)",
            UI to "val make = MilestonePlan.Companion::fromValidated",
            UI to "val plan = milestones.let(::MilestonePlan)",
            UI to "val release = ProgressExport::Released",
            UI to "val plan = MilestonePlan(milestones)",
            UI to "val constructor = RunProgress::class.java.declaredConstructors.single()",
            UI to "import com.bytedance.zgx.solin.orchestration.collab.MilestonePlan as Plan",
            UI to "import com.bytedance.zgx.solin.orchestration.collab.ProgressExport.Released as Ok",
            UI to "typealias Decision = com.bytedance.zgx.solin.orchestration.collab.RolePlacementDecision",
            UI to "val flag = DerivedProgressEgress.ClosedVocabulary",
            DEBUG to "RunProgressGate(DerivedProgressEgress.ClosedVocabulary)",
            UI to "val text = \"plan: \${MilestonePlan.fromValidated(milestones)}\"",
            UI to "val text = \"\"\"plan: \${listOf(1).map { MilestonePlan.fromValidated(it) }}\"\"\"",
            UI to "val quote = \"a\\\"b\"; val plan = MilestonePlan.fromValidated(milestones)",
            UI to "val quote = '\"'; val plan = MilestonePlan.fromValidated(milestones)",
            UI to "/* a /* nested */ comment */ val plan = MilestonePlan.fromValidated(milestones)",
        )
        val clean = listOf(
            UI to "// MilestonePlan.fromValidated(milestones) is for the parser only",
            UI to "/** See [RunProgress.fromGate], ::MilestonePlan and DerivedProgressEgress.ClosedVocabulary. */",
            UI to "/* a /* nested */ MilestonePlan.fromValidated(milestones) */ val x = 1",
            UI to "val message = \"MilestonePlan.fromValidated(x) and RolePlacementDecision.forTest(y)\"",
            UI to "val message = \"\"\"RunProgress.fromGate(x) \"ClosedVocabulary\" \"\"\"",
            UI to "val escaped = \"\\\${MilestonePlan.fromValidated(x)}\"",
            UI to "val plan: MilestonePlan = parsed; val vocabulary = MilestoneVocabulary.DEFAULT",
            UI to "val gate = RunProgressGate(DerivedProgressEgress.Off); val parser = MilestonePlanParser",
            UI to "val released = export as? ProgressExport.Released ?: return",
            GatedTypeScan.PLAN_OWNER to "return MilestonePlan.fromValidated(milestones)",
            GatedTypeScan.PLAN_OWNER to "internal fun forTest(milestones: List<Milestone>) = MilestonePlan(milestones)",
            GatedTypeScan.PLAN_OWNER to "return ProgressExport.Released.fromGate(RunProgress.fromGate(statuses))",
            GatedTypeScan.POLICY_OWNER to "return RolePlacementDecision.fromPolicy(role, constraint, privacy, decision)",
            COLLAB_OTHER to "if (flag != DerivedProgressEgress.ClosedVocabulary) return",
        )
        flagged.forEach { (path, snippet) ->
            assertTrue("not flagged in $path: $snippet", GatedTypeScan.violations(path, snippet).isNotEmpty())
        }
        clean.forEach { (path, snippet) ->
            assertEquals("$path: $snippet", emptyList<String>(), GatedTypeScan.violations(path, snippet))
        }
        assertEquals(
            listOf("$UI:6: factory fromValidated: val plan = MilestonePlan.fromValidated(x)"),
            GatedTypeScan.violations(UI, "/* one\r\n two */\nval s = \"\"\"\nline\n\"\"\"\nval plan = MilestonePlan.fromValidated(x)\n"),
        )
    }

    private fun productionSourceFiles(): List<Pair<String, File>> {
        val appSrc = ProductionSourceScanner.mainJavaRoot().absoluteFile.parentFile.parentFile
        return appSrc.listFiles().orEmpty()
            .filter { set -> set.isDirectory && !set.name.startsWith("test") && !set.name.startsWith("androidTest") }
            .flatMap { set -> listOf(File(set, "java"), File(set, "kotlin")).filter(File::isDirectory) }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in SOURCE_EXTENSIONS }.toList() }
            .map { file -> file.relativeTo(appSrc).invariantSeparatorsPath to file }
            .sortedBy { (path, _) -> path }
    }

    private companion object {
        val SOURCE_EXTENSIONS = setOf("kt", "java")
        const val UI = "main/java/com/bytedance/zgx/solin/ui/Bypass.kt"
        const val DEBUG = "debug/java/com/bytedance/zgx/solin/debug/Bypass.kt"
        const val COLLAB_OTHER = "main/java/com/bytedance/zgx/solin/orchestration/collab/Other.kt"
    }
}

/** The names each owner file keeps to itself. Paths are relative to `app/src`, so a same-named file in another source set is not an owner. */
private object GatedTypeScan {
    private const val COLLAB = "main/java/com/bytedance/zgx/solin/orchestration/collab/"
    const val PLAN_OWNER = COLLAB + "MilestonePlan.kt"
    const val POLICY_OWNER = COLLAB + "CollaborationPolicy.kt"
    private val OWNERS = setOf(PLAN_OWNER, POLICY_OWNER)

    private val TYPE_OWNERS = mapOf(
        "MilestonePlan" to PLAN_OWNER,
        "MilestoneVocabulary" to PLAN_OWNER,
        "RunProgress" to PLAN_OWNER,
        "ProgressExport" to PLAN_OWNER,
        "Released" to PLAN_OWNER,
        "RolePlacementDecision" to POLICY_OWNER,
    )
    private val FACTORY_OWNERS = mapOf(
        "fromValidated" to PLAN_OWNER,
        "fromGate" to PLAN_OWNER,
        "fromPolicy" to POLICY_OWNER,
    )

    fun violations(path: String, source: String): List<String> {
        val rules = rulesFor(path)
        val sourceLines = source.lines()
        return SourceStripper.strip(source).lines().withIndex().flatMap { (index, line) ->
            rules.filter { (_, pattern) -> pattern.containsMatchIn(line) }
                .map { (name, _) -> "$path:${index + 1}: $name: ${sourceLines[index].trim()}" }
        }
    }

    private fun rulesFor(path: String): List<Pair<String, Regex>> = buildList {
        FACTORY_OWNERS.filterValues { owner -> owner != path }.keys.forEach { factory ->
            add("factory $factory" to Regex("""\b$factory\b"""))
        }
        // An owner file may declare a forTest factory; no production code may call one.
        add("test factory forTest" to Regex(if (path in OWNERS) """(?<!\bfun\s)\bforTest\b""" else """\bforTest\b"""))
        TYPE_OWNERS.filterValues { owner -> owner != path }.keys.forEach { type ->
            add("constructor call $type" to Regex("""\b$type\s*\("""))
            add("constructor reference $type" to Regex("""::\s*$type\b"""))
            add("reflection on $type" to Regex("""\b$type\s*::\s*class\b"""))
            add("import alias of $type" to Regex("""^\s*import\s+[\w.`]*\b$type\b[\w.`]*\s+as\b"""))
            add("typealias of $type" to Regex("""\btypealias\b.*=.*\b$type\b"""))
        }
        if (!path.startsWith(COLLAB)) add("ClosedVocabulary outside collab" to Regex("""\bClosedVocabulary\b"""))
    }
}

/**
 * Kotlin or Java source with comments (nested block comments too), string and char literals blanked.
 * Line breaks and the code inside `${...}` string templates are kept, so line numbers still match.
 */
private object SourceStripper {
    fun strip(source: String): String = Lexer(source).run()

    private class Lexer(private val text: String) {
        private val out = StringBuilder(text.length)
        private var index = 0

        fun run(): String {
            code(inTemplate = false)
            return out.toString()
        }

        private fun more(): Boolean = index < text.length

        private fun at(prefix: String): Boolean = text.startsWith(prefix, index)

        private fun keep() {
            out.append(text[index++])
        }

        private fun blank() {
            val char = text[index++]
            out.append(if (char == '\n' || char == '\r') char else ' ')
        }

        private fun code(inTemplate: Boolean) {
            var depth = 0
            while (more()) {
                when {
                    at("//") -> while (more() && text[index] != '\n') blank()
                    at("/*") -> blockComment()
                    at("\"\"\"") -> string(raw = true)
                    at("\"") -> string(raw = false)
                    at("'") -> charLiteral()
                    at("`") -> {
                        keep()
                        while (more() && text[index] != '`' && text[index] != '\n') keep()
                        if (more()) keep()
                    }
                    at("{") -> {
                        depth++
                        keep()
                    }
                    at("}") -> {
                        if (inTemplate && depth == 0) {
                            blank()
                            return
                        }
                        depth--
                        keep()
                    }
                    else -> keep()
                }
            }
        }

        private fun blockComment() {
            var depth = 0
            while (more()) {
                when {
                    at("/*") -> {
                        depth++
                        blank()
                        blank()
                    }
                    at("*/") -> {
                        depth--
                        blank()
                        blank()
                        if (depth == 0) return
                    }
                    else -> blank()
                }
            }
        }

        private fun string(raw: Boolean) {
            repeat(if (raw) 3 else 1) { blank() }
            while (more()) {
                when {
                    !raw && at("\\") -> {
                        blank()
                        if (more()) blank()
                    }
                    at("\${") -> {
                        blank()
                        blank()
                        code(inTemplate = true)
                    }
                    raw && at("\"\"\"") -> {
                        while (at("\"")) blank()
                        return
                    }
                    !raw && at("\"") -> {
                        blank()
                        return
                    }
                    else -> blank()
                }
            }
        }

        private fun charLiteral() {
            blank()
            if (at("\\")) blank()
            if (more()) blank()
            while (more() && text[index] != '\'' && text[index] != '\n') blank()
            if (at("'")) blank()
        }
    }
}
