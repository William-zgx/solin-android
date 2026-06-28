# 真机验收指南

本页只回答一件事：怎样在真机或模拟器上证明当前构建能安装、启动，并通过关键用户路径。发布放行由 `docs/release_checklist.md`、最终 release gate 和 release owner sign-off 判定；`docs/release_blocker_dashboard.md` 只汇总风险。

## 证据口径

- 真机证据优先。JVM、lint、build、模拟器、debug eval、人工观察都不能替代 release physical-device evidence。
- release 真机证据必须绑定设备 serial、API、ABI、执行命令、APK/AAB SHA-256、`device-verification.properties`、`instrumentation.txt`、`logcat.txt` 及对应 SHA-256。
- 失败也要可审计：报告必须写 `failedTarget` 和 `reason`，不能只写自由文本。
- debug device-control 和 real-app search eval 只证明 App 控制链路 readiness，不是正式 release validation。
- 覆盖安装必须使用 `adb install -r` 或脚本中明确的覆盖安装路径，并记录数据是否保留。
- `bundledModels` 是内部体验包口径，不是 Play/public release 口径；模型 license、redistribution approval、正式签名和 store/privacy review 仍要单独完成。

```mermaid
flowchart TD
    Local["本地验证\nscripts/verify_local.sh"] --> Device["真机回归\ninstall_and_test_device.sh"]
    Device --> Manual["人工验收\n系统入口/模型/隐私/动作"]
    Manual --> Perf["RC 性能基线\ncollect_perf_baseline.sh"]
    Perf --> Gate["release gate\nverify_release_gate.sh"]
    Device -.debug only.-> DebugEval["Debug receiver boundary\ndevice-control / real-app search eval"]
    DebugEval -.not release validation.-> Gate
    Manual --> InstallR["覆盖安装\nadb install -r"]
    InstallR --> Preserve["确认 Room/DataStore/模型数据保留"]
```

## 连接设备

1. 手机连续点击“版本号”打开开发者选项。
2. 打开“USB 调试”。
3. 使用支持数据传输的 USB 线连接 Mac。
4. 在手机弹窗中允许调试。
5. 在仓库根目录确认设备：

```bash
adb devices -l
```

继续前必须看到一台状态为 `device` 的已授权设备。多台设备同时连接时，显式设置：

```bash
export ANDROID_SERIAL=<physical-device-serial>
```

小米 / HyperOS / MIUI 出现 `INSTALL_FAILED_USER_RESTRICTED` 时，在开发者选项里打开“USB 安装 / 通过 USB 安装”，并在安装确认弹窗中允许。

## 自动回归

本地验证不要求设备，也不要求 `adb` 在 `PATH`：

```bash
scripts/doctor.sh
scripts/verify_local.sh
```

真机或模拟器验收要求 Android SDK 中存在 `adb`，且目标设备已授权：

```bash
scripts/doctor.sh --device
ANDROID_SERIAL=<physical-device-serial> scripts/install_and_test_device.sh
```

`doctor --device` 只检查 SDK 工具。设备选择、授权状态、ABI、可用空间、APK 安装、AndroidTest 安装、instrumentation 总数和 App 启动由 `install_and_test_device.sh` 检查。没有授权设备、设备 `offline` / `unauthorized`、未指定目标且存在多台设备时，脚本会在 Gradle 构建、安装和 instrumentation 前退出。

默认脚本会保留 debug App 安装包，但在最终人工启动前执行 `pm clear` 清空 App 数据。这会删除私有存储中的模型文件、模型登记、远程配置、会话和消息。需要连旧安装包一起清理时使用：

```bash
CLEAN_DEVICE=1 ANDROID_SERIAL=<physical-device-serial> scripts/install_and_test_device.sh
```

需要保留测试后的数据、模型或远程配置时，显式设置：

```bash
RESET_APP_DATA_AFTER_TESTS=0 ANDROID_SERIAL=<physical-device-serial> scripts/install_and_test_device.sh
```

不要用 `./gradlew :app:connectedDebugAndroidTest` 作为最后一步来保留真机安装；Android Gradle Plugin 可能在 instrumentation 后清理安装包。

instrumentation 超时必须按失败记录，不能替代 release physical-device evidence。至少保留 `failedTarget`、`reason`、`instrumentation_test_count`、设备 serial/API/ABI、`instrumentation.txt` SHA-256 和 `logcat.txt` SHA-256。需要缩小复现范围时再设置：

```bash
INSTRUMENTATION_CLASS=<fully.qualified.TestClass> \
ANDROID_SERIAL=<physical-device-serial> \
scripts/install_and_test_device.sh
```

