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
- Schema-driven tool validation plus Agent run tracing for plan-confirm-observe execution, safety checks, read-only bounded retry, and persistent audit events.
- Value-free Skill checkpoint persistence records only run/request/step ids,
  manifest identity, output keys, and private-output refs for pending Skill
  confirmations; raw continuation outputs and executable payload values stay
  out of Room and fail closed on mismatch. Restored Skill confirmations can use
  the checkpoint's completed-step frontier to satisfy later step dependencies
  without restoring any prior output values.
- JVM tool executor matrix tests cover registry validation, routing, permission
  failures, provider failures, structured error codes, and LocalOnly device
  context outputs.
- Minimal device context snapshots plus confirmed clipboard, calendar, contact,
  current-app notification summary, foreground-app, recent-file metadata, and
  recent-screenshot OCR, recent-image OCR, one-shot current-screen screenshot
  OCR, and a confirmed current-screen Accessibility text snapshot path for
  controlled context access.
- Confirmed Android runtime permission requests for tools that need calendar,
  contact, media, or reminder notification posting access.
- Runtime permission denial is observed as a structured tool failure without
  executing or automatically retrying the tool.
- Confirmed external navigation for safe HTTPS deep links, package-level app
  launches, and allowlisted app details settings, with user-recorded external
  outcome confirmation after launch-only Activity/share/draft openings.
- Versioned built-in skill manifests for email drafts, calendar drafts, map
  search, information lookup, device settings including Usage Access settings,
  local reminders, local periodic reminder checks, calendar
  availability, clipboard context, contact lookup, current foreground app
  context, current-app notification summaries, current-screen Accessibility
  text context, recent media metadata, HTTPS link navigation, app navigation,
  and system sharing, with manifest input schemas enforced before confirmation
  or execution.
- Skill-first routing for explicit clipboard context, current-app notification
  summary, current-screen Accessibility text, clipboard-summary-share, and
  current-screen-text-summary-share requests, plus explicit local periodic
  reminder check configuration, that do not need action-planner parameter
  extraction.
- Conservative clipboard-summary-share and current-screen-text-summary-share
  composite flows that keep summarization local and ask again before opening
  the Android share sheet.
- AlarmManager-backed local reminder scheduling with a dedicated notification channel.
- Running background task review for still-scheduled reminders and periodic checks, including explicit cancellation.
- Recent tool audit review from the background task entry, limited to redacted event metadata.
- Android share-target and in-app attachment picker entries for bounded shared text,
  bounded local `text/*` plus JSON/XML/YAML text-like application excerpts,
  RTF/PDF text-layer, PDF scanned-page OCR fallback, and Office Open XML
  excerpts, and bounded local OCR excerpts from user-provided `image/*`
  attachments; audio, video, legacy Office, and binary attachments remain
  metadata-only, plus confirmed outbound system sharing for text.
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
clipboard, recent-screenshot OCR, recent-image OCR, current-screen screenshot
OCR continuations, Android share-intent text, generated shared-input text
excerpts, current-screen Accessibility text snapshots, and attachment metadata
are not automatically sent to the configured backend. HTTPS is
required except for local debug hosts such as `localhost`, `127.0.0.1`, and
Android emulator `10.0.2.2`.
API keys are stored with Android Keystore-backed encryption and are removed
when the user clears the key field.

