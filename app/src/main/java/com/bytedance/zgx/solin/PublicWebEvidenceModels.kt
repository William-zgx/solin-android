package com.bytedance.zgx.solin

import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

private const val PUBLIC_WEB_EVIDENCE_KIND = "web_search_evidence"
private const val PUBLIC_WEB_EVIDENCE_MAX_ITEMS = 8

data class PublicWebEvidencePack(
    val query: String,
    val retrievedAt: String,
    val freshness: String,
    val items: List<PublicWebEvidenceItem>,
    val quality: String,
)

data class PublicWebEvidenceItem(
    val sourceId: String,
    val title: String,
    val url: String,
    val snippet: String,
    val sourceName: String,
    val qualityLabel: String,
)

fun publicWebEvidencePackFromToolResultData(
    data: Map<String, String>,
): PublicWebEvidencePack? {
    val rawJson = data["resultsJson"]?.takeIf { value -> value.isNotBlank() } ?: return null
    val json = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
    if (json.optString("kind") != PUBLIC_WEB_EVIDENCE_KIND) return null

    val retrievedAt = json.optString("retrievedAt").ifBlank { data["retrievedAt"].orEmpty() }
    val freshness = json.optString("freshness").ifBlank { data["freshness"].orEmpty() }
    val sourceNamesById = json.optJSONArray("sources").sourceNamesById()
    val sourceUrlsById = json.optJSONArray("sources").sourceUrlsById()
    val summaryText = data["summaryText"].orEmpty()
    val items = json.optJSONArray("results")
        .objects()
        .mapIndexedNotNull { index, result ->
            result.toPublicWebEvidenceItem(
                sourceIndex = index + 1,
                sourceNamesById = sourceNamesById,
                sourceUrlsById = sourceUrlsById,
                retrievedAt = retrievedAt,
                freshness = freshness,
            )
        }
        .deduplicatedPublicWebEvidenceItems()
        .ifEmpty {
            sourceNamesById.entries.firstOrNull()?.let { (sourceId, sourceName) ->
                listOf(
                    PublicWebEvidenceItem(
                        sourceId = "S1",
                        title = sourceName.ifBlank { "公开来源" },
                        url = sourceUrlsById[sourceId].orEmpty(),
                        snippet = summaryText,
                        sourceName = sourceName,
                        qualityLabel = publicWebEvidenceQualityLabel(
                            url = sourceUrlsById[sourceId].orEmpty(),
                            title = sourceName,
                            snippet = summaryText,
                            retrievedAt = retrievedAt,
                            freshness = freshness,
                        ),
                    ),
                )
            }.orEmpty()
        }
        .take(PUBLIC_WEB_EVIDENCE_MAX_ITEMS)

    if (items.isEmpty()) return null
    return PublicWebEvidencePack(
        query = json.optString("query").ifBlank { data["query"].orEmpty() },
        retrievedAt = retrievedAt,
        freshness = freshness,
        items = items,
        quality = items.packQualityLabel(),
    )
}

fun publicWebEvidencePacksFromToolResults(
    results: List<ToolResult>,
): List<PublicWebEvidencePack> =
    results
        .filter { result -> result.status == ToolStatus.Succeeded }
        .mapNotNull { result -> publicWebEvidencePackFromToolResultData(result.data) }

private fun JSONObject.toPublicWebEvidenceItem(
    sourceIndex: Int,
    sourceNamesById: Map<String, String>,
    sourceUrlsById: Map<String, String>,
    retrievedAt: String,
    freshness: String,
): PublicWebEvidenceItem? {
    val resultSourceId = optString("sourceId").trim()
    val sourceName = firstNonBlank(
        sourceNamesById[resultSourceId].orEmpty(),
        optString("source").trim(),
        resultSourceId,
    ).orEmpty()
    val url = optString("url").trim().ifBlank {
        sourceUrlsById[resultSourceId].orEmpty()
    }
    val title = firstNonBlank(
        optString("title").trim(),
        optString("location").trim(),
        optString("requestedLocation").trim(),
        sourceName,
        url.publicWebDomain().orEmpty(),
    ) ?: return null
    val snippet = firstNonBlank(
        optString("snippet").trim(),
        optJSONObject("current")?.toString()?.trim().orEmpty(),
    ).orEmpty()
    val quality = publicWebEvidenceQualityLabel(
        url = url,
        title = title,
        snippet = snippet,
        retrievedAt = retrievedAt,
        freshness = freshness,
    )
    return PublicWebEvidenceItem(
        sourceId = "S$sourceIndex",
        title = title,
        url = url,
        snippet = snippet,
        sourceName = sourceName.ifBlank { url.publicWebDomain().orEmpty() },
        qualityLabel = quality,
    )
}

private fun publicWebEvidenceQualityLabel(
    url: String,
    title: String,
    snippet: String,
    retrievedAt: String,
    freshness: String,
): String =
    when {
        url.isNotBlank() && title.isNotBlank() && snippet.isNotBlank() &&
            retrievedAt.isNotBlank() && freshness.isNotBlank() -> "High"

        url.isNotBlank() -> "Medium"
        else -> "Low"
    }

private fun List<PublicWebEvidenceItem>.packQualityLabel(): String =
    when {
        any { item -> item.qualityLabel == "High" } -> "High"
        any { item -> item.qualityLabel == "Medium" } -> "Medium"
        else -> "Low"
    }

private fun List<PublicWebEvidenceItem>.deduplicatedPublicWebEvidenceItems(): List<PublicWebEvidenceItem> {
    val seen = linkedSetOf<String>()
    return filter { item ->
        val key = item.url.normalizedPublicWebEvidenceKey()
            ?: "${item.sourceName.lowercase()}|${item.title.lowercase()}"
        seen.add(key)
    }
}

private fun String.normalizedPublicWebEvidenceKey(): String? =
    trim()
        .takeIf { value -> value.isNotBlank() }
        ?.let { value -> runCatching { URI(value).normalize().toString() }.getOrDefault(value) }
        ?.trimEnd('/')
        ?.lowercase()

private fun JSONArray?.objects(): List<JSONObject> =
    buildList {
        val array = this@objects ?: return@buildList
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let(::add)
        }
    }

private fun JSONArray?.sourceNamesById(): Map<String, String> =
    objects()
        .mapNotNull { source ->
            val id = source.optString("id").trim().takeIf { value -> value.isNotBlank() }
                ?: return@mapNotNull null
            id to source.optString("name").trim()
        }
        .toMap()

private fun JSONArray?.sourceUrlsById(): Map<String, String> =
    objects()
        .mapNotNull { source ->
            val id = source.optString("id").trim().takeIf { value -> value.isNotBlank() }
                ?: return@mapNotNull null
            id to source.optString("url").trim()
        }
        .toMap()

private fun firstNonBlank(vararg values: String): String? =
    values.firstOrNull { value -> value.isNotBlank() }

private fun String.publicWebDomain(): String? =
    runCatching {
        URI(this).host
            ?.removePrefix("www.")
            ?.takeIf { host -> host.isNotBlank() }
    }.getOrNull()
