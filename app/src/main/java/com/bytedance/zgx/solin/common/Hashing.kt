package com.bytedance.zgx.solin.common

import java.security.MessageDigest

/** Lowercase two-digit hex per byte. */
internal fun ByteArray.toLowerHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }

/** Lowercase hex SHA-256 of [bytes]. */
internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()

/** Lowercase hex SHA-256 of [text] encoded as UTF-8. */
internal fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

/** Appends `length:value;` so concatenated fields hash without ambiguity. */
internal fun StringBuilder.appendLengthPrefixed(value: String) {
    append(value.length)
    append(':')
    append(value)
    append(';')
}
