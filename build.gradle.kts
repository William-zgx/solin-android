import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.dynamic.feature) apply false
    alias(libs.plugins.android.test) apply false
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

val bundledModelAssetRoot = "assets/solin-bundled-models/"
val bundledModelManifestPath = "${bundledModelAssetRoot}manifest.json"

fun bundledModelAssetPathsFromManifest(manifest: String): Set<String> {
    val modelObjectPattern = Regex("""\{([^{}]+)}""")
    val fileNamePattern = Regex(""""fileName"\s*:\s*"([^"]+)"""")
    val chunkListPattern = Regex(""""chunks"\s*:\s*\[([^]]*)]""")
    val quotedValuePattern = Regex(""""([^"]+)"""")

    return modelObjectPattern.findAll(manifest).flatMap { match ->
        val modelObject = match.groupValues[1]
        val fileName = fileNamePattern.find(modelObject)?.groupValues?.get(1)
            ?: error("Bundled model manifest entry is missing fileName: $modelObject")
        val chunks = chunkListPattern.find(modelObject)
            ?.groupValues
            ?.get(1)
            ?.let(quotedValuePattern::findAll)
            ?.map { chunk -> chunk.groupValues[1] }
            ?.toList()
            .orEmpty()
        (if (chunks.isEmpty()) listOf(fileName) else chunks)
            .map { asset -> "$bundledModelAssetRoot$asset" }
    }.toSet()
}

fun zipEntryNames(path: String): Set<String> =
    ZipFile(path).use { zipFile ->
        zipFile.entries().asSequence().map { entry -> entry.name }.toSet()
    }

fun readBundledModelManifest(path: String): String =
    ZipFile(path).use { zipFile ->
        val manifestEntry = zipFile.getEntry(bundledModelManifestPath)
            ?: error("Bundled base APK is missing $bundledModelManifestPath")
        zipFile.getInputStream(manifestEntry).bufferedReader().use { reader -> reader.readText() }
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

        val baseApk = bundledModelsPackageApks.first()
        val declaredAssets = bundledModelAssetPathsFromManifest(readBundledModelManifest(baseApk))
        check(declaredAssets.isNotEmpty()) {
            "Bundled model manifest does not declare any model assets."
        }
        val splitAssets = bundledModelsPackageApks.drop(1).flatMap(::zipEntryNames).toSet()
        val missingAssets = declaredAssets - splitAssets
        check(missingAssets.isEmpty()) {
            "Bundled model manifest declares assets missing from feature splits: ${missingAssets.sorted().joinToString()}"
        }
        println("Bundled model manifest assets verified: ${declaredAssets.size}")
    }
}
