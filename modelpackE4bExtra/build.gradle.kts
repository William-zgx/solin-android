plugins {
    alias(libs.plugins.android.dynamic.feature)
}

extra["modelPackNamespace"] = "com.bytedance.zgx.solin.modelpack.e4b.extra"
extra["modelPackLayout"] = "split-modelpack-e4b-extra-v1"
extra["modelPackDescription"] = "Prepare the second E4B model chunk for the bundledModels variant."
extra["modelPackBaseSourceKey"] = "e4b"
extra["modelPackChunks"] = listOf(
    mapOf(
        "assetFileName" to "chunks/gemma-4-E4B-it.litertlm.part001",
        "offset" to 1_900_000_000L,
        "byteSize" to 1_759_530_240L,
    ),
)

apply(from = rootProject.file("gradle/modelpack-assets.gradle"))
