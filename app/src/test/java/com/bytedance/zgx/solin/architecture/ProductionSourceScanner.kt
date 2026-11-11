package com.bytedance.zgx.solin.architecture

import java.io.File

/**
 * Tiny filesystem helper for package-boundary architecture tests.
 * Scans production Kotlin under app/src/main/java without Android dependencies.
 */
internal object ProductionSourceScanner {
    private const val MAIN_JAVA_RELATIVE = "app/src/main/java"
    private const val SOLIN_PACKAGE_PATH = "com/bytedance/zgx/solin"
    private val SOLIN_REFERENCE = Regex(
        """com\.bytedance\.zgx\.solin\.[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*"""
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

    /** Returns import statements without the leading `import `, one per line. */
    fun importStatements(file: File): List<String> = codeLinesWithoutComments(file).mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("import ")) {
            trimmed.removePrefix("import ").trim().removeSuffix(";")
        } else {
            null
        }
    }

    /** Returns imports and fully qualified Solin references so FQ names cannot bypass a gate. */
    fun solinReferences(file: File): List<String> {
        val qualifiedReferences = codeLinesWithoutComments(file).flatMap { line ->
            SOLIN_REFERENCE.findAll(line).map { match -> match.value }.toList()
        }
        return (importStatements(file) + qualifiedReferences).distinct()
    }

    /** Ignores block comments and full-line `//` comments at the same simple level as before. */
    private fun codeLinesWithoutComments(file: File): List<String> {
        val codeLines = mutableListOf<String>()
        var inBlockComment = false
        file.useLines { lines ->
            for (raw in lines) {
                var line = raw
                if (inBlockComment) {
                    val end = line.indexOf("*/")
                    if (end < 0) continue
                    line = line.substring(end + 2)
                    inBlockComment = false
                }
                while (true) {
                    val start = line.indexOf("/*")
                    if (start < 0) break
                    val end = line.indexOf("*/", start + 2)
                    if (end < 0) {
                        line = line.substring(0, start)
                        inBlockComment = true
                        break
                    }
                    line = line.removeRange(start, end + 2)
                }
                if (line.trim().startsWith("//")) continue
                codeLines += line
            }
        }
        return codeLines
    }

    fun lineCount(file: File): Int = file.useLines { sequence -> sequence.count() }

    private fun repoRoot(): File = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
        .firstOrNull { candidate ->
            File(candidate, "settings.gradle.kts").isFile &&
                File(candidate, MAIN_JAVA_RELATIVE).isDirectory
        }
        ?: File(System.getProperty("user.dir") ?: ".")
}
