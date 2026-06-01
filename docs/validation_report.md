# PocketMind 验证报告

## 2026-06-02 运行时权限确认卡 UI 增量验证

本轮覆盖项：

- 新增 `MainActivityRuntimePermissionUiTest`，通过真实 `MainActivity` 输入
  `查联系人 Alice`，覆盖联系人查询动作草稿到 Compose 确认卡的 Activity/UI 路径。
- 确认卡现在有 instrumentation 覆盖：显示 `runtime_permission_requirements`
  区块、联系人权限标题和只读联系人摘要 rationale。
- 测试明确断言联系人查询不展示 `special_access_requirements`，避免 runtime
  permission 和系统特殊授权 UI 边界混淆。
- 测试只点击取消，不点击“确认执行”，因此不会触发 Android 系统权限弹窗，也不读取
  真实通讯录。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin
```

结果：通过。

## 2026-06-02 长期记忆真实 UI 路径增量验证

本轮覆盖项：

- 新增 `MainActivityLongTermMemoryUiTest`，以真实 `MainActivity` 和 Compose UI
  路径保存显式“记住”偏好，而不是只测 ViewModel 或 repository。
- 测试启动前清理主 Room 表、active session，并写入 configured localhost remote
  模式，让 composer 处于 ready；显式偏好命令仍会在远程调用前被本地记忆分支
  截获，不访问网络。
- 模型管理 > 高级 > 长期记忆面板现在有 instrumentation 覆盖：记忆开关开启、
  保存后的偏好行可见、单条“遗忘”按钮移除对应记录、清空按钮和确认弹窗清除
  剩余长期记忆。
- 同时抽出 androidTest 共享状态 helper，避免 share intent 和长期记忆 UI 测试
  受 DataStore/DB 持久状态串扰。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin
```

结果：通过。

## 2026-06-02 Activity share intent 冷启动边界增量验证

本轮覆盖项：

- 新增 `MainActivitySharedIntentTest`，用 `ActivityScenario` 以自定义
  `ACTION_SEND text/plain` intent 冷启动 `MainActivity`，覆盖真实
  Activity 边界的 `handleSharedIntent -> ShareIntentReader -> ingestSharedInput`
  链路，而不是只测 ViewModel 或 reader。
- 本地模式下，分享文本会作为 `LocalOnly` shared-input 消息进入 UI，并展示
  “已接收分享内容”本地提示。
- 远程模式下，Activity 在读取分享 intent 前选择 protected read mode；测试断言
  UI 只显示隐私保护提示，分享正文 sentinel 不渲染，避免冷启动 share target
  因持久化远程模式而读取或展示私有分享文本。
- 测试启动前显式重置主 Room 表、active session 和推理模式，降低仪器测试之间
  DataStore/DB 持久状态串扰导致的假阳性或假阴性。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin
```

结果：通过。

## 2026-06-02 Built-in Skill manifest contract 增量验证

本轮覆盖项：

- `BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills` 从包含式断言
  收紧为测试自有字面量快照，防止新增、删除、改名或常量同步误改导致 Skill
  静默漂移。
- 每个 built-in Skill manifest 必须有固定 version、固定 risk level、非空
  title/description、精确 trigger examples、精确 required tools、raw closed input
  schema；required tools 的 union 必须覆盖 `MobileActionFunctions.supported`，
  且每个 required tool 必须存在于 `ToolRegistry`。
- 每个 trigger example 必须 route 回声明它的 Skill，单步 tool request 必须通过
  `ToolRegistry` 校验；代表性规划 fixture 集合也必须覆盖全部 built-in manifest。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest'
```

结果：通过。

## 2026-06-02 Background task active-list 语义增量验证

本轮覆盖项：

- 后台任务入口的 active list 只展示仍处于 `Scheduled` 的可管理任务；竞态进入
  `Running` 的任务不会继续显示成可取消项。
- `Running` 任务仍参与 task-state long-term memory 索引和恢复逻辑，避免修 UI
  语义时丢失 Agent 可召回的后台任务状态。
- 取消失败后会重新加载 active/history/policy；若底层任务仍是 `Scheduled` 则保留
  可见，若已进入 `Running` 则从可管理列表隐藏，同时显示失败提示。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateLoadsScheduledBackgroundTasksAndIndexesRunningTaskStateWithoutRemoteWork' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.cancelScheduledBackgroundTaskRefreshesUiAndCancelsScheduler' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.cancelScheduledBackgroundTaskFailureKeepsTaskVisible' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.cancelScheduledBackgroundTaskFailureHidesConcurrentlyRunningTask' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.setPeriodicCheckPolicySchedulesDefaultPolicyAndRefreshesUi' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.setPeriodicCheckPolicyFailureDoesNotShowHealthyRunningTask' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.disablePeriodicCheckPolicyMovesTaskToHistory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.disablePeriodicCheckPolicyFailureKeepsScheduledTaskVisible'