Memory recall is currently a lightweight on-device token/hash index over saved
sessions, with a conservative in-memory alias index for explicit
`Preference`/structured `TaskState` records. Alias terms help local hash recall
answer-style preferences and active background task state without changing Room
record text or memory context output. Long-term memory now supports reviewing
saved records, forgetting a single record, and clearing explicit memory records.
Its persisted scope is only explicit preference and task-state records stored
locally in Room; ordinary conversation recall is still rebuilt from saved
chat-session history. Forgotten auto-managed task-state records stay suppressed
so background task refreshes do not recreate them in long-term memory.
`记住：...` / `remember ...` is handled as a local memory-control command: it
updates local long-term preferences, records only `LocalOnly` control/status
messages if visible in the session, and is not sent to a remote model as
ordinary chat. Explicit response-length and response-language preferences replace
older conflicting preferences instead of accumulating contradictory records.
`忘记：...` / `forget ...` can delete the matching explicit preference through
the same local-only command path without invoking the chat/action router or a
remote model. For answer-style preferences, family targets such as "回答语言偏好"
or "answer length preference" remove the matching response-language or
response-length preference without deleting unrelated memories.
The semantic-memory boundary can verify a downloaded MemoryEmbedding asset and
production wires a fail-closed LiteRT embedding runtime factory. The current
LiteRT-LM artifact exposes chat/generation APIs but no public embedding vector
API, so installing the memory asset reports a runtime load failure and recall
falls back to the lightweight index until a real embedding runtime reports
active. Mobile actions can use the
verified action model as an experimental planner; if it is missing or does not
produce a supported
`call:function {...}` draft, PocketMind falls back to deterministic local rules
and still requires explicit user confirmation before opening Android system
pages, drafts, HTTPS links, or package-level app launches. When the verified
action model is used for observation replanning, it can only propose one next
supported tool draft after a successful observed tool result; unsupported or
malformed drafts fail closed, and every proposed tool still requires explicit
confirmation. After confirmation,
Android execution returns a structured tool result that is written back to the
Agent run trace, audit log, and chat session.
Reminder requests such as “提醒我 15 分钟后喝水” become confirmed
`schedule_reminder` tool calls and are persisted before being handed to Android
AlarmManager. Pending reminders are restored after device reboot; reminders
that became due while the device was off are rescheduled with a short catch-up
delay. Alarm delivery re-checks the local task record and only posts
still-`Scheduled` reminders, using the stored title/body instead of trusting
alarm extras. The running background tasks view lists still-scheduled tasks;
canceling one cancels the pending AlarmManager or WorkManager work, marks the
local record as `Cancelled`, and removes it from the running list.
Requests such as “取消提醒 task-123” use the skill-first path and become
confirmed `cancel_reminder` tool calls only when the request explicitly
mentions a reminder and a `task-*` id. Requests without a task id, API or
implementation discussions, negated commands, and non-reminder cancellations
are not routed to the tool.
Requests such as “开启周期检查，每 2 小时” or “关闭周期检查” become confirmed
`configure_periodic_check` tool calls. The tool only enables or disables the
local reminder patrol policy through WorkManager; it does not run background
chat, read screens, scan files, or execute arbitrary periodic tasks.
Requests such as “查看后台任务” or “周期检查状态” become confirmed
`query_background_tasks` tool calls. The tool only reads the local scheduled
task store and periodic-check policy; it does not schedule, cancel, or
reconfigure background work, and it omits reminder bodies from the local-only
result.
The same entry also exposes recent persisted tool audit events for review. The
audit list shows only event time, event type, tool name, status, risk,
permission names, and a parameter-free generated summary; it does not show tool
arguments, prompts, remote responses, raw clipboard text, Authorization
headers, or API keys.
Clipboard requests such as “读取剪贴板” become confirmed `read_clipboard`
tool calls. After confirmation, clipboard text is used only for the immediate
local follow-up answer and is redacted from trace, audit, and persisted chat
tool-observation messages. Both successful and failed clipboard tool results
carry `privacy=LocalOnly` and `requiresLocalModel=true` metadata, so remote
model mode does not automatically receive clipboard content or clipboard read
observations.
Tools that declare private outputs use the same LocalOnly metadata for
failed, rejected, and cancelled results; declared private output fields are
removed from those non-success tool results, unknown data keys are dropped,
and only allowlisted permission-recovery metadata is kept before the result
can enter trace, audit, or follow-up model routing.
Explicit requests such as “识别最近 1 张截图文字” or “OCR 最近截图” use the
skill-first path and become confirmed `read_recent_screenshot_ocr` tool calls.
Requests to OCR multiple screenshots are rejected. After confirmation,
PocketMind reads only the most recent screenshot through Android media
permissions, extracts a bounded local OCR text excerpt, and does not persist
the image URI, path, raw pixels, or OCR text in trace/audit. The permission
boundary is `READ_MEDIA_IMAGES` on Android 13+ or legacy storage read permission
on older Android versions; this is not current-screen capture, visual
understanding, arbitrary media OCR, or multi-screenshot OCR. The OCR excerpt
may preserve recognized block and line order from ML Kit, but it does not add
coordinates, labels, captions, or image semantics. Remote model mode
stops before automatic continuation and asks the user to switch local or
manually provide content they are willing to upload.
Explicit requests such as “识别最近图片文字” use the skill-first path and become
confirmed `read_recent_image_ocr` tool calls. After confirmation, PocketMind
scans up to 3 recent images through Android media permissions, extracts the
first bounded local OCR text excerpt, and uses the same LocalOnly, trace/audit
redaction, and remote-mode protection as screenshot OCR. Plain “最近图片”
requests use a skill-first, metadata-only `query_recent_files(kind="images")`
path and do not read image pixels or OCR text. Requests for all/many/more than
3 images, implementation/API/permission discussion, negated reads, or
visual/semantic image understanding such as describing what is in an image are
rejected from image OCR routing.
Requests such as “最近通知” become confirmed `query_recent_notifications`
tool calls. The tool reads only PocketMind/current-app active notification
metadata, defaults to 5 entries, caps requests at 20, and returns LocalOnly
minimal summaries without notification body text, extras, unread state,
notification shade contents, other apps, or Notification Listener data. This
query does not request Android notification runtime permission; if app
notifications are disabled, it returns a structured permission-denied result.
Requests such as “查联系人 Alice” become confirmed `query_contacts` tool
calls. The tool requests `READ_CONTACTS` only after confirmation, searches by
the explicit query, defaults to 5 entries, caps requests at 20, and returns
LocalOnly minimal `name`/`phone` summaries. It does not return email, avatar,
address, notes, contact IDs, or full address-book exports; the contact query and
result JSON are redacted from trace and audit summaries.
Requests such as “新建联系人 Alice” use the skill-first path and become
confirmed `create_contact_draft` tool calls. The tool opens the system contacts
insert page and pre-fills only draft `name`/`email`/`phone` fields; it does not
read the address book, request `READ_CONTACTS`, save the contact, or submit the
system form for the user.
Requests such as “查忙闲 2026-06-01T09:00:00Z 到 2026-06-01T10:00:00Z”
become confirmed `query_calendar_availability` tool calls. The tool requires
`READ_CALENDAR` after confirmation, accepts only explicit timezone-qualified
ISO start/end windows, and returns LocalOnly busy/free blocks without event
titles, locations, attendees, notes, or calendar IDs.
Requests such as “读取当前屏幕文字” become confirmed skill-first
`read_current_screen_text` tool calls. After confirmation, the tool may read
only the current Accessibility text-node snapshot exposed by Android
accessibility services, mark the result `LocalOnly`, and use it only for
immediate local continuation. It is not screenshot capture, OCR, pixel
analysis, or semantic screen understanding; raw `screenText` must not enter
trace, audit, persisted tool-observation messages, or remote model requests.
Accessibility access is modeled as special access, not an Android runtime
permission. Ambiguous screen-understanding requests such as “总结当前屏幕内容”,
“summarize current screen content”, “summarize this page”, or “describe current
screen” do not trigger this tool unless the user explicitly asks for current
screen text / visible text / Accessibility text.
Explicit requests such as “OCR 当前屏幕截图文字” become confirmed
`capture_current_screenshot_ocr` tool calls. After the normal tool confirmation,
PocketMind asks Android for a foreground MediaProjection consent result, consumes
that token once in memory, captures one current-screen frame, runs local ML Kit
OCR, and returns only bounded OCR text plus included/truncated flags. It does
not persist pixels, URI/path data, file names, window titles, coordinates, or
visual descriptions, and it does not perform semantic screen understanding.
Cancelling the MediaProjection consent is observed as a structured LocalOnly
tool failure.
Requests such as “总结当前屏幕文字并分享” use one constrained composite flow:
after the user confirms the current-screen Accessibility text read, PocketMind
summarizes locally, then opens a second confirmation for `share_text` with the
generated summary. The raw `screenText` cannot be bound directly to
`share_text`, and a restarted app fails closed at the payload-bearing share
confirmation instead of restoring or sending the summary.
Requests such as “总结剪贴板并分享” use one constrained composite flow: after
the user confirms the clipboard read, PocketMind summarizes locally, then opens
a second confirmation for `share_text` with the generated summary. The share
sheet is never opened without this second confirmation.
Requests such as “分享这段文字...” open Android's system share panel through
`share_text`; destination selection stays with the user.
Shared text or attachments from other Android apps, as well as files selected
through the in-app attachment picker, are ingested as privacy-minimal
multimodal prompts: PocketMind records bounded user-visible shared text, may produce
bounded local text excerpts for `text/*` plus JSON/XML/YAML text-like
application documents, bounded local text-layer excerpts for user-provided RTF,
PDF text layers, and `.docx` / `.xlsx` / `.pptx` files, may produce bounded
local OCR excerpts for user-provided `image/*` attachments, and may fall back to
bounded scanned-page OCR for user-provided PDFs with no readable text layer.
It keeps attachment metadata for local processing. Binary, audio, video, legacy
Office, and other unsupported attachments remain metadata-only. Automatically
generated shared-input excerpts and metadata are
marked `LocalOnly`; remote mode now protects at the reader boundary and does not
read shared text values, attachment metadata, file streams, text excerpts, or OCR
before showing a local privacy notice.
Voice input uses Android system speech recognition and inserts the transcript
into the compose box only; sending remains explicit, and PocketMind does not
read audio files for this path.
Automatically generated shared-input and clipboard-derived messages are marked
local-only, filtered from remote history, and rejected as current prompts before
any remote model request is made.

