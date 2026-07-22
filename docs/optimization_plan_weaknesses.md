# 针对已知缺点的优化方案（可执行版）

> 状态：Wave 1–6 已完成（见 §20–§22 战役总结）
> 关联：`docs/plans/viewmodel-split.md`、`solin-screen-split.md`、`uistate-split.md`、`data-layer-suspend-migration.md`
> 目标：把「安全 harness 很强、热点文件不可演进」的债务，变成可并行交付的结构改进。
> 架构原则与愿景见 §2；已落地的结构化改进（日志/常量/加密/并发等）见 `agent_core_modules.md` 对应章节。

---

## 1. 问题回放（只保留可行动条目）

| ID | 缺点 | 症状 | 业务/工程代价 |
| --- | --- | --- | --- |
| W1 | 上帝对象 | `SolinViewModel` ~6.8k、`SolinScreen` ~6.3k、`AgentLoopRuntime` ~5.4k | 并行改动冲突、AI/人 diff 易误伤、单测锁定耦合形态 |
| W2 | 文档/门禁超前于结构 | release JSON/verifier 完备，热点拆分仍在 plan | 给「生产就绪」错觉；结构债继续膨胀 |
| W3 | 包边界只是约定 | 单 `:app` 模块，手工 DI | 错误依赖无法被编译挡住 |
| W4 | 能力面宽、默认路径重 | 工具/远程/屏幕/记忆全开在主路径 | 心智负担大，真机门槛高 |
| W5 | 测试强锁回归、弱促演进 | 巨型 integration test | 重构成本指数上升 |
| W6 | 开源/身份与协作叙事弱 | 包名/角色文档 vs 个人实验仓库 | 贡献门槛高 |
| W7 | UI 产品层偏「面板堆叠」 | Screen 参数爆炸、重组面过大 | 体验与性能双输 |

非目标（本方案不碰）：放宽 fail-closed、削弱确认/审计、把私有上下文默认可上传。

---

## 2. 原则

1. **结构债优先于能力扩张**：新工具/新 Agent 能力默认要求「落在已拆边界内」。
2. **增量可合并**：每步独立可编译、可测、可回滚；禁止 Big Bang 重写。
3. **先逻辑边界，后物理模块**：package + 架构测试稳定后，再拆 Gradle module。
4. **不破坏隐私契约**：`LocalOnly` / 确认 / 审计 / ToolSpec 策略语义不变。
5. **多 Agent 并行时文件所有权互斥**：同一 PR 波次内禁止两名 agent 编辑同一热点文件的重叠区段。

---

## 3. 目标架构（瘦 facade）

```text
presentation/
  SolinViewModel.kt              # 薄 facade：组装 controller + 转发
  controllers/
    ChatController.kt
    ModelLoadController.kt
    ToolExecutionController.kt
    MemoryController.kt          # 已存在 memory/，可迁或保留
    DeviceContextController.kt
    SettingsController.kt
    EvidenceUiController.kt
    SessionController.kt
  ChatUiState.kt / substates/    # 后续 UiState 拆分

ui/
  SolinScreen.kt                 # 编排壳
  components/                    # 叶子 composable
  actions/SolinScreenActions.kt

orchestration/
  AgentLoopRuntime.kt            # 薄 facade
  AgentTurnRunner.kt
  ToolPlanCoordinator.kt
  ObservationPlanner.kt          # 从 AgentObservationReplanner 继续收束
  IntentRoutingSupport.kt
  AgentRunBudget.kt

tool/                            # 后续：contract / registry / executor 再分文件
```

---

## 4. 分期路线图

### Wave 1 — 立刻（本轮多 Agent）— 降上下文 + 建护栏

| 轨道 | Owner 文件 | 交付物 | 完成定义 |
| --- | --- | --- | --- |
| A 文档/Harness | `AGENTS.md`、`Guidebook.md`、本文件、`docs/index.md` | Agent 入口协议 | 新 agent 读 3 个文件即可开工 |
| B UI 叶子拆分 | `ui/SolinScreen.kt` → `ui/components/*` | Glyph/Backdrop/Trust/Home 等叶子迁出 | Screen 行数明显下降，UI 测试绿 |
| C ViewModel 瘦身 | `SolinViewModel.kt` + `memory/` + `presentation/` | Memory 残留方法归位；纯函数迁出；ModelLoad 起步 | ViewModel 行数下降，相关单测绿 |
| D AgentLoop 收束 | `orchestration/AgentLoopRuntime.kt` | 路由/预算等无状态逻辑外提 | Loop 行数下降，orchestration 单测绿 |
| E 边界测试 | `app/src/test/.../architecture/` | 包依赖禁止规则 | 违规依赖 CI 可拦 |

