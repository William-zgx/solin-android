# ChatUiState 拆分决策记录

**状态：不执行全量多流拆分。** 这是 Wave 1–6 的明确取舍，见
[优化历史 §21](../../optimization_plan_weaknesses.md#21-明确不做的项及原因)。
当前核对日期为 2026-11-04，源码基线为 `cb7e4918`。

## 原目标与当前实现

原计划希望把大型 `ChatUiState` 分成独立的子状态和 `StateFlow`，减少重组范围。
控制器提取已完成，但该多流方案没有实施：`ChatModels.kt` 中仍使用一个
`ChatUiState` data class，部分字段采用嵌套值类型，例如 `VoiceCaptureUiState`。

旧字段表、默认值、行号和拟议迁移代码保存在
[原计划版本](https://github.com/William-zgx/solin-android/blob/cb7e4918057fb3a6ed71df87ba24ecc8875567c7/docs/plans/uistate-split.md)。
它们不能作为当前产品或状态 API 的依据。例如当前
`reduceDeviceActionConfirmations=true`，`remoteGuiAutomationEnabled=false`；
确认与隐私合同以 [模块文档](../../agent_core_modules.md#safety-and-audit) 和
[隐私说明](../../privacy_notice.md) 为准。

## 保留单一状态的原因

- 控制器、持久化、生成和工具事件共同更新同一状态；全拆需要同时改变消费者及原子更新语义。
- 仅减少字段数不能证明重组或性能改善。必须先观察具体 UI 更新热点，再设计可测的局部边界。
- 多个流需要明确跨流一致性：忙碌状态、当前会话、运行位置、待确认动作不能互相落后。

## 允许的后续演进

按 [架构深度规则](../../architecture_depth.md)，可以围绕真实问题增量引入嵌套值类型或
局部 selector；一次变更只迁移一个所有权清晰的状态面。不要恢复旧计划中的机械字段分组，
也不要为隐藏跨控制器依赖增加转发层。

重新启动迁移前，应给出：

1. 实际重组、并发或维护问题及可复现证据。
2. 唯一状态所有者、原子更新要求和旧消费者迁移顺序。
3. 会话切换、生成结束、下载结束、取消和确认恢复的回归用例。
4. 当前设备上的行为或性能比较，而不是仅用 JVM 通过证明 UI 改善。

`ChatModelsTest`、`ChatUiStateModelVerificationTest`、`GenerationStreamReducerTest`
和相关控制器测试覆盖状态合同；Compose 和系统流程验收仍另行记录到
[验证报告](../../validation_report.md)。
