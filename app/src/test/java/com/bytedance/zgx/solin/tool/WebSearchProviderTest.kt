package com.bytedance.zgx.solin.tool

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WebSearchProviderTest {
    @Test
    fun freshnessInferenceRecognizesCurrentLanguageHints() {
        listOf(
            "最新 AI 模型",
            "目前最强的手机芯片",
            "当前 OpenAI model",
            "现在热门应用",
            "中国和美国最火热的 AI 模型",
            "AI 模型排行",
            "手机芯片榜单",
            "latest AI model",
            "current Android release",
            "recent Kotlin changes",
            "trending AI tools",
            "hottest phones",
            "2026 AI model ranking",
        ).forEach { query ->
            assertEquals(
                query,
                WebSearchFreshness.Current,
                inferWebSearchFreshness(
                    query = query,
                    searchMode = WebSearchMode.General,
                    currentYear = 2026,
                ),
            )
        }
        assertEquals(
            WebSearchFreshness.AnyTime,
            inferWebSearchFreshness(
                query = "Kotlin coroutine dispatcher guide",
                searchMode = WebSearchMode.General,
                currentYear = 2026,
            ),
        )
    }

    @Test
    fun weatherSearchStripsChineseQuestionParticlesBeforeGeocoding() {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse(beijingGeocodingJson()))
            server.enqueue(jsonResponse(forecastJson(temperature = 12.0)))
            server.start()
            val provider = provider(server)

            val result = provider.search(
                WebSearchRequest(
                    query = "北京今天下雨了吗？",
                    searchMode = WebSearchMode.WeatherCurrent,
                ),
            )

            require(result is WebSearchReadResult.Available) { result.toString() }
            assertEquals("open_meteo", result.source)
            assertTrue(result.summaryText.contains("北京"))
            assertEquals(WebSearchMode.WeatherCurrent, result.searchMode)
            assertEquals(fixedInstant, result.retrievedAt)
            assertEquals(WebSearchFreshness.Current, result.freshness)
            val json = JSONObject(result.resultsJson)
            assertEquals(1, json.getInt("schemaVersion"))
            assertEquals("web_search_evidence", json.getString("kind"))
            assertEquals("北京今天下雨了吗？", json.getString("query"))
            assertEquals("weather_current", json.getString("searchMode"))
            assertEquals(fixedInstant.toString(), json.getString("retrievedAt"))
            assertEquals("current", json.getString("freshness"))
            assertTrue(json.getJSONArray("sources").length() >= 1)
            assertEquals("weather_current", json.getJSONArray("results").getJSONObject(0).getString("kind"))
            assertEquals("北京", server.takeRequest().url.queryParameter("name"))
            server.takeRequest()
        }
    }

    @Test
    fun generalWeatherWordingDoesNotRouteToOpenMeteo() {
        MockWebServer().use { server ->
            server.enqueue(
                htmlResponse(
                    """
                    <html>
                      <body>
                        <div class="result">
                          <h2><a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Fweather">北京天气</a></h2>
                          <a class="result__snippet">北京天气相关搜索摘要</a>
                        </div>
                      </body>
                    </html>
                    """.trimIndent(),
                ),
            )
            server.start()
            val provider = provider(server)

            val result = provider.search("北京今天下雨了吗？")

            require(result is WebSearchReadResult.Available)
            assertEquals("duckduckgo_html", result.source)
            assertEquals(WebSearchMode.General, result.searchMode)
            val json = JSONObject(result.resultsJson)
            assertEquals("general", json.getString("searchMode"))
            assertEquals("current", json.getString("freshness"))
            assertEquals("duckduckgo_html", json.getJSONArray("results").getJSONObject(0).getString("sourceId"))
            assertTrue(server.takeRequest().target.startsWith("/html?"))
        }
    }

    @Test
    fun generalSearchWithLatestKeywordInfersCurrentAndEnhancesDuckDuckGoPageQuery() {
        MockWebServer().use { server ->
            server.enqueue(
                htmlResponse(
                    """
                    <html>
                      <body>
                        <div class="result">
                          <h2><a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Flatest-ai">Latest AI model release</a></h2>
                          <a class="result__snippet">A current roundup of AI model releases.</a>
                        </div>
                      </body>
                    </html>
                    """.trimIndent(),
                ),
            )
            server.start()
            val provider = provider(server)

            val result = provider.search("OpenAI latest model release")

            require(result is WebSearchReadResult.Available)
            assertEquals("duckduckgo_html", result.source)
            assertEquals(WebSearchFreshness.Current, result.freshness)
            val json = JSONObject(result.resultsJson)
            assertEquals("OpenAI latest model release", json.getString("query"))
            assertEquals("OpenAI latest model release 2026", json.getString("submittedQuery"))
            assertEquals("current", json.getString("freshness"))
            val request = server.takeRequest()
            assertTrue(request.target.startsWith("/html?"))
            assertEquals("OpenAI latest model release 2026", request.url.queryParameter("q"))
        }
    }

    @Test
    fun generalSearchSubmitsOptimizedKeywordsInsteadOfNaturalLanguageQuestion() {
        MockWebServer().use { server ->
            server.enqueue(
                htmlResponse(
                    """
                    <html>
                      <body>
                        <div class="result">
                          <h2><a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Fhot-ai-models">Hot AI models</a></h2>
                          <a class="result__snippet">A current roundup of popular AI models in China and the US.</a>
                        </div>
                      </body>
                    </html>
                    """.trimIndent(),
                ),
            )
            server.start()
            val provider = provider(server)

            val originalQuery = "目前中国和美国最新最火热的AI模型分别是什么"
            val result = provider.search(originalQuery)

            require(result is WebSearchReadResult.Available)
            assertEquals(WebSearchFreshness.Current, result.freshness)
            val json = JSONObject(result.resultsJson)
            assertEquals(originalQuery, json.getString("query"))
            assertEquals("中国和美国最新最火热 AI 模型 2026", json.getString("submittedQuery"))
            val request = server.takeRequest()
            assertEquals("中国和美国最新最火热 AI 模型 2026", request.url.queryParameter("q"))
        }
    }

    @Test
    fun generalSearchWithCurrentYearKeepsSemanticDuckDuckGoPageQuery() {
        MockWebServer().use { server ->
            server.enqueue(
                htmlResponse(
                    """
                    <html>
                      <body>
                        <div class="result">
                          <h2><a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Fai-ranking">AI model ranking 2026</a></h2>
                          <a class="result__snippet">A fresh ranking of AI models.</a>
                        </div>
                      </body>
                    </html>
                    """.trimIndent(),
                ),
            )
            server.start()
            val provider = provider(server)

            val result = provider.search("2026 AI model ranking")

            require(result is WebSearchReadResult.Available)
            assertEquals(WebSearchFreshness.Current, result.freshness)
            val json = JSONObject(result.resultsJson)
            assertEquals("2026 AI model ranking", json.optString("submittedQuery", json.getString("query")))
            assertEquals("current", json.getString("freshness"))
            val request = server.takeRequest()
            assertTrue(request.target.startsWith("/html?"))
            assertEquals("2026 AI model ranking", request.url.queryParameter("q"))
        }
    }

    @Test
    fun generalSearchFallsBackToDuckDuckGoHtmlWhenInstantAnswerHasNoEvidence() {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse("""{}"""))
            server.enqueue(
                htmlResponse(
                    """
                    <html>
                      <body>
                        <div class="result">
                          <h2><a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Fcoroutines">Kotlin coroutine guide</a></h2>
                          <a class="result__snippet">A practical guide to coroutine dispatchers.</a>
                        </div>
                      </body>
                    </html>
                    """.trimIndent(),
                ),
            )
            server.start()
            val provider = provider(server)

            val result = provider.search("Kotlin coroutine dispatcher guide")

            require(result is WebSearchReadResult.Available)
            assertEquals("duckduckgo_html", result.source)
            assertTrue(result.summaryText.contains("Kotlin coroutine guide"))
            val json = JSONObject(result.resultsJson)
            val firstResult = json.getJSONArray("results").getJSONObject(0)
            assertEquals("web_result", firstResult.getString("kind"))
            assertEquals("duckduckgo_html", firstResult.getString("sourceId"))
            assertEquals("https://example.com/coroutines", firstResult.getString("url"))
            assertTrue(server.takeRequest().target.startsWith("/instant?"))
            assertTrue(server.takeRequest().target.startsWith("/html?"))
        }
    }

    @Test
    fun generalSearchWithoutInstantOrHtmlEvidenceFailsClosed() {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse("""{}"""))
            server.enqueue(htmlResponse("""<html><body>No results</body></html>"""))
            server.enqueue(htmlResponse("""<html><body>No lite results</body></html>"""))
            server.start()
            val provider = provider(server)

            val result = provider.search("Kotlin coroutine dispatcher guide")

            require(result is WebSearchReadResult.Failed)
            assertTrue(result.reason.contains("没有找到可直接引用的搜索证据"))
            assertTrue(server.takeRequest().target.startsWith("/instant?"))
            assertTrue(server.takeRequest().target.startsWith("/html?"))
            assertTrue(server.takeRequest().target.startsWith("/lite?"))
        }
    }

    @Test
    fun generalSearchFallsBackFromHtmlPageToLitePage() {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse("""{}"""))
            server.enqueue(htmlResponse("""<html><body>No html results</body></html>"""))
            server.enqueue(
                htmlResponse(
                    """
                    <html>
                      <body>
                        <table>
                          <tr>
                            <td>
                              <a class="result-link" href="/l/?uddg=https%3A%2F%2Fexample.com%2Flite-ai">Lite AI result</a>
                              <span class="result-snippet">Lite search result snippet.</span>
                            </td>
                          </tr>
                        </table>
                      </body>
                    </html>
                    """.trimIndent(),
                ),
            )
            server.start()
            val provider = provider(server)

            val result = provider.search("Kotlin coroutine dispatcher guide")

            require(result is WebSearchReadResult.Available)
            assertEquals("duckduckgo_lite", result.source)
            assertTrue(result.summaryText.contains("Lite search result snippet"))
            val firstResult = JSONObject(result.resultsJson).getJSONArray("results").getJSONObject(0)
            assertEquals("duckduckgo_lite", firstResult.getString("sourceId"))
            assertEquals("https://example.com/lite-ai", firstResult.getString("url"))
            assertTrue(server.takeRequest().target.startsWith("/instant?"))
            assertTrue(server.takeRequest().target.startsWith("/html?"))
            assertTrue(server.takeRequest().target.startsWith("/lite?"))
        }
    }

    @Test
    fun weatherSearchKeepsChineseLocationCharactersThatLookLikeConnectorsOrAdjectives() {
        listOf("和田", "大同", "高雄").forEach { locationName ->
            MockWebServer().use { server ->
                server.enqueue(jsonResponse(geocodingJson(locationName)))
                server.enqueue(jsonResponse(forecastJson(temperature = 12.0)))
                server.start()
                val provider = provider(server)

                val result = provider.search(
                    WebSearchRequest(
                        query = "${locationName}天气怎么样",
                        searchMode = WebSearchMode.WeatherCurrent,
                    ),
                )

                require(result is WebSearchReadResult.Available)
                assertTrue(result.summaryText.contains(locationName))
                assertEquals(locationName, server.takeRequest().url.queryParameter("name"))
                server.takeRequest()
            }
        }
    }

    @Test
    fun weatherSearchReturnsMultiLocationFactsWithoutAnsweringComparisonItself() {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse(beijingGeocodingJson()))
            server.enqueue(jsonResponse(forecastJson(temperature = 12.0)))
            server.enqueue(jsonResponse(shanghaiGeocodingJson()))
            server.enqueue(jsonResponse(forecastJson(temperature = 18.0)))
            server.start()
            val provider = provider(server)

            val result = provider.search(
                WebSearchRequest(
                    query = "北京和上海的温差是多少",
                    searchMode = WebSearchMode.WeatherCurrent,
                ),
            )

            require(result is WebSearchReadResult.Available)
            assertEquals("open_meteo", result.source)
            assertTrue(result.summaryText.contains("北京"))
            assertTrue(result.summaryText.contains("上海"))
            assertTrue(!result.summaryText.contains("温差"))
            val json = JSONObject(result.resultsJson)
            assertEquals(1, json.getInt("schemaVersion"))
            assertEquals("web_search_evidence", json.getString("kind"))
            assertEquals("weather_current", json.getString("searchMode"))
            assertEquals("current", json.getString("freshness"))
            val locations = json.getJSONArray("results")
            assertEquals(2, locations.length())
            assertEquals("北京", locations.getJSONObject(0).getString("requestedLocation"))
            assertEquals(12.0, locations.getJSONObject(0).getJSONObject("current").getDouble("temperature_2m"), 0.0)
            assertEquals("上海", locations.getJSONObject(1).getString("requestedLocation"))
            assertEquals(18.0, locations.getJSONObject(1).getJSONObject("current").getDouble("temperature_2m"), 0.0)
            assertEquals("北京", server.takeRequest().url.queryParameter("name"))
            server.takeRequest()
            assertEquals("上海", server.takeRequest().url.queryParameter("name"))
            server.takeRequest()
        }
    }

    @Test
    fun weatherComparisonStripsTrailingAdjectivesWithoutDamagingLocationNames() {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse(beijingGeocodingJson()))
            server.enqueue(jsonResponse(forecastJson(temperature = 12.0)))
            server.enqueue(jsonResponse(shanghaiGeocodingJson()))
            server.enqueue(jsonResponse(forecastJson(temperature = 18.0)))
            server.start()
            val provider = provider(server)

            val result = provider.search(
                WebSearchRequest(
                    query = "北京比上海气温高多少",
                    searchMode = WebSearchMode.WeatherCurrent,
                ),
            )

            require(result is WebSearchReadResult.Available)
            assertEquals("北京", server.takeRequest().url.queryParameter("name"))
            server.takeRequest()
            assertEquals("上海", server.takeRequest().url.queryParameter("name"))
            server.takeRequest()
        }
    }

    @Test
    fun hotSearchQueryDoesNotRouteToWeatherBySingleHotCharacter() {
        MockWebServer().use { server ->
            server.enqueue(
                htmlResponse(
                    """
                    <html>
                      <body>
                        <div class="result">
                          <h2><a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Fhot">今日热搜</a></h2>
                          <a class="result__snippet">今日热点摘要</a>
                        </div>
                      </body>
                    </html>
                    """.trimIndent(),
                ),
            )
            server.start()
            val provider = provider(server)

            val result = provider.search(
                WebSearchRequest(
                    query = "今天有什么热搜",
                    maxResults = 1,
                ),
            )

            require(result is WebSearchReadResult.Available)
            assertEquals("duckduckgo_html", result.source)
            assertEquals(1, result.maxResults)
            assertEquals(WebSearchFreshness.Current, result.freshness)
            assertEquals(1, JSONObject(result.resultsJson).getInt("maxResults"))
            assertTrue(server.takeRequest().target.startsWith("/html?"))
        }
    }

    private fun provider(server: MockWebServer): OkHttpWebSearchProvider =
        OkHttpWebSearchProvider(
            client = OkHttpClient(),
            geocodingBaseUrl = server.url("/geo").toString(),
            forecastBaseUrl = server.url("/forecast").toString(),
            instantAnswerBaseUrl = server.url("/instant").toString(),
            htmlSearchBaseUrl = server.url("/html").toString(),
            liteSearchBaseUrl = server.url("/lite").toString(),
            clock = Clock.fixed(fixedInstant, ZoneOffset.UTC),
        )

    private fun jsonResponse(body: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun htmlResponse(body: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "text/html; charset=utf-8")
            .body(body)
            .build()

    private fun beijingGeocodingJson(): String =
        """
        {
          "results": [
            {"name": "北京", "admin1": "北京", "country": "中国", "latitude": 39.9042, "longitude": 116.4074}
          ]
        }
        """.trimIndent()

    private fun shanghaiGeocodingJson(): String =
        """
        {
          "results": [
            {"name": "上海", "admin1": "上海", "country": "中国", "latitude": 31.2304, "longitude": 121.4737}
          ]
        }
        """.trimIndent()

    private fun geocodingJson(name: String): String =
        """
        {
          "results": [
            {"name": "$name", "admin1": "$name", "country": "中国", "latitude": 30.0, "longitude": 100.0}
          ]
        }
        """.trimIndent()

    private fun forecastJson(temperature: Double): String =
        """
        {
          "timezone": "Asia/Shanghai",
          "current_units": {
            "time": "iso8601",
            "temperature_2m": "℃",
            "apparent_temperature": "℃",
            "relative_humidity_2m": "%",
            "precipitation": "mm",
            "weather_code": "wmo code",
            "wind_speed_10m": "km/h"
          },
          "current": {
            "time": "2026-06-02T12:00",
            "temperature_2m": $temperature,
            "apparent_temperature": $temperature,
            "relative_humidity_2m": 40,
            "precipitation": 0.0,
            "weather_code": 0,
            "wind_speed_10m": 5.0
          }
        }
        """.trimIndent()

    private companion object {
        val fixedInstant: Instant = Instant.parse("2026-06-02T04:00:00Z")
    }
}