**Wave 1 成功指标（量化）**

- `SolinScreen.kt` 减少 ≥800 行（叶子迁出）
- `SolinViewModel.kt` 减少 ≥300 行（helper + memory 归位）
- `AgentLoopRuntime.kt` 减少 ≥150 行（routing/budget）
- 新增架构测试 ≥3 条
- `./gradlew :app:testDebugUnitTest` 通过（或至少关键子集 + 编译）

### Wave 2 — 控制器化 ViewModel（1–2 周）

按 `docs/plans/viewmodel-split.md`：

1. `ModelLoadController`（下载/导入/backend/远程配置）
2. `SessionController` + `ChatController`（会话与发送）
3. `ToolExecutionController`（确认/边界执行）
4. `DeviceContextController` / `SettingsController` / `EvidenceUiController`
5. `SolinViewModel` 仅转发 + 组合 `ChatUiState`

每步：行为不变、`SolinViewModelTest` 绿、再开下一步。

### Wave 3 — UiState + Screen 编排（并行于 Wave 2 后半）

按 `uistate-split.md` + `solin-screen-split.md`：

1. 引入 `SolinScreenActions` 收敛 50+ 回调
2. `ChatUiState` 拆子状态（先 download/voice 高频面）
3. Screen 按 surface 拆 sheet（ModelManager / Memory / Background / Audit）

### Wave 4 — AgentLoop 真正拆职责

1. `ToolPlanCoordinator`（observation → next plan）
2. `AgentTurnRunner`（单 turn 模型调用契约）
3. 收束 `AgentObservationReplanner`
4. 事件总线已有骨架时，禁止再新增直调扇出

### Wave 5 — 物理模块（仅边界测试稳定后）

候选：`:core:tool-contract`、`:core:orchestration`、`:feature:chat-ui`  
前提：Wave 1–4 的架构测试 0 违规，且无循环依赖。

### Wave 6 — 产品默认路径收敛（与结构并行、不改安全语义）

- 首次打开：本地聊天 or 远程二选一，高级工具进 Trust/能力区
- model-driven 应用搜索保持 opt-in eval
- 文档与 capability matrix 对齐「默认路径 vs 高级路径」

---

## 5. 多 Agent 协作协议（强制）

### 5.1 文件所有权（Wave 1）

| Agent | 可写 | 只读 |
| --- | --- | --- |
| Docs | `AGENTS.md`、`Guidebook.md`、`docs/**`（除他人正在改的 plan 细节可不碰） | 源码 |
| UI | `app/.../ui/components/**`（新建）、`ui/SolinScreen.kt` | ViewModel/Loop |
| ViewModel | `presentation/**`（新建）、`SolinViewModel.kt`、`memory/MemoryController.kt`、必要时 `SolinAppContainer.kt` | Screen/Loop |
| Loop | `orchestration/*Support*.kt`、`AgentRunBudget.kt` 等新建、`AgentLoopRuntime.kt` | Screen/VM |
| Boundary | `app/src/test/**/architecture/**` | 生产代码只读 |

冲突规则：两 agent 都要改同一文件 → 后完成者 rebase/合并，或交 integrator。

### 5.2 验证命令

```bash
# 本地快速
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests "com.bytedance.zgx.solin.architecture.*"
# 更完整
scripts/verify_local.sh
```

### 5.3 PR / 提交策略

- 优先 **一个 Wave 一个集成提交** 或 **按轨道分提交**（docs / ui / vm / loop / arch-test）
- 提交信息说明「为哪条缺点 W# 服务」
- 禁止夹带无关功能

---

## 6. 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| 抽取后可见性行为微变 | 只搬代码不改逻辑；依赖现有单测 |
| 可见性从 private → internal 扩大 | 限制在同模块；后续可 file-private 再收 |
| ViewModel 状态共享难抽 | Wave 1 只抽纯函数与已有 MemoryController；重状态控制器放 Wave 2 |
| 并行合并冲突 | 所有权表 + integrator 串行合并热点 |

