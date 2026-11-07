# Decision memo: may device-derived content reach the cloud planner?

Date: 2026-11-07 · Base: `main@7d5fe1ef` · Status: **awaiting the owner's direct
decision**

- Design: [spec.md](spec.md) §6 (derived progress egress) and §12 (decisions).
- Research: [research.md](research.md).
- Threat model: [architecture_depth.md](../../architecture_depth.md).

## Decision record

| Item | State |
| --- | --- |
| Question | May anything derived from LocalOnly data (screen, OCR, accessibility text, tool results) reach a remote Planner? |
| Recommendation | Option A as the default; option B as the only opt-in, behind a flag that is Off in every build |
| Relayed approval | The integrator relayed that the owner replied "allowed to proceed per your recommendations". It is recorded here as direction for this batch. **It is not the owner's direct confirmation for enabling or wiring the flag** |
| Implemented | Option B as pure code: `RunProgressGate` and `CloudPlannerRequest` in `orchestration/collab/MilestonePlan.kt`, with adversarial tests. `DerivedProgressEgress.DEFAULT` is `Off`. No runtime path calls it, and no setting or build type can turn it on |
| Not implemented | Options C and D; any setting, wiring, disclosure line or audit event for B |
| Before the flag is wired or enabled | The owner confirms directly, and the Phase 6 evidence in [spec.md](spec.md) §10 exists |

Until the owner decides, the product contract is unchanged: content derived from
LocalOnly stays LocalOnly (`PromptPrivacyPlan.kt:32-127`).

## Context

A cloud Planner sees only the RemoteEligible request and the public vocabulary
(spec §4). When a milestone fails on the device, a better plan might need to know
*how* it failed. Every possible answer to that question is derived from the
screen or from tool results, and both are LocalOnly today.

The non-negotiables in `AGENTS.md` that bound this choice:

1. LocalOnly by default. Screen text, OCR and private tool results stay on the
   device unless the user explicitly chooses a remote path. Unknown privacy fails
   closed.
2. Fail closed on unclear privacy: refuse, keep local, or ask.
3. Critical actions stay with the user (not affected by any option below).
4. No raw prompts in remote audit.

## Options

### A. Never send device-derived content

The remote Planner gets the request and the vocabulary once. After a failed
milestone the run recovers locally (`ModelObservationReplanner`), asks the user,
or answers with what it has. It never calls the remote Planner again, because a
second call would carry no new information.

- **Threats addressed:** T4, T6 and T7 are closed for the planner path by
  construction. No covert channel exists.
- **Residual risk:** none beyond today's remote chat. The request is the same
  privacy class and endpoint, through the same preflight.
- **Non-negotiables touched:** none.
- **Audit:** the existing remote-send audit event for the planner send.
- **Cost:** the cloud cannot help after the first failure, so complex tasks may
  succeed less often than with B.
- **Measure first:** benchmark success of the request-only planner against fully
  local planning (`docs/phone_task_benchmark.json`, paired, 3 runs per task); the
  share of runs that fail after a milestone failure.

### B. Closed-vocabulary abstractions only

After a failed milestone, the device may send an enumerated progress report, and
nothing else (spec §6.1):

| Field | Values |
| --- | --- |
| `milestones` | `pending` / `active` / `reached` / `failed`, one per plan milestone (at most 8) |
| `lastStatus` | `succeeded` / `failed` / `rejected` / `cancelled` / `null` |
| `lastFailure` | One of the 12 `UiActionFailureKind` schema values, or `null`. Any other code becomes `unknown` |
| `foreground` | `planned_app` / `other_app` / `unknown`. Never the package name |
| `stepsUsed` | 0..32 |

At most 3 reports per run, only to a Planner the run already placed Remote on the
RemoteEligible request alone.

- **Threats addressed:**
  - T4 and T6: no text, screen element, package name or tool-result value is
    read, so none can leave. `RunProgress` has no `String` field (reflection
    test).
  - T1: nothing the screen says reaches the planner as text, so screen content
    cannot inject instructions into the plan.
  - T7: audit summaries use enum names only.
  - T9: the gate refuses unless the Planner placement was already Remote with no
    role constraint.
- **Residual risk:**
  - A covert channel (new threat T10). At most about 28.6 bits per report and 86
    bits per run (spec §6.3). An app on the screen can only influence them
    indirectly, by changing what Solin's own executors observe.
  - Coarse inference about the session. For example, `other_app` says the user
    left the planned app; `permission_missing` says a permission is not granted;
    `dangerous_action` says the run reached a critical control.
