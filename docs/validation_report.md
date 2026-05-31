# PocketMind 验证报告

## 2026-05-31 Reminder rollback metadata 增量验证

本轮覆盖项：

- `schedule_reminder` 成功结果新增受限 recovery metadata：
  `recoveryToolName=cancel_reminder` 和 `recoveryTaskId`，让 Agent/UI/audit
  层能识别该后台任务的回滚工具入口。
- Agent trace 只持久化 recovery tool 名称和 task id，不记录提醒标题、正文或
  其他未 allowlist 的任务内容。
- 现有 `cancel_reminder` 仍是显式工具，需要独立确认和 scheduler 取消路径。
  它只有在仍处于 `Scheduled` 的任务成功取消平台调度并更新本地状态后才返回成功；
  missing、已送达、已取消或本地状态竞态变化会作为 non-retryable stale
  rollback 失败返回，不声称回滚成功。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest.schedulesReminderThroughBackgroundScheduler' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest.reportsStaleReminderCancellationAsNonRetryableInvalidRequest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRemovalCoordinatorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStorePersistsReminderRecoveryMetadataWithoutReminderContent'
```

## 2026-05-31 Generic Skill model-step binding 增量验证

本轮覆盖项：

- Agent loop 不再按 `clipboard_summary_share_skill` 特判模型输出续跑；任意声明式
  Skill 只要 `ToolStep` 依赖 `ModelStep`，就可通过 `argumentBindings` 绑定模型输出并进入下一次确认。
- 绑定出的工具请求仍走 ToolRegistry 校验、SafetyPolicy、trace/audit 和
  `AwaitingUserConfirmation`，不会因为前一个工具已确认而直接执行。
- 缺失的模型输出 binding 会 fail closed；恶意将 `read_clipboard.text` 等私密工具输出直接绑定到
  `share_text.text` 不会产生分享确认，也不会把原始剪贴板写入 trace/audit/pending。
- Orchestrator 恢复第二个待确认动作时保留新的 `share_text` request id 和模型摘要参数，不复用旧
  `read_clipboard` request id。
- Room-backed pending 恢复覆盖到第二个 `share_text` 确认点：重启后旧
  `read_clipboard` request id 不能再次确认、观察或清空 pending，确认新的
  `share_text` request 后可完成 run，持久 trace 不写入原始剪贴板。
- 恢复出的 `share_text` pending 可以包含模型生成的待分享摘要，用于确认卡预览；
  该 payload 不进入普通 trace/audit 摘要，且 ViewModel 在恢复或伪造旧确认时
  不执行工具，只有当前 pending confirmation 才会打开分享面板。
- `SkillRunExecutor` 同步补上私密输出直绑保护：`read_clipboard.text` 等
  private output 可以进入本地 model step，但不能通过 `ToolStep.argumentBindings`
  直接成为后续外发工具参数；违规 plan 会 fail closed，不产生 `share_text`
  pending，也不暴露原文。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.clipboardSummarySharePlansShareAfterLocalModelResult' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepOutputBindsToDependentToolStepAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepBindingRejectsMissingOutputBeforeConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepBindingCannotDirectlyExposePrivateToolOutputToShare' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.compositeSkillIgnoresOldRequestIdsAfterShareIsPendingOrExecuting' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredClipboardSummarySharePendingIgnoresOldReadRequestAndCompletesShare' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest.clipboardSummaryShareAdvancesFromModelOutputToShareConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoredSharePendingPreviewDoesNotExecuteUntilCurrentConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest.privateToolOutputCannotBindDirectlyToLaterToolArgument'
```

## 2026-05-31 Launch-only external result 增量验证

本轮覆盖项：

- `ToolResult` 可识别 `completionState=ExternalActivityOpened` 且
  `completionVerified=false` 的外部界面启动结果。
- Agent observation 对未验证外部启动使用“外部界面已打开，最终结果未验证”文案，
  且不会基于该结果自动规划下一步工具。
- Tool audit 和 ViewModel UI 状态不再把分享面板、草稿页、外部 Activity 启动
  误写成“工具执行成功”。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.unverifiedExternalLaunchDoesNotAutoPlanNextTool' \
  --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditRepositoryTest.unverifiedExternalLaunchAuditDoesNotClaimExecutionSuccess' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.unverifiedExternalLaunchShowsLaunchOnlyStatus'
```

## 2026-05-31 SkillRun cancellation state 增量验证

本轮覆盖项：

- `SkillRunExecutor` 新增显式 `Cancelled` 状态，pending continuation 可以被取消而不是被记录为失败。
- 多步 Skill 取消会停在当前待确认工具前，不调用 `ToolExecutor` 执行后续工具。
- 取消后的公开 outputs/trace 仍过滤私密工具输出，例如已确认读取过的剪贴板原文不会从取消结果泄漏。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest'
```

## 2026-05-31 Reminder Skill-first 增量验证

本轮覆盖项：

- 提醒请求的相对时间解析抽到共享 `ReminderActionParser`，ActionPlanner 和
  Built-in Reminder Skill 共用同一套 `title/body/delayMinutes` 参数边界。
- “提醒我 15 分钟后喝水” / `remind me in 1 hour ...` 可在 action runtime 未分类为
  likely action 时直接由 `BuiltInSkillRuntime.plan(input)` 生成 `schedule_reminder`
  ToolStep。
- 多时间片段时只使用选中的相对延迟作为 `delayMinutes`，不删除标题里的第二个
  时间描述；“15 分钟后是什么意思”这类 timing discussion 不会触发 reminder
  或 calendar 确认。
- Skill-first reminder 仍进入 `AwaitingUserConfirmation`，走 ToolRegistry 校验、
  SafetyPolicy、runtime permission policy 和后续 AlarmManager 执行边界。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstReminderBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstEnglishReminderBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.reminderTimingDiscussionFallsBackToAnswerWithoutConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest'
```

## 2026-05-31 Persisted Trace Summary Rehydration 增量验证

本轮覆盖项：

- `RoomAgentTraceStore.steps()` 在无 live steps 时可从已持久化 trace rows 恢复
  summary-only `AgentStep.RestoredSummary`。
- 恢复出的 step 保留 persisted type、summary 和已脱敏 trace JSON，不恢复
  `ToolRequested` 的原始 arguments，也不会伪装成可继续执行的 pending request。
- pending confirmation 仍走独立 `pending_agent_confirmations` 恢复链路；完成 run 的
  summary-only rehydration 不改变确认/观察执行边界。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest'
```

## 2026-05-31 Usage Access 特殊授权增量验证

本轮覆盖项：

- Usage Access (`PACKAGE_USAGE_STATS`) 被建模为 special app access，不进入
  Android dangerous runtime permission policy，也不展示系统 runtime permission 弹窗。