回滚：各轨道独立文件，可按 commit 回退单轨。

---

## 7. 完成定义（项目级）

- [x] W1 热点文件持续下降（Wave 1 基线与结果已记录；架构水位线已收紧）
- [x] W3 架构测试阻止 UI→tool 实现细节、tool→ui 等反向依赖（首批 7 规则）
- [ ] W2 发布门禁与「结构就绪」解耦：README/status 明确 experimental + 结构里程碑
- [x] 新能力 PR 模板要求：`AGENTS.md` 禁止继续堆进三大热点；水位线测试兜底

---

## 8. Wave 1 基线（实施前）

| 文件 | 约行数（实施前） |
| --- | ---: |
| `SolinViewModel.kt` | 6807 |
| `SolinScreen.kt` | 6343 |
| `AgentLoopRuntime.kt` | 5436 |

实施后在本文件追加「Wave 1 结果」表。

---

## 9. Wave 1 实施状态

| 轨道 | 状态 | 说明 |
| --- | --- | --- |
| A 文档/Harness | **已落地** | 根目录 `AGENTS.md`、`Guidebook.md`；`docs/index.md` 已链到本文件与 harness 入口 |
| B UI 叶子拆分 | **已落地** | `ui/components/SolinChrome.kt`、`TrustSheetChrome.kt`；Screen −291 行 |
| C ViewModel 瘦身 | **已落地** | `presentation/*` 纯函数迁出；Memory 三个 mutator 接入 `MemoryController`；VM −112 行 |
| D AgentLoop 收束 | **已落地** | `orchestration/AgentLoopRouting.kt`；Loop −135 行 |
| E 边界测试 | **已落地** | `architecture/PackageBoundaryArchitectureTest` 7 规则；集成编译/关键单测通过 |

## 10. Wave 1 结果（集成后）

| 文件 | 实施前 | 实施后 | Δ |
| --- | ---: | ---: | ---: |
| `SolinViewModel.kt` | 6807 | 6695 | **−112** |
| `SolinScreen.kt` | 6343 | 6052 | **−291** |
| `AgentLoopRuntime.kt` | 5436 | 5301 | **−135** |
| **三大热点合计** | **18586** | **18048** | **−538** |

新增/扩充协作面：

| 路径 | 角色 |
| --- | --- |
| `AGENTS.md` / `Guidebook.md` | Agent/人 harness 入口 |
| `ui/components/*` | Screen 叶子 chrome |
| `presentation/*` | ViewModel 纯展示/选择辅助 |
| `orchestration/AgentLoopRouting.kt` | 路由与 plan→route 映射 |
| `memory/MemoryController` mutators | 记忆开关/遗忘/清空 |
| `test/.../architecture/*` | 包边界 + 上帝对象水位线 |

验证（集成）：

- `./gradlew :app:compileDebugKotlin` — SUCCESS  
- architecture / SolinScreenDisplay / MemoryController 相关 unit tests — SUCCESS  
- orchestration 轨道单独验证：365 tests PASS  

### Wave 1 对照成功指标

| 指标 | 目标 | 实际 |
| --- | --- | --- |
| SolinScreen 减行 | ≥800 | 291（叶子 chrome 优先，未强拆 home/sheet） |
| SolinViewModel 减行 | ≥300 | 112（纯函数 + 记忆 mutator；重状态控制器留 Wave 2） |
| AgentLoopRuntime 减行 | ≥150 | 135（接近） |
| 架构测试 | ≥3 | **7** |
| 编译/关键单测 | 绿 | 绿 |

**自评**：Wave 1 以「可并行、可回滚、不改行为」为先，行数目标部分未满额，但 **护栏 + 协作面 + 记忆委托** 已就位；下一波应优先 `ModelLoadController` 与更多 Screen sheet 迁出以补足行数指标。

### 建议的立即下一步（Wave 2 起手）

1. `ModelLoadController`：下载/导入/backend/远程配置（最大 VM 块）  
2. `ui/components`：ModelManager / RemoteDisclosure / ActionDraft sheets  
3. 将架构水位线在稳定一周后下调（例如 Screen ≤6200、VM ≤6800、Loop ≤5400）  
4. 继续把 `rebuildMemoryIndex` 等同步路径完整委托给 `MemoryController`  

