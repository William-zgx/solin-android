package com.bytedance.zgx.solin.lab.memory

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.MessageKind
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.memory.LocalMemoryModel
import com.bytedance.zgx.solin.memory.groundingFold
import com.bytedance.zgx.solin.orchestration.CompactionConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume

/**
 * Opt-in memory bench: drives the production memory prompts, parsers and guards
 * (`LocalModelMemoryFactExtractor`, `DefaultContextCompactor` with a model summary) against small
 * models served by a local Ollama, through [OllamaMemoryModel] as the `LocalMemoryModel` seam.
 *
 * Only the environment variable `SOLIN_LAB_MEMORY=1` turns the bench on. Gradle forwards the client
 * environment to the test JVM, but a `-D` property is not forwarded, so `-Dsolin.lab.memory` does nothing.
 * The variable is not a task input, so pass `--rerun`.
 *
 * Knobs: `SOLIN_LAB_MEMORY_MODELS` (comma list, default `gemma4:e2b`), `SOLIN_LAB_MEMORY_LIMIT`
 * (first N cases), `SOLIN_LAB_MEMORY_DATE` (yyyyMMdd output folder), `SOLIN_LAB_OLLAMA_URL`, and
 * `SOLIN_LAB_MEMORY_REPLAY` (yyyyMMdd): replay the answers saved in `lab/memory/results/<that date>`
 * through the current production rules instead of calling Ollama; this is the offline rescore.
 *
 * Output: a full live run writes `lab/memory/results/<date>/`; a limited or replayed run writes under
 * `build/memory-lab/`, so a smoke test can never land in the committed results. Datasets are synthetic
 * and live in `lab/memory/datasets/`.
 */
internal object MemoryLab {
    val enabled: Boolean
        get() = System.getenv("SOLIN_LAB_MEMORY") == "1"

    val models: List<String>
        get() = (System.getenv("SOLIN_LAB_MEMORY_MODELS") ?: "gemma4:e2b")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    val ollamaUrl: String
        get() = System.getenv("SOLIN_LAB_OLLAMA_URL")?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:11434"

    val caseLimit: Int?
        get() = System.getenv("SOLIN_LAB_MEMORY_LIMIT")?.toIntOrNull()?.takeIf { it > 0 }

    /** The saved run to replay offline, or null for a live run. */
    val replayDate: String?
        get() = System.getenv("SOLIN_LAB_MEMORY_REPLAY")?.takeIf { it.isNotBlank() }?.let(::validDate)

    private val runDate: String
        get() = System.getenv("SOLIN_LAB_MEMORY_DATE")?.takeIf { it.isNotBlank() }?.let(::validDate)
            ?: LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)

    /** yyyyMMdd and a real calendar date, so a typo cannot name a results folder. */
    private fun validDate(text: String): String {
        require(text.matches(Regex("""\d{8}"""))) { "date must be yyyyMMdd, got '$text'" }
        LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE)
        return text
    }

    private val repoRoot: File by lazy {
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "lab/memory").isDirectory }
            ?: error("Cannot locate the repository root from ${System.getProperty("user.dir")}")
    }

    val datasetsDir: File get() = File(repoRoot, "lab/memory/datasets")

    /**
     * Where this run writes. Only a full live run writes the committed `lab/memory/results/<date>`; a
     * limited or replayed run writes under `build/memory-lab/`. Getting this path creates nothing.
     */
    val outputDir: File
        get() = if (caseLimit == null && replayDate == null) {
            File(repoRoot, "lab/memory/results/$runDate")
        } else {
            File(repoRoot, "build/memory-lab/$runDate" + (replayDate?.let { "-replay-$it" } ?: ""))
        }

    fun savedResultsDir(date: String): File = File(repoRoot, "lab/memory/results/$date")

    /** Per-call budget in the bench: generous, so desktop latency is measured instead of cut off. */
    const val CALL_TIMEOUT_MILLIS = 300_000L
}

internal fun requireMemoryLabOptIn() {
    Assume.assumeTrue("Memory bench is opt-in: set SOLIN_LAB_MEMORY=1", MemoryLab.enabled)
}

/** A file in [MemoryLab.outputDir]; the folder is created here, when a result is actually written. */
internal fun outputFile(name: String): File =
    File(MemoryLab.outputDir, name).also { it.parentFile.mkdirs() }

internal fun <T> List<T>.limitedForLab(): List<T> = MemoryLab.caseLimit?.let(::take) ?: this

internal fun String.fileSafe(): String = replace(Regex("[^A-Za-z0-9._-]"), "-")

/** One `/api/chat` call: the raw answer (null on error), wall time and token counts. */
internal data class OllamaCall(
    val text: String?,
    val wallMillis: Long,
    val promptTokens: Int,
    val evalTokens: Int,
    val error: String?,
)

/** A `LocalMemoryModel` the bench can drive: live (Ollama) or replayed from saved answers. */
internal interface BenchModel : LocalMemoryModel {
    /** Every call made through this model, in order. */
    val calls: List<OllamaCall>

    /** Called before each case so a replay can pick that case's saved answer. */
    fun beginCase(caseId: String) {}

