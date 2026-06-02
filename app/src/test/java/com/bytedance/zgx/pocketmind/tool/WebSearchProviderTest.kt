package com.bytedance.zgx.pocketmind.tool

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchProviderTest {
    @Test
    fun weatherSearchStripsChineseQuestionParticlesBeforeGeocoding() {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse(beijingGeocodingJson()))
            server.enqueue(jsonResponse(forecastJson(temperature = 12.0)))
            server.start()
            val provider = provider(server)

            val result = provider.search("北京今天下雨了吗？")

            require(result is WebSearchReadResult.Available)
            assertEquals("open_meteo", result.source)
            assertTrue(result.summaryText.contains("北京"))
            assertEquals("北京", server.takeRequest().url.queryParameter("name"))
            server.takeRequest()
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

                val result = provider.search("${locationName}天气怎么样")

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

            val result = provider.search("北京和上海的温差是多少")

            require(result is WebSearchReadResult.Available)
            assertEquals("open_meteo", result.source)
            assertTrue(result.summaryText.contains("北京"))
            assertTrue(result.summaryText.contains("上海"))
            assertTrue(!result.summaryText.contains("温差"))
            val json = JSONObject(result.resultsJson)
            assertEquals("weather_current", json.getString("kind"))
            val locations = json.getJSONArray("locations")
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

            val result = provider.search("北京比上海气温高多少")

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
                jsonResponse(
                    """
                    {
                      "AbstractText": "今日热点摘要",
                      "AbstractURL": "https://example.com/hot",
                      "Heading": "热搜"
                    }
                    """.trimIndent(),
                ),
            )
            server.start()
            val provider = provider(server)

            val result = provider.search("今天有什么热搜")

            require(result is WebSearchReadResult.Available)
            assertEquals("duckduckgo", result.source)
            assertTrue(server.takeRequest().target.startsWith("/instant?"))
        }
    }

    private fun provider(server: MockWebServer): OkHttpWebSearchProvider =
        OkHttpWebSearchProvider(
            client = OkHttpClient(),
            geocodingBaseUrl = server.url("/geo").toString(),
            forecastBaseUrl = server.url("/forecast").toString(),
            instantAnswerBaseUrl = server.url("/instant").toString(),
        )

    private fun jsonResponse(body: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
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
}
