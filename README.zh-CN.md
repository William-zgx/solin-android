# Solin Android

[![Android Verification](https://github.com/William-zgx/solin-android/actions/workflows/android.yml/badge.svg)](https://github.com/William-zgx/solin-android/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-3DDC84.svg)](https://developer.android.com)
[![Status](https://img.shields.io/badge/status-experimental-orange.svg)](#当前状态)

<p align="center">
  <a href="README.md">English</a> | 简体中文
</p>

<p align="center">
  <img src="docs/assets/solin_brand_lockup.png" alt="Solin brand lockup" width="280">
</p>

Solin Android（应用名 `栖知 Solin`）是一个实验性的隐私优先 Android 助手。
支持本地 LiteRT-LM Text+Vision 聊天、可选 OpenAI 兼容远程端点，以及在用户确认后
执行手机侧工具（提醒、分享、应用导航、屏幕文本、OCR、联系人、日历、低风险应用搜索）。

## 目录

- [产品契约](#产品契约)
- [实现要点](#实现要点)
- [首次打开与信任流程](#首次打开与信任流程)
- [手机控制范围](#手机控制范围)
- [当前状态](#当前状态)
- [快速开始](#快速开始)
- [配置与密钥](#配置与密钥)
- [推荐模型](#推荐模型)
- [验证](#验证)
- [文档](#文档)
- [贡献](#贡献)
- [安全](#安全)
- [许可证](#许可证)

## 产品契约

- **默认本地优先**：聊天历史、记忆、私有工具结果、屏幕文本、OCR、本地图片和
  附件摘要默认保留在设备上，以 `LocalOnly` 处理，除非用户主动选择远程路径。
- **远程能力可选**：远程聊天仅在配置端点并选择远程模式后启用。图片、疑似敏感
  文本以及已配置的 `remote sends` 需要预览或确认。
- **只确认关键操作**：设备动作在本地经过权限、披露、审计和 fail-closed 边界。
  打开应用、填写草稿、设置提醒、点击滚动等可撤销操作直接执行；外发、敏感步骤，以及
  含敏感信息或读过隐私数据之后的联网查询/链接需要确认；支付、发送、删除、发布、下单、授权等按钮始终由
  用户亲手点击。信任中心可切回"每步确认"。
- **用户保持控制**：用户可以清除密钥、删除会话和记忆；涉及隐私的行为必须在发布前
  文档化。

```mermaid
flowchart LR
    User["用户请求"] --> Router["技能优先路由"]
    Router --> Local["本地 LiteRT-LM"]
    Router --> Remote["可选远程端点"]
    Router --> Tools["已确认的本地工具"]
    Tools --> Registry["ToolRegistry validation"]
    Registry --> Safety["SafetyPolicy"]
    Safety --> Confirm["用户确认"]
    Confirm --> Android["Android 权限 / intents / Accessibility"]
    Local --> Answer["助手回答"]
    Remote --> Answer
    Android --> Audit["脱敏 trace 与 audit"]
    Audit --> Answer
```

## 实现要点

- LiteRT-LM 本地聊天，支持 GPU/CPU 回退和显式模型加载。
- 本地记忆索引；语义召回仅在运行时探测通过后可用。
- 本地图片输入只对已验证的本地聊天模型开放；不支持的模型 fail closed，而非
  静默 OCR 或上传图片。
- OpenAI 兼容远程聊天，并在本地过滤 `LocalOnly` 上下文。
- 基于注册表的工具、内置 Skills、本地安全策略、脱敏 trace 和 audit 记录。
- model-driven 应用搜索用已校验的本地 Chat/动作规划模型 bootstrap；本地规划不可用时
  回退到静态 Skill 路径。
- **远程视觉 GUI 自动化（可选）**：信任中心开关开启、远程模式、且模型支持视觉时，
  Solin 通过 `AccessibilityService.takeScreenshot` 截取当前屏幕（无需 MediaProjection、
  无需新增前台服务），连同屏幕控件文字（不含密码框）发送给远程视觉模型，一次回复可规划
  点击、输入、提交等多步，并作为本地动作执行——仍需通过全部设备控制预检。屏幕内容外发由该开关（每步确认模式下另需首次确认）
  和每次外发审计把关；截图或发送失败时 fail-closed 并停止循环。
- **云端路由（可选）**：自动模式下，本机和已配置的远程模型都可用时，可只把本条请求
  文字发给 Codiv OpenJev 决策模型，判断由本机还是云端回答。“只观察”只计数，“启用”
  采用置信度 ≥ 0.9 的判断。Codiv API Key 由用户在信任中心填写。
- 聊天界面只展示安全结果摘要；结构化工具字段通过 trace/audit surface 查看，而非
  直接插入 `typed chat card`。

## 首次打开与信任流程

Solin 启动后直接进入助手界面。首次使用时，用户可选择远程配置、推荐本地模型下载、
受信任模型导入或模型管理。本地设置、远程发送、附件、语音、记忆和工具执行都保持为
可见的用户选择。

脚本回归和手工验收必须分开记录。Voice 输入、Android system document picker、
foreground 提示和 MediaProjection consent sheet 都是系统介导流程，需要真实设备验收。

## 手机控制范围

手机控制仅限低风险导航和搜索：observe、tap、type、submit search、scroll、swipe
（滑动）、long-press（长按）、系统按键（home/recents/enter/delete）、back 和 wait。
系统按键是固定白名单，绝非任意键码。Agent 从不点击支付、发送、删除、发布、下单、
转账或授权类按钮：按名称或坐标点到这类按钮时会在执行前停下，由用户亲手点击。输入
和滚动在任何页面都可用；提交、回车、滑动、长按在页面上有这类按钮时会停下。切回
"每步确认"后，低风险应用控制每 5 个 UI 动作回到用户确认一次。

当应用启动携带后续意图（follow-up intent）时，Solin 可在已打开的应用中继续操作，
前提是已安装本地移动操作模型，并受 expectedPackageName
前台守卫约束。若模型缺失或目标应用不在前台，则 fail-closed 回退为"仅打开不继续"，
且该延续保持 LocalOnly。

可选的远程视觉路径用支持视觉的远程模型替代本地动作模型完成应用内延续：远程模型看到
屏幕截图和控件文字后返回点击/返回/滚动/输入/提交/等待/停止（或其中几步的短计划），
作为本地 `ui_*` 动作或 `finish` 在相同的预检规则下执行。此路径默认关闭，需要信任中心"远程视觉 GUI 自动化"开关、远程推理模式以及
支持图片输入的模型。外发同意与审计模型见 `docs/privacy_notice.md`。

## 当前状态

适合本地开发、个人评估和受控测试构建。尚未准备好用于大规模应用商店发布或生产分发。

- 仓库包含源码、测试、脚本、文档和少量项目资源。
- 仓库不包含模型权重、API key、keystore、签名密码、用户数据或生成的发布产物。
- 推荐模型下载是第三方产物，必须分别检查其上游许可证、访问规则和再分发条款。
- bundled-model 包是内部实验室产物，直到模型许可证、再分发、署名和 notice 审批
  完成前都不应作为公共发布路径。

## 快速开始

要求：JDK 17+、Android SDK 36（target SDK 36，min API 28）、用于真实 LiteRT-LM 验证的
arm64-v8a Android 真机。

```bash
git clone https://github.com/William-zgx/solin-android.git
cd solin-android
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

启动后选择一种开始方式：配置 OpenAI 兼容远程端点、下载推荐的本地 E2B 模型，或导入受信任
且兼容的 `.litertlm` 模型。

## 配置与密钥

Solin 不依赖提交到仓库的密钥。远程端点可在应用内配置，也可以用环境变量进行本地验证。

| 变量 | 用途 | 说明 |
| --- | --- | --- |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | Gradle 和脚本 | Android SDK 位置。 |
| `ANDROID_SERIAL` | 设备脚本 | 选择一台已授权手机或模拟器。 |
| `SOLIN_HF_TOKEN` | bundled-model 构建 | 用于下载 gated Hugging Face 产物的凭据；不代表许可证审批。 |
| `SOLIN_LIVE_REMOTE_BASE_URL` | 远程调试辅助 | 报告中会脱敏。 |
| `SOLIN_LIVE_REMOTE_MODEL` | 远程调试辅助 | 报告中会脱敏。 |
| `SOLIN_LIVE_REMOTE_API_KEY` | 远程调试辅助 | 不能提交或写入记录。 |
| `RELEASE_KEYSTORE` 和相关签名变量 | 签名脚本 | 只应来自私有签名环境。 |

提交敏感变更前，请先运行本地密钥扫描：

```bash
scripts/privacy_scan.sh --report build/verification/privacy-scan.properties README.md README.zh-CN.md docs app/src/main scripts
```

如果 token 或签名密钥进入 Git 历史，应先视为已泄露并撤销，然后清理历史并轮换相关凭据。

## 推荐模型

推荐模型元数据固定在 `docs/model_manifest.md`。下载内容只有在大小和 SHA-256 校验通过后
才会登记。

| 能力 | 产物 | 约略大小 | 用途 |
| --- | --- | ---: | --- |
| 基础聊天 E2B | `.litertlm` 聊天模型 | 2.59 GB | 默认本地 Text+Vision 聊天路径 |
| 本地记忆 | EmbeddingGemma `.tflite` + tokenizer | 184 MB | 运行时探测通过后的语义记忆索引 |
| 设备动作 | `.litertlm` 动作模型 | 284 MB | 带规则回退的受限动作规划 |
| 高质量聊天 E4B | `.litertlm` 聊天模型 | 3.66 GB | 更高质量的本地 Text+Vision 聊天选项 |

模型文件不会提交到 Git。普通公共发布产物不打包模型文件。内部 `bundledModels` 包是快速
体验和实验室验证的文档化例外，详见 `docs/bundled_model_package.md`。

## 验证

```bash
# 本地验证
scripts/doctor.sh
scripts/verify_local.sh

# 设备或模拟器验证
scripts/doctor.sh --device
ANDROID_SERIAL=<device-or-emulator> scripts/install_and_test_device.sh

# 完整模拟器回归（更严格的产物门禁）
AVD_NAME=focus_agent_api36_arm64 scripts/regression_emulator.sh
```

只有当 `regression-emulator.properties` 包含 `status=passed` 时，才能把模拟器回归记录为
通过。

model-driven 应用搜索 eval 属于 debug/device 检查，需显式设置
`RUN_MODEL_DRIVEN_APP_SEARCH_EVAL=1`。mock 和 real app 模式都通过 `verifySearchQuery`、
`expectedPackageName`、`expectedAppName` 校验结果，并要求
`searchVerificationStatus=verified`。

需要真实设备行为，或需要保留已下载模型、远程配置、会话和手工验收状态的流程，请参考
`docs/phone_acceptance.md`。

## 文档

- English README: `README.md`
- 架构与模块归属：`docs/agent_core_modules.md`
- 隐私边界：`docs/privacy_notice.md`
- 模型来源：`docs/model_manifest.md`
- bundled-model 实验室包：`docs/bundled_model_package.md`
- 设备/手工验收：`docs/phone_acceptance.md`
- 发布就绪状态：`docs/release_readiness.md`
- 文档索引：`docs/index.md`

## 贡献

欢迎贡献。一个有用的变更通常应包含清晰的问题陈述、聚焦的代码或文档修改、测试或验证说明，
以及用于设备相关问题的安全日志或截图。

发起 pull request 前请运行 `scripts/verify_local.sh`。如果变更涉及设备流程，也请遵循
`docs/phone_acceptance.md`。新增工具、Skills、模型路径和手机控制行为需要 schema 校验、
隐私分级、确认策略、审计覆盖和测试。

适合入门的贡献方向：保持 owner docs 聚焦的文档修正、围绕工具 schema 和安全策略的测试、
低风险应用搜索和屏幕观察回归的 replay fixture、保持现有 `testTag` 的 UI 可访问性修复。

## 安全

请不要在公开 issue 中包含密钥、私有端点、个人数据、敏感截图、未发布的签名细节或受再分发
限制的模型文件。

建议披露流程：

1. 在不暴露私有 payload 的前提下复现问题。
2. 捕获最小化的安全日志、堆栈或验证报告。
3. 在公开细节前，先私下联系仓库所有者。
4. 附上受影响 commit、Android 版本、设备或模拟器类型以及受影响区域。

安全敏感修复应保持默认 fail-closed 行为，并在可行时加入回归测试。

## 许可证

Solin Android 应用代码基于 MIT License 发布，第三方源码另有声明的部分除外。从 Mika
适配的手机操作代码保留 Apache-2.0 许可，详见 [NOTICE](NOTICE) 和
[随附许可证](licenses/Apache-2.0.txt)。推荐模型下载是第三方产物，受其上游许可证
约束；请参考 `docs/model_manifest.md` 和 `docs/model_license_review.json`。
