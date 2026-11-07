# Cloud-edge collaboration: role- and step-level placement

Date: 2026-11-07 · Base: `20361499` (merge base of the simplification branch) · Status: **draft for owner review**

- Research: [research.md](research.md).
- Privacy decision: [privacy-derivation-options.md](privacy-derivation-options.md).
- Current placement facts: [adaptive-inference status](../20260718-adaptive-edge-inference/status.md).
- Threat model: [architecture_depth.md](../../architecture_depth.md).

## 0. What exists after this batch

The pure core is in `app/src/main/java/com/bytedance/zgx/solin/orchestration/collab/`:

- `CollaborationPolicy.kt`: `CollaborationRole`, `RolePlacementPolicy`, `EscalationPolicy`.
- `MilestonePlan.kt`: `MilestonePlanParser` with its strict JSON scan
  (`StrictJsonScan`), the per-run `RunProgressGate`, and `CloudPlannerRequest`.

There are 47 JVM tests, plus one instrumented test class
(`MilestonePlanParserDeviceTest`) that compiles but has not yet run on a device.
**No runtime path calls any of it.** It touches no Room table and no network.
`DerivedProgressEgress.DEFAULT` is `Off`, and no build type or setting can change
it. Runtime behavior is therefore identical to `20361499`. Section 7 lists the
code changes needed to wire it, and §10 gives the order.

## 1. Problem and goals

Today one placement decision covers a whole run (`ModelPlacementPolicy`,
`RunPlacementBinding` keyed by `runId`). A phone task that needs a strong planner
must therefore either:

- run entirely on the device, with a weak plan; or
- run on the remote model, which stops as soon as the run reads the screen
  (FR-1.10, `PLACEMENT_LOCAL_CONTINUATION_REQUIRED`).

The goals:

1. Let a strong remote model **plan** from the user's RemoteEligible request,
   while every step that reads or acts on the phone stays on the device.
2. Answer locally first, and use the cloud only when a calibrated signal says the
   local answer failed. Never escalate LocalOnly content, and always go through
   the existing disclosure and consent.
3. Keep every non-negotiable in `AGENTS.md` intact. Any rule that changes is named
   in §6 and §8, and the owner approves it.

Non-goals:

- Speculative decoding or draft/correct, KV or hidden-state transfer, mid-generation
  migration, a learned router, multi-node scheduling.
- Any change to `SafetyPolicy`, tool risk levels or confirmation rules.
- Any free-text channel from the device to the cloud.

## 2. Today's contract (unchanged by this batch)

| Rule | Where |
| --- | --- |
| One placement per run, bound atomically before the first serving call | FR-1.3, FR-1.8; `RunPlacementBinding.kt:19-80`; `SolinDatabaseEntities.kt:169` (primary key `runId`) |
| The dispatcher rejects a receipt whose destination differs from the binding | `RunPlacementBindingStore.kt:260-309` |
| No automatic cross-target fallback; a user retry is a new run | FR-1.9 |
| `localInvocationCount > 0` and `remoteInvocationCount > 0` are never both true in a run | success criterion 2 |
| A Remote run that gains LocalOnly content stops remote continuation | FR-1.10; `RunPlacementContinuationResolver.kt:15-41` |
| The strictest segment wins; null privacy means LocalOnly; content derived from LocalOnly stays LocalOnly | `PromptPrivacyPlan.kt:32-127` |
| Trace: exactly one `PlacementSelected`, then sequential receipt/invocation pairs | `AgentBehaviorEvalModels.kt:255-306`; `RunPlacementBindingStore.kt:449` |

## 3. Role × step placement model

A **role** is what a model call does inside a run. A **step** is the per-role
ordinal of a call with a new prompt, starting at 0: a new plan, a replan, or an
escalated answer. Retrying the same prompt stays in its step as another `attempt`,
exactly as today.