## 覆盖安装与人工安装

人工查看当前 debug 包时，不要把完整 smoke 脚本作为最后一步；它默认清空 App 数据。使用只负责安装和报告的脚本：

```bash
ANDROID_SERIAL=<physical-device-serial> \
ARTIFACT_DIR=build/verification/manual-acceptance-install-current \
scripts/install_review_device.sh
```

需要临时注入远程模型配置时通过环境变量传入。报告只记录变量来源，不记录实际密钥：

```bash
ANDROID_SERIAL=<physical-device-serial> \
SOLIN_REVIEW_REMOTE_BASE_URL=<https-base-url> \
SOLIN_REVIEW_REMOTE_MODEL=<model-name> \
SOLIN_REVIEW_REMOTE_API_KEY=<api-key> \
ARTIFACT_DIR=build/verification/manual-acceptance-install-remote-current \
scripts/install_review_device.sh
```

该报告会写 `target=manual-acceptance-install` 和 `regressionEvidence=false`，只能作为人工安装证据，不能作为 release physical regression evidence。

内部 ad hoc release smoke 可以用 release 构建加本地临时签名覆盖安装，验证混淆/压缩后的 APK 能启动并保留 App 数据。该签名不等同于正式分发签名：

```bash
./gradlew :app:assembleRelease

BUILD_TOOLS="$ANDROID_SDK_ROOT/build-tools/36.0.0"
UNSIGNED=app/build/outputs/apk/release/app-release-unsigned.apk
ALIGNED=app/build/outputs/apk/release/app-release-local-aligned.apk
SIGNED=app/build/outputs/apk/release/app-release-local-signed.apk

"$BUILD_TOOLS/zipalign" -p -f 4 "$UNSIGNED" "$ALIGNED"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$HOME/.android/debug.keystore" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$SIGNED" \
  "$ALIGNED"
"$BUILD_TOOLS/apksigner" verify --verbose "$SIGNED"
adb -s "$ANDROID_SERIAL" install -r "$SIGNED"
adb -s "$ANDROID_SERIAL" shell am start -n com.bytedance.zgx.solin/.MainActivity
```

覆盖安装后重点确认：已有 Room/DataStore 状态、会话、远程配置和已下载模型按预期保留；如果之前选择过远程模式，需手动切回本地再判断 bundled/local 模型是否可用。

## 隐私边界人工核对

远程模型模式下，`记住：...` 只写入本地记忆，不应发送到远程模型。相关验收记录必须说明本地记忆、Accessibility 文本、OCR 摘录和截图派生内容的隐私等级；OCR 摘录属于 `LocalOnly`，只能用于本机一次性确认或本地处理路径。

## 内置模型体验包

验收“安装后直接可用”的内部体验包时，使用 `bundledModels` split 包，不使用普通 debug/release 单 APK。该路径会把推荐的 E2B、E4B、本地记忆模型和设备动作模型打入 install-time modelpack split，并在首启复制、校验、注册到本地模型目录。

```bash
export SOLIN_BUNDLED_MODELS_DIR=/path/to/verified/model/files
ALLOW_DEBUG_KEYSTORE=1 \
INSTALL_ON_DEVICE=1 \
ANDROID_SERIAL=<physical-device-serial> \
scripts/package_bundled_models.sh
```

安装必须使用脚本固定的 `adb install-multiple --no-incremental -r`。不要退回 incremental install；2026-06-26 在 `fb6272c` 上默认 incremental 曾快速返回 `Success`，但 PackageManager 中没有主包，不能作为安装成功证据。

至少确认：

- `pm path com.bytedance.zgx.solin` 同时列出 `base.apk` 和四个 split：`split_modelpackE2b.apk`、`split_modelpackE2bExtra.apk`、`split_modelpackE4b.apk`、`split_modelpackE4bExtra.apk`。
- 模型管理页中 E2B、E4B、本地记忆模型、设备动作模型都显示 `SHA-256 已校验`。
- 新装或切回本地后，首页显示 `本机模型已就绪`，当前模型为基础对话 E2B，健康状态为 `已加载`，并标明 GPU 或 CPU backend。
- 本地记忆文件 SHA 校验通过不等于语义记忆 runtime 已可用；embedding runtime probe 失败时，UI 可显示 `已安装待探测` 或 `已回退轻量索引`。

## 模拟器与远程 debug 检查

模拟器用于 UI、确认链路、工具失败路径和普通聊天回归；LiteRT-LM 性能、GPU 行为和正式 release physical evidence 仍以真机为准。

