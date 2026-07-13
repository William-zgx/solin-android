import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.OutputDirectory
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

val bundledModelAssetSpecs = listOf(
    BundledModelAssetSpec(
        modelId = "chat-e2b",
        fileName = "gemma-4-E2B-it.litertlm",
        byteSize = 2_588_147_712L,
        sha256Hex = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/a4a831c060880f3733135ad22f10e0e9f758f45d/gemma-4-E2B-it.litertlm?download=true",
        chunkFileNames = listOf(
            "chunks/gemma-4-E2B-it.litertlm.part000",
            "chunks/gemma-4-E2B-it.litertlm.part001",
        ),
    ),
    BundledModelAssetSpec(
        modelId = "memory-embedding-gemma-300m",
        fileName = "embeddinggemma-300M_seq256_mixed-precision.tflite",
        byteSize = 179_131_736L,
        sha256Hex = "37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/870cbe05ef460385363c6b574c851ae5d8989ce3/embeddinggemma-300M_seq256_mixed-precision.tflite?download=true",
        requiresHuggingFaceAuthorization = true,
    ),
    BundledModelAssetSpec(
        modelId = "memory-embedding-gemma-300m",
        fileName = "sentencepiece.model",
        byteSize = 4_683_319L,
        sha256Hex = "d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/870cbe05ef460385363c6b574c851ae5d8989ce3/sentencepiece.model?download=true",
        primary = false,
        requiresHuggingFaceAuthorization = true,
    ),
    BundledModelAssetSpec(
        modelId = "mobile-action-270m",
        fileName = "mobile-actions_q8_ekv1024.litertlm",
        byteSize = 284_426_240L,
        sha256Hex = "92109695f911d1872fa8ae07c1e3ff0ed70f2c3d1690d410ec6db8587c2ab409",
        downloadUrl = "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm/resolve/82d0f654a6270c518d16c600edce3136221b3347/mobile-actions_q8_ekv1024.litertlm?download=true",
    ),
    BundledModelAssetSpec(
        modelId = "chat-e4b",
        fileName = "gemma-4-E4B-it.litertlm",
        byteSize = 3_659_530_240L,
        sha256Hex = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/65ce5ba80d8790d66ef11d82d7d079a06f3fef97/gemma-4-E4B-it.litertlm?download=true",
        chunkFileNames = listOf(
            "chunks/gemma-4-E4B-it.litertlm.part000",
            "chunks/gemma-4-E4B-it.litertlm.part001",
        ),
    ),
)

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
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
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
    implementation(libs.protobuf.javalite)
    implementation(libs.sqlcipher.android)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