---

## 11. Wave 2 实施计划

目标：控制器化 ViewModel 热路径 + 继续拆 Screen sheet；**不改隐私/确认语义**。

| 轨道 | Owner | 交付 | 状态 |
| --- | --- | --- | --- |
| B2 UI sheets | `ui/components/*` + `SolinScreen.kt` | Disclosure / ActionDraft / ExternalOutcome | **已落地** |
| C2 ModelLoadController | `presentation/ModelLoadController.kt` + VM | 下载/导入/选型/HF/远程/setup/loadModel | **已落地** |
| C2b Memory 全委托 | `MemoryController` + VM 记忆路径 | rebuild/load/sync/explicit 委托 | **已落地** |
| SessionController | — | 与 recreateConversation 强耦合 | **本波跳过** → Wave 3 |

## 12. Wave 2 结果（集成后）

相对 **Wave 1 结束** 基线：

| 文件 | Wave 1 后 | Wave 2 后 | Δ |
| --- | ---: | ---: | ---: |
| `SolinViewModel.kt` | 6695 | **5498** | **−1197** |
| `SolinScreen.kt` | 6052 | **5337** | **−715** |
| `AgentLoopRuntime.kt` | 5301 | 5301 | 0（本波未改） |
| **三大热点合计** | 18048 | **16136** | **−1912** |

相对 **项目审视时** 原始基线（6807 / 6343 / 5436）：

| 文件 | 原始 | 现 | 累计 Δ |
| --- | ---: | ---: | ---: |
| ViewModel | 6807 | 5498 | **−1309** |
| Screen | 6343 | 5337 | **−1006** |
| Loop | 5436 | 5301 | **−135** |
| **合计** | 18586 | 16136 | **−2450** |

新增协作面（Wave 2）：

| 路径 | 角色 |
| --- | --- |
| `presentation/ModelLoadController.kt` (~1261) | 模型下载/导入/加载/远程就绪 |
| `ui/components/RemoteModeDisclosureSheet.kt` | 远程模式披露 |
| `ui/components/RemoteSendDisclosureSheet.kt` | 远程发送披露 |
| `ui/components/ActionDraftSheet.kt` | 动作确认草稿 |
| `ui/components/ExternalOutcomeSheet.kt` | 外部 outcome 确认 |
| `TrustSheetChrome.SectionTitle` | 共享 section 标题 |

验证：`compileDebugKotlin` + architecture / ui / memory / SolinViewModelTest 相关单测 — SUCCESS。

架构水位线（Wave 2 收紧）：VM ≤5700，Screen ≤5500，Loop ≤5400。

### Wave 2 对照成功指标

| 指标 | 目标 | 实际 |
| --- | --- | --- |
| SolinViewModel ≤6200 | 是 | **5498** ✓ |
| SolinScreen ≤5600 | 是 | **5337** ✓ |
| ModelLoadController 委托 | 是 | ✓ |
| Memory 全委托 | 是 | ✓ |

### Wave 3 建议起手

1. `SessionController` + 抽离 `recreateConversation*` 协作面  
2. `ChatController`（send/stop/shared input）— 最大剩余 VM 块  
3. `ModelManagerSheet` 从 Screen 迁出（仍在 SolinScreen 内）  
4. `ToolExecutionController`（confirm/dismiss agent confirmation）  
5. Loop：`ToolPlanCoordinator` / observation 规划收束  

---

## 13. Wave 3 实施计划

目标：继续拆 Screen 最大 sheet 面 + Session/Tool 控制器化；Chat send 全路径若风险高则只抽边界清晰部分。

| 轨道 | Owner | 交付 | 状态 |
| --- | --- | --- | --- |
| B3 UI ModelManager+ | `ui/components/*` + Screen | Model/Trust/Memory/Session/Background sheets | **已落地** |
| C3 SessionController | `presentation/SessionController.kt` + VM | create/select/delete + recreate | **已落地** |
| C3b ToolExecutionController | `presentation/ToolExecutionController.kt` + VM | confirm/execute/restore 工具路径 | **已落地** |
| ChatController | — | send/stop/shared/continueAfterToolObservation | **本波跳过**（仍耦合最深） |

## 14. Wave 3 结果（集成后）

