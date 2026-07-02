package com.bytedance.zgx.solin.tool

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

private const val MAX_WEB_SEARCH_SUMMARY_CHARS = 1_200
private const val MAX_WEB_SEARCH_RESULTS_JSON_CHARS = 4_000
private const val WEB_SEARCH_EVIDENCE_SCHEMA_VERSION = 1
private const val DEFAULT_WEB_SEARCH_MAX_RESULTS = 3
private const val MAX_WEB_SEARCH_MAX_RESULTS = 5
private const val WEB_SEARCH_USER_AGENT = "Solin/0.1 Android WebSearch"
private val currentFreshnessTermRegex =
    Regex(
        """(?i)(最新|目前|当前|现在|近期|最近|今日|今天|今年|当下|热搜|热点|热门|火热|榜单|排行|排名|趋势|\blatest\b|\bcurrent\b|\bcurrently\b|\brecent\b|\btrending\b|\bhottest\b|\bpopular\b|\branking\b|\brankings\b|\bleaderboard\b|\btoday\b|\bnow\b)""",
    )
private val cjkAndAsciiBoundaryRegex =
    Regex("""(?<=[\u4E00-\u9FFF])(?=[A-Za-z0-9])|(?<=[A-Za-z0-9])(?=[\u4E00-\u9FFF])""")
private val searchPunctuationRegex = Regex("""[，。！？、；;:：,.?()（）\[\]{}"'“”]+""")
private val cjkSearchNoiseRegex =
    Regex("""(搜索一下|搜一下|查一下|看一下|帮我看看|帮我查查|帮我|帮忙|麻烦|请问|请|给我|告诉我|网页搜索|网络搜索|互联网搜索|上网搜索|网上搜索|上网查|网上查|百度一下|搜索|查询|查找|分别是什么|是什么|有哪些|怎么样|如何|多少|一下|分别|目前|当前|现在|当下|吗|呢|的)""")
private val englishSearchNoiseRegex =
    Regex("""(?i)\b(?:please|can\s+you|could\s+you|search\s+for|search|look\s+up|google|bing|tell\s+me|what\s+is|what\s+are|what's|which|who\s+is)\b""")

interface WebSearchProvider {
    fun search(request: WebSearchRequest): WebSearchReadResult

    fun search(query: String, searchMode: String? = null): WebSearchReadResult {
        val mode = WebSearchMode.fromSchemaValue(searchMode)
            ?: return WebSearchReadResult.Failed("不支持的搜索模式：$searchMode")
        return search(WebSearchRequest(query = query, searchMode = mode))
    }
}

data class WebSearchRequest(
    val query: String,
    val searchMode: WebSearchMode = WebSearchMode.General,
    val freshness: WebSearchFreshness = searchMode.defaultFreshness,
    val maxResults: Int = DEFAULT_WEB_SEARCH_MAX_RESULTS,
)

internal fun inferWebSearchFreshness(
    query: String,
    searchMode: WebSearchMode,
    currentYear: Int = LocalDate.now(ZoneOffset.UTC).year,
): WebSearchFreshness =
    when {
        searchMode == WebSearchMode.WeatherCurrent -> WebSearchFreshness.Current
        query.hasCurrentFreshnessIntent(currentYear) -> WebSearchFreshness.Current
        else -> searchMode.defaultFreshness
    }

internal fun fanOutWebSearchRequests(
    request: WebSearchRequest,
    currentYear: Int = LocalDate.now(ZoneOffset.UTC).year,
): List<WebSearchRequest> {
    val freshness = if (
        request.freshness == WebSearchFreshness.Current ||
        request.query.hasCurrentFreshnessIntent(currentYear) ||
        request.searchMode == WebSearchMode.WeatherCurrent
    ) {
        WebSearchFreshness.Current
    } else {
        request.freshness
    }
    if (request.searchMode == WebSearchMode.WeatherCurrent) {
        val locations = request.query.weatherLocationQueries()
        return locations
            .take(2)
            .map { location ->
                request.copy(
                    query = location,
                    searchMode = WebSearchMode.WeatherCurrent,
                    freshness = WebSearchFreshness.Current,
                    maxResults = 1,
                )
            }
            .ifEmpty { listOf(request.copy(freshness = freshness)) }
    }
    val subjects = request.query.compareSubjects()
    if (subjects.size < 2) return listOf(request.copy(freshness = freshness))
    val first = subjects[0]
    val second = subjects[1]
    return listOf(
        request.copy(query = first, freshness = freshness),
        request.copy(query = second, freshness = freshness),
        request.copy(query = "$first $second 对比 差异", freshness = freshness),
    ).distinctBy { fanOutRequest -> fanOutRequest.query.lowercase() }
}

