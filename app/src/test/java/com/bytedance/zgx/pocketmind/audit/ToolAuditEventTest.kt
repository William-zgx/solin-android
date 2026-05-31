package com.bytedance.zgx.pocketmind.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAuditEventTest {
    @Test
    fun sanitizedSummaryCollapsesWhitespaceAndTruncates() {
        val event = ToolAuditEvent(
            runId = "run-1",
            requestId = "request-1",
            toolName = "web_search",
            skillId = null,
            eventType = ToolAuditEventType.ToolPlanned,
            summary = "  first\nsecond\t${"x".repeat(200)}  ",
        )

        val sanitized = event.sanitizedSummary(maxLength = 20)

        assertEquals(20, sanitized.length)
        assertTrue(!sanitized.contains("\n"))
        assertTrue(sanitized.startsWith("first second"))
    }

    @Test
    fun sanitizedSummaryRedactsCredentialsBeforePersisting() {
        val apiKey = "sk-" + "a".repeat(32)
        val bearer = "Bearer " + "b".repeat(32)
        val event = ToolAuditEvent(
            runId = "run-1",
            requestId = "request-1",
            toolName = "web_search",
            skillId = null,
            eventType = ToolAuditEventType.ToolObserved,
            summary = "api_key=$apiKey Authorization: $bearer email alice@example.com",
        )

        val sanitized = event.sanitizedSummary()

        assertFalse(sanitized.contains(apiKey))
        assertFalse(sanitized.contains(bearer))
        assertFalse(sanitized.contains("alice@example.com"))
        assertTrue(sanitized.contains("api_key=[redacted]"))
        assertTrue(sanitized.contains("Authorization=[redacted]"))
        assertTrue(sanitized.contains("[email]"))
    }

    @Test
    fun sanitizedSummaryRedactsGenericTokenAndKeyAssignments() {
        val tokenValue = "private-token-" + "e".repeat(20)
        val keyValue = "private-key-" + "f".repeat(20)
        val event = ToolAuditEvent(
            runId = "run-1",
            requestId = "request-1",
            toolName = "web_search",
            skillId = null,
            eventType = ToolAuditEventType.ToolObserved,
            summary = "token=$tokenValue key: $keyValue",
        )

        val sanitized = event.sanitizedSummary()

        assertFalse(sanitized.contains(tokenValue))
        assertFalse(sanitized.contains(keyValue))
        assertTrue(sanitized.contains("token=[redacted]"))
        assertTrue(sanitized.contains("key=[redacted]"))
    }

    @Test
    fun inMemorySinkStoresRedactedAuditCopy() {
        val secret = "sk-" + "c".repeat(32)
        val sink = InMemoryToolAuditSink()

        sink.record(
            ToolAuditEvent(
                runId = "run-1",
                requestId = "request-1",
                toolName = "read_clipboard",
                skillId = null,
                eventType = ToolAuditEventType.ToolObserved,
                summary = "clipboard result accidentally included token $secret",
            ),
        )

        assertEquals(1, sink.events.size)
        assertFalse(sink.events.single().summary.contains(secret))
        assertTrue(sink.events.single().summary.contains("sk-[redacted]"))
    }
}
