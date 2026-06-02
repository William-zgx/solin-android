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
        val locationQuery = query.weatherLocationQuery()
        if (locationQuery.isBlank()) {
            return WebSearchReadResult.Failed("天气查询需要明确地点")
        }
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
            return WebSearchReadResult.Failed("地点解析失败：${throwable.cleanReason()}")
        }
        val location = geocodingJson.optJSONArray("results")?.optJSONObject(0)
            ?: return WebSearchReadResult.Failed("没有找到地点：$locationQuery")
        val latitude = location.optDouble("latitude", Double.NaN)
        val longitude = location.optDouble("longitude", Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) {
            return WebSearchReadResult.Failed("地点缺少经纬度：$locationQuery")
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
            return WebSearchReadResult.Failed("天气查询失败：${throwable.cleanReason()}")
        }
        val current = forecastJson.optJSONObject("current")
            ?: return WebSearchReadResult.Failed("天气接口没有返回当前天气")
        val units = forecastJson.optJSONObject("current_units") ?: JSONObject()
        val locationName = listOfNotNull(
            location.optString("name").takeIf { it.isNotBlank() },
            location.optString("admin1").takeIf { it.isNotBlank() },
            location.optString("country").takeIf { it.isNotBlank() },
        ).distinct().joinToString(" · ")
        val weatherCode = current.optInt("weather_code", -1)
        val temperature = current.optString("temperature_2m")
        val apparentTemperature = current.optString("apparent_temperature")
        val humidity = current.optString("relative_humidity_2m")
        val windSpeed = current.optString("wind_speed_10m")
        val tempUnit = units.optString("temperature_2m", "℃")
        val windUnit = units.optString("wind_speed_10m", "km/h")
        val summary = buildString {
            append(locationName.ifBlank { locationQuery })
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
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("timezone", forecastJson.optString("timezone"))
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

private fun String.looksLikeWeatherQuery(): Boolean {
    val normalized = lowercase(Locale.US)
    return listOf("天气", "气温", "降雨", "下雨", "温度").any { it in this } ||
        Regex("""\bweather\b|\btemperature\b|\brain\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
}

private fun String.weatherLocationQuery(): String =
    trim()
        .replace(Regex("""(?i)\b(?:what(?:'s| is)?|how(?:'s| is)?|tell me|check|look up)\b"""), " ")
        .replace(Regex("""(?i)\b(?:weather|temperature|rain|forecast)\b"""), " ")
        .replace(Regex("""(?i)\b(?:in|for|today|now|currently)\b"""), " ")
        .replace(Regex("""(天气|气温|温度|降雨|下雨|预报|怎么样|如何|多少|现在|今天|查询|查一下|搜一下|帮我|请|一下)"""), " ")
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
