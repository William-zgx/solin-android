# Guidebook — 栖知 Solin 架构速览

面向人类与 coding agent 的**短索引**。细节以 `docs/` 为准，本页只指路。

English product entry: [`README.md`](README.md) · 中文入口: [`README.zh-CN.md`](README.zh-CN.md)  
Agent 协议（必读）: [`AGENTS.md`](AGENTS.md)

---

## 1. 这是什么

**栖知 Solin** 是实验性的、隐私优先的 Android 端侧助手：

- 本地 LiteRT-LM（Text+Vision）聊天，或可选 OpenAI 兼容远程端点
- 工具 / Skill 在用户确认后执行手机侧动作（提醒、分享、导航、屏幕文本、OCR 等）
- 默认 **LocalOnly**；远程与高风险动作 fail-closed + 确认 + 审计

仓库形态：**mainline 单仓**（源码 + 文档 + 脚本同树），主产品代码在 `:app`，不是多包 monorepo 产品 harness。

---

## 2. 先读哪里

| 目标 | 文档 |
| --- | --- |
| 构建 / 验证 / 产品契约 | `README.md` / `README.zh-CN.md` |
| 文档角色与流程图 | `docs/index.md` |
| Agent / 工具模块归属 | `docs/agent_core_modules.md` |
| 结构债与 Wave 并行规则 | `docs/optimization_plan_weaknesses.md` |
| 隐私边界 | `docs/privacy_notice.md` |
| 模型来源与校验 | `docs/model_manifest.md` |
| 真机验收 | `docs/phone_acceptance.md` |
| ViewModel / Screen / UiState 拆分计划 | `docs/plans/` |

Coding agent 最少读：`README` → `docs/index.md` → `docs/agent_core_modules.md` → `docs/optimization_plan_weaknesses.md`（见 `AGENTS.md`）。

---

## 3. 代码地图

根包：`app/src/main/java/com/bytedance/zgx/solin/`

```text
orchestration/  聊天·记忆·工具·动作路由与 Agent loop
tool/           ToolSpec、注册表、校验、执行
skill/          内置 Skill 与规划
safety/         风险、确认、隐私决策
runtime/        本地 LiteRT / 远程运行时
memory/         本地记忆索引与语义运行时
device/         设备上下文快照
data/           模型、会话、设置持久化
storage/        本地 KV / 文档 / 向量存储契约
ui/             Compose 界面
resource/       系统资源采样与稳定压力状态
presentation/   ViewModel facade / 19 个文件（8 个命名控制器，已落地）
action/         手机动作规划与 Android 执行边界
audit/          脱敏工具审计
evidence/       证据 blob 加密存储
multimodal/     分享、附件、OCR 边界
background/     提醒与定时任务
module/         SolinModule 注册与冻结
mcp/            Model Context Protocol 服务器接入
credentials/    API Key / OAuth 凭证解析
download/       模型下载与校验
capability/     能力矩阵与产品定位
plan/           多步计划持久化与工具桥接
undo/           动作撤销策略
rcperf/         RC 性能基准（纯 JVM 可测）
eval/           AI 行为评估数据模型
logging/        SolinLog 结构化日志门面
```

请求主路径（摘要）：

```text
用户输入 → Skill-first 路由 → 本地模型 / 远程 / 工具
工具 → ToolRegistry 校验 → SafetyPolicy → 用户确认 → 执行 → 脱敏 audit/trace
```

更完整的生命周期图见 `docs/agent_core_modules.md`。

---

## 4. 不可协商边界

- **LocalOnly 默认**；未知隐私元数据按 LocalOnly fail-closed
- **中风险及以上必须确认**；不绕过 `SafetyPolicy`
- **远程审计不落 raw prompt**
- **密钥 / 模型权重 / 用户数据不进 Git**
- 低风险手机控制限：observe / tap / type / submit search / scroll / back / wait

详细产品表述以 README 与 `docs/privacy_notice.md` 为准。

---

## 5. 结构债现状与演进方向

Wave 1–6 已完成（见 `docs/optimization_plan_weaknesses.md` §20–§22）。三大热点文件已大幅瘦身：

| 文件 | 原始行数 | 现行 | 协作面 |
| --- | ---: | ---: | --- |
| `SolinViewModel.kt` | ~6,800 | ~1,500 | `presentation/*` 19 个文件（8 个命名控制器） |
| `SolinScreen.kt` | ~6,300 | ~1,600 | `ui/components/*` 17 个叶子组件 |
| `AgentLoopRuntime.kt` | ~5,400 | ~3,400 | `orchestration/ToolPlanCoordinator` 等 5+ 协作类 |

**原则：** 先逻辑边界与架构测试，再物理 Gradle module；增量可合并，禁止 Big Bang。  
**并行改代码时：** 遵守 `optimization_plan_weaknesses.md` §5 文件所有权表（与 `AGENTS.md` 一致）。

---

## 6. 本地验证

```bash
scripts/doctor.sh
scripts/verify_local.sh
./gradlew :app:testDebugUnitTest
```

编译与架构测试子集：

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests "com.bytedance.zgx.solin.architecture.*"
```

设备流：`docs/phone_acceptance.md`。发布门禁与证据：`docs/release_readiness.md`、`docs/validation_report.md`。

---

## 7. 贡献与安全

- PR 前跑 `scripts/verify_local.sh`；设备相关改动对照 `phone_acceptance.md`
- 新工具 / Skill：schema、隐私分级、确认策略、审计与测试齐套
- 勿在公开 issue 中贴密钥、私有端点、敏感截图或模型文件
- 许可证：应用代码 MIT；推荐模型为第三方产物（见 `docs/model_manifest.md`）

---

## 8. 文档维护

- 一条事实只放在一个 owner 文档，其它处链接（见 `docs/index.md` Editing Rules）
- 本 Guidebook **不**替代 `agent_core_modules.md` 或 release 证据日志
- 结构里程碑与 Wave 状态以 `docs/optimization_plan_weaknesses.md` 为准
