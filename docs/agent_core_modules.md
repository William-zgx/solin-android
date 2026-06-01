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
- Declare output schemas for successful `ToolResult` data.
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
- `ToolSpec` now includes an `outputSchemaJson` contract for successful
  `ToolResult.data`. `ValidatingToolExecutor` validates successful delegate
  results after execution; rejected, failed, and cancelled results remain
  structured error/cancellation states and are not required to satisfy the
  success schema. Output schema failures are wrapped as non-retryable
  `InvalidResult` failures carrying only tool context, so malformed success
  data cannot flow into Agent observation, trace summaries, audit display, or
  Skill output binding as a successful observation.
- Current tools cover Wi-Fi settings, flashlight settings, map search, web
  search, email draft, calendar draft, contact draft, local reminders,
  confirmed clipboard text reads, outbound system sharing for text, current
  foreground app summaries, contact lookup, recent notification summaries,
  calendar availability, recent file metadata summaries, confirmed recent
  screenshot OCR, confirmed recent image OCR, confirmed current-screen
  Accessibility text snapshots, safe HTTPS deep-link navigation, package-level
  app launches, and allowlisted app deep targets.
- `read_current_screen_text` belongs to this
  tool boundary as a confirmed, `LocalOnly` device-context read. It may expose
  only a bounded current Accessibility text-node snapshot, not screenshots, OCR
  output, pixels, or semantic screen understanding.
- Tools that may require runtime permissions declare that requirement in
  `ToolSpec` only when the current runtime permission policy can request a
  concrete Android manifest permission. The Activity boundary maps pending tool
  confirmations to Android runtime permission requests before handing the same
  confirmation back to the ViewModel for execution.
- Special app access requirements are declared separately from Android runtime
  permissions. Usage Access (`PACKAGE_USAGE_STATS`) is modeled as a
  settings-granted capability, not a dangerous runtime permission.

Tests:

- `ToolRegistryTest`
- `ToolSchemaContractTest`
- `CalendarAvailabilityToolExecutorTest`
- `DeviceContextToolExecutorTest`
- `RoutingAndValidatingToolExecutorTest`
- `ActionExecutorTest`
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
- Implemented conservative Skill-first routing for explicit clipboard context,
  current-screen Accessibility text, and clipboard-summary-share requests.
  These requests can enter `AwaitingUserConfirmation` without first being
  classified by the action planner, because their first tool step does not
  require model-driven parameter extraction.
- Implemented confirmation state and post-confirmation observation.
- Confirmed clipboard observations can now create a follow-up model prompt so
  the assistant can answer from the just-read tool result instead of stopping at
  a generic "tool succeeded" message.
- Model-step Skills now have an app-level continuation path: after a confirmed
  tool step, the loop can build the next model-step prompt from that tool
  result, then bind the model output into the next `ToolStep` and return to
  `AwaitingUserConfirmation`. It does not execute follow-up tools directly.
  If a private read such as clipboard, recent screenshot OCR, or a
  current-screen Accessibility text snapshot belongs to a `SkillPlan` with a
  following `ModelStep`, the Skill-defined model title and instruction take
  precedence over the legacy hard-coded prompt; those continuations still
  require a local model and redact private payloads from persisted trace/audit.
  The hard-coded clipboard/OCR prompts remain as fallback for one-off private
  reads without a declarative model step.
- Generic Skill model continuations also honor tool-result privacy metadata:
  `privacy=LocalOnly` or `requiresLocalModel=true` forces the continuation to
  stay local even when the `ModelStep` is otherwise marked remote-eligible.
- Unknown tool-result privacy metadata fails closed as `LocalOnly` before any
  remote continuation, matching the stored-message privacy boundary.
- Retryable local read failures now schedule one bounded retry on the already
  confirmed request, record a `ToolRetryScheduled` trace/audit event, and only
  fail the run after the retry budget is exhausted. External Activity launches,
  external sends, notification posting, background scheduling, and high/critical
  risk tools are not automatically replayed even if a lower layer marks the
  failure retryable.
- Tool observation now produces an explicit `AgentObservationDecision`:
  complete, continue with model, retry tool, fail, or cancel. The decision is
  recorded in trace without storing private continuation prompts.
- Plain chat answer runs now carry their trace `runId` through the ViewModel and
  call `observeModelResult()` after generation, so successful answers finish as
  `Completed` instead of being recovered as stale `GeneratingAnswer` failures on
  the next process start.
- Successful observations can now call `AgentObservationReplanner` to produce a
  next tool plan. The default production strategy is conservative: it only
  replans one explicit next action after sequence words such as "然后" / "then",
  and only when the current run has planned exactly one tool so far. Room
  restore can resume that path from pending recovery metadata by persisting only
  the explicit next-action suffix, not the full original prompt.
- Replanned tools are validated, safety checked, audited, traced, and returned
  to `AwaitingUserConfirmation` instead of being executed directly.
- Confirmation and observation are now bound to the current pending/confirmed
  request id, so stale request ids from earlier steps cannot advance the run.
- Implemented trace steps for skill planning, user confirmation, tool
  observation, assistant response, rejection, cancellation, and failure.
- Pending tool confirmations are persisted separately from the trace, then
  restored on startup as UI confirmation state only. Restoration does not
  execute tools; explicit user confirmation is still required before Android
  execution can continue. Pending rows may include bounded recovery metadata
  such as the active request arguments, share-preview text, and sequential
  `nextActionInput`; none of that is written to trace, audit, or recent run
  summaries. Persisted `SkillPlan` recovery stores only redacted plan structure,
  not the original skill input or tool/draft payload text, and terminal runs
  clear the in-memory recovery copy.
- `AwaitingUserConfirmation` is treated as recoverable only when its pending
  confirmation row can be parsed and still matches the pending tool boundary.
  Startup repair fails awaiting runs without a valid pending row, and malformed
  pending or `SkillPlan` JSON is not silently downgraded into a plain pending
  confirmation.
- Model-bound multi-step Skills that reach a second confirmation persist that
  second pending tool request as the active pending confirmation. Startup
  restore keeps the new `share_text` request id and model-produced arguments;
  confirm or observe calls using the earlier `read_clipboard` request id cannot
  advance, rerun, or overwrite the current run.
- `pending_agent_confirmations` may store and restore the model-produced text
  that is being previewed for an outbound share confirmation, plus the explicit
  next-action suffix needed to continue a sequential run after process restart.
  These payloads are confirmation/recovery UI state, not trace or audit content;
  tools still open only after the user confirms the current pending request.
- Completed persisted runs now rehydrate summary-only `RestoredSummary` steps
  from the trace store. This keeps typed timeline inspection available after a
  restart without restoring raw tool arguments or private payloads.