enum class WebSearchMode(val schemaValue: String) {
    General("general"),
    WeatherCurrent("weather_current"),
    ;

    val defaultFreshness: WebSearchFreshness
        get() = when (this) {
            General -> WebSearchFreshness.AnyTime
            WeatherCurrent -> WebSearchFreshness.Current
        }

    companion object {
        fun fromSchemaValue(value: String?): WebSearchMode? =
            when (value?.trim()?.takeIf { it.isNotBlank() } ?: General.schemaValue) {
                General.schemaValue -> General
                WeatherCurrent.schemaValue -> WeatherCurrent
                else -> null
            }
    }
}

enum class WebSearchFreshness(val schemaValue: String) {
    AnyTime("any_time"),
    Current("current"),

    ;

    companion object {
        fun fromSchemaValue(value: String?): WebSearchFreshness? =
            when (value?.trim()?.takeIf { it.isNotBlank() }) {
                null -> null
                AnyTime.schemaValue -> AnyTime
                Current.schemaValue -> Current
                else -> null
            }
    }
}

sealed class WebSearchReadResult {
    data class Available(
        val query: String,
        val source: String,
        val summaryText: String,
        val resultsJson: String,
        val searchMode: WebSearchMode = WebSearchMode.General,
        val retrievedAt: Instant = Instant.now(),
        val freshness: WebSearchFreshness = searchMode.defaultFreshness,
        val maxResults: Int = DEFAULT_WEB_SEARCH_MAX_RESULTS,
    ) : WebSearchReadResult()

    data class Failed(
        val reason: String,
    ) : WebSearchReadResult()
}

class OkHttpWebSearchProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val geocodingBaseUrl: String = "https://geocoding-api.open-meteo.com/v1/search",
    private val forecastBaseUrl: String = "https://api.open-meteo.com/v1/forecast",
    private val instantAnswerBaseUrl: String = "https://api.duckduckgo.com/",
    private val htmlSearchBaseUrl: String = "https://html.duckduckgo.com/html/",
    private val liteSearchBaseUrl: String = "https://lite.duckduckgo.com/lite/",
    private val clock: Clock = Clock.systemUTC(),
) : WebSearchProvider {
    override fun search(request: WebSearchRequest): WebSearchReadResult {
        val cleanedQuery = request.query.trim()
        if (cleanedQuery.isBlank()) {
            return WebSearchReadResult.Failed("搜索词不能为空")
        }
        val normalizedRequest = request.copy(
            query = cleanedQuery,
            freshness = if (request.freshness == WebSearchFreshness.Current) {
                WebSearchFreshness.Current
            } else {
                inferWebSearchFreshness(
                    query = cleanedQuery,
                    searchMode = request.searchMode,
                    currentYear = Instant.now(clock).atZone(ZoneOffset.UTC).year,
                )
            },
            maxResults = request.maxResults.coerceIn(1, MAX_WEB_SEARCH_MAX_RESULTS),
        )
        val retrievedAt = Instant.now(clock)
        return when (normalizedRequest.searchMode) {
            WebSearchMode.General -> searchGeneral(normalizedRequest, retrievedAt)
            WebSearchMode.WeatherCurrent -> searchWeather(normalizedRequest, retrievedAt)
        }
    }

    private fun searchGeneral(request: WebSearchRequest, retrievedAt: Instant): WebSearchReadResult {
        if (request.freshness == WebSearchFreshness.Current) {
            val pageSearch = searchDuckDuckGoPages(request, retrievedAt)
            if (pageSearch is WebSearchReadResult.Available) {
                return pageSearch
            }
            val instantAnswer = searchInstantAnswer(request, retrievedAt)
            if (instantAnswer is WebSearchReadResult.Available) {
                return instantAnswer
            }
            val pageReason = (pageSearch as WebSearchReadResult.Failed).reason
            val instantReason = (instantAnswer as WebSearchReadResult.Failed).reason
            return WebSearchReadResult.Failed(
                if (pageReason.contains("没有找到") && instantReason.contains("没有找到")) {
                    "没有找到可直接引用的搜索证据"
                } else {
                    "$pageReason；备用即时摘要搜索失败：$instantReason"
                },
            )
        }
        val instantAnswer = searchInstantAnswer(request, retrievedAt)
        if (instantAnswer is WebSearchReadResult.Available) {
            return instantAnswer
        }
        val pageSearch = searchDuckDuckGoPages(request, retrievedAt)
        if (pageSearch is WebSearchReadResult.Available) {
            return pageSearch
        }
        val instantReason = (instantAnswer as WebSearchReadResult.Failed).reason
        val pageReason = (pageSearch as WebSearchReadResult.Failed).reason
        return WebSearchReadResult.Failed(
            if (instantReason.contains("没有找到") && pageReason.contains("没有找到")) {
                "没有找到可直接引用的搜索证据"
            } else {
                "$instantReason；备用网页搜索失败：$pageReason"
            },
        )
    }

    private fun searchWeather(request: WebSearchRequest, retrievedAt: Instant): WebSearchReadResult {
        val locationQueries = request.query.weatherLocationQueries()
        if (locationQueries.isEmpty()) {
            return WebSearchReadResult.Failed("天气查询需要明确地点")
        }
        val snapshots = locationQueries.take(request.maxResults.coerceAtMost(2)).map { locationQuery ->
            readWeatherSnapshot(locationQuery).getOrElse { throwable ->
                return WebSearchReadResult.Failed(throwable.cleanReason())
            }
        }
        return if (snapshots.size > 1) {
            weatherEvidenceResult(request, retrievedAt, snapshots)
        } else {
            singleWeatherResult(request, retrievedAt, snapshots.single())
        }
    }

    private fun readWeatherSnapshot(locationQuery: String): Result<WeatherSnapshot> = runCatching {
        val geocodingJson = runCatching {
            JSONObject(
                get(
                    geocodingBaseUrl.toHttpUrl().newBuilder()
                        .addQueryParameter("name", locationQuery)
                        .addQueryParameter("count", "1")
                        .addQueryParameter("language", "zh")
                        .addQueryParameter("format", "json")
                        .build()
                        .toString(),
                ),
            )
        }.getOrElse { throwable ->
            throw IOException("地点解析失败：${throwable.cleanReason()}")
        }
        val location = geocodingJson.optJSONArray("results")?.optJSONObject(0)
            ?: throw IOException("没有找到地点：$locationQuery")
        val latitude = location.optDouble("latitude", Double.NaN)
        val longitude = location.optDouble("longitude", Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) {
            throw IOException("地点缺少经纬度：$locationQuery")
        }

        val forecastJson = runCatching {
            JSONObject(
                get(
                    forecastBaseUrl.toHttpUrl().newBuilder()
                        .addQueryParameter("latitude", latitude.toString())
                        .addQueryParameter("longitude", longitude.toString())
                        .addQueryParameter(
                            "current",
                            "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m",
                        )
                        .addQueryParameter("timezone", "auto")
                        .build()
                        .toString(),
                ),
            )
        }.getOrElse { throwable ->
            throw IOException("天气查询失败：${throwable.cleanReason()}")
        }
        val current = forecastJson.optJSONObject("current")
            ?: throw IOException("天气接口没有返回当前天气")
        val units = forecastJson.optJSONObject("current_units") ?: JSONObject()
        val locationName = listOfNotNull(
            location.optString("name").takeIf { it.isNotBlank() },
            location.optString("admin1").takeIf { it.isNotBlank() },
            location.optString("country").takeIf { it.isNotBlank() },
        ).distinct().joinToString(" · ")
        WeatherSnapshot(
            requestedLocation = locationQuery,
            displayName = locationName.ifBlank { locationQuery },
            latitude = latitude,
            longitude = longitude,
            timezone = forecastJson.optString("timezone"),
            current = current,
            units = units,
        )
    }

    private fun singleWeatherResult(
        request: WebSearchRequest,
        retrievedAt: Instant,
        snapshot: WeatherSnapshot,
    ): WebSearchReadResult {
        val current = snapshot.current
        val units = snapshot.units
        val locationName = snapshot.displayName
        val weatherCode = current.optInt("weather_code", -1)
        val temperature = current.optString("temperature_2m")
        val apparentTemperature = current.optString("apparent_temperature")
        val humidity = current.optString("relative_humidity_2m")
        val windSpeed = current.optString("wind_speed_10m")
        val tempUnit = units.optString("temperature_2m", "℃")
        val windUnit = units.optString("wind_speed_10m", "km/h")
        val summary = buildString {
            append(locationName.ifBlank { snapshot.requestedLocation })
            append(" 当前天气：")
            append(weatherCode.weatherDescription())
            append("，气温 ")
            append(temperature)
            append(tempUnit)
            append("，体感 ")
            append(apparentTemperature)
            append(tempUnit)
            append("，湿度 ")
            append(humidity)
            append("%，风速 ")
            append(windSpeed)
            append(windUnit)
            append("。")
        }.boundedText(MAX_WEB_SEARCH_SUMMARY_CHARS)
        return WebSearchReadResult.Available(
            query = request.query,
            source = "open_meteo",
            summaryText = summary,
            resultsJson = request.evidenceJson(
                retrievedAt = retrievedAt,
                sources = openMeteoSources(),
                results = JSONArray().put(snapshot.toJsonObject()),
            ),
            searchMode = request.searchMode,
            retrievedAt = retrievedAt,
            freshness = request.freshness,
            maxResults = request.maxResults,
        )
    }

    private fun weatherEvidenceResult(
        request: WebSearchRequest,
        retrievedAt: Instant,
        snapshots: List<WeatherSnapshot>,
    ): WebSearchReadResult {
        val locationArray = JSONArray()
        snapshots.forEach { snapshot ->
            locationArray.put(snapshot.toJsonObject())
        }
        val summary = "已读取${snapshots.joinToString("、") { snapshot -> snapshot.shortName }}当前天气。"
            .boundedText(MAX_WEB_SEARCH_SUMMARY_CHARS)
        return WebSearchReadResult.Available(
            query = request.query,
            source = "open_meteo",
            summaryText = summary,
            resultsJson = request.evidenceJson(
                retrievedAt = retrievedAt,
                sources = openMeteoSources(),
                results = locationArray,
            ),
            searchMode = request.searchMode,
            retrievedAt = retrievedAt,
            freshness = request.freshness,
            maxResults = request.maxResults,
        )
    }

    private fun searchInstantAnswer(request: WebSearchRequest, retrievedAt: Instant): WebSearchReadResult {
        val submittedQuery = request.duckDuckGoSubmittedQuery(retrievedAt)
        val json = runCatching {
            JSONObject(
                get(
                    instantAnswerBaseUrl.toHttpUrl().newBuilder()
                        .addQueryParameter("q", submittedQuery)
                        .addQueryParameter("format", "json")
                        .addQueryParameter("no_html", "1")
                        .addQueryParameter("skip_disambig", "1")
                        .build()
                        .toString(),
                    accept = "application/json",
                ),
            )
        }.getOrElse { throwable ->
            return WebSearchReadResult.Failed("搜索失败：${throwable.cleanReason()}")
        }
        val results = buildList {
            val abstractText = json.optString("AbstractText").trim()
            if (abstractText.isNotBlank()) {
                add(
                    WebSearchItem(
                        title = json.optString("Heading").ifBlank { request.query },
                        snippet = abstractText,
                        url = json.optString("AbstractURL"),
                    ),
                )
            }
            addAll(json.optJSONArray("RelatedTopics").relatedTopicItems())
        }.distinctBy { item -> item.snippet }.take(request.maxResults)

        if (results.isEmpty()) {
            return WebSearchReadResult.Failed("没有找到可直接引用的搜索证据")
        }

        val summary = results.joinToString(separator = "\n") { item ->
            "${item.title.ifBlank { "搜索结果" }}：${item.snippet}"
        }.boundedText(MAX_WEB_SEARCH_SUMMARY_CHARS)
        val resultArray = JSONArray()
        results.forEach { item ->
            resultArray.put(item.toJsonObject())
        }
        return WebSearchReadResult.Available(
            query = request.query,
            source = "duckduckgo",
            summaryText = summary,
            resultsJson = request.evidenceJson(
                retrievedAt = retrievedAt,
                sources = duckDuckGoSources(),
                results = resultArray,
                submittedQuery = submittedQuery,
            ),
            searchMode = request.searchMode,
            retrievedAt = retrievedAt,
            freshness = request.freshness,
            maxResults = request.maxResults,
        )
    }

    private fun searchDuckDuckGoPages(request: WebSearchRequest, retrievedAt: Instant): WebSearchReadResult {
        val submittedQuery = request.duckDuckGoSubmittedQuery(retrievedAt)
        val failures = mutableListOf<String>()
        duckDuckGoPageEndpoints().forEach { endpoint ->
            val html = runCatching {
                get(
                    endpoint.baseUrl.toHttpUrl().newBuilder()
                        .addQueryParameter("q", submittedQuery)
                        .build()
                        .toString(),
                    accept = "text/html,application/xhtml+xml",
                )
            }.getOrElse { throwable ->
                failures += "${endpoint.sourceId}: ${throwable.cleanReason()}"
                return@forEach
            }
            val results = html.duckDuckGoHtmlItems(
                maxResults = request.maxResults,
                baseUri = endpoint.baseUri,
                sourceId = endpoint.sourceId,
            )
            if (results.isEmpty()) {
                failures += "${endpoint.sourceId}: no evidence"
                return@forEach
            }
            return duckDuckGoPageResult(
                request = request,
                retrievedAt = retrievedAt,
                endpoint = endpoint,
                results = results,
                submittedQuery = submittedQuery,
            )
        }
        return if (failures.all { it.endsWith(": no evidence") }) {
            WebSearchReadResult.Failed("没有找到可直接引用的搜索证据")
        } else {
            WebSearchReadResult.Failed(failures.joinToString("；"))
        }
    }

    private fun duckDuckGoPageEndpoints(): List<DuckDuckGoPageEndpoint> =
        listOf(
            DuckDuckGoPageEndpoint(
                sourceId = "duckduckgo_html",
                name = "DuckDuckGo HTML Search",
                baseUrl = htmlSearchBaseUrl,
                baseUri = "https://html.duckduckgo.com/",
            ),
            DuckDuckGoPageEndpoint(
                sourceId = "duckduckgo_lite",
                name = "DuckDuckGo Lite Search",
                baseUrl = liteSearchBaseUrl,
                baseUri = "https://lite.duckduckgo.com/",
            ),
        )

    private fun duckDuckGoPageResult(
        request: WebSearchRequest,
        retrievedAt: Instant,
        endpoint: DuckDuckGoPageEndpoint,
        results: List<WebSearchItem>,
        submittedQuery: String,
    ): WebSearchReadResult {
        val summary = results.joinToString(separator = "\n") { item ->
            if (item.snippet.isBlank()) {
                "${item.title.ifBlank { "搜索结果" }}：${item.url}"
            } else {
                "${item.title.ifBlank { "搜索结果" }}：${item.snippet}"
            }
        }.boundedText(MAX_WEB_SEARCH_SUMMARY_CHARS)
        val resultArray = JSONArray()
        results.forEach { item ->
            resultArray.put(item.toJsonObject())
        }
        return WebSearchReadResult.Available(
            query = request.query,
            source = endpoint.sourceId,
            summaryText = summary,
            resultsJson = request.evidenceJson(
                retrievedAt = retrievedAt,
                sources = duckDuckGoPageSources(endpoint),
                results = resultArray,
                submittedQuery = submittedQuery,
            ),
            searchMode = request.searchMode,
            retrievedAt = retrievedAt,
            freshness = request.freshness,
            maxResults = request.maxResults,
        )
    }

    private fun get(url: String, accept: String? = null): String {
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", WEB_SEARCH_USER_AGENT)
        accept?.let { requestBuilder.header("Accept", it) }
        val response = client.newCall(requestBuilder.build()).execute()
        response.use { safeResponse ->
            if (!safeResponse.isSuccessful) {
                throw IOException("HTTP ${safeResponse.code}")
            }
            return safeResponse.body.string()
        }
    }
}

