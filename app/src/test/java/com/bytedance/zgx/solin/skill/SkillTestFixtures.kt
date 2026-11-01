package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolExecutor
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult

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
