package com.bytedance.zgx.solin.skill.`package`

import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipInputStream

data class ExternalSkillPackageLimits(
    val maxTotalBytes: Long = 8L * 1024L * 1024L,
    val maxSingleFileBytes: Long = 2L * 1024L * 1024L,
    val maxFileCount: Int = 128,
)

class SafeExternalSkillPackageExtractor(
    private val limits: ExternalSkillPackageLimits = ExternalSkillPackageLimits(),
) {
    fun extract(zipFile: Path, stagingDirectory: Path): Set<String> {
        require(Files.notExists(stagingDirectory) || Files.list(stagingDirectory).use { !it.findAny().isPresent }) {
            "Skill package staging directory must be empty"
        }
        Files.createDirectories(stagingDirectory)
        val root = stagingDirectory.toAbsolutePath().normalize()
        val extracted = linkedSetOf<String>()
        var totalBytes = 0L
        Files.newInputStream(zipFile).use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = validatePath(entry.name, entry.isDirectory)
                    require(extracted.add(name)) { "Duplicate ZIP entry: $name" }
                    require(extracted.size <= limits.maxFileCount) { "ZIP exceeds file count limit" }
                    val destination = root.resolve(name).normalize()
                    require(destination.startsWith(root)) { "ZIP entry escapes staging directory" }
                    Files.createDirectories(destination.parent)
                    Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var fileBytes = 0L
                        val prefix = ArrayList<Byte>(EXECUTABLE_PREFIX_BYTES)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            if (prefix.size < EXECUTABLE_PREFIX_BYTES) {
                                buffer.take(minOf(count, EXECUTABLE_PREFIX_BYTES - prefix.size)).forEach(prefix::add)
                            }
                            fileBytes += count
                            totalBytes += count
                            require(fileBytes <= limits.maxSingleFileBytes) { "ZIP entry exceeds size limit" }
                            require(totalBytes <= limits.maxTotalBytes) { "ZIP exceeds total size limit" }
                            output.write(buffer, 0, count)
                        }
                        require(!hasExecutablePrefix(prefix.toByteArray())) { "Executable package content is forbidden" }
                    }
                }
            }
        }
        require(setOf(MANIFEST_FILE, SIGNATURE_FILE, INSTRUCTIONS_FILE).all(extracted::contains)) {
            "Skill package is missing required files"
        }
        return extracted
    }

    private fun validatePath(name: String, directory: Boolean): String {
        require(!directory && name.isNotBlank() && !name.endsWith('/')) { "Directory ZIP entries are forbidden" }
        require(!name.startsWith('/') && !WINDOWS_ABSOLUTE.matches(name) && '\\' !in name) {
            "Absolute or backslash ZIP paths are forbidden"
        }
        require(name.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Unsafe ZIP path" }
        require(
            name == MANIFEST_FILE ||
                name == SIGNATURE_FILE ||
                name == INSTRUCTIONS_FILE ||
                name.startsWith("schemas/") ||
                name.startsWith("resources/"),
        ) { "ZIP entry is outside the package allowlist" }
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in ALLOWED_EXTENSIONS) { "Package resource type is forbidden" }
        return name
    }

    private fun hasExecutablePrefix(prefix: ByteArray): Boolean =
        EXECUTABLE_MAGIC_PREFIXES.any { magic ->
            prefix.size >= magic.size && magic.indices.all { index -> prefix[index] == magic[index] }
        }

    private companion object {
        const val EXECUTABLE_PREFIX_BYTES = 8
        val WINDOWS_ABSOLUTE = Regex("[A-Za-z]:/.*")
        val ALLOWED_EXTENSIONS = setOf("json", "md", "txt", "yaml", "yml")
        val EXECUTABLE_MAGIC_PREFIXES = listOf(
            byteArrayOf('#'.code.toByte(), '!'.code.toByte()),
            byteArrayOf(0x7f.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()),
            byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte()),
            byteArrayOf(0x00.toByte(), 'a'.code.toByte(), 's'.code.toByte(), 'm'.code.toByte()),
            byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03.toByte(), 0x04.toByte()),
        )
    }
}
