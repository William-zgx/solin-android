plugins {
    alias(libs.plugins.android.dynamic.feature)
}

extra["modelPackNamespace"] = "com.bytedance.zgx.solin.modelpack.e2b.extra"
extra["modelPackLayout"] = "split-modelpack-e2b-extra-v1"
extra["modelPackDescription"] = "Prepare the second E2B chunk plus memory/action assets for the bundledModels variant."
extra["modelPackBaseSourceKey"] = "e2b"
extra["modelPackExtraSourceKeys"] = listOf("embeddingGemma", "embeddingSentencepiece", "mobileActions")
extra["modelPackChunks"] = listOf(
    mapOf(
        "assetFileName" to "chunks/gemma-4-E2B-it.litertlm.part001",
        "offset" to 1_400_000_000L,
        "byteSize" to 1_188_147_712L,
    ),
)

apply(from = rootProject.file("gradle/modelpack-assets.gradle"))
