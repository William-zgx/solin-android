# PocketMind Android

PocketMind Android is a local-first Android chat app for running LiteRT-LM
models on device. It can download recommended `.litertlm` models, import a
model from local storage, and stream offline answers directly on the phone.

The project is built with Kotlin, Jetpack Compose, Android Gradle Plugin, and
Google AI Edge LiteRT-LM.

## Features

- On-device streaming chat with LiteRT-LM models.
- First-run setup for the recommended on-device chat model.
- Optional higher-quality chat model presets.
- Custom `.litertlm` download links and local file import.
- Model manager for switching downloaded or imported models.
- Configurable streaming remote chat backend for OpenAI-compatible `/v1/chat/completions` services.
- Lightweight local memory recall over previous conversation context.
- Experimental local mobile action planning with deterministic rule fallback and explicit confirmation.
- Schema-driven tool validation plus Agent run tracing for plan-confirm-observe execution, safety checks, bounded retry, and persistent audit events.
- JVM tool executor matrix tests cover registry validation, routing, permission
  failures, provider failures, structured error codes, and LocalOnly device
  context outputs.
- Minimal device context snapshots plus confirmed clipboard, calendar, contact, notification, foreground-app, and recent-file metadata reads for controlled context access.
- Confirmed Android runtime permission requests for tools that need calendar, contact, media, or notification access.
- Runtime permission denial is observed as a structured tool failure without
  executing or automatically retrying the tool.
- Confirmed external navigation for safe HTTPS deep links and package-level app launches.
- Versioned built-in skill manifests for email drafts, calendar drafts, map search, information lookup, device settings, local reminders, clipboard context, and system sharing, with manifest input schemas enforced before confirmation or execution.
- Skill-first routing for explicit clipboard context and clipboard-summary-share requests that do not need action-planner parameter extraction.
- A conservative clipboard-summary-share composite flow that keeps summarization local and asks again before opening the Android share sheet.
- AlarmManager-backed local reminder scheduling with a dedicated notification channel.
- Running background task review for still-scheduled reminders and periodic checks, including explicit cancellation.
- Recent tool audit review from the background task entry, limited to redacted event metadata.
- Android share-target and in-app attachment picker entries for shared text and
  bounded local `text/*` document excerpts; image, audio, video, PDF, Office,
  and binary attachments remain metadata-only, plus confirmed outbound system
  sharing for text.
- GPU backend with CPU fallback when GPU initialization is unavailable.
- Local chat sessions with create, switch, and delete actions.
- Stop button while a response is being generated.
- JVM tool and skill contract tests for registry schemas, skill manifest input
  schemas, executor routing, permission/failure paths, audit redaction, and
  skill gates.
- Instrumented smoke tests for first launch and model manager entry points.

## Screens And Model Flow

PocketMind opens directly into the chat surface. The top bar exposes model,
background task, and session management:

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

Remote chat uses the same conversation and action routing surface as local
chat, but private local context is stricter: memory recall, device context,
clipboard continuations, Android share-intent text, generated shared-input text
excerpts, and attachment metadata are not automatically sent to the configured
backend. HTTPS is required except for local debug hosts such as `localhost`,
`127.0.0.1`, and Android emulator `10.0.2.2`.
API keys are stored with Android Keystore-backed encryption and are removed
when the user clears the key field.

