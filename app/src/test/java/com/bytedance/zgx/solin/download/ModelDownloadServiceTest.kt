package com.bytedance.zgx.solin.download

import org.junit.Assert.assertEquals
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
            assertEquals(ModelDownloadNetworkPolicy.LocalDebug, modelDownloadNetworkPolicyFor(url))
        }
    }

    @Test
    fun remoteDownloadsStayWifiOnly() {
        listOf(
            "https://huggingface.co/litert-community/model/resolve/main/model.litertlm",
            "http://api.example.com/model.litertlm",
            "not a url",
        ).forEach { url ->
            assertEquals(ModelDownloadNetworkPolicy.WifiOnly, modelDownloadNetworkPolicyFor(url))
        }
    }
}
