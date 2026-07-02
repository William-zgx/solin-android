package com.bytedance.zgx.solin.data

import java.io.File
import java.nio.file.Files

internal fun withTempModelDir(
    prefix: String = "solin-model",
    block: (File) -> Unit,
) {
    val dir = Files.createTempDirectory(prefix).toFile()
    try {
        block(dir)
    } finally {
        dir.deleteRecursively()
    }
}