相对 **Wave 2 结束**：

| 文件 | Wave 2 | Wave 3 | Δ |
| --- | ---: | ---: | ---: |
| `SolinViewModel.kt` | 5498 | **4533** | **−965** |
| `SolinScreen.kt` | 5337 | **2820** | **−2517** |
| `AgentLoopRuntime.kt` | 5301 | 5301 | 0 |
| **三大热点合计** | 16136 | **12654** | **−3482** |

相对 **项目审视原始基线**（6807 / 6343 / 5436）：

| 文件 | 原始 | 现 | 累计 Δ |
| --- | ---: | ---: | ---: |
| ViewModel | 6807 | 4533 | **−2274 (−33%)** |
| Screen | 6343 | 2820 | **−3523 (−56%)** |
| Loop | 5436 | 5301 | **−135** |
| **合计** | 18586 | 12654 | **−5932 (−32%)** |

Wave 3 新增协作面：

| 路径 | 角色 |
| --- | --- |
| `presentation/SessionController.kt` (~251) | 会话 CRUD + recreate |
| `presentation/ToolExecutionController.kt` (~982) | 工具确认/执行/restore |
| `ui/components/ModelManagerSheet.kt` + `ModelManagerPanels.kt` | 模型管理 |
| `ui/components/TrustBoundaryPanel.kt` | 隐私/审计面板 |
| `ui/components/MemoryPanel.kt` | 记忆面板 |
| `ui/components/SessionManagerSheet.kt` | 会话面板 |
| `ui/components/BackgroundTaskSheet.kt` | 后台任务/trace |

验证：compile + architecture / ui / tool / memory / SolinViewModelTest — SUCCESS。

架构水位线（Wave 3）：**VM ≤4700 / Screen ≤3000 / Loop ≤5400**。

### Wave 3 对照成功指标

| 指标 | 目标 | 实际 |
| --- | --- | --- |
| SolinViewModel ≤5000 | 是 | **4533** ✓ |
| SolinScreen ≤4500 | 是 | **2820** ✓ |

### Wave 4 建议起手

1. **`ChatController`**：`sendMessageInternal` + remote disclosure send + shared input + **`continueAfterToolObservation`**（VM 最大剩余）  
2. **AgentLoop 拆分**：`ToolPlanCoordinator` / turn runner（Loop 现为三大热点之最）  
3. **UiState 拆分**：高频 voice/download 子状态（`docs/plans/uistate-split.md`）  
4. 可选：`SettingsController`（voice / generation params / reduce confirmations）  

---

## 15. Wave 4 实施计划

| 轨道 | Owner | 交付 | 状态 |
| --- | --- | --- | --- |
| C4 ChatController | `presentation/ChatController.kt` + VM | send/stop/shared/continue/remote-send | **已落地** |
| D4 ToolPlanCoordinator | `orchestration/ToolPlanCoordinator.kt` + `AgentRunBudget.kt` + Loop | observation→next plan + budgets | **已落地** |
| UiState 拆分 | — | 高频子状态 | **本波跳过** → Wave 5 |

集成注意：并行 agent 曾用 `git checkout HEAD -- SolinScreen` 暂存 UI，导致 `ui/components` 丢失；已从 stash 恢复 Screen 瘦身版与 components，并修复 DisplayTest imports。

## 16. Wave 4 结果（集成后）

相对 **Wave 3 结束**：

| 文件 | Wave 3 | Wave 4 | Δ |
| --- | ---: | ---: | ---: |
| `SolinViewModel.kt` | 4533 | **1804** | **−2729** |
| `SolinScreen.kt` | 2820 | **2820** | 0（恢复 Wave 3 成果） |
| `AgentLoopRuntime.kt` | 5301 | **4760** | **−541** |
| **三大热点合计** | 12654 | **9384** | **−3270** |

相对 **原始基线**（6807 / 6343 / 5436）：

| 文件 | 原始 | 现 | 累计 Δ |
| --- | ---: | ---: | ---: |
| ViewModel | 6807 | 1804 | **−5003 (−73%)** |
| Screen | 6343 | 2820 | **−3523 (−56%)** |
| Loop | 5436 | 4760 | **−676 (−12%)** |
| **合计** | 18586 | 9384 | **−9202 (−50%)** |

Wave 4 新增：

