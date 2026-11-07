# Cloud-edge collaboration: research

Date: 2026-11-07 · Code base: `main@7d5fe1ef` · Status: design history (the owner of
current facts is [spec.md](spec.md); the privacy decision is in
[privacy-derivation-options.md](privacy-derivation-options.md))

## Method and limits

- Solin facts come from a read-only review of the code at the base commit. File
  references point at that commit.
- External patterns come from the excerpts in the task brief. They were not
  re-checked against the papers for this document, so **every external number
  below is as reported, not independently verified**. Each section lists what the lab must measure on
  Solin's own tasks before a design decision depends on it.
- Nothing here changes the current contract. The adaptive-inference
  [status](../20260718-adaptive-edge-inference/status.md) page remains the owner of
  implemented placement behavior.

## Summary

1. The useful split is **by role, not by run**. A cloud model can plan from the
   user's RemoteEligible request alone, while every role that reads or acts on the
   live phone stays on the device. That keeps T6 (protected screens to the cloud)
   closed by construction instead of by a filter.
2. What crosses the boundary must be **closed vocabulary in both directions**:
   milestones from a fixed intent set going down, and enumerated progress going up.
   Free text in either direction reopens T1 (injection) or T4 (exfiltration).
3. Routers and self-verification are weak signals. Escalation from local to cloud
   must rest on signals the lab has calibrated on Solin's own tasks, and must never
   apply to LocalOnly content.

## External patterns

### EcoAgent (AAAI 2026, arXiv 2505.05440)

Reported: a cloud planning agent plus on-device execution and observation agents.
Screenshots stay on the device, and the system reportedly matches fully cloud-based
AndroidWorld success at lower cost and latency.

Implication for Solin: this is the closest model for the Planner / Perceiver /
Executor / Verifier split in [spec.md](spec.md). One part of it is incompatible
with Solin's contract: if the observation agent sends a text description of the
screen back to the cloud planner for replanning, that is device-derived text
leaving the phone. That detail is unverified here. Solin's version, "EcoAgent-lite",
replaces any such text with the closed `RunProgress` enums. They are flag-gated and
Off, and the planner works without them.

Evidence the lab must produce first:
- Success rate on `docs/phone_task_benchmark.json` for three arms: fully local,
  cloud planner with request only, and cloud planner with `RunProgress`. Use a
  paired comparison with 3 runs per task, per `architecture_depth.md`.
- Replan rate and the cause of each replan, by `UiActionFailureKind`.
- If request-only planning is as good as planning with progress, the progress
  relaxation is not worth shipping.

### AdecPilot (arXiv 2604.07767)

Reported: the cloud emits abstract milestones only, and the device grounds them.

Implication: `MilestonePlan` uses a closed set of seven intents, apps from static
profiles, tools from a public allowlist, and query spans copied literally from the
request (`orchestration/collab/MilestonePlan.kt`). The device grounds each
milestone through the existing skill runtime and tool gates. The cloud never names
a screen element.

Evidence first:
- **Vocabulary coverage:** the share of benchmark tasks that the seven intents can
  express without loss. If coverage is low, extend the vocabulary in a reviewed
  change; never allow free-form milestones.
- **Literal-span rate:** how often a correct plan needs a query that is not a
  literal substring of the request (synonyms, translations). The parser rejects
  those today by design.

### AutoMix: cascade with self-verification

Reported: a small model answers first and a larger model is called when
self-verification fails. Self-verification is noisy and needs calibration.

Implication: `EscalationPolicy` accepts only signals listed in
`calibratedSignals`. That set is empty until the lab measures precision for a
specific model pair. Model self-assessment is not one of the signals; all four are
deterministic (`ModelOutputQualityGuard`, schema validators, planner failure,
structural complexity).

Evidence first:
- Per-signal precision and recall against labeled local failures, per model pair.
- Proposed bar: precision ≥ 0.9 on the fixed set before a signal enters
  `calibratedSignals`. Below that bar, escalation becomes approval fatigue.

### RouteLLM, Hybrid LLM, RouterBench

Reported: edge/cloud routing saves 20–40% of cost at a quality drop of 1–4% or
less, but RouterBench finds that simple routers often fail to beat a no-router
baseline.

Implication: do not add a learned router for roles. Role placement reuses the
structural rules of `ModelPlacementPolicy` unchanged (FR-1.7 bans LLM routers for
complexity). The existing `DecisionModelPlacementPolicy` with
`CodivDecisionModelRouter` already conflicts with FR-1.7's wording: it sends the
request text to a third-party model in Shadow or Active mode. Its T9 defense holds,
because it only chooses between placements the rules already allow and only sees
text that passed the sensitivity check. Even so, it is a second egress path and a
second router.

Evidence first:
- On `docs/decision_routing_eval.json`, compare three arms: rules only, Codiv
  Active, and always local. If Codiv does not beat rules only, retire it (owner
  decision in [spec.md](spec.md) §12).

### RedactionBench (arXiv 2606.18782)

Reported: fine-tuned small language models lose to small BERT or GLiNER-style NER
models at PII redaction, and surrogate values are more robust than placeholders.

Implication: a redaction-based relaxation (option C in the memo) would need a
dedicated on-device NER model with its own asset, license, size and latency cost.
Its recall is below 100%, so every miss is a T4 leak. This is the main reason the
memo recommends closed vocabularies (B) over redaction (C).

