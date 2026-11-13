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
internal fun JSONObject.optNullableString(name: String): String? = if (isNull(name)) null else optString(name).takeIf { value -> value.isNotBlank() }

/** [name] as a string (blank allowed), or null when absent or JSON null. */
internal fun JSONObject.optStringOrNull(name: String): String? = if (!has(name) || isNull(name)) null else optString(name)

internal fun JSONObject.optDoubleOrNull(name: String): Double? = if (!has(name) || isNull(name)) null else optDouble(name)

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

internal fun firstNonBlank(vararg values: String): String? = values.firstOrNull { value -> value.isNotBlank() }

/** Exact persisted enum name, or null when it is absent or no longer recognized. Fallback stays with the caller. */
internal inline fun <reified T : Enum<T>> persistedEnumOrNull(raw: String?): T? = enumValues<T>().firstOrNull { it.name == raw }

/** Syntax violations found before a platform JSON parser can apply its own lenient rules. */
internal enum class StrictJsonRejection {
    NotJsonObject,
    MalformedJson,
    DuplicateKey,
    TrailingContent
}

/**
 * Checks that text is exactly one strict RFC 8259 JSON object, without org.json. Android's built-in
 * org.json is lenient: it skips `#`, `//` and block comments, accepts `=`, `=>` and `;` separators,
 * unquoted and single-quoted strings, and hex or octal numbers, and keeps the last value of a
 * duplicate key. The org.json artifact on the JVM test classpath is stricter, so JVM tests alone
 * cannot show what the phone accepts.
 */
internal object StrictJsonScan {
    /** Bound nested input such as plans and model responses. */
    const val MAX_DEPTH = 8

    /** Null when [text] is one strict JSON object with unique keys at every depth. */
    fun firstViolation(text: String): StrictJsonRejection? {
        val cursor = Cursor(text)
        cursor.skipWhitespace()
        if (cursor.current() != '{') return StrictJsonRejection.NotJsonObject
        return try {
            cursor.readValue(depth = 0)
            cursor.skipWhitespace()
            if (cursor.atEnd()) null else StrictJsonRejection.TrailingContent
        } catch (violation: Violation) {
            violation.reason
        }
    }

    private class Cursor(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        /** NUL past the end; a NUL inside the text fails wherever it appears, so the two never mix. */
        fun current(): Char = if (atEnd()) '\u0000' else text[index]

        fun skipWhitespace() {
            while (current() == ' ' || current() == '\t' || current() == '\n' || current() == '\r') index++
        }

        fun readValue(depth: Int) {
            when (val char = current()) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> readString()
                't' -> readLiteral("true")
                'f' -> readLiteral("false")
                'n' -> readLiteral("null")
                else -> if (char == '-' || char in '0'..'9') readNumber() else malformed()
            }
        }

        private fun readObject(depth: Int) {
            if (depth > MAX_DEPTH) malformed()
            index++
            skipWhitespace()
            if (current() == '}') {
                index++
                return
            }
            val keys = HashSet<String>()
            while (true) {
                skipWhitespace()
                if (current() != '"') malformed()
                if (!keys.add(readString())) throw Violation(StrictJsonRejection.DuplicateKey)
                skipWhitespace()
                expect(':')
                skipWhitespace()
                readValue(depth)
                skipWhitespace()
                if (current() == ',') {
                    index++
                } else {
                    expect('}')
                    return
                }
            }
        }

        private fun readArray(depth: Int) {
            if (depth > MAX_DEPTH) malformed()
            index++
            skipWhitespace()
            if (current() == ']') {
                index++
                return
            }
            while (true) {
                skipWhitespace()
                readValue(depth)
                skipWhitespace()
                if (current() == ',') {
                    index++
                } else {
                    expect(']')
                    return
                }
            }
        }

        /** Returns the decoded text, so `"app"` and `"app"` are the same key. */
        private fun readString(): String {
            index++
            val decoded = StringBuilder()
            while (true) {
                if (atEnd()) malformed()
                val char = text[index++]
                when {
                    char == '"' -> return decoded.toString()
                    char == '\\' -> decoded.append(readEscape())
                    char < ' ' -> malformed()
                    else -> decoded.append(char)
                }
            }
        }

        private fun readEscape(): Char {
            if (atEnd()) malformed()
            return when (val char = text[index++]) {
                '"', '\\', '/' -> char
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readHexChar()
                else -> malformed()
            }
        }

        private fun readHexChar(): Char {
            val digits = text.substring(index, minOf(index + 4, text.length))
            val isHex = digits.all { digit -> digit in '0'..'9' || digit in 'a'..'f' || digit in 'A'..'F' }
            if (digits.length != 4 || !isHex) malformed()
            index += 4
            return digits.toInt(16).toChar()
        }

        private fun readLiteral(word: String) {
            if (!text.startsWith(word, index)) malformed()
            index += word.length
        }

        /** `-?(0|[1-9][0-9]*)(.[0-9]+)?([eE][+-]?[0-9]+)?`: no hex, octal, `+1`, `.5`, `1.` or NaN. */
        private fun readNumber() {
            if (current() == '-') index++
            if (current() == '0') index++ else readDigits()
            if (current() == '.') {
                index++
                readDigits()
            }
            if (current() == 'e' || current() == 'E') {
                index++
                if (current() == '+' || current() == '-') index++
                readDigits()
            }
        }

        private fun readDigits() {
            if (current() !in '0'..'9') malformed()
            while (current() in '0'..'9') index++
        }

        private fun expect(char: Char) {
            if (current() != char) malformed()
            index++
        }

        private fun malformed(): Nothing = throw Violation(StrictJsonRejection.MalformedJson)
    }

    private class Violation(val reason: StrictJsonRejection) : RuntimeException(null, null, false, false)
}
