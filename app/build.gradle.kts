import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import java.net.URI
import java.security.MessageDigest

val huggingFaceOAuthClientId: String = providers
    .gradleProperty("solin.huggingFaceOAuthClientId")
    .orElse(providers.environmentVariable("SOLIN_HF_OAUTH_CLIENT_ID"))
    .orElse("")
    .get()

val zvecVersion = "0.5.1"
val zvecGeneratedRoot = layout.buildDirectory.dir("generated/zvec").get().asFile
val zvecJniLibsRoot = zvecGeneratedRoot.resolve("jniLibs")
val zvecArchiveLibName = "libzvec.so"
val zvecRuntimeLibName = "libzvec_c_api.so"
val zvecArm64Lib = zvecJniLibsRoot.resolve("arm64-v8a/$zvecRuntimeLibName")
val zvecArm64ArchiveLib = zvecJniLibsRoot.resolve("arm64-v8a/$zvecArchiveLibName")
val zvecIncludeRoot = zvecGeneratedRoot.resolve("include")
val zvecCApiHeader = zvecIncludeRoot.resolve("zvec/c_api.h")
val zvecNativeBaseUrl = "https://github.com/zvec-ai/zvec-dart/releases/download/v$zvecVersion"
val zvecArm64LibSha256 = "708a58bf32a232890fd3e761bf662c05357aca9e0a4ba2783e4a9f86b78bbe3f"
val zvecCApiHeaderSha256 = "ea6b3f3373f29799a885442bf51ec1604426055e5b7b56ea2e12d8ccd70b2af0"
val zvecAndroidArm64ZipSeed = providers
    .gradleProperty("solin.zvecAndroidArm64Zip")
    .orElse(providers.environmentVariable("SOLIN_ZVEC_ANDROID_ARM64_ZIP"))
    .orNull
val zvecCApiHeaderSeed = providers
    .gradleProperty("solin.zvecCApiHeader")
    .orElse(providers.environmentVariable("SOLIN_ZVEC_C_API_HEADER"))
    .orNull
val bundledModelsSourceDir = providers
    .gradleProperty("solin.bundledModelsDir")
    .orElse(providers.environmentVariable("SOLIN_BUNDLED_MODELS_DIR"))
val bundledModelsHuggingFaceToken = providers
    .gradleProperty("solin.huggingFaceToken")
    .orElse(providers.environmentVariable("SOLIN_HF_TOKEN"))
val roomSchemaDir = layout.projectDirectory.dir("schemas").asFile
val bundledModelsAssetRoot = layout.buildDirectory.dir("generated/bundledModels/assets")
val bundledModelsCacheRoot = layout.buildDirectory.dir("bundled-model-cache")
val bundledModelPackModules = setOf(
    ":modelpackE2b",
    ":modelpackE2bExtra",
    ":modelpackE4b",
    ":modelpackE4bExtra",
)
val includeBundledModelPacks = providers
    .gradleProperty("solin.includeBundledModelPacks")
    .map(String::toBoolean)
    .orElse(false)
    .get() || gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("BundledModels", ignoreCase = true)
    }

data class BundledModelAssetSpec(
    val modelId: String,
    val fileName: String,
    val byteSize: Long,
    val sha256Hex: String,
    val downloadUrl: String,
    val primary: Boolean = true,
    val requiresHuggingFaceAuthorization: Boolean = false,
    val chunkFileNames: List<String> = emptyList(),
)

abstract class PrepareBundledModelAssetsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

// Pinned model asset metadata lives in gradle/bundled-models.json, shared with
// gradle/modelpack-assets.gradle and scripts/verify_local.sh.
// BundledModelAssetsCatalogTest keeps it in sync with RECOMMENDED_MODELS.
val bundledModelsCatalogFile = rootProject.file("gradle/bundled-models.json")

@Suppress("UNCHECKED_CAST")
val bundledModelAssetSpecs: List<BundledModelAssetSpec> =
    ((groovy.json.JsonSlurper().parse(bundledModelsCatalogFile) as Map<String, Any?>)["assets"] as List<Map<String, Any?>>)
        .map { asset ->
            BundledModelAssetSpec(
                modelId = asset.getValue("modelId") as String,
                fileName = asset.getValue("fileName") as String,
                byteSize = (asset.getValue("byteSize") as Number).toLong(),
                sha256Hex = asset.getValue("sha256Hex") as String,
                downloadUrl = asset.getValue("downloadUrl") as String,
                primary = asset.getValue("primary") as Boolean,
                requiresHuggingFaceAuthorization = asset.getValue("requiresHuggingFaceAuthorization") as Boolean,
                chunkFileNames = (asset["chunks"] as List<Map<String, Any?>>? ?: emptyList())
                    .map { chunk -> chunk.getValue("assetFileName") as String },
            )
        }

