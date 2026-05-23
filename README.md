# PocketMind Android

PocketMind 是一个 Android 本地问答 App：安装后可在应用内下载推荐的 LiteRT-LM 模型、粘贴模型下载链接，也可以导入已有 `.litertlm` 文件，然后在手机端离线流式问答。

## 模型

内置推荐 Hugging Face LiteRT Community 的 Gemma 4 E2B / E4B LiteRT-LM 模型：

<https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm>
<https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm>

App 内置的模型选择会下载：

```text
https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm
https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm
```

E2B 文件约 2.59 GB，E4B 文件约 3.66 GB。也可以在首屏粘贴自定义 `.litertlm` 下载链接。建议使用 Wi-Fi，并确保手机有足够空间。下载或导入成功后会自动加载；GPU 初始化失败时会自动切到 CPU。

模型文件不要提交到仓库，也不要放进 APK。大模型应由用户安装后下载，或从手机本地导入。

## 手机端使用

1. 安装 APK 后打开“PocketMind”。
2. 首屏默认是聊天界面；点顶部“模型”进入模型管理，选择 E2B/E4B 后下载，或粘贴下载链接，已有模型时点“导入本地文件”。
3. 下载或导入成功后会出现在“本地模型”列表里，点任意本地模型即可切换并重新加载。
4. 看到“离线可用”后直接输入问题；点顶部“会话”可以新建、切换或删除会话。
5. 如果设备 GPU 不支持，App 会自动切到 CPU，速度会慢一些但仍可使用。

## 运行

```bash
./gradlew assembleDebug
```

首次运行 Gradle Wrapper 会下载 Gradle 9.5.1。本机需要安装 Android SDK 36。

然后用 Android Studio 或 `adb install app/build/outputs/apk/debug/app-debug.apk` 安装到真机。GPU 后端依赖手机厂商 OpenCL 驱动；如果初始化失败，切到 CPU 后重新加载模型。安卓模拟器通常没有可用的 OpenCL，不建议用来验证 GPU。

## 测试

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

本地单元测试覆盖模型目录、文件名、精确大小、存储空间、下载状态、进度和字节显示。连接真机后可再跑启动冒烟测试：

```bash
adb devices
./gradlew connectedDebugAndroidTest
```

也可以直接运行脚本：

```bash
scripts/verify_local.sh
scripts/install_and_test_device.sh
```

更完整的真机验收步骤见 [docs/phone_acceptance.md](docs/phone_acceptance.md)。
本次真机验证结果见 [docs/validation_report.md](docs/validation_report.md)。

真机手动验收建议：

1. 首次启动看到聊天页，顶部有“模型 / 会话 / 新建”入口。
2. 点“模型”打开模型管理，Wi-Fi 下点击推荐模型下载或粘贴模型链接下载，进度可见，完成后自动加载。
3. 断网后可以继续问答。
4. 导入一个已有 `.litertlm` 文件时显示复制进度，并能加载。
5. 下载或导入多个模型后，在“本地模型”中切换，确认顶部当前模型名随之变化。
6. 如果 GPU 初始化失败，App 自动切到 CPU 并仍可问答。

连接真机：手机打开开发者选项和 USB 调试，用数据线连接 Mac，在手机弹窗里允许调试；`adb devices` 显示 `device` 后即可安装和跑测试。
