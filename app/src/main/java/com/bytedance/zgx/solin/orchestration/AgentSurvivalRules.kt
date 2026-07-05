package com.bytedance.zgx.solin.orchestration

/**
 * Heuristic "agent survival manual" rules that guide the model's mobile UI automation behavior.
 *
 * Inspired by Open-AutoGLM's per-step rulebook, these rules help the agent avoid dead loops,
 * handle edge cases gracefully, and respect safety boundaries. The [SYSTEM_PROMPT_RULES] are
 * soft guidance appended to the system prompt; the hard constants are used by
 * [com.bytedance.zgx.solin.orchestration.DeadLoopDetectionReplanner] as hard guards.
 */
object AgentSurvivalRules {

    /** Maximum identical consecutive actions before forcing a different approach. */
    const val MAX_REPEAT_ACTIONS = 3

    /** Maximum times seeing the same screen before forcing back/navigation. */
    const val MAX_SAME_SCREEN_OBSERVATIONS = 3

    /** Maximum scrolls in the same direction without new content. */
    const val MAX_SCROLL_SAME_DIRECTION = 3

    /** Maximum notes per run before oldest are dropped. */
    const val MAX_NOTES_PER_RUN = 20

    /** Maximum total characters in scratchpad before oldest notes are truncated. */
    const val MAX_SCRATCHPAD_TOTAL_CHARS = 4000

    /**
     * Soft heuristic rules appended to the system prompt for mobile UI automation runs.
     * The model is *instructed* to follow these but may not always comply — the hard
     * guards in [DeadLoopDetectionReplanner] provide the enforcement layer.
     */
    val SYSTEM_PROMPT_RULES: String = """
        |## Agent 操作规则（手机 UI 自动化）
        |
        |在控制手机界面时，请严格遵守以下规则：
        |
        |1. 如果同一个动作连续失败 2 次（如点击同一按钮），请尝试不同的方法（返回、滚动或选择其他目标），不要重复相同操作。
        |2. 如果连续 3 次观察到相同的屏幕内容（没有进展），请按系统返回键或尝试其他路径。
        |3. 绝对不要在没有用户明确确认的情况下点击"支付"、"付款"、"购买"、"确认支付"、"转账"、"发送"等高风险按钮。
        |4. 如果遇到登录页面、验证码、密码输入框或任何需要人工身份验证的界面，请使用 take_over 动作将控制权交还给用户。
        |5. 完成一个子任务后，请在进入下一步之前验证结果（使用 observe_current_screen 确认）。
        |6. 如果页面正在加载中（显示加载动画或空白），请使用 ui_wait 等待，不要反复点击。
        |7. 当不确定当前位置时，优先使用系统返回键（ui_press_back）而不是应用内返回按钮。
        |8. 同一方向的滚动最多连续 3 次，如果还没找到目标内容，请换方向或使用其他方法。
        |9. 如果输入文字失败，请先检查输入框是否确实获得了焦点（可观察屏幕确认）。
        |10. 如果应用崩溃或显示错误对话框，请先关闭它，然后重试一次；如果再次失败则放弃并告知用户。
        |11. 在离开一个页面之前，如果该页面包含后续步骤需要的信息，请使用 note 动作记录下来。
        |12. 当用户的请求已完成时，请使用 finish 动作并附上完成总结，不要只是停止操作。
        |13. 不要重复已经成功完成的动作——在重试之前先检查观察证据确认是否真的需要重做。
        |14. 搜索流程的标准顺序是：点击搜索框 → 输入查询词 → 提交搜索，严格按此顺序执行。
        |15. 如果不确定下一步该做什么，请使用 observe_current_screen 获取更多屏幕信息后再决定。
        |16. 对于 ui_tap 动作，可以通过文本标签（target="按钮文本"）或归一化坐标 0-1000（targetX=500&targetY=800 表示屏幕中下位置）来指定目标。当目标没有可用文本标签或文本定位失败时使用坐标。
        |17. 如果认为某个动作可能涉及敏感操作（如支付、删除、发送、授权），请在工具调用中包含 sensitivityReason 参数来触发用户确认。示例：call:ui_tap{"target":"发送","sensitivityReason":"涉及发送消息给他人"}
        |18. 使用 note{"content":"..."} 记录重要的页面内容或观察结果供后续步骤参考。这对以下情况特别有用：记录搜索结果后再点击进入、记录页面标题、存储验证证据。
    """.trimMargin()
}