fun downloadToFile(url: String, outputFile: File, authorizationHeader: String? = null) {
    var lastFailure: Exception? = null
    repeat(3) { attempt ->
        try {
            val connection = URI(url).toURL().openConnection()
            if (!authorizationHeader.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", authorizationHeader)
            }
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.getInputStream().use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            }
            return
        } catch (error: Exception) {
            lastFailure = error
            if (attempt < 2) {
                Thread.sleep(2_000L * (attempt + 1))
            }
        }
    }
    throw GradleException("Failed to download $url", lastFailure)
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

fun verifySha256(file: File, expected: String) {
    val actual = sha256(file)
    check(actual == expected) {
        "Unexpected SHA-256 for ${file.name}: $actual (expected $expected)"
    }
}

fun isExpectedBundledModelFile(file: File, spec: BundledModelAssetSpec): Boolean =
    file.isFile &&
        file.length() == spec.byteSize &&
        sha256(file).equals(spec.sha256Hex, ignoreCase = true)

fun jsonEscape(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

fun jsonStringArray(values: List<String>): String =
    values.joinToString(prefix = "[", postfix = "]") { value -> "\"${jsonEscape(value)}\"" }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.bytedance.zgx.solin"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    if (includeBundledModelPacks) {
        dynamicFeatures += bundledModelPackModules
    }

    defaultConfig {
        applicationId = "com.bytedance.zgx.solin"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "HUGGING_FACE_OAUTH_CLIENT_ID", "\"$huggingFaceOAuthClientId\"")
        buildConfigField("Boolean", "BUNDLED_MODELS_ENABLED", "false")
        buildConfigField("String", "ADAPTIVE_INFERENCE_ROLLOUT_STAGE", "\"opt_in\"")
        // RC perf collection entry points are gated off by default. Only the dedicated
        // rcPerfRelease variant flips this to true, so the production release never exposes the
        // harness receiver/activity/service.
        buildConfigField("Boolean", "RC_PERF_ENABLED", "false")

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DZVEC_PREBUILT_DIR=${zvecJniLibsRoot.absolutePath}",
                    "-DZVEC_INCLUDE_DIR=${zvecIncludeRoot.absolutePath}",
                )
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "ADAPTIVE_INFERENCE_ROLLOUT_STAGE", "\"opt_in\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                debugSymbolLevel = "NONE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // release-like variant used only for RC performance collection. It inherits the
        // release minify/shrink/proguard path so measured numbers reflect the shipping shape,
        // but enables the controlled RC perf harness and is signed with the debug key so it can
        // be installed on a real device. It deliberately keeps the production applicationId (no
        // suffix) so the harness can exercise models the app already downloaded on the device,
        // and it never wipes app data or model directories.
        create("rcPerfRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("Boolean", "RC_PERF_ENABLED", "true")
        }
        // Release-like offline experience package. It keeps the production applicationId and
        // release runtime shape, but packages recommended model assets so first launch can import
        // them without a Hugging Face download flow.
        create("bundledModels") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            proguardFiles("proguard-bundled-models-instrumentation.pro")
            buildConfigField("Boolean", "BUNDLED_MODELS_ENABLED", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        noCompress += listOf("litertlm", "tflite", "model")
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add(zvecJniLibsRoot.absolutePath)
        }
        // Exported Room schemas (see ksp room.schemaLocation below) are packaged into the
        // instrumentation APK so androidx.room.testing.MigrationTestHelper can load them.
        getByName("androidTest") {
            assets.directories.add(roomSchemaDir.absolutePath)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
}

// Room writes <version>.json schema snapshots here when @Database(exportSchema = true);
// the argument is ignored while exportSchema is false. Commit the generated app/schemas/.
// The schema files are tracked as KSP inputs (relative paths) so a deleted/edited snapshot
// re-runs Room and the absolute path does not leak into build-cache keys.
class RoomSchemaArgProvider(
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val schemaDir: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("room.schemaLocation=${schemaDir.path}")
}

ksp {
    arg(RoomSchemaArgProvider(roomSchemaDir))
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    // BundledModelAssetsCatalogTest reads this file from the repo root; rerun tests when it changes.
    inputs.file(bundledModelsCatalogFile)
        .withPropertyName("bundledModelsCatalog")
        .withPathSensitivity(PathSensitivity.NONE)
    (
        providers.gradleProperty("aiBehaviorActualTraceFile").orNull
            ?: providers.systemProperty("aiBehaviorActualTraceFile").orNull
    )?.let { outputPath ->
        systemProperty("aiBehaviorActualTraceFile", outputPath)
    }
}

