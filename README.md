# PocketMind Android

PocketMind Android is a local-first Android chat app for running LiteRT-LM
models on device. It can download recommended `.litertlm` models, import a
model from local storage, and stream offline answers directly on the phone.

The project is built with Kotlin, Jetpack Compose, Android Gradle Plugin, and
Google AI Edge LiteRT-LM.

## Features

- On-device streaming chat with LiteRT-LM models.
- Built-in Gemma 4 E2B and Gemma 4 E4B model presets.
- Custom `.litertlm` download links and local file import.
- Model manager for switching downloaded or imported models.
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
3. Load the selected model.
4. Chat offline once the app reports that the model is ready.

Model selection itself does not immediately reload the runtime. This keeps the
model manager responsive while browsing models or switching CPU/GPU; use
**Load model** when you are ready to initialize the selected model.

## Recommended Models

The app includes presets for LiteRT Community Gemma models hosted on Hugging
Face:

- [Gemma 4 E2B Instruct LiteRT-LM](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
- [Gemma 4 E4B Instruct LiteRT-LM](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm)

The downloaded files are large:

| Model | File | Size |
| --- | --- | --- |
| Gemma 4 E2B | `gemma-4-E2B-it.litertlm` | about 2.59 GB |
| Gemma 4 E4B | `gemma-4-E4B-it.litertlm` | about 3.66 GB |

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

Run instrumented tests on one connected Android device:

```bash
adb devices
./gradlew :app:connectedDebugAndroidTest
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

## Project Structure

```text
app/
  src/main/java/com/bytedance/zgx/gemmalocalqa/
    MainActivity.kt          Activity wiring
    GemmaChatViewModel.kt    Coordinates UI state and use cases
    GemmaModelRules.kt       Model metadata and validation helpers
    data/                    Model and session persistence
    download/                DownloadManager boundary
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
./gradlew :app:connectedDebugAndroidTest
```

## License

This repository does not include a license file yet. Add a license before
publishing the project for broad external reuse.
