plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.dynamic.feature) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

val bundledModelPackModules = listOf(
    "modelpackE2b",
    "modelpackE2bExtra",
    "modelpackE4b",
    "modelpackE4bExtra",
)

val bundledModelsPackageApks =
    listOf("app/build/outputs/apk/bundledModels/app-bundledModels-unsigned.apk") +
        bundledModelPackModules.map { module ->
            "$module/build/outputs/apk/bundledModels/$module-bundledModels.apk"
        }

tasks.register("assembleBundledModelsPackage") {
    group = "distribution"
    description = "Builds the internal bundledModels quick-experience split APK set."
    dependsOn(
        listOf(":app:assembleBundledModels") +
            bundledModelPackModules.map { module -> ":$module:assembleBundledModels" },
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
