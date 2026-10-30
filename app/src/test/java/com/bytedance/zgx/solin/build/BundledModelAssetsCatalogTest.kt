package com.bytedance.zgx.solin.build

import com.bytedance.zgx.solin.RECOMMENDED_MODELS
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `gradle/bundled-models.json` is the machine-readable source for pinned model assets used by the
 * Gradle bundledModels/model-pack build and `scripts/verify_local.sh`. The app itself reads
 * [RECOMMENDED_MODELS]. This test fails CI when the two drift apart.
 */
class BundledModelAssetsCatalogTest {
    private val repoRoot: File by lazy {
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .first { candidate -> File(candidate, CATALOG_PATH).isFile }
    }

    private val assets: List<JSONObject> by lazy {
        val catalog = JSONObject(File(repoRoot, CATALOG_PATH).readText())
        assertEquals(1, catalog.getInt("schemaVersion"))
        catalog.getJSONArray("assets").objects()
    }

    @Test
    fun everyRecommendedModelAndCompanionFileIsDeclaredExactlyOnce() {
        val declared = assets.map { it.getString("modelId") to it.getString("fileName") }
        assertEquals("duplicate asset entries", declared.toSet().size, declared.size)
        assertEquals(
            "duplicate asset keys",
            assets.size,
            assets.map { it.getString("key") }.toSet().size,
        )

        val expected = RECOMMENDED_MODELS.flatMap { model ->
            listOf(model.id to model.fileName) +
                model.companionFiles.map { companion -> model.id to companion.fileName }
        }
        assertEquals(expected.toSet(), declared.toSet())
    }

    @Test
    fun primaryAssetsMatchRecommendedModels() {
        val primaryAssets = assets.filter { it.getBoolean("primary") }
        assertEquals(RECOMMENDED_MODELS.size, primaryAssets.size)
        primaryAssets.forEach { asset ->
            val modelId = asset.getString("modelId")
            val model = RECOMMENDED_MODELS.firstOrNull { it.id == modelId }
            assertNotNull("unknown modelId $modelId", model)
            model!!
            assertEquals(modelId, model.fileName, asset.getString("fileName"))
            assertEquals(modelId, model.byteSize, asset.getLong("byteSize"))
            assertEquals(modelId, model.sha256Hex, asset.getString("sha256Hex"))
            assertEquals(modelId, model.sourceRevision, asset.getString("sourceRevision"))
            assertEquals(modelId, model.downloadUrl, asset.getString("downloadUrl"))
            assertEquals(
                modelId,
                model.requiresHuggingFaceAuthorization,
                asset.getBoolean("requiresHuggingFaceAuthorization"),
            )
        }
    }

    @Test
    fun companionAssetsMatchRecommendedModelCompanionFiles() {
        assets.filterNot { it.getBoolean("primary") }.forEach { asset ->
            val modelId = asset.getString("modelId")
            val fileName = asset.getString("fileName")
            val model = RECOMMENDED_MODELS.firstOrNull { it.id == modelId }
            assertNotNull("unknown modelId $modelId", model)
            val companion = model!!.companionFiles.firstOrNull { it.fileName == fileName }
            assertNotNull("unknown companion $modelId/$fileName", companion)
            companion!!
            assertEquals(fileName, companion.byteSize, asset.getLong("byteSize"))
            assertEquals(fileName, companion.sha256Hex, asset.getString("sha256Hex"))
            assertEquals(fileName, companion.downloadUrl, asset.getString("downloadUrl"))
            assertEquals(fileName, model.sourceRevision, asset.getString("sourceRevision"))
            assertEquals(
                fileName,
                companion.requiresHuggingFaceAuthorization,
                asset.getBoolean("requiresHuggingFaceAuthorization"),
            )
        }
    }

    @Test
    fun downloadUrlsArePinnedToTheDeclaredRevision() {
        assets.forEach { asset ->
            val url = asset.getString("downloadUrl")
            val revision = asset.getString("sourceRevision")
            assertTrue(url, revision.matches(Regex("[0-9a-f]{40}")))
            assertTrue(url, url.startsWith("https://huggingface.co/"))
            assertTrue(url, url.contains("/resolve/$revision/${asset.getString("fileName")}"))
            assertFalse(url, url.contains("/resolve/main/"))
            assertTrue(url, asset.getString("sha256Hex").matches(Regex("[0-9a-f]{64}")))
            assertTrue(url, asset.getLong("byteSize") > 0L)
        }
    }

    @Test
    fun modelPackChunksAreContiguousAndCoverTheWholeAsset() {
        assets.forEach { asset ->
            val chunks = asset.getJSONArray("chunks").objects()
            if (chunks.isEmpty()) return@forEach
            var expectedOffset = 0L
            chunks.forEach { chunk ->
                val module = chunk.getString("module")
                assertTrue(
                    "missing model pack module $module",
                    File(repoRoot, "$module/build.gradle.kts").isFile,
                )
                assertTrue(
                    chunk.getString("assetFileName"),
                    chunk.getString("assetFileName").startsWith("chunks/${asset.getString("fileName")}.part"),
                )
                assertEquals(chunk.getString("assetFileName"), expectedOffset, chunk.getLong("offset"))
                assertTrue(chunk.getLong("byteSize") > 0L)
                expectedOffset += chunk.getLong("byteSize")
            }
            assertEquals(asset.getString("fileName"), asset.getLong("byteSize"), expectedOffset)
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    private companion object {
        const val CATALOG_PATH = "gradle/bundled-models.json"
    }
}
