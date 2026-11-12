package com.bytedance.zgx.solin.architecture

import java.io.File

/**
 * Tiny filesystem helper for package-boundary architecture tests.
 * Scans production Kotlin under app/src/main/java without Android dependencies.
 */
internal object ProductionSourceScanner {
    private const val MAIN_JAVA_RELATIVE = "app/src/main/java"
    private const val SOLIN_PACKAGE_PATH = "com/bytedance/zgx/solin"
    private val QUALIFIED_REFERENCE = Regex(
        """\b(?:[A-Za-z_][A-Za-z0-9_]*\.)+[A-Za-z_][A-Za-z0-9_]*"""
    )

    fun mainJavaRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File(MAIN_JAVA_RELATIVE),
            File(repoRoot(), MAIN_JAVA_RELATIVE)
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "Could not locate production main/java root. Tried: " +
                    candidates.joinToString { it.absolutePath }
            )
    }

    fun solinPackageRoot(): File {
        val root = File(mainJavaRoot(), SOLIN_PACKAGE_PATH)
        require(root.isDirectory) { "Missing Solin package root: ${root.absolutePath}" }
        return root
    }

    fun kotlinFilesUnder(relativePackageDir: String): List<File> {
        val dir = File(solinPackageRoot(), relativePackageDir)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.absolutePath }
            .toList()
    }

    fun rootPackageKotlinFiles(): List<File> = solinPackageRoot().listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.name }

    fun kotlinFile(relativePathFromSolinRoot: String): File {
        val file = File(solinPackageRoot(), relativePathFromSolinRoot)
        require(file.isFile) { "Missing production source: $relativePathFromSolinRoot" }
        return file
    }

    fun packageDirExists(relativePackageDir: String): Boolean = File(solinPackageRoot(), relativePackageDir).isDirectory

    /** All Java/Kotlin production source files, with paths relative to app/src, sorted by path. */
    fun productionSourceFiles(): List<Pair<String, File>> {
        val srcRoot = requireNotNull(mainJavaRoot().absoluteFile.parentFile?.parentFile) {
            "mainJavaRoot must have an app/src parent"
        }
        return srcRoot.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
            .flatMap { set ->
                listOf("java", "kotlin").map { File(set, it) }.filter { it.isDirectory }.flatMap { root ->
                    root.walkTopDown()
                        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                        .map { file -> file.absoluteFile.relativeTo(srcRoot).invariantSeparatorsPath to file }
                        .toList()
                }
            }
            .sortedBy { it.first }
    }

    /** Returns import statements without the leading `import `, one per line. */
    fun importStatements(file: File): List<String> = importStatements(codeLines(file))

    private fun importStatements(lines: List<String>): List<String> = lines.mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("import ")) {
            trimmed.removePrefix("import ").trim().removeSuffix(";")
        } else {
            null
        }
    }

    /** Returns imports and fully qualified references, including Android and AndroidX packages. */
    fun sourceReferences(file: File): List<String> {
        val lines = codeLines(file)
        val qualifiedReferences = lines.flatMap { line ->
            QUALIFIED_REFERENCE.findAll(line).map { match -> match.value }.toList()
        }
        return (importStatements(lines) + qualifiedReferences).distinct()
    }

    /**
     * Removes comments and literal string/character content with a small Kotlin-aware lexer while
     * preserving executable `${...}` template expressions. Newlines are preserved for diagnostics,
     * and a space is inserted at removed boundaries to avoid joining tokens.
     */
    private fun codeLines(file: File): List<String> {
        val text = file.readText()
        val output = StringBuilder(text.length)
        var state = ScanState.CODE
        var blockCommentDepth = 0
        val templateFrames = mutableListOf<TemplateFrame>()
        var index = 0

        fun separate() {
            if (output.isNotEmpty() && output.last() != ' ' && output.last() != '\n') {
                output.append(' ')
            }
        }

        while (index < text.length) {
            val char = text[index]
            val next = text.getOrNull(index + 1)
            when (state) {
                ScanState.CODE -> when {
                    char == '{' && templateFrames.isNotEmpty() -> {
                        output.append(char)
                        templateFrames.last().braceDepth++
                        index++
                    }
                    char == '}' && templateFrames.isNotEmpty() -> {
                        output.append(char)
                        val frame = templateFrames.last()
                        frame.braceDepth--
                        index++
                        if (frame.braceDepth == 0) {
                            state = frame.returnState
                            templateFrames.removeLast()
                        }
                    }
                    char == '/' && next == '/' -> {
                        state = ScanState.LINE_COMMENT
                        separate()
                        index += 2
                    }
                    char == '/' && next == '*' -> {
                        state = ScanState.BLOCK_COMMENT
                        blockCommentDepth = 1
                        separate()
                        index += 2
                    }
                    char == '"' && next == '"' && text.getOrNull(index + 2) == '"' -> {
                        state = ScanState.TRIPLE_QUOTE
                        separate()
                        index += 3
                    }
                    char == '"' -> {
                        state = ScanState.DOUBLE_QUOTE
                        separate()
                        index++
                    }
                    char == '\'' -> {
                        state = ScanState.CHAR
                        separate()
                        index++
                    }
                    else -> {
                        output.append(char)
                        index++
                    }
                }
                ScanState.DOUBLE_QUOTE -> when {
                    char == '$' && next == '{' -> {
                        templateFrames += TemplateFrame(returnState = ScanState.DOUBLE_QUOTE, braceDepth = 1)
                        state = ScanState.CODE
                        output.append('$').append('{')
                        index += 2
                    }
                    char == '\\' -> {
                        if (next == '\n') output.append('\n')
                        index += 2
                    }
                    char == '"' -> {
                        state = ScanState.CODE
                        separate()
                        index++
                    }
                    char == '\n' -> {
                        output.append(char)
                        index++
                    }
                    else -> index++
                }
                ScanState.TRIPLE_QUOTE -> {
                    if (char == '$' && next == '{') {
                        templateFrames += TemplateFrame(returnState = ScanState.TRIPLE_QUOTE, braceDepth = 1)
                        state = ScanState.CODE
                        output.append('$').append('{')
                        index += 2
                    } else if (char == '"' && next == '"' && text.getOrNull(index + 2) == '"') {
                        state = ScanState.CODE
                        separate()
                        index += 3
                    } else {
                        if (char == '\n') output.append(char)
                        index++
                    }
                }
                ScanState.CHAR -> when {
                    char == '\\' -> {
                        if (next == '\n') output.append('\n')
                        index += 2
                    }
                    char == '\'' -> {
                        state = ScanState.CODE
                        separate()
                        index++
                    }
                    char == '\n' -> {
                        output.append(char)
                        index++
                    }
                    else -> index++
                }
                ScanState.LINE_COMMENT -> {
                    if (char == '\n') {
                        output.append(char)
                        state = ScanState.CODE
                    }
                    index++
                }
                ScanState.BLOCK_COMMENT -> when {
                    char == '/' && next == '*' -> {
                        blockCommentDepth++
                        index += 2
                    }
                    char == '*' && next == '/' -> {
                        blockCommentDepth--
                        index += 2
                        if (blockCommentDepth == 0) {
                            state = ScanState.CODE
                            separate()
                        }
                    }
                    char == '\n' -> {
                        output.append(char)
                        index++
                    }
                    else -> index++
                }
            }
        }
        return output.toString().lines()
    }

    fun lineCount(file: File): Int = file.useLines { sequence -> sequence.count() }

    private fun repoRoot(): File = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
        .firstOrNull { candidate ->
            File(candidate, "settings.gradle.kts").isFile &&
                File(candidate, MAIN_JAVA_RELATIVE).isDirectory
        }
        ?: File(System.getProperty("user.dir") ?: ".")

    private data class TemplateFrame(
        val returnState: ScanState,
        var braceDepth: Int
    )

    private enum class ScanState {
        CODE,
        DOUBLE_QUOTE,
        TRIPLE_QUOTE,
        CHAR,
        LINE_COMMENT,
        BLOCK_COMMENT
    }
}