| Role | Does | Inputs (`PromptSegmentSource`) | Privacy class of inputs | Remote under today's contract? | Remote under this spec? |
| --- | --- | --- | --- | --- | --- |
| Planner | Turns the request into a closed-vocabulary `MilestonePlan` | `CurrentInput` only, plus the static public vocabulary. With the flag on, also the `RunProgress` enums (§6) | The request's own class; the vocabulary is public | No. Planning is local (rules, skills, local action model). The only remote planning is the remote-vision exception | Yes, when the request is RemoteEligible and the unchanged Auto/Remote rules choose Remote |
| Perceiver | Reads the screen, OCR, accessibility tree and device context | `ScreenOcr`, `DeviceContext`, `ToolObservation` | LocalOnly | No (remote vision is a separately consented exception, see §11) | **No, under every preference** |
| Executor | Grounds one milestone into one tool call on the live screen | Milestone plus live observation | LocalOnly | No | **No** |
| Verifier | Checks a milestone against the post-action observation | Post-action observation | LocalOnly | No | **No** |
| Answerer | Writes the user-facing reply | Any source | Strictest segment wins | Only if every segment is RemoteEligible (today's run-level rule) | Same rule, plus at most one escalation (§5) |

The code is `CollaborationRole.remoteSources`. Device-bound roles have an empty
set, the Planner has `{CurrentInput}`, and the Answerer has every source.
`RolePlacementPolicy.decide` checks in this order:

1. **Undeclared inputs** (null or empty): `Blocked(PLACEMENT_DECISION_MISSING)`
   for every role.
2. **Device-bound role:** forced LocalOnly with `requiresLocalModel`, decided with
   the Auto preference. A missing local model **blocks** the call; it never falls
   back to Remote. The returned decision still carries the user's real
   preference, so diagnostics and audit never show a false Auto.
3. **Source outside the role's contract**, for example a Planner given a
   `ToolObservation` even if mislabelled RemoteEligible: forced LocalOnly.
4. Otherwise `PromptPrivacyPlanner.build(inputs)`, then the unchanged
   `ModelPlacementPolicy.decide`.

Tests prove the Answerer decides exactly as `ModelPlacementPolicy` does, and that
Remote placement implies every input is RemoteEligible, has no local-model
requirement, and comes from a source the role allows
(`RolePlacementPolicyTest`).

That equivalence test compares against the rules only. The live Answerer path is
`decisionModelPlacementPolicy?.decide(input, routingText) ?: ModelPlacementPolicy.decide(input)`
(`ChatController.kt:160`), and `DecisionModelPlacementPolicy`, the Codiv router
wired at `SolinAppContainer.kt:481`, can override the rules. Phase 3a therefore
depends on D5. If Codiv is retired, the existing test is enough. If it stays,
equivalence must be re-proven against whichever router is live before
`ChatController` switches to `RolePlacementPolicy`.

**Complexity of a Planner call.** A Planner call exists only because no rule or
skill plan covers a multi-step phone task. Its `RolePlacementInput` is therefore
built with `requiresMultiStepPlan = true`, which `RequestComplexityAggregator`
scores Complex (`RequestComplexityAggregator.kt:33`). Under Auto the Planner is
Remote when the remote candidate is eligible, and Local otherwise. It does not
reuse `AgentRunOptions.expectsToolLoop()` (branch
`fix/adaptive-routing-and-skill-resilience`, `6b4abefb`; not on `20361499`).
That signal sizes the answer turn and deliberately leaves out local model
planning, so a Planner call scored through it would always be Simple under Auto
and stay local forever. The Answerer keeps the existing signals. If the Phase 1
lab shows the local planner covers the vocabulary well, the owner can make Planner
calls Simple instead; the rule lives in one place, where the Planner input is
built.

**Only Planner and Answerer calls are placed and bound.** Device-bound roles keep
using the local helper runtimes they use today (FR-1.3: "本地辅助模型不参与目的地选择").
They never reach the dispatcher, so no code path exists from them to the remote
adapter.

## 4. EcoAgent-lite: cloud milestone planner, device loop

```mermaid
sequenceDiagram
    participant U as User
    participant P as Planner (remote or local)
    participant D as Device loop (Perceiver / Executor / Verifier, local)
    participant A as Answerer (local first)
    U->>P: RemoteEligible request + public vocabulary (CloudPlannerRequest)
    P-->>D: MilestonePlan JSON → MilestonePlanParser (strict, whole-plan reject)
    loop each milestone
        D->>D: observe → ground → existing tool gates → verify → MilestoneStatus
    end
    D-->>P: only if flag on and budget left: RunProgress enums (≤ 3 per run)
    D->>A: LocalOnly results stay local; the answer is written on the device
```

### 4.1 Closed vocabulary (`MilestonePlan.kt`)

| Intent | Slots | Grounded on the device by |
| --- | --- | --- |
| `open_app` | `app` | The existing app-launch tool |
| `search_in_app` | `app`, `query` | The app-search skill (model-driven bootstrap or static skill) |
| `refine_results` | `app`, `query` | Sort or filter controls, through the observation-action loop |
| `read_results` | `app` | The local screen read; the result is LocalOnly |
| `enter_draft_text` | `app`, `query` | Typing the user's own words without submitting |
| `navigate` | `app`, `query` | The observation-action loop inside the app |
| `use_tool` | `tool` | One allowlisted tool, with arguments grounded locally from the request |

The vocabulary has these properties:

- **No intent pays, sends, deletes, publishes, orders, transfers, authorizes or
  confirms.** The action gate still refuses those controls, and the user taps them
  (non-negotiable 3).
- `app` must come from `AppInteractionProfiles` package names. That is static app
  data; the installed-app list is never sent.
- `tool` must come from `MilestoneVocabulary.DEFAULT_TOOLS`: alarm, timer,
  reminder, Wi-Fi, system and flashlight settings, and camera. These are public
  tools with no private output keys, and a test checks this against `ToolRegistry`.
- `query` must be well-formed (not blank, at most 64 characters, trimmed, no
  control or format characters) and **a literal substring of the user's request**.
  The planner cannot put words of its own on the device.
- **Parser limits:** at most 4096 characters; strict RFC 8259 JSON, checked by
  `StrictJsonScan` before org.json runs; schema version 1; exact key sets; at
  most 8 milestones. Android's built-in org.json is lenient where the JVM test
  artifact is strict: it skips comments, accepts `=` and `;` separators, reads
  octal and hex numbers, and keeps the last value of a duplicate key. The scan
  refuses all of these, duplicate keys at any depth, and anything after the
  object, without depending on org.json. Any violation rejects the whole plan
  with an enum reason. There is no partial acceptance and no repair.

A local planner uses the same render and parser, so the device loop consumes one
plan contract whatever the placement.

### 4.2 Device loop and replanning

- Each milestone runs through the existing `ToolPlanCoordinator` path: skill plan
  validation, `SafetyPolicy`, confirmation, the action gate, and the egress gate.
  Nothing in the milestone loop can approve a step that the current gates would
  confirm or refuse.
- Failure inside a milestone is recovered locally by `ModelObservationReplanner`,
  bounded by the existing step budget.
- If a milestone fails:
  - with the flag Off (the default), the run never calls the remote planner again;
    a second request would carry no new information. The run asks the user, or
    answers locally with what it has;
  - with the flag on, at most `RunProgressGate.MAX_REPLANS` (3) progress reports
    go to the Planner as new Planner steps. The run's single `RunProgressGate`
    instance counts them (§6.3).

## 5. Answer cascade

1. The Answerer runs locally first whenever the unchanged rules allow it.
2. A failed local call may be retried on the remote model only if
   `EscalationPolicy.decide` allows it. `EscalationInput` carries the failed
   call's own `RolePlacementDecision` as well as the retry payload. It checks, in
   order:
   - both the failed call and the retry are Answerer calls. Any other role gives
     `Forbidden(RoleCannotEscalate)`; the Planner never escalates;
   - the preference is Auto, for the retry and in the failed call's decision;
   - the failed call was Local;
   - the failed call read only RemoteEligible content: its privacy plan is
     RemoteEligible with no local-model need, and its remote state is not
     `PrivacyBlocked`. Otherwise `Forbidden(InputsMustStayLocal)`, even when the
     retry payload has been trimmed to RemoteEligible;
   - fewer than `MAX_ESCALATIONS_PER_RUN` (1) escalations have happened;
   - at least one signal is in the **lab-calibrated** set, which is empty until
     measured;
   - `RolePlacementPolicy` on the exact retry payload finds Remote eligible.
     LocalOnly, unknown or undeclared inputs give `Forbidden(InputsMustStayLocal)`.
3. A sensitive payload always gives `RequireApproval(SensitiveContent)`. Otherwise
   the result is `Preapproved` only if the user enabled the escalation opt-in,
   else `RequireApproval(NotPreapproved)`. Pre-approval approves the mechanism,
   never specific content (sweep test).
4. `Preapproved` still passes the remote-send preflight
   (`ChatSendPreflight.preparedRemoteSend`, `ChatSendPreflight.kt:86-106`),
   disclosure and audit at dispatch.
5. **Prerequisite:** the three liveness defects recorded in `4b9c1eb2` must be
   fixed first. The arm point cannot observe cancellation, cannot see session
   identity, and is not authoritative against stop.

The escalated call is a new `(run, Answerer, 1)` binding inside the same run. This
amends FR-1.9 (§7.8). The conservative alternative is to keep FR-1.9 and offer
escalation as a one-tap user retry that creates a new run (owner decision D3).

## 6. Derived progress egress (explicit, flag-gated policy change)

This is the only place where content derived from LocalOnly data may leave the
device. It is an exception to the rule in `PromptPrivacyPlan.kt` that "derived from
LocalOnly stays LocalOnly". It is implemented in `RunProgressGate` and
`CloudPlannerRequest`, is unwired, and defaults to Off. The decision record is
[privacy-derivation-options.md](privacy-derivation-options.md), option B.

### 6.1 Exactly what may leave

These are the only fields `CloudPlannerRequest` can append, under the key
`progress`:

| Field | Values | Source on the device |
| --- | --- | --- |
| `milestones` | One of `pending`, `active`, `reached`, `failed` per plan milestone, in plan order (at most 8) | Deterministic Verifier status, never model free text |
| `lastStatus` | `succeeded`, `failed`, `rejected`, `cancelled`, or `null` | `ToolResult.status` |
| `lastFailure` | One of the 12 codes pinned in `RunProgressGate.RELEASABLE_FAILURE_CODES`, or `null`. Any other code, including padded or case-changed ones and any `UiActionFailureKind` entry added later, becomes `unknown`. Always `null` after a success | `ToolResult.data["failureKind"]` |
| `foreground` | `planned_app`, `other_app` or `unknown`. **The package name never leaves** | `ScreenObservation.packageName` compared with the plan's apps |
| `stepsUsed` | Integer 0..32 | Step counter |

The rest of the request is the RemoteEligible user request, the intent names, and
the sorted app and tool lists, always from `MilestoneVocabulary.DEFAULT`: `render`
has no vocabulary parameter, so an installed-app list cannot be passed in.

**Read on the device, but only the enum result leaves:** `ToolResult.status`,
`ToolResult.data["failureKind"]` (mapped through the pinned list) and
`ScreenObservation.packageName` (compared with the plan's apps).

**Never read, so never sent:** tool summaries, any other `ToolResult.data` value,
`ToolError` messages, request IDs, screen elements and their text, OCR,
accessibility text, clipboard, contacts, files, memory and history.
`RunProgress` has no `String` field; a reflection test enforces this.

### 6.2 When it may leave (gate order)

One `RunProgressGate` is created per run. A replan request is built with
`CloudPlannerRequest.render(request, gate, planner, plan, record)`, which asks the
gate for this step's progress; no caller can hand `render` progress of its own.
`MilestonePlan`, `MilestoneVocabulary`, `RunProgress`, `ProgressExport.Released`
and `RolePlacementDecision` have internal constructors, and a source scan in the
tests fails if production code outside their owning file constructs them.

The gate releases progress only when all of these hold, checked in this order:

1. `flag == ClosedVocabulary`. The default is `Off`, which gives `Withheld(FlagOff)`.
2. The planner decision is role Planner, constraint `None`, placement Remote
   (`PlannerNotRemote`).
3. Its privacy plan is RemoteEligible with no local-model need
   (`PlannerPrivacyNotRemoteEligible`). With step 2, the run already placed the
   Planner on the RemoteEligible request alone.
4. The plan has 1 to 8 milestones (`PlanSizeOutOfRange`).
5. The status count equals the plan's milestone count.
6. `stepsUsed` is in 0..32.
7. The gate has released fewer than 3 reports in this run. A withheld call does
   not count; a released report counts whether or not the request is then sent.

With the flag Off, the replan render is **byte-identical** to `render(request)`
for every adversarial record, and `render(request)` itself is pinned byte for
byte (test `flagOffRequestIsByteIdenticalToTheRequestWithoutDeviceData`). The
pin holds **per platform**: the JVM unit test pins the org.json output of the JVM,
and Android's `JSONObject.quote` escapes `/`, so the device bytes can differ from
the JVM bytes for text containing `/`. The flag-Off equality is claimed for each
platform separately, not across them.

### 6.3 Covert-channel bound

Per report the upper bound on content is \(16 + \log_2 5 + \log_2 13 + \log_2 3 + \log_2 33 \approx 28.6\) bits:
8 milestones at 2 bits each, then `lastStatus`, `lastFailure` (12 pinned codes or
`null`), `foreground` and `stepsUsed`. With 3 reports, a run can release at most
about 86 bits of content.

That bound counts only the fields. **The existence and timing of a report leak
too.** A report is sent only after a milestone failed, so the cloud learns that a
step failed and when, and the number of reports in a run (0 to 3) is itself a
signal. The gate neither pads nor delays reports. With the flag Off the run never
calls the remote Planner again (§4.2), so neither content nor existence leaks.

The per-run `RunProgressGate` instance owns the report counter; the caller does
not pass it in, so it cannot reset or understate it within a run. A second gate
for the same run would start a fresh budget, so the Phase 6 wiring creates exactly
one gate where the run starts (§7.12), and trace reconciliation (§7.7 rule 6)
independently caps releases at 3 per run.

The failure codes are pinned: a new `UiActionFailureKind` entry leaves as
`unknown` until it is reviewed and added to `RELEASABLE_FAILURE_CODES`, and a test
fails when the enum and the list differ. The bound cannot widen silently.

An app on the screen can influence these bits only indirectly, by changing what
Solin's own executors observe. This residual risk is threat T10 in §8.

### 6.4 Disclosure and audit

- The first remote Planner call in a run goes through the existing remote-send
  preflight and disclosure, like any remote send. When the flag is on, the
  disclosure adds one line: 规划过程中会发送步骤进度类别（里程碑状态、结果类别、前台是否为计划中的应用、步数），不含屏幕文字、应用名称或工具结果。
- Every release writes a `RemoteSendAuditEvent` whose summary uses enum names only
  (§7.9). It contains no request text and no field values beyond the enums
  (non-negotiable 4).
- The run receipt records that progress was released (§7.4), so trace
  reconciliation can bound it.

## 7. Code changes to wire it

The file owners in §10 apply. Hotspot files (`AgentLoopRuntime.kt`,
`SolinViewModel.kt`, `SolinScreen.kt`) are described here as integration points
only.

### 7.1 Room migration to the next schema version: bindings keyed by (run, role, step)

**Sequencing.** This change takes the next schema version, whatever it is when
Phase 2 lands. The simplification branch's typed-audit-metadata migration lands
first as 18 → 19, so collab Phase 2 takes 19 → 20; the names below assume that
order. Phase 2 adds a migration chain test that opens a v18 database and runs
every migration to the current version, and one owner holds `data/` for the whole
phase (§10), so two branches never claim the same version.

`data/SolinDatabaseEntities.kt`:

```kotlin
@Entity(
    tableName = "agent_run_placement_bindings",
    primaryKeys = ["runId", "role", "step"],
    foreignKeys = [ /* unchanged: runId → agent_runs.id ON DELETE CASCADE */ ],
)
data class AgentRunPlacementBindingEntity(
    val runId: String,
    val role: String,
    val step: Int,
    // ... every existing column unchanged ...
)
```

`data/SolinDatabaseMigrations.kt` uses the SQLite rebuild pattern, keeps the table
name so existing references and the privacy scan stay valid, and appends
`MIGRATION_19_20` to `ALL_MIGRATIONS`:

```sql
CREATE TABLE IF NOT EXISTS `agent_run_placement_bindings_v20` (
    `runId` TEXT NOT NULL, `role` TEXT NOT NULL, `step` INTEGER NOT NULL,
    `schemaVersion` INTEGER NOT NULL, `policyVersion` INTEGER NOT NULL,
    `preference` TEXT NOT NULL, `placement` TEXT NOT NULL, `primaryReason` TEXT NOT NULL,
    `complexity` TEXT NOT NULL, `resourceBand` TEXT NOT NULL, `localState` TEXT NOT NULL,
    `remoteState` TEXT NOT NULL, `remoteProfileRevision` TEXT, `bootCount` INTEGER NOT NULL,
    `boundAtElapsedRealtimeMillis` INTEGER NOT NULL, `dispatchState` TEXT NOT NULL,
    `attempt` INTEGER NOT NULL,
    PRIMARY KEY(`runId`, `role`, `step`),
    FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
);
INSERT INTO `agent_run_placement_bindings_v20`
SELECT `runId`, 'Answerer', 0, `schemaVersion`, `policyVersion`, `preference`, `placement`,
       `primaryReason`, `complexity`, `resourceBand`, `localState`, `remoteState`,
       `remoteProfileRevision`, `bootCount`, `boundAtElapsedRealtimeMillis`,
       `dispatchState`, `attempt`
FROM `agent_run_placement_bindings`;
DROP TABLE `agent_run_placement_bindings`;
ALTER TABLE `agent_run_placement_bindings_v20` RENAME TO `agent_run_placement_bindings`;
```

The remaining changes:

- `data/SolinDatabase.kt` moves to `version = 20`, and the exported
  `app/schemas/.../20.json` is committed.
- Migrated rows keep `schemaVersion = 1`. Recovery requires the current schema
  version, so a run that was in flight during the upgrade fails closed as
  `PLACEMENT_NOT_RESTORABLE`, the same as today's boot-count rule.
- `app/src/androidTest/.../data/SolinDatabaseMigrationTest.kt` adds a 19 → 20
  case and the chain from 18. A binding row from before the migration must become
  `(runId, Answerer, 0)` with every column preserved.

### 7.2 DAO (`data/SolinDatabaseDaos.kt:382-487`)

| Today | After |
| --- | --- |
| `binding(runId)` | `binding(runId, role, step)` and `liveBinding(runId)` (`dispatchState != 'Terminal'`; at most one row by the invariant below) |
| `compareAndSetStarted(runId, placement, expectedAttempt)` | Adds `AND role = :role AND step = :step AND NOT EXISTS (SELECT 1 FROM agent_run_placement_bindings WHERE runId = :runId AND dispatchState = 'Started')` |
| `compareAndSetIdle(runId, placement, attempt)` | Adds `AND role = :role AND step = :step` |
| `markBindingTerminal(runId)` | Unchanged: terminalizes every role-step of the run |
| `bindAndReserveTransaction` | Before insert: the parent run is not terminal; no row of the run is `Started`; `COUNT(*) WHERE runId AND role` equals `binding.step`; then it closes earlier role-steps (`Pending`/`Idle` become `Terminal`) |
| `claimAndRecordTransaction(runId, placement, ...)` | Adds `role` and `step` and passes them to the CAS |
| `recoverySnapshot(runId)` | Returns `liveBinding(runId)` plus every step of the run |

**Invariant: at most one live role-step per run, and at most one in-flight
invocation per run.** Cancellation therefore still stops exactly one handle.

### 7.3 Binding and store

`orchestration/RunPlacementBinding.kt`:

- Add `role: CollaborationRole` and `step: Int`.
- `RUN_PLACEMENT_BINDING_SCHEMA_VERSION = 2`; `RUN_PLACEMENT_TRACE_SCHEMA_VERSION = 2`.
- `init` requires `role in {Planner, Answerer}` and `step >= 0`. A device-bound
  role cannot be bound at all.
- `fromDecision(runId, decision: RolePlacementDecision, step, ...)` takes the role
  from the decision.
- `ModelRuntimeInvocation` gains `role` and `step`.

`orchestration/RunPlacementBindingStore.kt`:

- The in-memory map stays keyed by `runId` and holds the **live** role-step.
  `activeBinding(runId)` returns its permit.
- `bindAndReserve` for a new role-step refuses unless the current entry's
  `runtimeEntry` is `Ready` or `Terminal`. It then swaps the entry.
- `claimForDispatchLocked` (`:260-309`) keeps its rules, applied to the live
  role-step:
  - `receipt.destination` must equal that role-step's placement;
  - the state must be `Pending` or `Idle`;
  - the snapshot must be fresh.

  It also requires: a Remote destination implies `role in {Planner, Answerer}`;
  and `receipt.derivedProgressReleased` implies role Planner, placement Remote and
  `step >= 1`.
- `inspectForRecovery` restores only a live **Answerer** role-step. A Planner step
  is never restored; the run replans or stops (fail closed).
- `haveConsistentPlacementTrace` (`:449`) applies the §7.7 rules.

### 7.4 Receipt and trace fields

- `RunDataReceipt` (`AgentModels.kt:39`) gains
  `derivedProgressReleased: Boolean = false`.
- `PlacementSelected` and `ModelRuntimeInvocationStarted` traces gain `role` and
  `step`. Update `PLACEMENT_SELECTED_TRACE_KEYS`,
  `MODEL_RUNTIME_INVOCATION_TRACE_KEYS` and `RUN_DATA_RECEIPT_TRACE_KEYS` in
  `RunPlacementBindingStore.kt`.
- `scripts/privacy_scan.sh` must add `role` and `step` to `trace_allowed_fields`
  for `PlacementSelected` and `ModelRuntimeInvocationStarted`, and
  `derivedProgressReleased` for `RunDataReceiptRecorded`. Otherwise the scan
  flags the new keys.

### 7.5 Dispatcher (`orchestration/ModelRuntimeDispatcher.kt:52-96`)

- The `dispatch(permit, receipt, adapters)` signature is unchanged. The permit
  carries the role-step, and adapter selection by `invocation.placement` is
  unchanged.
- `stop(runId, ...)` is unchanged: it terminalizes every role-step and stops the
  single running handle.
- A Planner call uses `ModelRuntimeAdapters<String>`. Its remote adapter sends
  `CloudPlannerRequest.render(...)` as the user message under a fixed planner
  instruction. Its local adapter sends the same text to the local model. The
  adapter shape depends on the runtime slice (§10).

### 7.6 Continuation resolver (`RunPlacementContinuationResolver.kt:15-41`)

The rule is unchanged: a Remote role-step whose privacy plan becomes LocalOnly is
blocked with `PLACEMENT_LOCAL_CONTINUATION_REQUIRED`. FR-1.10 holds by
construction, because a Remote Planner never continues with observations. The
next device-bound work is local, and a new Answerer step is placed from scratch by
`RolePlacementPolicy`. Nothing is added here. **One gate per invariant.**

### 7.7 `reconcilePlacementTrace` (`eval/AgentBehaviorEvalModels.kt:255-306`)

The current rule is exactly one `PlacementSelected` per run. It is replaced by:

1. Group events by `(role, step)`. A schema-v1 event without a role reads as
   `(Answerer, 0)`, so old traces still reconcile.
2. Each group with an invocation has exactly one selection, which comes before its
   first dispatch event. Receipt and invocation pairs are sequential per group.
   Placement, destination and remote revision must match the group's selection.
3. Groups do not interleave. Every dispatch event of group *k* lies between the
   selection of *k* and the selection of *k + 1*.
4. A selection whose role is not Planner or Answerer is rejected. Steps per role
   are contiguous from 0.
5. A Remote receipt requires `currentPromptPrivacy == RemoteEligible`
   (unchanged) and role Planner or Answerer.
6. `derivedProgressReleased` appears only on Remote Planner receipts with
   `step >= 1`, at most `RunProgressGate.MAX_REPLANS` times per run.
7. Answerer has at most 2 steps. If it has 2, step 0 is Local and step 1 is Remote
   with reason `ESCALATED_AFTER_LOCAL_FAILURE`.

`AgentBehaviorPlacementTrace` gains `rolePlacements: List<(role, step, placement,
reason)>`. Its existing `placement` and `reason` keep meaning "the final Answerer
step", so existing evaluators are unaffected.

### 7.8 Amendments to the adaptive-inference spec

These amend `../20260718-adaptive-edge-inference/spec.md`. They apply only when
Phase 2 lands, and only after the owner approves.

- **FR-1.8 (line 584), proposed text:** placement 必须原子绑定到 (run, role, step)，并被该
  role-step 的同目标 attempt、续写、受限恢复和取消复用；统一 dispatcher 禁止对同一
  role-step 跨目标 claim；同一 run 同一时刻至多一个 live role-step 和一个 in-flight
  invocation；只有 Planner 与 Answerer 可以绑定，Perceiver / Executor / Verifier 只在本机运行。
- **FR-1.9 (line 585), proposed text:** 首次模型发送后，同一 role-step 不得自动跨目标
  fallback。新 role-step 只能由 `RolePlacementPolicy` 依据其自身输入重新决策；Answerer 的远端
  重答只能经 `EscalationPolicy` 产生（每 run 至多一次、仅 Auto、仅 RemoteEligible、经既有
  披露与确认）；其他用户重试仍创建新 run。
- **Success criterion 2 (line 39), proposed text:** 任一 role-step 的初始生成、同目标重试、
  工具续写和取消始终绑定同一 placement；对任一 role-step，`localInvocationCount > 0` 与
  `remoteInvocationCount > 0` 永不同时成立；同一 run 内可以同时存在本地和远端 role-step，但每个
  远端 invocation 的 receipt 必须为 RemoteEligible 且 role ∈ {Planner, Answerer}。
- FR-1.10 and FR-1.13 are unchanged.
- FR-1.7 is unchanged. Role placement adds no router; the Codiv router conflict is
  decision D5.
- Non-goal §13, "自动故障转移…运行中迁移": a new role-step is a separate,
  separately privacy-planned call, not a migration of a running generation. The
  single Answerer escalation is the one consented exception. §13 should name it.

### 7.9 UI and audit text

`ui/SolinDisplayText.kt` keeps its existing reason-code text for the Answerer and
adds a role line derived from `RolePlacementDecision`:

| Case | Text |
| --- | --- |
| Planner, Remote | 规划：远程模型（只发送你的请求和公开的应用/功能清单） |
| Planner, Remote, progress released | 规划：远程模型（另发送步骤进度类别，不含屏幕文字或应用名称） |
| Planner, Local | 规划：本地模型 |
| Device-bound roles | 看屏、操作与核对：始终在本机 |
| Escalation needs approval | 本地模型的回答未通过质量检查。是否把这条请求发给远程模型重新回答？屏幕内容和仅限本地的内容不会发送。 |
| Answerer, `ESCALATED_AFTER_LOCAL_FAILURE` | 本次使用远程模型：本地回答未通过质量检查，已按你的授权重新回答。 |

`RemoteSendAuditEvent.summary` uses enum names only and never request text:

- Planner send: 云端规划：发送本次请求与公开清单，返回里程碑计划；不含屏幕内容。
- Progress release: 云端规划：发送第 N 次步骤进度（里程碑状态、结果类别、前台是否为计划应用、步数）；不含屏幕文字、应用名称、工具结果。
- Escalation: 质量升级：本地回答未通过质量检查，改由远程模型重答（已预先授权 / 已确认）。

### 7.10 Policy version and reason code

- `ModelPlacementPolicy.POLICY_VERSION` goes from 1 to 2 in the same change that
  writes `role` and `step`. Role-scoped traces are then distinguishable, and
  `matchesPlacement` keeps comparing it. The decision rules themselves do not
  change.
- Add one persisted reason code, `ESCALATED_AFTER_LOCAL_FAILURE`.
- Role constraints are displayed from `RolePlacementDecision.constraint` rather
  than as new reason codes, so `ModelPlacementPolicy` keeps its 23 codes plus this
  one.

### 7.11 Planning mode and settings

The milestone planner is a new `InitialPlanningMode` value, proposed name
**`MilestonePlanner`** (not implemented), rather than a new boolean.
`InitialPlanningMode` already decides how a run gets its first plan:
`AgentRunOptions.kt:17-20` has `RuleFirst` and `ModelFirstRemoteTools`, and the
local-agent slice adds `LocalModelFirst`. `initialAgentRunOptions`
(`ChatControllerHelpers.kt:253-271`) picks one value per run. `MilestonePlanner`
means rules and skills first, as in `RuleFirst`; when none covers a
phone-operation request, the run asks the Planner role for a milestone plan. One
switch therefore chooses the planning path, and there is no third planning
setting. Phase 4 selects it in debug builds only, and never when the preference
is Remote (§11).

The two remaining settings live in `data/Stores.kt` and
`PreferenceSettingsStore.kt`, and each defaults to off in every build type. No
`BuildConfig` field enables either.

- `derivedProgressEgress(): DerivedProgressEgress = Off`. It is persisted by name,
  and an unknown value reads as `Off`.
- `answerEscalationPreapproved(): Boolean = false`.

### 7.12 Integration points in hotspot files (described, not edited)

- `AgentLoopRuntime.kt`:
  - when `initialPlanningMode == MilestonePlanner` and a phone-operation request
    has no rule or skill plan, ask for a milestone plan through the local-agent
    slice's new `InitialToolPlanner` entry;
  - keep the run's milestone state, including its single `RunProgressGate`
    created when the run starts, and clear it where
    `VerifiedPhoneRouteReplanner.clear()` is called (lines 795 and 903);
  - add no other logic.
- `SolinViewModel.kt`: expose the two settings and the escalation-approval state.
- `SolinScreen.kt`: the role line in the run header, two Trust Center toggles,
  and the escalation approval sheet.
- `SolinAppContainer.kt:418` (composition root): insert the collab
  `MilestoneReplanner` into `CompositeAgentObservationReplanner` after
  `DeadLoopDetectionReplanner` and before `ModelObservationReplanner`. It returns
  null when the run has no milestone plan.

## 8. Threats and rules

Rules use the `architecture_depth.md` style. T1–T9 are the existing threats; T10
and T11 are new. T11 must be added to that table when the cloud planner is wired
(Phase 4), and T10 when the progress flag is wired (Phase 6).

| # | New threat | Example | Primary defense |
| --- | --- | --- | --- |
| T10 | Covert channel through derived signals | A malicious app hides a button so the failure kind the cloud operator sees encodes a bit | Closed enums only, pinned failure codes, at most 3 reports and about 86 bits of content per run, plus the existence and timing of each report; flag Off by default (§6.3) |
| T11 | Malicious or compromised remote planner | The planner returns a plan that opens a banking app and types text | Closed intent set with no critical intent, apps and tools from static lists, queries copied literally from the request, strict JSON on every org.json, unchanged action and egress gates |

| Rule | Threats | Enforced by |
| --- | --- | --- |
| Perceiver, Executor and Verifier are never placed Remote and cannot be bound | T6, T4 | `CollaborationRole.remoteSources`, the binding `init`, and dispatcher reachability |
| The Planner's prompt carries only `CurrentInput` | T4, T6, T1 (screen text cannot steer the plan) | `RoleConstraint.SourceOutsideRoleContract` |
| Undeclared inputs block the call | T9 | `RoleConstraint.UndeclaredInputs` gives `PLACEMENT_DECISION_MISSING` |
| A device-bound role with no local model blocks; it never falls back to Remote | T6, T9 | `RolePlacementPolicy` (decided under Auto, reported with the user's preference) |
| Closed intents with no critical action | T1, T2, T11 | `MilestoneIntent` and the action gate |
| A query is a literal span of the request | T1, T5, T11 | `MilestonePlanParser` |
| Apps and tools come from static public lists | T4, T11 | `MilestoneVocabulary.DEFAULT` |
| A bad milestone rejects the whole plan, with an enum reason | T1, T7 | `MilestonePlanParser` |
| Plan JSON is strict whichever org.json is on the classpath (no comments, odd separators, non-decimal numbers, duplicate keys or trailing text) | T1, T11 | `StrictJsonScan` before org.json; `StrictJsonScanTest` on the JVM and `MilestonePlanParserDeviceTest` on a device |
| Progress is enums only; the package name becomes a relation | T4, T6, T7 | `RunProgressGate` and the reflection test on `RunProgress` |
| Progress needs the flag and a Planner already placed Remote on the request alone; callers cannot build progress, plans or vocabularies | T9 | `RunProgressGate` gate steps 1–3, `render` calling the gate, internal constructors with a source scan |
| Progress is bounded: 1–8 milestones, 32 steps, 3 reports per run counted by the run's own gate, 12 pinned failure codes | T10 | `RunProgressGate` gate steps 4–7, `RELEASABLE_FAILURE_CODES` |
| Escalation is Answerer-only and requires Auto, a failed Local call that read only RemoteEligible content, at most once, a calibrated signal, RemoteEligible retry inputs, and the existing preflight | T9, T4 | `EscalationPolicy` |
| A sensitive payload always needs approval; pre-approval approves the mechanism only | T4 | `EscalationPolicy` |
| One live role-step per run; CAS per role-step | T9 | DAO (§7.2) |
| Remote audit is summary-only and uses enum names | T7 | §7.9 |

## 9. Acceptance tests

**Present now** (JVM, `orchestration.collab.*`, 47 tests):

| Test class | Tests | Covers |
| --- | --- | --- |
| `RolePlacementPolicyTest` | 10 | Role contract, privacy, candidates, Answerer equivalence with the rules, Remote-implies-eligible sweep, real preference on device-bound decisions |
| `EscalationPolicyTest` | 10 | Answerer-only, failed-call privacy, escalation tiers and the pre-approval sweep |
| `MilestonePlanParserTest` | 12 | Injection, hidden or padded queries, vocabulary, exact shape, duplicate keys, whole-plan rejection, no critical intent, public tools |
| `StrictJsonScanTest` | 3 | Syntax Android's org.json tolerates, tested on the scan directly; strict JSON still accepted |
| `RunProgressGateTest` | 12 | Flag defaults Off, byte-identical and pinned output when Off, no secrets in output, failure-code smuggling and the pinned code list, OCR in element names, package spoofing, gate order, per-run budget, closed rendering, reflection, constructor source scan |

**Present, pending device evidence:** `MilestonePlanParserDeviceTest`
(androidTest, 2 tests) runs the `StrictJsonScanTest` cases on the phone's lenient
org.json. It compiles; it has not run on a device.

**Required with the wiring:**

1. **Migration to the next schema version** (androidTest; 19 → 20 after the
   simplification branch's 18 → 19, plus the chain from 18): rows preserved as
   `(runId, Answerer, 0)`. A schema-v1 in-flight row is not restorable.
2. **DAO:**
   - the CAS rejects the wrong role, step or placement;
   - the CAS rejects a second `Started` row in the same run;
   - a non-sequential `step` is rejected;
   - binding a new role-step closes earlier ones.
3. **Store and dispatcher:**
   - a Remote receipt for a device-bound role is rejected;
   - a progress flag on a Local or step-0 Planner receipt is rejected;
   - stop terminalizes every role-step.
4. **`reconcilePlacementTrace`:** a table of valid and invalid multi-role traces,
   one case per rule in §7.7. Old single-placement traces still reconcile.
5. **Behavior preservation:** with every setting off, the full existing placement
   suite passes unchanged and every run has exactly one `(run, Answerer, 0)`
   binding.
6. **End-to-end with fake runtimes:**
   - a LocalOnly or screen-reading run makes zero remote invocations for any
     device-bound role;
   - with the flag Off, the remote planner receives bytes equal to
     `CloudPlannerRequest.render(request)`.
7. **`scripts/privacy_scan.sh`** passes with the new trace keys.
8. **Device** (`docs/phone_acceptance.md`; JVM evidence is not device evidence):
   - `MilestonePlanParserDeviceTest` passes;
   - packet capture in LocalOnly mode shows 0 bytes sent;
   - a planner run sends only the request and the vocabulary, or the progress
     fields when the flag is on;
   - phone benchmark paired comparison;
   - an independent adversarial fixture set.

## 10. Phased rollout and wiring order

| Phase | Content | Owner | Depends on | Rollback |
| --- | --- | --- | --- | --- |
| 0 (this batch) | Pure core, tests, docs | collab | none | Delete `orchestration/collab/` |
| 1 | Lab evidence (research.md checklist) | lab | 0 | n/a |
| 2 | Placement contract: §7.1–7.7, §7.10, privacy scan; behavior-preserving `(run, Answerer, 0)`; the next schema version after the simplification branch's 18 → 19 | One owner for `data/` + `RunPlacementBinding*` + `ModelRuntimeDispatcher` + `AgentModels` / `AgentTraceStore` traces + `eval/AgentBehaviorEvalModels.kt` + `scripts/privacy_scan.sh` (persistence hotspot, serialized) | 0; the simplification branch's migration; owner decisions D1, D3 | Migration is forward-only; the behavior equals today's |
| 3a | `ChatController` builds the Answerer decision with `RolePlacementPolicy`, proved identical to whichever router is live (§3); `InitialToolPlanner.planFromMilestones(plan)` produces a validated `SkillPlan`; `InitialPlanningMode.MilestonePlanner` | local-agent (`ChatController`, `ChatControllerHelpers`, `InitialToolPlanner`, `AgentRunOptions`) | 2; D5; runtime-slice adapter shape | Mode not selected |
| 3b | `MilestoneTracker` (Verifier, deterministic) and `MilestoneReplanner`, both pure with tests; planner instruction text | collab (`orchestration/collab/**`) | 2 (parallel with 3a) | Not composed |
| 3c | Planner adapters: `ModelRuntimeAdapters<String>` for local and remote | runtime (`runtime/`, `action/`) | 2 | Not composed |
| 4 | Composition and UI: `SolinAppContainer`, §7.12 hooks, §7.9 text, §7.11 planning mode and settings; cloud planner request-only, debug build first | integrator (serialized hotspots) | 3a, 3b, 3c; lab vocabulary coverage | Mode not selected |
| 5 | Answer escalation | local-agent plus runtime | 4; `4b9c1eb2` liveness fixes; a calibrated signal | Setting off |
| 6 | Derived progress flag wiring | collab plus integrator | 4; benchmark uplift; packet capture; **owner's direct confirmation** | Flag Off |
| 7 | Consider Auto by default | owner | All of the above on two physical devices | Settings |

Owner documents to update when facts move:

- adaptive `status.md` and `spec.md` (FR amendments);
- `architecture_depth.md` (T10 and T11, plus the gate map);
- `privacy_notice.md` (planner send, progress fields);
- `capability_matrix.json` if a capability's privacy class changes.

## 11. What the core replaces or extends

| Existing | Relation |
| --- | --- |
| `ModelPlacementPolicy` | Extended, not changed. `RolePlacementPolicy` adds the role contract in front of it; the Answerer path is provably identical |
| `RunPlacementBinding` (per run) | Replaced by per (run, role, step) bindings (§7.1–7.3); old rows become `(run, Answerer, 0)` |
| `classifyQualityEscalation` (`4b9c1eb2`, unmerged) | Replaced by `EscalationPolicy`, with the same three tiers plus Answerer-only, failed-call privacy, calibrated-signal and once-per-run checks. Do not merge `4b9c1eb2`'s classifier; reuse its opt-in store design |
| `InitialToolPlanner` / skill runtime | Extended. They consume `MilestonePlan`, and the same parser serves a local planner |
| `InitialPlanningMode.ModelFirstRemoteTools` (`AgentLoopRuntime.kt:569`; chosen by `initialAgentRunOptions` for Remote runs) | **Coexists** with `MilestonePlanner`; one run uses one mode. A Remote preference keeps `ModelFirstRemoteTools` exactly as today: the whole run is remote, the remote model calls public tools itself (`RemoteToolScope.ModelPlanning`), and it stops at the first LocalOnly observation (FR-1.10). Auto and Local may use `MilestonePlanner`, where the remote side only plans and the device loop reads the screen. They solve different contracts, so neither replaces the other now. Whether `ModelFirstRemoteTools` should stop taking phone-operation requests is for the owner to decide on the Phase 4 benchmark |
| `ModelObservationReplanner` | Kept as the Executor's local recovery inside a milestone |
| `DecisionModelPlacementPolicy` / `CodivDecisionModelRouter` | Candidate for removal (D5). It is a second router and egress path; role placement does not use it. While it is live, Phase 3a must prove Answerer equivalence against it (§3) |
| `RemoteVisionObservationReplanner` | Kept as a separately consented exception (manual Remote, opt-in, confirmed first step). Candidate to route through the dispatcher as its own consented role-step so success criterion 2 covers it (D6) |
| `PromptPrivacyPlanner` | Unchanged. The only declassification is `RunProgressGate`, with no new `PromptSegmentSource`, so one gate owns the exception |

## 12. Owner decisions

| # | Decision | Options | Recommendation | Status |
| --- | --- | --- | --- | --- |
| D1 | May anything derived from LocalOnly data reach the cloud planner? | A never · B closed enums · C NER redaction · D per-run preview approval | **A by default, B as the only opt-in** (memo) | **Approved**, relayed by the integrator: the owner said "allow per your recommendation" |
| D2 | When to enable the progress flag | never · debug only · opt-in setting in all builds | Not before Phase 6 evidence; then debug-only first | **Pending** the owner's direct confirmation |
| D3 | Answer escalation inside a run | amend FR-1.9 (new Answerer step) · keep FR-1.9 (user retry starts a new run) | Amend, after the liveness fixes; the alternative is the safe fallback | Open |
| D4 | Escalation pre-approval setting | none · opt-in, default off | Opt-in, default off; it can only lift `NotPreapproved` | Open |
| D5 | Codiv decision router | keep Shadow · keep Active · retire | Retire unless it beats rules-only on `decision_routing_eval.json`; never use it for the Planner role. Phase 3a depends on it (§3) | Open |
| D6 | Remote vision | keep as a bypass · route through the dispatcher as a consented role-step | Route through the dispatcher in a later batch | Open |

## 13. Relaxations compared with today's contract

**In code now:**

- Only `DerivedProgressEgress.ClosedVocabulary` lets the five §6.1 fields, derived
  from LocalOnly tool results and observations, reach a remote Planner, within the
  §6.2 bounds: a Planner placed Remote on the RemoteEligible request alone, 1–8
  milestones, 0–32 steps, at most 3 reports per run counted by the run's own
  gate, and failure codes from the pinned list of 12. It is an exception to
  "derived from LocalOnly stays LocalOnly".
- The default is `Off`. Nothing is wired, and no setting or build can turn it on.
  **The net runtime change in this batch is none.**

**Proposed for after wiring, each requiring owner approval:**

- Role-level placement: one run may contain local and remote invocations in
  different role-steps. This amends FR-1.8, FR-1.9 and success criterion 2 (§7.8).
  Sending the RemoteEligible request to the remote Planner is the same privacy
  class and endpoint as today's remote chat, through the same preflight.
- At most one consented local-to-remote Answerer escalation per run.
- Derived progress, when the flag is turned on (D2).

**Not relaxed:**

- LocalOnly content (screen, OCR, accessibility text, local history, memory,
  private tool-result text, images) never leaves.
- Unknown privacy fails closed.
- Critical controls stay with the user.
- `SafetyPolicy` and confirmation rules are unchanged.
- Remote audit holds no raw prompts.
