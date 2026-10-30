package com.bytedance.zgx.solin.storage

import android.database.Cursor
import android.provider.OpenableColumns
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenableMetadataTest {
    @Test
    fun readsNameAndSize() {
        val metadata = fakeCursor(mapOf(OpenableColumns.DISPLAY_NAME to "model.litertlm", OpenableColumns.SIZE to 42L))
            .readOpenableMetadata()

        assertEquals(OpenableMetadata(displayName = "model.litertlm", sizeBytes = 42L), metadata)
    }

    @Test
    fun nullOrNegativeOrMissingColumnsBecomeNull() {
        assertEquals(
            OpenableMetadata(displayName = null, sizeBytes = null),
            fakeCursor(mapOf(OpenableColumns.DISPLAY_NAME to null, OpenableColumns.SIZE to -1L)).readOpenableMetadata(),
        )
        assertEquals(
            OpenableMetadata(displayName = null, sizeBytes = null),
            fakeCursor(emptyMap()).readOpenableMetadata(),
        )
    }

    @Test
    fun emptyCursorHasNoMetadata() {
        assertNull(fakeCursor(mapOf(OpenableColumns.SIZE to 1L), hasRow = false).readOpenableMetadata())
    }

    /** Minimal single-row [Cursor] over [values]; column order follows the map. */
    private fun fakeCursor(values: Map<String, Any?>, hasRow: Boolean = true): Cursor {
        val columns = values.keys.toList()
        return Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
            when (method.name) {
                "moveToFirst" -> hasRow
                "getColumnIndex" -> columns.indexOf(args!![0] as String)
                "isNull" -> values[columns[args!![0] as Int]] == null
                "getString" -> values[columns[args!![0] as Int]] as String?
                "getLong" -> values[columns[args!![0] as Int]] as Long
                "close" -> Unit
                else -> error("unexpected Cursor.${method.name}")
            }
        } as Cursor
    }
}
