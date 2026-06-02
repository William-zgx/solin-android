package com.bytedance.zgx.pocketmind.tool

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val MAX_WEB_SEARCH_SUMMARY_CHARS = 1_200
private const val MAX_WEB_SEARCH_RESULTS_JSON_CHARS = 4_000

interface WebSearchProvider {
    fun search(query: String, searchMode: String? = null): WebSearchReadResult
}

sealed class WebSearchReadResult {
    data class Available(
        val query: String,
        val source: String,
        val summaryText: String,
        val resultsJson: String,
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
) : WebSearchProvider {
    override fun search(query: String, searchMode: String?): WebSearchReadResult {
        val cleanedQuery = query.trim()
        if (cleanedQuery.isBlank()) {
            return WebSearchReadResult.Failed("搜索词不能为空")
        }
        return if (cleanedQuery.looksLikeWeatherQuery()) {
            searchWeather(cleanedQuery)
        } else {
            searchInstantAnswer(cleanedQuery)
        }
    }

    private fun searchWeather(query: String): WebSearchReadResult {
        val locationQueries = query.weatherLocationQueries()
        if (locationQueries.isEmpty()) {
            return WebSearchReadResult.Failed("天气查询需要明确地点")
        }
        val snapshots = locationQueries.map { locationQuery ->
            readWeatherSnapshot(locationQuery).getOrElse { throwable ->
                return WebSearchReadResult.Failed(throwable.cleanReason())
            }
        }
        return if (snapshots.size > 1) {
            weatherEvidenceResult(query, snapshots.take(2))
        } else {
            singleWeatherResult(query, snapshots.single())
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

    private fun singleWeatherResult(query: String, snapshot: WeatherSnapshot): WebSearchReadResult {
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
        val results = JSONObject()
            .put("kind", "weather")
            .put("provider", "Open-Meteo")
            .put("query", query)
            .put("location", locationName)
            .put("latitude", snapshot.latitude)
            .put("longitude", snapshot.longitude)
            .put("timezone", snapshot.timezone)
            .put("current", current)
            .toString()
            .boundedText(MAX_WEB_SEARCH_RESULTS_JSON_CHARS)
        return WebSearchReadResult.Available(
            query = query,
            source = "open_meteo",
            summaryText = summary,
            resultsJson = results,
        )
    }

    private fun weatherEvidenceResult(query: String, snapshots: List<WeatherSnapshot>): WebSearchReadResult {
        val locationArray = JSONArray()
        snapshots.forEach { snapshot ->
            locationArray.put(snapshot.toJsonObject())
        }
        val summary = "已读取${snapshots.joinToString("、") { snapshot -> snapshot.shortName }}当前天气。"
            .boundedText(MAX_WEB_SEARCH_SUMMARY_CHARS)
        val results = JSONObject()
            .put("kind", "weather_current")
            .put("provider", "Open-Meteo")
            .put("query", query)
            .put("locations", locationArray)
        return WebSearchReadResult.Available(
            query = query,
            source = "open_meteo",
            summaryText = summary,
            resultsJson = results.toString().boundedText(MAX_WEB_SEARCH_RESULTS_JSON_CHARS),
        )
    }

    private fun searchInstantAnswer(query: String): WebSearchReadResult {
        val json = runCatching {
            JSONObject(
                get(
                    instantAnswerBaseUrl.toHttpUrl().newBuilder()
                        .addQueryParameter("q", query)
                        .addQueryParameter("format", "json")
                        .addQueryParameter("no_html", "1")
                        .addQueryParameter("skip_disambig", "1")
                        .build()
                        .toString(),
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
                        title = json.optString("Heading").ifBlank { query },
                        snippet = abstractText,
                        url = json.optString("AbstractURL"),
                    ),
                )
            }
            addAll(json.optJSONArray("RelatedTopics").relatedTopicItems())
        }.distinctBy { item -> item.snippet }.take(3)

        val summary = if (results.isEmpty()) {
            "没有找到可直接引用的搜索摘要。"
        } else {
            results.joinToString(separator = "\n") { item ->
                "${item.title.ifBlank { "搜索结果" }}：${item.snippet}"
            }
        }.boundedText(MAX_WEB_SEARCH_SUMMARY_CHARS)
        val resultArray = JSONArray()
        results.forEach { item ->
            resultArray.put(
                JSONObject()
                    .put("title", item.title)
                    .put("snippet", item.snippet.boundedText(500))
                    .put("url", item.url),
            )
        }
        val resultsJson = JSONObject()
            .put("kind", "instant_answer")
            .put("provider", "DuckDuckGo")
            .put("query", query)
            .put("results", resultArray)
            .toString()
            .boundedText(MAX_WEB_SEARCH_RESULTS_JSON_CHARS)
        return WebSearchReadResult.Available(
            query = query,
            source = "duckduckgo",
            summaryText = summary,
            resultsJson = resultsJson,
        )
    }

    private fun get(url: String): String {
        val response = client.newCall(Request.Builder().url(url).get().build()).execute()
        response.use { safeResponse ->
            if (!safeResponse.isSuccessful) {
                throw IOException("HTTP ${safeResponse.code}")
            }
            return safeResponse.body.string()
        }
    }
}

private data class WebSearchItem(
    val title: String,
    val snippet: String,
    val url: String,
)

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
            .put("requestedLocation", requestedLocation)
            .put("location", displayName)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("timezone", timezone)
            .put("currentUnits", units)
            .put("current", current)
}

private fun String.looksLikeWeatherQuery(): Boolean {
    val normalized = lowercase(Locale.US)
    return listOf("天气", "气温", "降雨", "下雨", "温度", "温差", "相差", "更高", "更低", "更热", "更冷")
        .any { it in this } ||
        Regex("""(比.+[冷热]|[冷热]多少|哪个.*[冷热]|哪一个.*[冷热]|谁.*[冷热])""").containsMatchIn(this) ||
        Regex(
            """\bweather\b|\btemperature\b|\brain\b|\bforecast\b|\bhotter\b|\bcolder\b|\bwarmer\b|\bcooler\b|\bdifference\b|\bcompare\b""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(normalized)
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
        .replace(Regex("""\s*(?:有|会|是|的)$"""), " ")
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

private fun String.boundedText(maxChars: Int): String =
    if (length <= maxChars) this else take(maxChars).trimEnd() + "..."

private fun Throwable.cleanReason(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
