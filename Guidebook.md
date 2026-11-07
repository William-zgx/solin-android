# Guidebook — 栖知 Solin 架构速览

面向开发者与 coding agent 的短索引。产品入口：[English](README.md) ·
[简体中文](README.zh-CN.md)；改代码前遵循 [AGENTS.md](AGENTS.md)。

## 项目定位

栖知 Solin 是实验性的隐私优先 Android 助手：本地 LiteRT-LM Text+Vision 聊天、可选
OpenAI 兼容远程推理，以及受权限、确认和审计约束的手机工具。默认只确认关键操作，
获准的可撤销步骤可以直接执行；这不等于自动批准所有工具。

仓库采用 mainline 单仓：源码、文档和脚本同树。主产品代码在 `:app`；`releaseSmoke`
用于发布包测试，四个 `modelpack*` 模块（目录在 `modelpacks/` 下）用于内部 bundledModels
分发，不是产品包边界拆分。

## 按任务找文档

| 目标 | 事实归属 |
| --- | --- |
| 构建、产品功能、配置 | [README](README.zh-CN.md) |
| 文档所有权与维护规则 | [文档索引](docs/index.md) |
| Agent、工具、运行时与持久化 | [核心模块](docs/agent_core_modules.md) |
| 深模块目标、两道安全门禁、威胁模型 | [架构方向](docs/architecture_depth.md) |
| 历史重构、并行所有权与遗留项 | [结构债记录](docs/optimization_plan_weaknesses.md) |
| 默认本地、外发、确认与保留期限 | [隐私说明](docs/privacy_notice.md) |
| 手机组合工具、路线复用、后台显示实验 | [手机能力合同](docs/plans/mika-phone-capabilities.md) |
| 模型来源、固定版本与校验值 | [模型清单](docs/model_manifest.md) |
| 内部模型分包、签名和覆盖安装 | [Bundled models](docs/bundled_model_package.md) |
| Auto 当前实现与构建开关 | [端云推理状态](docs/specs/20260718-adaptive-edge-inference/status.md) |
| 设备操作、模型任务与物理设备验收 | [设备验收指南](docs/phone_acceptance.md) |
| 实际运行过的命令、版本和结果 | [验证报告](docs/validation_report.md) |
| 发布阻塞与人工审批 | [发布就绪状态](docs/release_readiness.md) |

最少阅读顺序：README 相关产品/构建段 → `docs/index.md` → 本任务的 owner 文档。
不必为局部修改加载所有架构或历史计划。

## 代码地图

根包：`app/src/main/java/com/bytedance/zgx/solin/`。

| 包 | 主要职责 |
| --- | --- |
| `orchestration/` | Agent 循环、路由、观察、预算、恢复与确认策略 |
| `tool/`、`action/` | 工具注册、schema、执行边界、有界组合及 Android 动作适配 |
| `skill/` | 内置技能与经过验证的会话路线复用；`skill/external/` 为外部 Skill 包 |
| `device/`、`device/background/` | 无障碍读屏与动作、输入核验、实验独立显示 helper/session |
| `safety/`、`audit/`、`evidence/` | 风险与隐私决策、脱敏审计、证据存储 |
| `runtime/`、`multimodal/` | 本地/远程推理、附件与 OCR 边界 |
| `data/`、`memory/`、`storage/` | 设置/会话/模型持久化、本地索引与存储契约 |
| `presentation/`、`ui/` | 状态与任务协调、Compose 界面 |
| `download/`、`resource/`、`background/` | 下载与校验、资源采样、提醒与定时任务 |
| `module/`、`mcp/` | 模块注册冻结、MCP 接入 |
| `capability/`、`plan/`、`eval/`、`rcperf/`、`logging/` | 能力声明、计划、评估、性能证据和日志 |
| `common/` | 跨包共用的 JSON 读取与哈希小工具 |

根包只放应用入口与组装根、跨包共享模型和 Activity 侧协调器（清单见
[AGENTS.md](AGENTS.md)）；新功能代码放进所属的包。

调用路径和生命周期以[核心模块](docs/agent_core_modules.md)为准。不要用易过期的文件
行数或控制器数量判断架构质量；深度指标和门禁归属由[架构方向](docs/architecture_depth.md)维护。

## 必须保持的边界

- `LocalOnly` 数据默认留在本机；未知隐私元数据按本地处理，不能静默 OCR 或上传。
- 网络查询、输入和文本交给其他 App 都受敏感性及同一运行的私有读取历史约束。
  具体可自动执行列表见 [AGENTS.md](AGENTS.md) 和[隐私说明](docs/privacy_notice.md)。
- 支付、发送、删除、发布、下单、转账、授权控件由用户亲手点击；组合工具或路线复用
  不继承旧授权，每个子动作仍通过现有门禁。
- 远程视觉需要显式开启、远程模式、支持图片的模型，以及本次运行首个应用内步骤的
  用户确认；两种确认模式都不能跳过截图外发批准。后台显示作用域禁用此路径及主屏 OCR。
- 后台显示仅为 debug 实验：显式授权、短期会话、丢失绑定后拒绝操作，不回退主屏。
- 远程审计不存 raw prompt；密钥、模型权重、用户数据和发布产物不进 Git。

## 验证与交付

使用 JDK 21 与 CI 对齐。按变更选择最小有效门禁；完整本地门禁包含脚本回归、JVM、
Lint、构建和产物检查，单独编译成功不能替代它。

```bash
# 完整本地门禁
scripts/doctor.sh
scripts/verify_local.sh

# 实现期间按需选择
./gradlew :app:testDebugUnitTest --tests "<affected-test-pattern>"
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests "com.bytedance.zgx.solin.architecture.*"
```

纯文档修改只运行相关链接、格式和记录校验；隐私相关文档另跑 `scripts/privacy_scan.sh`。
设备与发布证据见上述 owner 文档。CI 的基础设施 skip 不等于设备测试通过；完整模拟器
回归必须有 `regression-emulator.properties` 的 `status=passed`。

## 文档与贡献

一条事实由一个 owner 文档维护，其余文档链接过去。历史计划和验证记录不应改写成
当前功能承诺。新工具应包含 schema、隐私分级、确认策略、审计和测试；并行写入遵循
[AGENTS.md](AGENTS.md) 的独占文件与隔离工作树规则。

应用自有代码采用 MIT；从 Mika 适配的部分保留 Apache-2.0，见 [NOTICE](NOTICE)。推荐
模型为第三方产物，下载权限与文件校验均不代表再分发审批。