```bash
AVD_NAME=focus_agent_api36_arm64 scripts/regression_emulator.sh
```

完整模拟器回归只以 `regression-emulator.properties` 中的 `status=passed` 为准。

在 x86 Linux 工作站做 UI 实效检查时，先准备 x86_64 AVD，再跑截图链路：

```bash
scripts/check_x86_emulator_host.sh
APPLY=1 scripts/prepare_x86_emulator.sh
scripts/capture_x86_release_screenshots.sh
```

默认 AVD 为 `pocketmind_api36_x86_64`，默认 headless 启动参数为 `-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot`。如果要打开模拟器窗口，可显式传空的 `EMULATOR_ARGS`，但当前新版 Android Emulator 的 Qt UI 可能要求 glibc 2.30 或更新：

```bash
EMULATOR_ARGS= scripts/capture_x86_release_screenshots.sh
```

这条 x86_64 链路只用于开发模拟和截图复核；正式 release evidence 仍以 arm64 模拟器矩阵和 arm64 真机验收为准。

已有模拟器时可用 emulator-only helper，避免误选真机：

```bash
ANDROID_SERIAL=emulator-5554 scripts/verify_emulator.sh
```

真实远程模型 debug 检查使用 `scripts/live_remote_emulator.sh`。脚本默认只选 emulator；要在真机上跑必须显式设置 `SOLIN_LIVE_REMOTE_TARGET=device`。远程 base URL、model、API key 必须来自环境变量或静默 stdin，报告不得记录实际 key。

```bash
SOLIN_LIVE_REMOTE_TARGET=device \
ANDROID_SERIAL=<physical-device-serial> \
SOLIN_LIVE_REMOTE_BASE_URL=<https-base-url> \
SOLIN_LIVE_REMOTE_MODEL=<model-name> \
SOLIN_LIVE_REMOTE_API_KEY=<api-key> \
scripts/live_remote_emulator.sh
```

## Debug App 控制验收

手机控制专项验收使用已授权真机和 debug eval receiver：

```bash
ANDROID_SERIAL=<physical-device-serial> scripts/run_device_control_debug_eval.sh
ANDROID_SERIAL=<physical-device-serial> scripts/run_real_app_search_eval.sh
```

`run_real_app_search_eval.sh` 验证真实 App 的低风险搜索闭环，不等同于正式 release validation。完成 debug 验收后，应使用 `adb install -r` 覆盖安装最新签名 release 包，以保留已下载模型数据并恢复正式包。

报告要求：

- 顶层 fatal 必须写 `failedTarget` 和 `reason`，例如 `device-selection` / `selected-device-unavailable`。
- 已选中设备后必须记录 serial/API/ABI、logcat 路径和 SHA-256。
- 每个失败 case 使用 `RealAppSearchCaseArtifact/v1`，包含 `failed_step`、debug receiver `result_file` 与 SHA-256、resolver evidence、diagnostics 目录、截图、UIAutomator XML、focused-window dump、logcat 路径和 SHA-256。
- 淘宝、拼多多、高德、京东、Chrome、Android Browser、Quark、UC 是低风险搜索矩阵目标；未安装只记录 skipped。

2026-06-27 无线真机补充记录：

- 设备：`192.168.1.27:35537`，Xiaomi `23127PN0CC`，API 36，`arm64-v8a`。
- 覆盖安装 connected tests 通过：
  `build/verification/device-wireless-action-observation-20260627-123000/device-verification.properties`，
  `clean_device=0`、`reset_app_data_after_tests=0`、`instrumentation_test_count=63`。
- Device-control debug eval 通过：
  `build/verification/device-control-debug-wireless-action-observation-rerun-20260627-124000/device-control-debug-eval.properties`，
  `command_count=39`。
- Real-app search eval 通过：
  `build/verification/real-app-search-wireless-action-observation-final-20260627-125900/real-app-search-eval.properties`，
  `run_count=7`、`pass_count=7`、`skip_count=1`、`fail_count=0`。
- 本轮使用 `adb install -r` 或 `SKIP_INSTALL=1`，未执行 `pm clear`，未删除已下载或已加载模型。

## 手工验收场景

手工验收只记录用户可见行为和必要系统弹窗，不能用脚本通过、直接调用 ViewModel/reader、mock intent 或 UI 文案存在替代。

## 必须手工验收的系统入口

- 语音输入必须在设备上点麦克风入口，观察 Android 系统语音识别、收音/转写条、取消/完成状态和最终文本进入输入框。
- 系统文档选择器必须从输入区附件按钮打开，观察本地摘录、metadata-only、远程预览确认和取消路径。
- 当前屏幕截图 OCR 必须在确认卡后观察 Android MediaProjection 前台同意弹窗；取消和同意后的单次消费行为不能用 provider 直接调用替代。