- Recent Agent run timeline summaries can now be restored from the persisted
  trace store and shown in the background activity surface. The UI reads only
  `AgentTraceStepSummary` type/summary metadata and does not parse or display
  persisted trace JSON or tool arguments.
- Room-backed trace stores redact `agent_runs.input` at the persistence
  boundary. The full raw run input is not written to Room; only an active
  pending confirmation may temporarily store a bounded next-action suffix so
  confirmation, observation, and replanning can continue after restore without
  writing the full prompt to Room or recent trace summaries.
- Persisted trace summaries and JSON previews now reuse the audit redactor for
  credential-like assignments, bearer values, API-key-shaped strings, and email
  addresses before truncation. Tool request and `UseTool` planning trace is
  parameter-free: it records tool names, argument keys, and draft titles only,
  not parameterized request reasons. Redaction still covers draft titles,
  observed summaries, retry/failure reasons, assistant text previews, and
  allowlisted completion metadata values. Foreground-app package/name outputs
  are treated as private device context and are redacted before trace/audit
  persistence.
- Background reminder requests with explicit relative delays now also have a
  Skill-first path. The reminder skill reuses the same delay/title parser as
  the action planner, then enters the normal confirmation and runtime
  permission boundary before scheduling any AlarmManager work.
- Explicit map search, email draft, and calendar draft commands now also have a
  conservative Skill-first path. The built-in Skill runtime reuses the same
  parameter parsers as the action planner, rejects discussion/negative
  phrasing, and still routes through registry validation, safety checks, audit,
  and user confirmation before any external app opens.
- Compound sequential inputs are not collapsed into a single direct parser
  result; if a full Agent plan cannot preserve the sequence, the request falls
  back to an answer instead of silently executing only one later action.
- Explicit Wi-Fi, Usage Access, and flashlight settings commands now also have
  a conservative Skill-first path. The shared device-settings parser accepts
  only settings navigation requests, rejects explanation/implementation/negative
  phrasing, and still routes through registry validation, safety checks, audit,
  and user confirmation before any system settings page opens.
- Explicit web search commands now also have a conservative Skill-first path.
  The shared parser accepts search/web-search/online-search phrasing with a
  non-empty query, rejects bare ambiguous “查一下” and discussion/negative
  phrasing, and does not broaden the external-search surface beyond confirmed
  `web_search` requests.
- Explicit recent media metadata requests now also have a conservative
  Skill-first path. The shared parser accepts recent images/screenshots/videos
  or audio metadata requests, keeps documents/downloads/all-files on the action
  fallback path, and rejects negative, discussion/API, and OCR/text-extraction
  phrasing so recent screenshot and image OCR remain separate confirmed tools.
- Explicit HTTPS link navigation now also has a conservative Skill-first path.
  The shared parser requires an open/visit intent plus an `https://` URI,
  rejects bare links, explanation/negative phrasing, and non-HTTPS schemes, and
  keeps URI-like text out of package app-intent inference.
- Explicit current foreground app requests now also have a conservative
  Skill-first path. The shared parser accepts only current/foreground/active
  app metadata requests, rejects foreground-service, implementation/design
  discussions, and sentence-start negation, and still relies on Usage Access
  special-app-access handling before reading the minimal app name/package
  metadata.
- Explicit current-app notification summary requests now also have a
  conservative Skill-first path. The shared parser accepts recent/current-app
  notification-summary wording, rejects bare English `notification`,
  permission/channel/push/global/system/shade/listener wording and
  sentence-start negation, and keeps the tool scoped to confirmed LocalOnly
  summaries of PocketMind/current-app active notifications. `maxCount` is
  schema-bounded to 20 and the query does not request Android runtime permission
  or special access.
- Explicit contact lookup requests now also have a conservative Skill-first
  path. The shared parser requires an explicit query, rejects bare
  contact/contacts wording, permission/API/implementation/list/export/negative
  phrasing, and keeps create/edit contact requests outside the lookup skill.
  `query_contacts` still requires confirmed `READ_CONTACTS`, schema-bounds
  `maxCount` to 20, returns only name/phone summaries, and marks both query and
  contacts JSON as private trace outputs.
- Explicit contact draft requests now also have a conservative Skill-first
  path. `create_contact_draft` is an ExternalDraft/navigation capability that
  opens the system contact insert page with only `name`/`email`/`phone` draft
  fields, does not read contacts, does not request `READ_CONTACTS`, and remains
  separate from `query_contacts`.
- Explicit calendar availability requests now also have a conservative
  Skill-first path. The shared parser requires a busy/free intent plus two
  timezone-qualified ISO timestamps, rejects permissions/API/implementation
  discussion and natural-language-only dates, and keeps the existing
  `query_calendar_availability` boundary: confirmed `READ_CALENDAR`, at most
  the schema/provider window, and busy/free blocks without event titles,
  locations, attendees, notes, or calendar IDs.
- Explicit current-screen text requests now also have a conservative
  Skill-first path. The shared parser accepts only current-screen Accessibility
  text snapshot wording, rejects screenshot/OCR/pixel/visual/semantic
  screen-understanding phrasing, and routes to confirmed
  `read_current_screen_text` with `LocalOnly` private `screenText` output and
  Accessibility special-access handling rather than Android runtime permission.
- Skill model-step results can now be consumed generically: when a declarative
  `ToolStep` depends on a `ModelStep`, the model output is bound through the
  tool step's `argumentBindings`, then validated, safety-checked, audited, and
  returned to `AwaitingUserConfirmation`. The progression fails closed if the
  next tool still depends on another unsatisfied step, preventing model output
  from skipping required tool or model prerequisites.
- Skill-first clipboard context and clipboard-summary-share planning reject
  sequential commands such as "read clipboard, then open Wi-Fi settings" so the
  one-step or composite skill cannot swallow later user intent.
- Open-ended model-driven replanning, arbitrary multi-confirmation skill UI
  orchestration, and full argument-bearing typed step rehydration are still
  pending.

Tests:

- `AgentLoopRuntimeTest`
- `AssistantOrchestratorTest`
- `AgentLoopRuntimeCompatibilityTest`
- `AgentLoopRuntimeTest.successfulObservationCanPlanNextToolAndRequestConfirmationAgain`
- `AgentLoopRuntimeTest.replannedToolCannotReuseExistingRequestId`
- `AgentLoopRuntimeTest.skillFirstClipboardSummaryShareBypassesActionPlannerAndRequestsConfirmation`
- `AgentLoopRuntimeTest.skillFirstClipboardContextBypassesActionPlannerAndRequestsConfirmation`
- `AgentLoopRuntimeTest.skillFirstPlanStillUsesRegistryAndRejectsInvalidToolArguments`
- `AgentLoopRuntimeTest.skillFirstReminderBypassesActionPlannerAndRequestsConfirmation`
- `AgentLoopRuntimeTest.skillFirstEnglishReminderBypassesActionPlannerAndRequestsConfirmation`
- `AgentLoopRuntimeTest.skillFirstDeviceSettingsBypassActionPlannerAndRequestConfirmation`
- `AgentLoopRuntimeTest.skillFirstWebSearchBypassesActionPlannerAndRequestsConfirmation`
- `AgentLoopRuntimeTest.skillFirstRecentMediaFilesBypassesActionPlannerAndRequestsConfirmation`
- `AgentLoopRuntimeTest.skillFirstHttpsDeepLinkBypassesActionPlannerAndRequestsConfirmation`
- `AgentLoopRuntimeTest.skillFirstForegroundAppBypassesActionPlannerAndRequestsConfirmation`
- `AgentLoopRuntimeTest.skillFirstRecentNotificationsBypassesActionPlannerAndRequestsConfirmation`
- `AgentLoopRuntimeTest.skillFirstContactLookupBypassesActionPlannerAndRequestsConfirmation`
- `AgentLoopRuntimeTest.contactObservationRedactsPrivateTraceFields`
- `AgentLoopRuntimeTest.skillFirstCalendarAvailabilityBypassesActionPlannerAndRequestsConfirmation`
- `AgentLoopRuntimeTest.reminderTimingDiscussionFallsBackToAnswerWithoutConfirmation`
- `AgentLoopRuntimeTest.clipboardSummarySharePlansShareAfterLocalModelResult`
- `AgentLoopRuntimeTest.modelStepOutputBindsToDependentToolStepAndRequestsConfirmation`
- `AgentLoopRuntimeTest.modelStepBindingRejectsMissingOutputBeforeConfirmation`
- `AgentLoopRuntimeTest.modelStepBindingCannotDirectlyExposePrivateToolOutputToShare`
- `AgentLoopRuntimeTest.compositeSkillIgnoresOldRequestIdsAfterShareIsPendingOrExecuting`
- `AgentLoopRuntimeTest.restoredClipboardSummaryPendingContinuesWithModelAndPlansShareConfirmation`
- `AgentLoopRuntimeTest.restoredClipboardSummarySharePendingIgnoresOldReadRequestAndCompletesShare`
- `AgentTraceStoreTest.roomStorePersistsRunAndStepSummariesWithoutRawToolArguments`
- `AgentTraceStoreTest.roomStoreToolPlanningTraceDoesNotPersistParameterLikeReasonText`
- `AgentTraceStoreTest.roomStoreRedactsSensitiveTraceTextAcrossSummariesAndJson`
- `AgentTraceStoreTest.roomStoreRedactsAllowlistedCompletionMetadataValues`
- `AgentTraceStoreTest.roomStoreRestoresPendingConfirmationWithoutPuttingRawArgumentsInTrace`
- `AgentTraceStoreTest.roomStoreReturnsRecentRunSummariesWithStepLimit`
- `PocketMindViewModelTest.restoreStartupStateRestoresPendingAgentConfirmationWithoutExecutingTool`
- `PocketMindViewModelTest.restoredSharePendingPreviewDoesNotExecuteUntilCurrentConfirmation`
- `PocketMindViewModelTest.refreshAuditEventsAlsoLoadsAgentTraceSummaries`
- `BuiltInSkillRuntimeTest.plansReminderSkillFirstWithoutActionDraft`
- `BuiltInSkillRuntimeTest.plansEnglishReminderSkillFirstWithoutActionDraft`
- `BuiltInSkillRuntimeTest.plansReminderSkillFirstWithVariantDelayPhrases`
- `BuiltInSkillRuntimeTest.reminderSkillFirstRejectsTimingDiscussionFalsePositives`
- `AssistantOrchestratorTest.defaultSequentialReplannerPlansExplicitNextActionAfterObservation`
- `AssistantOrchestratorTest.clipboardSummaryShareAdvancesFromModelOutputToShareConfirmation`
- `AssistantOrchestratorTest.skillFirstClipboardSummaryShareRoutesEvenWhenActionRuntimeDoesNotClassifyAction`

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
- `SkillRuntime` now exposes an optional Skill-first planner for requests that
  can safely build their first tool step from the raw user input.
- Implemented built-in manifests for email drafts, calendar drafts, map search,
  information lookup, device settings including Usage Access settings,
  background reminders, clipboard context, app navigation, and system text
  sharing.
- `SkillRequest.arguments` is now validated against
  `SkillManifest.inputSchemaJson` before confirmation or execution. Missing
  required inputs, blank required string inputs, extra fields, type mismatches,
  invalid enum values, regex mismatches, and numeric range failures reject the
  skill plan. Tool parameters remain owned and validated separately by the Tool
  Registry.
- Implemented a minimal declarative composition model for ordered skill steps:
  tool steps can declare stable ids, dependencies, and argument bindings; model
  transform steps can consume prior tool outputs and expose named outputs.
- Added `clipboard_summary_share_skill` as the first composite skill contract:
  it reads clipboard text after confirmation, keeps the transform local, then
  binds the generated summary into the system share tool.
- Explicit clipboard context and clipboard-summary-share inputs can now be
  planned directly by the built-in Skill runtime before action planning.
- The app loop can now consume declarative model-step output bindings for
  composite skills, not just the built-in clipboard summary share flow. Bound
  tool requests still go through registry validation, safety checks, trace,
  audit, and explicit confirmation before execution.
- Private tool outputs are not available for direct model-result bindings into
  later external tools. Clipboard text and similar private values must pass
  through the local model step boundary and still require a second
  confirmation before sharing.
- Added `SkillRunExecutor` as the first executable multi-step skill runner. It
  validates the plan, resolves step output bindings, enforces a step limit, and
  separates private binding outputs from the public result/trace.
- Added `SkillRunProgressor` as the pure Kotlin skill progression boundary for
  plan validation, argument binding, model-output-to-tool progression, and
  private tool output fences. `SkillRunExecutor` and Agent model-result
  replanning now share this progression logic instead of maintaining separate
  binding implementations.
- `SkillRunProgressor.nextToolAfterModelOutput()` also verifies that every
  declared dependency of the next `ToolStep` is already satisfied before
  returning another confirmation. A model output cannot skip an unrequested
  prerequisite tool and jump directly to a later external action.
- `SkillRunExecutor` now rejects direct `ToolStep.argumentBindings` from private
  tool outputs such as `read_clipboard.text` into later tool arguments. Private
  values can feed local model steps, but external tool payloads must come from
  explicit model-step outputs and still pass confirmation.
- `SkillRunExecutor` now gates every tool step through registry validation and
  safety policy before execution. Steps that require user confirmation return
  `AwaitingConfirmation` instead of calling the injected `ToolExecutor`
  directly.
