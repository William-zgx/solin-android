# Intent Routing / Skill Arbitration

## 目标

路由层按“明确动作优先、风险边界优先、误触发最小化”处理。系统设置、权限设置、App 内搜索、普通 App 打开必须分层明确；没有明确动作意图时保持 `NoAction`。

## 优先级矩阵

| 输入类型 | 示例 | 期望路径 | 说明 |
| --- | --- | --- | --- |
| 否定、解释、设计讨论 | `解释 Wi-Fi`、`不要打开 Wi-Fi`、`Wi-Fi 设置页面怎么设计` | `NoAction` | 不进入 Skill，也不生成工具草稿。 |
| 系统 Wi-Fi 设置动作 | `打开WiFi`、`打开 WiFi`、`打开 Wi-Fi` | `device_settings_skill` / `open_wifi_settings` | Skill-first 和 ActionPlanner 都必须优先命中系统设置。 |
| 其他系统/权限设置动作 | `打开无障碍设置`、`打开 Usage Access` | `device_settings_skill` / 对应 settings tool | 系统设置高于普通 App 打开。 |
| 明确命名 App 打开 | `打开名为 WiFi 的 App` | App navigation 层 | 不能误触系统 Wi-Fi 设置。 |
| App 内搜索 | `打开淘宝搜索耳机` | App navigation + UI control | 低风险搜索闭环，失败时输出 resolver evidence。 |
| 普通 App 打开 | `打开淘宝` | App navigation 层 | 只有不命中系统设置/权限设置时才进入。 |

## Phase 1 合同

- `IntentRoutingDecision`：记录 Skill-first、ActionPlanner、remote tool planning 的命中路径、优先级、拒绝原因和确认策略。
- `UiTargetResolutionEvidence`：记录 resolver ranked candidates、label、bounds、clickable/editable、profile hint、score components、selected node 和 failure kind。
- `AgentBehaviorEvalCase`：记录输入、期望工具、确认策略、risk level、LocalOnly/RemoteEligible 和允许失败模式。
- `DeviceVerificationArtifact`：记录 serial、API、ABI、test count、failedTarget、reason、instrumentation/logcat 路径和 SHA-256 绑定。
- `ModelCapabilityProfile`：复用现有 `ModelProfile`，让 UI/runtime 从单一 profile 判断 chat、vision、embedding、action 能力。

`DeviceVerificationArtifact` 在脚本层落地为 `device-verification.properties`：
`artifact_schema=DeviceVerificationArtifact/v1`、`artifact_id`、`serial`、`api_level`、`abi`、`test_count`、
`instrumentation_output_file` / `instrumentation_output_sha256`、`logcat_file` / `logcat_sha256`、
`failedTarget` 和 `reason`。Release validation verifier 会追 physical device report 与 API matrix nested
device report 的 instrumentation/logcat SHA，防止只填路径不绑定内容。

## Resolver Evidence 策略

`UiTargetResolver.explain()` 只提供可解释诊断，不替换 Accessibility runtime 的实际点击/输入路径。debug eval receiver 会把 `targetResolution.*` 字段写入每次 tap/type/submit 的 request result，便于真实 App 失败时回看候选排名；正式 tool result schema 暂不扩大。