聊天中只应追加安全摘要；结构化工具结果、allowlisted completion metadata
和执行细节通过 Agent trace / audit 入口查看。Agent trace / audit 应提供足够的
状态、工具名、风险、权限和红acted summary，不能暴露原始私密 payload。

| 场景 | 必看点 |
| --- | --- |
| 模型准备 | 主界面的远程配置、推荐模型下载、导入模型或内置体验包路径；下载进度/取消、`SHA-256 已校验`、离线问答、token/s、模型切换、GPU 失败后 CPU fallback。 |
| 导入模型 | `.litertlm` 可导入并加载；非 `.litertlm`、无效 URL、空间不足、断网都有可理解失败提示。 |
| 远程模型 | HTTPS 配置、API key 非明文保存、流式响应、取消恢复、错误不泄露响应体或 Authorization；敏感内容逐次确认，取消不得请求远程。 |
| 记忆 | `记住/忘记/清空` 只走本地控制路径；远程模式不得自动携带长期记忆文本或 embedding。语义记忆必须证明不是关键词召回。 |
| 动作与 Skill | 中高风险和外发文本动作先确认；取消不执行；低风险公开 `web_search` 可无确认；混入私密或副作用工具的批次全批拒绝。 |
| 系统入口 | 语音输入、系统文档选择器、MediaProjection 当前屏幕 OCR 必须在设备上点真实系统入口。 |
| 多模态 | 文本/RTF/PDF/Office 只做有界本地摘录；图片仅在已验证本地视觉或逐次确认远程视觉时读取；音频/视频/未知二进制 metadata-only。 |
| 后台任务 | 提醒确认后才创建；Android 13+ 通知权限拒绝不误报成功；任务完成/失败/取消后的列表状态正确。 |
| 资源入口 | compact 宽度从 `更多` 菜单进入；普通状态不显示大号百分比圆环；详情优先展示 App 内存、可用 RAM、App CPU、温度，高级 heap 指标靠后。 |

远程模型模式下，`记住：...` 只写入本地记忆控制路径，不把长期记忆文本或 embedding 自动发送给远程模型。OCR 摘录按 LocalOnly 处理；远程视觉发送与 OCR 工具分离，必须经过逐次确认。

2026-06-25 曾在 `fb6272c`、Xiaomi 23127PN0CC、API 36、`arm64-v8a` 上人工观察资源入口通过，但未绑定 `build/verification/` artifact 和 SHA-256；该记录只能作参考，不能替代正式 release physical-device evidence。

## RC 性能基线

正式 RC 需要把真机实测指标写成 `perf-baseline.properties`，并与签名 APK/AAB SHA-256 绑定。脚本只记录已测得的值，不生成推测值：

```bash
OUT_FILE=build/verification/rc/perf-baseline.properties \
RELEASE_ARTIFACT=app/build/outputs/apk/release/app-release-signed.apk \
ANDROID_SERIAL=<physical-device-serial> \
APP_VERSION=<versionName> \
MODEL_ID=chat-e2b \
BACKEND=GPU \
FIRST_LAUNCH_INTERACTIVE_MS=<measured> \
MODEL_LOAD_MS=<measured> \
FIRST_TOKEN_MS=<measured> \
TOKENS_PER_SECOND=<measured> \
STOP_GENERATION_RECOVERY_MS=<measured> \
GPU_FALLBACK_STATUS=<not-needed|cpu-fallback-passed> \
VISION_INPUT_MS=<measured> \
MEMORY_SEARCH_5K_MS=<measured> \
ZVEC_MEMORY_INDEX_50K_MS=<measured> \
ZVEC_MEMORY_SEARCH_50K_MS=<measured> \
MEMORY_PEAK_MB=<measured> \
OOM_OR_ANR_OBSERVED=false \
scripts/collect_perf_baseline.sh
```

推荐用真机 harness 自动采集这些字段：

```bash
ANDROID_SERIAL=<physical-device-serial> \
RELEASE_ARTIFACT=app/build/outputs/apk/release/app-release-signed.apk \
APP_VERSION=<versionName> \
HARNESS_MODEL_ID=<installed-vision-chat-model-id> \
scripts/collect_rc_perf_from_device.sh
```

`HARNESS_MODEL_ID` 可指定已安装且支持视觉输入的本地对话模型，例如 `chat-e4b`。该参数只决定本次只读 perf harness 加载哪个已安装模型，不修改用户当前 active model，不删除模型文件。
