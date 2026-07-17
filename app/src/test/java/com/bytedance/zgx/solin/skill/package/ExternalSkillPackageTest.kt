package com.bytedance.zgx.solin.skill.`package`

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.skill.SkillManifest
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolSpec
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSkillPackageTest {
    @Test
    fun `unknown privacy parses as LocalOnly`() {
        val parsed = ExternalSkillPackageParser.parseManifest(manifestJson(privacy = "UnknownPrivacy"))

        assertEquals(MessagePrivacy.LocalOnly, parsed.privacy)
    }

    @Test
    fun `trusted signed package validates into a skill source`() {
        val directory = Files.createTempDirectory("skill-package")
        val instructions = directory.resolve(INSTRUCTIONS_FILE)
        Files.write(instructions, "Summarize local content without remote upload.".toByteArray())
        val manifestBytes = manifestJson(resourceHash = sha256(Files.readAllBytes(instructions))).toByteArray()
        val manifest = ExternalSkillPackageParser.parseManifest(manifestBytes.toString(Charsets.UTF_8))
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(manifestBytes)
        val signature = ExternalSkillPackageSignature(
            publisher = "trusted.publisher",
            keyId = "primary",
            signatureBase64 = Base64.getEncoder().encodeToString(signer.sign()),
        )
        val validator = ExternalSkillPackageValidator(
            frozenToolSpecs = listOf(toolSpec("local.read")),
            currentAppVersion = "1.2.0",
            trustedPublishers = mapOf("trusted.publisher" to mapOf("primary" to keyPair.public)),
        )

        val source = validator.validate(manifest, signature, manifestBytes, directory)

        assertEquals("example.summary", source.manifests().single().id)
    }

    @Test
    fun `loader validates a complete package and retains verified staging`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val zip = signedPackageZip(keyPair)
        val staging = Files.createTempDirectory("skill-package-loader").resolve("verified")
        val loader = packageLoader(keyPair)

        val source = loader.load(zip, staging)

        assertEquals("example.summary", source.manifests().single().id)
        assertTrue(Files.isRegularFile(staging.resolve(INSTRUCTIONS_FILE)))
    }

    @Test
    fun `loader cleans staging when package validation fails`() {
        val trustedKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val untrustedKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val zip = signedPackageZip(untrustedKeyPair)
        val staging = Files.createTempDirectory("skill-package-loader-invalid").resolve("rejected")
        val loader = packageLoader(trustedKeyPair)

        assertThrows(IllegalArgumentException::class.java) {
            loader.load(zip, staging)
        }

        assertTrue(Files.notExists(staging))
    }

    @Test
    fun `invalid signature and unknown tool fail closed`() {
        val directory = Files.createTempDirectory("skill-package-invalid")
        val instructions = directory.resolve(INSTRUCTIONS_FILE)
        Files.write(instructions, "local instructions".toByteArray())
        val manifestBytes = manifestJson(
            requiredTool = "unknown.tool",
            resourceHash = sha256(Files.readAllBytes(instructions)),
        ).toByteArray()
        val manifest = ExternalSkillPackageParser.parseManifest(manifestBytes.toString(Charsets.UTF_8))
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val validator = ExternalSkillPackageValidator(
            frozenToolSpecs = listOf(toolSpec("local.read")),
            currentAppVersion = "1.2.0",
            trustedPublishers = mapOf("trusted.publisher" to mapOf("primary" to keyPair.public)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            validator.validate(
                manifest,
                ExternalSkillPackageSignature("trusted.publisher", "primary", Base64.getEncoder().encodeToString(ByteArray(64))),
                manifestBytes,
                directory,
            )
        }
    }

    @Test
    fun `extractor rejects zip slip and scripts`() {
        val zip = Files.createTempFile("skill-package", ".zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { output ->
            output.putNextEntry(ZipEntry("../escape.txt"))
            output.write("escape".toByteArray())
            output.closeEntry()
        }

        assertThrows(IllegalArgumentException::class.java) {
            SafeExternalSkillPackageExtractor().extract(zip, Files.createTempDirectory("skill-stage"))
        }

        val scriptZip = Files.createTempFile("skill-package-script", ".zip")
        ZipOutputStream(Files.newOutputStream(scriptZip)).use { output ->
            listOf(
                MANIFEST_FILE to "{}",
                SIGNATURE_FILE to "{}",
                INSTRUCTIONS_FILE to "#!/bin/sh",
            ).forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafeExternalSkillPackageExtractor().extract(scriptZip, Files.createTempDirectory("skill-stage-script"))
        }
    }

    @Test
    fun `extractor rejects disguised executable resources`() {
        val cases = mapOf(
            "resources/payload.apk" to "plain text".toByteArray(),
            "resources/payload.txt" to byteArrayOf(0x7f.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()),
            "resources/payload.md" to byteArrayOf(0x00.toByte(), 'a'.code.toByte(), 's'.code.toByte(), 'm'.code.toByte()),
            "resources/payload.yaml" to byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03.toByte(), 0x04.toByte()),
        )

        cases.forEach { (resourceName, resourceBytes) ->
            val zip = Files.createTempFile("skill-package-executable", ".zip")
            ZipOutputStream(Files.newOutputStream(zip)).use { output ->
                mapOf(
                    MANIFEST_FILE to "{}".toByteArray(),
                    SIGNATURE_FILE to "{}".toByteArray(),
                    INSTRUCTIONS_FILE to "instructions".toByteArray(),
                    resourceName to resourceBytes,
                ).forEach { (name, content) ->
                    output.putNextEntry(ZipEntry(name))
                    output.write(content)
                    output.closeEntry()
                }
            }

            assertThrows(IllegalArgumentException::class.java) {
                SafeExternalSkillPackageExtractor().extract(zip, Files.createTempDirectory("skill-stage-executable"))
            }
        }
    }

    private fun packageLoader(keyPair: KeyPair) = ExternalSkillPackageLoader(
        extractor = SafeExternalSkillPackageExtractor(),
        validator = ExternalSkillPackageValidator(
            frozenToolSpecs = listOf(toolSpec("local.read")),
            currentAppVersion = "1.2.0",
            trustedPublishers = mapOf("trusted.publisher" to mapOf("primary" to keyPair.public)),
        ),
    )

    private fun signedPackageZip(keyPair: KeyPair): Path {
        val instructions = "Summarize local content without remote upload.".toByteArray()
        val manifestBytes = manifestJson(resourceHash = sha256(instructions)).toByteArray()
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(manifestBytes)
        val signatureJson = """
            {
              "publisher":"trusted.publisher",
              "keyId":"primary",
              "signatureBase64":"${Base64.getEncoder().encodeToString(signer.sign())}"
            }
        """.trimIndent().toByteArray()
        val zip = Files.createTempFile("skill-package-loader", ".zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { output ->
            mapOf(
                MANIFEST_FILE to manifestBytes,
                SIGNATURE_FILE to signatureJson,
                INSTRUCTIONS_FILE to instructions,
            ).forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content)
                output.closeEntry()
            }
        }
        return zip
    }

    private fun toolSpec(name: String) = ToolSpec(
        name = name,
        title = name,
        description = name,
        inputSchemaJson = """{"type":"object","additionalProperties":false}""",
        capability = ToolCapability.Extension,
        riskLevel = RiskLevel.LowReadOnly,
        confirmationPolicy = ConfirmationPolicy.NotRequired,
    )

    private fun manifestJson(
        privacy: String = "LocalOnly",
        requiredTool: String = "local.read",
        resourceHash: String = "0".repeat(64),
    ): String = """
        {
          "packageId":"example.package",
          "skillId":"example.summary",
          "version":1,
          "title":"Summary",
          "description":"Summarize local content",
          "triggerExamples":["summarize"],
          "requiredTools":["$requiredTool"],
          "inputSchema":{"type":"object","additionalProperties":false},
          "riskLevel":"LowReadOnly",
          "privacy":"$privacy",
          "minAppVersion":"1.0.0",
          "resources":{"instructions.md":"$resourceHash"}
        }
    """.trimIndent()

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
