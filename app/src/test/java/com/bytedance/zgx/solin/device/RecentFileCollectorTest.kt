package com.bytedance.zgx.solin.device

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentFileCollectorTest {
    @Test
    fun stopsReadingRowsAfterMaxMatchingItems() {
        val readIds = mutableListOf<Long>()
        val rows = sequence {
            for (id in 1L..3L) {
                readIds += id
                yield(recentFileRow(id = id, mimeType = "image/png"))
            }
        }

        val files = collectRecentFileItems(
            rows = rows,
            filter = RecentFileKindFilter(allowedKind = "images"),
            maxCount = 1,
        )

        assertEquals(listOf(1L), readIds)
        assertEquals(1, files.size)
        assertEquals(1L, files.single().id)
    }

    @Test
    fun skipsNonMatchingRowsButStopsAfterEnoughMatches() {
        val readIds = mutableListOf<Long>()
        val rows = sequence {
            listOf(
                recentFileRow(id = 1L, mimeType = "audio/mpeg"),
                recentFileRow(id = 2L, mimeType = "image/jpeg"),
                recentFileRow(id = 3L, mimeType = "image/png"),
            ).forEach { row ->
                readIds += row.id
                yield(row)
            }
        }

        val files = collectRecentFileItems(
            rows = rows,
            filter = RecentFileKindFilter(allowedKind = "images"),
            maxCount = 1,
        )

        assertEquals(listOf(1L, 2L), readIds)
        assertEquals(1, files.size)
        assertEquals(2L, files.single().id)
    }

    private fun recentFileRow(
        id: Long,
        mimeType: String?,
    ): RecentFileRow =
        RecentFileRow(
            id = id,
            name = "file-$id",
            mimeType = mimeType,
            sizeBytes = 128L,
            lastModifiedSeconds = 10L,
        )
}
