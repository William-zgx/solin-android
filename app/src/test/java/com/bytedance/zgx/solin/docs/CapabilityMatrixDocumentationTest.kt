package com.bytedance.zgx.solin.docs

import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.capability.CapabilityMatrix
import com.bytedance.zgx.solin.capability.CapabilityPrivacyLevel
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.ToolRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityMatrixDocumentationTest {
    @Test
    fun capabilityMatrixJsonMatchesProductDescriptors() {
        val json = JSONObject(readRepoFile("docs/capability_matrix.json"))
        val documented = json.getJSONArray("productCapabilities")
        val documentedIds = (0 until documented.length()).map { index ->
            documented.getJSONObject(index).getString("capabilityId")
        }

        assertEquals(CapabilityMatrix.productPositioning, json.getString("productPositioning"))
        assertEquals(CapabilityMatrix.targetUserJob, json.getString("targetUserJob"))
        assertTrue(json.getString("productPositioning").contains("让 AI 住在手机里"))
        assertTrue(json.getString("productPositioning").contains("默认保守确认"))
        assertTrue(json.getString("productPositioning").contains("低风险连续"))
        assertTrue(json.getString("targetUserJob").contains("本地上下文留在本机"))

        assertEquals(
            CapabilityMatrix.productDescriptors.map { descriptor -> descriptor.capabilityId },
            documentedIds,
        )
        CapabilityMatrix.productDescriptors.forEachIndexed { index, descriptor ->
            val item = documented.getJSONObject(index)
            assertEquals(descriptor.capabilityId, item.getString("capabilityId"))
            assertEquals(descriptor.entrypoint, item.getString("entrypoint"))
            assertEquals(descriptor.toolName, item.nullableString("toolName"))
            assertEquals(descriptor.modelCapability?.name, item.nullableString("modelCapability"))
            assertEquals(descriptor.privacyLevel.name, item.getString("privacyLevel"))
            assertEquals(descriptor.requiresLocalModel, item.getBoolean("requiresLocalModel"))
            assertEquals(descriptor.remoteEligible, item.getBoolean("remoteEligible"))
            assertEquals(descriptor.confirmationPolicy.name, item.getString("confirmationPolicy"))
            assertEquals(descriptor.failureBehavior, item.getString("failureBehavior"))
            assertEquals(descriptor.requiredTests, item.getStringList("requiredTests"))
            assertEquals(descriptor.ownerAgent.name, item.getString("ownerAgent"))
        }
        assertEquals(
            listOf(
                "local_offline_chat",
                "explicit_memory",
                "shared_file_text_input",
                "local_vision_image_input",
                "remote_vision_image_input",
                "voice_transcript_input",
                "confirmed_device_tools",
                "auditable_agent_trace",
                "model_management",
                "run_data_receipt",
                "local_private_qa_and_memory",
                "local_screen_clipboard_summary_share",
                "low_risk_app_search_control",
                "local_reminders_background_tasks",
                "remote_public_evidence",
                "trust_center_capability_review",
                "release_gate",
            ),
            documentedIds,
        )
    }

    @Test
    fun nextStageProductCapabilitiesNameCoreMvpScenarios() {
        val json = JSONObject(readRepoFile("docs/capability_matrix.json"))
        val documentedScenarios = json.getJSONArray("nextStageMvpScenarios")
        val documentedScenarioIds = (0 until documentedScenarios.length()).map { index ->
            documentedScenarios.getJSONObject(index).getString("capabilityId")
        }
        val documentedScenarioTitles = (0 until documentedScenarios.length()).associate { index ->
            val item = documentedScenarios.getJSONObject(index)
            item.getString("capabilityId") to item.getString("title")
        }
        val descriptors = CapabilityMatrix.productDescriptors.associateBy { descriptor ->
            descriptor.capabilityId
        }
        val requiredIds = CapabilityMatrix.nextStageMvpScenarioIds

        assertEquals(requiredIds, CapabilityMatrix.nextStageMvpScenarioTitles.keys.toList())
        assertEquals(requiredIds, documentedScenarioIds)
        assertEquals(CapabilityMatrix.nextStageMvpScenarioTitles, documentedScenarioTitles)
        requiredIds.forEach { capabilityId ->
            val title = CapabilityMatrix.nextStageMvpScenarioTitle(capabilityId)
            assertTrue("missing next-stage descriptor $capabilityId", descriptors.containsKey(capabilityId))
            assertTrue("next-stage title must be non-blank for $capabilityId", title.isNotBlank())
            assertFalse("next-stage title must be user-readable for $capabilityId", title.contains("_"))
        }
        assertTrue(descriptors.getValue("local_private_qa_and_memory").failureBehavior.contains("LocalOnly"))
        assertTrue(descriptors.getValue("local_screen_clipboard_summary_share").failureBehavior.contains("二次确认"))
        assertTrue(descriptors.getValue("low_risk_app_search_control").failureBehavior.contains("低风险"))
        assertTrue(descriptors.getValue("local_reminders_background_tasks").privacyLevel.name == "BackgroundTask")
        assertTrue(descriptors.getValue("remote_public_evidence").failureBehavior.contains("整批拒绝"))
        assertTrue(descriptors.getValue("trust_center_capability_review").failureBehavior.contains("能力与信任中心"))
    }

    @Test
    fun requiredBehaviorEvalBoundariesMatchJsonAndStayStable() {
        val json = JSONObject(readRepoFile("docs/capability_matrix.json"))
        val documentedBoundaries = json.getStringList("requiredBehaviorEvalBoundaries")

        assertEquals(CapabilityMatrix.requiredBehaviorEvalBoundaries, documentedBoundaries)
        assertEquals(
            listOf("public_evidence_multi_search_batch_allowed"),
            CapabilityMatrix.requiredBehaviorEvalBoundaries,
        )
        assertFalse(CapabilityMatrix.requiredBehaviorEvalBoundaries.isEmpty())
        assertEquals(
            CapabilityMatrix.requiredBehaviorEvalBoundaries,
            CapabilityMatrix.requiredBehaviorEvalBoundaries.distinct(),
        )
        CapabilityMatrix.requiredBehaviorEvalBoundaries.forEach { boundary ->
            assertTrue(
                "required behavior eval boundary must use stable slug taxonomy: $boundary",
                Regex("^[a-z0-9][a-z0-9_:-]*$").matches(boundary),
            )
        }
    }

    @Test
    fun localVisionImageInputRequiresLocalModelAndDoesNotForceOcr() {
        val descriptor = CapabilityMatrix.productDescriptors.single {
            it.capabilityId == "local_vision_image_input"
        }

        assertTrue(descriptor.requiresLocalModel)
        assertFalse(descriptor.remoteEligible)
        assertEquals(ConfirmationPolicy.NotRequired, descriptor.confirmationPolicy)
        assertTrue(descriptor.failureBehavior.contains("已验证"))
        assertTrue(descriptor.failureBehavior.contains("8 MB"))
        assertTrue(descriptor.failureBehavior.contains("不强制 OCR"))
    }

    @Test
    fun remoteVisionImageInputRequiresRemoteSendConfirmation() {
        val descriptor = CapabilityMatrix.productDescriptors.single {
            it.capabilityId == "remote_vision_image_input"
        }

        assertEquals(ConfirmationPolicy.Required, descriptor.confirmationPolicy)
        assertTrue(descriptor.failureBehavior.contains("远程发送预览确认"))
        assertTrue(descriptor.failureBehavior.contains("不支持图片"))
        assertTrue(descriptor.failureBehavior.contains("不强制 OCR"))
    }

    @Test
    fun sensitiveCapabilityDisclosuresMatchJsonAndCoverUserRiskBoundaries() {
        val json = JSONObject(readRepoFile("docs/capability_matrix.json"))
        val documented = json.getJSONArray("sensitiveCapabilityDisclosures")
        val requiredDisclosureIds = listOf(
            "remote_model_send",
            "voice_transcript_input",
            "share_and_file_picker_input",
            "confirmed_device_actions",
            "contacts_calendar_reads",
            "media_and_recent_ocr",
            "usage_stats_foreground_app",
            "accessibility_current_screen_text",
            "media_projection_screenshot_ocr",
        )

        assertEquals(CapabilityMatrix.sensitiveCapabilityDisclosures.size, documented.length())
        assertEquals(
            requiredDisclosureIds,
            CapabilityMatrix.sensitiveCapabilityDisclosures.map { disclosure -> disclosure.capabilityId },
        )
        CapabilityMatrix.sensitiveCapabilityDisclosures.forEachIndexed { index, disclosure ->
            val item = documented.getJSONObject(index)
            assertEquals(disclosure.capabilityId, item.getString("capabilityId"))
            assertEquals(disclosure.displayName, item.getString("displayName"))
            assertEquals(disclosure.dataAccessed, item.getString("dataAccessed"))
            assertEquals(disclosure.consentBoundary, item.getString("consentBoundary"))
            assertEquals(disclosure.remoteBoundary, item.getString("remoteBoundary"))
            assertEquals(disclosure.revokeOrClearControl, item.getString("revokeOrClearControl"))
            assertEquals(disclosure.requiredTests, item.getStringList("requiredTests"))
            assertTrue(disclosure.dataAccessed.isNotBlank())
            assertTrue(disclosure.consentBoundary.contains("确认") || disclosure.consentBoundary.contains("系统"))
            assertTrue(disclosure.remoteBoundary.contains("远程"))
            assertFalse(disclosure.revokeOrClearControl.contains("清理本地审计"))
            assertFalse(disclosure.revokeOrClearControl.contains("删除审计"))
            assertTrue(
                disclosure.revokeOrClearControl.contains("取消") ||
                    disclosure.revokeOrClearControl.contains("撤销") ||
                    disclosure.revokeOrClearControl.contains("清除") ||
                    disclosure.revokeOrClearControl.contains("删除"),
            )
            assertFalse(disclosure.requiredTests.isEmpty())
        }
        val voice = CapabilityMatrix.sensitiveCapabilityDisclosures.single {
            it.capabilityId == "voice_transcript_input"
        }
        assertTrue(voice.dataAccessed.contains("麦克风音频"))
        assertTrue(voice.dataAccessed.contains("Android 系统语音识别"))
        assertTrue(voice.dataAccessed.contains("不保存音频文件"))
        assertTrue(voice.consentBoundary.contains("App 内同意"))
        assertTrue(voice.consentBoundary.contains("确认后才请求麦克风权限"))
        val action = CapabilityMatrix.sensitiveCapabilityDisclosures.single {
            it.capabilityId == "confirmed_device_actions"
        }
        assertTrue(action.consentBoundary.contains("必须先确认"))
        assertTrue(action.consentBoundary.contains("低风险 App 搜索"))
        assertTrue(action.remoteBoundary.contains("LocalOnly"))
        assertTrue(action.dataAccessed.contains("外部打开结果"))
    }

    @Test
    fun derivedToolDescriptorsHaveOwnersTestsAndStableToolCoverage() {
        val json = JSONObject(readRepoFile("docs/capability_matrix.json"))
        val documented = json.getJSONArray("toolCapabilities")
        val registry = ToolRegistry()
        val descriptors = CapabilityMatrix.toolDescriptors(registry)
        val documentedToolNames = (0 until documented.length()).map { index ->
            documented.getJSONObject(index).getString("toolName")
        }

        assertEquals(registry.specs().map { it.name }.toSet(), descriptors.mapNotNull { it.toolName }.toSet())
        assertEquals(registry.specs().map { it.name }, documentedToolNames)
        descriptors.forEach { descriptor ->
            assertTrue(descriptor.capabilityId.startsWith("tool_"))
            assertFalse(descriptor.requiredTests.isEmpty())
            assertTrue(descriptor.failureBehavior.isNotBlank())
        }
        descriptors.forEachIndexed { index, descriptor ->
            val item = documented.getJSONObject(index)
            assertDescriptorMatchesJson(descriptor, item)
        }
    }

    @Test
    fun uiActionToolsRemainRequiredConfirmationInToolSpec() {
        val registry = ToolRegistry()
        val uiToolNames = listOf(
            "ui_tap",
            "ui_type_text",
            "ui_submit_search",
            "ui_scroll",
            "ui_press_back",
            "ui_wait",
        )

        uiToolNames.forEach { toolName ->
            val spec = registry.specs().single { candidate -> candidate.name == toolName }
            assertEquals("$toolName must stay confirmation-required at ToolSpec level", ConfirmationPolicy.Required, spec.confirmationPolicy)
        }

        val deviceDescriptor = CapabilityMatrix.productDescriptors.single {
            descriptor -> descriptor.capabilityId == "confirmed_device_tools"
        }
        assertTrue(deviceDescriptor.failureBehavior.contains("低风险"))
        assertTrue(deviceDescriptor.failureBehavior.contains("仍 fail-closed"))
    }

    @Test
    fun publicEvidenceToolDescriptorsDoNotRequireMobileActionProfile() {
        val descriptors = CapabilityMatrix.toolDescriptors(ToolRegistry()).associateBy { descriptor ->
            descriptor.toolName
        }

        val webSearch = descriptors.getValue("web_search")
        assertEquals(ModelCapability.Chat, webSearch.modelCapability)
        assertEquals(CapabilityPrivacyLevel.PublicEvidence, webSearch.privacyLevel)
        assertEquals(ConfirmationPolicy.NotRequired, webSearch.confirmationPolicy)
        assertTrue(webSearch.remoteEligible)

        val shareText = descriptors.getValue("share_text")
        assertEquals(ModelCapability.MobileAction, shareText.modelCapability)
        assertFalse(shareText.privacyLevel == CapabilityPrivacyLevel.PublicEvidence)
        assertEquals(ConfirmationPolicy.Required, shareText.confirmationPolicy)
    }

    @Test
    fun capabilityRequiredTestsReferenceExistingTestClasses() {
        val testClasses = buildTestClassIndex(repoRoot())
        val missingClasses = CapabilityMatrix.allDescriptors()
            .flatMap { descriptor -> descriptor.requiredTests }
            .distinct()
            .filterNot { className -> className in testClasses }

        assertTrue(
            "Capability required test classes must exist: $missingClasses",
            missingClasses.isEmpty(),
        )
    }

    @Test
    fun sensitiveDisclosureRequiredTestsReferenceExistingTestClasses() {
        val testClasses = buildTestClassIndex(repoRoot())
        val missingClasses = CapabilityMatrix.sensitiveCapabilityDisclosures
            .flatMap { disclosure -> disclosure.requiredTests }
            .distinct()
            .filterNot { className -> className in testClasses }

        assertTrue(
            "Sensitive disclosure required test classes must exist: $missingClasses",
            missingClasses.isEmpty(),
        )
    }

    @Test
    fun sensitiveDisclosuresHaveExplicitUiAndTestAnchors() {
        val anchorsByCapability = mapOf(
            "remote_model_send" to listOf(
                SourceAnchor(
                    "app/src/main/java/com/bytedance/zgx/solin/ui/components/RemoteSendDisclosureSheet.kt",
                    "remote_send_disclosure_sheet",
                ),
                SourceAnchor("app/src/test/java/com/bytedance/zgx/solin/ui/SolinScreenDisplayTest.kt", "remoteSendDisclosureRowsNameDestinationAndProtectedData"),
            ),
            "voice_transcript_input" to listOf(
                SourceAnchor("app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt", "voice_permission_disclosure_dialog"),
                SourceAnchor("app/src/androidTest/java/com/bytedance/zgx/solin/SolinVoiceInputConsentUiTest.kt", "voiceButtonRequiresAppConsentBeforeStartingVoiceInput"),
                SourceAnchor("app/src/androidTest/java/com/bytedance/zgx/solin/MainActivityVoicePermissionUiTest.kt", "voiceConsentThenDenyMicrophonePermissionShowsFailureAndKeepsRecoveryEntry"),
            ),
            "share_and_file_picker_input" to listOf(
                SourceAnchor("app/src/main/java/com/bytedance/zgx/solin/ui/SolinScreen.kt", "remote_attachment_protection_notice"),
                SourceAnchor("app/src/androidTest/java/com/bytedance/zgx/solin/MainActivityAdaptiveUiTest.kt", "remote_attachment_protection_notice"),
            ),
            "confirmed_device_actions" to listOf(
                SourceAnchor(
                    "app/src/main/java/com/bytedance/zgx/solin/ui/components/ActionDraftSheet.kt",
                    "action_confirm_button",
                ),
                SourceAnchor("app/src/androidTest/java/com/bytedance/zgx/solin/MainActivitySkillUiTest.kt", "action_dismiss_button"),
            ),
            "contacts_calendar_reads" to listOf(
                SourceAnchor("app/src/androidTest/java/com/bytedance/zgx/solin/MainActivityRuntimePermissionUiTest.kt", "contactLookupConfirmationShowsRuntimePermissionRequirementWithoutSpecialAccess"),
                SourceAnchor("app/src/androidTest/java/com/bytedance/zgx/solin/MainActivityRuntimePermissionUiTest.kt", "calendarAvailabilityConfirmationShowsRuntimePermissionRequirementWithoutSpecialAccess"),
            ),
            "media_and_recent_ocr" to listOf(
                SourceAnchor("app/src/androidTest/java/com/bytedance/zgx/solin/MainActivityRuntimePermissionUiTest.kt", "recentImageOcrConfirmationShowsBoundedImageReadRationaleAndCancelsCleanly"),
                SourceAnchor("app/src/androidTest/java/com/bytedance/zgx/solin/MainActivityRuntimePermissionUiTest.kt", "recentImageFilesConfirmationShowsMetadataOnlyRationaleAndCancelsCleanly"),
            ),
            "usage_stats_foreground_app" to listOf(
                SourceAnchor("app/src/androidTest/java/com/bytedance/zgx/solin/MainActivitySpecialAccessUiTest.kt", "foregroundAppConfirmationShowsUsageAccessRequirementWithoutRuntimePermission"),
                SourceAnchor(
                    "app/src/main/java/com/bytedance/zgx/solin/ui/components/ActionDraftSheet.kt",
                    "special_access_requirements",
                ),
            ),
            "accessibility_current_screen_text" to listOf(
                SourceAnchor("app/src/androidTest/java/com/bytedance/zgx/solin/MainActivitySpecialAccessUiTest.kt", "currentScreenTextConfirmationShowsSpecialAccessRequirementWithoutRuntimePermission"),
                SourceAnchor(
                    "app/src/main/java/com/bytedance/zgx/solin/ui/components/ActionDraftSheet.kt",
                    "special_access_requirements",
                ),
            ),
            "media_projection_screenshot_ocr" to listOf(
                SourceAnchor("app/src/androidTest/java/com/bytedance/zgx/solin/MainActivitySkillUiTest.kt", "currentScreenshotOcrSkillShowsOneShotMediaProjectionConfirmation"),
                SourceAnchor("app/src/androidTest/java/com/bytedance/zgx/solin/MainActivitySkillUiTest.kt", "MediaProjection"),
            ),
        )
        val disclosureIds = CapabilityMatrix.sensitiveCapabilityDisclosures
            .map { disclosure -> disclosure.capabilityId }
            .toSet()

        assertEquals(disclosureIds, anchorsByCapability.keys)
        anchorsByCapability.forEach { (capabilityId, anchors) ->
            val disclosure = CapabilityMatrix.sensitiveCapabilityDisclosures.single {
                it.capabilityId == capabilityId
            }
            anchors.forEach { anchor ->
                val source = readRepoFile(anchor.path)
                assertTrue(
                    "$capabilityId missing anchor ${anchor.text} in ${anchor.path}",
                    source.contains(anchor.text),
                )
                val testClass = anchor.testClassNameOrNull()
                if (testClass != null) {
                    assertTrue(
                        "$capabilityId anchor ${anchor.path} is not listed in requiredTests",
                        testClass in disclosure.requiredTests,
                    )
                }
            }
        }
    }

    private fun readRepoFile(path: String): String =
        File(repoRoot(), path).also { file ->
            assertTrue("missing ${file.path}", file.isFile)
        }.readText()

    private fun assertDescriptorMatchesJson(
        descriptor: com.bytedance.zgx.solin.capability.CapabilityDescriptor,
        item: JSONObject,
    ) {
        assertEquals(descriptor.capabilityId, item.getString("capabilityId"))
        assertEquals(descriptor.entrypoint, item.getString("entrypoint"))
        assertEquals(descriptor.toolName, item.nullableString("toolName"))
        assertEquals(descriptor.modelCapability?.name, item.nullableString("modelCapability"))
        assertEquals(descriptor.privacyLevel.name, item.getString("privacyLevel"))
        assertEquals(descriptor.requiresLocalModel, item.getBoolean("requiresLocalModel"))
        assertEquals(descriptor.remoteEligible, item.getBoolean("remoteEligible"))
        assertEquals(descriptor.confirmationPolicy.name, item.getString("confirmationPolicy"))
        assertEquals(descriptor.failureBehavior, item.getString("failureBehavior"))
        assertEquals(descriptor.requiredTests, item.getStringList("requiredTests"))
        assertEquals(descriptor.ownerAgent.name, item.getString("ownerAgent"))
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.getStringList(key: String): List<String> {
        val values = getJSONArray(key)
        return (0 until values.length()).map { index -> values.getString(index) }
    }

    private fun buildTestClassIndex(repoRoot: File): Set<String> =
        listOf(
            File(repoRoot, "app/src/test/java"),
            File(repoRoot, "app/src/androidTest/java"),
        )
            .flatMap { root -> root.walkTopDown().filter { file -> file.extension == "kt" }.toList() }
            .map { file -> file.nameWithoutExtension }
            .toSet()

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { file -> file.parentFile }
            .first { candidate -> File(candidate, "docs/capability_matrix.json").isFile }
            .absoluteFile

    private data class SourceAnchor(
        val path: String,
        val text: String,
    ) {
        fun testClassNameOrNull(): String? =
            path.takeIf { it.endsWith("Test.kt") }
                ?.let { filePath -> File(filePath).nameWithoutExtension }
    }
}
