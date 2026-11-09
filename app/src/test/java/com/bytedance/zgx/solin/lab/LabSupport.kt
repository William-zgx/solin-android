package com.bytedance.zgx.solin.lab

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume

/**
 * Shared plumbing for the opt-in capability bench. Every lab test calls [requireLabOptIn] first, so a
 * normal `testDebugUnitTest` run skips the whole package and never talks to Ollama.
 *
 * Opt-in: environment variable `SOLIN_LAB=1` (Gradle forwards the client environment to the test JVM).
 * Because the env var is not a task input, re-running after a skipped run needs `--rerun`.
 */
object LabEnv {
    const val OPT_IN_VARIABLE = "SOLIN_LAB"

    val enabled: Boolean
        get() = System.getenv(OPT_IN_VARIABLE) == "1"

    val ollamaBaseUrl: String
        get() = System.getenv("SOLIN_LAB_OLLAMA_URL")?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:11434"

    /** Root containing the production LiteRT files; no host-specific fallback is committed. */
    val liteRtModelRoot: File?
        get() = System.getenv("SOLIN_LAB_LITERT_MODEL_ROOT")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)

    /** Initial-planning cases default to the tuned 151; held-out evaluation must be explicit. */
    val initialPlanningCaseSource: String
        get() {
            val source = System.getenv("SOLIN_LAB_INITIAL_PLANNING_CASE_SOURCE")
                ?.takeIf { it.isNotBlank() }
                ?: "tuning"
            require(source in setOf("tuning", "heldout")) {
                "SOLIN_LAB_INITIAL_PLANNING_CASE_SOURCE must be tuning or heldout, not $source"
            }
            return source
        }

    /** Optional per-suite case cap for smoke runs; null means the full dataset. */
    val caseLimit: Int?
        get() = System.getenv("SOLIN_LAB_LIMIT")?.toIntOrNull()?.takeIf { it > 0 }

    /** Optional explicit case ids for a small transport smoke; unset means every labelled case. */
    val requestedCaseIds: Set<String>?
        get() = System.getenv("SOLIN_LAB_CASE_IDS")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }

    val requestedModels: List<String>
        get() = System.getenv("SOLIN_LAB_MODELS")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: DEFAULT_MODELS

    val requestedSuites: Set<String>
        get() = System.getenv("SOLIN_LAB_SUITES")
            ?.split(',')
            ?.map { it.trim().uppercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: setOf("A", "B", "C", "D")

    /** Optional model-mode filter (for example `N-topk`); null runs every mode. Rule baselines always run. */
    val requestedModes: Set<String>?
        get() = System.getenv("SOLIN_LAB_MODES")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }

    fun modeSelected(mode: String): Boolean = requestedModes?.contains(mode) ?: true

    val runDate: String
        get() = System.getenv("SOLIN_LAB_DATE")
            ?.takeIf { it.matches(Regex("\\d{8}(?:-heldout)?")) }
            ?: LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)

    /**
     * Who produced the numbers. Override with `SOLIN_LAB_HOST_NOTE`; the default is the Mac bench
     * host, so a devbox run must set it (`devbox CPU, Ollama 0.40.13, no GPU`) rather than inherit a
     * Mac label. The runner is read from `/api/tags` details, so this only names the machine.
     */
    val hostNote: String
        get() = System.getenv("SOLIN_LAB_HOST_NOTE")?.takeIf { it.isNotBlank() }
            ?: "Apple M3 Pro 36 GB, Ollama MLX runner; desktop numbers are not device evidence"

    val repoRoot: File by lazy {
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "app").isDirectory }
            ?: error("Cannot locate repository root from ${System.getProperty("user.dir")}")
    }

    val labDir: File get() = File(repoRoot, "lab")
    val datasetsDir: File get() = File(labDir, "datasets")
    val resultsDir: File get() = File(File(labDir, "results"), runDate).apply { mkdirs() }

    val DEFAULT_MODELS = listOf("qwen3.5:2b", "qwen3.5:4b", "gemma4:e2b", "gemma4:e4b")
}

fun requireLabOptIn(suite: String? = null) {
    Assume.assumeTrue("Lab bench is opt-in: set ${LabEnv.OPT_IN_VARIABLE}=1", LabEnv.enabled)
    if (suite != null) {
        Assume.assumeTrue("Suite $suite not selected by SOLIN_LAB_SUITES", suite in LabEnv.requestedSuites)
    }
}

object LabLog {
    private val file: File by lazy { File(LabEnv.resultsDir, "run.log") }

    @Synchronized
    fun info(message: String) {
        val line = "${Instant.now()} $message"
        println(line)
        file.appendText(line + "\n")
    }
}