Agent and skill module responsibilities are documented in
`docs/agent_core_modules.md`. The current code includes the Tool Registry,
single-run Agent planning, confirmation, tool observation, built-in one-step,
skill-first information lookup/recent-media-metadata/calendar-availability/
contact-lookup/current-app-notification-summary/foreground-app/
HTTPS-link-navigation/device-settings/map/email/calendar/text sharing/local
periodic reminder checks/background-task queries, and one conservative
clipboard-summary-share
composite flow,
conservative observe-after-success replanning for explicit next actions plus
bounded model-backed next-tool drafts behind validation and confirmation, a
gated skill-run executor, minimal device context
snapshots, safety policy, persistent tool audit, long-term memory controls,
local reminder scheduling, confirmed periodic reminder-check configuration,
running background task review/cancellation/read-only Agent queries,
run-level Agent cancellation and hard budgets before additional tool
confirmations/retries/model continuations,
confirmed clipboard/device-context reads, outbound text sharing, safe HTTPS
deep-link navigation, package-level app launches, Android share intent and
in-app picker text plus bounded `text/*` and JSON/XML/YAML document excerpt
ingestion, bounded RTF/PDF text-layer, PDF scanned-page OCR fallback, and
Office Open XML excerpts,
system speech-recognition input,
confirmed recent screenshot/image OCR, confirmed one-shot current-screen
screenshot OCR, and restart restoration for the latest pending tool confirmation
without auto-execution, value-free completed-step frontier recovery for restored
Skill confirmations, plus confirmed current-screen Accessibility text snapshot
reads, current-screen text summary sharing, and user-confirmed external Activity
outcome recording before completion-dependent next-tool planning.
Broad semantic screen understanding, arbitrary argument-bearing typed run recovery, complete
document parsing, current-screen semantic understanding, continuous screen
capture, PDF layout parsing, legacy Office parsing, full rich-text fidelity,
image semantic understanding, arbitrary-media OCR beyond user-provided PDF/image
attachments and confirmed recent/current-screen image reads, and media content
understanding are tracked there as pending core modules.

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