- `SkillRunExecutor` now returns an opaque `SkillRunContinuation` at
  confirmation boundaries. A confirmed `ToolResult` can resume from the pending
  tool step, continue through model transforms, and stop again at the next
  confirmation without re-running earlier steps or exposing private tool output.
- `SkillRunExecutor` now has an explicit `Cancelled` state for pending skill
  continuations. Cancelling a multi-step skill stops before the pending tool,
  preserves only public outputs, and records a cancellation trace without
  exposing private tool outputs.
- App-level persistence covers the active pending tool confirmation produced by
  model-bound continuations, including the pending request/draft and `SkillPlan`
  structure needed to resume from that confirmation boundary after restart. The
  persisted `SkillPlan` is redacted before Room writes it; the raw
  `SkillRunContinuation` object is still not persisted, so private skill input,
  `outputs/privateOutputRefs/trace`, and draft payload text remain outside Room;
  broad arbitrary skill-runner state persistence is still pending.
- Room restore validates that a persisted pending confirmation with an attached
  `SkillPlan` still points at a tool step in that plan before restoring the UI.
  Invalid or malformed pending rows fail the owning awaiting run so restart does
  not leave an invisible confirmation zombie.
  Corrupt or stale rows are skipped instead of reviving an unexplainable skill
  continuation.

Tests:

- `BuiltInSkillRuntimeTest`
- `BuiltInSkillRuntimeTest.plansClipboardSummaryShareAsOrderedCompositeSkill`
- `BuiltInSkillRuntimeTest.routesClipboardSummaryShareInputToCompositePlan`
- `BuiltInSkillRuntimeTest.plansClipboardSummaryShareWithoutActionDraft`
- `BuiltInSkillRuntimeTest.clipboardSummaryShareSkillFirstRejectsSequentialFollowUp`
- `BuiltInSkillRuntimeTest.clipboardSummaryShareRejectsNegativeAndDiscussionRequests`
- `BuiltInSkillRuntimeTest.plansClipboardContextWithoutActionDraft`
- `BuiltInSkillRuntimeTest.clipboardContextSkillFirstRejectsSequentialFollowUp`
- `BuiltInSkillRuntimeTest.plansDeviceSettingsWithoutActionDraftWhenCommandIsExplicit`
- `BuiltInSkillRuntimeTest.plansWebSearchWithoutActionDraftWhenCommandIsExplicit`
- `BuiltInSkillRuntimeTest.plansRecentMediaFilesWithoutActionDraftWhenMetadataRequestIsExplicit`
- `BuiltInSkillRuntimeTest.plansHttpsDeepLinkWithoutActionDraftWhenCommandIsExplicit`
- `BuiltInSkillRuntimeTest.plansForegroundAppWithoutActionDraftWhenCommandIsExplicit`
- `BuiltInSkillRuntimeTest.plansRecentNotificationsWithoutActionDraftWhenCurrentAppRequestIsExplicit`
- `BuiltInSkillRuntimeTest.plansContactLookupWithoutActionDraftWhenQueryIsExplicit`
- `BuiltInSkillRuntimeTest.plansCalendarAvailabilityWithoutActionDraftWhenIsoWindowIsExplicit`
- `BuiltInSkillRuntimeTest.skillFirstPlannerDoesNotTreatOrdinaryShareDiscussionAsShareTool`
- `BuiltInSkillRuntimeTest.shareTextSkillFirstRejectsNegativeRequests`
- `BuiltInSkillRuntimeTest.shareTextSkillFirstRejectsQuestionAndDocumentationRequests`
- `BuiltInSkillRuntimeTest.validateStructureRejectsUnorderedOrInvalidCompositePlan`
- `BuiltInSkillRuntimeTest.builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema`
- `BuiltInSkillRuntimeTest.validateStructureRejectsSkillRequestArgumentsOutsideManifestSchema`
- `SkillRunExecutorTest`
- `SkillRunProgressorTest`
- `SkillRunExecutorTest.failsBeforeExecutingWhenSkillArgumentsDoNotMatchManifestSchema`
- `SkillRunExecutorTest.resumesAfterConfirmedToolResultAndStopsAtNextConfirmation`
- `SkillRunExecutorTest.resumesAgainAfterSecondConfirmationAndCompletesSkill`
- `SkillRunExecutorTest.resumeRejectsToolResultThatDoesNotMatchPendingRequest`
- `SkillRunExecutorTest.privateToolOutputCannotBindDirectlyToLaterToolArgument`
- `SkillRunExecutorTest.cancelStopsPendingSkillWithoutExecutingOrLeakingPrivateOutputs`
- `AgentLoopRuntimeTest.modelStepOutputBindsToDependentToolStepAndRequestsConfirmation`
- `AgentLoopRuntimeTest.modelStepBindingRejectsMissingOutputBeforeConfirmation`
- `AgentLoopRuntimeTest.modelStepBindingCannotDirectlyExposePrivateToolOutputToShare`
- `AgentLoopRuntimeTest.actionPlannerAttachedSkillPlanMustSatisfyManifestSchemaBeforeConfirmation`
- `AgentLoopRuntimeTest.replannedToolAttachedSkillPlanMustSatisfyManifestSchemaBeforeConfirmation`
- `AgentTraceStoreTest.roomStoreSkipsPendingSkillPlanThatDoesNotContainPendingToolRequest`
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
  chat prompts. Android share-intent text, generated shared-input text excerpts,
  and attachment metadata are held back from automatic remote generation unless
  the user manually provides the content they are willing to upload.
- Chat history now carries `MessagePrivacy`; messages marked `LocalOnly` are
  persisted for the local conversation but are filtered from remote history, and
  a `LocalOnly` current prompt is rejected before any remote request is made.
- Local action draft turns are persisted as `LocalOnly` user/assistant messages,
  even when the app is in remote mode. The confirmation flow executes Android
  tools locally and does not make that action text part of later remote-model
  history.
- Unknown stored `MessagePrivacy` enum values restore as `LocalOnly` rather
  than `RemoteEligible`, so future-version or corrupted history fails closed at
  the remote-history boundary.
- Legacy chat rows that predate the `privacy` column, and sessions imported
  from old SharedPreferences, are migrated as `LocalOnly` because they have no
  reliable remote-upload provenance.
- ViewModel dependencies now sit behind narrow ports for model state, sessions,
  generation settings, remote settings, downloads, memory, tool execution, and
  assistant routing so privacy behavior can be regression-tested without
  Android-bound concrete repositories.
- Implemented confirmed, read-only device context tools for current foreground
  app metadata, current-app notification summaries, contact lookup, calendar
  busy/free windows, and recent file metadata. Recent file reads return
  `LocalOnly` metadata only: file name, MIME type, coarse kind, size, and last
  modified time. The tool does not return file paths, URIs, or file contents.
