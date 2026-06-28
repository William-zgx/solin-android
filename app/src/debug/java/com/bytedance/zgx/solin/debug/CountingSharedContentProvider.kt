package com.bytedance.zgx.solin.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class CountingSharedContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            val payload = payloadFor(uri)
            addRow(arrayOf<Any?>(displayNameFor(uri), payload.size.toLong()))
        }

    override fun getType(uri: Uri): String = mimeTypeFor(uri)

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        when (method) {
            METHOD_RESET_COUNTERS -> {
                resetCountersInProvider()
                Bundle.EMPTY
            }

            METHOD_COUNTS -> Bundle().apply {
                putInt(KEY_IMAGE_OPEN_COUNT, imageOpenCount.get())
                putInt(KEY_TEXT_OPEN_COUNT, textOpenCount.get())
            }

            else -> super.call(method, arg, extras)
        }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check(mode.contains("r")) { "CountingSharedContentProvider is read-only" }
        recordOpen(uri)
        return openPipeFor(uri)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        check(mode.contains("r")) { "CountingSharedContentProvider is read-only" }
        recordOpen(uri)
        return AssetFileDescriptor(openPipeFor(uri), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?,
    ): AssetFileDescriptor {
        recordOpen(uri)
        return AssetFileDescriptor(openPipeFor(uri), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("CountingSharedContentProvider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("CountingSharedContentProvider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int =
        throw UnsupportedOperationException("CountingSharedContentProvider is read-only")

    private fun openPipeFor(uri: Uri): ParcelFileDescriptor =
        openPipeHelper(uri, mimeTypeFor(uri), null, payloadFor(uri)) { output, _, _, _, bytes ->
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                stream.write(bytes)
            }
        }

    private fun recordOpen(uri: Uri) {
        if (isImageUri(uri)) {
            imageOpenCount.incrementAndGet()
        } else {
            textOpenCount.incrementAndGet()
        }
    }

    private fun payloadFor(uri: Uri): ByteArray =
        if (isImageUri(uri)) TINY_PNG_BYTES else TEXT_BYTES

    private fun displayNameFor(uri: Uri): String =
        if (isImageUri(uri)) "counting-image.png" else "private-notes.txt"

    private fun mimeTypeFor(uri: Uri): String =
        if (isImageUri(uri)) "image/png" else "text/plain"

    private fun isImageUri(uri: Uri): Boolean =
        uri.lastPathSegment == IMAGE_PATH

    companion object {
        private const val AUTHORITY = "com.bytedance.zgx.solin.debug.sharedcontent"
        private const val METHOD_RESET_COUNTERS = "resetCounters"
        private const val METHOD_COUNTS = "counts"
        private const val KEY_IMAGE_OPEN_COUNT = "imageOpenCount"
        private const val KEY_TEXT_OPEN_COUNT = "textOpenCount"
        private const val IMAGE_PATH = "image.png"
        private const val TEXT_PATH = "private-notes.txt"
        private const val TINY_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
        private val TINY_PNG_BYTES = Base64.decode(TINY_PNG_BASE64, Base64.DEFAULT)
        private val TEXT_BYTES = "PRIVATE_NOTES_SHOULD_NOT_BE_READ".toByteArray(Charsets.UTF_8)

        private val imageOpenCount = AtomicInteger()
        private val textOpenCount = AtomicInteger()

        val imageUri: Uri = Uri.parse("content://$AUTHORITY/$IMAGE_PATH")
        val textUri: Uri = Uri.parse("content://$AUTHORITY/$TEXT_PATH")
        private val controlUri: Uri = Uri.parse("content://$AUTHORITY")

        fun resetCounters(context: Context) {
            context.contentResolver.call(controlUri, METHOD_RESET_COUNTERS, null, null)
        }

        fun imageOpenCount(context: Context): Int =
            counts(context).getInt(KEY_IMAGE_OPEN_COUNT)

        fun textOpenCount(context: Context): Int =
            counts(context).getInt(KEY_TEXT_OPEN_COUNT)

        private fun counts(context: Context): Bundle =
            requireNotNull(context.contentResolver.call(controlUri, METHOD_COUNTS, null, null)) {
                "CountingSharedContentProvider did not return counts"
            }

        private fun resetCountersInProvider() {
            imageOpenCount.set(0)
            textOpenCount.set(0)
        }
    }
}
