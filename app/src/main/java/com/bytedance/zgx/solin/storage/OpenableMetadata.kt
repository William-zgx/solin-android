package com.bytedance.zgx.solin.storage

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns

/**
 * [OpenableColumns] metadata of a content URI. Either field is null when the provider omits the
 * column, reports SQL NULL, or (for [sizeBytes]) reports a negative size.
 */
internal data class OpenableMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
)

/**
 * Reads [OpenableColumns.DISPLAY_NAME] and [OpenableColumns.SIZE] for [uri] in one query. Returns
 * null when the provider returns no cursor or no row. Provider exceptions (e.g. SecurityException)
 * propagate; callers decide whether to fail or degrade.
 */
internal fun ContentResolver.openableMetadata(uri: Uri): OpenableMetadata? =
    query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor -> cursor.readOpenableMetadata() }

/** Reads the first row of an [OpenableColumns] cursor; see [openableMetadata]. */
internal fun Cursor.readOpenableMetadata(): OpenableMetadata? {
    if (!moveToFirst()) return null
    val nameIndex = getColumnIndex(OpenableColumns.DISPLAY_NAME)
    val sizeIndex = getColumnIndex(OpenableColumns.SIZE)
    return OpenableMetadata(
        displayName = if (nameIndex >= 0 && !isNull(nameIndex)) getString(nameIndex) else null,
        sizeBytes = if (sizeIndex >= 0 && !isNull(sizeIndex)) getLong(sizeIndex).takeIf { it >= 0L } else null,
    )
}
