import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.dynamic.feature)
}

val bundledModelsSourceDir = providers
    .gradleProperty("solin.bundledModelsDir")
    .orElse(providers.environmentVariable("SOLIN_BUNDLED_MODELS_DIR"))
val bundledModelsHuggingFaceToken = providers
    .gradleProperty("solin.huggingFaceToken")
    .orElse(providers.environmentVariable("SOLIN_HF_TOKEN"))
val bundledModelsAssetRoot = layout.buildDirectory.dir("generated/bundledModels/assets")
val bundledModelsCacheRoot = rootProject.layout.projectDirectory.dir("app/build/bundled-model-cache")

data class SourceModelSpec(
    val fileName: String,
    val byteSize: Long,
    val sha256Hex: String,
    val downloadUrl: String,
)

data class ChunkSpec(
    val assetFileName: String,
    val offset: Long,
    val byteSize: Long,
)

abstract class PrepareModelPackAssetsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val e2bSource = SourceModelSpec(
    fileName = "gemma-4-E2B-it.litertlm",
    byteSize = 2_588_147_712L,
    sha256Hex = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
    downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/a4a831c060880f3733135ad22f10e0e9f758f45d/gemma-4-E2B-it.litertlm?download=true",
)

val e2bChunk = ChunkSpec(
    assetFileName = "chunks/gemma-4-E2B-it.litertlm.part000",
    offset = 0L,
    byteSize = 1_400_000_000L,
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
            if (attempt < 2) Thread.sleep(2_000L * (attempt + 1))
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

fun isExpectedSource(file: File, spec: SourceModelSpec): Boolean =
    file.isFile &&
        file.length() == spec.byteSize &&
        sha256(file).equals(spec.sha256Hex, ignoreCase = true)

fun copyChunk(sourceFile: File, target: File, chunk: ChunkSpec) {
    target.parentFile.mkdirs()
    val temp = target.resolveSibling("${target.name}.tmp")
    temp.delete()
    sourceFile.inputStream().use { input ->
        var skipped = 0L
        while (skipped < chunk.offset) {
            val delta = input.skip(chunk.offset - skipped)
            check(delta > 0L) { "Unable to seek ${chunk.assetFileName}" }
            skipped += delta
        }
        temp.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = chunk.byteSize
            while (remaining > 0L) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(read > 0) { "Unexpected EOF while writing ${chunk.assetFileName}" }
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }
    check(temp.length() == chunk.byteSize) {
        "Unexpected chunk size for ${chunk.assetFileName}: ${temp.length()} (expected ${chunk.byteSize})"
    }
    target.delete()
    check(temp.renameTo(target)) { "Unable to publish chunk ${chunk.assetFileName}" }
}

android {
    namespace = "com.bytedance.zgx.solin.modelpack.e2b"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    buildTypes {
        release { isMinifyEnabled = false }
        create("rcPerfRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
        create("bundledModels") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
    }

    androidResources {
        noCompress += listOf("litertlm", "tflite", "model")
    }
}

val prepareBundledModelsAssets by tasks.registering(PrepareModelPackAssetsTask::class) {
    description = "Prepare the first E2B model chunk for the bundledModels variant."
    outputDir.set(bundledModelsAssetRoot)
    inputs.property("source", "${e2bSource.fileName}:${e2bSource.byteSize}:${e2bSource.sha256Hex}")
    inputs.property("chunk", "${e2bChunk.assetFileName}:${e2bChunk.offset}:${e2bChunk.byteSize}")
    inputs.property("bundledModelLayout", "split-modelpack-e2b-chunk-v1")
    inputs.property("bundledModelsSourceDir", bundledModelsSourceDir.orNull ?: "")

    doLast {
        val assetDir = outputDir.get().asFile.resolve("solin-bundled-models")
        val cacheDir = bundledModelsCacheRoot.asFile
        val sourceDir = bundledModelsSourceDir.orNull?.takeIf { it.isNotBlank() }?.let(::file)
        val huggingFaceAuthorizationHeader = bundledModelsHuggingFaceToken.orNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { token -> "Bearer $token" }
        assetDir.deleteRecursively()
        assetDir.mkdirs()
        cacheDir.mkdirs()

        val sourceFile = sourceDir?.resolve(e2bSource.fileName)?.takeIf { isExpectedSource(it, e2bSource) }
            ?: cacheDir.resolve(e2bSource.fileName).takeIf { isExpectedSource(it, e2bSource) }
            ?: run {
                val tempDownload = cacheDir.resolve("${e2bSource.fileName}.download")
                tempDownload.delete()
                println("Downloading bundled model asset: ${e2bSource.fileName}")
                downloadToFile(e2bSource.downloadUrl, tempDownload, huggingFaceAuthorizationHeader)
                check(isExpectedSource(tempDownload, e2bSource)) {
                    "Bundled model source failed verification: ${e2bSource.fileName}"
                }
                tempDownload.copyTo(cacheDir.resolve(e2bSource.fileName), overwrite = true)
                tempDownload.delete()
                cacheDir.resolve(e2bSource.fileName)
            }

        val target = assetDir.resolve(e2bChunk.assetFileName)
        if (target.isFile && target.length() == e2bChunk.byteSize) {
            println("Bundled model chunk ready: ${e2bChunk.assetFileName}")
        } else {
            copyChunk(sourceFile, target, e2bChunk)
            println("Bundled model chunk ready: ${e2bChunk.assetFileName}")
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("bundledModels")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            prepareBundledModelsAssets,
            PrepareModelPackAssetsTask::outputDir,
        )
    }
}

dependencies {
    implementation(project(":app"))
}
