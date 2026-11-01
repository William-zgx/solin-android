package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStateTest {
    @Test
    fun endingADownloadLeavesAnUnrelatedRunBusy() {
        // Regression: a download no longer owns isBusy, so a remote chat can be generating while
        // it runs. Finishing or failing the download used to reset isBusy and unlock the composer
        // and model controls in the middle of that run.
        val generatingWhileDownloading = ChatUiState(
            isBusy = true,
            isGenerating = true,
            isDownloading = true,
            isWaitingForWifiDownload = true,
            downloadProgressPercent = 40,
        )

        val finished = generatingWhileDownloading.downloadFinished("模型下载完成")

        assertTrue(finished.isBusy)
        assertTrue(finished.isGenerating)
        assertFalse(finished.isDownloading)
        assertFalse(finished.isWaitingForWifiDownload)
    }
}