private data class DuckDuckGoPageEndpoint(
    val sourceId: String,
    val name: String,
    val baseUrl: String,
    val baseUri: String,
)

private data class WebSearchItem(
    val title: String,
    val snippet: String,
    val url: String,
    val kind: String = "instant_answer",
    val sourceId: String = "duckduckgo",
) {
    fun toJsonObject(): JSONObject =
        JSONObject()
            .put("kind", kind)
            .put("sourceId", sourceId)
            .put("title", title)
            .put("snippet", snippet.boundedText(500))
            .put("url", url)
}

private data class WeatherSnapshot(
    val requestedLocation: String,
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val current: JSONObject,
    val units: JSONObject,
) {
    val shortName: String = displayName.substringBefore(" · ").ifBlank { requestedLocation }

    fun toJsonObject(): JSONObject =
        JSONObject()
            .put("kind", WebSearchMode.WeatherCurrent.schemaValue)
            .put("sourceId", "open_meteo")
            .put("requestedLocation", requestedLocation)
            .put("location", displayName)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("timezone", timezone)
            .put("currentUnits", units)
            .put("current", current)
}

private fun String.weatherLocationQueries(): List<String> {
    val normalized = trim()
        .replace(Regex("""(?i)\b(?:what(?:'s| is)?|how(?:'s| is)?|tell me|check|look up)\b"""), " ")
        .replace(Regex("""(?i)\b(?:weather|temperature|rain|forecast)\b"""), " ")
        .replace(Regex("""(?i)\b(?:in|for|today|now|currently)\b"""), " ")
        .replace(Regex("""(?i)\b(?:and|vs|versus|between|compare|difference)\b"""), "|")
        .replace(
            Regex(
                """(比较|对比|相差|气温差|温度差|温差|差多少|差了多少|哪个更热|哪个更冷|哪一个更热|哪一个更冷|谁更热|谁更冷)""",
            ),
            "|",
        )
        .replace(Regex("""(?<=.)比(?=.)"""), "|")
        .replace(Regex("""[、,，/;；]"""), "|")
        .replace(Regex("""(?<=.)[和与跟及](?=.)"""), "|")
        .replace(Regex("""[，。！？、:：?]+"""), " ")
        .replace(Regex("""\s*\|\s*"""), "|")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return normalized.split('|')
        .map { location -> location.cleanWeatherLocationCandidate() }
        .filter { location -> location.isNotBlank() }
        .distinct()
        .take(2)
}

