# 自适应端云推理阶段一任务清单

日期：2026-07-18

状态：实施中

依据：[spec.md](spec.md) · [plan.md](plan.md)

## 分派约束

- 每个任务由一个实现 Agent 独占完成并单独提交；同一时刻只允许一个实现 Agent 写工作区。
- 每个提交依次经过规格符合性审查和代码质量审查，两个审查都通过后才开始下一个任务。
- 所有 serving-model 路径必须遵守 `privacy → decide → bind → disclose → dispatch`；运行中不得从偏好重新推断 placement。
- `LocalOnly` 和未知隐私一律 fail closed；不得静默切换模型、上传受保护内容或把 endpoint/token/prompt 写入 trace。
- 下表优先保持可独立测试的边界；少数 Android 编译原子切片超过 3 个文件，原因是接口与调用方必须同提交迁移。

## 任务

| ID | Description | Files | Depends-On | Acceptance |
|---|---|---|---|---|
| T01 | 增加兼容的 Local/Auto/Remote 偏好编码，并让未知/损坏值回落 Local。 | `RemoteModels.kt`; `PreferenceSettingsStore.kt`; `InferencePreferenceCodecTest.kt` | — | `./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.data.InferencePreferenceCodecTest'`；旧 Local/Remote 可读写，Auto 可持久化，未知值为 Local。 |
| T02 | 扩展远程 capability/profile revision，物质配置变更轮换 revision；连接快照仅内存保存、60 秒 TTL、revision 不匹配即失效。 | `Stores.kt`; `RemoteModelRepository.kt`; `LegacyPrefsMigrator.kt`; `RemoteModelConfigTest.kt`; `RemoteModelRepositoryTest.kt` | T01 | `./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.RemoteModelConfigTest' --tests 'com.bytedance.zgx.solin.data.RemoteModelRepositoryTest'`；重建 repository 不恢复 Reachable，59,999ms 新鲜、60,000ms 过期。 |
| T03 | 实现确定性的请求复杂度聚合器，只接收结构化特征，不读取 prompt。 | `RequestComplexityAggregator.kt`; `RequestComplexityAggregatorTest.kt` | T01 | `./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.orchestration.RequestComplexityAggregatorTest'`；69%/70% 边界、reasoning/tool/output 信号和 Unknown 均通过。 |
| T04 | 实现 app-scoped 稳定资源窗口与 thermal 映射，2/3 多数决、Hot 15 秒降档冷却。 | `StableResourceSnapshotAggregator.kt`; `SystemResourceMonitor.kt`; `StableResourceSnapshotAggregatorTest.kt`; `SystemResourceMonitorTest.kt` | — | `./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.resource.StableResourceSnapshotAggregatorTest' --tests 'com.bytedance.zgx.solin.resource.SystemResourceMonitorTest'`；0/1/2/3 样本、null CPU、2/3 Hot、cooldown 通过。 |
| T05 | 实现纯 `ModelPlacementPolicy`、稳定原因码和完整决策优先级，手工 Remote 保持显式行为，Auto 仅使用新鲜且授权的候选。 | `ModelPlacementPolicy.kt`; `ModelPlacementPolicyTest.kt` | T02,T03,T04 | `./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.orchestration.ModelPlacementPolicyTest'`；LocalOnly、能力不匹配、stale、Simple/Complex、Hot、vision、无候选矩阵通过，源码无 Android/UI/BuildConfig 依赖。 |
| T06 | 建立与 placement 独立的 `PromptPrivacyPlan`，对当前输入、历史、附件、evidence、observation 做最严格聚合。 | `PromptPrivacyPlan.kt`; `PromptPrivacyPlanTest.kt` | T05 | `./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.orchestration.PromptPrivacyPlanTest'`；未知来源、LocalOnly 必需证据和 Remote run 新增 LocalOnly observation 均阻断远端。 |
| T07 | 把独立隐私计划传播到共享输入、Agent loop routing 和 observation replanning，移除由 preference 推断隐私的逻辑。 | `ChatSharedInputSupport.kt`; `AgentLoopRouting.kt`; `AgentObservationReplanner.kt`; `AgentLoopRoutingTest.kt`; `AgentObservationReplannerTest.kt` | T06 | `./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.orchestration.AgentLoopRoutingTest' --tests 'com.bytedance.zgx.solin.orchestration.AgentObservationReplannerTest'`；Auto 不改变隐私标签，任一必需 LocalOnly segment 远端调用数为 0。 |
| T08 | 建立不可变 `BoundRunPlacement`、binding store 与 Room 持久化/CAS；数据库迁移 fail closed。 | `RunPlacementBinding.kt`; `RunPlacementBindingStore.kt`; `SolinDatabase.kt`; `AgentModels.kt`; `RunPlacementBindingStoreTest.kt`; `SolinDatabaseMigrationTest.kt` | T05 | `./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.orchestration.RunPlacementBindingStoreTest' --tests 'com.bytedance.zgx.solin.data.SolinDatabaseMigrationTest'`；同 run 不可换 placement，缺失/损坏/旧 binding 不可恢复，17→18 迁移通过。 |
| T09 | 实现唯一 runtime dispatcher、attempt 计数和无敏感数据的 placement/invocation trace，禁止 local+remote 双发。 | `ModelRuntimeDispatcher.kt`; `AssistantOrchestrator.kt`; `AgentTraceStore.kt`; `ModelRuntimeDispatcherTest.kt`; `RunDataReceiptTraceTest.kt` | T08 | `./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.orchestration.ModelRuntimeDispatcherTest' --tests 'com.bytedance.zgx.solin.orchestration.RunDataReceiptTraceTest'`；并发 CAS 仅一个 invocation，placement=invocation=receipt，敏感 fixture 不出现在 trace。 |
| T10 | 增加 off/shadow/opt-in rollout、Auto 首次披露授权、配置 revision 校验和 app-scoped 资源/连接刷新 wiring；release 默认 off。 | `AdaptiveInferenceRollout.kt`; `app/build.gradle.kts`; `SolinAppContainer.kt`; `MainActivity.kt`; `ModelLoadController.kt`; `RemoteModeDisclosureSheet.kt`; `AdaptiveInferenceRolloutTest.kt` | T02,T04,T09 | `./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.orchestration.AdaptiveInferenceRolloutTest' --tests 'com.bytedance.zgx.solin.SolinViewModelTest' && ./gradlew :app:compileDebugKotlin`；未知 stage=off，release Auto 不可选，revision 变化使旧授权失效。 |
| T11 | 实现 `PreparedChatRunCoordinator`，一次性完成 privacy/requirements/snapshot/decision/binding；披露恢复同一个 prepared run。 | `PreparedChatRunCoordinator.kt`; `ChatRemoteSendSupport.kt`; `AgentRunOptions.kt`; `PreparedChatRunCoordinatorTest.kt` | T07,T10 | `./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.presentation.PreparedChatRunCoordinatorTest'`；Blocked 零 runtime 调用，披露期间 preference/config 改动不会把确认复用到新目标。 |
| T12 | 把初始 Chat 发送改为 prepare→bind→disclose→dispatch，并让 UI state 暴露实际 placement/reason；不得扩张大 ViewModel/Screen。 | `ChatController.kt`; `ChatControllerHelpers.kt`; `ChatModels.kt`; `SolinViewModel.kt`; `PreparedChatRunCoordinatorTest.kt`; `SolinViewModelTest.kt` | T11 | `./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.presentation.PreparedChatRunCoordinatorTest' --tests 'com.bytedance.zgx.solin.SolinViewModelTest'`；Auto Simple/Complex/Hot 路由正确，route exception 为 Blocked，单 run 只命中一个 runtime。 |
| T13 | 让工具续写、context/citation retry、stop/cancel 和确认恢复只读 binding；Remote 收到 LocalOnly observation 立即失败且不 fallback。 | `ChatToolContinuationSupport.kt`; `ToolExecutionController.kt`; `ChatGenerationSupport.kt`; `ChatController.kt`; `PendingConfirmationSupport.kt`; `RunPlacementRecoveryTest.kt`; `RemoteChatRuntimeTest.kt`; `SolinViewModelTest.kt` | T12 | `./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.orchestration.RunPlacementRecoveryTest' --tests 'com.bytedance.zgx.solin.runtime.RemoteChatRuntimeTest' --tests 'com.bytedance.zgx.solin.SolinViewModelTest'`；切换 preference 不改变续写/停止目标，同目标 retry placement 不变，缺 binding fail closed。 |
| T14 | 完成三态模型配置和实际位置解释 UI；偏好、候选状态与本次执行事实分开展示。 | `ModelManagerSheet.kt`; `ChatTopBar.kt`; `ChatEmptyState.kt`; `FirstRunSetupPanel.kt`; `SolinScreen.kt`; `SolinScreenDisplayTest.kt`; `MainActivityAdaptiveUiTest.kt` | T12 | `./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.ui.SolinScreenDisplayTest' && ./gradlew :app:compileDebugKotlin`；三态文案和 Auto→Local/Remote/Blocked 解释正确，UI 不展示 endpoint/IP/revision。真机测试命令记录为待人工执行。 |
| T15 | 让 Audit/行为评测从 invocation 读取 actual placement，并校验 placement、invocation、receipt 三方一致。 | `AuditUiController.kt`; `AgentBehaviorEvalModels.kt`; `AiBehaviorActualTraceGeneratorTest.kt`; `RunDataReceiptTraceTest.kt`; `privacy_boundary.jsonl`; `restart_recovery.jsonl`; `runtime_failure.jsonl` | T09,T13,T14 | `./gradlew :app:testDebugUnitTest --tests 'com.bytedance.zgx.solin.eval.AiBehaviorActualTraceGeneratorTest' --tests 'com.bytedance.zgx.solin.orchestration.RunDataReceiptTraceTest'`；错配 fixture 失败，LocalOnly remote invocation 失败，实际 target 不再从 preference 推断。 |
| T16 | 更新 capability/privacy/release 验证脚本和当前事实文档，诚实保留真机与人工发布证据 pending，完成全量回归。 | `scripts/**`; `docs/**`; `README.md`; `README.zh-CN.md`; documentation tests | T15 | `bash scripts/doctor.sh && bash scripts/verify_local.sh && ./gradlew :app:testDebugUnitTest && ./gradlew :app:compileDebugKotlin && bash scripts/privacy_scan.sh && bash scripts/test_validation_scripts.sh && bash scripts/verify_model_capability_profiles.sh && bash scripts/verify_capability_matrix.sh && bash scripts/verify_ai_behavior_eval.sh --require-boundary-map`；全部退出 0，未运行的 connected/release gate 不伪造通过。 |

## 依赖顺序

```text
T01 → T02 ─┐
T03 ───────┼→ T05 → T06 → T07 ─┐
T04 ───────┘                    ├→ T11 → T12 → T13 ─┐
T05 → T08 → T09 → T10 ─────────┘                   ├→ T15 → T16
                                      T12 → T14 ────┘
```

在共享工作区中按 `T01…T16` 的线性顺序执行；依赖图只表达逻辑关系，不授权并行实现。
