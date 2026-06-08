package com.bytedance.zgx.pocketmind.safety

import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.tool.ConfirmationPolicy
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolCapability
import com.bytedance.zgx.pocketmind.tool.ToolPermission
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import com.bytedance.zgx.pocketmind.tool.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPolicyTest {
    private val policy = SafetyPolicy()

    @Test
    fun requiredConfirmationToolWaitsUntilUserConfirms() {
        val spec = toolSpec(
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Required,
        )
        val request = ToolRequest(toolName = spec.name)

        val beforeConfirmation = policy.evaluate(
            spec = spec,
            request = request,
            context = SafetyContext(userConfirmed = false),
        )
        val afterConfirmation = policy.evaluate(
            spec = spec,
            request = request,
            context = SafetyContext(userConfirmed = true),
        )

        assertEquals(SafetyOutcome.RequireConfirmation, beforeConfirmation.outcome)
        assertEquals(SafetyOutcome.Allow, afterConfirmation.outcome)
    }

    @Test
    fun highRiskToolsCannotSkipConfirmation() {
        val spec = toolSpec(
            riskLevel = RiskLevel.HighExternalSend,
            confirmationPolicy = ConfirmationPolicy.Optional,
            permissions = setOf(ToolPermission.StartsExternalActivity),
        )

        val decision = policy.evaluate(
            spec = spec,
            request = ToolRequest(toolName = spec.name),
            context = SafetyContext(userConfirmed = true),
        )

        assertEquals(SafetyOutcome.Reject, decision.outcome)
    }

    @Test
    fun externalTextToolsCannotRunWithoutConfirmationPolicy() {
        val spec = toolSpec(
            riskLevel = RiskLevel.MediumDraftOrNavigation,
            confirmationPolicy = ConfirmationPolicy.Optional,
            permissions = setOf(ToolPermission.SendsTextToExternalApp),
        )

        val decision = policy.evaluate(
            spec = spec,
            request = ToolRequest(toolName = spec.name),
            context = SafetyContext(userConfirmed = true),
        )

        assertEquals(SafetyOutcome.Reject, decision.outcome)
    }

    @Test
    fun privateReadToolsCannotSkipConfirmationPolicy() {
        val spec = toolSpec(
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Optional,
            permissions = setOf(ToolPermission.ReadsDeviceContext, ToolPermission.ReadsContacts),
        )

        val decision = policy.evaluate(
            spec = spec,
            request = ToolRequest(toolName = spec.name),
            context = SafetyContext(userConfirmed = true),
        )

        assertEquals(SafetyOutcome.Reject, decision.outcome)
    }

    @Test
    fun boundaryPermissionsCannotSkipConfirmationPolicy() {
        boundaryPermissions.forEach { permission ->
            listOf(ConfirmationPolicy.Optional, ConfirmationPolicy.NotRequired).forEach { confirmationPolicy ->
                val spec = toolSpec(
                    riskLevel = RiskLevel.LowReadOnly,
                    confirmationPolicy = confirmationPolicy,
                    permissions = setOf(permission),
                )

                val decision = policy.evaluate(
                    spec = spec,
                    request = ToolRequest(toolName = spec.name),
                    context = SafetyContext(userConfirmed = true),
                )

                assertEquals("permission=$permission policy=$confirmationPolicy", SafetyOutcome.Reject, decision.outcome)
            }
        }
    }

    @Test
    fun registeredBoundaryToolsRequireConfirmationBeforeExecution() {
        val boundarySpecs = ToolRegistry().specs()
            .filter { spec ->
                spec.riskLevel.requiresHardConfirmationForTest() ||
                    spec.permissions.any { permission -> permission in boundaryPermissions }
            }

        assertTrue(boundarySpecs.isNotEmpty())
        boundarySpecs.forEach { spec ->
            val beforeConfirmation = policy.evaluate(
                spec = spec,
                request = ToolRequest(toolName = spec.name),
                context = SafetyContext(userConfirmed = false),
            )
            val afterConfirmation = policy.evaluate(
                spec = spec,
                request = ToolRequest(toolName = spec.name),
                context = SafetyContext(userConfirmed = true),
            )

            assertEquals(spec.name, ConfirmationPolicy.Required, spec.confirmationPolicy)
            assertEquals(spec.name, SafetyOutcome.RequireConfirmation, beforeConfirmation.outcome)
            assertEquals(spec.name, SafetyOutcome.Allow, afterConfirmation.outcome)
        }
    }

    @Test
    fun publicWebSearchQueryCanRunWithoutConfirmation() {
        val spec = ToolRegistry().specFor(MobileActionFunctions.WEB_SEARCH)
        requireNotNull(spec)

        val decision = policy.evaluate(
            spec = spec,
            request = ToolRequest(
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "北京天气怎么样"),
            ),
            context = SafetyContext(userConfirmed = false),
        )

        assertEquals(SafetyOutcome.Allow, decision.outcome)
    }

    @Test
    fun sensitiveWebSearchQueryRequiresConfirmationBeforeNetworkAccess() {
        val spec = ToolRegistry().specFor(MobileActionFunctions.WEB_SEARCH)
        requireNotNull(spec)
        val sensitiveQueries = listOf(
            "搜索我的手机号 13800138000 有没有泄露",
            "look up my email alex@example.com",
            "帮我查我的地址附近有什么",
            "搜一下我的银行卡 6222020202020202020 有没有风险",
            "check my employee id E123456 incident history",
            "搜索我的 HIV 检测结果应该怎么办",
            "查一下附近心理咨询",
            "附近孕检在哪里",
            "附近艾滋检测中心",
            "find bankruptcy lawyer near me",
            "HIV testing near me",
            "pregnancy test near me",
            "depression help near me",
            "search my credit card debt options",
            "look up my child's insurance claim",
            "search " + "sk-" + "1234567890abcdef1234567890abcdef",
            "search AKIA1234567890ABCDEF",
        )

        sensitiveQueries.forEach { query ->
            val beforeConfirmation = policy.evaluate(
                spec = spec,
                request = ToolRequest(
                    toolName = MobileActionFunctions.WEB_SEARCH,
                    arguments = mapOf("query" to query),
                ),
                context = SafetyContext(userConfirmed = false),
            )
            val afterConfirmation = policy.evaluate(
                spec = spec,
                request = ToolRequest(
                    toolName = MobileActionFunctions.WEB_SEARCH,
                    arguments = mapOf("query" to query),
                ),
                context = SafetyContext(userConfirmed = true),
            )

            assertEquals(query, SafetyOutcome.RequireConfirmation, beforeConfirmation.outcome)
            assertEquals(query, SafetyOutcome.Allow, afterConfirmation.outcome)
        }
    }

    @Test
    fun sensitiveRemotePromptContentIsDetectedForOutboundGate() {
        assertTrue(policy.containsSensitivePersonalOrSecretContent("我的手机号是 13800138000，帮我总结"))
        assertTrue(policy.containsSensitivePersonalOrSecretContent("please explain my email alex@example.com"))
        assertTrue(policy.containsSensitivePersonalOrSecretContent("我的银行卡 6222020202020202020 是否安全"))
        assertTrue(policy.containsSensitivePersonalOrSecretContent("my employee id E123456 needs a report"))
        assertTrue(policy.containsSensitivePersonalOrSecretContent("我的怀孕检查结果怎么解读"))
        assertTrue(policy.containsSensitivePersonalOrSecretContent("nearby therapist near me"))
        assertTrue(policy.containsSensitivePersonalOrSecretContent("HIV testing near me"))
        assertTrue(policy.containsSensitivePersonalOrSecretContent("pregnancy test near me"))
        assertTrue(policy.containsSensitivePersonalOrSecretContent("附近孕检在哪里"))
        assertTrue(policy.containsSensitivePersonalOrSecretContent("my bankruptcy lawyer options"))
        assertTrue(policy.containsSensitivePersonalOrSecretContent("check " + "sk-" + "1234567890abcdef1234567890abcdef"))
        assertTrue(policy.containsSensitivePersonalOrSecretContent("AWS key AKIA1234567890ABCDEF"))
        assertTrue(policy.containsSensitivePersonalOrSecretContent("Google key AIzaSyA123456789012345678901234567890123"))
        assertTrue(policy.containsSensitivePersonalOrSecretContent("Slack token xoxb-123456789012-abcdefabcdef"))
        assertTrue(policy.containsSensitivePersonalOrSecretContent("-----BEGIN PRIVATE KEY-----"))
        assertTrue(policy.containsSensitivePersonalOrSecretContent("client_secret = superSecret123"))
    }

    @Test
    fun isoToolTimeWindowsAreNotTreatedAsPhoneNumbersByOutboundGate() {
        assertFalse(
            policy.containsSensitivePersonalOrSecretContent(
                "查忙闲 2026-06-01T09:00:00Z 到 2026-06-01T10:00:00Z",
            ),
        )
        assertFalse(
            policy.containsSensitivePersonalOrSecretContent(
                "calendar availability 2026-06-01T09:00:00Z to 2026-06-01T10:00:00Z",
            ),
        )
    }

    @Test
    fun commonNonSensitiveNumberListsDoNotTriggerOutboundConfirmation() {
        // Space-separated number lists (year comparisons, short groups) and short codes/prices
        // are common, non-sensitive queries that must not be misread as phone numbers.
        val nonSensitive = listOf(
            "2020 2021 2022 销量对比",
            "compare revenue 2019 2020 2021 2022",
            "这款手机 1299 元值得买吗",
            "验证码 123456 多久过期",
            "比一下 123 456 789 三组数据",
            "room 101 102 103 availability",
        )
        nonSensitive.forEach { query ->
            assertFalse(query, policy.containsSensitivePersonalOrSecretContent(query))
        }
    }

    @Test
    fun realPhoneNumbersStillTriggerOutboundConfirmation() {
        // Recall guard: tightening the phone heuristic must not drop genuine phone numbers.
        val sensitive = listOf(
            "我的手机号是 13800138000，帮我总结",
            "电话 010-1234-5678 是谁的",
            "call +1 415 555 2671 back",
        )
        sensitive.forEach { query ->
            assertTrue(query, policy.containsSensitivePersonalOrSecretContent(query))
        }
    }

    @Test
    fun detectSensitiveCategoriesReturnsEmptyForOrdinaryText() {
        assertTrue(policy.detectSensitiveCategories("今天天气怎么样").isEmpty())
        assertTrue(policy.detectSensitiveCategories("   ").isEmpty())
    }

    @Test
    fun detectSensitiveCategoriesFlagsPhoneAndPersonalIdentity() {
        val categories = policy.detectSensitiveCategories("我的手机号是 13800001111")
        assertTrue(categories.contains(SafetyCategory.Phone))
        assertTrue(categories.contains(SafetyCategory.PersonalIdentity))
    }

    @Test
    fun detectSensitiveCategoriesFlagsEmailAndSecret() {
        val email = policy.detectSensitiveCategories("contact a@b.com please")
        assertTrue(email.contains(SafetyCategory.Email))
        val secret = policy.detectSensitiveCategories("password=hunter2abc")
        assertTrue(secret.contains(SafetyCategory.SecretAssignment))
    }

    @Test
    fun detectSensitiveCategoriesAgreesWithBooleanContract() {
        val sensitive = "我的邮箱是 a@b.com"
        assertTrue(policy.containsSensitivePersonalOrSecretContent(sensitive))
        assertTrue(policy.detectSensitiveCategories(sensitive).isNotEmpty())
        val benign = "推荐几本科幻小说"
        assertFalse(policy.containsSensitivePersonalOrSecretContent(benign))
        assertTrue(policy.detectSensitiveCategories(benign).isEmpty())
    }

    private fun toolSpec(
        riskLevel: RiskLevel,
        confirmationPolicy: ConfirmationPolicy,
        permissions: Set<ToolPermission> = emptySet(),
    ): ToolSpec =
        ToolSpec(
            name = "test_tool",
            title = "Test Tool",
            description = "A test tool.",
            inputSchemaJson = "{}",
            capability = ToolCapability.ExternalNavigation,
            permissions = permissions,
            riskLevel = riskLevel,
            confirmationPolicy = confirmationPolicy,
        )

    private fun RiskLevel.requiresHardConfirmationForTest(): Boolean =
        this == RiskLevel.HighExternalSend || this == RiskLevel.CriticalDeviceOrPayment

    private companion object {
        val boundaryPermissions = setOf(
            ToolPermission.StartsExternalActivity,
            ToolPermission.SendsTextToExternalApp,
            ToolPermission.RequiresAndroidRuntimePermission,
            ToolPermission.SchedulesBackgroundWork,
            ToolPermission.PostsNotification,
            ToolPermission.ReadsClipboard,
            ToolPermission.ReadsContacts,
            ToolPermission.ReadsFiles,
            ToolPermission.ReadsCalendar,
            ToolPermission.ReadsAccessibilityText,
            ToolPermission.RequiresMediaProjectionConsent,
            ToolPermission.ReadsDeviceContext,
        )
    }
}