- **Non-negotiables touched:**
  - 1: an explicit, bounded exception to "derived from LocalOnly stays
    LocalOnly", only when the user turns the flag on and chooses a remote Planner.
  - 2: kept. An unknown failure code becomes `unknown`; a malformed record or an
    exhausted budget is withheld; the flag defaults to Off.
  - 4: kept. Audit records the release count and enum names only.
- **Audit:** one `RemoteSendAuditEvent` per release with an enum-only summary; a
  `derivedProgressReleased` flag on the run receipt; trace reconciliation bounds
  releases to 3 per run (spec §7.4, §7.7).
- **Measure first:** benchmark uplift of B over A on the same tasks (if B does not
  beat A, do not ship it); packet capture showing that only the documented fields
  leave; an independent adversarial fixture set; a review of the covert-channel
  bound.

### C. Text redacted by a dedicated NER model, with surrogate values

The device sends free text (for example a screen summary) after an on-device NER
model replaces detected entities with consistent surrogates.

- **Threats addressed:** T4 and T6 partly, up to the redactor's recall.
- **Residual risk:**
  - Every missed entity leaks (T4). Recall is below 100%, and RedactionBench
    reports that even dedicated NER models miss entities (research.md).
  - App-specific identifiers, order numbers and free-form personal details are
    hard to classify at all.
  - Free text reopens T1: screen text could steer the cloud planner.
- **Non-negotiables touched:**
  - 1: free text derived from LocalOnly leaves the device.
  - 2: a model redactor cannot fail closed on content it does not recognise. The
    integrator's constraint ("fail closed on unknown or unclassified content")
    would require withholding every token not positively classified as safe.
    That is a closed vocabulary, so C under that constraint collapses into B.
- **Audit:** the surrogate mapping must stay local; summaries may count
  redactions by category only; remote audit must store neither the original nor
  the redacted text.
- **Cost:** a new model asset with license, size, latency and update obligations.
- **Measure first, only if C is reconsidered:** recall on a fixed Chinese and
  English PII corpus; surrogate consistency within a run; red-team leak rate;
  on-device latency and asset size.

### D. Per-run approval of an exact preview

Before any device-derived text leaves, the user sees the exact bytes and approves
them for this run.

- **Threats addressed:** in principle T4 and T6, if the user reads every preview.
- **Residual risk:**
  - Approval fatigue. A phone task may need several previews, and users tend to
    approve without reading.
  - A preview of screen text can hide sensitive content in a form the user does
    not recognise (identifiers, codes).
  - Free text reopens T1.
- **Non-negotiables touched:**
  - 1: fits the letter ("unless the user explicitly chooses a remote path") but
    moves the protection from code to user attention.
  - 4: the preview text must never be stored in remote audit.
- **Audit:** record the approve or decline decision, the byte count and the
  sensitive-content categories; never the preview text.
- **Measure first:** in a user study, the approval rate and time per preview, the
  rubber-stamp rate on previews seeded with fake PII, and the number of previews
  needed per run.

## Comparison

| | A | B | C | D |
| --- | --- | --- | --- | --- |
| Free text leaves the device | No | No | Yes, redacted | Yes, approved |
| Protection rests on | Code | Code (closed types) | Model recall | User attention |
| Fails closed on unknown content | Yes | Yes | No | Only if the user declines |
| Bits per run | 0 | ≤ about 86 | Unbounded | Unbounded |
| New asset or UI | None | One disclosure line and a setting | NER model | Preview sheet per send |
| Non-negotiable 1 | Unchanged | Bounded exception | Exception | Exception by consent |

## Recommendation

1. **Keep A as the default** in every build. It needs no policy change.
2. **Allow B as the only opt-in**, after the Phase 6 evidence and the owner's
   direct confirmation. Start in debug builds only. It is the only option that
   keeps fail-closed behavior in code and puts a hard bound on what leaves.
3. **Reject C** for now. It cannot fail closed, adds a model asset, and under the
   integrator's constraint it reduces to B.
4. **Reject D** as a planner channel. It keeps free text and relies on user
   attention for every report. Per-send disclosure stays where it already is: the
   initial remote send.

## Decisions requested of the owner

| # | Decision | Recommended answer |
| --- | --- | --- |
| D1 | Which option governs device-derived content for the cloud planner | A by default; B as the only opt-in |
| D2 | When the B flag may be wired and enabled | Not before Phase 6 evidence; then debug builds first; never on by default |
| D2a | Whether the relayed approval covers enabling the flag | No. It covers this batch's unwired, Off-by-default implementation only |

Exactly what this batch relaxes: in code, only with `DerivedProgressEgress` set to
`ClosedVocabulary`, the five enumerated fields above may reach a remote Planner,
as an exception to "derived from LocalOnly stays LocalOnly". The default is `Off`,
nothing is wired, and no setting or build can change it, so the runtime contract
is unchanged.
