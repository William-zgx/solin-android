plugins {
    alias(libs.plugins.android.dynamic.feature)
}

extra["modelPackNamespace"] = "com.bytedance.zgx.solin.modelpack.e2b"
extra["modelPackLayout"] = "split-modelpack-e2b-chunk-v1"
extra["modelPackDescription"] = "Prepare the first E2B model chunk for the bundledModels variant."
extra["modelPackBaseSourceKey"] = "e2b"
extra["modelPackChunks"] = listOf(
    mapOf(
        "assetFileName" to "chunks/gemma-4-E2B-it.litertlm.part000",
        "offset" to 0L,
        "byteSize" to 1_400_000_000L,
    ),
)

apply(from = rootProject.file("gradle/modelpack-assets.gradle"))