The memory embedding model is currently a downloadable/verifiable asset for
future semantic recall; production probes it through a fail-closed factory, but
the current LiteRT-LM SDK surface cannot return embedding vectors, so retrieval
continues to use the lightweight index.
Use Wi-Fi and keep enough free device storage for the model and runtime cache.
Model files are intentionally not committed to this repository and should not
be bundled into the APK.

Recommended downloads are pinned to immutable Hugging Face revisions and include
expected byte size plus SHA-256 metadata. See `docs/model_manifest.md`.

## Requirements

Local verification:

- Android SDK 36.
- JDK 17 or newer.

Device or emulator validation:

- Android Studio or command-line Android SDK with platform-tools/adb.
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

`scripts/doctor.sh` checks the local JVM/Android SDK/Gradle toolchain by
default and does not require `adb`. Device or emulator validation uses the
stricter device mode, which verifies the SDK `adb` binary but does not prove a
device is connected:

```bash
scripts/doctor.sh --device
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

Run emulator-only validation without accidentally selecting a physical device:

```bash
ANDROID_SERIAL=emulator-5554 scripts/verify_emulator.sh
```

The emulator helper can also start an AVD before running the shared install and
instrumentation flow:

```bash
AVD_NAME=focus_agent_api36_arm64 scripts/verify_emulator.sh
```

For release-candidate style emulator regression, use the stricter artifact
gate. It forces `CLEAN_DEVICE=1`, runs the emulator helper, verifies both
machine-readable reports, and fails if the runner reports fewer AndroidTest
cases than the current `app/src/androidTest` source count:

```bash
AVD_NAME=focus_agent_api36_arm64 scripts/regression_emulator.sh
```

When more than one authorized device is connected, select the target explicitly:

```bash
ANDROID_SERIAL=emulator-5554 scripts/install_and_test_device.sh
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
If there is no authorized device, or if multiple authorized devices are
connected without `ANDROID_SERIAL`, the script exits before Gradle build, APK
install, or instrumentation. Record the instrumentation runner's reported test
count together with the device serial/API/ABI in full regression reports; the
device report records this as `instrumentation_test_count`.
`install_and_test_device.sh` writes a machine-readable
`device-verification.properties` report, and `verify_emulator.sh` writes an
`emulator-verification.properties` report plus the nested device report under
`build/verification/` by default; release records should link those artifacts.
For complete emulator regression, the artifact of record is the
`regression-emulator.properties` file written by `scripts/regression_emulator.sh`;
record the regression as passed only when that file contains `status=passed`.

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
  model_manifest.md        Pinned recommended model provenance, hashes, and license checklist
  agent_core_modules.md    Agent core module ownership and status
  phone_acceptance.md       Manual device acceptance checklist
  privacy_notice.md        Local/remote privacy boundary summary for release review
  release_checklist.md     Manual release candidate checklist
  release_readiness.md      External distribution checklist
  validation_report.md      Recent validation notes
scripts/
  doctor.sh                 Local Android/JDK environment checker
  verify_local.sh           Local build/test helper
  verify_emulator.sh        Emulator-only install and smoke-test helper
  regression_emulator.sh    Emulator regression artifact gate
  install_and_test_device.sh Device install and smoke-test helper
  test_validation_scripts.sh Shell preflight regression tests
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

PocketMind Android app code is distributed under the MIT License. See
`LICENSE`. Recommended model downloads are third-party artifacts governed by
their upstream licenses; see `docs/model_manifest.md` for provenance and the
release license checklist.
