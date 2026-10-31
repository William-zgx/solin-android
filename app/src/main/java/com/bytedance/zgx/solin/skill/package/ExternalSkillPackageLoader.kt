package com.bytedance.zgx.solin.skill.`package`

import com.bytedance.zgx.solin.skill.SkillSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class ExternalSkillPackageLoader(
    private val extractor: SafeExternalSkillPackageExtractor,
    private val validator: ExternalSkillPackageValidator,
) {
    fun load(zipFile: Path, stagingDirectory: Path): SkillSource {
        // The extractor requires an absent or empty staging directory. Check that HERE, before the
        // try, so a pre-existing NON-EMPTY directory is rejected without the catch block deleting
        // content this loader never created.
        val stagingWasPresent = Files.exists(stagingDirectory)
        if (stagingWasPresent) {
            require(Files.list(stagingDirectory).use { !it.findAny().isPresent }) {
                "Skill package staging directory must be empty: $stagingDirectory"
            }
        }
        try {
            extractor.extract(zipFile, stagingDirectory)
            val manifestBytes = Files.readAllBytes(stagingDirectory.resolve(MANIFEST_FILE))
            val signatureBytes = Files.readAllBytes(stagingDirectory.resolve(SIGNATURE_FILE))
            val manifest = ExternalSkillPackageParser.parseManifest(manifestBytes.toString(Charsets.UTF_8))
            val signature = ExternalSkillPackageParser.parseSignature(signatureBytes.toString(Charsets.UTF_8))
            return validator.validate(
                manifest = manifest,
                signature = signature,
                manifestBytes = manifestBytes,
                packageDirectory = stagingDirectory,
            )
        } catch (error: Throwable) {
            // Clean up only what this call created. If the directory already existed (empty, since
            // the extractor demands that), removing the extracted files is still ours to do; if the
            // directory did not exist, delete it entirely. A directory we did not create is left
            // alone.
            if (!stagingWasPresent) {
                deleteRecursively(stagingDirectory)
            } else {
                deleteExtractedFiles(stagingDirectory)
            }
            throw error
        }
    }

    /** Removes the extracted package files from a staging directory the caller already owned. */
    private fun deleteExtractedFiles(directory: Path) {
        if (Files.notExists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder())
                .filter { path -> path != directory }
                .forEach(Files::deleteIfExists)
        }
    }

    private fun deleteRecursively(directory: Path) {
        if (Files.notExists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
