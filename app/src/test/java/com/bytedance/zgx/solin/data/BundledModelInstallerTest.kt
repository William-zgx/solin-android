package com.bytedance.zgx.solin.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledModelInstallerTest {
    @Test
    fun publishBundledModelTempFileFallsBackWhenAtomicMoveIsUnavailable() {
        withTempModelDir { dir ->
            val target = File(dir, "model.litertlm").apply {
                writeText("old", Charsets.UTF_8)
            }
            val temp = File(dir, "model.litertlm.bundled.tmp").apply {
                writeText("new", Charsets.UTF_8)
            }

            publishBundledModelTempFile(
                temp = temp,
                target = target,
                atomicMove = { _, _ -> throw IOException("atomic move unavailable") },
                replaceMove = { from, to ->
                    Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
                },
            )

            assertEquals("new", target.readText(Charsets.UTF_8))
            assertFalse(temp.exists())
        }
    }

    @Test
    fun publishBundledModelTempFileKeepsExistingTargetWhenMovesFail() {
        withTempModelDir { dir ->
            val target = File(dir, "model.litertlm").apply {
                writeText("old", Charsets.UTF_8)
            }
            val temp = File(dir, "model.litertlm.bundled.tmp").apply {
                writeText("new", Charsets.UTF_8)
            }

            val failure = runCatching {
                publishBundledModelTempFile(
                    temp = temp,
                    target = target,
                    atomicMove = { _, _ -> throw IOException("atomic move unavailable") },
                    replaceMove = { _, _ -> throw IOException("replace failed") },
                )
            }

            assertTrue(failure.isFailure)
            assertEquals("replace failed", failure.exceptionOrNull()?.message)
            assertEquals("old", target.readText(Charsets.UTF_8))
            assertFalse(temp.exists())
        }
    }

}
