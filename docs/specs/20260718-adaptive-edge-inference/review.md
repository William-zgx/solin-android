# 自适应端云推理阶段一审查

日期：2026-07-19

审查基线：`codex/adaptive-edge-inference-phase1` @ `1ff31a8`

结论：阶段一代码与本地可执行门禁已收口；尚不满足真机验收或发布条件。

## 合同一致性

- preference 与一次 run 的 actual placement 已分离；Auto 决策只使用结构化复杂度、
  稳定资源、能力、授权、连接 freshness/revision 和独立隐私计划。
- serving 路径遵守 `privacy → decide → bind → disclose → dispatch`。Blocked、绑定/CAS
  失败、未知或损坏 binding 均为零 runtime 调用，不做隐式 local/remote fallback。
- 工具续写、同目标 retry、停止/取消和受限恢复只读取已绑定 placement；运行中改变
  preference 不改变当前 run。
- actual placement 只由 runtime invocation 事实产生，并与 placement/receipt fail-closed
  对账；审计与 trace 不记录 prompt、API key、endpoint 或 IP。
- debug rollout 为 `opt_in`，release-like 变体为 `off`；release-like 启动读到 Auto 会
  回落并保存为 Local。

## 已通过证据

- `compileDebugKotlin`、`compileDebugAndroidTestKotlin`。
- 275 项最终聚焦 JVM 回归，包含 `SolinViewModelTest`、prepared coordinator、dispatcher
  和 eval。
- `scripts/test_validation_scripts.sh`。
- 严格 capability matrix：`releaseLikeRolloutOff=true`、`servingSourceContract=passed`。
- privacy scan：0 findings。
- model capability profiles。
- behavior eval fixture：40 cases / 7 categories / 6 MVP scenarios。
- S5/S6 最终生命周期与审计修复经独立复审：stop-before-dispatch、首个远程事件前不
  提交审计、首事件 exactly once、failure/cancel discard 均通过。

## 未完成证据与风险

- 当前机器无 JDK 21；`localagents-rag:0.3.0` 含 classfile 65。JDK 17 下不能完成并宣称
  `verify_local.sh` / 全量 `testDebugUnitTest` 通过。
- 无连接设备；Room 17→18 connected migration、S7 instrumentation 和真机端到端路由
  仍 pending。
- fixture eval 不等于 release actual trace。当前没有 30 天内的 `agent_loop_runtime`
  actual trace、placement reconciliation、性能基线、正式签名和人工发布批准。
- release-like Auto 保持 Off；本分支不得作为已发布或广泛生产可用能力宣传。

计划中列出的 `AgentRunOptions.kt`、`MainActivityAdaptiveUiTest.kt`、
`restart_recovery.jsonl` 和 `runtime_failure.jsonl` 没有在本分支产生改动；相应合同由现有
调用方、其他 UI/eval fixture 与验证脚本覆盖。该偏差不替代 pending 的设备和真实 trace
证据。

## 下一门禁

1. 在 JDK 21 环境运行 `scripts/verify_local.sh` 和全量 Gradle 回归。
2. 在连接设备上运行数据库迁移、S7 instrumentation 和手机端端到端验收。
3. 采集真实 runtime trace，完成 placement reconciliation、性能、签名与人工发布门禁。
