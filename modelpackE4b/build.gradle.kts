plugins {
    alias(libs.plugins.android.dynamic.feature)
}

extra["modelPackNamespace"] = "com.bytedance.zgx.solin.modelpack.e4b"
extra["modelPackLayout"] = "split-modelpack-e4b-chunk-v1"
extra["modelPackDescription"] = "Prepare the first E4B model chunk for the bundledModels variant."
extra["modelPackBaseSourceKey"] = "e4b"
extra["modelPackChunks"] = listOf(
    mapOf(
        "assetFileName" to "chunks/gemma-4-E4B-it.litertlm.part000",
        "offset" to 0L,
        "byteSize" to 1_900_000_000L,
    ),
)

apply(from = rootProject.file("gradle/modelpack-assets.gradle"))
