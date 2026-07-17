package com.bytedance.zgx.solin.skill.`package`

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.skill.SkillManifest
import com.bytedance.zgx.solin.tool.RiskLevel
import org.json.JSONArray
import org.json.JSONObject

data class ExternalSkillPackageManifest(
    val packageId: String,
    val skill: SkillManifest,
    val privacy: MessagePrivacy,
    val minAppVersion: String,
    val resourceSha256: Map<String, String>,
)

data class ExternalSkillPackageSignature(
    val publisher: String,
    val keyId: String,
    val signatureBase64: String,
)

object ExternalSkillPackageParser {
    fun parseManifest(json: String): ExternalSkillPackageManifest {
        val root = JSONObject(json)
        val requiredTools = root.requireArray("requiredTools").strings("requiredTools")
        return ExternalSkillPackageManifest(
            packageId = root.requireString("packageId"),
            skill = SkillManifest(
                id = root.requireString("skillId"),
                version = root.requireInt("version"),
                title = root.requireString("title"),
                description = root.requireString("description"),
                triggerExamples = root.requireArray("triggerExamples").strings("triggerExamples"),
                requiredTools = requiredTools,
                inputSchemaJson = root.requireObject("inputSchema").toString(),
                riskLevel = parseRisk(root.requireString("riskLevel")),
            ),
            privacy = MessagePrivacy.entries.firstOrNull { it.name == root.optString("privacy") }
                ?: MessagePrivacy.LocalOnly,
            minAppVersion = root.requireString("minAppVersion"),
            resourceSha256 = root.requireObject("resources").stringMap(),
        )
    }

    fun parseSignature(json: String): ExternalSkillPackageSignature {
        val root = JSONObject(json)
        return ExternalSkillPackageSignature(
            publisher = root.requireString("publisher"),
            keyId = root.requireString("keyId"),
            signatureBase64 = root.requireString("signatureBase64"),
        )
    }

    private fun parseRisk(value: String): RiskLevel =
        RiskLevel.entries.firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Unknown package risk level: $value")
}

private fun JSONObject.requireString(name: String): String =
    optString(name).takeIf { has(name) && it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing non-blank string: $name")

private fun JSONObject.requireInt(name: String): Int =
    takeIf { has(name) && opt(name) is Number }?.getInt(name)
        ?: throw IllegalArgumentException("Missing integer: $name")

private fun JSONObject.requireArray(name: String): JSONArray =
    optJSONArray(name) ?: throw IllegalArgumentException("Missing array: $name")

private fun JSONObject.requireObject(name: String): JSONObject =
    optJSONObject(name) ?: throw IllegalArgumentException("Missing object: $name")

private fun JSONArray.strings(label: String): List<String> =
    List(length()) { index ->
        optString(index).takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$label[$index] must be a non-blank string")
    }

private fun JSONObject.stringMap(): Map<String, String> =
    keys().asSequence().associateWith { key -> requireString(key) }