Evidence first, only if C is ever reconsidered:
- Recall on a fixed Chinese and English PII corpus: phone numbers, ID and card
  numbers, names, addresses, verification codes and app-specific identifiers. Also
  measure on-device latency and the size of the asset.
- A surrogate-consistency test: the same entity must map to the same surrogate
  within a run, and never to a real value.

### HyCo: device-cloud speculative decoding (arXiv 2610.21436)

Reported: the device drafts and the cloud verifies, or the reverse.

Implication: the full context still reaches the cloud, so this is a latency
technique, not a privacy one. It stays out of scope, matching non-goal §13 of the
adaptive-inference spec ("本地生成草稿、远端校正或 speculative decoding").

Evidence first: none for privacy.

### Apple Private Cloud Compute

Reported requirements: stateless computation, enforceable guarantees, no privileged
access, non-targetability, and verifiable transparency.

Implication: Solin's remote endpoint is configured by the user, and Solin cannot
attest to any of the five. Protection therefore has to come from what the device
sends, not from trust in the server. Disclosure text must keep saying that the
endpoint operator sees whatever is sent.

Evidence first: none; this is a documentation constraint.

### GUI-Owl-1.5 (Mobile-Agent-v3.5)

Reported: a GUI agent model family designed for cloud-edge collaboration.

Implication: it is a candidate for the local Executor or Verifier, or for a remote
planner. It does not change the placement contract.

Evidence first:
- License review (`docs/model_license_review.json`).
- LiteRT-LM feasibility and on-device latency.
- Grounding accuracy on the benchmark, compared with the current verified
  observation-action model.

## Existing partial patterns in Solin

| Pattern | Where | What it already does | Gap for role-level collaboration |
| --- | --- | --- | --- |
| Run-level placement | `orchestration/ModelPlacementPolicy.kt:121-198` | 23 reason codes, LocalOnly and unknown privacy eliminate Remote, structural complexity | Decides once per run, for the chat answer only |
| Exclusive binding | `orchestration/RunPlacementBinding.kt:19-80`, `data/SolinDatabaseEntities.kt:158-185` (primary key `runId`) | Atomic, immutable binding per run; Room v18 | One placement per run; a role split needs a key of (run, role, step) |
| Dispatcher CAS | `orchestration/RunPlacementBindingStore.kt:259-308` (`claimForDispatchLocked`), `data/SolinDatabaseDaos.kt:376-481` | Rejects a receipt whose destination differs from the binding | Keyed by `runId` only |
| Trace reconciliation | `eval/AgentBehaviorEvalModels.kt:255-306`, `RunPlacementBindingStore.kt:448` | Exactly one `PlacementSelected`, then sequential receipt/invocation pairs | Rejects any multi-role run |
| Continuation gate | `orchestration/RunPlacementContinuationResolver.kt:15-41` | A Remote run that gains LocalOnly content stops with `PLACEMENT_LOCAL_CONTINUATION_REQUIRED` (FR-1.10) | Correct today; the role model keeps it by never giving the Planner observations |
| Privacy plan | `orchestration/PromptPrivacyPlan.kt:32-127` | Strictest segment wins; null means LocalOnly; an empty plan fails closed; LocalOnly stays sticky | No declassification path, by design |
| Decision router | `orchestration/DecisionModelRouter.kt:92`, `runtime/CodivDecisionModelRouter.kt` | Shadow/Active cloud router with a sensitivity check and remote-send audit | Conflicts with the wording of FR-1.7 |
| Remote vision | `orchestration/RemoteVisionObservationReplanner.kt:161`, `AndroidRemoteVisionDecider.kt` | Cloud sees screenshots; manual Remote mode plus opt-in plus a confirmed first step | Calls the remote runtime directly, bypassing binding and dispatcher; a separately consented exception |
| Local replanner | `orchestration/AgentObservationReplanner.kt:214` (`ModelObservationReplanner`) | Local action model proposes the next step from the observation | Device-bound already; becomes the Executor's local recovery |
| Initial planner | `orchestration/InitialToolPlanner.kt:32` | Rule-first and local-skill-first planning | No milestone input; owned by the local-agent slice |
| Quality guard | `runtime/ModelOutputQualityGuard.kt:58` | Detects repetition, empty or malformed output | The source of `EscalationSignal.QualityGuardTripped` |
| Escalation classifier | commit `4b9c1eb2` (branch `feat/escalation-approval`, not on main) | Forbidden / RequireApproval / Preapproved; pre-approval approves the mechanism, never content | Its three liveness defects are unfixed: the arm point cannot observe cancellation, cannot see session identity, and is not authoritative against stop |

## Evidence checklist before each phase

| Phase in [spec.md](spec.md) §10 | Required evidence |
| --- | --- |
| 2: placement contract | Migration 18→19 instrumented test; every existing placement test green with `(run, Answerer, 0)` |
| 3a–3c and 4: cloud planner, request only | Vocabulary coverage and literal-span rate; benchmark paired comparison against fully local |
| 5: answer escalation | Calibrated precision for at least one signal; the three `4b9c1eb2` liveness defects fixed |
| 6: derived progress | Benchmark uplift of progress over request-only; packet capture showing only the documented fields; independent adversarial fixtures; owner's direct confirmation |
| 7: Auto default | All of the above on at least two physical devices (`docs/phone_acceptance.md`) |