Memory recall is currently a lightweight on-device token/hash index over saved
sessions. Long-term memory now supports reviewing saved records, forgetting a
single record, and clearing explicit memory records. Its persisted scope is only
explicit preference and task-state records stored locally in Room; ordinary
conversation recall is still rebuilt from saved chat-session history, and the
dedicated embedding-model semantic memory is still pending. Mobile actions can
use the verified action model as an experimental planner; if it is missing or
does not produce a supported
`call:function {...}` draft, PocketMind falls back to deterministic local rules
and still requires explicit user confirmation before opening Android system
pages, drafts, HTTPS links, or package-level app launches. After confirmation,
Android execution returns a structured tool result that is written back to the
Agent run trace, audit log, and chat session.
Reminder requests such as “提醒我 15 分钟后喝水” become confirmed
`schedule_reminder` tool calls and are persisted before being handed to Android
AlarmManager. Pending reminders are restored after device reboot; reminders
that became due while the device was off are rescheduled with a short catch-up
delay. The running background tasks view lists still-scheduled tasks; canceling
one cancels the pending AlarmManager or WorkManager work, marks the local
record as `Cancelled`, and removes it from the running list.
The same entry also exposes recent persisted tool audit events for review. The
audit list shows only event time, event type, tool name, status, risk,
permission names, and a parameter-free generated summary; it does not show tool
arguments, prompts, remote responses, raw clipboard text, Authorization
headers, or API keys.
Clipboard requests such as “读取剪贴板” become confirmed `read_clipboard`
tool calls. After confirmation, clipboard text is used only for the immediate
local follow-up answer and is redacted from trace, audit, and persisted chat
tool-observation messages. Remote model mode does not automatically receive
clipboard content.
Requests such as “总结剪贴板并分享” use one constrained composite flow: after
the user confirms the clipboard read, PocketMind summarizes locally, then opens
a second confirmation for `share_text` with the generated summary. The share
sheet is never opened without this second confirmation.
Requests such as “分享这段文字...” open Android's system share panel through
`share_text`; destination selection stays with the user.
Shared text or attachments from other Android apps, as well as files selected
through the in-app attachment picker, are ingested as privacy-minimal
multimodal prompts: PocketMind records user-visible shared text, may produce
bounded local text excerpts for `text/*` documents, and keeps attachment
metadata for local processing. Binary, image, audio, video, PDF, Office, and
other non-text attachments remain metadata-only. Automatically generated
shared-input excerpts and metadata are marked `LocalOnly` and are not
auto-uploaded in remote mode.
Voice input uses Android system speech recognition and inserts the transcript
into the compose box only; sending remains explicit, and PocketMind does not
read audio files for this path.
Automatically generated shared-input and clipboard-derived messages are marked
local-only, filtered from remote history, and rejected as current prompts before
any remote model request is made.

Agent and skill module responsibilities are documented in
`docs/agent_core_modules.md`. The current code includes the Tool Registry,
single-run Agent planning, confirmation, tool observation, built-in one-step and
one conservative skill-first clipboard-summary-share composite flow,
conservative observe-after-success replanning for explicit next actions, a
gated skill-run executor, minimal device context
snapshots, safety policy, persistent tool audit, long-term memory controls,
local reminder scheduling, running background task review/cancellation,
confirmed clipboard/device-context reads, outbound text sharing, safe HTTPS
deep-link navigation, package-level app launches, Android share intent and
in-app picker text plus bounded `text/*` document excerpt ingestion, system
speech-recognition input, and restart restoration for the latest pending tool
confirmation without auto-execution. Broad screen understanding, generalized
typed run recovery, complete document parsing, OCR, Office/PDF parsing, and
media content understanding are tracked there as pending core modules.

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

Recommended downloads are pinned to immutable Hugging Face revisions and include
expected byte size plus SHA-256 metadata. See `docs/model_manifest.md`.

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

Recommended model URL provenance is checked only when explicitly requested:

```bash
VERIFY_MODEL_URLS=1 scripts/verify_local.sh
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
    audit/                   Tool audit event models and Room-backed sink
    background/              Alarm-backed reminders and scheduled task store
    data/                    Model and session persistence
    device/                  Minimal non-secret device context snapshots
    download/                DownloadManager boundary
    memory/                  Local memory indexing and search
    multimodal/              Shared/picked text excerpts and attachment metadata ingestion
    orchestration/           Chat, memory, and action route selection
    runtime/                 LiteRT-LM runtime boundary
    safety/                  Tool safety policy and confirmation decisions
    skill/                   Built-in skill manifests and skill-to-tool plans
    tool/                    Tool registry, schemas, results, and executor API
    ui/                      Compose chat, model, session, and message UI
  src/test/                 JVM unit tests
  src/androidTest/          Device smoke tests
docs/
  model_manifest.md        Pinned recommended model provenance and hashes
  agent_core_modules.md    Agent core module ownership and status
  phone_acceptance.md       Manual device acceptance checklist
  release_readiness.md      External distribution checklist
  validation_report.md      Recent validation notes
scripts/
  doctor.sh                 Local Android/JDK environment checker
  verify_local.sh           Local build/test helper
  install_and_test_device.sh Device install and smoke-test helper
```

## Development Notes

- Keep model binaries out of Git and out of the APK.
- Prefer a physical arm64-v8a device for runtime validation.
- Run unit tests after changing model rules, download logic, remote config, or formatting.
- Run connected tests after changing first-launch UI, model manager UI, or
  session navigation.
- Treat GPU fallback behavior as device-dependent; always keep the CPU path
  working.
- Chat sessions, model registry, and download records use Room. Non-secret
  settings use DataStore; API keys use Android Keystore-backed encrypted prefs.

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

PocketMind Android is distributed under the MIT License. See `LICENSE`.
