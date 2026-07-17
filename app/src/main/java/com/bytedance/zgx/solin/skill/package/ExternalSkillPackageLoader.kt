package com.bytedance.zgx.solin.skill.`package`

import com.bytedance.zgx.solin.skill.SkillSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class ExternalSkillPackageLoader(
    private val extractor: SafeExternalSkillPackageExtractor,
    private val validator: ExternalSkillPackageValidator,
) {
    fun load(zipFile: Path, stagingDirectory: Path): SkillSource = try {
        extractor.extract(zipFile, stagingDirectory)
        val manifestBytes = Files.readAllBytes(stagingDirectory.resolve(MANIFEST_FILE))
        val signatureBytes = Files.readAllBytes(stagingDirectory.resolve(SIGNATURE_FILE))
        val manifest = ExternalSkillPackageParser.parseManifest(manifestBytes.toString(Charsets.UTF_8))
        val signature = ExternalSkillPackageParser.parseSignature(signatureBytes.toString(Charsets.UTF_8))
        validator.validate(
            manifest = manifest,
            signature = signature,
            manifestBytes = manifestBytes,
            packageDirectory = stagingDirectory,
        )
    } catch (error: Throwable) {
        deleteRecursively(stagingDirectory)
        throw error
    }

    private fun deleteRecursively(directory: Path) {
        if (Files.notExists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
