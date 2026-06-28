package com.bytedance.zgx.solin.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAppProviderTest {
    @Test
    fun appOpsDeniedReturnsPermissionDeniedWithoutQueryingUsageStats() {
        val source = RecordingForegroundUsageStatsSource(
            hasPermission = false,
            snapshots = listOf(
                ForegroundUsageSnapshot("com.example.mail", 10L),
            ),
        )
        val provider = AndroidForegroundAppProvider(
            usageStatsSource = source,
            appLabelResolver = StaticForegroundAppLabelResolver(),
            clockMillis = { 1_000L },
        )

        val result = provider.currentForegroundApp()

        assertTrue(result is ForegroundAppReadResult.PermissionDenied)
        assertFalse(source.queryCalled)
    }

    @Test
    fun securityExceptionWhileQueryingUsageStatsReturnsPermissionDenied() {
        val provider = AndroidForegroundAppProvider(
            usageStatsSource = RecordingForegroundUsageStatsSource(
                hasPermission = true,
                securityExceptionOnQuery = true,
            ),
            appLabelResolver = StaticForegroundAppLabelResolver(),
            clockMillis = { 1_000L },
        )

        val result = provider.currentForegroundApp()

        assertTrue(result is ForegroundAppReadResult.PermissionDenied)
    }

    @Test
    fun picksLatestUsageStatAndResolvesLabel() {
        val source = RecordingForegroundUsageStatsSource(
            snapshots = listOf(
                ForegroundUsageSnapshot("com.example.old", 10L),
                ForegroundUsageSnapshot("com.example.mail", 30L),
                ForegroundUsageSnapshot("com.example.mid", 20L),
            ),
        )
        val provider = AndroidForegroundAppProvider(
            usageStatsSource = source,
            appLabelResolver = StaticForegroundAppLabelResolver(
                labels = mapOf("com.example.mail" to "Mail"),
            ),
            clockMillis = { 1_000_000L },
        )

        val result = provider.currentForegroundApp()

        require(result is ForegroundAppReadResult.Available)
        assertEquals(100_000L, source.lastStartTimeMillis)
        assertEquals(1_000_000L, source.lastEndTimeMillis)
        assertEquals("com.example.mail", result.appInfo.packageName)
        assertEquals("Mail", result.appInfo.appLabel)
        assertEquals(30L, result.appInfo.lastTimeUsedMillis)
        assertEquals("usage_stats_estimate", result.appInfo.source)
        assertEquals("estimate", result.appInfo.confidence)
    }

    private class RecordingForegroundUsageStatsSource(
        override val isSupported: Boolean = true,
        private val hasPermission: Boolean = true,
        private val snapshots: List<ForegroundUsageSnapshot> = emptyList(),
        private val securityExceptionOnQuery: Boolean = false,
    ) : ForegroundUsageStatsSource {
        var queryCalled = false
            private set
        var lastStartTimeMillis: Long? = null
            private set
        var lastEndTimeMillis: Long? = null
            private set

        override fun hasUsageStatsPermission(): Boolean = hasPermission

        override fun queryUsageStats(
            startTimeMillis: Long,
            endTimeMillis: Long,
        ): List<ForegroundUsageSnapshot> {
            queryCalled = true
            lastStartTimeMillis = startTimeMillis
            lastEndTimeMillis = endTimeMillis
            if (securityExceptionOnQuery) throw SecurityException("usage access revoked")
            return snapshots
        }
    }

    private class StaticForegroundAppLabelResolver(
        private val labels: Map<String, String> = emptyMap(),
    ) : ForegroundAppLabelResolver {
        override fun labelFor(packageName: String): String? = labels[packageName]
    }
}
