# PocketMind Android

PocketMind Android is a local-first Android chat app for running LiteRT-LM
models on device. It can download recommended `.litertlm` models, import a
model from local storage, and stream offline answers directly on the phone.

The project is built with Kotlin, Jetpack Compose, Android Gradle Plugin, and
Google AI Edge LiteRT-LM.

## Features

- On-device streaming chat with LiteRT-LM models.
- First-run model setup for chat, local memory, and mobile action capabilities.
- Optional higher-quality chat model presets.
- Custom `.litertlm` download links and local file import.
- Model manager for switching downloaded or imported models.
- Configurable remote chat backend for OpenAI-compatible `/v1/chat/completions` services.
- Local memory recall for previous conversation context.
- Safe mobile action drafts for settings, map, mail, calendar, and contacts.
- GPU backend with CPU fallback when GPU initialization is unavailable.
- Local chat sessions with create, switch, and delete actions.
- Stop button while a response is being generated.
- Instrumented smoke tests for first launch and model manager entry points.

## Screens And Model Flow

PocketMind opens directly into the chat surface. The top bar exposes model and
session management:

1. Open **Model**.
2. Pick a recommended model, paste a custom `.litertlm` URL, or import a local
   model file.
3. Or configure a remote chat service with a base URL, model name, and optional
   API key.
4. Load the selected local model, or switch to the configured remote model.
5. Chat once the app reports that the selected backend is ready.

Model selection itself does not immediately reload the runtime. This keeps the
model manager responsive while browsing models or switching CPU/GPU; use
**Load model** when you are ready to initialize the selected model.

Remote chat uses the same conversation, memory, and action routing surface as
local chat. Mobile actions still require the local action model and explicit
user confirmation before opening Android system pages or drafts.

## Recommended Models

The app includes model-neutral capability presets. The current upstream files
are hosted on Hugging Face and can be replaced by future compatible `.litertlm`
models:

- [基础对话模型 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
- [本地记忆模型 300M](https://huggingface.co/kontextdev/embeddinggemma-300m-litertlm)
- [设备动作模型 270M](https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm)
- [高质量对话模型 E4B](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm)

The downloaded files are large:

| Capability | File | Size |
| --- | --- | --- |
| 基础对话 E2B | upstream `.litertlm` chat model | about 2.59 GB |
| 本地记忆模型 | upstream `.litertlm` embedding model | about 179 MB |
| 设备动作模型 | upstream `.litertlm` action model | about 284 MB |
| 高质量对话 E4B | upstream `.litertlm` chat model | about 3.66 GB |

Use Wi-Fi and keep enough free device storage for the model and runtime cache.
Model files are intentionally not committed to this repository and should not
be bundled into the APK.

## Requirements

- Android Studio or command-line Android SDK.
- Android SDK 36.
- JDK 17 or newer.
- An arm64-v8a Android device for model execution.
- USB debugging enabled for device installation and instrumentation tests.

Emulators are useful for UI checks, but they usually do not expose a usable
OpenCL GPU backend. Use a physical device for realistic LiteRT-LM validation.

## Quick Start

Clone the repository:

```bash
git clone https://github.com/William-zgx/pocketmind-android.git
cd pocketmind-android
```

If your Android SDK is not in the default location, set it before building:

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
```

Build a debug APK:

```bash
./gradlew :app:assembleDebug
```

Install it on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or let Gradle install it:

```bash
./gradlew :app:installDebug
```

## Testing

Run local verification:

```bash
scripts/doctor.sh
scripts/verify_local.sh
```

Run instrumented tests on one connected Android device with the helper script:

```bash
adb devices
scripts/install_and_test_device.sh
```

Convenience scripts are also available:

```bash
scripts/verify_local.sh
scripts/install_and_test_device.sh
```

`scripts/install_and_test_device.sh` leaves the debug app installed after a
successful run and preserves app data by default. Use
`CLEAN_DEVICE=1 scripts/install_and_test_device.sh` only when you intentionally
want a clean first-launch validation.

Avoid `./gradlew :app:connectedDebugAndroidTest` when you need to keep the app
installed on the device. The Android Gradle Plugin may clean up test packages
after instrumentation runs.

## Project Structure

```text
app/
  src/main/java/com/bytedance/zgx/pocketmind/
    MainActivity.kt          Activity wiring
    PocketMindViewModel.kt   Coordinates UI state and use cases
    ModelCatalog.kt          Model metadata and validation helpers
    action/                  Mobile action planning and execution boundary
    data/                    Model and session persistence
    download/                DownloadManager boundary
    memory/                  Local memory indexing and search
    orchestration/           Chat, memory, and action route selection
    runtime/                 LiteRT-LM runtime boundary
    ui/                      Compose chat, model, session, and message UI
  src/test/                 JVM unit tests
  src/androidTest/          Device smoke tests
docs/
  phone_acceptance.md       Manual device acceptance checklist
  validation_report.md      Recent validation notes
scripts/
  doctor.sh                 Local Android/JDK environment checker
  verify_local.sh           Local build/test helper
  install_and_test_device.sh Device install and smoke-test helper
```

## Development Notes

- Keep model binaries out of Git and out of the APK.
- Prefer a physical arm64-v8a device for runtime validation.
- Run unit tests after changing model rules, download logic, or formatting.
- Run connected tests after changing first-launch UI, model manager UI, or
  session navigation.
- Treat GPU fallback behavior as device-dependent; always keep the CPU path
  working.

## Contributing

Issues and pull requests are welcome. A good contribution should include:

- A short explanation of the user-facing problem.
- Focused code changes with unrelated refactors kept separate.
- Tests or a manual validation note for UI and device behavior.
- Screenshots or logs when reporting device-specific runtime issues.

Before opening a pull request, run:

```bash
scripts/verify_local.sh
```

The local verification gate runs unit tests, lint, debug/androidTest APK
assembly, release assembly, APK content checks, and a 75 MB release APK budget.

If the change affects real-device flows, also run:

```bash
scripts/install_and_test_device.sh
```

## License

This repository does not include a license file yet. Add a license before
publishing the project for broad external reuse.