- 前台 App 摘要工具仍先经过 Agent 确认；未授权或从设置返回后仍未授权时，返回
  结构化权限失败和恢复入口，不执行 provider 读取、不自动重试。
- 授权后的结果保持 `LocalOnly` 最小 metadata；trace/audit 只记录工具名、权限/状态
  和安全摘要，不保存 usage history 或 App 内容。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.AndroidManifestTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.device.ForegroundAppProviderTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest'
```

## 2026-05-31 Runtime Permission 说明与策略一致性增量验证

本轮覆盖项：

- `AgentRuntimePermissionPolicy` 新增 `RuntimePermissionRequirement`，在保留
  raw manifest permission 请求链路的同时，提供确认卡可展示的友好权限名和用途说明。
- 待确认动作 Sheet 会在执行前展示“确认后可能请求系统权限”，覆盖联系人、日历、
  通知、媒体和 legacy storage 等当前可请求 runtime permission。
- 权限拒绝后的结构化 `ToolResult.data` 同时保留 raw `deniedPermissions` 和友好
  `deniedPermissionLabels`；用户文案不再只拼接 `android.permission.*`。
- Registry 的 `RequiresAndroidRuntimePermission` 标记与实际 runtime policy 对齐：
  `query_foreground_app` 和 `cancel_reminder` 不再伪装为 Android dangerous runtime
  permission flow。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.deniedRuntimePermissionFailsPendingToolWithoutExecutingIt' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest'
```

## 2026-05-31 Allowlisted App Deep Target 增量验证

本轮覆盖项：

- 新增 `open_app_deep_target` 工具，参数只接受 allowlisted `targetId` 和该
  target 声明的参数；首个 target 为 `android_app_details_settings`，使用固定
  Android 应用详情设置 action 和 `package:` URI。
- `open_app_intent` 保持 package launcher 语义，不接受 `targetId`、任意
  activity/action/data/extras；应用深层目标和启动页目标分离。
- `ActionExecutor` 对未知 target、额外 URI/action/extras、非法包名在启动外部
  Activity 前拒绝；结果 metadata 只包含 `targetId`、`targetPackage`、completion
  状态和 allowlist policy，不保存 raw URI path/query。
- `MobileActionPlanner` 只在用户明确指定 App/包名和“应用详情设置”时生成
  deep target 草稿；模糊“打开应用详情设置”不自动执行。
- Agent trace 的 `ToolObserved` completion metadata allowlist 新增 `targetId`，
  仍过滤 raw payload。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStorePersistsOnlyAllowlistedToolObservationCompletionMetadata' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.deepLinkAndAppIntentDoNotRequestRuntimePermissions'
```

## 2026-05-31 语义记忆运行时边界增量验证

本轮覆盖项：

- `MemoryRepository` 将默认轻量 token/hash 召回与真正 semantic runtime
  边界拆开：hash runtime 仍要求词项重叠，声明支持 semantic recall 的 runtime
  才能用高分阈值召回无词项重叠命中。
- `MemoryHit` 标记命中来源为 `Lexical` 或 `Semantic`，便于后续接入 LiteRT
  embedding adapter 后验证真实语义召回路径。
- 模型管理高级页的本地记忆开关不再绑定 memory model asset 安装状态；文案明确
  当前使用本地轻量索引，安装 asset 不等于 embedding runtime 参与检索。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest.injectsMemoryContextWhenMemoryIsEnabledWithoutRequiringEmbeddingModel' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeCompatibilityTest.memoryContextRemainsCompatibleWithoutEmbeddingCapability'

./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin
git diff --check
```

## 2026-05-31 最近 Agent 轨迹摘要增量验证

本轮覆盖项：

- `AgentTraceStore` 新增 recent run summary 读取能力，按 run 更新时间倒序返回
  持久化 `AgentTraceStepSummary` 摘要，并限制每个 run 展示的 step 数。
- `AssistantRouter` / ViewModel 将最近 Agent 轨迹加载到 UI state；读取异常降级为空
  列表，不阻断后台活动面板。
- 后台活动面板新增只读“最近 Agent 轨迹”区域，仅展示 run id 后缀、状态和
  step type/summary，不展示 trace JSON、工具参数、完整 prompt 或原始私密内容。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.refreshAuditEventsAlsoLoadsAgentTraceSummaries'
```

## 2026-05-31 周期检查策略 UX 增量验证

本轮覆盖项：

- 后台任务面板新增周期检查策略区域，展示 enabled、interval、min
  notification spacing、overdue grace、battery constraints、next allowed check、
  task status 和 latest run summary。
- `BackgroundTaskScheduler` 暴露 typed periodic check policy summary；ViewModel
  保存或关闭策略后刷新运行中任务、最近历史和策略状态。
- `ScheduledTaskRepository.recordPeriodicCheckRun()` 保留已保存策略字段，只追加
  latest run summary，避免 Worker 跑完后 UI 读不回用户策略。
- 关闭策略成功后 `periodic-check-local` 进入 `Cancelled` 历史；关闭失败时保留原
  running 状态并显示失败提示。
- 周期检查 UI 只管理本地提醒巡检策略，不执行聊天任务、不读取远程内容、不绕过通知
  权限和 WorkManager 约束。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.setPeriodicCheckPolicySchedulesDefaultPolicyAndRefreshesUi' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.setPeriodicCheckPolicyFailureDoesNotShowHealthyRunningTask' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.disablePeriodicCheckPolicyMovesTaskToHistory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.disablePeriodicCheckPolicyFailureKeepsRunningTaskVisible' \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest'
```

## 2026-05-31 最近截图元数据查询增量验证

本轮覆盖项：

- `query_recent_files` 新增 `screenshots` kind，仅作为 recent image metadata
  的筛选条件；Android 13+ 权限映射到 `READ_MEDIA_IMAGES`，Android 12- 仍使用
  legacy storage permission。
- Android provider 将 `screenshots` 限制在 `image/*`，并按文件名或截图目录
  特征筛选；返回的 `RecentFileItem.kind` 标记为 `screenshots`。
- Tool result 仍为 `LocalOnly`，且 `filesJson` 只包含 `name`、`mimeType`、
  `kind`、`sizeBytes`、`lastModifiedMillis`，不返回 MediaStore id、路径、
  URI、文件内容、像素或 OCR 文本。
- Planner 覆盖“最近截图”/`recent screenshots` 到
  `query_recent_files(kind="screenshots")` 的路由，不声明当前屏幕理解能力。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest'
