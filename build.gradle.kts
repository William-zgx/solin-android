plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.dynamic.feature) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

val bundledModelsPackageApks = listOf(
    "app/build/outputs/apk/bundledModels/app-bundledModels-unsigned.apk",
    "modelpackE2b/build/outputs/apk/bundledModels/modelpackE2b-bundledModels.apk",
    "modelpackE2bExtra/build/outputs/apk/bundledModels/modelpackE2bExtra-bundledModels.apk",
    "modelpackE4b/build/outputs/apk/bundledModels/modelpackE4b-bundledModels.apk",
    "modelpackE4bExtra/build/outputs/apk/bundledModels/modelpackE4bExtra-bundledModels.apk",
)

tasks.register("assembleBundledModelsPackage") {
    group = "distribution"
    description = "Builds the internal bundledModels quick-experience split APK set."
    dependsOn(
        ":app:assembleBundledModels",
        ":modelpackE2b:assembleBundledModels",
        ":modelpackE2bExtra:assembleBundledModels",
        ":modelpackE4b:assembleBundledModels",
        ":modelpackE4bExtra:assembleBundledModels",
    )
}

tasks.register("bundleBundledModelsPackage") {
    group = "distribution"
    description = "Builds the internal bundledModels Android App Bundle."
    dependsOn(":app:bundleBundledModels")
}

tasks.register("checkBundledModelsPackageOutputs") {
    group = "verification"
    description = "Verifies that the bundledModels split APK outputs exist."
    dependsOn("assembleBundledModelsPackage")

    doLast {
        val missing = bundledModelsPackageApks.filterNot { file(it).isFile }
        check(missing.isEmpty()) {
            "Missing bundledModels split APK output(s): ${missing.joinToString()}"
        }
        bundledModelsPackageApks.forEach { path ->
            val artifact = file(path)
            println("${artifact.path} ${artifact.length()} bytes")
        }
    }
}
