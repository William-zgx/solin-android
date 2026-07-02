package com.bytedance.zgx.solin.multimodal

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.readLimitedBytes(limit: Int): Pair<ByteArray, Boolean> {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0
    while (totalBytes < limit) {
        val bytesToRead = minOf(buffer.size, limit - totalBytes)
        val read = read(buffer, 0, bytesToRead)
        if (read == -1) break
        output.write(buffer, 0, read)
        totalBytes += read
    }
    return output.toByteArray() to (totalBytes >= limit)
}