| 路径 | 行数 | 角色 |
| --- | ---: | --- |
| `presentation/ChatController.kt` | ~3101 | 聊天发送/共享/远程披露/工具 continuation |
| `orchestration/ToolPlanCoordinator.kt` | ~648 | observation 后规划 |
| `orchestration/AgentRunBudget.kt` | ~86 | tool/observation 预算 |

验证：compile + architecture / ui / SolinViewModelTest / orchestration / tool / memory — SUCCESS。

架构水位线（Wave 4）：**VM ≤2000 / Screen ≤3000 / Loop ≤4900**。

### Wave 4 对照成功指标

| 指标 | 目标 | 实际 |
| --- | --- | --- |
| SolinViewModel ≤3800 | 是 | **1804** ✓ |
| AgentLoopRuntime ≤4800 | 是 | **4760** ✓ |

### 新热点提示

`ChatController.kt` ~3101 行已成为 **presentation 最大文件**，Wave 5 应再拆（local send / remote send / shared input / tool continuation）。

### Wave 5 建议起手

1. **拆 `ChatController`**：LocalGeneration / RemoteSend / SharedInput / ToolContinuation  
2. **继续拆 Loop**：`observeToolResultInternal` / turn runner  
3. **UiState 拆分**（voice/download 高频重组）  
4. 可选：物理 Gradle modules（边界测试已稳）  

---

## 17. Wave 5 实施计划

| 轨道 | Owner | 交付 | 状态 |
| --- | --- | --- | --- |
| C5 Chat 再拆 | `presentation/Chat*.kt` | RemoteSend + SharedInput + helpers | **已落地** |
| D5 Observation | `ToolObservationCoordinator.kt` + Loop | observeToolResultInternal 等 | **已落地** |
| UiState 拆分 | — | 高频子状态 | **本波跳过** → Wave 6 |

硬约束已遵守：未改 UI / components；未用 destructive checkout。

## 18. Wave 5 结果（集成后）

| 文件 | Wave 4 | Wave 5 | Δ |
| --- | ---: | ---: | ---: |
| `SolinViewModel.kt` | 1804 | **1804** | 0 |
| `SolinScreen.kt` | 2820 | **2820** | 0（`ui/components` 仍 12 文件） |
| `AgentLoopRuntime.kt` | 4760 | **4002** | **−758** |
| `ChatController.kt` | 3101 | **2142** | **−959** |

三大经典热点（VM+Screen+Loop）：**9384 → 8626（−758）**  
Chat 热点：3101 → 2142（−959）

相对 **原始基线**（6807 / 6343 / 5436）：

| 文件 | 原始 | 现 | 累计 Δ |
| --- | ---: | ---: | ---: |
| ViewModel | 6807 | 1804 | **−5003 (−73%)** |
| Screen | 6343 | 2820 | **−3523 (−56%)** |
| Loop | 5436 | 4002 | **−1434 (−26%)** |
| **合计** | 18586 | 8626 | **−9960 (−54%)** |

Wave 5 新增：

| 路径 | 行数 | 角色 |
| --- | ---: | --- |
| `ChatRemoteSendSupport.kt` | ~510 | 远程发送披露/审计 |
| `ChatSharedInputSupport.kt` | ~424 | 分享输入保护与草稿 |
| `ChatControllerHelpers.kt` | ~282 | 流式助手更新等包级助手 |
| `ToolObservationCoordinator.kt` | ~1089 | 工具观察/恢复/审计摘要 |

验证：compile + architecture / ui / SolinViewModelTest / orchestration / tool / memory — SUCCESS。

架构水位线（Wave 5）：**VM ≤2000 / Screen ≤3000 / Loop ≤4200 / ChatController ≤2300**。

### Wave 5 对照成功指标

| 指标 | 目标 | 实际 |
| --- | --- | --- |
| ChatController ≤2200 | 是 | **2142** ✓ |
| AgentLoopRuntime ≤4300 | 是 | **4002** ✓ |

### Wave 6 建议起手

1. 继续拆 `ChatController`：`sendMessageInternal` / `continueAfterToolObservation` 分轨  
2. Loop：`runOnce` / initial plan / model tool request 路径  
3. **UiState 拆分**（voice/download）  
4. 可选：`:presentation` / `:orchestration` Gradle 模块试水  

---

