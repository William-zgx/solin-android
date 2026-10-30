plugins {
    alias(libs.plugins.android.dynamic.feature)
}

extra["modelPackNamespace"] = "com.bytedance.zgx.solin.modelpack.e2b"
extra["modelPackLayout"] = "split-modelpack-e2b-chunk-v1"
extra["modelPackDescription"] = "Prepare the first E2B model chunk for the bundledModels variant."
extra["modelPackBaseSourceKey"] = "e2b"
// This pack's chunk layout is declared in gradle/bundled-models.json ("module" field).

apply(from = rootProject.file("gradle/modelpack-assets.gradle"))