```

结果：通过。

## 2026-06-02 JSON/XML/YAML 共享附件摘录增量验证

本轮覆盖项：

- 用户主动分享的 `application/json`、`application/xml`、`application/yaml`
  和 `application/x-yaml` 现在归类为 Document，并复用受限 UTF-8 文本摘录
  读取器；`application/octet-stream` 等二进制 application MIME 仍保持
  metadata-only，不打开附件流。
- Android manifest 的 SEND / SEND_MULTIPLE 入口和 in-app picker MIME 白名单同步
  接收上述四类 text-like application 文档；未纳入 `application/ld+json`，避免
  扩大到潜在二进制或图谱语义载荷。
- Protected share signal 对 `text/*`、JSON/XML/YAML、RTF、PDF、Office 和 image
  附件均不打开 stream、不跑 OCR、不暴露 protected source 计数。
- 远程模型模式在构建 prompt 前 fail closed：直接分享文本、metadata-only 附件、
  protected share signal、文本、JSON/XML/YAML、RTF/PDF/Office 和 OCR preview
  都只生成本地隐私提示，不向 remote runtime 发送正文、文件名、MIME、大小、摘录
  或 history。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsDirectSharedTextBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedAttachmentMetadataBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeHandlesProtectedShareSignalWithoutBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedTextPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedTextLikeApplicationPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedRichTextPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedImageOcrPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedOfficeDocumentPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedPdfTextLayerPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.AndroidManifestTest.shareTargetsAcceptPickerSupportedDocumentMimeTypes' \
  --tests 'com.bytedance.zgx.pocketmind.AndroidManifestTest.composerAttachmentPickerUsesShareTargetMimeTypes'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
git diff --unified=0 | rg -n "(sk-[A-Za-z0-9_-]{20,}|B[e]arer [A-Za-z0-9._-]{20,}|(?i)(api[_-]?key|s[e]cret|p[a]ssword|d[e]epseek))"
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：定向 multimodal/ViewModel/manifest 测试、全量 JVM 单测、AndroidTest Kotlin
编译、diff whitespace 检查、敏感串扫描和本地完整验证脚本通过。

## 2026-06-02 Agent retry / Skill checkpoint 状态口径增量验证

本轮覆盖项：

- 可重试的只读工具失败后只调度一次 bounded retry；retry 成功后仍回到正常
  observation path，并继续为显式顺序输入规划下一段确认。
- `SkillPlan` 明确把 `.` 作为 `stepId.outputKey` 引用分隔符；含 `.` 的 step id
  或 model output key 在结构校验阶段 fail closed，避免 value-free checkpoint
  误拆 private-output ref。
- 推荐模型卡片把 memory/action 模型安装状态显示为资产口径，避免把 memory asset
  安装误解为语义记忆 runtime 已启用。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.retryableToolFailurePlansNextSequentialActionAfterSuccessfulRetry' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest.valueFreeCheckpointRejectsPrivateRefsWhenCompletedStepIdContainsDot'
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
git diff --unified=0 | rg -n "<sensitive endpoint/model/key patterns>"
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：定向 Agent loop / Skill checkpoint 测试、相关 Agent/Skill 测试类、
全量 JVM 单测、AndroidTest Kotlin 编译、diff whitespace 检查、敏感串扫描和
本地完整验证脚本通过。

## 2026-06-02 语义记忆运行时状态增量验证

本轮覆盖项：

- `MemoryRepository` 明确区分 `NoVerifiedModel`、`RuntimeUnavailable`、
  `RuntimeLoadFailed` 和 `Active`；`semanticMemoryEnabled` 只在 `Active` 时为真。
- `PocketMindViewModel`/`ChatUiState` 同步暴露 runtime status，避免把已校验
  memory asset 误显示为已启用语义召回。
- 生产默认没有 LiteRT embedding runtime factory；已安装 memory asset 时报告
  runtime unavailable 并回退轻量索引，测试注入 semantic runtime 才产生
  `MemoryRecallMode.Semantic` 命中。
- 状态机覆盖了无 factory、load failed 后清空模型、同一路径失败后重试成功、
  Active 状态传播，以及无 runtime 时 lexical fallback 仍可用。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateSyncsVerifiedMemoryModelBeforeRebuildingMemoryIndex' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateReportsUnavailableSemanticRuntimeWhenFactoryIsMissing' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.localModeSemanticMemoryStatusAndPromptUseSemanticHit' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeKeepsSemanticMemoryRuntimeButDoesNotSendMemoryContext'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
git diff --unified=0 | rg -n "<sensitive endpoint/model/key patterns>"
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：定向记忆/ViewModel 测试、全量 JVM 单测、AndroidTest Kotlin 编译、
diff whitespace 检查、敏感串扫描和本地完整验证脚本通过。

## 2026-06-02 Emulator regression 验证

本轮覆盖项：

- 在 `focus_agent_api36_arm64` AVD 上启动 API 36 / `arm64-v8a` emulator。
- 构建并安装 debug APK 与 androidTest APK。
- 执行 instrumentation smoke/regression，覆盖主界面、会话、记忆、动作确认、
  自定义下载入口、背景任务和 Room migration。

验证命令：

```bash
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
AVD_NAME=focus_agent_api36_arm64 \
EMULATOR_ARGS='-no-snapshot -no-audio -no-window -no-boot-anim' \
EMULATOR_SELECT_TIMEOUT_SECONDS=120 \
BOOT_TIMEOUT_SECONDS=300 \
scripts/verify_emulator.sh
```

结果：通过。设备 `emulator-5554`，API 36，ABI `arm64-v8a`，
instrumentation `OK (14 tests)`，脚本输出 `Emulator verification passed`。
在 sequential composite Skill tail 支持提交后，使用同一 AVD/命令对当前
HEAD 复跑通过，instrumentation 仍为 `OK (14 tests)`。

## 2026-06-02 Sequential composite Skill segment 增量验证

本轮覆盖项：

- 显式顺序输入的第一段现在可以启动 validated composite Skill，例如
  “总结剪贴板并分享，然后打开 Wi-Fi 设置”会先进入
  `clipboard_summary_share_skill` 的 `read_clipboard` 确认。
- 中间 segment 也可以启动 validated composite Skill，例如
  “打开 Wi-Fi 设置，然后总结剪贴板并分享，再打开手电筒设置”会按
  `open_wifi_settings -> read_clipboard -> share_text -> open_flashlight_settings`
  推进。
- 顺序游标改为按 logical segment 计数，而不是按 tool request 计数；同一个
  composite Skill 内部的 `read_clipboard -> local model -> share_text` 不会跳过
  后续 segment。
- 后续 replan 使用当前 segment 文本绑定 Skill，避免把整条顺序输入作为下一段
  Skill 的 `input`。
- composite Skill 内部的剪贴板原文只出现在 local continuation prompt，不进入
  trace、audit 或 pending checkpoint；`share_text.text` 只能使用本地模型输出。
- 单独私密读取首段/中间段（如“读取剪贴板，然后打开 Wi-Fi 设置”）仍 fail closed。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.initialSequentialCompositeSkillSegmentPlansFirstCompositeSkill' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.sequentialCompositeSkillSegmentContinuesToNextSegmentAfterInternalToolsComplete' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.sequentialMiddleCompositeSkillSegmentContinuesToTailAfterInternalToolsComplete' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.sequentialMiddlePrivateReadSegmentDoesNotPlanWhenTailRemains' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.initialSequentialPrivateReadSegmentFallsBackToAnswer' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.defaultSequentialReplannerCanAdvanceThroughThreeExplicitActions'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
git diff --unified=0 | rg -n "^\\+.*<sensitive endpoint/model/key patterns>"
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：通过。`scripts/verify_local.sh` 覆盖 doctor、脚本校验、unit、lint、
debug/androidTest/release assemble 和 APK 检查；新增 diff 行敏感串扫描无命中。

## 2026-06-02 Local verification KSP/lint ordering 增量验证

本轮覆盖项：

- `scripts/verify_local.sh` 在 `lintDebug` / assemble 聚合前先显式执行
  `:app:kspReleaseKotlin`，避免 lint model 读取 release Room/KSP 生成源时与
  `assembleRelease` 并发竞态。
- `scripts/test_validation_scripts.sh` 增加静态顺序断言，防止本地验证脚本重新把
  release KSP 预生成步骤移到 lint 之后。

验证命令：

```bash
bash -n scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/verify_emulator.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
git diff --check
git diff --unified=0 | rg -n "^\\+.*<sensitive endpoint/model/key patterns>"
```

结果：通过。

## 2026-06-02 Initial sequential first-segment planning 增量验证

本轮覆盖项：

- 初始规划在整句显式顺序输入被保守拒绝后，可只用第一段 action segment
  重新尝试单工具/单步 Skill 规划。
- “先搜一下 Kotlin，然后打开 Wi-Fi 设置”现在能用规则路径进入 `web_search`
  确认，观察成功后继续规划 Wi-Fi 确认。
- 第一段如果是 bare 私密读取（如“读取剪贴板”）且后面还有 segment，则不拆，
  避免启动半截流程后必须跨过本地模型 continuation 继续执行后续 segment。
  后续增量已放行带自身 local model boundary 的 composite Skill 首段。
- 后续 replan 也对私密读取做同样保护：当后面还有 explicit segment 时不规划
  剪贴板/OCR/当前屏幕读取；如果它们是最后一段，则仍可进入确认。
- 解释性“先搜索再打开设置这个流程怎么实现”仍走普通回答，不进入确认。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.initialSequentialInputPlansFirstSingleToolSegmentThenContinues' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.initialSequentialCompositeSkillSegmentPlansFirstCompositeSkill' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.initialSequentialPrivateReadSegmentFallsBackToAnswer' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.sequentialReplannerSkipsPrivateReadWhenMoreSegmentsRemain' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.sequentialReplannerAllowsFinalPrivateReadSegment' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.explanatorySequentialTextStillFallsBackToAnswer'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
git diff --unified=0 | rg -n "^\\+.*<sensitive endpoint/model/key patterns>"
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：通过。第一次完整验证遇到 lint/KSP generated file 读取竞态，补跑
`:app:kspReleaseKotlin :app:lintDebug` 后重新执行 `scripts/verify_local.sh`
通过；本轮新增 diff 行敏感串扫描无命中。

## 2026-06-02 Explicit sequential Agent loop 增量验证

本轮覆盖项：

- `SequentialActionObservationReplanner` 从单次后续动作扩展为有上限的显式顺序
  replan：每个成功且已验证的 observation 只规划下一段，并重新进入用户确认。
- pending confirmation 在当前进程内只携带下一段动作；Room 仍不持久化
  `nextActionInput`，重启后不会恢复 raw 剩余 sequence。
- 两段序列在第二步成功后停止，不会重复规划第二段；三段序列按
  search -> Wi-Fi settings -> flashlight settings 顺序产生三次确认后完成。
- Room trace store 在最后一段 pending 时清空 live next-action cursor，避免最后
  一段成功后重复规划。
- ToolPlanned/ConfirmationRequested 审计持久化改为参数无关摘要，避免搜索 query
  等普通工具参数进入 audit DB summary。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.defaultSequentialReplannerCanAdvanceThroughThreeExplicitActions' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.roomSequentialReplannerDoesNotRepeatFinalSegmentWhenNextInputClears' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest.defaultSequentialReplannerPlansExplicitNextActionAfterObservation' \
  --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditRepositoryTest.recordDoesNotPersistToolParametersFromPlannedSummary'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
git diff --unified=0 | rg -n "^\\+.*<sensitive endpoint/model/key patterns>"
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：通过。`scripts/verify_local.sh` 覆盖 doctor、脚本自测、全量 JVM 单测、
lintDebug、debug/release assemble、AndroidTest assemble、APK 模型 artifact 检查和
release APK 体积检查；全仓敏感串扫描仍会命中既有测试 fixture，本轮新增 diff 行
扫描无命中。

## 2026-06-02 Release checklist 增量验证

本轮覆盖项：

- 新增 `docs/release_checklist.md`，覆盖 release scope、store metadata、
  screenshots、privacy/license、signing/build、device/emulator validation、
  rollback 和 final gate。
- `docs/release_readiness.md` 将手工 release checklist 从 Remaining 移到
  Completed；release signing、模型 license 人工核对和 connected/emulator
  release candidate 验证仍保留为 Remaining。
- README 文档目录补充 release checklist。

验证命令：

```bash
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
```

结果：通过。该切片仅更新文档，未运行 Gradle。

## 2026-06-02 Release privacy/model docs 增量验证

本轮覆盖项：

- 新增 `docs/privacy_notice.md`，覆盖本地存储、远程模型模式、设备上下文工具、
  外部 Intent/分享、模型下载、audit/trace 与保留控制。文档明确这是内部测试
  隐私边界说明草案，不是最终公开法律政策。
- `docs/model_manifest.md` 增加每个推荐模型的上游仓库和 license readiness
  checklist；本地缺少足够 license 证据时，保持发布前人工核对 blocker，而不是
  误写成已完成。
- `docs/release_readiness.md` 将 privacy notice 移到 Completed 草案项，并保留
  发布前 release/security/legal 审核；模型 license 核对保留为 Remaining blocker。
- README 文档目录与 License 段落已指向隐私说明和模型 manifest。

验证命令：

```bash
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
```

结果：通过。该切片仅更新文档，未运行 Gradle。

## 2026-06-02 Action preflight gate 收窄验证

本轮覆盖项：

- `MobileActionPlanner.isLikelyAction` 不再使用 app/file/document/image/video/audio
  等泛词作为动作入口信号，而是复用各工具已有的 conservative parser。
- 普通聊天输入如“帮我写一份文档”“这张图片是什么”“这个 app 架构怎么设计”
  会停在普通 Answer 路径；即使测试里注入了一个可执行工具草稿，也不会调用
  action planner 生成确认卡。
- 明确工具意图仍保留：最近图片 metadata 查询、当前应用查询、剪贴板读取等请求
  仍能进入各自 Skill-first 或动作规划路径。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.genericAppFileAndMediaWordsFallBackToAnswerWithoutActionPlanning' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstRecentMediaFilesBypassesActionPlannerAndRequestsConfirmation'
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
```

结果：通过。此前一次 Agent loop 定向命令因测试名不存在失败，未反映产品代码问题；
已改用当前存在的测试名重新验证通过。

## 2026-06-01 Current-screen text summary share Skill 增量验证

本轮覆盖项：

- 新增 `current_screen_text_summary_share_skill`：用户明确要求“总结当前屏幕文字并分享”时，
  先进入确认式 `read_current_screen_text`，再用本地 `ModelStep` 生成摘要，最后进入第二个
  `share_text` 确认。
- 该 Skill 只处理 Accessibility 可访问文本快照，不声明截图、OCR、像素读取或语义屏幕理解；
  说明型/否定型请求不会被当作动作。
- raw `screenText` 继续作为 private tool output：只能进入当前进程内的本地模型
  continuation，不能直接绑定到 `share_text.text`，也不进入 trace、audit、public outputs、
  pending checkpoint、持久消息或远程 runtime。
- 到第二个 payload-bearing `share_text` 确认卡后重启，恢复应 fail closed：不恢复摘要参数、
  不打开分享面板、不重跑旧 `read_current_screen_text`，旧 request id 不能继续推进。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest.executesCompositeCurrentScreenTextSummaryShareSkillInDependencyOrder' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest.currentScreenTextPrivateOutputCannotBindDirectlyToLaterShareArgument' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.currentScreenTextSummarySharePlansShareAfterLocalModelResult' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredCurrentScreenTextSummarySharePendingFailsClosedAfterRestart' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.currentScreenTextSummaryShareShowsSecondConfirmationAfterLocalSummary' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeProtectsCurrentScreenTextSummaryShareBeforeRemoteContinuation'
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：targeted Skill manifest/plan、executor privacy fence、Agent loop second-confirmation、
restart fail-closed、ViewModel 远程保护、全量 JVM 单测、AndroidTest Kotlin 编译、
diff whitespace 检查、敏感串扫描和本地完整验证脚本通过。

## 2026-06-01 Preference/TaskState alias memory 增量验证

本轮覆盖项：

- 默认 hash 记忆为显式 `Preference` 和结构化活跃 `TaskState` 增加保守的本地
  alias 索引，让回答长度/语言偏好和后台任务状态能通过常见中英文问法召回。
- Alias 只进入内存检索 token 和默认 hash embedding 输入，不写入 Room、
  `MemoryHit.text`、`buildContext`、长期记忆列表、远程 prompt 或普通会话记录。
- 普通 `Conversation`、非回答偏好、非结构化 `TaskState` 和 hidden
  `SuppressedTaskState` 不获得 alias；终态查询如“已取消提醒”不会召回仍活跃的
  Scheduled/Running 任务状态。
- 测试注入的 semantic runtime 启用时，embedding 输入仍使用原展示文本；检索同时保存
  原文 token 与 alias 后 token，避免 alias-only 命中被误标成普通 lexical recall。
  生产默认仍没有 LiteRT embedding runtime factory，memory asset 不会让该路径启用。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks \
  --tests com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest \
  --tests com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateSyncsVerifiedMemoryModelBeforeRebuildingMemoryIndex \
  --tests com.bytedance.zgx.pocketmind.PocketMindViewModelTest.localModeSemanticMemoryStatusAndPromptUseSemanticHit
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：定向记忆/语义状态测试、全量 JVM 单测、AndroidTest Kotlin 编译、
diff whitespace 检查、敏感串扫描和本地完整验证脚本通过。

## 2026-06-01 Device preflight script coverage 增量验证

本轮覆盖项：

- `scripts/test_validation_scripts.sh` 的 fake adb 增加 `FAKE_ABI_LIST` 和
  `FAKE_DATA_FREE_KB`，可以覆盖设备预检失败分支。
- 新增非 `arm64-v8a` 设备失败用例，确认 `scripts/install_and_test_device.sh`
  在 Gradle 组装前停止，并输出 ABI 不兼容提示。
- 新增 `/data` 可用空间低于 3 GB 失败用例，确认在 Gradle 组装前停止，并输出
  空间不足提示。
- 本轮只锁定既有设备预检行为，不改变真机安装、模拟器选择、instrumentation
  或产品代码。

验证命令：

```bash
bash -n scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/verify_emulator.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
```

结果：shell 语法检查和 validation script fake 回归通过，覆盖新增 ABI/空间失败
前置边界。

补充验证：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：全量 JVM 单测、AndroidTest Kotlin 编译、diff whitespace 检查、敏感串扫描
和本地完整验证脚本通过。

## 2026-06-01 Local remember control command 增量验证

本轮覆盖项：

- `记住：...` / `remember ...` 从远程可发送的普通聊天输入收窄为本地记忆控制命令。
- 显式 remember 命令会绕过 chat/action router、远程 runtime 和本地模型 runtime，
  直接 upsert 本地 `Preference` 长期记忆；会话中可见的控制命令与确认消息均为
  `LocalOnly`。
- 远程模型模式下，remember 命令不会作为当前 prompt 发送，也不会进入后续远程
  history；偏好内容和本地确认消息同样不会自动上传。
- 记忆存储失败时 fail closed：展示本地失败提示，不把 remember prompt 兜底发送
  给远程模型。
- remember 仍受待确认动作门禁保护；有 pending confirmation 时必须先确认或取消。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rememberCommandPersistsPreferenceMemoryOnceForDuplicateCommands' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rememberCommandReplacesConflictingPreferenceMemory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rememberCommandPersistsEnglishPreferenceMemory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rememberCommandBypassesRouterAndRemoteRuntime' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteRememberCommandDoesNotEnterLaterRemoteHistory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rememberCommandMemoryStoreFailureDoesNotFallbackToRemote' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rememberCommandWorksBeforeModelIsReady' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.forgetRememberCommandMemoryDoesNotReindexFromHistory'
```

结果：targeted remember 本地控制命令、远程模式保护、存储失败 fail-closed 和遗忘
后不从历史重新派生回归测试通过。

补充验证：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：全量 JVM 单测、AndroidTest Kotlin 编译、diff whitespace 检查、敏感串扫描
和本地完整验证脚本通过。

## 2026-06-01 RTF shared-input text-layer excerpt 增量验证

本轮覆盖项：

- 用户主动分享或选择 `application/rtf` / `text/rtf` 附件时，可以在本地读取
  有界 RTF 文本层摘录，最多读取 96 KiB、最多进入 prompt 4000 字符。
- `text/rtf` 不再走通用 `text/*` raw preview；RTF preview 只在附件 kind 为
  `Document` 且 MIME 为 RTF 时进入 shared-input prompt。
- RTF 摘录跳过常见 metadata / object / pict / style destination，并保持
  `LocalOnly` shared-input 边界；远程模式在构造 prompt 前拒绝，不会自动发送
  分享文本、RTF/Office 摘录、OCR 摘录、附件名或附件元数据。
- 本轮是 best-effort 文本层摘录，不实现完整富文档解析、版式理解、codepage
  保真、PDF 解析或旧版 Office 解析。

验证命令：

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedRichTextPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedTextPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedImageOcrPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedOfficeDocumentPreviewBeforeBuildingPrompt'
```

结果：targeted RTF/Text/OCR/Office shared-input 和远程模式保护回归测试通过。

补充验证：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：全量 JVM 单测、AndroidTest Kotlin 编译、diff whitespace 检查、敏感串扫描
和本地完整验证脚本通过。

## 2026-06-01 Value-free Skill checkpoint persistence 增量验证

本轮覆盖项：

- 新增独立 `agent_skill_run_checkpoints` Room 表和 `8 -> 9` 迁移，用于记录
  pending Skill confirmation 的 value-free checkpoint。
- checkpoint 只保存 schema version、run/request/step id、Skill request id、
  manifest id/version/hash、phase、已完成 step id、输出 key 名和 private-output
  refs；不保存 `SkillRunContinuation.outputs` 值、工具结果值、模型输出、原始
  用户输入、draft payload 或剪贴板/OCR/屏幕文本。
- `RoomAgentTraceStore` 在恢复 pending confirmation 时校验 checkpoint 与
  redacted `SkillPlan`、pending tool step 和当前 `ToolRegistry` 一致；损坏
  JSON、pending step/tool 改变、manifest 改变、输出 key 非规范或 private refs
  漂移都会 fail closed，并删除 pending/checkpoint。
- `SkillRunContinuation` 增加 value-free projection；该 projection 用于检查
  结构边界，不把 continuation 本体序列化到 Room。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest'
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest'
./gradlew :app:compileDebugAndroidTestKotlin
```

结果：targeted Skill checkpoint、TraceStore restore/fail-closed、Agent loop
回归测试通过；AndroidTest Kotlin 编译通过。

补充验证：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：全量 JVM 单测、AndroidTest Kotlin 编译、diff whitespace 检查、敏感串扫描
和本地完整验证脚本通过。

## 2026-06-01 OCR layout-preserving text excerpt 增量验证

本轮覆盖项：

- ML Kit OCR 输出从扁平 line 去重改为保留 recognized text block / line 顺序，
  block 之间用空行分隔，Latin/Chinese recognizer 输出跨源稳定去重。
- 输出仍是 bounded text excerpt；不加入坐标、bounding boxes、图片标签、caption、
  像素或视觉语义。
- 共享图片 OCR 与受确认的最近截图/最近图片 OCR 共用该格式化边界，LocalOnly、
  trace/audit redaction 和 remote-mode protection 不变。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.ImageTextExtractorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.recentScreenshotOcrSuccessReturnsLocalOnlyTextWithoutImageIdentifiers' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.recentImageOcrSuccessScansImagesAndReturnsLocalOnlyTextWithoutImageIdentifiers'
```

结果：targeted OCR layout-preserving text excerpt 回归测试通过。

补充验证：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：全量 JVM 单测、AndroidTest Kotlin 编译、diff whitespace 检查、敏感串扫描
和本地完整验证脚本通过。

## 2026-06-01 Office Open XML shared-input excerpt 增量验证

本轮覆盖项：

- 用户主动分享或选择的 Office Open XML `.docx` / `.xlsx` / `.pptx`
  附件可以在本地解析 ZIP XML 文本层，生成最多 4000 字符的用户可见摘录。
- 摘录只进入自动生成的 `LocalOnly` shared-input prompt；远程模型模式下不构造
  包含文件名、附件元数据或文档摘录的 prompt，也不会调用远程 runtime。
- PDF、旧版 Office 二进制、音频、视频、任意二进制文件仍保持 metadata-only；
  本轮不实现完整文档解析、PDF 解析、版式理解或语义理解。
- 解析器限制 ZIP entry 数量、XML entry bytes、总 XML bytes 和 prompt 字符数，
  并使用禁用外部实体的 XML parser。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedTextPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedImageOcrPreviewBeforeBuildingPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedOfficeDocumentPreviewBeforeBuildingPrompt'
```

结果：targeted Office Open XML shared-input excerpt 回归测试通过。

补充验证：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
```

结果：全量 JVM 单测、AndroidTest Kotlin 编译、diff whitespace 检查、敏感串扫描
和本地完整验证脚本通过。

## 2026-06-01 Periodic check startup reconcile 增量验证

本轮覆盖项：

- `BackgroundTaskScheduler` 新增 `reconcilePeriodicCheckOnStartup()`，Android
  实现委托 `PeriodicCheckScheduler`。
- App 启动恢复先重排 reminder alarm，再 reconcile `periodic-check-local`，
  然后重新加载 active tasks、history 和 typed periodic policy。
- Periodic check startup reconcile 会恢复 stale `Running`、重入队 enabled
  `Scheduled` WorkManager periodic work；入队失败时将本地任务标为 `Failed`。
- Disabled、terminal 或 fresh `Running` periodic check 不会在启动时被重入队或
  被错误改写。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateReconcilesPeriodicCheckBeforeLoadingBackgroundTasks' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateReschedulesReminderAlarmsBeforeLoadingBackgroundTasks'
```

结果：targeted periodic check startup reconcile 回归测试通过。

## 2026-06-01 Reminder audit metadata minimization 增量验证

本轮覆盖项：

- `ToolAuditRepository` 不再把 `schedule_reminder` / `cancel_reminder`
  成功事件的 stored summary 原样展示或持久化到审计 UI；写入审计库时只提取
  严格格式的 allowlisted task recovery metadata。
- 允许展示的字段限定为 `taskId`、`taskStatus`、`triggerAtMillis`、
  `recoveryToolName`、`recoveryTaskId`；提醒标题、正文、用户原文、邮箱、token
  或其他未声明 payload 不进入 `ToolAuditRecord.summary`。
- `ToolRegistryTest` 锁定 reminder/cancel output schema 不暴露 title/body/prompt/
  summary/text，避免未来把提醒内容加入成功结果契约。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.deniedSpecialAccessFailsPendingToolWithoutExecutingIt' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.specialAccessDenialSummaryUsesRequirementTitles' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.accessibilitySpecialAccessReturnUpdatesStatusTextWithoutExecutingTools'
```

结果：targeted reminder audit metadata minimization 回归测试通过。

补充验证：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
git diff --check
rg -n "<sensitive endpoint/model/key patterns>" . --glob '!**/build/**' --glob '!**/.gradle/**'
```

结果：全量 JVM 单测、AndroidTest Kotlin 编译、diff whitespace 检查通过；
敏感串扫描无命中。

## 2026-06-01 Accessibility special-access execution boundary 增量验证

本轮覆盖项：

- `read_current_screen_text` 的 Accessibility 屏幕文本授权返回路径与 Usage Access
  一样，只更新 UI 状态，不确认 pending tool、不执行工具、不读取屏幕文本。
- `read_current_screen_text` 确认前会重查 Accessibility special access；若未开启，
  ViewModel 以 `PermissionDenied` fail pending tool，返回
  `specialAccess/settingsAction` 结构化 metadata，且不调用 executor。
- pre-executor 保证由 JVM ViewModel 测试验证；UI-only AndroidTest 在无障碍未开启
  时无法区分 MainActivity 预检失败与 executor 防御失败，因此本轮以
  AndroidTest Kotlin 编译保持 instrumentation 源可用。
- `docs/agent_core_modules.md` 中的 Execution Boundary 状态改为反映当前代码：
  已覆盖 Usage Access 与 Accessibility screen text 两类 bounded special access；
  更广泛的特殊授权面仍待实现。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.accessibilitySpecialAccessReturnUpdatesStatusTextWithoutExecutingTools' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.deniedSpecialAccessFailsPendingToolWithoutExecutingIt' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.specialAccessDenialSummaryUsesRequirementTitles' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.currentScreenTextDeclaresAccessibilityAsSpecialAccessNotRuntimePermission' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.pendingSpecialAccessRequirementRestoresFromCurrentPendingConfirmationOnly'
```

结果：targeted Accessibility special-access boundary 回归测试通过。

## 2026-06-01 ToolResult output schema 执行边界增量验证

本轮覆盖项：

- `ToolSpec` 新增 `outputSchemaJson`，表示 successful `ToolResult.data`
  的结构契约，不是所有
  `ToolResult` 状态的通用契约。
- `Rejected`、`Failed`、`Cancelled` 保持结构化失败/取消结果；这些结果需要携带
  `ToolError`、取消原因或安全拒绝 metadata，但不要求满足 success data schema。
- `ValidatingToolExecutor` 在 delegate 返回后校验 success data；缺少必需字段、
  字段类型不匹配或 request id 不匹配时，返回非 retryable `InvalidResult`
  failed `ToolResult`，且只保留 `toolName` context。
- 设备上下文工具的私密输出字段仍声明在 output schema 中，并继续通过
  `privateOutputKeys` / `LocalOnly` 边界进入后续本地处理。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.CalendarAvailabilityToolExecutorTest'
```

结果：targeted Tool output schema / routing / device-context 回归测试通过。

## 2026-06-01 Pending confirmation payload minimization 增量验证

本轮覆盖项：

- `ToolSpec` 新增 `pendingArgumentAllowlist`，Room pending confirmation
  持久化只保留工具声明的安全参数键。当前非空 allowlist 仅覆盖
  `open_app_intent.packageName`、`open_app_deep_target.targetId/packageName`、
  `query_calendar_availability.start/end`、近期通知/文件/OCR/屏幕读取的数值
  上限，以及 `cancel_reminder.taskId`。
- `share_text`、`web_search`、地图搜索、邮件/日历/联系人草稿、联系人查询、
  `schedule_reminder` 和 `open_deep_link` 等 payload-bearing pending 不再跨
  重启恢复；Room row 不保存其 executable payload key/value，恢复时由
  `ToolRegistry.validate` 判定缺少必需参数并 fail closed。
- `nextActionInput` 不再写入 Room。当前进程内仍保留 active pending 的 raw
  snapshot 以支撑 UI/确认，进程重启后不再用 pending row 继续 sequential
  replan。
- Room 恢复路径会拒绝含 redacted executable payload、schema 校验失败或
  SkillPlan 边界不匹配的 pending row，并把所属 awaiting run 标记为
  `Failed`，避免不可见确认卡或 payload 复活。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest'
```

结果：targeted pending restore / fail-closed 回归测试通过。

## 2026-06-01 Restored Agent loop context 增量验证

本轮覆盖项：

- Room pending confirmation 不再保存显式 sequential 后续动作片段
  `nextActionInput`；完整 raw run input 仍不会写入 `agent_runs`、trace
  summary 或 audit。
- pending row 被确认/清理后，DB 不再保留该片段；Room store 仅在内存中保留到
  observation 结束，以便确认后的工具结果仍能规划下一步。
- 恢复 pending 时会从已持久化的 `ToolRequested` trace JSON 恢复历史
  request id 骨架，仅用于去重；不会恢复旧确认卡，也不会恢复旧 arguments /
  reason。
- 数据库版本升到 8，新增 `pending_agent_confirmations.nextActionInput`
  nullable column 并覆盖 7→8 migration；该列保留为兼容字段，当前写入值为
  null。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreRestoresPendingConfirmationWithoutPuttingRawArgumentsInTrace' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreHydratesPriorToolRequestsForRestoreDedupWithoutOldConfirmations' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredPendingConfirmationContinuesSequentialNextActionAfterObservation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredPendingConfirmationRejectsReplannedOldRequestId'

./gradlew :app:compileDebugAndroidTestKotlin
```

结果：通过。

## 2026-06-01 Foreground app trace privacy 增量验证

本轮覆盖项：

- `query_foreground_app` 的 `packageName` 与 `appLabel` 声明为 private tool
  outputs。
- Agent trace / audit / assistant observation 使用 redacted result summary，
  不再持久化前台应用名或包名。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.privateToolOutputsAreDeclaredByToolPolicy' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.foregroundAppObservationRedactsAppIdentityFromTrace'
```

结果：通过。

## 2026-06-01 Background terminal-state race 增量验证

本轮覆盖项：

- Reminder / periodic check 的失败回写改为条件状态转移，旧快照不会把
  `Cancelled`、`Delivered` 或其他终态覆盖为 `Failed`。
- `scheduledOrRunning()` 生产路径现在返回所有 `Scheduled` 与 `Running`
  任务，启动恢复不会漏掉投递中卡住的 reminder。
- 后台任务取消失败分支会重新加载 active/history/policy，避免竞态失败后 UI
  继续显示过期状态。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ReminderAlarmReceiverTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRemovalCoordinatorTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.cancelRunningBackgroundTaskFailureRefreshesStaleTaskLists' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateLoadsRunningBackgroundTasksWithoutRemoteWork'
```

结果：通过。

## 2026-06-01 Direct parser non-action guard 增量验证

本轮覆盖项：

- Action planner 和 built-in Skill runtime 对句首否定输入 fail closed，覆盖
  地图、邮件、日程、Web 搜索、深链、联系人、当前屏幕、日历忙闲和最近 OCR。
- 剪贴板上下文读取拒绝否定/讨论输入，避免“不要读取剪贴板/如何读取剪贴板”
  进入读取确认。
- 复合顺序输入不再被 skill-first 拒绝后由 rule action planner 接住后半段，
  避免“总结剪贴板并分享，然后打开 Wi-Fi 设置”直接变成 Wi-Fi 确认卡。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstSequentialInputFallsBackToAnswerWhenRulePlannerRejectsIt'
```

结果：通过。

## 2026-06-01 Legacy 会话隐私迁移增量验证

本轮覆盖项：

- 3→4 数据库迁移后，已有 `chat_messages` 行写为 `LocalOnly`，避免无
  provenance 的旧历史进入 remote history。
- 旧 SharedPreferences `sessions_json` 导入的消息写为 `LocalOnly`。
- Android instrumentation migration 测试已更新并通过 androidTest 编译。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin \
  :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.runtime.RemoteChatRuntimeTest.buildChatCompletionBody_excludesLocalOnlyHistory'
```

结果：通过。

## 2026-06-01 周期检查状态机竞态增量验证

本轮覆盖项：

- 周期检查 worker 只允许 `Scheduled -> Running` 与
  `Running -> Scheduled/Failed` 条件转移。
- 用户在 worker 通知过程中关闭周期检查时，`Cancelled` 终态不会被 worker
  completion 反写成 `Scheduled`。
- `scheduledOrRunning()` 不会在关闭后重新显示 `periodic-check-local`。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRemovalCoordinatorTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ReminderAlarmReceiverTest'
```

结果：通过。

## 2026-06-01 Agent 隐私与状态口径增量验证

本轮覆盖项：

- Clipboard context 的 Skill-first 路径拒绝 sequential follow-up，避免
  “读取剪贴板，然后打开 Wi-Fi 设置”只执行前半段。
- Clipboard summary share 的 Skill-first 路径拒绝 sequential follow-up，避免
  “总结剪贴板并分享，然后打开 Wi-Fi 设置”只执行前半段。
- 未知持久化 `MessagePrivacy` 恢复为 `LocalOnly`，从 remote history 边界
  fail-closed。
- Agent trace 的 `ToolRequested` 和 `UseTool` planning 摘要/JSON 不再保存
  参数化 `request.reason`，仅保留工具名、参数 key 和草稿标题。
- 普通 Chat 的 Agent trace 在生成完成后回写 `Completed`，避免重启 stale
  recovery 把已经成功回答的 run 标成 `Failed`。
- 工具结果里的未知 `privacy` metadata 按 `LocalOnly` 处理，防止未来值或坏值
  被当成可远程续写。
- 后台任务 UI 的 active 列表与 task-state memory 口径一致，包含
  `Scheduled` 与 `Running`；取消入口只展示给 still-`Scheduled` 任务。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.data.SessionRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest'
```

结果：通过。

补充验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest'
```

结果：通过。

补充验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.pureChatAnswerCompletesAgentTraceRun' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.unknownToolResultPrivacyIsTreatedAsLocalOnlyBeforeRemoteContinuation' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeProtectsGenericLocalOnlyContinuationAsLocalToolResult'
```

结果：通过。

## 2026-06-01 Cancel reminder skill-first routing 增量验证

本轮覆盖项：

- 显式“取消/撤销提醒 + `task-*` id”请求可由 built-in Skill runtime 直接规划为
  受确认保护的 `cancel_reminder`，不再依赖 action planner。
- Shared cancel-reminder parser 只接受 reminder/background-task 取消语义和
  `task-*` id；拒绝无 task id、API/实现/解释、否定命令，以及取消日历/联系人/
  邮件等非 reminder 任务。
- `cancel_reminder` 继续归属 `reminder_skill`，风险级别为
  `MediumDraftOrNavigation`；registry schema 也要求 `taskId` 匹配
  `^task-[A-Za-z0-9_-]+$`，避免绕过 parser。
- 权限和执行边界保持本地：取消提醒不请求 Android runtime permission 或
  special access；仍必须用户确认，并只在对应 still-`Scheduled` task 被平台取消且
  本地状态更新后报告成功。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersCancelReminderDraftWithTaskId' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansCancelReminderSkillFirstWithoutActionDraft' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansReminderAsBackgroundToolStep' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstCancelReminderBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.validatesCancelReminderTaskId'
```

结果：通过。

## 2026-06-01 Contact draft skill-first routing 增量验证

本轮覆盖项：

- 显式“新建/添加/创建联系人 + 姓名”请求可由 built-in Skill runtime 直接规划为
  受确认保护的 `create_contact_draft`，不再依赖 action planner。
- Shared contact-draft parser 只提取草稿字段 `name`/`email`/`phone`；拒绝空
  新建请求、联系人权限、ContactsContract/API/页面/组件/实现讨论、删除/编辑/
  导出联系人、联系人查询和否定命令。
- 新增 `contact_draft_skill` manifest，风险级别为 `MediumDraftOrNavigation`；
  registry 继续校验 `create_contact_draft` 的 `name` 必填和 closed schema。
- 权限和数据边界保持分离：`create_contact_draft` 是 ExternalDraft，只打开系统
  联系人插入页，不读取通讯录、不请求 `READ_CONTACTS`、不保存或提交联系人；
  `query_contacts` 仍是唯一需要 `READ_CONTACTS` 的联系人读取工具。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.contactDraftRequiresExplicitNameAndRejectsNonDraftInputs' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.contactQueryRequiresExplicitQueryAndRejectsNonLookupInputs' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansContactDraftWithoutActionDraftWhenCommandIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansContactLookupWithoutActionDraftWhenQueryIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstContactDraftBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.contactDraftSkillFirstConfirmationDoesNotRequestContactsPermission' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.exposesSpecsForSupportedActionsWithConfirmationRequired'
```

结果：通过。

## 2026-06-01 最近图片 OCR skill-first routing 增量验证

本轮覆盖项：

- 明确最近图片/照片文字 OCR 请求可由 built-in Skill runtime 直接规划为
  受确认保护的 `read_recent_image_ocr`。
- 确认后最多扫描最近 3 张图片像素并在本地提取第一条 OCR 摘录；结果为
  `LocalOnly`，`ocrText` 是 private tool output，不能直接绑定到后续工具参数。
- 普通“最近图片”仍只走 `query_recent_files(kind="images")` metadata-only；
  不读取图片像素或 OCR 文本。
- 权限边界为 Android 13+ `READ_MEDIA_IMAGES`，Android 12- legacy storage
  read permission。
- 该能力不是当前屏幕捕获、图片语义理解、看图描述、任意媒体 OCR 或全相册
  扫描；所有/大量/超过 3 张图片 OCR、实现/API/权限讨论和否定命令应拒绝。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersRecentImageOcrOnlyWhenTextExtractionIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansRecentImageOcrWithoutActionDraftWhenCommandIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansRecentMediaFilesWithoutActionDraftWhenMetadataRequestIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstRecentImageOcrBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.recentImageOcrSkillFirstConfirmationStillRequestsImageReadPermission'
```

结果：通过。

## 2026-06-01 最近截图 OCR skill-first routing 增量验证

本轮覆盖项：

- 明确最近 1 张截图文字/OCR 请求可由 built-in Skill runtime 直接规划为
  受确认保护的 `read_recent_screenshot_ocr`。
- 确认后只读取最近 1 张截图像素并在本地 OCR；结果为 `LocalOnly`，
  `ocrText` 是 private tool output，不能直接绑定到后续工具参数。
- 权限边界为 Android 13+ `READ_MEDIA_IMAGES`，Android 12- legacy storage
  read permission。
- 该能力不是当前屏幕捕获、视觉理解、任意媒体 OCR，也不支持多张截图
  OCR；多张截图 OCR 请求应拒绝。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersRecentScreenshotOcrOnlyWhenTextExtractionIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansRecentScreenshotOcrWithoutActionDraftWhenCommandIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansRecentMediaFilesWithoutActionDraftWhenMetadataRequestIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstRecentScreenshotOcrBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.recentScreenshotOcrSkillFirstConfirmationStillRequestsImageReadPermission'
```

结果：通过。

## 2026-06-01 Current-screen text skill-first routing 增量验证

本轮覆盖项：

- 显式“读取/总结当前屏幕文字”请求可由 built-in Skill runtime 直接规划为
  `read_current_screen_text` 待确认工具，不再依赖 action planner。
- Shared current-screen parser 只接受明确当前屏幕/当前界面的 Accessibility
  文本快照意图；拒绝“这页/页面内容”泛称、截图、OCR、像素、视觉/语义屏幕理解、
  API/实现/权限讨论和否定命令。
- 新增 `current_screen_text_context_skill` manifest，风险级别为
  `MediumDraftOrNavigation`；工具继续走 registry schema、safety、audit 和用户确认。
- 权限和数据边界保持最小化：skill-first pending confirmation 仍只声明
  Accessibility special access，不请求 Android runtime permission；raw
  `screenText` 仍只用于本地 continuation，并由 trace/audit 脱敏。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersCurrentScreenTextOnlyForAccessibleTextRequests' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansCurrentScreenTextWithoutActionDraftWhenCommandIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstCurrentScreenTextBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.currentScreenTextSkillFirstConfirmationDeclaresAccessibilitySpecialAccessOnly'
```

补充回归：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'
```

结果：通过。

## 2026-06-01 Calendar availability skill-first routing 增量验证

本轮覆盖项：

- 显式“查忙闲 + 两个 ISO 时间”请求可由 built-in Skill runtime 直接规划为
  `query_calendar_availability` 待确认工具，不再依赖 action planner。
- Shared calendar-availability parser 要求忙闲/availability/free-busy 意图和
  两个带时区 ISO-8601 时间同时存在；拒绝空忙闲请求、自然语言日期、日历权限、
  API/实现/设计讨论。
- 新增 `calendar_availability_skill` manifest，风险级别为 `LowReadOnly`；
  工具继续走 registry schema、provider 31 天窗口校验、safety、audit 和用户确认。
- 权限和数据边界保持最小化：skill-first pending confirmation 仍请求
  `READ_CALENDAR`；结果只返回 busy/free blocks，不返回事件标题、地点、参与人、
  备注或 calendar id。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersCalendarAvailabilityDraftWhenIsoWindowIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansCalendarAvailabilityWithoutActionDraftWhenIsoWindowIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstCalendarAvailabilityBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.calendarAvailabilitySkillFirstConfirmationStillRequestsCalendarPermission' \
  --tests 'com.bytedance.zgx.pocketmind.tool.CalendarAvailabilityToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.validatesCalendarAvailabilityStartAndEndArguments' \
 --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.exposesSpecsForSupportedActionsWithConfirmationRequired'
```

补充回归：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.CalendarAvailabilityToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'
```

结果：通过。

## 2026-06-01 Contact lookup skill-first routing 增量验证

本轮覆盖项：

- 显式“查联系人 Alice / look up Alice in contacts”请求可由 built-in Skill
  runtime 直接规划为 `query_contacts` 待确认工具，不再依赖 action planner。
- Shared contact parser 要求明确查询对象；拒绝裸“联系人/contact(s)”、空
  “查询联系人”、联系人权限、ContactsContract/API/实现讨论、否定、导出/全量
  列表、编辑/新建联系人等非查询意图。
- 新增 `contact_lookup_skill` manifest，风险级别为 `LowReadOnly`；registry
  schema 将 `maxCount` 限制为 `1..20`，executor 也按同一上限规范化。
- 权限和隐私边界保持最小化：skill-first pending confirmation 仍只请求
  `READ_CONTACTS`；工具只返回 `name`/`phone`，不返回 email、头像、地址、备注、
  contact id 或全量通讯录导出；`query` 和 `contactsJson` 标记为 private
  output，trace/audit 中使用“已读取联系人摘要”替代手机号明文。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.parsesContactQueryCallOutput' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.contactQueryRequiresExplicitQueryAndRejectsNonLookupInputs' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansContactLookupWithoutActionDraftWhenQueryIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstContactLookupBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.contactObservationRedactsPrivateTraceFields' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.contactLookupSkillFirstConfirmationStillRequestsContactsPermission' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.contactSummarySuccessReturnsMinimalLocalOnlyFields' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.contactSummaryFailureIsRetryableAndLocalOnly' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.contactSchemaRejectsMissingQueryAndUnsupportedMaxCount' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.privateToolOutputsAreDeclaredByToolPolicy' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.exposesSpecsForSupportedActionsWithConfirmationRequired'
```

补充回归：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'
```

结果：通过。

## 2026-06-01 Current-app notification skill-first routing 增量验证

本轮覆盖项：

- 显式“最近通知/当前应用通知摘要”请求可由 built-in Skill runtime 直接规划为
  `query_recent_notifications` 待确认工具，不再依赖 action planner。
- Shared notification parser 继续接受中文“最近通知/最近 N 条通知/通知摘要”
  和英文 `current app` / `this app` / `PocketMind` 通知摘要请求；拒绝裸
  `notification(s)`、`recent app notifications`、通知权限/渠道/push/listener、
  系统/全局/其他应用通知和通知栏语义，避免越过当前应用边界。
- 新增 `recent_notifications_context_skill` manifest，风险级别为
  `LowReadOnly`；registry schema 将 `maxCount` 限制为 `1..20`，executor
  也按同一上限规范化。
- 权限边界保持最小化：`query_recent_notifications` 不声明 Android runtime
  permission 或 special access；通知被系统关闭时返回结构化
  `PermissionDenied`，不会自动请求 `POST_NOTIFICATIONS`。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.recentNotificationSummaryMatchesCurrentAppOnlyBoundary' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansRecentNotificationsWithoutActionDraftWhenCurrentAppRequestIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstRecentNotificationsBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.recentNotificationsDeclareNoRuntimePermissionOrSpecialAccess' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.notificationSummarySuccessReturnsLocalOnlyMetadataOnlyJson' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.notificationSummaryPermissionDeniedAndFailureAreStructured' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.recentNotificationSchemaRejectsUnsupportedMaxCount' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.exposesSpecsForSupportedActionsWithConfirmationRequired'
```

补充回归：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'
```

结果：通过。

## 2026-06-01 Foreground app skill-first routing 增量验证

本轮覆盖项：

- 显式“当前应用/前台应用是什么”请求可由 built-in Skill runtime 直接规划为
  `query_foreground_app` 待确认工具，不再依赖 action planner。
- Shared foreground-app parser 拒绝“前台服务”“current app architecture”
  和实现/设计讨论，避免把 Android 开发语境误触为设备上下文读取。
- 新增 `foreground_app_context_skill` manifest，风险级别保持 `LowReadOnly`；
  工具仍声明 Usage Access 为 special app access，不伪装成 Android runtime
  permission，确认后仅读取应用名与包名。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersForegroundAppOnlyForExplicitCurrentAppRequests' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansForegroundAppWithoutActionDraftWhenCommandIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.exposesVersionedManifestsForCoreSkills' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstForegroundAppBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.foregroundAppDeclaresUsageAccessAsSpecialAccessNotRuntimePermission' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.foregroundAppSuccessReturnsLocalOnlyMinimalFields' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.foregroundAppPermissionDeniedAndFailureAreRetryableLocalFailures' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.declaresDeviceContextTools'
```

结果：通过。

## 2026-06-01 HTTPS deep link skill-first routing 增量验证

本轮覆盖项：

- 显式“打开/访问 HTTPS 链接”请求可由 built-in Skill runtime 直接规划为
  `open_deep_link` 待确认工具，不再依赖 action planner。
- Shared deep-link parser 要求打开意图和 `https://` URI 同时存在；裸链接、
  解释类、否定类、`http/file/javascript` 等非 HTTPS scheme 不触发。
- URI-like 文本不会再被 package-name app intent parser 误判成包名启动请求。
- 新增 `deep_link_navigation_skill` manifest，仍经 registry schema、safety、
  audit 和用户确认，工具层继续只允许 HTTPS 外部导航。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersDeepLinkDraftForExplicitUri' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansHttpsDeepLinkWithoutActionDraftWhenCommandIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstHttpsDeepLinkBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit'
```

补充回归：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'
```

结果：通过。

## 2026-06-01 Recent media metadata skill-first routing 增量验证

本轮覆盖项：

- 最近图片、截图、视频、音频等媒体 metadata 请求可由 built-in Skill runtime
  直接规划为受确认保护的 `query_recent_files`，不再依赖 action planner。
- Action planner 与 Skill runtime 复用同一组 recent-files parser；Skill-first
  只覆盖媒体元数据，文档、下载、全部文件仍保留在 action fallback 路径。
- Parser 明确拒绝“不要查询最近图片”“最近图片权限怎么申请”、
  “recent screenshots API”和“how to read recent images”等否定/讨论输入，
  避免把权限、API 或解释请求变成文件 metadata 读取确认。
- Parser 明确拒绝“识别/提取/文字/OCR/text”等内容读取意图，避免抢走
  `read_recent_screenshot_ocr` 和 `read_recent_image_ocr`。
- 新增 `recent_files_context_skill` manifest，风险级别保持 `LowReadOnly`，
  工具执行仍经 registry schema、权限策略、safety、audit 和用户确认。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstRecentMediaFilesBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'
```

补充回归：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest'
```

结果：通过。

## 2026-06-01 Information lookup skill-first routing 增量验证

本轮覆盖项：

- `web_search` 的显式搜索请求可由 built-in Skill runtime 直接规划为待确认工具，
  不再依赖 action planner。
- Action planner 与 Skill runtime 复用同一组搜索 parser。Parser 只接受明确
  搜索/网页搜索/网络搜索/百度/Google/Bing/look up 等表达并要求非空 query；
  裸“查一下”不再被推断为网页搜索，避免绕过窄口径 Skill-first 边界。
- 反例覆盖空搜索、解释类、否定类和代码/错误排查语境输入，避免“网页搜索是什么”
  或“查一下这个错误原因”误触工具确认。
- 顺序任务（如“先搜 Kotlin，然后打开 Wi-Fi 设置”）不会被一跳 Skill-first
  parser 抢走，继续交给 Agent loop / action runtime 的观察后重规划链路。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.infersDraftForNaturalLanguageWebSearch' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest.plansWebSearchWithoutActionDraftWhenCommandIsExplicit' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstWebSearchBypassesActionPlannerAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.successfulObservationCanPlanNextToolAndRequestConfirmationAgain' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.unverifiedExternalLaunchDoesNotAutoPlanNextTool' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.invalidActionDraftIsRejectedBeforeConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit'
```

补充回归：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.validatesWebSearchQueryArgument' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest' \
  --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditRepositoryTest.recentAuditEventsDoesNotExposeToolParametersFromPlannedSummary'
```

结果：通过。

## 2026-06-01 Device settings skill-first routing 增量验证

本轮覆盖项：

- `open_wifi_settings`、`open_usage_access_settings`、`open_flashlight_settings`
  的显式设置入口请求可由 built-in Skill runtime 直接规划为待确认工具，不再依赖
  action planner。
- Action planner 与 Skill runtime 复用同一组设备设置 parser，继续经过 registry
  validation、safety、audit 和用户确认，不直接打开系统设置页。
- 反例覆盖解释类、实现类、API 类、否定类输入，避免“Wi-Fi 是什么”“Wi-Fi 设置页面
  怎么设计”“Usage Access API 怎么用”或“不要打开使用情况访问权限设置”误触工具确认。
- 原 Wi-Fi action-planner fallback 测试保留，通过关闭 direct `plan(input)` 的
  test skill runtime 继续验证 action draft 附加 SkillPlan 的兼容路径。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.wifiActionInputRequestsConfirmationBeforeExecution' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstDeviceSettingsBypassActionPlannerAndRequestConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit'
```

结果：通过。

## 2026-06-01 Parameterized skill-first draft routing 增量验证

本轮覆盖项：

- `search_maps`、`compose_email`、`create_calendar_event` 的显式命令可由
  built-in Skill runtime 直接规划为待确认工具，不再依赖 action planner 先抽参。
- Action planner 与 Skill runtime 复用同一组参数 parser，继续经过 registry
  validation、safety、audit 和用户确认，不直接执行外部 App。
- 反例覆盖解释类、否定类和编程语境输入，避免“查到错误原因”“不要发邮件”
  或 `add event listener` 误触工具确认。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstMapEmailAndCalendarBypassActionPlannerAndRequestConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.validatesRequiredArgumentsForDraftTools' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest'
```

结果：通过。

## 2026-06-01 Shared text and scheduled task state boundary 增量验证

本轮覆盖项：

- `SharedInput` 对 Android share intent / in-app picker 的直传文本做换行归一、
  控制字符过滤和 4000 字符上限。
- 超长直传文本在 prompt 中显式标记“分享文本（已截断）”；普通短文本不增加额外前缀。
- 附件 `text/*` 摘录、image OCR 摘录、PDF/Office metadata-only 边界保持不变。
- `cancelScheduled` / `deleteScheduled` 改为 Scheduled-only 条件更新，避免旧快照
  覆盖已进入 `Running` / `Delivered` 等状态的后台任务。
- Fake DAO 与 Room DAO 使用相同条件更新语义，新增取消与提醒投递竞争的回归测试。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRemovalCoordinatorTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ReminderAlarmReceiverTest' \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.localSharedInputDoesNotEnterLaterRemoteHistory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedTextPreviewBeforeBuildingPrompt'
```

结果：通过。

## 2026-06-01 Skill-first direct text sharing 增量验证

本轮覆盖项：

- `share_text` 的显式文本请求（如“分享这段文字：...”）可由 built-in Skill
  runtime 直接规划为待确认工具，不再依赖 action planner 判定。
- share 文本解析逻辑抽到 `ShareTextActionParser`，Action planner 与 Skill
  runtime 复用同一组触发和参数提取规则。
- 普通讨论类和否定类输入不触发系统分享工具，避免“分享一下你的看法”、
  “不要分享这段文字”、“how to share this text”或 “don't share this text”
  进入分享确认。
- `ShareTextActionParser` 只在命令头部判断否定/讨论意图，待分享正文中的
  “不要分享”或 “don't share” 不会误杀明确分享请求。
- 剪贴板总结并分享的 Skill-first 路径拒绝否定和讨论输入，避免读取剪贴板
  的组合 skill 被“不要总结剪贴板并分享/如何总结剪贴板并分享”误触发。
- Agent loop skill-first 路径会进入确认，不执行工具；audit 计划事件不记录待分享原文。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.skillFirstShareTextBypassesActionPlannerAndRequestsConfirmation'
```

结果：通过。

## 2026-06-01 Tool private-output policy / special-access restore 增量验证

本轮覆盖项：

- `ToolSpec` 增加私密输出字段策略，`read_clipboard.text`、
  recent screenshot/image OCR 的 `ocrText`、以及 current-screen
  Accessibility 的 `screenText` 由 Tool Registry 统一声明。
- `SkillRunProgressor` 的 private-output fence 与
  `AgentLoopRuntime` 的 trace redaction 复用同一策略来源，避免新增私密工具时
  下游各自硬编码字段名。
- special-access 设置页跳转前保存 pending requirement id；Activity 重建后只从
  当前 pending confirmation 的 requirements 中反查恢复。设置页返回仍只汇报
  special-access 结果，不确认 pending tool、不执行工具。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.clipboardObservationBuildsContinuationPromptAndRedactsTrace' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.recentScreenshotOcrObservationBuildsLocalPromptAndRedactsTrace' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.recentImageOcrObservationBuildsLocalPromptAndRedactsTrace' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.currentScreenTextObservationBuildsLocalPromptAndRedactsTrace' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.specialAccessReturnUpdatesStatusTextWithoutExecutingTools'
```

结果：通过。

## 2026-06-01 Current-screen Accessibility text snapshot 增量验证

本轮覆盖项：

- 新增受确认保护的 `read_current_screen_text` 工具；它通过 Android
  Accessibility service 读取当前 Accessibility 文本节点快照，并将结果标记为
  `LocalOnly` / `requiresLocalModel=true`。
- 该能力不是截图、不是 OCR、不是像素读取，也不是视觉或语义屏幕理解；
  失败时不应自动退化为截图、OCR 或屏幕扫描。
- raw `screenText` 不进入 Agent trace、tool audit、持久工具观察消息或远程
  模型 prompt；远程模式应阻断自动 continuation，并提示用户切换本地模型或
  手动粘贴愿意上传的内容。
- Accessibility 授权按 special access 建模，不进入 Android runtime permission；
  Manifest 只声明受系统绑定的 `AccessibilityService`，不请求手势、截图或按键过滤能力。
- Skill private-output fence 禁止将 `screenText` 直接绑定到分享或外部工具参数。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.AndroidManifestTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.currentScreenTextObservationBuildsLocalPromptAndRedactsTrace' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeProtectsCurrentScreenTextBeforeRemoteContinuation' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest'

./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest'

bash -n scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
git diff --check
scripts/verify_local.sh
```

设备验证：

```bash
scripts/install_and_test_device.sh
```

结果：当前环境没有连接已授权 Android 设备，脚本在执行安装前以
`Connect exactly one authorized Android device, or set ANDROID_SERIAL to select one.`
退出。

## 2026-06-01 Recent image OCR tool 增量验证

本轮覆盖项：

- 新增独立受确认保护的 `read_recent_image_ocr` 工具，用于用户明确要求
  识别最近图片/照片文字时扫描最近图片像素并在本地提取 OCR 摘录。
- `query_recent_files(kind="images")` 继续保持 metadata-only；图片 OCR
  是单独工具，不返回 MediaStore id、URI、路径、原图或像素。
- 默认/最大扫描窗口限制为最近 3 张图片，返回第一条有界 OCR 摘录；
  结果标记 `LocalOnly` / `requiresLocalModel=true`。
- Agent observation 会用本地模型 continuation 处理图片 OCR；远程模式
  阻断自动 continuation，并提示已保护图片 OCR 内容。
- trace/audit 会把 `ocrText` 脱敏；Skill runner 将
  `read_recent_image_ocr.ocrText` 视为私有输出，不能直接绑定到后续
  `share_text` 等外发工具参数。
- Android 13+ 只请求 `READ_MEDIA_IMAGES`，Android 12- 使用
  `READ_EXTERNAL_STORAGE`；权限 rationale 明确像素读取和 OCR 边界。
- 该能力不声明当前屏幕捕获、图片语义理解、任意媒体 OCR 或媒体内容理解。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolSchemaContractTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.recentImageOcrObservationBuildsLocalPromptAndRedactsTrace' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeProtectsRecentImageOcrBeforeRemoteContinuation'
```

## 2026-06-01 Preference memory conflict resolution 增量验证

本轮覆盖项：

- `MemoryRepository` 为显式长期偏好新增轻量冲突族识别：回答长短
  (`response-length`) 与回答语言 (`response-language`)。
- 新的同族显式偏好会删除旧的同族偏好记录，再写入当前偏好，避免
  `记住：回答尽量简洁` 与 `记住：回答要详细` 同时进入长期记忆。
- 不同偏好族仍可共存，例如回答长短和回答语言不会相互覆盖。
- 当时 `PocketMindViewModel` 仍在用户消息成功进入会话后持久化 `记住` 偏好；
  冲突替换后长期记忆 UI 和 in-memory 索引同步展示当前偏好。该入口口径已被
  2026-06-01 Local remember control command 增量验证收窄为本地控制命令。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.explicitPreferenceConflictKeyRecognizesResponseFamilies' \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.conflictingResponseLengthPreferenceReplacesOlderRecord' \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.unrelatedResponsePreferenceFamiliesCanCoexist' \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.combinedResponsePreferenceReplacesBothFamilies' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rememberCommandReplacesConflictingPreferenceMemory'
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'
bash -n scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/verify_local.sh
git diff --check
```

验证层补充：

- `scripts/verify_local.sh` 现在会先执行 shell 语法检查和
  `scripts/test_validation_scripts.sh`，避免设备选择、无设备提前退出、
  `ANDROID_SERIAL` 路径在本地 gate 外悄悄失效。
- README 与真机验收文档不再硬编码 instrumentation 测试数量，要求记录
  runner 实际报告的测试总数。

## 2026-05-31 Skill private-read continuation precedence 增量验证

本轮覆盖项：

- `AgentLoopRuntime` 在工具观察成功后会优先检查当前 `SkillPlan` 是否有依赖该工具的
  `ModelStep`；命中时使用 Skill 声明的 title/instruction/input binding 生成
  continuation prompt，不再被 clipboard/OCR 硬编码 prompt 抢先。
- clipboard/OCR 仍保持本地模型要求和 trace/audit 脱敏；没有声明式 `ModelStep` 的
  one-off 私密读取继续走原有兜底 prompt。
- Room 恢复后的 run input 如果已经是 `[redacted]`，Skill continuation 会回退到
  pending `SkillPlan.request.arguments["input"]`，避免模型 prompt 丢失用户原始请求。
- generic `ModelStep` 会尊重工具结果里的 `privacy=LocalOnly` /
  `requiresLocalModel=true`，即使该模型步骤声明 `keepsSensitiveInputLocal=false`，
  也会要求本地模型继续处理。
- 远程模式遇到非 clipboard/OCR 的 local-only continuation 时，UI 使用“本地工具结果”
  保护文案，不再误报为“剪贴板内容”，也不会调用 remote runtime。
- `READ_RECENT_SCREENSHOT_OCR.ocrText` 直接绑定到后续工具参数会被
  `SkillRunProgressor` 拒绝，和 clipboard private output fence 对齐。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.clipboardSummarySharePlansShareAfterLocalModelResult' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepOutputBindsToDependentToolStepAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.restoredClipboardSummaryPendingContinuesWithModelAndPlansShareConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.clipboardReadObservationBuildsLocalPromptAndRedactsTrace' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.recentScreenshotOcrObservationBuildsLocalPromptAndRedactsTrace'
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.ocrSkillModelStepTakesPrecedenceOverPrivateReadFallbackPrompt' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.localOnlyToolResultMetadataForcesGenericModelContinuationLocal' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest.rejectsScreenshotOcrPrivateOutputBindingToToolArgument' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeProtectsGenericLocalOnlyContinuationAsLocalToolResult'
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest'
scripts/verify_local.sh
git diff --check
rg -n --hidden -S \
  "(AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|sk-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|-----BEGIN (RSA |DSA |EC |OPENSSH |PGP )?PRIVATE KEY-----|password\s*=\s*['\"][^'\"]+['\"]|secret\s*=\s*['\"][^'\"]+['\"]|token\s*=\s*['\"][^'\"]+['\"])" \
  --glob '!build/**' --glob '!**/.gradle/**' --glob '!app/build/**' \
  --glob '!**/src/test/**' --glob '!**/src/androidTest/**' .
```

结果：

- 通过：上述 targeted JVM 回归测试，以及 AgentLoopRuntime、SkillRunProgressor、
  PocketMindViewModel 的全量 targeted JVM 回归。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 Generic Skill model continuation 增量验证

本轮覆盖项：

- `AgentLoopRuntime` 不再只为 clipboard/OCR 生成硬编码 continuation；当
  当前工具属于恢复后的 `SkillPlan` 且后续存在依赖该工具的 `ModelStep` 时，
  会从工具结果绑定模型输入、生成下一段模型 prompt，并继续进入
  `GeneratingAnswer`。
- 模型输出仍通过 `SkillRunProgressor.nextToolAfterModelOutput()` 绑定到后续
  `ToolStep.argumentBindings`，然后重新进入 `AwaitingUserConfirmation`；后续工具
  不会被自动执行。
- 本轮保持既有隐私边界：不持久化完整原始 `SkillRunContinuation` 对象；Room
  只恢复无私密 executable payload 的 active pending confirmation snapshot +
  redacted `SkillPlan`，私密 `outputs/privateOutputRefs/trace` 不进入持久层。
- 多段流程仍覆盖 `web_search -> model -> share_text -> model ->
  open_wifi_settings` 的进程内 continuation。当前 pending payload 最小化策略
  下，若在 `share_text` payload 确认点进程重启，则该 pending 不再恢复，而是
  fail closed。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest'
scripts/verify_local.sh
git diff --check
rg -n --hidden -S \
  "(AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|sk-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|-----BEGIN (RSA |DSA |EC |OPENSSH |PGP )?PRIVATE KEY-----|password\s*=\s*['\"][^'\"]+['\"]|secret\s*=\s*['\"][^'\"]+['\"]|token\s*=\s*['\"][^'\"]+['\"])" \
  --glob '!build/**' --glob '!**/.gradle/**' --glob '!app/build/**' \
  --glob '!**/src/test/**' --glob '!**/src/androidTest/**' .
```

结果：

- 通过：targeted JVM AgentLoopRuntime 和 SkillRunProgressor 回归测试。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 Reminder PendingIntent identity 增量验证

本轮覆盖项：

- Reminder alarm `PendingIntent` identity 不再只依赖 `task.id.hashCode()`；
  新版 alarm 使用固定 requestCode 和包含 URL-escaped opaque task id 的 Intent
  `data`，因此 `"Aa"` / `"BB"` 这类 Java hash 碰撞 id 不会共享同一个 alarm
  identity。
- reminder 取消会同时尝试新的 data URI identity 和旧版 no-data identity，
  兼容已经由旧构建登记的 alarm。
- reminder boot reschedule 在登记新版 alarm 后会清理旧版 hash-only identity，
  避免升级后新旧 alarm 双重触发。
- 保留 alarm Intent extras 里的 task id 作为 receiver 读取入口；identity 用
  data URI，投递仍以本地 DB task row 为准。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.ReminderAlarmIdentityTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest'
./gradlew :app:assembleDebugAndroidTest
scripts/verify_local.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：targeted JVM ReminderAlarmIdentity 和 ScheduledTaskRepository
  reschedule cleanup 回归测试。
- 通过：`assembleDebugAndroidTest`，新增真实 Android `PendingIntent`
  identity instrumentation 测试编译通过；连接设备执行仍属于后续真机验收。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 Recent files cursor boundary 增量验证

本轮覆盖项：

- `AndroidRecentFileProvider` 将 MediaStore cursor 行转换为惰性 metadata
  sequence，并在收集到 `maxCount` 个匹配文件后立即停止读取后续行。
- `RecentFilesToolExecutor` 保留 provider 返回的 `PermissionDenied(reason)`，
  因此 Android 13+ 非媒体文件需要系统文件选择器授权时，用户可见失败原因不再被
  泛化为普通文件权限缺失。
- `ToolRegistry`、动作规划 prompt 和确认摘要都明确 Android 13+ `all` 只表示
  已授权媒体，`documents`、`downloads`、`others` 需要系统文件选择器授权；这类
  非媒体拒绝不会被标记为同一工具可重试。
- `query_recent_files.maxCount` schema 同时覆盖下界和上界拒绝，避免无效计数进入
  provider 层。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.device.RecentFileCollectorTest' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.RoutingAndValidatingToolExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest'
scripts/verify_local.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：targeted JVM RecentFile collector、DeviceContext executor、
  ActionPlanner、Routing/Validating executor 和 ToolRegistry 回归测试。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 Private read safety invariant 增量验证

本轮覆盖项：

- `SafetyPolicy` 对包含 `ReadsClipboard`、`ReadsContacts`、`ReadsFiles`、
  `ReadsCalendar` 或 `ReadsDeviceContext` 的工具增加强制确认 invariant。
- 如果未来私密读取工具的 `ToolSpec.confirmationPolicy` 被误配为 `Optional` 或
  `NotRequired`，SafetyPolicy 会直接 `Reject`，而不是在未确认状态下放行。
- `ToolRegistryTest` 增加全量 registry invariant，当前所有私密读取工具都必须声明
  `ConfirmationPolicy.Required`。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.safety.SafetyPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest'
scripts/verify_local.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：targeted JVM SafetyPolicy 和 ToolRegistry 私密读取确认边界回归测试。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 Reminder task id collision 增量验证

本轮覆盖项：

- `ScheduledTaskRepository.createReminder()` 不再用 `task-$timestamp-$titleHash`
  生成 id，避免同一毫秒、同标题提醒覆盖同一条本地记录。
- reminder task id 改为由 `UUID` 风格工厂生成，并在持久化前检查本地碰撞；测试
  注入始终重复的 id factory 时，第二条提醒会重试并保留为独立记录。
- rollback/recovery metadata 继续只暴露不透明 task id，但不再因标题相同而指向
  被覆盖的提醒。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest'
scripts/verify_local.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：targeted JVM ScheduledTaskRepository reminder id collision 回归测试。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 Stale reminder alarm delivery 增量验证

本轮覆盖项：

- `ReminderAlarmReceiver` 不再读取 alarm Intent 中的 title/body extras；新的
  reminder PendingIntent 只携带不透明 task id。
- `ReminderAlarmDeliveryHandler` 投递前通过 `ScheduledTaskRepository` 重新读取
  本地任务，并通过 DAO 条件更新只允许仍存在、类型为 `Reminder`、状态为
  `Scheduled` 的任务进入 `Running`。
- 本地 DB 记录成为提醒标题/正文的唯一投递来源；旧 alarm 即使携带过期 extras，
  也不会覆盖当前持久化任务内容。
- missing、`Cancelled`、`Deleted`、`Failed` 等 stale alarm 不发通知、不创建新
  状态，也不把终态任务改回 `Running` / `Delivered`。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.ReminderAlarmReceiverTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRemovalCoordinatorTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest'
scripts/verify_local.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：targeted JVM reminder alarm delivery stale-boundary、repository
  delivery-start、removal coordinator 和 periodic scheduler 回归测试。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 Device validation serial selection 增量验证

本轮覆盖项：

- `scripts/install_and_test_device.sh` 支持通过 `ANDROID_SERIAL` 在多台已授权
  设备/模拟器中选择目标；所有后续 `adb shell`、安装、instrumentation 和启动命令
  都绑定到该序列号。
- 未指定 `ANDROID_SERIAL` 时仍要求恰好一台 `device` 状态目标；无已授权
  设备或多台已授权设备会在 Gradle 构建、APK 安装和 instrumentation 前退出。
- 指定 `ANDROID_SERIAL` 时，目标必须存在且状态为 `device`；`unauthorized` /
  `offline` / missing serial 都会在 Gradle 构建、APK 安装和 instrumentation 前
  退出。
- `scripts/doctor.sh --device` 的输出收窄为 device toolchain check，避免把
  SDK `adb` 存在误读为“已连接可验收设备”。
- README、真机验收清单和 Agent core 文档同步说明无设备预期行为、
  `ANDROID_SERIAL` 用法，以及完整回归报告需要记录 instrumentation runner
  报告的测试总数和通过结果。

验证命令：

```bash
bash -n scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
scripts/doctor.sh
scripts/doctor.sh --device
scripts/install_and_test_device.sh
scripts/verify_local.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：`bash -n` 覆盖 `doctor.sh`、`verify_local.sh`、
  `install_and_test_device.sh` 和 `test_validation_scripts.sh`。
- 通过：`scripts/test_validation_scripts.sh`，覆盖 fake SDK 下 local doctor 无
  adb 通过、device doctor 缺 adb 失败、无设备 / unauthorized / offline /
  多已授权设备 / missing serial 都不调用 Gradle，以及单设备和
  `ANDROID_SERIAL` 选择设备的 happy path。
- 通过：`scripts/doctor.sh`。
- 通过：`scripts/doctor.sh --device`，确认 SDK `adb` 存在且输出为 device
  toolchain check。
- 预期失败：`scripts/install_and_test_device.sh` 在当前真实环境无已授权设备时
  退出；输出设备列表后停止，没有进入 Gradle 构建、APK 安装或 instrumentation。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 Skill progression boundary 增量验证

本轮覆盖项：

- 新增纯 Kotlin `SkillRunProgressor`，集中处理 Skill plan 校验、step limit、
  tool/model argument binding、工具结果输出整理和 private output fence。
- `SkillRunExecutor` 改为复用 `SkillRunProgressor`，保留工具执行、模型执行、
  safety/registry gate、trace 和 continuation 这些副作用边界。
- `AgentLoopRuntime.observeModelResult()` 的模型输出续跑改为复用
  `SkillRunProgressor.nextToolAfterModelOutput()`，不再维护独立的
  `nextToolStepForModelOutput` / binding parser。
- `read_clipboard.text` 与 `read_recent_screenshot_ocr.ocrText` 都由 progressor
  统一标记为私有工具输出，不能直接绑定到后续工具参数。
- 该切片不持久化完整 `SkillRunContinuation`，仍沿用现有 pending confirmation
  snapshot 恢复边界。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest' \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepOutputBindsToDependentToolStepAndRequestsConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepBindingRejectsMissingOutputBeforeConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepBindingCannotDirectlyExposePrivateToolOutputToShare' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.clipboardSummarySharePlansShareAfterLocalModelResult' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.blankCompositeModelResultFailsWithoutPlanningShare'

scripts/verify_local.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：targeted JVM Skill progressor、Skill executor 和 Agent loop model-output
  progression 回归测试。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 和 APK 检查。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 本地验证脚本分层增量验证

本轮覆盖项：

- `scripts/doctor.sh` 默认进入 local 模式，只检查 JDK、Android SDK 36、
  build-tools/aapt 和 Gradle wrapper，不再要求 `adb`。
- `scripts/doctor.sh --device` 保留设备/模拟器验收前的 `adb` 检查。
- `scripts/verify_local.sh` 调用 `doctor --local`，让 JVM 单测、lint、APK 构建和
  APK 内容检查不被缺失 `adb` 阻断。
- `scripts/install_and_test_device.sh` 先调用 `doctor --device`，继续要求
  Android SDK platform-tools/adb、单台已授权设备、arm64-v8a 和设备空间。
- README 与真机验收文档同步区分 local verification 与 device/emulator validation。

验证命令：

```bash
for f in scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh; do bash -n "$f"; done
scripts/doctor.sh
scripts/doctor.sh --local
scripts/doctor.sh --device
# 临时 SDK fixture：仅复制 android-36 与 aapt，不提供 platform-tools/adb。
ANDROID_SDK_ROOT="$TMP_SDK" ANDROID_HOME="$TMP_SDK" scripts/doctor.sh --local
ANDROID_SDK_ROOT="$TMP_SDK" ANDROID_HOME="$TMP_SDK" scripts/doctor.sh --device
scripts/verify_local.sh
scripts/install_and_test_device.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：三个脚本 `bash -n`。
- 通过：`scripts/doctor.sh`、`scripts/doctor.sh --local` 和
  `scripts/doctor.sh --device`；当前 SDK 下存在
  `/Users/bytedance/Library/Android/sdk/platform-tools/adb`。
- 通过：临时 SDK 中只提供 Android platform 与 `aapt`、不提供 platform-tools/adb
  时，`doctor --local` 通过。
- 预期失败：同一临时 SDK 下 `doctor --device` 报告缺少 `adb`，证明设备模式仍保留硬性检查。
- 通过：`scripts/verify_local.sh`，覆盖 `testDebugUnitTest`、`lintDebug`、
  `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、APK model
  artifact 检查、badging 检查、release size 检查、immutable model URL 检查和
  plaintext remote API key 检查。
- 预期失败：`scripts/install_and_test_device.sh` 通过 device doctor 后因当前没有已授权设备而停止，没有进入安装或 instrumentation。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。

## 2026-05-31 最近截图 OCR 增量验证

本轮覆盖项：

- 新增受确认保护的 `read_recent_screenshot_ocr` 工具：仅在用户明确要求识别最近截图文字时，读取最近 1 张截图并在本地提取 OCR 摘录。
- `query_recent_files(kind="screenshots")` 继续保持 metadata-only；截图 OCR 是独立工具，不返回 MediaStore id、URI、路径、原图或像素。
- OCR 文本标记为 `LocalOnly`，进入本地 continuation 前会在 trace/audit/persisted observation 中脱敏；远程模式不会自动发送截图 OCR 内容。
- Skill runner 将 `ocrText` 视为私有工具输出，不能直接绑定到后续工具参数；失败路径不回显底层异常里的 URI/path。
- Android 权限说明改为明确披露会读取最近 1 张截图像素并提取 OCR；工具风险等级升为 `MediumDraftOrNavigation`。
- MediaStore 查询优先按 `DATE_ADDED` 排序，再按修改时间兜底，减少编辑旧截图后被误当作最近截图的概率；仍不声明当前屏幕捕获或图片语义理解。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest.recentScreenshotOcrTextCannotBindDirectlyToLaterToolArgument' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.recentScreenshotOcrPermissionRationaleDisclosesPixelAndOcrRead' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.exposesSpecsForSupportedActionsWithConfirmationRequired' \
  --tests 'com.bytedance.zgx.pocketmind.tool.DeviceContextToolExecutorTest.recentScreenshotOcrPermissionDeniedAndFailureAreStructured' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeProtectsRecentScreenshotOcrBeforeRemoteContinuation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.recentScreenshotOcrObservationBuildsLocalPromptAndRedactsTrace'

./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
adb devices -l
```

结果：

- 通过：targeted JVM 最近截图 OCR 隐私边界、权限文案、registry、ViewModel 远程保护和 Agent trace 脱敏回归。
- 通过：完整 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin
  :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`。
- 通过：`:app:lintDebug`。
- 通过：`:app:assembleDebug :app:assembleDebugAndroidTest`。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 Share target MIME, wording, and skill restore 增量验证

本轮覆盖项：

- Android share-target 的 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` MIME 覆盖与
  in-app document picker 对齐，补齐 RTF、legacy Office 和 OOXML Office 类型。
- 该轮的 Office 与 RTF 分享仍复用当时的 `SharedInput` 只读元数据边界，
  不读取正文、不做 PDF/Office 解析。
- `query_recent_notifications` 的草稿文案收窄为“当前应用最近通知摘要”，与
  provider 只读取本应用 active notification 摘要的实现一致，不再暗示“未读”或
  跨 App 通知读取。
- Room 恢复 pending skill confirmation 时，会校验持久化 `SkillPlan` 是否包含
  当前待确认的 tool request id 和 tool name；损坏或旧格式行会被跳过，避免恢复
  到无法由 skill plan 解释的确认卡。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.AndroidManifestTest.shareTargetsAcceptPickerSupportedDocumentMimeTypes' \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest.officeAndRtfAttachmentsRemainMetadataOnlyWithoutPreview' \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest.recentNotificationSummaryMatchesCurrentAppOnlyBoundary' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest.exposesSpecsForSupportedActionsWithConfirmationRequired' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreSkipsPendingSkillPlanThatDoesNotContainPendingToolRequest'

./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
adb devices -l
```

结果：

- 通过：targeted JVM share MIME、metadata-only、notification wording 和 skill
  pending restore guard 测试。
- 通过：完整 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin
  :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`。
- 通过：`:app:lintDebug`。
- 通过：`:app:assembleDebug :app:assembleDebugAndroidTest`。
- 通过：`git diff --check`。
- 通过：排除测试夹具后的敏感配置扫描无匹配；测试目录中的命中为 redaction
  回归用例中的 dummy 字符串。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 Usage Access settings return 增量验证

本轮覆盖项：

- `ActionDraftSheet` 的特殊授权入口现在传递完整 `SpecialAccessRequirement`，
  而不是只传 settings action 字符串。
- `MainActivity` 使用 `ActivityResultContracts.StartActivityForResult` 打开
  Usage Access 设置页；用户返回 App 后通过 AppOps 重新检查
  `OPSTR_GET_USAGE_STATS`，并把结果写入 ViewModel 状态。
- 返回状态只更新 UI 文案，不确认 pending tool、不执行工具、不读取前台 App
  provider；真正执行仍必须点击确认卡的确认按钮。
- `SPECIAL_ACCESS_USAGE_STATS` 成为稳定 id，避免 UI / Activity / 测试使用裸字符串。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.specialAccessReturnUpdatesStatusTextWithoutExecutingTools' \
  --tests 'com.bytedance.zgx.pocketmind.AgentRuntimePermissionPolicyTest.foregroundAppDeclaresUsageAccessAsSpecialAccessNotRuntimePermission'

./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
adb devices -l
```

结果：

- 通过：targeted JVM Usage Access settings return 状态测试。
- 通过：完整 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`。
- 通过：`:app:lintDebug`。
- 通过：`:app:assembleDebug :app:assembleDebugAndroidTest`。
- 通过：`git diff --check`。
- 通过：敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 Agent stale in-flight recovery 增量验证

本轮覆盖项：

- `AgentTraceStore.failStaleInFlightRuns()` 会在进程重启恢复边界把无法安全继续的
  `Created` / `LoadingContext` / `Planning` / `ExecutingTool` / `RetryingTool` /
  `Observing` / `GeneratingAnswer` run 标记为 `Failed`，并追加 `Failed`
  trace step。
- `AwaitingUserConfirmation` 不会被清理，因为它有 `pending_agent_confirmations`
  作为明确恢复快照，仍可恢复到待确认 UI。
- `PocketMindViewModel.restoreStartupState()` 启动时执行该 stale run 清理，
  避免 Agent trace UI 长期展示已经不可能继续的运行中状态。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreFailsStaleInFlightRunsButKeepsPendingConfirmationsOnStartup'

./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
adb devices -l
```

结果：

- 通过：targeted JVM stale in-flight Agent run 恢复边界测试。
- 通过：完整 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`。
- 通过：`:app:lintDebug`。
- 通过：`:app:assembleDebug :app:assembleDebugAndroidTest`。
- 通过：`git diff --check`。
- 通过：敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 Reminder recovery confirmation entry 增量验证

本轮覆盖项：

- 聊天输入区上方新增 latest recovery entry，展示最近一次提醒 observation 提供的
  `AgentRecoveryAction`，点击后只进入确认流程，不直接调用后台任务取消。
- `AssistantRouter.requestRecoveryAction()` 会为 typed reminder recovery 创建新的
  Agent run，重新执行工具 schema 校验、安全策略、`ToolPlanned` /
  `ConfirmationRequested` audit，并保存 `pending_agent_confirmations`。
- ViewModel 将 recovery route 转成普通 `PendingAgentConfirmation`；确认前不执行
  `cancel_reminder`，确认后才走现有 `confirmAgentConfirmation()`、
  `UserConfirmed`、`ToolObserved` 链路。
- 工具 observation 完成后补齐 `isBusy=false` / `isGenerating=false`，避免已完成的
  工具结果阻塞后续 recovery 入口。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.reminderRecoveryActionRequestsAuditedCancelConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.reminderUndoEntryCreatesPendingCancelConfirmationAndDoesNotExecuteUntilConfirmed'

./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
adb devices -l
```

结果：

- 通过：targeted JVM reminder recovery confirmation entry 回归测试。
- 通过：完整 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`。
- 通过：`:app:lintDebug`。
- 通过：`:app:assembleDebug :app:assembleDebugAndroidTest`。
- 通过：`git diff --check`。
- 通过：敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

## 2026-05-31 Semantic memory runtime boundary 增量验证

本轮覆盖项：

- `ModelRepository` 暴露 `verifiedMemoryEmbeddingModelPath()`，只返回已存在、已通过
  recommended 校验且 capability 为 `MemoryEmbedding` 的模型路径；路径选择要求
  DB 中的 catalog size/revision/SHA-256 evidence 与当前文件 size/SHA-256 都匹配，
  Chat/Action、未校验、缺文件、替换文件或伪装成 memory asset 的自定义模型不会被
  误用为语义记忆 runtime。
- `MemoryRepository` 新增 `SemanticMemoryRuntimeController`，可在默认 hash runtime
  与注入的 semantic runtime 间切换；切换时会重算当前 memory entry embedding。
- 生产默认仍不声明语义召回已启用。安装 memory model asset 不等于 runtime 已接入；
  生产 model-path 路径上，只有 controller 成功切到 `supportsSemanticRecall=true`
  的 runtime 才产生 `MemoryRecallMode.Semantic` 命中。
- Production `AppContainer` 仍未注入 LiteRT embedding runtime factory；verified
  memory asset 在生产路径上会报告 runtime 不可用并回退轻量索引。
- `PocketMindViewModel` 在 memory rebuild 前同步 verified memory model path，确保
  启动/模型校验后的索引使用当前 runtime 边界，同时不要求聊天模型加载。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.semanticRuntimeControllerSwitchesBetweenFallbackAndSemanticRuntime' \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.semanticRuntimeFactoryReturningNullFallsBackAndReembedsExistingEntries' \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.switchingSemanticRuntimeReembedsExistingEntriesWithNewRuntime' \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.memoryModelPathDoesNotEnableSemanticRecallWithoutRuntimeSupport' \
  --tests 'com.bytedance.zgx.pocketmind.data.ModelRepositoryPathTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateSyncsVerifiedMemoryModelBeforeRebuildingMemoryIndex'
```

## 2026-05-31 Task-state memory sync 增量验证

本轮覆盖项：

- ViewModel 会把仍处于 `Scheduled` / `Running` 的后台任务同步为稳定 id 的
  `TaskState` 长期记忆，让 Agent 可以召回当前任务状态。
- 自动任务状态记忆只保存任务类型、状态、触发时间和不透明任务记录 id；提醒标题、
  正文、工具参数、prompt、远程响应不写入长期记忆、长期记忆 UI 或远程上下文。
- 后台任务取消、完成、失败、删除或从活跃列表消失时，对应自动 `TaskState`
  记忆会被遗忘；手动创建的非 auto-managed task-state 记录不受此同步清理。
- Room-backed Agent trace 会在持久化 `agent_runs.input` 时脱敏完整原始
  prompt；当前进程内仍保留 raw run input 和 active pending confirmation 的
  raw snapshot，Room 只保留声明安全参数，不再持久化 bounded next-action suffix。
- 持久 trace 的 summary、JSON 预览与 allowlisted metadata value 复用 audit
  redactor，对工具 reason、draft title、工具观察 summary、assistant preview
  中的 key/token/email/bearer 片段做脱敏。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.taskStateMemoryRecordIdIsStableForWhitespace' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.restoreStartupStateIndexesScheduledTasksAsForgettableTaskState' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.backgroundTaskStateMemoryDoesNotEnterRemotePromptOrHistory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.cancelBackgroundTaskForgetsTaskStateMemory' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.refreshBackgroundTasksDropsTerminalTaskStateMemory' \
  --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditEventTest.sanitizedSummaryRedactsGenericTokenAndKeyAssignments' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStorePersistsRunAndStepSummariesWithoutRawToolArguments' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreRedactsSensitiveTraceTextAcrossSummariesAndJson' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreRedactsAllowlistedCompletionMetadataValues' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest.roomStoreRestoresPendingConfirmationWithoutPuttingRawArgumentsInTrace'
```

## 2026-06-01 Task-state memory suppression 增量验证

本轮覆盖项：

- 用户显式遗忘或清空仍处于 `Scheduled` / `Running` 的 auto-managed
  `TaskState` 长期记忆后，系统会持久化隐藏 suppression marker。
- 后续后台任务 refresh、startup sync 或 `sendMessage` 触发的 task-state sync
  会跳过被 suppress 的记录，确保已忘记的任务状态不会重新出现在长期记忆 UI、
  召回索引或远程上下文中。
- Hidden suppression marker 不进入 `savedRecords()`、memory rebuild 或长期记忆
  UI；普通 terminal/missing task-state 清理语义保持不变。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.memory.MemoryRepositoryTest.suppressedTaskStateRecordsAreHiddenAndNotIndexed' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.forgetActiveTaskStateMemoryDoesNotReappearOnRefreshOrChat' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.clearLongTermMemorySuppressesActiveTaskStateMemoryResync'
```

## 2026-06-01 Emulator verification helper 增量验证

本轮覆盖项：

- 新增 `scripts/verify_emulator.sh`，作为 emulator-only 验证入口；它复用
  `doctor --device` 和 `install_and_test_device.sh`，但只允许 `emulator-*`
  目标，避免 instrumentation 误跑到真机。
- 未指定 `ANDROID_SERIAL` 时，脚本要求恰好一台已授权模拟器；指定 serial 时，
  目标必须是 `device` 状态的 emulator serial。
- 支持 `AVD_NAME=...` 先启动 AVD，等待 `sys.boot_completed=1` 后记录 serial、
  API、ABI、AVD 名称和 `CLEAN_DEVICE`，再执行 build/install/instrumentation。
- 失败时尽量收集截图、UI dump 和短 logcat 到 `build/verification/`，用于模拟器
  回归排查。

验证命令：

```bash
bash -n scripts/doctor.sh scripts/verify_local.sh scripts/install_and_test_device.sh scripts/verify_emulator.sh scripts/test_validation_scripts.sh
scripts/test_validation_scripts.sh
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

结果：

- 通过：`bash -n` 覆盖 `doctor.sh`、`verify_local.sh`、
  `install_and_test_device.sh`、`verify_emulator.sh` 和
  `test_validation_scripts.sh`。
- 通过：`scripts/test_validation_scripts.sh`，覆盖 fake SDK 下 emulator helper
  拒绝 physical serial、拒绝仅真机目标、选择唯一授权 emulator、启动指定
  AVD，以及继续复用 install helper 的安装路径。
- 未执行真实模拟器 instrumentation：当前切片只固化脚本入口和 fake adb/emulator
  选择边界；真实设备/模拟器回归需在有可启动 AVD 的环境执行
  `ANDROID_SERIAL=emulator-5554 scripts/verify_emulator.sh` 或
  `AVD_NAME=<name> scripts/verify_emulator.sh`。

## 2026-06-01 Reminder catch-up trigger persistence 增量验证

本轮覆盖项：

- 开机重排过期 reminder 时，`ReminderRescheduler` 会把实际安排的 catch-up
  alarm 时间条件写回 `scheduled_tasks.triggerAtMillis`，避免后台任务列表、
  周期检查和 auto-managed `TaskState` 长期记忆继续看到过去的触发时间。
- 写回使用 `Reminder + Scheduled` 条件更新；如果任务已被取消、投递或并发进入
  终态，不通过普通 upsert 覆盖状态。
- alarm 安排失败时仍标记任务 `Failed`，且不把 catch-up 时间误写入失败任务。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRemovalCoordinatorTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ReminderAlarmReceiverTest'
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

## 2026-06-01 Skill model dependency guard 增量验证

本轮覆盖项：

- `SkillRunProgressor.nextToolAfterModelOutput()` 在绑定模型输出到后续
  `ToolStep` 前，会确认该工具声明的所有依赖都已满足。
- 如果后续工具还依赖另一个未请求/未完成的前置步骤，进度器返回 rejected，
  `AgentLoopRuntime` 进入 `Failed`，不生成新的外发工具确认卡。
- 私密输出 fence、缺失 binding 失败和正常模型输出绑定路径保持不变。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunProgressorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepBindingRejectsUnmetDependenciesBeforeConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepBindingRejectsMissingOutputBeforeConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelStepOutputBindsToDependentToolStepAndRequestsConfirmation'
git diff --check
rg credential-pattern scan excluding build, .gradle, and test fixtures
```

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

## 2026-05-31 Reminder typed recovery action 增量验证

本轮覆盖项：

- `schedule_reminder` 成功 observation 会把 allowlisted
  `recoveryToolName=cancel_reminder` / `recoveryTaskId` 提升为 typed
  `AgentRecoveryAction`，而不是只停留在通用 `ToolResult.data` 字段。
- typed recovery action 只接受 `schedule_reminder -> cancel_reminder(taskId)`
  这一条受控路径；task id 必须是安全的 `task-*` 形式。
- ViewModel 将最新 typed recovery action 写入 UI state，为后续显式“撤销提醒”
  入口提供结构化数据源；本轮不声称已完成可点击 UI。
- observation 文案只展示 bounded task id，不展示提醒标题、正文或其他任务内容。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.reminderObservationSurfacesBoundedRecoveryHint' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.reminderObservationIgnoresUnsafeRecoveryMetadata' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.reminderObservationStoresTypedRecoveryActionForUi'

./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
adb devices -l
```

结果：

- 通过：targeted JVM typed recovery action / unsafe metadata 回归测试。
- 通过：完整 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`。
- 通过：`git diff --check`。
- 通过：敏感配置扫描无匹配。
- 未执行模拟器回归：当前环境缺少 `adb` 命令。

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
- `open_app_intent` 与 `open_app_deep_target` 现在共用 `app_navigation_skill`
  的 Skill-first 路由；显式“启动微信”“打开微信应用详情设置”可直接进入确认卡，
  不再等待 action planner。
- `ActionExecutor` 对未知 target、额外 URI/action/extras、非法包名在启动外部
  Activity 前拒绝；结果 metadata 只包含 `targetId`、`targetPackage`、completion
  状态和 allowlist policy，不保存 raw URI path/query。
- `MobileActionPlanner` 只在用户明确指定 App/包名和“应用详情设置”时生成
  deep target 草稿；模糊“打开应用详情设置”不自动执行。
- 反例覆盖否定、故障/文档/API/Intent payload、裸 app 目标、微信小程序/支付码/
  App 内设置等未白名单深层目标，避免把这些请求降级成普通 App 启动。
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
- Production `AppContainer` 当前未注入 LiteRT embedding runtime factory；安装并校验
  memory model asset 不会让语义召回路径启用。
- `MemoryHit` 标记命中来源为 `Lexical` 或 `Semantic`，便于后续接入 LiteRT
  embedding adapter 后验证真实语义召回路径。
- 模型管理高级页的本地记忆开关不再绑定 memory model asset 安装状态；文案明确
  当前使用本地轻量索引，安装 asset 不等于 embedding runtime 参与检索；UI 状态区分
  asset installed 与 `semanticMemoryEnabled`。
- 本地模型回答若使用本地记忆，或用户输入/工具结果已标记 `LocalOnly`，会作为
  `LocalOnly` turn 保存，避免后续切换到远程模型时进入远程 history。

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
- 当时用户发送 `记住：...` / `remember ...` 时，显式偏好在 `sendMessage`
  生产路径的消息落会话后持久化为 Preference 记录；该入口口径已被
  2026-06-01 Local remember control command 增量验证收窄为本地控制命令。
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
- 该切片不覆盖图片/OCR；完整文档解析、Office/PDF 解析和媒体内容理解仍待实现。

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

## 2026-05-31 用户主动提供 image/* 本地 OCR 摘录增量验证

本轮覆盖项：

- 用户通过 Android 分享入口或 App 内系统文件选择器主动提供 `image/*` 附件时，可以生成用户可见、有界、本地 OCR 文本摘录。
- OCR 摘录只进入 shared-input prompt，并标记为 `LocalOnly`；远程模型模式不会自动上传 OCR 文本、分享文本、文本摘录或附件元数据。
- 该能力不读取当前屏幕，不捕获截图，不处理 `query_recent_files(kind="screenshots")` 返回的最近截图候选，也不声明图片语义理解。
- 音频、视频、PDF、Office、二进制和其他不支持 OCR 的附件继续保持 metadata-only。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsSharedImageOcrPreviewBeforeBuildingPrompt'

./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
git diff --check
rg credential-pattern scan excluding build and .gradle outputs
adb devices -l
```

结果：

- 通过：targeted `SharedInputTest` 与 `remoteModeRejectsSharedImageOcrPreviewBeforeBuildingPrompt`。
- 通过：完整 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`。
- 通过：`:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`。
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
- Room 恢复路径会恢复历史 `ToolRequested` request id 去重骨架，但当前策略
  不再跨重启恢复 `nextActionInput`；确认或观察不能复用旧 request id。
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
- Agent Loop 对 retryable 本地只读工具失败增加一次有界自动重试，记录 `ToolRetryScheduled` trace/audit 事件，重试预算耗尽后才进入 `Failed`；外部界面启动、外部发送、通知/后台调度以及高/关键风险工具即使被底层标记为 retryable 也不会自动重放。
- 用户取消动作确认时，Agent run 会进入 `Cancelled`，并记录取消/观察审计事件。
- Built-in Skill Runtime 增加版本化 manifest，并将邮件、日程、地图、信息查找、设备设置映射为一跳工具 skill。
- Device Context 增加最小非敏感设备状态快照，并接入 Agent trace / prompt context。
- Safety Policy 增加风险分级执行策略，阻止中高风险工具绕过确认。
- Tool Audit 增加 `tool_audit_events` Room 表，记录计划、确认、执行观察和拒绝事件。
- Memory 增加显式偏好/任务状态记录与遗忘控制。
  默认召回仍为 token/hash；semantic runtime 边界已存在，但 LiteRT embedding
  adapter 尚未参与检索。
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
- 当前仍未完成的核心能力包括语义屏幕理解、LiteRT embedding adapter 参与记忆检索、special-access permission flows beyond Usage Access、当前屏幕像素/截图捕获、任意媒体 OCR 和实际图片/文档语义理解；状态见 `docs/agent_core_modules.md`。

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

## 2026-06-01 Emulator regression closure and audit hardening

本轮覆盖项：

- `scripts/install_and_test_device.sh` 不再只信 `adb shell am instrument`
  的退出码；当 instrumentation 输出包含失败用例、stack trace、`shortMsg`
  或 failed status code 时直接失败，避免 “OK/FAILURES” 被误判。
- `scripts/verify_emulator.sh` 在启动前校验 `AVD_NAME`，失败时列出可用
  AVD，并稳定打印 emulator 日志路径；fake SDK 回归覆盖缺 emulator binary、
  未知 AVD 和 instrumentation 输出失败。
- AVD UI 测试修复了会话 sheet 关闭不稳、审计/轨迹 empty-state 受前置测试
  污染、远程模式本地动作历史泄漏断言缺失的问题。
- Agent tool observed audit metadata 改为以已确认的 `ToolRequest.toolName`
  为准，不信工具结果里可伪造的 `toolName`；`taskId`/`recoveryTaskId`
  也必须满足安全任务 id 格式。
- App 启动时会触发 reminder alarm 重排，覆盖 “DB 已有 Scheduled reminder
  但普通重启后 AlarmManager 未注册” 的恢复窗口；WorkManager periodic
  enqueue/cancel 现在等待 `Operation.result`。
- 远程模式下被拒绝的本地动作消息标记为 `LocalOnly`，不会进入后续远程
  prompt/history。

验证命令：

```bash
scripts/test_validation_scripts.sh
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.audit.ToolAuditRepositoryTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.PeriodicCheckSchedulerTest' \
  --tests 'com.bytedance.zgx.pocketmind.background.ScheduledTaskRepositoryTest'
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:testDebugUnitTest
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
scripts/verify_local.sh
ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/bytedance/Library/Android/sdk \
AVD_NAME=pocketmind_api36_arm64 \
EMULATOR_ARGS='-no-window -no-snapshot -no-audio -gpu swiftshader_indirect' \
CLEAN_DEVICE=1 \
BOOT_TIMEOUT_SECONDS=360 \
EMULATOR_SELECT_TIMEOUT_SECONDS=120 \
scripts/verify_emulator.sh
```

结果：

- 通过：validation script fake SDK 回归。
- 通过：目标单测、全量 `:app:testDebugUnitTest`、`compileDebugAndroidTestKotlin`。
- 通过：`scripts/verify_local.sh`，覆盖 validation script tests、unit tests、
  lintDebug、debug/androidTest/release 构建、APK 资产和 ABI/体积约束。
- 通过：`pocketmind_api36_arm64` AVD，serial `emulator-5554`，API 36，
  ABI `arm64-v8a`，`CLEAN_DEVICE=1`，instrumentation `OK (12 tests)`。
- 记录：真机 `fb6272c` 构建与主 APK 安装通过，但测试 APK 安装被设备策略
  拦截为 `INSTALL_FAILED_USER_RESTRICTED`；需要在手机开发者选项中允许 USB
  安装后再跑真机 instrumentation。

## 2026-06-02 Remote memory privacy emulator regression

本轮覆盖项：

- 修正 `MainActivityComprehensiveTest` 中过时的远程模型期望：
  `请记住：...` / `remember ...` 是本地长期记忆控制命令，应等待本地确认，
  不应产生远程 chat completion 请求。
- 综合 emulator walkthrough 新增远程隐私断言：后续远程请求不得携带原始
  记忆命令、显式偏好内容、本地确认消息、本地记忆上下文或设备上下文。
- 保留普通远程聊天、远程流式取消、本地动作草稿、session 切换、模型管理与
  自定义下载入口的端到端覆盖。

验证命令：

```bash
./gradlew :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rememberCommandBypassesRouterAndRemoteRuntime' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteRememberCommandDoesNotEnterLaterRemoteHistory'

AVD_NAME=focus_agent_api36_arm64 \
EMULATOR_ARGS='-no-snapshot -no-audio -no-window -no-boot-anim' \
EMULATOR_SELECT_TIMEOUT_SECONDS=120 \
BOOT_TIMEOUT_SECONDS=300 \
scripts/verify_emulator.sh
```

结果：

- 通过：AndroidTest Kotlin 编译与远程记忆隐私契约单测。
- 通过：`focus_agent_api36_arm64` AVD，serial `emulator-5554`，API 36，
  ABI `arm64-v8a`，instrumentation `OK (13 tests)`。
- 失败根因关闭：先前失败停在 `请记住：蓝色机器人喜欢端侧 AI` 后等待
  `模拟器回答`；当前实现和单测契约要求该命令本地处理、绕过 router 和远程
  runtime，因此应修 instrumentation 断言而不是产品代码。

## 2026-06-02 PDF shared-input text-layer and remote read guard

本轮覆盖项：

- 用户主动分享或选择 `application/pdf` 附件时，可以在本地读取有界 PDF
  文本层摘录。该能力只扫描受限 PDF bytes/content streams，支持普通/Flate
  text-showing stream，最多进入 shared-input prompt 4000 字符。
- PDF 摘录是文本层预览，不是 PDF OCR、图片扫描、版式理解或完整 PDF 解析；
  图片型/扫描型 PDF 没有可读文本层时保持 metadata-only。
- `MainActivity` 会按当前推理模式选择 `ShareIntentReader` 读取策略。远程模式
  下只生成 value-free protected share signal；不会读取 `EXTRA_TEXT` 值、查询
  附件 metadata、打开文件流、解析文本层或运行 OCR。
- 远程模式 `ingestSharedInput` 的本地提示更新为“不会读取或自动发送”分享文本、
  RTF/PDF/Office 摘录、OCR 摘录或附件元数据。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.multimodal.SharedInputTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteModeRejectsShared*'
```

结果：

- 通过：PDF text-layer reader、protected shared-input prompt、远程模式 shared
  input 保护回归测试。

## 2026-06-02 Safety boundary confirmation hardening

本轮覆盖项：

- `SafetyPolicy` 不再只硬拦截私密读取和高风险外发；凡是 ToolSpec 声明会
  启动外部 Activity、外发文本、请求 Android runtime permission、调度后台任务、
  发通知，或读取剪贴板/联系人/文件/日历/Accessibility/设备上下文，都必须声明
  `ConfirmationPolicy.Required`。
- 新增真实 `ToolRegistry` 遍历回归：所有已注册边界工具在用户确认前必须返回
  `RequireConfirmation`，确认后才允许执行。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.safety.SafetyPolicyTest' \
  --tests 'com.bytedance.zgx.pocketmind.tool.ToolRegistryTest'
```

结果：

- 通过：SafetyPolicy 边界权限硬门禁和真实 registry confirmation 回归测试。

## 2026-06-02 ToolResult observe/resume schema revalidation

本轮覆盖项：

- `AgentLoopRuntime.observeToolResult` 在生成本地 continuation prompt、trace
  脱敏、audit、retry 或 replan 之前，重新按当前 `ToolRegistry` 校验 successful
  `ToolResult.data`。malformed success 会转成 non-retryable `InvalidResult`
  失败，不把原始 data 或 summary 当作成功观察继续传播。
- `SkillRunExecutor` 在直接执行 tool step 和从确认点 resume 时，都先校验
  successful `ToolResult.data`，再写 `ToolFinished` trace 或绑定 step output。
  这样 schema-extra / 缺字段的成功结果不会进入后续模型步骤或 Skill outputs。
- 旧单测 fixture 同步补齐真实 output schema 字段，避免测试继续依赖生产路径不会
  接受的 sparse success。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.skill.SkillRunExecutorTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest'

./gradlew :app:testDebugUnitTest
```

结果：

- 通过：Skill executor malformed success、Agent observe malformed success、防泄漏
  回归测试。
- 通过：完整 JVM 单测 `:app:testDebugUnitTest`。

## 2026-06-02 Session-scoped Agent trace cleanup

本轮覆盖项：

- `AgentRun` / `agent_runs` 新增 nullable `sessionId`，新建普通对话 run 和
  typed recovery run 都绑定当前 active chat session；旧 Room 数据经
  `MIGRATION_9_10` 保持 `sessionId = null`，不猜测回填。
- pending action 恢复按当前 active session 过滤。切换、新建或删除 session 时
  不再把其他会话的 pending confirmation 留在 UI 上阻塞或误执行。
- 删除 active session 后，同步清理该 session 对应的 `agent_runs`、
  `agent_steps`、`pending_agent_confirmations` 和
  `agent_skill_run_checkpoints`，避免已删除会话的工具确认在重启后恢复。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentTraceStoreTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.sendMessagePassesActiveSessionIdToAgentRoute' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.deleteActiveSessionClearsSessionAgentTraceAndPendingConfirmation'

./gradlew :app:assembleDebugAndroidTest

./gradlew :app:testDebugUnitTest
```

结果：

- 通过：session-scoped trace deletion、route/recovery session binding、
  ViewModel 删除会话 pending 清理回归测试。
- 通过：AndroidTest APK 编译，包含 Room 9->10 migration 编译覆盖。
- 通过：完整 JVM 单测 `:app:testDebugUnitTest`。

## 2026-06-02 Remote OpenAI tool_calls confirmation path

本轮覆盖项：

- `RemoteChatRuntime` 保留旧 `send(): Flow<String>`，新增 tool-aware
  `sendWithTools()` 事件流；请求体可序列化 `ToolSpec` 为 OpenAI-compatible
  `tools[]`，并继续过滤 `LocalOnly` history。
- SSE / 非流式 Chat Completions 响应可解析 OpenAI-style `tool_calls`，
  支持流式 `function.arguments` 分片累积；一次多个 tool call、malformed
  arguments JSON 会结构化拒绝，不进入执行。混合 `tool_calls` /
  legacy `function_call`、以及缺少 `index` 的多工具流式片段也会拒绝。
- ViewModel 远程分支把 `RemoteChatEvent.ToolCall` 交给
  `AssistantRouter.observeModelToolRequest()`，由 Agent loop 复用
  `ToolRegistry.validate`、`SafetyPolicy`、trace/audit 和 pending
  confirmation 链路。远程模型不能直接执行 Android tool。
- 远程 tool-call 草稿消息写为 `LocalOnly`，后续远程 history 不会带上本地
  待确认动作提示；被拒绝的远程 tool-call 会刷新 trace 并停在
  `动作不可执行` 状态；用户确认后仍走现有 runtime permission /
  special access / `confirmToolRequest` / tool result privacy gate。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.runtime.RemoteChatRuntimeTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestratorTest' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.remoteToolCallBecomesPendingConfirmationWithoutExecutingTool' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.rejectedRemoteToolCallShowsActionFailureAndRefreshesTrace'

./gradlew :app:testDebugUnitTest --rerun-tasks

./gradlew :app:assembleDebugAndroidTest
```

结果：

- 通过：RemoteChatRuntime tools body、非流式/流式 tool_calls parser、
  混合/缺 index 多工具拒绝、AssistantOrchestrator remote ToolRequest
  确认边界、ViewModel pending / rejected UI 回归测试。
- 通过：完整 JVM 单测 `:app:testDebugUnitTest --rerun-tasks`。
- 通过：AndroidTest APK 编译。

## 2026-06-02 Local model call:function confirmation path

本轮覆盖项：

- 本地模型生成的整段 `call:function{...}` 输出会被严格解析为模型工具请求；
  普通回答保持纯聊天完成，不会因为包含 `call:` 片段被抽取成工具。
- parser 只接受单个调用和 JSON object primitive 参数；坏 JSON、多段 call、
  嵌套 object/array、未知工具会 fail closed，不生成确认卡、不执行工具。
- 有效本地 call 会进入 Agent loop 的 `ToolRegistry.validate`、`SafetyPolicy`、
  trace/audit 和 pending confirmation 链路，`ToolRequest.reason` 使用固定
  `local model tool call`，避免参数进入 ToolPlanned audit summary。
- ViewModel 初始本地聊天分支会消费 `observeModelResult()` 的
  `PlanNextTool`，把 raw call 输出替换成 `LocalOnly` 标题级确认提示后再持久化；
  取消该确认并切到远程聊天时，remote history 不包含本地 call 参数。
- `observeModelToolRequest()` 增加重复 request id 防线，远程/本地模型工具请求
  不能复用同一 run 中已有的 tool request id。

验证命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.bytedance.zgx.pocketmind.action.ActionPlannerTest' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.localModelToolCallOutputRequestsConfirmationAfterAnswerGeneration' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.localModelToolCallAuditSummariesDoNotPersistArguments' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.ordinaryModelAnswerStillCompletesWithoutActionParsingFallback' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.localModelUnknownToolCallOutputFailsRunWithoutPendingConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.localModelInvalidToolArgumentsFailBeforeConfirmation' \
  --tests 'com.bytedance.zgx.pocketmind.orchestration.AgentLoopRuntimeTest.modelToolRequestCannotReusePriorToolRequestId' \
  --tests 'com.bytedance.zgx.pocketmind.PocketMindViewModelTest.localModelCallOutputBecomesPendingConfirmationWithoutLeakingToRemoteHistory'

./gradlew :app:testDebugUnitTest

./gradlew :app:assembleDebugAndroidTest
```

结果：

- 通过：strict parser、Agent loop 本地模型工具调用确认边界、参数无关 audit
  summary、未知/非法调用 fail-closed、重复 request id 拒绝、ViewModel 本地 call
  参数不进入远程 history。
- 通过：完整 JVM 单测 `:app:testDebugUnitTest`。
- 通过：AndroidTest APK 编译。