```

## 2026-05-31 后台任务历史查看增量验证

本轮覆盖项：

- `ScheduledTaskRepository` 新增最近任务查询，按 `updatedAtMillis`
  倒序返回全部状态，用于历史视图而不影响运行中任务查询。
- ViewModel 启动、刷新和取消后台任务后同时刷新运行中列表与最近历史；运行中
  只保留 `Scheduled`，历史只展示 `Delivered` / `Cancelled` / `Deleted` /
  `Failed`。
- 后台任务面板新增只读“最近后台任务”区域；已结束任务不会显示取消按钮，
  不会被误当成仍在运行。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRemovalCoordinatorTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ReminderAlarmReceiverTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateLoadsRunningBackgroundTasksWithoutRemoteWork' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.cancelRunningBackgroundTaskRefreshesUiAndCancelsScheduler' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.cancelRunningBackgroundTaskFailureKeepsTaskVisible'
```

## 2026-05-31 记忆兜底与显式偏好持久化增量验证

本轮覆盖项：

- `memoryIndex.search` 抛异常时降级为空 `memoryHits`，普通聊天继续生成
  Answer 计划，trace 记录空记忆上下文。
- 长期记忆 store 读取或重建失败时不阻断启动、恢复或远程聊天；长期记忆
  列表降级为空。
- 用户发送 `记住：...` / `remember ...` 时，显式偏好在 `sendMessage`
  生产路径的消息落会话后持久化为 Preference 记录；route 失败且消息未保存时
  不留下孤儿长期记忆。
- 同一规范化偏好文本使用确定性 id/upsert，重复发送同一句 remember 命令
  不产生重复长期记忆。
- 遗忘显式偏好后，`rebuild` 不会从历史 remember 控制消息重新派生同一偏好。
- CJK 召回收紧为多字符 token 优先匹配，避免 `简洁回答` 被 `远程回复` 的
  单字重叠误命中。
- 当前默认本地记忆检索仍是轻量 token/hash 索引；`MemoryRepository`
  保留真正 semantic runtime 接入点，语义命中可跳过词项重叠过滤，但 LiteRT
  embedding adapter 尚未接入。
- 安装或补装 memory model asset 本身不代表 embedding runtime 已参与检索。
- `memoryIndex.buildContext` 异常时也降级为空记忆块，设备上下文仍可进入 prompt。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'
```

## 2026-05-31 外部 Activity 完成语义 Metadata 增量验证

本轮覆盖项：

- 外部 Activity/Intent 工具成功结果不再只表达“完成”，而是显式标记
  `completionState=ExternalActivityOpened`、`completionVerified=false` 和
  `externalOutcome=Unknown`，说明当前只验证外部页面/chooser 已打开。
- `ActivityNotFoundException` 仍返回 `NoActivityFound`；其他启动异常返回
  `ExecutionFailed`，并带 `completionState=NotStarted` 与 `exceptionType`。
- `share_text`、深链、package launcher 和 app deep target 结果只输出 allowlisted metadata；
  不把分享文本、URI path/query 等 raw payload 写入 `ToolResult.data`。
- Agent trace 的 `ToolObserved` 只持久化 completion metadata allowlist，
  不保存 raw payload；`open_app_intent` 描述与 package-only schema 对齐，深层目标
  使用单独 `open_app_deep_target` schema。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest'
```

结果：targeted JVM 外部 Activity completion metadata 测试通过。

## 2026-05-31 Pending Confirmation 恢复回归增量验证

本轮覆盖项：

- Room 恢复出的 pending confirmation 可以再次确认、执行结果 observe，并清除
  pending snapshot。
- 恢复带 `clipboard_summary_share_skill` 的 pending 后，确认读取剪贴板可继续
  到本地模型续写，再规划第二个 `share_text` 待确认。
- ViewModel 启动恢复出的 pending confirmation 重复点击确认也只执行/observe
  一次。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'
```

结果：targeted JVM pending confirmation 恢复回归测试通过。

## 2026-05-31 后台任务运行态生命周期增量验证

本轮覆盖项：

- 周期检查 Worker 执行前将 `periodic-check-local` 标记为 `Running`；
  成功扫描后回到 `Scheduled`，执行异常时标记为 `Failed`。
- Worker 层异常兜底会再次尝试把周期检查任务标记为 `Failed`，避免
  WorkManager failure 后本地状态仍显示为健康 scheduled。
- 提醒 alarm 回调通过 `ReminderAlarmDeliveryHandler` 先进入 `Running`；
  通知投递成功标记为 `Delivered`，通知被阻止或抛异常标记为 `Failed`。
- `schedule_reminder` / `cancel_reminder` 工具结果增加 `taskStatus`
  metadata，便于 Agent observation 和调试确认任务状态。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ReminderAlarmReceiverTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest'
```

结果：targeted JVM 后台生命周期与提醒工具结果测试通过。

## 2026-05-31 Skill 执行恢复与输入契约增量验证

本轮覆盖项：

- `SkillPlan.validateStructure()` 会校验 `SkillRequest.skillId` 与 manifest
  一致，并按 `SkillManifest.inputSchemaJson` 校验 Skill 输入。
- 内置 Skill 的 `SkillRequest.arguments` 收敛为 `{ "input": 原始用户输入 }`；
  工具参数继续保留在 `ToolRequest.arguments` / `ActionDraft.parameters`，由
  Tool Registry 单独校验。
- 缺失 required、空白 required string、额外字段、类型错误、enum、pattern 和
  数值范围错误会让 Skill plan 在确认或执行前拒绝。
- `SkillRunExecutor` 在非法 Skill 输入时不会执行工具或模型步骤。
- `SkillRunExecutor` 在需要用户确认的 tool step 处返回
  `SkillRunContinuation`；确认后的 `ToolResult` 可从该 step 继续执行后续
  model/tool step，不会重跑已完成步骤。
- 多确认 Skill 可在第二个确认点再次停下；错误 `requestId` 的结果会被拒绝，
  不进入后续 model step。
- action-planner 附加的 Skill plan 与 observation replan 附加的 Skill plan
  也必须通过 manifest 输入契约，不能只靠 tool registry 校验。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.actionPlannerAttachedSkillPlanMustSatisfyManifestSchemaBeforeConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.replannedToolAttachedSkillPlanMustSatisfyManifestSchemaBeforeConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'
```

结果：targeted JVM Skill manifest contract 测试通过；完整 JVM 单测、Debug
构建、AndroidTest 构建和 lint 通过；`git diff --check` 和敏感扫描通过；当前环境缺少
`adb`，未执行设备列表与模拟器回归。

## 2026-05-31 Runtime 权限拒绝执行边界增量验证

本轮覆盖项：

- Android runtime permission 回调会检查实际 grant result；权限仍缺失时不再
  调用 `confirmAgentConfirmation`。
- runtime permission 被拒后，ViewModel 通过 `failPendingToolRequest` 把
  `PermissionDenied` 作为 Agent observation 回写，清除 pending
  confirmation，且不执行工具。
- Agent loop 支持 pending confirmation 阶段的 pre-execution failure
  observation，用于记录系统权限拒绝等“未开始执行”的失败。
- `PermissionDenied` 失败不触发自动 retry，即使低层结果误标为
  `retryable=true`。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.deniedRuntimePermissionFailsPendingToolWithoutExecutingIt' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.permissionDeniedToolFailureDoesNotScheduleAutomaticRetry' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.pendingToolPermissionDenialIsObservedWithoutEnteringExecutionState'
```

