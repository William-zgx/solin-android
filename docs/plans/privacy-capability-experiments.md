# Privacy-preserving cloud planning and typed device execution

Date: 2026-11-14 · Code baseline: `00a2969f` · Status: **lab / debug experiment**

## Purpose and current boundary

This batch implements the first testable pieces of two research directions: upfront, capability-bound
branch plans for cloud-edge collaboration, and high-confidence typed execution for phone operations.
It does **not** enable a production cloud planner, change model placement, add a privacy bypass, convert a
new GUI model, or claim device acceptance.

- LocalOnly, unknown-privacy fail-closed behavior, confirmation policy and critical-control ownership are
  unchanged.
- `DerivedProgressEgress` remains Off. No screen text, OCR, history, memory or private tool result is sent
  by the new code.
- Release observation behavior is unchanged. The typed replanner is constructed with
  `enabled = BuildConfig.DEBUG` and otherwise returns null.
- P3 full-context data exists only in explicitly declared synthetic fixtures. There is no production
  `FullContext` setting.

## 1. Forward-only branching milestone plans

`orchestration/collab/MilestonePlan.kt` now also contains a lab-only branching contract:

- At most eight nodes, unique IDs, the first node as start, and destinations limited to a later node,
  `done` or `blocked`. Back edges, cycles and unreachable nodes reject the whole plan.
- Embedded actions reuse the canonical `MilestonePlanParser`: closed intents/apps/tools and literal query
  spans remain unchanged.
- The only local predicates are `results_match_query`, `last_action_succeeded`,
  `planned_app_foreground` and `target_exists`. Predicates are evaluated locally; unknown outcomes block.
- `BranchCapabilities` is required from the caller and bounds every intent, app and tool in every branch.
  The parser rejects any node outside that precommitted bound, including Branch Steering attempts toward
  an unapproved app, tool or intent.
- `BranchingPlanEvaluator` exposes only the current action, `Done` or `Blocked`. Callers cannot jump by
  naming a different node, and advancing after a terminal state cannot reopen the plan.
- `BranchingMilestonePlan` is covered by the existing gated-type source scan so another production file
  cannot bypass the validated factory.

No runtime path calls this parser or evaluator. A live request-only cloud-planner comparison must show a
benefit before role/step placement or Room migration is considered.

## 2. High-confidence typed observation candidates

`orchestration/TypedObservationCandidateReplanner.kt` avoids a model call only for two search states:

| State | Required evidence | Proposal |
| --- | --- | --- |
| `input_ready` | A query extracted through the observation-specific canonical skill matcher and exactly one enabled, real Accessibility editable candidate | `ui_type_text` with the node ID, literal query and verification fields |
| `query_typed` | The previous `ui_type_text` used the exact query (and any supplied verification field agrees), plus exactly one enabled, real Accessibility submit candidate | `ui_submit_search` with the query and expected package |

OCR-only candidates, ambiguity, unknown stages, sequential/critical requests, mismatched queries and missing
observations fall through to the existing replanners. Every proposal uses non-model provenance and the
ordinary registry, confirmation, action and egress gates. Verified-search completion remains owned by
`ToolObservationCoordinator`; this class does not duplicate it.

The stage is checked before query extraction, and observation JSON is parsed once per invocation and reused
between stage classification and target resolution.

## 3. Evidence

### Paired saved-output replay

`TypedObservationReplayTest` replays the typed planner before the checked-in `20261108` production-model
answers without calling a model. Only typed answers are replaced; all other answers and baseline scores
remain unchanged.

| Model | Baseline | Typed replay | Fixed | Broken | Calls avoided |
| --- | ---: | ---: | ---: | ---: | ---: |
| E2B | 17/46 | 30/46 | 13 | 0 | 21 |
| E4B | 23/46 | 30/46 | 7 | 0 | 23 |

All 16 typed emissions match the gold labels. See
[`lab/results/20261114-typed-observation/SUMMARY.md`](../../lab/results/20261114-typed-observation/SUMMARY.md).
This is replanner-level saved-output evidence, not full production-path, latency, energy or device evidence.

### Synthetic privacy-utility projections

`lab/scripts/privacy_utility.py` implements P0 local, P1 request+public capabilities, P2 structured
observation and P3 synthetic full-context projections. Public capabilities and structured fields are closed
sets. Every `project()` call revalidates its input; unknown privacy metadata fails closed.

The 12-case saved-answer contract suite covers local successes and failures, argument shapes,
already-complete stops, unsupported targets, PII contexts and Branch Steering. P2 has no Branch Steering
failure in this fixture, while the deliberately less constrained P3 answer has one. These are synthetic
contract outcomes, not model-quality or privacy-risk measurements. See
[`lab/results/20261114-privacy-utility/SUMMARY.md`](../../lab/results/20261114-privacy-utility/SUMMARY.md).

## 4. Next admission gates

1. **Live cloud planning:** compare fully local, request-only sequential and request-only branching plans
   on the same tune/holdout tasks. Record fixed/broken, plan coverage, cloud calls/bytes, cost per success
   and p95 latency. Do not start placement migration without a measured win.
2. **Privacy value:** compare structured observations against consented full visual context on synthetic or
   explicitly authorized test data. If structured evidence is sufficient, do not open raw context. Progress
   egress stays Off unless an additional variant demonstrates a separate benefit and receives owner approval.
3. **Typed execution:** run the full production coordinator on more screens, including dynamic lists,
   ambiguous nodes, critical stops and prompt-injection fixtures. Do not infer production improvement from
   the replanner-only replay alone.
4. **Physical acceptance:** paired device runs must confirm final-state success, steps, latency, memory and
   safety. No connected device was available for this batch.
5. **Later models:** consider a small typed executor or local visual fallback only after deterministic rules
   and existing models leave a measured gap. Model conversion or distillation is not a prerequisite for the
   current experiments.

Validation commands and final integrated results are recorded in
[`docs/validation_report.md`](../validation_report.md).