## 19. Wave 6+ 收尾计划

目标：清掉方案中剩余结构债；**不做**物理 Gradle 模块（风险/收益不匹配，边界测试已护航）。

| 轨道 | 交付 | 状态 |
| --- | --- | --- |
| C6 Chat 再拆 | ToolContinuation + Generation | **已落地** ChatController **2029** |
| D6 Loop 再拆 | InitialToolPlanner + PendingConfirmation + ModelToolRequest | **已落地** Loop **3399** |
| B6 Screen chrome | TopBar/Empty/Bubble/Timeline/FirstRun | **已落地** Screen **1605** |
| C6b VM residual | Voice/Background/Audit/DeviceContext | **已落地** VM **1490** |
| UiState 全拆 | Big Bang 多 StateFlow | **明确不做**（见 §21） |
| Gradle 物理模块 | `:presentation` / `:orchestration` | **明确不做**（包边界测试已足够） |

## 20. Wave 6 结果（最终集成）

| 文件 | Wave 5 | Wave 6 | Δ |
| --- | ---: | ---: | ---: |
| `SolinViewModel.kt` | 1804 | **1490** | **−314** |
| `SolinScreen.kt` | 2820 | **1605** | **−1215** |
| `AgentLoopRuntime.kt` | 4002 | **3399** | **−603** |
| `ChatController.kt` | 2142 | **2029** | **−113** |

三大经典热点（VM+Screen+Loop）：**8626 → 6494（−2132）**

相对 **原始基线**（6807 / 6343 / 5436 = 18586）：

| 文件 | 原始 | 现 | 累计 Δ |
| --- | ---: | ---: | ---: |
| ViewModel | 6807 | 1490 | **−5317 (−78%)** |
| Screen | 6343 | 1605 | **−4738 (−75%)** |
| Loop | 5436 | 3399 | **−2037 (−37%)** |
| **合计** | 18586 | **6494** | **−12092 (−65%)** |

Wave 6 新增协作面（节选）：

| 路径 | 角色 |
| --- | --- |
| `ChatGenerationSupport` / `ChatToolContinuationSupport` | 生成流与工具 continuation |
| `InitialToolPlanner` / `PendingConfirmationSupport` / `ModelToolRequestCoordinator` | Loop 初始规划与模型 tool 请求 |
| `ChatTopBar` / `ChatEmptyState` / `MessageBubble` / `ChatMessageChrome` / `FirstRunSetupPanel` | 聊天 chrome |
| `VoiceInputController` / `BackgroundTaskController` / `AuditUiController` / `DeviceContextPresentation` | VM 残留能力 |

验证：compile + architecture / ui / SolinViewModelTest / orchestration / tool / memory — SUCCESS。

架构水位线（Wave 6 终态）：**VM ≤1550 / Screen ≤1650 / Loop ≤3600 / ChatController ≤1550**。

## 21. 明确不做的项（及原因）

| 项 | 原因 |
| --- | --- |
| `ChatUiState` 多 `StateFlow` Big Bang | `_uiState.update` 仍数百处；全拆需同步改 Screen 观察与全部控制器，回归面过大。后续可按 download/voice **增量**嵌套类型再做，不在本结构债战役范围。 |
| Gradle 物理多模块 | 包级边界 + 架构测试已拦住反向依赖；物理拆分会牵动 DI/测试 classpath，收益边际、风险高。 |
| 继续压 Loop 到 <2k | 剩余多为 run 生命周期与公开 API 面，再拆 ROI 下降；现 3.4k 且已有 5+ 协作类。 |

## 22. 战役总结（Wave 1–6）

| 阶段 | 主收益 |
| --- | --- |
| Wave 1 | AGENTS 护栏、叶子 UI、Memory 委托、Loop routing、架构测试 |
| Wave 2 | ModelLoadController、Disclosure sheets、Memory 全委托 |
| Wave 3 | ModelManager 等大 sheet、Session/ToolExecution 控制器 |
| Wave 4 | ChatController 抽出、ToolPlanCoordinator |
| Wave 5 | Chat Remote/Shared 再拆、ToolObservationCoordinator |
| Wave 6 | Chat/Loop/Screen/VM 全面再拆至可维护 facade |

**产品契约未削弱**：LocalOnly 默认、fail-closed、确认/审计语义保持。
