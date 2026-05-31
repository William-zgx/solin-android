# PocketMind Agent Core Modules

This document is the module map for the end-side Agent architecture. It records
what each core module owns, what it must not own, and the current implementation
status.

## Tool Layer

Code:

- `app/src/main/java/com/bytedance/zgx/pocketmind/tool/`
- `app/src/main/java/com/bytedance/zgx/pocketmind/action/ActionExecutor.kt`

Responsibilities:

- Declare every device capability as a `ToolSpec`.
- Validate `ToolRequest` names and arguments before execution.
- Declare permissions, risk level, and confirmation policy.
- Return structured `ToolResult` values with `ToolError` codes.

Non-responsibilities:

- Tool code must not decide conversation flow.
- Tool code must not bypass user confirmation for medium or higher risk work.

Current status:

- Implemented registry, JSON schema strings, argument validation, permission
  declarations, risk levels, confirmation policy, structured result helpers,
  and Android Intent execution results.
- Tool argument validation is now driven by each tool's JSON schema for
  required properties, closed argument sets, `minLength`, and regex `pattern`
  checks.
- Current tools cover Wi-Fi settings, flashlight settings, map search, web
  search, email draft, calendar draft, contact draft, local reminders,
  confirmed clipboard text reads, outbound system sharing for text, current
  foreground app summaries, contact lookup, recent notification summaries,
  calendar availability, and recent file metadata summaries.
- Tools that may require runtime permissions declare that requirement in
  `ToolSpec`. The Activity boundary maps pending tool confirmations to Android
  runtime permission requests before handing the same confirmation back to the
  ViewModel for execution.

Tests:

- `ToolRegistryTest`
- `AgentLoopRuntimeTest.confirmedToolResultIsObservedAndCompletesRun`

## Agent Loop

Code:

- `app/src/main/java/com/bytedance/zgx/pocketmind/orchestration/`

Responsibilities:

- Own the run state machine.
- Load local memory context.
- Plan chat, tool, or skill-backed tool work.
- Request user confirmation before tool execution.
- Observe `ToolResult` values after execution and append trace steps.

Non-responsibilities:

- The loop does not start Android activities directly.
- The loop does not store secret or full remote error payloads in trace.

Current status:

- Implemented single-run planning for chat and tool requests.
- Implemented confirmation state and post-confirmation observation.
- Confirmed clipboard observations can now create a follow-up model prompt so
  the assistant can answer from the just-read tool result instead of stopping at
  a generic "tool succeeded" message.
- `clipboard_summary_share_skill` now has a constrained app-level continuation:
  after the confirmed clipboard read and local model summary, the loop binds the
  model output into a `share_text` request and returns to
  `AwaitingUserConfirmation`. It does not execute the share step directly.
- Retryable tool failures now schedule one bounded retry on the already
  confirmed request, record a `ToolRetryScheduled` trace/audit event, and only
  fail the run after the retry budget is exhausted.
- Tool observation now produces an explicit `AgentObservationDecision`:
  complete, continue with model, retry tool, fail, or cancel. The decision is
  recorded in trace without storing private continuation prompts.
- Successful observations can now call `AgentObservationReplanner` to produce a
  next tool plan. The default production strategy is conservative: it only
  replans one explicit next action after sequence words such as "然后" / "then",
  and only when the current run has planned exactly one tool so far.
- Replanned tools are validated, safety checked, audited, traced, and returned
  to `AwaitingUserConfirmation` instead of being executed directly.
- Confirmation and observation are now bound to the current pending/confirmed
  request id, so stale request ids from earlier steps cannot advance the run.
- Implemented trace steps for skill planning, user confirmation, tool
  observation, assistant response, rejection, cancellation, and failure.
- General model-driven next-step planning, generalized multi-step skill UI
  orchestration beyond the clipboard summary share flow, and persisted run
  recovery are still pending.

Tests:

- `AgentLoopRuntimeTest`
- `AssistantOrchestratorTest`
- `AgentLoopRuntimeCompatibilityTest`
- `AgentLoopRuntimeTest.successfulObservationCanPlanNextToolAndRequestConfirmationAgain`
- `AgentLoopRuntimeTest.replannedToolCannotReuseExistingRequestId`
- `AgentLoopRuntimeTest.clipboardSummarySharePlansShareAfterLocalModelResult`
- `AgentLoopRuntimeTest.compositeSkillIgnoresOldRequestIdsAfterShareIsPendingOrExecuting`
- `AssistantOrchestratorTest.defaultSequentialReplannerPlansExplicitNextActionAfterObservation`
- `AssistantOrchestratorTest.clipboardSummaryShareAdvancesFromModelOutputToShareConfirmation`

## Skill Framework

Code:

- `app/src/main/java/com/bytedance/zgx/pocketmind/skill/`

Responsibilities:

- Declare reusable, versioned task skills.
- Map skill steps to Tool Registry requests.
- Keep task composition separate from Android execution.

Non-responsibilities:

- Skills must not call `Context.startActivity`.
- Skills must not define private tool names outside the registry.

Current status:

- Implemented `SkillManifest`, `SkillRequest`, `SkillPlan`, `SkillStep`, and
  `SkillRuntime`.
- Implemented built-in manifests for email drafts, calendar drafts, map search,
  information lookup, device settings, background reminders, clipboard context,
  and system text sharing.
- Implemented a minimal declarative composition model for ordered skill steps:
  tool steps can declare stable ids, dependencies, and argument bindings; model
  transform steps can consume prior tool outputs and expose named outputs.
- Added `clipboard_summary_share_skill` as the first composite skill contract:
  it reads clipboard text after confirmation, keeps the transform local, then
  binds the generated summary into the system share tool.
- This composite skill is now wired into the app loop for one conservative
  flow: read clipboard, summarize locally, request explicit confirmation for
  the share sheet. Other composite skills still need their own UI orchestration.
- Added `SkillRunExecutor` as the first executable multi-step skill runner. It
  validates the plan, resolves step output bindings, enforces a step limit, and
  separates private binding outputs from the public result/trace.
- `SkillRunExecutor` now gates every tool step through registry validation and
  safety policy before execution. Steps that require user confirmation return
  `AwaitingConfirmation` instead of calling the injected `ToolExecutor`
  directly.
- General app-level UI orchestration for arbitrary multi-confirmation skill
  runs, richer skill cancellation state, and persisted skill runs are still
  pending.

Tests:

- `BuiltInSkillRuntimeTest`
- `BuiltInSkillRuntimeTest.plansClipboardSummaryShareAsOrderedCompositeSkill`
- `BuiltInSkillRuntimeTest.routesClipboardSummaryShareInputToCompositePlan`
- `BuiltInSkillRuntimeTest.validateStructureRejectsUnorderedOrInvalidCompositePlan`
- `SkillRunExecutorTest`
- `ToolSchemaContractTest`
- `AgentLoopRuntimeTest.wifiActionInputRequestsConfirmationBeforeExecution`

## Device Context

Code:

- `app/src/main/java/com/bytedance/zgx/pocketmind/device/`
- Current context sources: `MemoryRepository`, `SessionRepository`, model and
  device capability state in `ChatUiState`.

Responsibilities:

- Provide controlled, minimal context to the loop and planner.
- Keep private device data out of remote prompts unless explicitly allowed.

Current status:

- Implemented local memory hits, recent session history, and a minimal
  `DeviceContextSnapshot` containing non-secret state such as inference mode,
  installed capability classes, memory toggle, storage estimate, active-session
  presence, and pending confirmation state.
- The Agent loop records this context in trace and may include it in the model
  prompt behind an instruction to use it only when relevant.
- Implemented a confirmed `read_clipboard` tool for current foreground text
  clipboard access. Raw text is used only to build the immediate in-memory
  continuation prompt after user confirmation; trace steps, audit events, and
  persisted tool-observation messages store sanitized summaries instead of the
  raw text.
- Remote model mode does not automatically receive clipboard continuation
  prompts; it asks the user to switch local or manually provide text they agree
  to upload.