/** Writes one JSONL file per (suite, model, mode); the file is replaced at the start of each run. */
class JsonlWriter(suite: String, model: String, mode: String) : AutoCloseable {
    val file: File = File(LabEnv.resultsDir, "${suite}__${model.sanitizedForFileName()}__$mode.jsonl")
    private val writer = file.bufferedWriter(Charsets.UTF_8)

    fun write(record: JSONObject) {
        writer.write(record.toString())
        writer.newLine()
        writer.flush()
    }

    override fun close() = writer.close()
}

fun String.sanitizedForFileName(): String = replace(Regex("[^A-Za-z0-9._-]"), "-")

fun String.previewForLab(maxChars: Int = 400): String =
    replace(Regex("\\s+"), " ").trim().take(maxChars)

fun readJsonl(file: File): List<JSONObject> =
    file.readLines(Charsets.UTF_8)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") }
        .map { JSONObject(it) }

fun JSONArray?.stringList(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }

fun JSONObject?.stringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    val result = linkedMapOf<String, String>()
    keys().forEach { key -> result[key] = get(key).toString() }
    return result
}

fun Map<String, String>.toJson(): JSONObject = JSONObject().also { json -> forEach { (k, v) -> json.put(k, v) } }

/**
 * Records model identity once per run so result files can be tied to an exact weight digest. Merges
 * with an existing manifest so suites run in separate Gradle invocations share one file.
 */
object LabManifest {
    @Synchronized
    fun recordModels(client: OllamaClient, models: List<String>) {
        val file = File(LabEnv.resultsDir, "manifest.json")
        val manifest = if (file.isFile) JSONObject(file.readText()) else JSONObject()
        manifest.put("runDate", LabEnv.runDate)
        manifest.put("ollamaVersion", runCatching { client.version() }.getOrDefault("unknown"))
        manifest.put("gitHead", gitHead())
        // The host is part of the result: a devbox CPU run and a Mac MLX run must never share a column.
        // It is recorded per model as well, because one folder can hold an Ollama run and a LiteRT
        // run on the same day and the top-level note only names the last host that wrote here.
        manifest.put("hostNote", LabEnv.hostNote)
        manifest.put("samplingOptions", OllamaClient.Options().toJson())
        val modelsJson = manifest.optJSONObject("models") ?: JSONObject()
        val tags = runCatching { client.tags() }.getOrNull()
        models.forEach { model ->
            val tag = tags?.let { array ->
                (0 until array.length()).map { array.getJSONObject(it) }.firstOrNull { it.optString("name") == model }
            }
            modelsJson.put(
                model,
                JSONObject()
                    .put("digest", tag?.optString("digest"))
                    .put("sizeBytes", tag?.optLong("size"))
                    .put("host", LabEnv.hostNote)
                    .put("details", tag?.optJSONObject("details")),
            )
        }
        manifest.put("models", modelsJson)
        file.writeText(manifest.toString(2))
    }

    @Synchronized
    fun recordSuite(suite: String, info: JSONObject) {
        val file = File(LabEnv.resultsDir, "manifest.json")
        val manifest = if (file.isFile) JSONObject(file.readText()) else JSONObject()
        val suites = manifest.optJSONObject("suites") ?: JSONObject()
        suites.put(suite, info)
        manifest.put("suites", suites)
        file.writeText(manifest.toString(2))
    }

    /** Marks a (suite, model) pair that cannot run, so the summary reports it instead of a missing row. */
    @Synchronized
    fun recordUnsupported(suite: String, model: String, reason: String) {
        val file = File(LabEnv.resultsDir, "manifest.json")
        val manifest = if (file.isFile) JSONObject(file.readText()) else JSONObject()
        val unsupported = manifest.optJSONObject("unsupported") ?: JSONObject()
        val bySuite = unsupported.optJSONObject(suite) ?: JSONObject()
        bySuite.put(model, reason)
        unsupported.put(suite, bySuite)
        manifest.put("unsupported", unsupported)
        file.writeText(manifest.toString(2))
    }

    private fun gitHead(): String =
        runCatching {
            val process = ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(LabEnv.repoRoot)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
        }.getOrDefault("unknown")
}

/** Models from [LabEnv.requestedModels] that Ollama actually has; the rest are logged as pending. */
fun availableLabModels(client: OllamaClient): List<String> {
    val installed = runCatching {
        val tags = client.tags()
        (0 until tags.length()).map { tags.getJSONObject(it).optString("name") }.toSet()
    }.getOrDefault(emptySet())
    val (present, missing) = LabEnv.requestedModels.partition { it in installed }
    if (missing.isNotEmpty()) LabLog.info("models not installed (pending): $missing")
    return present
}
