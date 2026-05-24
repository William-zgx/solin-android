# PocketMind 验证报告

验证时间：2026-05-24

## 模拟器完整功能回归

环境：

- AVD：`pocketmind_api36_arm64`
- Android：API 36 Google APIs ARM64
- 安装包：`app/build/outputs/apk/release/app-release-local-signed.apk`
- 模型目录：`/sdcard/Android/data/com.bytedance.zgx.pocketmind/files/Download/`

模型补齐结果：

- `gemma-4-E2B-it.litertlm`：已安装，约 2.4 GB。
- `embeddinggemma-300m.litertlm`：已补装，约 171 MB。
- `mobile-actions_q8_ekv1024.litertlm`：已补装，约 271 MB。
- 模型管理页确认三类能力均出现在“本地模型”，设备检查在基础能力齐全后显示 `待下载：已就绪`。

真实交互覆盖：

- 普通聊天：保留既有 `用三句话解释端侧大模型` 成功回答验证。
- 记忆增强：新会话发送 `Remember my rcode is xb83`，停止生成后会重建记忆索引；追问 `What is my rcode` 时 UI 显示 `已引用本地记忆 1 条`，回答 `你的 rcode 是 xb83。`
- 动作草稿：发送 `open wifi settings` 后只展示确认 Sheet；未确认时仍停留在 App；点击 `确认并打开` 后进入系统 Wi-Fi 设置页。
- 会话管理：会话列表展示当前会话、消息数量和历史会话，入口可正常打开。
- 模型管理：推荐模型区支持基础对话、记忆、动作模型逐项补装/重下；Top K 滑条不再展示密集刻度点；下载完成后不再残留旧进度条。

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
