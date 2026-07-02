package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolExecutor
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult

internal fun allowAllSkillToolGate(): SkillToolGate =
    SkillToolGate { _, _ -> SkillToolGateDecision.Allow }

internal fun testDraft(
    toolName: String,
    title: String = toolName,
    summary: String = "$title.",
    parameters: Map<String, String> = emptyMap(),
): ActionDraft =
    ActionDraft(
        functionName = toolName,
        title = title,
        summary = summary,
        parameters = parameters,
    )

internal fun testToolStep(
    id: String,
    toolName: String,
    requestId: String = "$id-request",
    arguments: Map<String, String> = emptyMap(),
    dependsOn: List<String> = emptyList(),
    argumentBindings: Map<String, String> = emptyMap(),
    title: String = toolName,
    summary: String = "$title.",
): SkillStep.ToolStep {
    val draft = testDraft(
        toolName = toolName,
        title = title,
        summary = summary,
        parameters = arguments,
    )
    return SkillStep.ToolStep(
        id = id,
        dependsOn = dependsOn,
        request = ToolRequest(
            id = requestId,
            toolName = toolName,
            arguments = arguments,
        ),
        draft = draft,
        argumentBindings = argumentBindings,
    )
}

internal fun testSkillPlan(
    skillId: String,
    requiredTools: List<String>,
    steps: List<SkillStep>,
    arguments: Map<String, String> = emptyMap(),
    riskLevel: RiskLevel = RiskLevel.MediumDraftOrNavigation,
): SkillPlan =
    SkillPlan(
        request = SkillRequest(
            id = "$skillId-request",
            skillId = skillId,
            arguments = arguments,
            reason = "test",
        ),
        manifest = SkillManifest(
            id = skillId,
            version = 1,
            title = skillId,
            description = "test",
            triggerExamples = emptyList(),
            requiredTools = requiredTools,
            inputSchemaJson = if (arguments.isEmpty()) {
                """{"type":"object","properties":{},"additionalProperties":false}"""
            } else {
                """
                    {
                      "type": "object",
                      "required": ["input"],
                      "properties": {
                        "input": {
                          "type": "string",
                          "minLength": 1
                        }
                      },
                      "additionalProperties": false
                    }
                """.trimIndent()
            },
            riskLevel = riskLevel,
        ),
        steps = steps,
    )

internal fun clipboardSuccess(text: String): Map<String, String> =
    mapOf(
        "toolName" to MobileActionFunctions.READ_CLIPBOARD,
        "privacy" to MessagePrivacy.LocalOnly.name,
        "requiresLocalModel" to "true",
        "text" to text,
        "truncated" to "false",
    )

internal fun currentScreenTextSuccess(text: String): Map<String, String> =
    mapOf(
        "toolName" to MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
        "privacy" to "LocalOnly",
        "requiresLocalModel" to "true",
        "source" to "accessibility_active_window",
        "maxChars" to "1200",
        "capturedAtMillis" to "1000",
        "nodeCount" to "1",
        "screenText" to text,
        "truncated" to "false",
        "screenTextIncluded" to "true",
        "structureSummaryIncluded" to "false",
        "rawTreeIncluded" to "false",
        "metadataPolicy" to "accessibility_text_local_only_no_node_ids_bounds_or_hierarchy_persisted",
    )

internal fun externalActivitySuccess(toolName: String): Map<String, String> =
    mapOf(
        "toolName" to toolName,
        "completionState" to "ExternalActivityOpened",
        "completionVerified" to "false",
        "externalOutcome" to "Unknown",
        "externalOutcomeSource" to "Unknown",
        "targetKind" to "android_chooser",
        "intentAction" to "android.intent.action.SEND",
        "metadataPolicy" to "AllowlistedCompletionMetadata",
        "rawPayloadIncluded" to "false",
    )

internal class RecordingToolExecutor(
    results: List<ToolResult>,
) : ToolExecutor {
    private val remainingResults = ArrayDeque(results)
    val requests = mutableListOf<ToolRequest>()

    override fun execute(request: ToolRequest): ToolResult {
        requests += request
        val result = remainingResults.removeFirst()
        return result.copy(requestId = request.id)
    }
}
