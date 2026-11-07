# 自适应端云推理阶段一：当前事实

当前核对：2026-11-04，主分支 `cb7e4918`。最初阶段一集成基线为 `1ff31a8`。

状态：阶段一代码已集成，尚无正式发布批准。

执行开关：`app/build.gradle.kts` 的 `defaultConfig` 为 `opt_in`，debug 显式同值，
release-like 变体继承该默认值。旧版“release-like Off”的描述已经过期。

本文记录当前代码事实；目标合同及旧阶段设计见 [spec.md](spec.md)、[plan.md](plan.md)
和 [tasks.md](tasks.md)，不能用历史 rollout 目标覆盖当前构建配置。S1–S9 已集成，
JDK 21 本地构建门禁和当前远端 CI 已有通过证据；真实 Auto 路由设备验收、runtime
actual trace 和人工发布批准仍不能由这些结果推断。

## 当前怎么使用

- **Local**：当前可选，强制使用已准备好的本地 LiteRT-LM 聊天模型；不自动改用远端。
- **Remote**：当前可选，强制使用用户已经配置的 OpenAI-compatible 远端；仍受现有
  隐私分类和远程发送披露约束，`LocalOnly` 内容不会因此获准出端，也不自动回退本地。
- **Auto**：当前 debug 和 release-like 构建都以 `opt_in` 暴露三态入口；用户显式
  选择后，远端仍须已配置、已授权并完成披露。`Off` 阶段的降级逻辑仍由代码和测试
  保留，但不是当前 release-like 默认配置。

构建中可选择 Auto，不等于已取得真机验收或发布批准。手动 Local/Remote 路径仍可使用；
远端可选性不会改变 `LocalOnly` 边界。

## Preference 与实际位置

`InferenceMode` 现在表示长期偏好，`RunPlacement` 表示一次 run 的实际 serving 位置；
两者不能互相替代。

| Preference | 已合并语义 | 当前开放状态 |
| --- | --- | --- |
| `Local` | 只允许 Local；本地不可用、能力不匹配或资源硬阻断时明确失败 | 可用 |
| `Auto` | 手机在合法候选中决定 `RunPlacement.Local` 或 `RunPlacement.Remote` | debug 与 release-like 均为显式 opt-in；发布验收未完成 |
| `Remote` | 只允许已配置远端；隐私或远端条件不满足时明确失败 | 可用 |

未知、空白或未来版本的 preference 编码回落到 Local，升级迁移本身不会发起模型请求。

## 手机端如何做 Auto 决策

放置策略是手机上的确定性纯 Kotlin 策略，不把原始 prompt 交给路由器。它按以下顺序
处理：隐私和本地证据边界、能力需求、用户偏好、候选健康，再在两个合法候选之间依据
稳定资源与结构化问题复杂度取舍。

Remote 只有在内容为 `RemoteEligible`、不要求本地模型，并且远端已配置、已授权、
能力匹配、连接快照新鲜且为 `Reachable`、配置 revision 一致时，才会成为 Auto 候选。
状态未知、过期、鉴权失败或不可达都会把它排除；策略也能接收过载信号，但第一阶段
不实现远端负载协议。第一阶段只复用用户手工配置的 OpenAI-compatible 端点，不做
电脑发现或自动配对。

当 Local 与 Remote 都合法时，当前策略是：

- 图片请求且本地模型支持视觉输入时优先 Local，保持图片在端侧。
- 稳定低内存、最新低内存、稳定 Hot，或 Severe/Critical thermal 时选择 Remote。
- 结构化复杂度为 Complex 时选择 Remote；70% 以上本地上下文压力、Medium/High
  reasoning、多步计划、工具循环或至少 4,096 输出 token 都会形成复杂信号。
- 简单或信息不足的请求选择 Local。
- 可选的云端路由（Codiv OpenJev，默认关闭）只在上面按复杂度二选一时介入：Shadow
  只调用并计数、不改结果；Active 下置信度 ≥ 0.9 的判断决定本机或云端，记为
  `AUTO_DECISION_MODEL_LOCAL/REMOTE`。只发送本条请求文字，疑似个人或密钥内容不发；
  超时、HTTP 错误或不合协议的回复保持规则结果。准确率用
  `scripts/run_decision_routing_eval.py` 在 `docs/decision_routing_eval.json` 上测量。

资源状态使用手机进程内的稳定窗口，而不是单次 CPU 尖峰：10 秒窗口最多保留三次
采样，至少两票形成稳定档位，并对 Hot/本地硬阻断保留 15 秒冷却。资源和复杂度只在
隐私、能力、授权等硬门禁之后生效，不能把 `LocalOnly` 内容变成可远传内容。

## Run 级执行链

阶段一要求所有 serving-model 路径遵守同一顺序：

