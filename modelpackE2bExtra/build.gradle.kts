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
    .gradleProperty("pocketmind.bundledModelsDir")
    .orElse(providers.environmentVariable("POCKETMIND_BUNDLED_MODELS_DIR"))
val bundledModelsHuggingFaceToken = providers
    .gradleProperty("pocketmind.huggingFaceToken")
    .orElse(providers.environmentVariable("POCKETMIND_HF_TOKEN"))
val bundledModelsAssetRoot = layout.buildDirectory.dir("generated/bundledModels/assets")
val bundledModelsCacheRoot = rootProject.layout.projectDirectory.dir("app/build/bundled-model-cache")

data class SourceModelSpec(
    val fileName: String,
    val byteSize: Long,
    val sha256Hex: String,
    val downloadUrl: String,
    val requiresHuggingFaceAuthorization: Boolean = false,
)

data class ChunkSpec(
    val sourceFileName: String,
    val assetFileName: String,
    val offset: Long,
    val byteSize: Long,
)

abstract class PrepareModelPackAssetsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val sources = listOf(
    SourceModelSpec(
        fileName = "gemma-4-E2B-it.litertlm",
        byteSize = 2_588_147_712L,
        sha256Hex = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/a4a831c060880f3733135ad22f10e0e9f758f45d/gemma-4-E2B-it.litertlm?download=true",
    ),
    SourceModelSpec(
        fileName = "embeddinggemma-300M_seq256_mixed-precision.tflite",
        byteSize = 179_131_736L,
        sha256Hex = "37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/870cbe05ef460385363c6b574c851ae5d8989ce3/embeddinggemma-300M_seq256_mixed-precision.tflite?download=true",
        requiresHuggingFaceAuthorization = true,
    ),
    SourceModelSpec(
        fileName = "sentencepiece.model",
        byteSize = 4_683_319L,
        sha256Hex = "d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/870cbe05ef460385363c6b574c851ae5d8989ce3/sentencepiece.model?download=true",
        requiresHuggingFaceAuthorization = true,
    ),
    SourceModelSpec(
        fileName = "mobile-actions_q8_ekv1024.litertlm",
        byteSize = 284_426_240L,
        sha256Hex = "92109695f911d1872fa8ae07c1e3ff0ed70f2c3d1690d410ec6db8587c2ab409",
        downloadUrl = "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm/resolve/82d0f654a6270c518d16c600edce3136221b3347/mobile-actions_q8_ekv1024.litertlm?download=true",
    ),
)

val e2bChunk = ChunkSpec(
    sourceFileName = "gemma-4-E2B-it.litertlm",
    assetFileName = "chunks/gemma-4-E2B-it.litertlm.part001",
    offset = 1_400_000_000L,
    byteSize = 1_188_147_712L,
)

fun downloadToFile(url: String, outputFile: File, authorizationHeader: String? = null) {
    var lastFailure: Exception? = null
    repeat(3) { attempt ->
        try {
            val connection = URI(url).toURL().openConnection()
            if (!authorizationHeader.isNullOrBlank()) connection.setRequestProperty("Authorization", authorizationHeader)
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.getInputStream().use { input -> outputFile.outputStream().use { output -> input.copyTo(output) } }
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
    return digest.digest().joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

fun isExpectedSource(file: File, spec: SourceModelSpec): Boolean =
    file.isFile && file.length() == spec.byteSize && sha256(file).equals(spec.sha256Hex, ignoreCase = true)

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
    check(temp.length() == chunk.byteSize)
    target.delete()
    check(temp.renameTo(target)) { "Unable to publish chunk ${chunk.assetFileName}" }
}

android {
    namespace = "com.bytedance.zgx.pocketmind.modelpack.e2b.extra"
    compileSdk = 36

    defaultConfig { minSdk = 28 }

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

    androidResources { noCompress += listOf("litertlm", "tflite", "model") }
}

val prepareBundledModelsAssets by tasks.registering(PrepareModelPackAssetsTask::class) {
    description = "Prepare the second E2B chunk plus memory/action assets for the bundledModels variant."
    outputDir.set(bundledModelsAssetRoot)
    inputs.property("sources", sources.joinToString("|") { "${it.fileName}:${it.byteSize}:${it.sha256Hex}" })
    inputs.property("chunk", "${e2bChunk.assetFileName}:${e2bChunk.offset}:${e2bChunk.byteSize}")
    inputs.property("bundledModelLayout", "split-modelpack-e2b-extra-v1")
    inputs.property("bundledModelsSourceDir", bundledModelsSourceDir.orNull ?: "")

    doLast {
        val assetDir = outputDir.get().asFile.resolve("pocketmind-bundled-models")
        val cacheDir = bundledModelsCacheRoot.asFile
        val sourceDir = bundledModelsSourceDir.orNull?.takeIf { it.isNotBlank() }?.let(::file)
        val huggingFaceAuthorizationHeader = bundledModelsHuggingFaceToken.orNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { token -> "Bearer $token" }
        assetDir.deleteRecursively()
        assetDir.mkdirs()
        cacheDir.mkdirs()

        fun sourceFor(spec: SourceModelSpec): File {
            return sourceDir?.resolve(spec.fileName)?.takeIf { isExpectedSource(it, spec) }
                ?: cacheDir.resolve(spec.fileName).takeIf { isExpectedSource(it, spec) }
                ?: run {
                    if (spec.requiresHuggingFaceAuthorization && huggingFaceAuthorizationHeader == null) {
                        error("Bundled model ${spec.fileName} requires Hugging Face authorization.")
                    }
                    val tempDownload = cacheDir.resolve("${spec.fileName}.download")
                    tempDownload.delete()
                    println("Downloading bundled model asset: ${spec.fileName}")
                    downloadToFile(spec.downloadUrl, tempDownload, huggingFaceAuthorizationHeader)
                    check(isExpectedSource(tempDownload, spec)) { "Bundled model source failed verification: ${spec.fileName}" }
                    tempDownload.copyTo(cacheDir.resolve(spec.fileName), overwrite = true)
                    tempDownload.delete()
                    cacheDir.resolve(spec.fileName)
                }
        }

        val e2bSource = sourceFor(requireNotNull(sources.firstOrNull { it.fileName == e2bChunk.sourceFileName }))
        val chunkTarget = assetDir.resolve(e2bChunk.assetFileName)
        if (!chunkTarget.isFile || chunkTarget.length() != e2bChunk.byteSize) {
            copyChunk(e2bSource, chunkTarget, e2bChunk)
        }
        println("Bundled model chunk ready: ${e2bChunk.assetFileName}")

        sources.filter { it.fileName != e2bChunk.sourceFileName }.forEach { spec ->
            val source = sourceFor(spec)
            val target = assetDir.resolve(spec.fileName)
            if (!target.isFile || target.length() != spec.byteSize) {
                target.parentFile.mkdirs()
                val temp = target.resolveSibling("${target.name}.tmp")
                temp.delete()
                source.copyTo(temp, overwrite = true)
                check(isExpectedSource(temp, spec)) { "Bundled model asset failed verification: ${spec.fileName}" }
                target.delete()
                check(temp.renameTo(target)) { "Unable to publish bundled model asset: ${spec.fileName}" }
            }
            println("Bundled model asset ready: ${spec.fileName}")
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

dependencies { implementation(project(":app")) }
