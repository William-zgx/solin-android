package com.bytedance.zgx.pocketmind.docs

import com.bytedance.zgx.pocketmind.ModelCatalog
import com.bytedance.zgx.pocketmind.ModelProfile
import com.bytedance.zgx.pocketmind.RemoteModelConfig
import com.bytedance.zgx.pocketmind.modelProfile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilityProfilesDocumentationTest {
    @Test
    fun modelCapabilityProfilesJsonMatchesRecommendedCatalogProfiles() {
        val json = JSONObject(readRepoFile("docs/model_capability_profiles.json"))
        val documented = json.getJSONArray("profiles")
        val profiles = ModelCatalog.recommendedProfiles()

        assertEquals(1, json.getInt("version"))
        assertEquals("ModelCapabilityProfilesDocumentationTest", json.getString("contractTest"))
        assertTrue(json.getString("privacyBoundary").contains("LocalOnly"))
        assertEquals(profiles.map { profile -> profile.id }, documented.ids())

        profiles.forEachIndexed { index, profile ->
            assertProfileMatchesJson(
                profile = profile,
                item = documented.getJSONObject(index),
            )
            assertFalse(profile.remoteEligible)
            assertFalse(profile.requiresRemoteSendConfirmation)
        }
    }

    @Test
    fun remoteOpenAiCompatibleTemplatesStayFailClosedForVisionAndLocalBackends() {
        val documented = JSONObject(readRepoFile("docs/model_capability_profiles.json"))
            .getJSONArray("remoteOpenAiCompatibleTemplates")
        val textTemplate = RemoteModelConfig(
            modelName = "remote-text-template",
            supportsVisionInput = false,
        ).modelProfile()
        val visionTemplate = RemoteModelConfig(
            modelName = "remote-vision-template",
            supportsVisionInput = true,
        ).modelProfile()

        assertEquals(listOf(textTemplate.id, visionTemplate.id), documented.ids())
        assertProfileMatchesJson(textTemplate, documented.getJSONObject(0))
        assertProfileMatchesJson(visionTemplate, documented.getJSONObject(1))
        assertFalse(textTemplate.supportsVisionInput)
        assertTrue(visionTemplate.supportsVisionInput)
        assertTrue(textTemplate.remoteEligible)
        assertTrue(visionTemplate.remoteEligible)
        assertTrue(textTemplate.requiresRemoteSendConfirmation)
        assertTrue(visionTemplate.requiresRemoteSendConfirmation)

        for (index in 0 until documented.length()) {
            val item = documented.getJSONObject(index)
            assertTrue(item.getBoolean("requiresRemoteSendConfirmation"))
            assertTrue(item.getBoolean("remoteEligible"))
            assertTrue(item.getJSONArray("preferredLocalBackends").length() == 0)
        }
    }

    @Test
    fun customLocalTemplateStaysTextOnlyWithoutCatalogAssetEvidence() {
        val documented = JSONObject(readRepoFile("docs/model_capability_profiles.json"))
            .getJSONArray("customLocalTemplates")
        val profile = ModelCatalog.customLocalChatProfile("custom-local-template")

        assertEquals(listOf(profile.id), documented.ids())
        assertProfileMatchesJson(profile, documented.getJSONObject(0))
        assertTrue(profile.supportsChatGeneration)
        assertFalse(profile.supportsVisionInput)
        assertFalse(profile.supportsMemoryEmbedding)
        assertFalse(profile.supportsMobileActionPlanning)
        assertFalse(profile.remoteEligible)
        assertFalse(profile.requiresRemoteSendConfirmation)
        assertTrue(profile.preferredLocalBackends.isEmpty())
    }

    private fun assertProfileMatchesJson(
        profile: ModelProfile,
        item: JSONObject,
    ) {
        assertEquals(profile.id, item.getString("id"))
        assertEquals(profile.displayName, item.getString("displayName"))
        assertEquals(profile.backendKind.name, item.getString("backendKind"))
        assertEquals(profile.capability.name, item.getString("capability"))
        assertEquals(profile.inputModalities.map { it.name }, item.getStringList("inputModalities"))
        assertEquals(profile.features.map { it.name }, item.getStringList("features"))
        assertEquals(profile.contextWindowTokens, item.nullableInt("contextWindowTokens"))
        assertEquals(profile.preferredLocalBackends.map { it.name }, item.getStringList("preferredLocalBackends"))
        assertEquals(profile.experimental, item.optBoolean("experimental", false))
        assertEquals(profile.byteSize, item.nullableLong("byteSize"))
        assertEquals(profile.sha256Hex, item.nullableString("sha256Hex"))
        assertEquals(profile.sourceRevision, item.nullableString("sourceRevision"))
        assertEquals(profile.remoteEligible, item.getBoolean("remoteEligible"))
        assertEquals(profile.requiresRemoteSendConfirmation, item.getBoolean("requiresRemoteSendConfirmation"))

        val capabilities = item.getJSONObject("capabilities")
        assertEquals(profile.supportsChatGeneration, capabilities.getBoolean("chat"))
        assertEquals(profile.supportsVisionInput, capabilities.getBoolean("vision"))
        assertEquals(profile.supportsMemoryEmbedding, capabilities.getBoolean("memoryEmbedding"))
        assertEquals(profile.supportsMobileActionPlanning, capabilities.getBoolean("mobileAction"))
    }

    private fun JSONArray.ids(): List<String> =
        (0 until length()).map { index -> getJSONObject(index).getString("id") }

    private fun JSONObject.getStringList(key: String): List<String> {
        val values = getJSONArray(key)
        return (0 until values.length()).map { index -> values.getString(index) }
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.nullableInt(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    private fun JSONObject.nullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private fun readRepoFile(path: String): String =
        File(repoRoot(), path).also { file ->
            assertTrue("missing ${file.path}", file.isFile)
        }.readText()

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { file -> file.parentFile }
            .first { candidate -> File(candidate, "docs/model_capability_profiles.json").isFile }
            .absoluteFile
}
