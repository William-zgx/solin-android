package com.bytedance.zgx.solin.download

import android.app.DownloadManager
import com.bytedance.zgx.solin.data.ModelTransferSizeLimitExceededException
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadServiceTest {
    @Test
    fun loopbackDownloadsUseLocalDebugNetworkPolicy() {
        listOf(
            "http://127.0.0.1:8123/model.litertlm",
            "http://localhost:8123/model.litertlm",
            "http://[::1]:8123/model.litertlm",
            "http://10.0.2.2:8123/model.litertlm",
        ).forEach { url ->
            assertEquals(true, isLocalDebugModelDownloadUrl(url))
        }
    }

    @Test
    fun remoteDownloadsStayWifiOnly() {
        listOf(
            "https://huggingface.co/litert-community/model/resolve/main/model.litertlm",
            "http://api.example.com/model.litertlm",
            "not a url",
        ).forEach { url ->
            assertEquals(false, isLocalDebugModelDownloadUrl(url))
        }
    }

    @Test
    fun modelDownloadsStayWifiOnlyUnlessTheUserAllowsMeteredData() {
        // The default request must never spend the user's cellular data.
        assertEquals(DownloadManager.Request.NETWORK_WIFI, downloadNetworkTypes(allowMetered = false))
        assertEquals(
            DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE,
            downloadNetworkTypes(allowMetered = true),
        )
    }

    @Test
    fun wifiPausedTaskIsReportedAsWaitingForWifiOnly() {
        val waiting = DownloadInfo(
            status = DownloadManager.STATUS_PAUSED,
            reason = DownloadManager.PAUSED_QUEUED_FOR_WIFI,
            downloadedBytes = 1L,
            totalBytes = 10L,
        )
        val otherPause = DownloadInfo(
            status = DownloadManager.STATUS_PAUSED,
            reason = DownloadManager.PAUSED_WAITING_FOR_NETWORK,
            downloadedBytes = 1L,
            totalBytes = 10L,
        )
        val running = DownloadInfo(
            status = DownloadManager.STATUS_RUNNING,
            reason = 0,
            downloadedBytes = 1L,
            totalBytes = 10L,
        )

        assertTrue(waiting.isWaitingForWifi)
        assertFalse(otherPause.isWaitingForWifi)
        assertFalse(running.isWaitingForWifi)
    }

    @Test
    fun chunkedDownloadRejectsOversizeAndDeletesPartialFile() {
        withTempDownloadFile { targetFile ->
            val progress = mutableListOf<Long>()

            val failure = runCatching {
                downloadStreamToFileWithByteLimit(
                    input = ByteArrayInputStream(ByteArray(6)),
                    targetFile = targetFile,
                    maximumBytes = 5L,
                    onBytesCopied = progress::add,
                )
            }

            assertTrue(failure.exceptionOrNull() is ModelTransferSizeLimitExceededException)
            assertFalse(targetFile.exists())
            assertTrue(progress.isEmpty())
        }
    }

    @Test
    fun localDebugDownloadStopsAndDeletesPartialFileWhenCancelled() {
        withTempDownloadFile { targetFile ->
            var cancelled = false
            val progress = mutableListOf<Long>()

            val failure = runCatching {
                downloadStreamToFileWithByteLimit(
                    input = ByteArrayInputStream(ByteArray(DEFAULT_BUFFER_SIZE * 4)),
                    targetFile = targetFile,
                    maximumBytes = Long.MAX_VALUE,
                    shouldCancel = { cancelled },
                    onBytesCopied = { copied ->
                        progress += copied
                        cancelled = true
                    },
                )
            }

            assertTrue(failure.exceptionOrNull() is java.util.concurrent.CancellationException)
            assertFalse(targetFile.exists())
            assertEquals(listOf(DEFAULT_BUFFER_SIZE.toLong()), progress)
        }
    }

    @Test
    fun downloadManagerDeclaredOversizeCancelsTaskBeforeMoreBytesArrive() {
        var cancelCount = 0
        val info = DownloadInfo(
            status = DownloadManager.STATUS_RUNNING,
            reason = 0,
            downloadedBytes = 1L,
            totalBytes = 6L,
        )

        val bounded = enforceDownloadByteLimit(info, maximumBytes = 5L) {
            cancelCount += 1
        }

        assertEquals(1, cancelCount)
        assertEquals(DownloadManager.STATUS_FAILED, bounded.status)
        assertEquals(DownloadManager.ERROR_FILE_ERROR, bounded.reason)
    }

    @Test
    fun downloadManagerObservedOversizeCancelsUnknownSizeTask() {
        var cancelCount = 0
        val info = DownloadInfo(
            status = DownloadManager.STATUS_RUNNING,
            reason = 0,
            downloadedBytes = 6L,
            totalBytes = -1L,
        )

        val bounded = enforceDownloadByteLimit(info, maximumBytes = 5L) {
            cancelCount += 1
        }

        assertEquals(1, cancelCount)
        assertEquals(DownloadManager.STATUS_FAILED, bounded.status)
        assertEquals(6L, bounded.downloadedBytes)
    }

    @Test
    fun downloadManagerAtExactLimitKeepsTaskRunning() {
        var cancelCount = 0
        val info = DownloadInfo(
            status = DownloadManager.STATUS_RUNNING,
            reason = 0,
            downloadedBytes = 5L,
            totalBytes = 5L,
        )

        val bounded = enforceDownloadByteLimit(info, maximumBytes = 5L) {
            cancelCount += 1
        }

        assertEquals(0, cancelCount)
        assertEquals(info, bounded)
    }

    private fun withTempDownloadFile(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("solin-download-").toFile()
        val file = File(directory, "model.litertlm")
        try {
            block(file)
        } finally {
            file.delete()
            directory.deleteRecursively()
        }
    }
}
