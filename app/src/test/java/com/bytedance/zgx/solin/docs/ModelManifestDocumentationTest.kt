package com.bytedance.zgx.solin.docs

import com.bytedance.zgx.solin.RECOMMENDED_MODELS
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManifestDocumentationTest {
    @Test
    fun modelLicenseReviewCoversEveryRecommendedModel() {
        val json = JSONObject(readRepoFile("docs/model_license_review.json"))
        val models = json.getJSONArray("models")
        val documentedIds = (0 until models.length()).map { index ->
            models.getJSONObject(index).getString("id")
        }

        assertEquals(RECOMMENDED_MODELS.map { model -> model.id }, documentedIds)
    }

    @Test
    fun modelLicenseMetadataCoversEveryRecommendedModelWithoutReplacingManualReview() {
        val json = JSONObject(readRepoFile("docs/model_license_metadata.json"))
        val models = json.getJSONArray("models")
        val documentedIds = (0 until models.length()).map { index ->
            val model = models.getJSONObject(index)
            assertTrue(model.getString("apiUrl").startsWith("https://huggingface.co/api/models/"))
            assertTrue(model.getBoolean("metadataOnly"))
            model.getString("id")
        }

        assertEquals(RECOMMENDED_MODELS.map { model -> model.id }, documentedIds)
    }

    @Test
    fun modelLicenseApprovalGateRequiresCompleteManualReviewWhenEnabled() {
        if (System.getenv("VERIFY_MODEL_LICENSES") != "1") return

        val models = JSONObject(readRepoFile("docs/model_license_review.json")).getJSONArray("models")
        for (index in 0 until models.length()) {
            val model = models.getJSONObject(index)
            assertEquals("approved", model.getString("status"))
            assertEquals("approved", model.getString("redistributionDecision"))
            assertTrue(model.getString("licenseName").isNotBlank())
            assertTrue(model.getString("licenseUrl").startsWith("https://"))
            assertTrue(model.getString("reviewer").isNotBlank())
            assertTrue(model.getString("reviewDate").isNotBlank())
        }
    }

    private fun readRepoFile(path: String): String =
        File(repoRoot(), path).also { file ->
            assertTrue("missing ${file.path}", file.isFile)
        }.readText()

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { file -> file.parentFile }
            .first { candidate -> File(candidate, "docs/model_license_review.json").isFile }
            .absoluteFile
}
