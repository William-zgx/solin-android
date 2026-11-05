plugins {
    alias(libs.plugins.android.dynamic.feature)
}

extra["modelPackNamespace"] = "com.bytedance.zgx.solin.modelpack.e2b.extra"
extra["modelPackLayout"] = "split-modelpack-e2b-extra-v1"
extra["modelPackDescription"] = "Prepare the second E2B chunk plus memory/action assets for the bundledModels variant."
extra["modelPackBaseSourceKey"] = "e2b"
extra["modelPackExtraSourceKeys"] = listOf("embeddingGemma", "embeddingSentencepiece", "mobileActions")
// This pack's chunk layout is declared in gradle/bundled-models.json ("module" field).

apply(from = rootProject.file("gradle/modelpack-assets.gradle"))
