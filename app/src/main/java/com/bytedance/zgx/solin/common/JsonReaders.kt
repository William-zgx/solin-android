package com.bytedance.zgx.solin.common

import org.json.JSONArray
import org.json.JSONObject

/*
 * Small org.json readers shared across packages. Each one fails soft (null / empty) so callers
 * decide how to fail closed.
 */

/** Keys of this object in insertion order. */
internal fun JSONObject.keysSet(): Set<String> = keys().asSequence().toCollection(linkedSetOf())

/** Every JSONObject element of this array, skipping non-object entries. */
internal fun JSONArray.jsonObjects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)

/** [name] as a non-blank string, or null when absent, JSON null or blank. */
internal fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { value -> value.isNotBlank() }

/** [name] as a string (blank allowed), or null when absent or JSON null. */
internal fun JSONObject.optStringOrNull(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name)

internal fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (!has(name) || isNull(name)) null else optDouble(name)

/** String elements of array [name]; throws when an element is not a string, like [JSONArray.getString]. */
internal fun JSONObject.optStringSetOrNull(name: String): Set<String>? {
    val array = optJSONArray(name) ?: return null
    return buildSet {
        for (index in 0 until array.length()) {
            add(array.getString(index))
        }
    }
}

internal fun JSONObject.optStringSet(name: String): Set<String> = optStringSetOrNull(name).orEmpty()

internal fun firstNonBlank(vararg values: String): String? =
    values.firstOrNull { value -> value.isNotBlank() }