结果：targeted runtime permission denial 边界测试通过；完整 JVM 单测、Debug
构建、AndroidTest 构建和 lint 通过；`git diff --check` 和敏感扫描通过；当前环境缺少
`adb`，未执行设备列表与模拟器回归。

## 2026-05-31 JVM 工具执行矩阵增量验证

本轮覆盖项：

- `RoutingToolExecutor` 正确分发设备上下文工具和普通外部动作工具。
- `ValidatingToolExecutor` 在 delegate 前拒绝未知工具、缺参、错参。
- delegate 异常会包装为 retryable `ExecutionFailed`，并保留 `toolName`
  context。
- foreground app、notification summary、contact summary、calendar
  availability、recent files executor 覆盖 success、permission denied、provider
  failure 和 wrong-tool 分支。
- 设备上下文工具结果保持 `LocalOnly`、最小字段、结构化 error code，不泄露
  path、URI、通知正文、剪贴板或 API key。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.CalendarAvailabilityToolExecutorTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
adb devices -l
```

结果：targeted 工具执行矩阵测试通过；完整 JVM/构建/lint 回归通过；
`git diff --check` 和敏感扫描通过；当前环境缺少 `adb`，未执行设备列表与模拟器回归。

## 2026-05-31 Skill-first 剪贴板 Skill 路由增量验证

本轮覆盖项：

- `SkillRuntime` 增加可选 Skill-first planner，用于无需 action-planner
  参数抽取的明确 Skill 请求。
- 内置 Skill runtime 可直接把“读取剪贴板”和“总结剪贴板并分享”规划为
  SkillPlan。
- Agent loop 在 action planner 前尝试 Skill-first；“总结剪贴板并分享”即使
  `ActionPlanningRuntime.isLikelyAction=false` 也会进入首个 `read_clipboard`
  确认。
- Skill-first 仍走 Tool Registry 校验、SafetyPolicy、trace、audit 和
  pending confirmation，不直接执行工具。
- 邮件、日程、路线、提醒等需要结构化参数的 Skill 仍依赖 action planner
  抽取参数。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
adb devices -l
```

结果：

- 通过：targeted `BuiltInSkillRuntimeTest`、`AgentLoopRuntimeTest` 和
  `AssistantOrchestratorTest`。