    /** Loads the model so the first measured call does not pay the load time. */
    fun warmUp() {}

    fun describe(): JSONObject
}

/**
 * The bench's on-device stand-in: a blocking, non-streaming Ollama `/api/chat` client behind the
 * production `LocalMemoryModel` seam. Greedy sampling with a fixed seed and no thinking, like the
 * app's local runs. Any transport or server error returns null, as an unavailable engine would.
 */
internal class OllamaMemoryModel(
    private val model: String,
    private val baseUrl: String = MemoryLab.ollamaUrl,
) : BenchModel {
    override val calls = mutableListOf<OllamaCall>()

    override suspend fun generate(systemInstruction: String, prompt: String): String? =
        withContext(Dispatchers.IO) { chat(systemInstruction, prompt).also { calls += it }.text }

    override fun warmUp() {
        chat("Answer with one word.", "Say OK.")
    }

    override fun describe(): JSONObject {
        val tag = runCatching {
            val models = JSONObject(get("/api/tags")).getJSONArray("models")
            (0 until models.length()).map { models.getJSONObject(it) }.firstOrNull { it.optString("name") == model }
        }.getOrNull()
        return JSONObject()
            .put("model", model)
            .put("digest", tag?.optString("digest") ?: JSONObject.NULL)
            .put("details", tag?.optJSONObject("details") ?: JSONObject.NULL)
            .put("ollamaVersion", runCatching { JSONObject(get("/api/version")).optString("version") }.getOrDefault("unknown"))
            .put("options", OPTIONS)
            .put("think", false)
            .put("hostNote", "desktop Ollama proxy; not LiteRT-LM int4 on a phone, not device evidence")
    }

    private fun chat(systemInstruction: String, prompt: String): OllamaCall {
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("think", false)
            .put("keep_alive", "10m")
            .put("options", OPTIONS)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemInstruction))
                    .put(JSONObject().put("role", "user").put("content", prompt)),
            )
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        return try {
            val json = JSONObject(post("/api/chat", body.toString()))
            val error = json.optString("error").takeIf { it.isNotBlank() }
            OllamaCall(
                text = if (error == null) json.optJSONObject("message")?.optString("content") else null,
                wallMillis = elapsed(),
                promptTokens = json.optInt("prompt_eval_count"),
                evalTokens = json.optInt("eval_count"),
                error = error,
            )
        } catch (error: IOException) {
            OllamaCall(null, elapsed(), 0, 0, "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun post(path: String, body: String): String {
        val connection = open(path)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return connection.readBody()
    }

    private fun get(path: String): String = open(path).readBody()

    private fun open(path: String): HttpURLConnection =
        (URI(baseUrl.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = MemoryLab.CALL_TIMEOUT_MILLIS.toInt()
        }

    private fun HttpURLConnection.readBody(): String {
        try {
            val stream = if (responseCode in 200..299) inputStream else errorStream ?: throw IOException("http_$responseCode")
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (responseCode !in 200..299) throw IOException("http_$responseCode: ${text.take(200)}")
            return text
        } finally {
            disconnect()
        }
    }

    private companion object {
        val OPTIONS: JSONObject
            get() = JSONObject()
                .put("temperature", 0.0)
                .put("top_k", 1)
                .put("top_p", 0.1)
                .put("seed", 7)
                .put("num_ctx", 8192)
                .put("num_predict", 512)
    }
}

/**
 * Replays the answers saved in a results folder. Each case gets the answer it got when it was run, so
 * the production code runs unchanged and its metrics follow the current rules. A call the saved run did
 * not make is an error: the replay would then not be the same run.
 */
internal class ReplayMemoryModel(
    private val model: String,
    private val savedDate: String,
    private val saved: Map<String, OllamaCall>,
) : BenchModel {
    override val calls = mutableListOf<OllamaCall>()
    private var caseId: String? = null

    override fun beginCase(caseId: String) {
        this.caseId = caseId
    }

    override suspend fun generate(systemInstruction: String, prompt: String): String? {
        val id = checkNotNull(caseId) { "beginCase must name the case before a replayed call" }
        val call = checkNotNull(saved[id]) { "no saved answer for case $id of $model in $savedDate; the replay is not the saved run" }
        calls += call
        return call.text
    }

    override fun describe(): JSONObject =
        JSONObject()
            .put("model", model)
            .put("digest", JSONObject.NULL)
            .put("replayOf", savedDate)
            .put("hostNote", "offline replay of saved desktop Ollama answers under the current rules; not device evidence")
}

/**
 * The model the bench uses for [suite] (`facts` or `compaction`): live Ollama, or a replay of the saved
 * answers in [textField] (`rawOutput` or `rawSummary`) when `SOLIN_LAB_MEMORY_REPLAY` is set.
 */
internal fun benchModelFor(suite: String, model: String, textField: String): BenchModel {
    val date = MemoryLab.replayDate ?: return OllamaMemoryModel(model)
    val file = File(MemoryLab.savedResultsDir(date), "${suite}__${model.fileSafe()}.jsonl")
    return ReplayMemoryModel(model, date, savedCalls(file, textField))
}

/** The calls a saved run made, keyed by case id; cases that made no call are not in the map. */
internal fun savedCalls(file: File, textField: String): Map<String, OllamaCall> =
    file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map(::JSONObject)
        .filterNot { it.isNull("wallMs") }
        .associate { record ->
            val error = if (record.isNull("error")) null else record.getString("error")
            val text = if (error == null && !record.isNull(textField)) record.getString(textField) else null
            record.getString("id") to OllamaCall(
                text = text,
                wallMillis = record.getLong("wallMs"),
                promptTokens = record.optInt("promptTokens"),
                evalTokens = record.optInt("evalTokens"),
                error = error,
            )
        }

internal data class GoldFact(val kind: String, val fact: String)

internal data class FactCase(val id: String, val message: String, val gold: List<GoldFact>, val tags: List<String>)

internal data class CompactionCase(val id: String, val messages: List<ChatMessage>, val mustKeep: List<String>)

internal object MemoryLabDatasets {
    fun factCases(): List<FactCase> = readJsonl("fact_extraction.jsonl").map { json ->
        FactCase(
            id = json.getString("id"),
            message = json.getString("message"),
            gold = json.getJSONArray("gold").objects().map { GoldFact(it.getString("kind"), it.getString("fact")) },
            tags = json.getJSONArray("tags").strings(),
        )
    }

    fun compactionCases(): List<CompactionCase> = readJsonl("compaction.jsonl").map { json ->
        CompactionCase(
            id = json.getString("id"),
            messages = json.getJSONArray("messages").objects().map { message ->
                ChatMessage(
                    role = if (message.getString("role") == "user") MessageRole.User else MessageRole.Assistant,
                    text = message.getString("text"),
                    privacy = MessagePrivacy.valueOf(message.optString("privacy", MessagePrivacy.RemoteEligible.name)),
                    kind = MessageKind.valueOf(message.optString("kind", MessageKind.Chat.name)),
                )
            },
            mustKeep = json.getJSONArray("must_keep").strings(),
        )
    }

    private fun readJsonl(name: String): List<JSONObject> =
        File(MemoryLab.datasetsDir, name).readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map(::JSONObject)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
}

/**
 * Compaction setup shared by the bench and the dataset check: a two-turn tail, a chars/2 token
 * estimate, and a budget whose compaction threshold is 75% of the case's size.
 */
internal object CompactionBench {
    val config = CompactionConfig(preserveRecentTurns = 2)

    val estimator: (List<ChatMessage>) -> Int = { messages -> messages.sumOf { it.text.length / 2 + 4 } }

    fun budgetFor(messages: List<ChatMessage>): Int =
        ceil(estimator(messages) * 0.75 / config.compactionThresholdRatio).toInt()

    /** Must-keep strings found in [text] under the grounding fold. */
    fun retained(mustKeep: List<String>, text: String): List<String> {
        val folded = text.groundingFold()
        return mustKeep.filter { item -> item.groundingFold() in folded }
    }
}

/**
 * A predicted fact matches a gold fact when, under the grounding fold, they are equal, the
 * prediction contains the gold fact, or the prediction is a part of the gold fact covering at least
 * half of it.
 */
internal fun factMatches(predicted: String, gold: String): Boolean {
    val p = predicted.groundingFold()
    val g = gold.groundingFold()
    return p == g || g in p || (p in g && p.length * 2 >= g.length)
}

internal class PrecisionRecall {
    private var predicted = 0
    private var predictedCorrect = 0
    private var gold = 0
    private var goldFound = 0

    fun add(predictions: List<String>, golds: List<String>) {
        predicted += predictions.size
        predictedCorrect += predictions.count { p -> golds.any { g -> factMatches(p, g) } }
        gold += golds.size
        goldFound += golds.count { g -> predictions.any { p -> factMatches(p, g) } }
    }

    fun toJson(): JSONObject =
        JSONObject()
            .put("precision", ratio(predictedCorrect, predicted))
            .put("recall", ratio(goldFound, gold))
            .put("predicted", predicted)
            .put("predictedCorrect", predictedCorrect)
            .put("gold", gold)
            .put("goldFound", goldFound)
}

internal fun ratio(numerator: Int, denominator: Int): Any =
    if (denominator == 0) JSONObject.NULL else Math.round(numerator * 1000.0 / denominator) / 1000.0

/** Nearest-rank percentile of [values] (0 when empty). */
internal fun percentile(values: List<Long>, fraction: Double): Long {
    if (values.isEmpty()) return 0
    val sorted = values.sorted()
    return sorted[(ceil(fraction * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
}

internal fun latencyJson(values: List<Long>): JSONObject =
    JSONObject()
        .put("n", values.size)
        .put("p50", percentile(values, 0.5))
        .put("p95", percentile(values, 0.95))
        .put("max", values.maxOrNull() ?: 0)

/** Writes [records] as JSONL, replacing the file. */
internal fun writeJsonl(file: File, records: List<JSONObject>) {
    file.bufferedWriter(Charsets.UTF_8).use { out ->
        records.forEach { record ->
            out.write(record.toString())
            out.newLine()
        }
    }
}