val downloadZvecNativeLibs by tasks.registering {
    description = "Download zvec v$zvecVersion Android arm64 native library and C API header."
    inputs.property("zvecVersion", zvecVersion)
    inputs.property("zvecArm64LibSha256", zvecArm64LibSha256)
    inputs.property("zvecCApiHeaderSha256", zvecCApiHeaderSha256)
    inputs.property("zvecAndroidArm64ZipSeed", zvecAndroidArm64ZipSeed ?: "")
    inputs.property("zvecCApiHeaderSeed", zvecCApiHeaderSeed ?: "")
    outputs.file(zvecArm64Lib)
    outputs.file(zvecCApiHeader)

    doLast {
        if (!zvecArm64Lib.isFile) {
            val libDir = zvecArm64Lib.parentFile
            val zipFile = temporaryDir.resolve("libzvec-android-arm64-v8a.zip")
            libDir.mkdirs()
            if (zvecArm64ArchiveLib.isFile) {
                zvecArm64ArchiveLib.copyTo(zvecArm64Lib, overwrite = true)
            } else {
                val seedZip = zvecAndroidArm64ZipSeed?.let(::file)?.takeIf { it.isFile }
                if (seedZip == null) {
                    downloadToFile("$zvecNativeBaseUrl/libzvec-android-arm64-v8a.zip", zipFile)
                } else {
                    seedZip.copyTo(zipFile, overwrite = true)
                }
                copy {
                    from(zipTree(zipFile))
                    into(libDir)
                    include(zvecArchiveLibName)
                    rename { zvecRuntimeLibName }
                }
            }
            check(zvecArm64Lib.isFile) {
                "Downloaded zvec archive did not contain $zvecArchiveLibName"
            }
        }
        verifySha256(zvecArm64Lib, zvecArm64LibSha256)
        zvecArm64ArchiveLib.delete()

        if (!zvecCApiHeader.isFile) {
            zvecCApiHeader.parentFile.mkdirs()
            val seedHeader = zvecCApiHeaderSeed?.let(::file)?.takeIf { it.isFile }
            if (seedHeader == null) {
                downloadToFile(
                    "https://raw.githubusercontent.com/alibaba/zvec/v$zvecVersion/src/include/zvec/c_api.h",
                    zvecCApiHeader,
                )
            } else {
                seedHeader.copyTo(zvecCApiHeader, overwrite = true)
            }
            check(zvecCApiHeader.isFile) {
                "Failed to download zvec c_api.h"
            }
        }
        verifySha256(zvecCApiHeader, zvecCApiHeaderSha256)
    }
}

val prepareBundledModelsAssets by tasks.registering(PrepareBundledModelAssetsTask::class) {
    description = "Prepare bundled model assets for the bundledModels variant."
    outputDir.set(bundledModelsAssetRoot)
    inputs.property(
        "bundledModelSpecs",
        bundledModelAssetSpecs.joinToString("|") { spec ->
            "${spec.modelId}:${spec.fileName}:${spec.byteSize}:${spec.sha256Hex}:${spec.primary}"
        },
    )
    inputs.file(bundledModelsCatalogFile).withPathSensitivity(PathSensitivity.NONE)
    inputs.property("bundledModelLayout", "manifest-only-v2")
    inputs.property("bundledModelsSourceDir", bundledModelsSourceDir.orNull ?: "")

    doLast {
        val assetDir = outputDir.get().asFile.resolve("solin-bundled-models")
        assetDir.deleteRecursively()
        assetDir.mkdirs()

        val manifest = buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"models\": [\n")
            bundledModelAssetSpecs.forEachIndexed { index, spec ->
                append("    {")
                append("\"modelId\": \"${jsonEscape(spec.modelId)}\", ")
                append("\"fileName\": \"${jsonEscape(spec.fileName)}\", ")
                append("\"primary\": ${spec.primary}")
                if (spec.chunkFileNames.isNotEmpty()) {
                    append(", \"chunks\": ${jsonStringArray(spec.chunkFileNames)}")
                }
                append("}")
                if (index != bundledModelAssetSpecs.lastIndex) append(",")
                append("\n")
            }
            append("  ]\n")
            append("}\n")
        }
        assetDir.resolve("manifest.json").writeText(manifest)
    }
}

androidComponents {
    onVariants(selector().withBuildType("bundledModels")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            prepareBundledModelsAssets,
            PrepareBundledModelAssetsTask::outputDir,
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadZvecNativeLibs)
}

tasks.matching { task ->
    task.name != "downloadZvecNativeLibs" &&
        (
            task.name.startsWith("configureCMake") ||
                task.name.startsWith("buildCMake") ||
                task.name.endsWith("NativeLibs")
            )
}.configureEach {
    dependsOn(downloadZvecNativeLibs)
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litertlm.android)
    implementation(libs.localagents.rag)
    // Not referenced from app code: localagents-rag needs protobuf-javalite at runtime but does
    // not pull it in transitively (guarded by EmbeddingRuntimeDependencyTest).
    implementation(libs.protobuf.javalite)
    implementation(libs.sqlcipher.android)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    // Real org.json for JVM unit tests: android.jar only ships throwing stubs, and tests/fixture
    // parsers use JSONObject/JSONArray off-device. Not needed at runtime (Android provides it).
    testImplementation(libs.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
