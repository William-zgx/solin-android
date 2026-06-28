package com.bytedance.zgx.solin.data

import android.content.Context
import com.bytedance.zgx.solin.BuildConfig
import com.bytedance.zgx.solin.DEFAULT_CHAT_MODEL_ID
import com.bytedance.zgx.solin.ModelCatalog
import java.io.File
import java.io.IOException
import org.json.JSONObject

data class BundledModelInstallResult(
    val available: Boolean,
    val installedModelCount: Int = 0,
    val copiedFileCount: Int = 0,
    val failedModelIds: List<String> = emptyList(),
) {
    val changed: Boolean
        get() = copiedFileCount > 0 || installedModelCount > 0

    val hasFailures: Boolean
        get() = failedModelIds.isNotEmpty()
}

interface BundledModelInstaller {
    val isEnabled: Boolean
    fun install(): BundledModelInstallResult
}

object NoOpBundledModelInstaller : BundledModelInstaller {
    override val isEnabled: Boolean = false
    override fun install(): BundledModelInstallResult =
        BundledModelInstallResult(available = false)
}

class AssetBundledModelInstaller(
    context: Context,
    private val modelRepository: ModelRepositoryFacade,
    private val enabled: Boolean = BuildConfig.BUNDLED_MODELS_ENABLED,
) : BundledModelInstaller {
    private val appContext = context.applicationContext
    override val isEnabled: Boolean
        get() = enabled

    override fun install(): BundledModelInstallResult {
        if (!enabled) return BundledModelInstallResult(available = false)
        val entries = readManifest() ?: return BundledModelInstallResult(available = false)
        val byModelId = entries.groupBy { it.modelId }
        val previousActiveModelId = modelRepository.currentState().activeInstalledModelId
        var copiedFiles = 0
        var installedModels = 0
        val failedModelIds = mutableListOf<String>()

        byModelId.keys.forEach { modelId ->
            val model = ModelCatalog.recommendedModelOrNull(modelId) ?: return@forEach
            val modelEntries = byModelId.getValue(modelId)
            val primaryEntry = modelEntries.firstOrNull { it.primary && it.fileName == model.fileName }
                ?: return@forEach
            if (hasExistingVerifiedModel(model.id)) {
                return@forEach
            }
            val primaryTarget = modelRepository.downloadedModelFile(model.fileName)
            if (primaryTarget == null) {
                failedModelIds += model.id
                return@forEach
            }

            runCatching {
                copiedFiles += copyAssetIfNeeded(
                    assetFileName = primaryEntry.fileName,
                    chunkFileNames = primaryEntry.chunkFileNames,
                    target = primaryTarget,
                    expectedBytes = model.byteSize,
                    expectedSha256 = model.sha256Hex,
                )
                model.companionFiles.forEach { companion ->
                    val companionEntry = modelEntries.firstOrNull { !it.primary && it.fileName == companion.fileName }
                        ?: error("Missing bundled companion asset: ${companion.fileName}")
                    copiedFiles += copyAssetIfNeeded(
                        assetFileName = companionEntry.fileName,
                        chunkFileNames = companionEntry.chunkFileNames,
                        target = ModelCatalog.recommendedModelCompanionFile(primaryTarget, companion),
                        expectedBytes = companion.byteSize,
                        expectedSha256 = companion.sha256Hex,
                    )
                }
                check(ModelCatalog.hasCompleteRecommendedBundle(primaryTarget, model)) {
                    "Bundled model failed verification after copy: ${model.id}"
                }
                modelRepository.registerInstalledModel(
                    path = primaryTarget.absolutePath,
                    displayName = model.shortName,
                    recommendedModelId = model.id,
                    verifiedSha256 = model.sha256Hex,
                    verificationStatus = ModelVerificationStatus.VerifiedRecommended,
                )
                installedModels += 1
            }.onFailure {
                failedModelIds += model.id
            }
        }

        if (previousActiveModelId != null) {
            runCatching { modelRepository.selectInstalledModel(previousActiveModelId) }
        } else {
            runCatching { modelRepository.selectRecommendedModel(DEFAULT_CHAT_MODEL_ID) }
        }

        return BundledModelInstallResult(
            available = true,
            installedModelCount = installedModels,
            copiedFileCount = copiedFiles,
            failedModelIds = failedModelIds.distinct(),
        )
    }

    private fun hasExistingVerifiedModel(modelId: String): Boolean {
        val model = ModelCatalog.recommendedModelOrNull(modelId) ?: return false
        return modelRepository.currentState().installedModels.any { installed ->
            installed.recommendedModelId == modelId &&
                installed.isUsable &&
                ModelCatalog.hasCompleteRecommendedBundle(File(installed.path), model)
        }
    }

    private fun copyAssetIfNeeded(
        assetFileName: String,
        chunkFileNames: List<String>,
        target: File,
        expectedBytes: Long,
        expectedSha256: String,
    ): Int {
        if (target.isFile &&
            target.length() == expectedBytes &&
            ModelCatalog.matchesExpectedSha256(target, expectedSha256)
        ) {
            return 0
        }
        val parent = target.parentFile ?: error("Bundled model target has no parent: ${target.absolutePath}")
        if (!parent.exists() && !parent.mkdirs()) {
            error("Unable to create bundled model directory: ${parent.absolutePath}")
        }
        val temp = File(parent, "${target.name}.bundled.tmp")
        temp.delete()
        temp.outputStream().use { output ->
            val assetPaths = chunkFileNames.ifEmpty { listOf(assetFileName) }
            assetPaths.forEach { assetPath ->
                appContext.assets.open("$ASSET_ROOT/$assetPath").use { input ->
                    input.copyTo(output)
                }
            }
        }
        check(temp.length() == expectedBytes) {
            "Unexpected bundled model size for $assetFileName: ${temp.length()} (expected $expectedBytes)"
        }
        check(ModelCatalog.matchesExpectedSha256(temp, expectedSha256)) {
            "Bundled model SHA-256 mismatch: $assetFileName"
        }
        if (target.exists() && !target.delete()) {
            temp.delete()
            error("Unable to replace bundled model target: ${target.absolutePath}")
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            error("Unable to publish bundled model target: ${target.absolutePath}")
        }
        return 1
    }

    private fun readManifest(): List<BundledModelAssetEntry>? =
        try {
            appContext.assets.open("$ASSET_ROOT/manifest.json").use { input ->
                val json = JSONObject(input.bufferedReader().readText())
                val models = json.getJSONArray("models")
                List(models.length()) { index ->
                    val item = models.getJSONObject(index)
                    BundledModelAssetEntry(
                        modelId = item.getString("modelId"),
                        fileName = item.getString("fileName"),
                        primary = item.optBoolean("primary", true),
                        chunkFileNames = item.optJSONArray("chunks")?.let { chunks ->
                            List(chunks.length()) { chunkIndex -> chunks.getString(chunkIndex) }
                        }.orEmpty(),
                    )
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private data class BundledModelAssetEntry(
        val modelId: String,
        val fileName: String,
        val primary: Boolean,
        val chunkFileNames: List<String>,
    )

    private companion object {
        const val ASSET_ROOT = "solin-bundled-models"
    }
}