- Remote model mode also strips memory hits and device context from ordinary
  chat prompts. Android share-intent text and attachment metadata are held back
  from automatic remote generation unless the user manually provides the content
  they are willing to upload.
- Chat history now carries `MessagePrivacy`; messages marked `LocalOnly` are
  persisted for the local conversation but are filtered from remote history, and
  a `LocalOnly` current prompt is rejected before any remote request is made.
- ViewModel dependencies now sit behind narrow ports for model state, sessions,
  generation settings, remote settings, downloads, memory, tool execution, and
  assistant routing so privacy behavior can be regression-tested without
  Android-bound concrete repositories.
- Implemented confirmed, read-only device context tools for current foreground
  app metadata, current-app notification summaries, contact lookup, calendar
  busy/free windows, and recent file metadata. Recent file reads return
  `LocalOnly` metadata only: file name, MIME type, coarse kind, size, and last
  modified time. The tool does not return file paths, URIs, or file contents.
- Broad screen understanding and direct file-content readers are still pending.

## Execution Boundary

Code:

- `ActionExecutor`
- `PendingAgentConfirmation`
- `PocketMindViewModel.confirmAgentConfirmation`

Responsibilities:

- Convert confirmed `ToolRequest` values into Android system intents.
- Return execution success or failure as `ToolResult`.
- Surface execution results to the UI and Agent trace.

Current status:

- Implemented Intent-based draft/navigation execution, AlarmManager-backed
  reminder scheduling, and observation.
- User cancellation of a pending tool request now closes the run as
  `Cancelled` and records trace/audit events without executing Android intents.
- Implemented runtime notification permission request for reminder confirmation
  and generalized runtime permission requests for confirmed calendar, contact,
  recent-media-file, and reminder tools. Permission prompts are issued only
  after the user confirms the Agent tool request; denial returns through the
  normal structured tool result path.
- Android share-target ingestion for text/images/audio/video/PDF metadata is
  implemented.
- Implemented outbound `share_text` as a confirmed tool that opens Android's
  system share panel. Success means the chooser was opened; the app cannot know
  whether the user completed sharing in the destination app.
- Deep links, app-specific intents, broad permission flows, and
  result-confirmation callbacks are pending.

## Safety And Audit

Code:

- `app/src/main/java/com/bytedance/zgx/pocketmind/safety/`
- `app/src/main/java/com/bytedance/zgx/pocketmind/audit/`
- `ToolSpec.riskLevel`
- `ToolSpec.confirmationPolicy`
- `ToolSpec.permissions`
- `AgentTraceStore`
- `tool_audit_events` Room table

Responsibilities:

- Make capability risk explicit in code.
- Keep a trace of planned and executed work.
- Minimize stored private data.

Current status:

- Implemented risk and permission declarations, `SafetyPolicy`, in-memory run
  trace, and persistent audit events for tool planning, confirmation request,
  user confirmation, user cancellation, rejection, and observation.
- Audit events store request metadata, status, risk, permission names, and a
  short sanitized summary. They intentionally do not store tool arguments,
  prompts, remote responses, or secrets.
- Audit summary sanitization removes key-like tokens, bearer credentials, and
  email addresses before truncation. The in-memory audit sink stores the same
  redacted copy as the Room-backed repository so tests cannot accidentally
  depend on raw private data.
- The first local-only privacy boundary is implemented for shared input,
  clipboard-derived continuations, and remote chat history. Broader taint
  propagation, undo/rollback, and richer per-tool privacy policies are pending.

Tests:

- `SafetyPolicyTest`
- `ToolAuditEventTest`
- `SessionRepositoryTest`
- `AgentLoopRuntimeTest.confirmedToolResultIsObservedAndCompletesRun`

## Memory

Code:

- `app/src/main/java/com/bytedance/zgx/pocketmind/memory/`
- `memory_records` Room table

Responsibilities:

- Recall relevant local context when memory is enabled.
- Avoid injecting unrelated private content.
- Persist explicit user preferences and task-state records until the user
  forgets or clears them.

Current status:

- Implemented lightweight token/hash memory over saved sessions.
- Added explicit long-term memory controls for preference records, task-state
  records, forgetting individual records, and clearing the index.
- Explicit preference and task-state records are now persisted in Room and
  reloaded during memory rebuild; ordinary conversation-derived memory remains
  rebuilt from saved chat messages instead of duplicated into the memory table.
- `rebuild` extracts simple explicit user preference statements such as
  `记住：...` / `remember ...`.
- Dedicated embedding-model semantic memory and UI controls for reviewing or
  forgetting saved records are pending.

Tests:

- `MemoryRepositoryTest`

## Background Tasks

Code:

- `app/src/main/java/com/bytedance/zgx/pocketmind/background/`
- `scheduled_tasks` Room table
- `ReminderAlarmReceiver`

Responsibilities:

- Persist scheduled Agent tasks before handing them to Android.
- Use Android system scheduling instead of keeping foreground coroutines alive.
- Deliver reminder notifications through a dedicated notification channel.
- Keep background task scheduling separate from conversation planning.

Current status:

- Implemented `schedule_reminder` as a confirmed tool and `reminder_skill` as a
  built-in one-step skill.
- Implemented `ScheduledTaskRepository`, `AndroidBackgroundTaskScheduler`, a
  reminder notification channel, `ReminderAlarmReceiver`, and
  `ReminderBootReceiver`.
- The scheduler uses `AlarmManager.setAndAllowWhileIdle`; triggered reminders
  post a local notification when notification permission is available and update
  the task status to `Delivered` or `Failed`.
- `ReminderBootReceiver` listens for `BOOT_COMPLETED` and asks
  `ReminderRescheduler` to restore every still-`Scheduled` reminder after the
  system clears alarms on reboot. Past-due reminders are rescheduled with a
  short catch-up delay instead of being silently dropped.
- If an alarm cannot be scheduled or restored, the task is marked `Failed` so
  repository state does not claim a reminder is still pending when Android has
  no alarm registered.
- Reminder confirmation requests notification permission before execution on
  Android versions that require it. If permission is still unavailable,
  execution fails with a structured `PermissionDenied` tool result.
- Periodic check policies, WorkManager-backed recurring jobs, runtime
  task review UI are pending.

Tests:

- `ScheduledTaskRepositoryTest`
- `ActionExecutorTest`
- `ActionPlannerTest.infersReminderDraftWithDelayMinutes`
- `ToolRegistryTest.validatesReminderDelayMinutesAsPositiveInteger`
- `BuiltInSkillRuntimeTest.plansReminderAsBackgroundToolStep`

## Multimodal Inputs

Code:

- `app/src/main/java/com/bytedance/zgx/pocketmind/multimodal/`
- `MainActivity` share intent handling

Responsibilities:

- Accept user-initiated shared text and attachment metadata from Android.
- Classify attachments by MIME type without reading file content by default.
- Keep multimodal source handling separate from chat generation and tools.

Current status:

- Implemented Android share-target entry for `ACTION_SEND` and
  `ACTION_SEND_MULTIPLE`.
- Implemented privacy-minimal `SharedInput` prompts for text plus attachment
  metadata such as kind, MIME type, display name, and byte size.
- Shared-input prompts are marked `LocalOnly` when generated automatically, so
  local processing can continue without later leaking the prompt or local
  assistant response into remote chat history.
- The app does not read image, audio, video, PDF, or document bytes yet; it only
  records metadata in the user-visible prompt. Voice capture, screenshot
  capture, gallery picker, OCR, image understanding, and document parsing are
  pending.

Tests:

- `SharedInputTest`
- `PocketMindViewModelTest.localSharedInputDoesNotEnterLaterRemoteHistory`
- `PocketMindViewModelTest.remoteModeRejectsLocalOnlyPromptBeforeCallingRemoteRuntime`

## Regression Strategy

Local verification:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Emulator regression:

```bash
adb devices
./gradlew :app:connectedDebugAndroidTest
```

Full device validation remains required for LiteRT-LM model execution because
emulators usually do not expose the same GPU backend behavior as physical
arm64-v8a devices.