private fun String.cleanWeatherLocationCandidate(): String =
    trim()
        .replace(Regex("""(?i)\b(?:weather|temperature|rain|forecast|today|now|currently)\b"""), " ")
        .replace(
            Regex(
                """(天气|气温|温度|温差|降雨|下雨|预报|怎么样|如何|多少|现在|今天|今晚|明天|查询|查一下|搜一下|帮我|请|一下|有没有|会不会|没有|没|吗|嘛|么|了|呢|呀|啊|？|\?)""",
            ),
            " ",
        )
        .replace(Regex("""^\s*(?:有|会|是|的)\s*"""), " ")
        .replace(Regex("""\s*(?:有|会|是|的)\s*$"""), " ")
        .replace(Regex("""\s*(?:更高|更低|更热|更冷|高|低|冷|热)\s*$"""), " ")
        .replace(Regex("""[，。！？、:：?]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun Int.weatherDescription(): String =
    when (this) {
        0 -> "晴"
        1, 2 -> "少云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61, 63, 65 -> "雨"
        66, 67 -> "冻雨"
        71, 73, 75 -> "雪"
        77 -> "雪粒"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95 -> "雷暴"
        96, 99 -> "雷暴伴冰雹"
        else -> "未知天气代码 $this"
    }

private fun JSONArray?.relatedTopicItems(): List<WebSearchItem> {
    if (this == null) return emptyList()
    val items = mutableListOf<WebSearchItem>()
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        val text = item.optString("Text").trim()
        if (text.isNotBlank()) {
            items += WebSearchItem(
                title = item.optString("FirstURL").substringAfterLast('/').replace('_', ' '),
                snippet = text,
                url = item.optString("FirstURL"),
            )
        }
        items += item.optJSONArray("Topics").relatedTopicItems()
    }
    return items
}

private fun String.duckDuckGoHtmlItems(
    maxResults: Int,
    baseUri: String,
    sourceId: String,
): List<WebSearchItem> {
    val document = Jsoup.parse(this, baseUri)
    val resultBlocks = document.select("div.result, div.web-result, article.result")
    val blockItems = resultBlocks.mapNotNull { block ->
        val anchor = block.selectFirst("a.result__a, a.result-link, h2 a, a[href]")
            ?: return@mapNotNull null
        anchor.toDuckDuckGoWebSearchItem(
            snippetContainer = block,
            sourceId = sourceId,
        )
    }.distinctBy { item -> item.url.ifBlank { item.title } }
        .take(maxResults)
    if (blockItems.isNotEmpty()) return blockItems
    return document.select("a.result__a, a.result-link")
        .mapNotNull { anchor ->
            anchor.toDuckDuckGoWebSearchItem(
                snippetContainer = anchor.parents().firstOrNull { parent ->
                    parent.selectFirst(".result__snippet, .result-snippet, .snippet") != null
                },
                sourceId = sourceId,
            )
        }
        .distinctBy { item -> item.url.ifBlank { item.title } }
        .take(maxResults)
}

private fun org.jsoup.nodes.Element.toDuckDuckGoWebSearchItem(
    snippetContainer: org.jsoup.nodes.Element?,
    sourceId: String,
): WebSearchItem? {
    val title = text().trim()
    val url = attr("href").decodeDuckDuckGoResultUrl()
    val snippet = snippetContainer?.selectFirst(
        "a.result__snippet, .result__snippet, .result-snippet, .result__body, .snippet",
    )?.text()?.trim().orEmpty()
    return if (title.isBlank() || url.isBlank()) {
        null
    } else {
        WebSearchItem(
            title = title,
            snippet = snippet,
            url = url,
            kind = "web_result",
            sourceId = sourceId,
        )
    }
}

private fun String.decodeDuckDuckGoResultUrl(): String {
    val raw = trim()
    if (raw.isBlank()) return ""
    val absolute = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "https://duckduckgo.com$raw"
        else -> raw
    }
    val parsed = absolute.toHttpUrlOrNull()
    val decoded = parsed?.queryParameter("uddg")?.takeIf { it.isNotBlank() }
    if (decoded != null) return decoded
    val encoded = Regex("""[?&]uddg=([^&]+)""").find(absolute)?.groupValues?.getOrNull(1)
    if (!encoded.isNullOrBlank()) {
        return runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }
            .getOrDefault(absolute)
    }
    return absolute
}

private fun WebSearchRequest.duckDuckGoSubmittedQuery(retrievedAt: Instant): String {
    val searchQuery = query.optimizedWebSearchKeywords()
    if (freshness != WebSearchFreshness.Current) return searchQuery
    val currentYear = retrievedAt.atZone(ZoneOffset.UTC).year
    val hasCurrentYear = searchQuery.containsWholeYear(currentYear) || query.containsWholeYear(currentYear)
    val additions = mutableListOf<String>()
    if (!hasCurrentYear) {
        additions += currentYear.toString()
    }
    if (!hasCurrentYear &&
        !currentFreshnessTermRegex.containsMatchIn(searchQuery) &&
        !currentFreshnessTermRegex.containsMatchIn(query)
    ) {
        additions += if (searchQuery.containsCjk() || query.containsCjk()) "最新" else "latest"
    }
    if (additions.isEmpty()) return searchQuery
    return (listOf(searchQuery) + additions).joinToString(" ")
}

private fun String.hasCurrentFreshnessIntent(currentYear: Int): Boolean =
    currentFreshnessTermRegex.containsMatchIn(this) ||
        containsWholeYear(currentYear)

private fun String.optimizedWebSearchKeywords(): String {
    val optimized = trim()
        .replace(cjkAndAsciiBoundaryRegex, " ")
        .replace(searchPunctuationRegex, " ")
        .replace(englishSearchNoiseRegex, " ")
        .replace(cjkSearchNoiseRegex, " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return optimized.ifBlank { trim() }
}

private fun String.compareSubjects(): List<String> {
    val normalized = trim()
        .replace(Regex("""(?i)\b(?:compare|comparison|difference(?:s)?\s+between|versus|vs\.?)\b"""), "|")
        .replace(Regex("""(?i)\b(?:and|with)\b"""), "|")
        .replace(Regex("""(比较|对比|区别|差异|差别|相差|不同|哪个更好|哪一个更好|谁更好)"""), "|")
        .replace(Regex("""(?<=.)比(?=.)"""), "|")
        .replace(Regex("""[、,，/;；]"""), "|")
        .replace(Regex("""(?<=.)[和与跟及](?=.)"""), "|")
        .replace(searchPunctuationRegex, " ")
        .replace(Regex("""\s*\|\s*"""), "|")
        .replace(Regex("""\s+"""), " ")
        .trim('|', ' ')
    return normalized.split('|')
        .map { candidate -> candidate.optimizedWebSearchKeywords() }
        .filter { candidate -> candidate.length >= 2 }
        .distinct()
        .take(2)
}

private fun String.containsWholeYear(year: Int): Boolean =
    Regex("""(?<!\d)$year(?!\d)""").containsMatchIn(this)

private fun String.containsCjk(): Boolean =
    any { character -> character in '\u4E00'..'\u9FFF' }

private fun WebSearchRequest.evidenceJson(
    retrievedAt: Instant,
    sources: JSONArray,
    results: JSONArray,
    submittedQuery: String = query,
): String {
    return JSONObject()
        .put("schemaVersion", WEB_SEARCH_EVIDENCE_SCHEMA_VERSION)
        .put("kind", "web_search_evidence")
        .put("query", query)
        .put("submittedQuery", submittedQuery)
        .put("searchMode", searchMode.schemaValue)
        .put("retrievedAt", retrievedAt.toString())
        .put("freshness", freshness.schemaValue)
        .put("maxResults", maxResults)
        .put("sources", sources)
        .put("results", results)
        .toString()
        .boundedText(MAX_WEB_SEARCH_RESULTS_JSON_CHARS)
}

private fun openMeteoSources(): JSONArray =
    JSONArray()
        .put(
            JSONObject()
                .put("id", "open_meteo_geocoding")
                .put("name", "Open-Meteo Geocoding API")
                .put("url", "https://geocoding-api.open-meteo.com/v1/search"),
        )
        .put(
            JSONObject()
                .put("id", "open_meteo")
                .put("name", "Open-Meteo Forecast API")
                .put("url", "https://api.open-meteo.com/v1/forecast"),
        )

private fun duckDuckGoSources(): JSONArray =
    JSONArray()
        .put(
            JSONObject()
                .put("id", "duckduckgo")
                .put("name", "DuckDuckGo Instant Answer API")
                .put("url", "https://api.duckduckgo.com/"),
        )

private fun duckDuckGoPageSources(endpoint: DuckDuckGoPageEndpoint): JSONArray =
    JSONArray()
        .put(
            JSONObject()
                .put("id", endpoint.sourceId)
                .put("name", endpoint.name)
                .put("url", endpoint.baseUrl),
        )

private fun String.boundedText(maxChars: Int): String =
    if (length <= maxChars) this else take(maxChars).trimEnd() + "..."

private fun Throwable.cleanReason(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