- 通过：完整 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`。
- 通过：`git diff --check`。
- 通过：敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 App 内附件选择入口增量验证

本轮覆盖项：

- Composer 新增附件按钮，调用 Android 系统文档选择器。
- 用户主动选择的 text/image/audio/video/PDF/Office 文件复用 `SharedInput`
  入口；`text/*` 仍只生成有界本地文本摘录。
- 图片、音频、视频、PDF、Office 和其他非文本选择结果保持 metadata-only，
  不读取正文、像素或二进制内容。
- 自动生成的 picked/shared input 仍标记为 `LocalOnly`，远程模式不自动上传。
- 系统选择器入口可被 instrumentation smoke test 发现。

验证命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
adb devices -l
```

结果：

- 通过：完整 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`。
- 通过：`git diff --check`。
- 通过：敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 共享 text/* 文档摘录边界增量验证

本轮覆盖项：

- Shared text 和 `text/*` documents 可以产生用户可见、有界、本地文本摘录。
- 自动生成的 shared-input 文本摘录和附件元数据标记为 `LocalOnly`，只用于本地
  shared-input prompt。
- 二进制、图片、音频、视频、PDF、Office 和其他非文本附件保持
  metadata-only，不读取正文或二进制内容。
- 远程模式不会自动上传 shared-input 文本、文本摘录或附件元数据；用户必须手动
  粘贴愿意发送的内容。
- 完整文档解析、OCR、Office/PDF 解析和媒体内容理解仍待实现。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
```

结果：

- 通过：targeted `SharedInputTest` 与 `PocketMindViewModelTest`。
- 通过：完整 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`。
- 通过：`:app:lintDebug`。
- 通过：`git diff --check`。
- 通过：敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 语音输入入口增量验证

本轮覆盖项：

- Composer 新增语音输入按钮，调用 Android 系统语音识别。
- 识别文本作为一次性草稿回填输入框；未点击发送前不创建用户消息、
  不进入聊天路由，也不触发本地或远程模型。
- 语音入口不读取音频文件内容；音频分享入口仍保持 metadata-only 边界。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 严格敏感信息扫描未发现 OpenAI-style API Key、DeepSeek URL/model 或真实
  Authorization Bearer token 被写入文件。
- 当前 shell 中 `adb` 不在 PATH，因此本轮未执行连接设备/模拟器语音入口回归。

## 2026-05-31 最近审计日志可查看 UI 增量验证

本轮覆盖项：

- “后台任务”入口补充最近持久化工具审计事件查看能力，便于在真机上核对
  Agent 计划、确认、拒绝、取消、观察等工具审计链路。
- 审计列表保持 metadata-only：只展示时间、事件类型、工具名、状态、风险、
  权限和不含参数的安全摘要。
- UI 不展示工具参数、prompt、远程响应、剪贴板原文、Authorization 或 API
  Key；剪贴板和外部服务相关事件只能看到安全摘要。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditEventTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 严格敏感信息扫描未发现 OpenAI-style API Key、DeepSeek URL/model 或真实
  Authorization Bearer token 被写入文件。
- 当前 shell 中 `adb` 不在 PATH，因此本轮未执行连接设备/模拟器回归。

## 2026-05-31 运行中后台任务查看/取消 UI 增量验证

本轮覆盖项：

- `BackgroundTaskScheduler` 新增运行中任务读取与 type-aware 取消边界；
  reminder 取消会撤销 AlarmManager `PendingIntent`，periodic check 取消会撤销
  WorkManager unique work。
- `ScheduledTaskRepository` 可区分 `Scheduled`、`Running`、`Delivered`、
  `Cancelled`、`Deleted` 和 `Failed`，并只把仍处于 `Scheduled` 的任务暴露给
  当前 UI 的运行中列表。
- `ActionExecutor` 补齐 `cancel_reminder` 执行分支，Tool Registry 中已注册的
  取消提醒工具现在能返回结构化取消结果或失败原因。
- `PocketMindViewModel` 新增运行中后台任务状态、刷新和取消事件；启动时读取
  活跃任务，不展示历史完成/失败/取消记录，取消失败时保留原任务并显示失败提示。
- UI 新增“后台任务”入口，显示任务标题、触发/检查时间、状态和取消入口；空
  列表显示“暂无运行中的后台任务”。
- 该 UI 只管理已确认创建的后台任务，不绕过 `schedule_reminder` 的 Agent 确认
  和 Android 通知权限链路。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 严格敏感信息扫描未发现 OpenAI-style API Key、DeepSeek URL/model 或真实
  Authorization Bearer token 被写入文件。
- 当前 shell 中 `adb` 不在 PATH，因此本轮未执行连接设备/模拟器回归。

## 2026-05-31 长期记忆查看/遗忘控制增量验证

本轮覆盖项：

- `MemoryRepository` 新增已保存长期记忆读取边界，只列出显式持久化的偏好
  与任务状态记录，不把普通会话索引或历史 `记住：...` 临时抽取项展示为
  长期记忆。
- `PocketMindViewModel` 新增长期记忆状态流和单条遗忘/清空事件；遗忘后会
  同步刷新 UI、内存索引和 Room 记录，清空长期记忆不会删除聊天会话。
- “模型管理 > 高级 > 本地记忆”现在可查看已保存长期记忆、单条遗忘，并通过
  二次确认清空显式长期记忆记录。
- 远程模式下查看、遗忘或清空本地长期记忆不发起远程模型请求，也不会上传
  记忆内容。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 严格敏感信息扫描未发现 OpenAI-style API Key、DeepSeek URL/model 或真实
  Authorization Bearer token 被写入文件。
- 当前 shell 中 `adb` 不在 PATH，因此本轮未执行连接设备/模拟器回归。

## 2026-05-31 Deep Link / App Intent 执行边界增量验证

本轮覆盖项：

- `open_deep_link` 从已注册/可规划补齐到确认后执行；执行边界仅允许安全
  `https://` 链接，并拒绝 `http`、`file`、`content`、`javascript`、自定义
  scheme、带 user info 或超长 URI。
- `open_app_intent` 从已注册/可规划补齐到确认后执行；当前只支持
  `packageName` 打开应用启动页，不暴露任意 activity/action/data/extras。
- `ActionPlanningRuntime` prompt、`MobileActionPlanner`、`ToolRegistry` schema、
  `ActionExecutor` 和 runtime permission policy 均同步到这个收敛后的安全边界。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 严格敏感信息扫描未发现 OpenAI-style API Key、DeepSeek URL/model 或真实
  Authorization Bearer token 被写入文件。
- 当前 shell 中 `adb` 不在 PATH，因此本轮未执行连接设备/模拟器回归。

## 2026-05-31 Pending Agent Confirmation Recovery 增量验证

本轮覆盖项：

- 新增 `pending_agent_confirmations` Room 表，用于保存最新待确认工具请求的
  恢复快照；普通 Agent trace/audit 仍不写入原始工具参数。
- `RoomAgentTraceStore` 重启后只恢复仍处于 `AwaitingUserConfirmation` 的
  pending run，并跳过/清理 stale pending；查询顺序加入稳定 tie-breaker。
- 恢复会补回确认所需的 typed live steps，包括多步骤 skill 的
  `SkillPlanned`，保证 “总结剪贴板并分享” 这类后续模型观察还能继续规划
  第二个待确认工具。
- `PocketMindViewModel.restoreStartupState` 只恢复 UI confirmation state，
  不执行工具，也不触发 Android runtime permission；新消息仍会被待确认动作
  拦截。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- `stash@{0}` 的 recent-files 候选实现未直接应用；当前主线已覆盖其核心能力，
  且补强了编译、权限请求和最小化返回字段。
- 当前 shell 中 `adb` 不在 PATH，因此本轮未执行连接设备/模拟器回归。

## 2026-05-31 Runtime Permission Request 增量验证

本轮覆盖项：

- 新增 `AgentRuntimePermissionPolicy`，把已确认的 `PendingAgentConfirmation`
  映射到具体 Android runtime permission。
- `MainActivity` 改为统一使用 `RequestMultiplePermissions`：用户先确认 Agent
  工具请求，再触发 Android 权限弹窗；权限返回后用同一个 confirmation 继续执行。
- 补齐 `READ_CONTACTS` manifest 声明；保留 provider/executor 的最终权限检查，
  denied 仍走结构化 `PermissionDenied` tool result。
- 权限映射覆盖提醒通知、日历忙闲、联系人查询和最近文件媒体权限；Android 13+
  的最近文件 `kind` 会映射到最小媒体权限，非媒体文件不伪装成可请求 runtime
  permission。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.CalendarAvailabilityToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 严格敏感信息扫描未发现 OpenAI-style API Key、DeepSeek URL/model 或真实
  Authorization Bearer token 被写入文件。
- 当前 shell 中 `adb` 不在 PATH，因此本轮未执行连接设备/模拟器回归。

## 2026-05-31 Recent Files 设备上下文工具增量验证

本轮覆盖项：

- 从 `query_recent_files` stash 候选实现中提取可用部分，并按当前主线分层
  重新接入 `ToolRegistry`、`RoutingToolExecutor`、`MobileActionPlanner` 和
  `PocketMindAppContainer`。
- 新增 `ReadsFiles` 权限声明与 Android 文件读取权限 manifest 声明；工具仍需
  用户确认，并把结果标记为 `LocalOnly`。
- `RecentFilesToolExecutor` 只返回文件名、MIME、粗粒度 kind、大小和修改时间，
  不返回 MediaStore id、路径、URI 或文件内容。
- `AndroidRecentFileProvider` 在 Android 13+ 下只查询已授权的媒体类型；
  非媒体文件类型在缺少系统文件选择器授权时返回结构化 `PermissionDenied`。
- Planner 覆盖 “查询最近5个图片文件列表” 和 “最近 3 张图片” 这类中文路由。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.CalendarAvailabilityToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 严格敏感信息扫描未发现 OpenAI-style API Key、DeepSeek URL/model 或真实
  Authorization Bearer token 被写入文件。
- 当前 shell 中 `adb` 不在 PATH，因此本轮未执行连接设备/模拟器回归。

## 2026-05-31 长期记忆持久化增量验证

本轮覆盖项：

- 新增 `memory_records` Room 表和 `4 -> 5` 数据库迁移，用于保存显式用户偏好
  与任务状态记忆。
- `MemoryRepository` 会把 `indexPreference` / `indexTaskState` 写入持久化
  store，并在 `rebuild` 时重新载入；普通会话记忆仍从已保存聊天消息重建，避免
  重复写入长期记忆表。
- `forget(id)` 同时删除内存索引和持久化记录，`clear()` 清空长期记忆记录。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest'
```

结果：通过。

## 2026-05-31 Clipboard Summary Share Skill 接入增量验证

本轮覆盖项：

- `clipboard_summary_share_skill` 不再只停留在独立 Skill contract；输入
  “总结剪贴板并分享”会先规划受确认保护的 `read_clipboard`。
- 剪贴板 observe 成功后仍优先进入本地模型续写，不调用普通 observation
  replanner。
- 本地模型生成摘要后，Agent loop 绑定摘要到 `share_text.text`，回到
  `AwaitingUserConfirmation` 等待第二次确认；不会直接打开分享面板。
- Agent loop 只接受当前 pending / confirmed request id，避免旧
  `read_clipboard` request 在第二步确认或执行阶段被重复确认/观察。
- ViewModel 在本地摘要生成后会展示第二次 `share_text` 确认，并保持剪贴板
  派生消息为 `LocalOnly`；存在待确认动作时会阻止新消息越过旧确认卡。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'

./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 严格敏感信息扫描未发现 OpenAI-style API Key、DeepSeek URL/model 或真实
  Authorization Bearer token 被写入文件。
- 当前 `adb devices -l` 无已连接设备，因此本轮未执行
  `connectedDebugAndroidTest` 或真机分享面板验证。

## 2026-05-31 Agent Replan / ViewModel 隐私回归增量验证

本轮覆盖项：

- Agent observe 成功后可通过 `AgentObservationReplanner` 产出下一步工具计划。
- 默认生产策略 `SequentialActionObservationReplanner` 会在用户输入含明确顺序
  连接词（如“然后”/`then`）且 run 目前只有一个工具计划时，规划下一步动作。
- 下一步工具会重新经过 Tool Registry 参数校验、SafetyPolicy、trace、audit
  和用户确认；不会因为来自 observe 阶段就直接执行。
- Replanned request id 不能复用已有 `ToolRequested` id，避免确认/观察串到旧
  请求。
- Clipboard continuation 优先于 replan；当观察结果需要本地模型续写时，不会
  调用 replanner，也不会产生被忽略的拒绝 trace/audit 副作用。
- ViewModel 构造边界改为窄接口，新增 JVM 回归测试覆盖远程 `LocalOnly`
  当前输入保护，以及本地 share input / local assistant 回复不进入后续远程
  history。
- LiteRT native logging 配置从 ViewModel 构造移到 AppContainer，降低 JVM
  单测副作用。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest'

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
git diff --check
```

结果：通过。

补充检查：

- 仓库扫描未发现 DeepSeek/OpenAI-style API Key 或 DeepSeek 远程配置被写入文件。
- 当前 `adb devices -l` 无已连接设备，因此本轮未执行 `connectedDebugAndroidTest`。

## 2026-05-30 隐私边界 / Skill 执行器 / DB 迁移增量验证

本轮覆盖项：

- `MessagePrivacy.LocalOnly` 持久化到会话消息，并在远程历史构造前过滤。
- 远程模式下当前输入若标记为 `LocalOnly`，会在 ViewModel 层直接保护，
  不调用远程模型。
- Android 分享入口与剪贴板派生续写的自动消息标记为 `LocalOnly`，避免之后
  切换远程模式时进入 history。
- `RemoteChatRuntime` 在请求体构造和真实 `send` 路径中都过滤 `LocalOnly`
  history。
- `SkillRunExecutor` 默认通过工具 registry 和 safety policy gate；需确认的
  tool step 返回 `AwaitingConfirmation`，不会直接执行工具。
- `SkillRunExecutor` 的公开 outputs 不再暴露 `read_clipboard.text` 等私有工具
  绑定输出。
- Agent observe 阶段新增显式 `AgentObservationDecision`，覆盖 complete、
  continue-with-model、retry、fail、cancel，并写入 trace 但不存储私有续写 prompt。
- Room `chat_messages.privacy` 增加实体默认值和 `3 -> 4` 迁移；新增
  instrumentation 迁移测试。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.runtime.RemoteChatRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.data.SessionRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:testDebugUnitTest
```

结果：通过。

模拟器说明：

- SDK 工具位于 `/Users/bytedance/Library/Android/sdk`。
- `pocketmind_api36_arm64` 与 `focus_agent_api36_arm64` 两个 AVD 在本轮尝试
  中均未能在限定时间内完成启动，且 emulator 进程自动退出，因此本轮未能执行
  `connectedDebugAndroidTest`。
- `PocketMindDatabaseMigrationTest` 已通过 `assembleDebugAndroidTest` 编译，
  仍需要在可启动模拟器或真机上执行。

验证时间：2026-05-30

## Agent 核心能力增量验证

环境：

- AVD：`focus_agent_api36_arm64`
- Android：API 36 Google APIs ARM64
- 设备序列号：`emulator-5554`
- SDK：`/Users/bytedance/Library/Android/sdk`

覆盖项：

- Tool Registry 增加权限声明、结构化错误模型、参数拒绝和执行结果。
- Tool Registry 参数校验改为由 JSON schema 驱动，覆盖 required、额外参数拒绝、`minLength` 和 `pattern`。
- Agent Loop 增加确认后的 `ToolResult` observe 回写、trace step 和完成状态。
- Agent Loop 对 retryable 工具失败增加一次有界自动重试，记录 `ToolRetryScheduled` trace/audit 事件，重试预算耗尽后才进入 `Failed`。
- 用户取消动作确认时，Agent run 会进入 `Cancelled`，并记录取消/观察审计事件。
- Built-in Skill Runtime 增加版本化 manifest，并将邮件、日程、地图、信息查找、设备设置映射为一跳工具 skill。
- Device Context 增加最小非敏感设备状态快照，并接入 Agent trace / prompt context。
- Safety Policy 增加风险分级执行策略，阻止中高风险工具绕过确认。
- Tool Audit 增加 `tool_audit_events` Room 表，记录计划、确认、执行观察和拒绝事件。
- Memory 增加显式偏好/任务状态记录与遗忘控制。
- Background Tasks 增加 `schedule_reminder` 工具、`reminder_skill`、`scheduled_tasks` Room 表、AlarmManager 调度和提醒通知通道。
- Reminder 执行前会请求 Android 通知权限；拒绝后返回结构化 `PermissionDenied`。
- Device Context 增加受确认保护的 `read_clipboard` 工具和剪贴板上下文 Skill。
- Clipboard observe 成功后会生成一次本地模型续写 prompt，剪贴板原文只进入即时内存续写链路；trace、audit 和持久化工具观察消息保留脱敏摘要，远程模型模式不会自动上传剪贴板内容。
- 远程模型普通聊天不再自动注入本地记忆和设备上下文；Android 分享入口在远程模式下不自动上传分享文本、文本摘录或附件元数据。
- 远程 API Key 清空时会同步清除已加密保存的旧值。
- Execution Boundary 增加 `share_text` 工具和系统分享 Skill；结果语义为打开系统分享面板。
- Multimodal Inputs 增加 Android 分享入口，接收 shared text 和有界 `text/*`
  文档摘录；图片/音频/视频/PDF/Office/二进制附件保持 metadata-only。
- 模型管理 sheet 增加显式关闭按钮，避免模拟器回归依赖 Back 键状态。
- 远程模型回归使用本地 mock OpenAI-compatible 服务，不写入真实 API Key。

验证命令：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug

ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
ANDROID_SERIAL=emulator-5554 \
./gradlew :app:connectedDebugAndroidTest
```

结果：通过。

- JVM 单测：通过。
- Debug APK 构建：通过。
- 模拟器 instrumentation：`4 tests, 0 skipped, 0 failed`。

说明：

- 用户提供的 DeepSeek 远程配置仅作为可选手工验证输入，未写入仓库、测试代码或文档。
- 当前仍未完成的核心能力包括屏幕理解、LiteRT embedding adapter 参与记忆检索、special-access permission flows beyond Usage Access、截图/相册入口和实际图片/文档理解；状态见 `docs/agent_core_modules.md`。

## 历史验证记录

验证时间：2026-05-24

## 模拟器完整功能回归

环境：

- AVD：`pocketmind_api36_arm64`
- Android：API 36 Google APIs ARM64
- 安装包：`app/build/outputs/apk/release/app-release-local-signed.apk`
- 模型目录：`/sdcard/Android/data/com.bytedance.zgx.pocketmind/files/Download/`

模型补齐结果：

- `gemma-4-E2B-it.litertlm`：已安装，约 2.4 GB。
- `embeddinggemma-300m.litertlm`：已补装，约 171 MB；该 asset 仅表示文件已安装，
  不表示 LiteRT embedding adapter 已参与记忆检索。
- `mobile-actions_q8_ekv1024.litertlm`：已补装，约 271 MB。
- 模型管理页确认三类推荐资产均出现在“本地模型”，设备检查在基础能力齐全后显示 `待下载：已就绪`。

真实交互覆盖：

- 普通聊天：保留既有 `用三句话解释端侧大模型` 成功回答验证。
- 记忆增强：新会话发送 `Remember my rcode is xb83`，停止生成后会重建本地轻量
  token/hash 记忆索引；追问 `What is my rcode` 时 UI 显示 `已引用本地记忆 1 条`，
  回答 `你的 rcode 是 xb83。`
- 动作草稿：发送 `open wifi settings` 后只展示确认 Sheet；未确认时仍停留在 App；点击 `确认并打开` 后进入系统 Wi-Fi 设置页。
- 会话管理：会话列表展示当前会话、消息数量和历史会话，入口可正常打开。
- 模型管理：推荐模型区支持基础对话、记忆 asset、动作模型逐项补装/重下；
  记忆 asset 的安装不代表 runtime 参与；Top K 滑条不再展示密集刻度点；
  下载完成后不再残留旧进度条。

修复项：

- 记忆检索增加词项重叠过滤，避免哈希向量碰撞导致无关旧记忆注入。
- 英文停用词过滤加入 `remember`、`is`、`my` 等常见词，避免“记住某事”类命令互相误召回。
- 停止生成后会重建本地记忆索引，已经发送的用户事实可被后续检索。
- 设备检查按仍需下载的基础能力大小提示空间；基础能力齐全时不再误报 2.4 GB 对话模型空间不足。

验证命令：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/gemma4-e2b/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/gemma4-e2b/android-sdk \
scripts/verify_local.sh
```

结果：通过。覆盖 `testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、APK 不含 `.litertlm`、仅 `arm64-v8a` native code、release size <= 75 MB。

## 模拟器真实对话验证

环境：

- AVD：`pocketmind_api36_arm64`
- Android：API 36 Google APIs ARM64
- 安装包：`app/build/outputs/apk/release/app-release-local-signed.apk`
- 模型文件：在模拟器内通过 App 首装向导下载 `基础对话 E2B`，文件位于 `/sdcard/Android/data/com.bytedance.zgx.pocketmind/files/Download/gemma-4-E2B-it.litertlm`

流程：

- 首装向导默认展示基础能力包；为缩短真实对话验证时间，只保留对话模型，取消记忆与动作模型。
- 模拟器内 DownloadManager 完成约 2.4 GB 对话模型下载。
- App 自动注册模型并加载；GPU dispatch 初始化不可用时自动回退 CPU，界面显示 `基础对话 E2B · CPU · 已就绪`。
- 新建会话后点击开场问题 `用三句话解释端侧大模型`。

首次结果：

- 模型下载与加载成功，但生成结束后因为 LiteRT benchmark 未启用，`getBenchmarkInfo()` 抛错，UI 显示 `生成失败，建议重新加载`。

修复：

- `PocketMindViewModel` 读取生成统计时忽略 benchmark 不可用错误。
- `RealLiteRtRuntime.lastGenerationStats()` 对 LiteRT benchmark API 做容错，统计不可用时返回 `null`。

复测结果：

- 同一模拟器保留已下载模型，覆盖安装修复后的 release 包。
- 新建会话再次发送 `用三句话解释端侧大模型`，成功返回三句话中文回答。
- 生成结束后状态回到 `基础对话 E2B · CPU · 已就绪`，未再出现 benchmark 导致的生成失败。
- 截图：`/tmp/pocketmind-real-dialogue-fixed.png`

验证命令：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/gemma4-e2b/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/gemma4-e2b/android-sdk \
./gradlew testDebugUnitTest assembleRelease
```

结果：通过。

## 最新增量验证

命令：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
scripts/doctor.sh

ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
scripts/verify_local.sh
```

结果：通过。

覆盖项：

- `PocketMindViewModel` 已拆到 runtime、model repository、download service 和 session repository 边界。
- `MainActivity` 仅保留 Activity wiring；Compose UI 移到 `ui/`，markdown 分段逻辑已可 JVM 测试。
- 下载取消会先取消 monitor job 并清除 active download id，避免取消后被轮询覆盖为“下载任务不存在”。
- Release 已开启 R8/resource shrink，并在本地门禁中加入 75 MB APK 预算。
- `scripts/doctor.sh` 已验证 JDK、Android SDK 36、aapt、adb 和 Gradle wrapper。

产物：

- `app/build/outputs/apk/debug/app-debug.apk`，约 100 MB
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，约 3.4 MB
- `app/build/outputs/apk/release/app-release-unsigned.apk`，约 25 MB

真机：

- 已连接设备 `fb6272c`，型号 `23127PN0CC`，状态为 `device`。
- 重新允许 USB 安装后，`./gradlew :app:connectedDebugAndroidTest --console=plain` 通过。
- 真机执行 `3 tests, 0 skipped, 0 failed`。

## 之前增量验证

命令：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

结果：通过。

覆盖项：

- 推荐模型目录包含 基础对话模型 E2B 与 高质量对话模型 E4B。
- 首屏直接暴露推荐模型选择、下载、导入、设备检查和状态提示。
- 顶部常驻展示运行状态，模型管理弹层展示当前模型、本地模型、推荐模型、添加模型和进度。
- 底部输入区会根据无模型、忙碌、就绪状态切换提示与主操作。
- 下载/导入进入加载阶段时会清理进度字段，避免 100% 下载进度残留。
- Compose 冒烟测试已补充首屏模型准备入口断言。

真机：

- 已连接设备 `23127PN0CC`，设备状态为 `device`。
- `connectedDebugAndroidTest` 首次尝试在已有模型状态下进入 instrumentation 后卡住；清理调试包后重跑，设备安装阶段返回 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。
- 需要在 Xiaomi / HyperOS / MIUI 开发者选项中允许“USB 安装 / 通过 USB 安装”，并确认手机弹窗后重跑真机自动化。

## 本地验证

命令：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
GRADLE_CMD=/tmp/gradle-9.5.1/bin/gradle \
scripts/verify_local.sh
```

结果：通过。

覆盖项：

- `testDebugUnitTest`
- `lintDebug`
- `assembleDebug`
- `assembleDebugAndroidTest`
- APK 不包含 `.litertlm` 模型文件
- APK 仅包含 `arm64-v8a` native code
- Manifest 包名和联网权限正确
- Hugging Face 模型下载 URL 可访问，`Content-Length = 2588147712`

产物：

- `app/build/outputs/apk/debug/app-debug.apk`，约 84 MB
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，约 2.4 MB

## 真机验证

设备：

- 厂商：Xiaomi
- 型号：23127PN0CC
- Android：16
- ABI：arm64-v8a
- `/data` 可用空间：约 50 GB

自动验证：

```bash
scripts/install_and_test_device.sh
```

结果：通过。`MainActivitySmokeTest.firstLaunchShowsModelSetupActions` 在真机上执行 `1 tests, 0 failures`。

## 模型下载与加载

App 内点击“下载推荐模型”后，模型文件成功下载到：

```text
/storage/emulated/0/Android/data/com.bytedance.zgx.pocketmind/files/Download/chat-model.litertlm
```

文件大小：

```text
2588147712 bytes
```

App 偏好已保存模型路径：

```xml
<string name="model_path">/storage/emulated/0/Android/data/com.bytedance.zgx.pocketmind/files/Download/chat-model.litertlm</string>
```

加载结果：

```text
就绪 · GPU
离线可用
```

覆盖安装新 APK 后保留模型文件，强停并重启 App，仍能自动加载到 `就绪 · GPU`。

## 真机问答

问题：

```text
用三句话解释什么是端侧大模型
```

真机回答：

```text
端侧大模型是指将大型语言模型（LLM）的能力部署在本地设备（如手机、边缘计算设备）上。

这意味着模型可以在没有连接到云端服务器的情况下，直接在用户设备上进行推理和应用。

其主要优势在于提高响应速度、增强隐私性，并降低对网络带宽和云端算力的依赖。
```

结果：真机本地模型问答成功，生成结束后状态回到 `就绪 · GPU`。

## 参数与生成统计验证

日期：2026-05-24

覆盖内容：

- “模型”入口已在顶部和输入框发送按钮旁保留，便于发送前调整模型与参数。
- 模型管理页新增全局生成参数：Temperature、Top P、Top K；页面文案说明低/高取值对稳定性、多样性和候选范围的影响。
- 参数修改后立即持久化；模型已加载时会重建当前会话 runtime，使后续生成直接使用新参数。
- 端侧后端说明已补充：GPU 通常更快但更依赖驱动/内存条件，CPU 更稳但更慢；GPU 初始化失败会自动切到 CPU。
- 每次回答生成结束后，助手消息下方显示本次生成 token 数和 token/s；非法或不可用速度值会被过滤。
- 停止生成现在会调用 LiteRT `cancelProcess()`，避免 UI 停止后 native 侧继续生成。
- 会话数据读取兼容旧 top-level array 格式；不会在 repository 构造时立刻重写旧格式，降低回滚风险。

验证命令：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
scripts/verify_local.sh
```

结果：通过。覆盖 `testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、APK 不含 `.litertlm`、仅 `arm64-v8a` native code、release size <= 75 MB。

真机命令：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk \
./gradlew :app:connectedDebugAndroidTest --console=plain
```

结果：通过。设备 `23127PN0CC - 16` 执行 `3 tests, 0 skipped, 0 failed`。测试在保留已有模型和会话数据的设备状态下通过。

安装命令：

```bash
/Users/bytedance/Documents/Codex/2026-05-24/pocketmind-model/android-sdk/platform-tools/adb \
  -s fb6272c install -r app/build/outputs/apk/debug/app-debug.apk
```

结果：`Success`。仅覆盖安装，未卸载 App，未清除 App 数据。

## 主分支合并检查

日期：2026-05-24

分支与提交：

- `main`
- 合并提交：`b0b4306 Add model capability setup and assistant orchestration`

代码检查：

```bash
rg "com.bytedance.zgx.gemmalocalqa|GemmaChatViewModel|GemmaChatScreen|GemmaModelRules|GEMMA_|FunctionGemmaActionPlanner|gemma_local_qa" \
  app/src build.gradle.kts settings.gradle.kts docs scripts
```

结果：通过，未发现旧产品级包名、类名或脚本符号残留。

本地验证：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/gemma4-e2b/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/gemma4-e2b/android-sdk \
scripts/verify_local.sh
```

结果：通过。覆盖 `testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、APK 不含 `.litertlm`、仅 `arm64-v8a` native code。

真机 UI 验证：

```bash
ANDROID_SDK_ROOT=/Users/bytedance/Documents/Codex/2026-05-24/gemma4-e2b/android-sdk \
ANDROID_HOME=/Users/bytedance/Documents/Codex/2026-05-24/gemma4-e2b/android-sdk \
./gradlew connectedDebugAndroidTest --console=plain
```

结果：通过。设备 `23127PN0CC - 16` 执行 `3 tests, 0 skipped, 0 failed`，`BUILD SUCCESSFUL in 1m 39s`。

补充修复：instrumentation 进程内自动跳过启动期 pending download / 模型加载工作，并禁止测试期间打开模型来源外链；`MainActivitySmokeTest` 使用稳定的 `testTag` 定位模型管理与自定义下载入口，避免依赖长滚动和占位文案。该改动已由本地验证和真机 UI 测试覆盖。