- Foreground app summaries may require Usage Access. Without the grant the tool
  returns a structured permission failure with the recovery settings action;
  successful reads remain `LocalOnly` minimal app metadata, not usage history
  or screen content.
- `query_recent_files` supports a `screenshots` kind that filters recent image
  metadata likely belonging to screenshot folders or names. It remains
  metadata-only and returns the same minimal fields as other recent-file reads.
  The MediaStore cursor stops once the requested number of matching metadata
  rows is collected, so the provider does not continue reading later file
  metadata after `maxCount` is satisfied. On Android 13 and above, non-media
  file kinds such as `documents`, `downloads`, and `others` require system file
  picker authorization instead of broad MediaStore runtime permissions; those
  denials are surfaced as non-retryable for the same MediaStore tool.
- `read_recent_screenshot_ocr` is a separate skill-first, confirmed tool for
  explicit "识别最近 1 张截图文字" / recent screenshot OCR requests. It rejects
  multi-screenshot OCR requests, reads only the most recent screenshot pixels
  after confirmation through `READ_MEDIA_IMAGES` or legacy storage permission,
  extracts a bounded local OCR text excerpt, marks the result `LocalOnly`,
  treats `ocrText` as private Skill output, and does not persist or expose the
  MediaStore id, URI, path, original image, or raw pixels.
  Remote mode treats the OCR continuation like other protected local context and
  stops before sending it to a configured backend.
- `read_recent_image_ocr` is a separate skill-first, confirmed tool for
  explicit "识别最近图片/照片文字" requests. It scans up to 3 recent images, returns
  the first bounded local OCR text excerpt, marks the result `LocalOnly`, and
  reuses the same trace/audit redaction, remote-mode protection, and private
  Skill output boundary as screenshot OCR. Plain
  `query_recent_files(kind="images")` remains metadata-only and does not read
  pixels. The parser rejects all/many/more-than-3 image OCR, implementation/API
  or permission discussion, negated reads, and visual/semantic image
  understanding such as describing what is in an image.
- `read_current_screen_text` is a separate confirmed Device Context read for
  explicit current-screen text requests. It
  reads only the current Accessibility text nodes exposed by Android
  accessibility services at confirmation time, returns a bounded `screenText`
  snapshot marked
  `LocalOnly` / `requiresLocalModel=true`, and stops remote-mode automatic
  continuation. It must not capture a screenshot, run OCR, inspect pixels,
  infer visual or semantic screen state, or persist/expose raw `screenText` in
  trace, audit, persisted tool-observation messages, or remote model prompts.
  Its Accessibility requirement is special access, not a normal runtime
  permission.
- JVM executor matrix tests cover foreground app, notification summary, contact
  summary, calendar availability, recent file metadata, recent screenshot OCR,
  and recent image OCR success, permission denied, provider failure, LocalOnly,
  and minimal-field boundaries.
- Broad screen semantic understanding, screenshot capture, complete document
  parsing, Office/PDF parsing, arbitrary image/media OCR, image pixel analysis,
  and media content understanding are still pending. The Accessibility text
  snapshot boundary does not complete any of those modules.

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
  normal structured tool result path without executing the tool. Permission
  denial is treated as non-auto-retryable at the Agent loop boundary even when
  a lower-level provider marks the failure as retryable.
- Runtime permission requirements are now modeled with user-facing labels and
  rationales. Pending confirmation UI can show which Android runtime
  permissions may be requested before the system prompt appears, while
  structured failure data still preserves the raw manifest permission names.
- Startup restoration can rehydrate the latest pending Agent confirmation from
  Room without invoking Android execution or runtime permission requests.
- Android share-target ingestion for shared text, bounded `text/*` document
  excerpts, and image/audio/video/PDF/Office/binary metadata is implemented.
- Implemented outbound `share_text` as a confirmed tool that opens Android's
  system share panel. Explicit “分享这段文字...” requests can now enter the
  confirmation flow through the built-in Skill runtime without waiting for the
  action planner. The shared parser rejects negative share wording such as
  “不要分享/别分享/不要把...分享出去” and “don't share”, plus question/API-style
  discussion phrasing. Success means the chooser was opened; the app cannot
  know whether the user completed sharing in the destination app.
- Implemented constrained external navigation: `open_deep_link` only opens
  safe HTTPS links with `ACTION_VIEW`, and `open_app_intent` only opens an app
  launcher by package name. Arbitrary Intent extras, activities, actions, data
  URIs, and non-HTTPS schemes are intentionally not exposed.
- Implemented `open_app_deep_target` for fixed allowlisted app targets. The
  first target opens Android's application details settings for a package using
  a fixed system action and `package:` URI; the tool accepts only `targetId`
  and target-declared arguments, not arbitrary Intent payloads.
- Explicit app launch and app details settings requests now also have a
  conservative `app_navigation_skill` Skill-first path. The shared parser
  rejects negation, troubleshooting, docs/API/Intent payloads, app-internal
  targets such as mini-programs or payment codes, and non-allowlisted settings
  pages instead of downgrading them to a generic app launch.
- External Activity tool results now make this launch-only completion explicit
  with allowlisted metadata: `completionState`, `completionVerified=false`,
  `externalOutcome=Unknown`, `targetKind`, and safe target identifiers such as
  URI scheme/host, package name, or app target id. Agent trace persists only
  that allowlisted metadata, not raw payload text or original URI paths/query
  strings.
- Agent observation, UI status, and audit summaries now consume launch-only
  metadata directly. When an external activity is opened but completion is not
  verified, PocketMind reports that boundary explicitly and does not auto-plan
  a next tool from that unverified result.
- Usage Access special-app-access flow is modeled for `query_foreground_app`:
  the confirmation UI warns with a special-access requirement and settings
  entry, denial returns structured `specialAccess/settingsAction` recovery
  metadata, and `open_usage_access_settings` can open Android Usage Access
  settings. The confirmation-sheet settings entry now uses an ActivityResult
  boundary: when the user returns from Android settings, MainActivity rechecks
  Usage Access with AppOps and updates UI status without executing the pending
  tool. Broad special-access flows beyond Usage Access are still pending.

Tests:

- `AgentRuntimePermissionPolicyTest.deniedGrantResultKeepsToolFromExecutingUntilPermissionIsActuallyGranted`
- `PocketMindViewModelTest.specialAccessReturnUpdatesStatusTextWithoutExecutingTools`
- `PocketMindViewModelTest.deniedRuntimePermissionFailsPendingToolWithoutExecutingIt`
- `AgentLoopRuntimeTest.pendingToolPermissionDenialIsObservedWithoutEnteringExecutionState`
- `AgentLoopRuntimeTest.permissionDeniedToolFailureDoesNotScheduleAutomaticRetry`
- `ActionExecutorTest.opensAllowedDeepLinkAsActionViewIntent`
- `ActionExecutorTest.shareTextMetadataDoesNotIncludeRawPayload`
- `ActionExecutorTest.reportsActivityNotFoundAsNotStartedExternalCompletion`
- `ActionExecutorTest.reportsExternalActivityExceptionWithExceptionType`
- `AgentTraceStoreTest.roomStorePersistsOnlyAllowlistedToolObservationCompletionMetadata`
- `AgentLoopRuntimeTest.unverifiedExternalLaunchDoesNotAutoPlanNextTool`
- `ToolAuditRepositoryTest.unverifiedExternalLaunchAuditDoesNotClaimExecutionSuccess`
- `PocketMindViewModelTest.unverifiedExternalLaunchShowsLaunchOnlyStatus`

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
- `SafetyPolicy` rejects private device read tools whose specs do not require
  confirmation. This covers clipboard, contacts, files, calendar, and generic
  device-context reads, so future registry mistakes cannot silently downgrade a
  private read to optional/no confirmation.
- Audit events store request metadata, status, risk, permission names, and a
  short sanitized summary. They intentionally do not store tool arguments,
  prompts, remote responses, or secrets.
- Recent persisted audit events are now visible from the background task
  activity entry. The UI is intentionally metadata-only: time, event type, tool
  name, status, risk, permission names, and a parameter-free generated summary.
  It does not expose tool arguments, prompts, remote responses, raw clipboard
  text, raw `screenText`, Authorization headers, or API keys.
- Launch-only external activity observations are displayed as "opened but
  unverified" audit records rather than generic success, so audit history does
  not imply the user completed a share, draft, or target-app action.
- Successful reminder observations can now promote allowlisted recovery
  metadata into a typed `AgentRecoveryAction`. The typed action is limited to
  `schedule_reminder -> cancel_reminder(taskId)`, is surfaced through UI state
  as a local recovery entry, and does not include reminder title or body
  content.
- Tapping the reminder recovery entry does not cancel the task directly. It asks
  the Agent runtime to create a new audited `cancel_reminder` pending tool
  confirmation, reruns tool validation and safety policy, persists the pending
  confirmation for restore, and only executes after the user confirms the normal
  action sheet.
- `pending_agent_confirmations` is a narrower recovery table for the latest
  awaiting tool confirmation and may hold the tool arguments needed for an
  explicit later confirmation. It is separate from trace/audit summaries and is
  cleared when the request is confirmed, cancelled, or found stale.
- On startup, persisted in-flight Agent runs that cannot be safely resumed
  after process death (`Created`, context loading, planning, executing,
  observing, retrying, or model generation) are marked `Failed` with a trace
  failure step. `AwaitingUserConfirmation` runs are not failed because their
  pending confirmation snapshot remains the explicit recovery boundary.
- Audit summary sanitization removes key-like tokens, bearer credentials, and
  email addresses before truncation. The in-memory audit sink stores the same
  redacted copy as the Room-backed repository so tests cannot accidentally
  depend on raw private data.
- The first local-only privacy boundary is implemented for shared input,
  clipboard-derived continuations, and remote chat history. Tool specs now
  declare private output keys for clipboard text, recent OCR text, and
  current-screen Accessibility text so Skill output fencing and trace redaction
  share one policy source. Reminder rollback now has a visible Agent/UI
  confirmation handoff, while broader taint propagation is still pending.

Tests:

- `SafetyPolicyTest`
- `ToolRegistryTest.privateDeviceReadToolsMustRequireConfirmation`
- `ToolAuditEventTest`
- `ToolAuditRepositoryTest.unverifiedExternalLaunchAuditDoesNotClaimExecutionSuccess`
- `SessionRepositoryTest`
- `AgentLoopRuntimeTest.confirmedToolResultIsObservedAndCompletesRun`
- `AgentTraceStoreTest.roomStoreFailsStaleInFlightRunsButKeepsPendingConfirmationsOnStartup`

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

- Default local memory uses a lightweight token/hash index over saved sessions
  and explicit persisted records; it does not require a dedicated memory model
  asset to be installed.
- Added explicit long-term memory controls for reviewing saved records,
  forgetting a single record, and clearing explicit memory records.
- Explicit preference and task-state records are now persisted in Room and
  reloaded during memory rebuild; ordinary conversation-derived memory remains
  rebuilt from saved chat messages instead of duplicated into the memory table.
- Active background tasks are now synced into deterministic `TaskState`
  long-term memory records, giving the Agent recallable task state without
  requiring the user to manually remember it. The sync stores only task type,
  status, trigger time, and an opaque auto-managed record id; reminder titles,
  bodies, prompts, tool arguments, and remote responses are not written to
  long-term memory. Terminal or missing auto-managed task-state records are
  forgotten on refresh. When the user explicitly forgets one of these active
  auto-managed records or clears long-term memory, a hidden suppression marker
  keeps later startup, refresh, or chat-triggered sync from recreating it.
- `sendMessage` persists explicit user preference statements such as
  `记住：...` / `remember ...` after the user message is accepted into the
  session; `rebuild` reloads persisted records and saved non-control session
  history into the in-memory index.
- Explicit preference records use deterministic ids derived from normalized
  preference text, so repeating the same remember command upserts one record
  instead of creating duplicate long-term memories.
- Explicit response-length and response-language preferences resolve conflicts
  by replacing older records in the same preference family, so `记住` commands
  update the user's current answer-style preference instead of keeping
  contradictory long-term memories.
- Explicit remember control messages are not re-derived from chat history, so
  forgetting a persisted preference prevents it from being restored by a later
  memory rebuild.
- Agent planning treats memory lookup/context formatting as optional: failures
  fall back to empty memory context and continue ordinary chat/planning.
- CJK memory recall requires specific multi-character overlap when the query
  has multi-character tokens, reducing unrelated single-character matches on
  the lightweight index.
- `MemoryRepository` has a semantic runtime extension point: a true semantic
  runtime can return semantic hits without the lightweight term-overlap gate.
- `ModelRepository` now exposes a verified memory-embedding model path only
  for existing `MemoryEmbedding` assets that passed recommended-model
  verification. `PocketMindViewModel` syncs that path into
  `SemanticMemoryRuntimeController` before rebuilding memory so runtime
  switching happens at model verification boundaries.
- `MemoryRepository` implements the semantic runtime controller and can switch
  between the default hash runtime and an injected semantic runtime, re-embedding
  current entries on switch. Production still falls back to hash unless a real
  semantic runtime declares support for semantic recall.
- The LiteRT embedding adapter is still not wired into runtime retrieval; a
  downloaded memory model asset alone does not mean embedding semantics are
  participating.

Tests:

- `MemoryRepositoryTest`
- `MemoryRepositoryTest.conflictingResponseLengthPreferenceReplacesOlderRecord`
- `MemoryRepositoryTest.unrelatedResponsePreferenceFamiliesCanCoexist`
- `MemoryRepositoryTest.combinedResponsePreferenceReplacesBothFamilies`
- `MemoryRepositoryTest.semanticRuntimeControllerSwitchesBetweenFallbackAndSemanticRuntime`
- `MemoryRepositoryTest.memoryModelPathDoesNotEnableSemanticRecallWithoutRuntimeSupport`
- `ModelRepositoryPathTest`
- `MemoryRepositoryTest.taskStateMemoryRecordIdIsStableForWhitespace`
- `PocketMindViewModelTest`
- `PocketMindViewModelTest.rememberCommandReplacesConflictingPreferenceMemory`
- `PocketMindViewModelTest.restoreStartupStateSyncsVerifiedMemoryModelBeforeRebuildingMemoryIndex`
- `PocketMindViewModelTest.restoreStartupStateIndexesScheduledTasksAsForgettableTaskState`
- `PocketMindViewModelTest.backgroundTaskStateMemoryDoesNotEnterRemotePromptOrHistory`
- `PocketMindViewModelTest.cancelBackgroundTaskForgetsTaskStateMemory`
- `PocketMindViewModelTest.refreshBackgroundTasksDropsTerminalTaskStateMemory`
- `AgentLoopRuntimeTest`

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
- Reminder task ids are generated independently of title/body text and retry on
  local collisions before persistence, so two same-title reminders created in
  the same clock tick do not overwrite each other or share rollback ids.
- The scheduler uses `AlarmManager.setAndAllowWhileIdle`; triggered reminders
  first move through `Running`, then post a local notification when notification
  permission is available and update the task status to `Delivered` or
  `Failed`.
- Reminder alarms use an Intent data URI derived from the opaque task id in
  addition to the request code, so Java hash collisions between task ids cannot
  make two reminders share one `PendingIntent`. Cancellation also probes the
  legacy no-data identity so alarms scheduled by older builds can still be
  removed.
- Reminder alarm delivery treats the local `scheduled_tasks` row as the source
  of truth. A fired alarm only carries the opaque task id; delivery ignores
  title/body extras from old alarms, verifies that the task still exists, is a
  `Reminder`, and is still `Scheduled`, then posts using the stored title/body.
  Missing, cancelled, deleted, or failed tasks are ignored without changing
  state or posting stale notifications.
- Reminder cancellation and deletion now use a conditional Scheduled-only
  database update before cancelling the platform alarm/work. This closes the
  in-flight broadcast window because delivery still gates on the database row
  being `Scheduled`; a stale cancellation path also cannot overwrite a task that
  has already moved to `Running`, `Delivered`, `Failed`, or another terminal
  state.
- Reminder delivery completion and reschedule failures also use conditional
  state updates, so old delivery/rescheduler snapshots cannot overwrite a
  concurrently written terminal state.
- `ReminderBootReceiver` listens for `BOOT_COMPLETED` and asks
  `ReminderRescheduler` to restore every still-`Scheduled` reminder after the
  system clears alarms on reboot. The rescheduler first recovers stale
  `Running` reminder rows whose lease has expired, then pages through scheduled
  reminders by stable `(triggerAtMillis, id)` order instead of stopping at the
  first batch. Past-due reminders are rescheduled with a short catch-up delay
  instead of being silently dropped; after the catch-up alarm is accepted, the
  stored `triggerAtMillis` is conditionally moved to the same catch-up time
  while the row is still `Reminder` + `Scheduled`. After registering the new
  data-URI alarm identity, the rescheduler performs a best-effort cleanup of the
  legacy hash-only identity to prevent duplicate wakeups after upgrade.
- If an alarm cannot be scheduled or restored while the row is still
  `Scheduled`, the task is marked `Failed` so repository state does not claim a
  reminder is still pending when Android has no alarm registered.
- Reminder confirmation requests notification permission before execution on
  Android versions that require it. If permission is still unavailable,
  execution fails with a structured `PermissionDenied` tool result.
- Periodic checks now enter `Running` for each worker execution and settle back
  to `Scheduled` after a successful scan. Runner or Worker exceptions mark the
  periodic task `Failed` so local state no longer claims a healthy scheduled
  check.
- A stale `Running` periodic check is reclaimed before a new worker run tries to
  acquire it, using the same bounded lease as reminder delivery recovery.
- Periodic check worker state updates are conditional transitions:
  `Scheduled -> Running` and `Running -> Scheduled/Failed`. If the user disables
  the policy while a worker is notifying, the final `Cancelled` state is kept
  and worker completion or outer worker failure cannot revive or fail it.
- `schedule_reminder` and `cancel_reminder` tool observation audit includes
  bounded task metadata (`taskId`, `taskStatus`, `triggerAtMillis`,
  `recoveryToolName`, `recoveryTaskId`) while continuing to omit reminder
  title/body content from audit display.
- Successful `schedule_reminder` results now include bounded rollback metadata:
  `recoveryToolName=cancel_reminder` and the scheduled `recoveryTaskId`. Agent
  trace preserves only this recovery metadata, not reminder title/body content.
- Agent observation now converts that allowlisted pair into a typed
  `AgentRecoveryAction` with a fresh `cancel_reminder(taskId)` request/draft.
  The observation message includes the bounded task id so the user can ask to
  undo the reminder without exposing reminder body text.
- `cancel_reminder` reports success only after a still-`Scheduled` task has had
  its platform schedule cancelled and local state moved out of `Scheduled`.
  Missing, already delivered, already cancelled, or concurrently changed tasks
  fail as non-retryable stale rollback requests instead of claiming success.
- Explicit cancel reminder requests now also have a conservative Skill-first
  path. The shared parser accepts only cancel/undo reminder wording with a
  `task-*` id, and rejects missing task ids, API/implementation/explanation
  discussions, negated commands, and non-reminder cancellations such as
  calendar/contact/mail cancellation.
- Implemented runtime background task review UI for active `Scheduled` and
  `Running` tasks. The UI shows pending or executing task metadata; explicit
  cancellation is shown only for still-`Scheduled` tasks, where it cancels the
  platform schedule, updates local task state to `Cancelled`, and removes the
  task from the running-task list. Cancellation failure paths reload active and
  history lists from the scheduler so stale UI rows do not keep old status.
- Implemented recent background task history in the same review surface for
  terminal `Delivered`, `Cancelled`, `Deleted`, and `Failed` tasks. History rows
  are read-only and cannot be mistaken for still-running tasks.
- Implemented periodic check policy UX in the background task surface. Users
  can inspect and save the local reminder patrol policy, including enabled
  state, interval, minimum notification spacing, overdue grace, battery
  constraints, task status, next allowed check time, and latest run summary.
  Policy changes are persisted through the singleton `periodic-check-local`
  task and remain separate from one-shot reminder scheduling.
