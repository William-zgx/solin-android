plugins {
    alias(libs.plugins.android.dynamic.feature)
}

extra["modelPackNamespace"] = "com.bytedance.zgx.solin.modelpack.e4b.extra"
extra["modelPackLayout"] = "split-modelpack-e4b-extra-v1"
extra["modelPackDescription"] = "Prepare the second E4B model chunk for the bundledModels variant."
extra["modelPackBaseSourceKey"] = "e4b"
// This pack's chunk layout is declared in gradle/bundled-models.json ("module" field).

apply(from = rootProject.file("gradle/modelpack-assets.gradle"))
