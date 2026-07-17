package com.bytedance.zgx.solin.skill.`package`

import com.bytedance.zgx.solin.skill.SkillSource
import com.bytedance.zgx.solin.tool.ToolSpec
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

class ExternalSkillPackageValidator(
    frozenToolSpecs: Collection<ToolSpec>,
    private val currentAppVersion: String,
    private val trustedPublishers: Map<String, Map<String, PublicKey>>,
) {
    private val toolSpecsByName = frozenToolSpecs.associateBy { it.name }

    fun validate(
        manifest: ExternalSkillPackageManifest,
        signature: ExternalSkillPackageSignature,
        manifestBytes: ByteArray,
        packageDirectory: Path,
    ): SkillSource {
        require(ID_REGEX.matches(manifest.packageId)) { "Invalid packageId: ${manifest.packageId}" }
        require(ID_REGEX.matches(manifest.skill.id)) { "Invalid skillId: ${manifest.skill.id}" }
        require(version(currentAppVersion) >= version(manifest.minAppVersion)) {
            "Package requires app version ${manifest.minAppVersion}"
        }
        require(manifest.skill.requiredTools.isNotEmpty()) { "External skill must declare required tools" }
        require(manifest.skill.requiredTools.distinct().size == manifest.skill.requiredTools.size) {
            "External skill required tools must be unique"
        }
        val requiredSpecs = manifest.skill.requiredTools.map { toolName ->
            toolSpecsByName[toolName] ?: throw IllegalArgumentException("Unknown frozen tool: $toolName")
        }
        val highestRisk = requiredSpecs.maxBy { it.riskLevel.ordinal }.riskLevel
        require(manifest.skill.riskLevel.ordinal >= highestRisk.ordinal) {
            "External skill risk is lower than its required tool risk"
        }
        validateResources(manifest, packageDirectory)
        verifySignature(signature, manifestBytes)
        return SkillSource { listOf(manifest.skill) }
    }

    private fun verifySignature(signature: ExternalSkillPackageSignature, manifestBytes: ByteArray) {
        val key = trustedPublishers[signature.publisher]?.get(signature.keyId)
            ?: throw IllegalArgumentException("Untrusted skill package publisher or key")
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(key)
        verifier.update(manifestBytes)
        val decoded = runCatching { Base64.getDecoder().decode(signature.signatureBase64) }
            .getOrElse { throw IllegalArgumentException("Invalid skill package signature encoding") }
        require(verifier.verify(decoded)) { "Invalid skill package signature" }
    }

    private fun validateResources(manifest: ExternalSkillPackageManifest, packageDirectory: Path) {
        val root = packageDirectory.toRealPath()
        val actual = linkedSetOf<String>()
        Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile)
                .map(root::relativize)
                .map { it.toString().replace(java.io.File.separatorChar, '/') }
                .filter { it != MANIFEST_FILE && it != SIGNATURE_FILE }
                .forEach(actual::add)
        }
        require(actual == manifest.resourceSha256.keys) { "Resource hash manifest does not match payload" }
        manifest.resourceSha256.forEach { (relativePath, expectedHash) ->
            require(SHA256_REGEX.matches(expectedHash)) { "Invalid SHA-256 for $relativePath" }
            val file = root.resolve(relativePath).normalize()
            require(file.startsWith(root) && Files.isRegularFile(file)) { "Invalid package resource path" }
            val actualHash = MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file))
                .joinToString("") { byte -> "%02x".format(byte) }
            require(actualHash.equals(expectedHash, ignoreCase = true)) { "SHA-256 mismatch for $relativePath" }
        }
    }

    private fun version(value: String): List<Int> {
        require(VERSION_REGEX.matches(value)) { "Invalid app version: $value" }
        return value.substringBefore('-').split('.').map(String::toInt).let { it + List(4 - it.size) { 0 } }
    }

    private operator fun List<Int>.compareTo(other: List<Int>): Int {
        indices.forEach { index ->
            val comparison = get(index).compareTo(other[index])
            if (comparison != 0) return comparison
        }
        return 0
    }

    private companion object {
        val ID_REGEX = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+")
        val VERSION_REGEX = Regex("(?:0|[1-9]\\d*)(?:\\.(?:0|[1-9]\\d*)){0,3}(?:-[0-9A-Za-z.-]+)?")
        val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")
    }
}

internal const val MANIFEST_FILE = "manifest.json"
internal const val SIGNATURE_FILE = "signature.json"
internal const val INSTRUCTIONS_FILE = "instructions.md"