- Periodic check run summaries preserve the saved policy fields instead of
  replacing them, so the UI reads typed policy state from the background layer
  rather than parsing task history rows.

Tests:

- `ScheduledTaskRepositoryTest`
- `PeriodicCheckSchedulerTest`
- `ReminderAlarmReceiverTest`
- `ActionExecutorTest.schedulesReminderThroughBackgroundScheduler`
- `ActionExecutorTest.reportsStaleReminderCancellationAsNonRetryableInvalidRequest`
- `AgentTraceStoreTest.roomStorePersistsReminderRecoveryMetadataWithoutReminderContent`
- `ScheduledTaskRemovalCoordinatorTest`
- `PocketMindViewModelTest.restoreStartupStateLoadsRunningBackgroundTasksWithoutRemoteWork`
- `PocketMindViewModelTest.cancelRunningBackgroundTaskRefreshesUiAndCancelsScheduler`
- `PocketMindViewModelTest.setPeriodicCheckPolicySchedulesDefaultPolicyAndRefreshesUi`
- `PocketMindViewModelTest.setPeriodicCheckPolicyFailureDoesNotShowHealthyRunningTask`
- `PocketMindViewModelTest.disablePeriodicCheckPolicyMovesTaskToHistory`
- `PocketMindViewModelTest.disablePeriodicCheckPolicyFailureKeepsRunningTaskVisible`
- `MainActivitySmokeTest.backgroundTaskManagerShowsEmptyState`
- `ActionExecutorTest`
- `ActionPlannerTest.infersReminderDraftWithDelayMinutes`
- `ActionPlannerTest.infersReminderDelayFromMatchedRelativeDelayPhrase`
- `ActionPlannerTest.infersReminderDraftWithChineseVariantDelay`
- `ActionPlannerTest.infersReminderDraftWithDecimalHourDelay`
- `ActionPlannerTest.infersReminderDraftForPoliteEnglishCommand`
- `ActionPlannerTest.rejectsReminderTimingDiscussionsAsNoAction`
- `ToolRegistryTest.validatesReminderDelayMinutesAsPositiveInteger`
- `BuiltInSkillRuntimeTest.plansReminderAsBackgroundToolStep`
- `BuiltInSkillRuntimeTest.plansReminderSkillFirstWithoutActionDraft`
- `BuiltInSkillRuntimeTest.plansEnglishReminderSkillFirstWithoutActionDraft`
- `BuiltInSkillRuntimeTest.plansReminderSkillFirstWithVariantDelayPhrases`
- `BuiltInSkillRuntimeTest.reminderSkillFirstRejectsTimingDiscussionFalsePositives`

## Multimodal Inputs

Code:

- `app/src/main/java/com/bytedance/zgx/pocketmind/multimodal/`
- `MainActivity` share intent handling
- `MainActivity` in-app attachment picker handling

Responsibilities:

- Accept user-initiated shared text, bounded `text/*` document excerpts,
  bounded local OCR text excerpts for user-provided `image/*` attachments,
  and attachment metadata from Android share targets and the in-app picker.
- Classify attachments by MIME type; keep unsupported non-text files
  metadata-only.
- Keep multimodal source handling separate from chat generation and tools.

Current status:

- Implemented Android share-target entry for `ACTION_SEND` and
  `ACTION_SEND_MULTIPLE`, including text, image, audio, video, PDF, RTF, and
  Office MIME types.
- Implemented a composer attachment entry that launches Android's system
  document picker for user-selected text, image, audio, video, PDF, and Office
  files. Picked files reuse the same `SharedInput` path as share intents.
- Implemented privacy-minimal `SharedInput` prompts for bounded direct shared
  text plus attachment metadata such as kind, MIME type, display name, and byte
  size.
- Implemented bounded local text excerpts for user-initiated shared `text/*`
  documents and bounded local OCR text excerpts for user-provided `image/*`
  attachments. Excerpts are user-visible and limited to the local shared-input
  prompt.
- Audio, video, PDF, Office, binary, and other unsupported attachments stay
  metadata-only; the app does not parse or embed their bytes into prompts.
- Implemented a voice input entry that launches Android system speech
  recognition and returns the transcript as a one-shot compose-box draft.
  Transcripts are not auto-sent, do not create chat messages until the user
  taps send, and do not trigger model generation by themselves.
- Shared-input prompts are marked `LocalOnly` when generated automatically, so
  local processing can continue without later leaking shared text, generated
  excerpts, attachment metadata, or local assistant responses into remote chat
  history. Remote mode rejects automatically generated shared-input prompts
  before calling a remote backend.
- The voice entry does not read or parse audio files. Recent screenshot OCR and
  recent image OCR are implemented as confirmed Device Context tools, not
  automatic shared-input ingestion. The current-screen Accessibility
  text snapshot tool follows the same Device Context boundary and reads text
  nodes only; screenshot capture, screen semantic understanding, Office/PDF
  parsing, image semantic understanding, and media content understanding are
  pending. Image OCR is limited to user-provided `image/*` attachments, the
  user-confirmed recent screenshot OCR tool, or the user-confirmed recent image
  OCR tool, and produces text excerpts only.

Tests:

- `SharedInputTest`
- `PocketMindViewModelTest.localSharedInputDoesNotEnterLaterRemoteHistory`
- `PocketMindViewModelTest.remoteModeRejectsLocalOnlyPromptBeforeCallingRemoteRuntime`
- `PocketMindViewModelTest.remoteModeProtectsRecentScreenshotOcrBeforeRemoteContinuation`
- `PocketMindViewModelTest.remoteModeProtectsRecentImageOcrBeforeRemoteContinuation`
- `PocketMindViewModelTest.voiceTranscriptDraftIsOneShotAndDoesNotSendMessage`
- `MainActivitySmokeTest` composer attachment and voice entry visibility

## Regression Strategy

Local verification:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Emulator regression:

```bash
adb devices -l
ANDROID_SERIAL=emulator-5554 scripts/verify_emulator.sh
```

Use `AVD_NAME=<name> scripts/verify_emulator.sh` when the helper should launch
an AVD first. The emulator helper refuses physical-device serials and exits
before Gradle, APK install, or instrumentation when it cannot identify exactly
one authorized `emulator-*` target. It also validates requested AVD names before
startup and treats instrumentation failure markers such as failed test status
codes, stack traces, and `FAILURES!!!` as hard failures even when `adb shell am
instrument` exits zero. Use
`ANDROID_SERIAL=<serial> scripts/install_and_test_device.sh` for physical
device validation. A full regression report should record the target serial or
AVD name, API level, ABI, whether `CLEAN_DEVICE=1` was used, and the current
instrumentation test count.

Full device validation remains required for LiteRT-LM model execution because
emulators usually do not expose the same GPU backend behavior as physical
arm64-v8a devices.