```mermaid
flowchart LR
    Input["输入、历史、附件和证据"] --> Privacy["privacy\n聚合最严格隐私"]
    Privacy --> Decide["decide\n手机端放置策略"]
    Decide -->|Blocked| Stop["明确阻断\n零 runtime 调用"]
    Decide -->|Local / Remote| Bind["bind\nrunId 固定 placement"]
    Bind --> Disclose{"disclose\n所选远端是否需确认?"}
    Disclose -->|取消或失效| Stop
    Disclose -->|不需要或已确认| Dispatch["dispatch\n只调用绑定目标"]
    Dispatch --> Local["本地 LiteRT-LM"]
    Dispatch --> Remote["已配置远端"]
```

截至事实基线，首次 Chat 已通过 prepared run 执行 `privacy → decide → bind →
disclose → dispatch`，工具续写、重试、停止/取消和受限恢复也从 active binding 读取
实际 placement；三态 UI 已显示 preference 与 actual placement/reason。stop-before-
dispatch、首个远程事件前审计、失败/取消丢弃暂存审计等竞态已由聚焦回归覆盖。设备和
真实 release trace 尚未验证，所以这条链路仍不是发布保证。

## 同一 run 的 binding

已合并的 binding/dispatcher 合同把 `runId`、preference、实际 placement、原因码、
策略版本、资源/复杂度枚举和远端 profile revision 固定在一起：

- binding 先持久化并原子 reserve，dispatcher 再原子 claim；任一步失败都不得调用
  serving runtime。
- dispatcher 只根据已绑定 placement 选择本地或远端 adapter；另一 placement 不能
  claim 同一个 run。同目标后续 attempt 可以递增，但不能跨目标重试。
- trace/binding 只记录枚举化诊断和不透明 revision，不记录原始 prompt、API key、
  endpoint 或 IP。
- 首次调用、工具续写、重试、停止/取消和受限恢复已经迁移到 binding；275 项集成 JVM
  回归已通过。真机生命周期与 release trace 仍待验证。

## 实施与证据状态

| Slice | 当前事实 | 状态 |
| --- | --- | --- |
| T01–T07 / S1–S2 | preference、复杂度、稳定资源、放置策略和独立隐私计划已合并 | 已完成 |
| S3 | Room binding、CAS dispatcher 和 placement/invocation/receipt trace 基础已合并 | 已完成；connected 迁移证据 pending |
| S4 | rollout、Auto 授权、revision 校验和 app-scoped 资源/连接 wiring 已合并 | 已完成 |
| S5 | prepared run 与首次完整执行链已集成 | 已完成；stop-before-dispatch 与 deferred audit 回归通过 |
| S6 | 续写、retry、stop/cancel 和受限恢复读取 binding | 已完成；continuation audit 的 commit/discard 双审通过 |
| S7 | 三态配置和 actual placement/reason UI 已集成 | debug/release-like opt-in；Auto 专项设备 instrumentation pending |
| S8 | Audit/eval 从 invocation 读取实际位置并完成三方 fail-closed 对账 | 已完成 |
| S9 | capability/privacy/release 验证脚本强化与事实文档 | 代码已完成；本地可执行门禁通过，发布证据 pending |

当前证据与历史证据分开记录：

- 初始阶段一的 275 项聚焦 JVM 结果属于 `1ff31a8` 历史基线。
- `cb7e4918` 的全量 2688 项 JVM 和 Lint 已通过；远端
  [Android Verification](https://github.com/William-zgx/solin-android/actions/runs/41699276903)
  也完成完整验证及 40-case behavior boundary 检查。这不是 Auto 专项设备验收。
- 当前 capability verifier 按源码校验 release-like 的 `opt_in`，并检查 serving source
  不从 preference 猜测实际 target。此前 JDK 17 环境限制已由 JDK 21 验证补上。
- 远端模拟器任务因 `emulator-infra-hvf-unsupported` 跳过，不可把工作流绿色状态
  当作 connected、Room 迁移或 Auto 设备验收通过。详细命令与证据见
  [验证报告](../../validation_report.md)。

以下证据仍为 pending：

- `connectedDebugAndroidTest` 的 Room 17→18 迁移，以及 S7 Auto/actual placement 设备
  instrumentation。
- 真机上的 Auto 资源/复杂度路由、披露、续写、重试、停止/取消和 UI 解释。
- 30 天内的 `agent_loop_runtime` actual trace、placement reconciliation、release-like 构建
  门禁、性能基线、签名证书、隐私/安全/法务人工批准和正式发布记录。

本阶段尚未发布，也未获准用于广泛生产分发。设备验收要求见
[phone_acceptance.md](../../phone_acceptance.md)，发布状态仍以
[release_readiness.md](../../release_readiness.md) 为准。
